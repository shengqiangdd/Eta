package io.github.mangi.eta.agent.acp

import android.os.Handler
import android.os.HandlerThread
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.terminal.TerminalEnvEntry
import io.github.mangi.eta.agent.terminal.TerminalEnvRules
import io.github.mangi.eta.core.AgentLogger
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * ACP 客户端：在 Linux 环境里拉起外部编码代理，按 JSON-RPC over stdio 对话。
 *
 * 实现要点（对应 memory §160「ACP 接入」）：
 * 1. **进程**：通过 `su -c` 进入 Eta 的 Linux rootfs（chroot），
 *    代理的 stdout/stdin 就是 ACP 通道；stderr 单独收（代理的日志噪声都在那里）。
 * 2. **协议**：一行一条 JSON-RPC。先 `initialize`，再 `session/new`，再 `session/prompt`。
 *    `session/update` 是 notification（无 id），持续回流文本增量与工具调用。
 * 3. **权限**：代理会发 `session/request_permission`（有 id 的 request）。
 *    这里**默认自动允许只读类、拒绝写文件/执行命令类**，
 *    因为 Eta 无法在工具调用中弹窗问用户；宁可让代理停下来报告，也不能替用户决定落盘。
 * 4. **收尾**：进程、读线程、HandlerThread 全部显式回收；close 幂等。
 */
/**
 * 一次 ACP 启动需要的环境：会话标记（非机密）+ 凭据（机密）。
 *
 * 拆成两组是为了让机密只走 0600 脚本，而标记仍可内联——收尾靠标记扫环境变量
 * 找出整棵进程树（Android 的 Process 没有 pid，destroyForcibly 只杀 su 那一层）。
 */
internal data class AcpLaunchEnv(
    val marker: TerminalEnvEntry,
    val secrets: List<TerminalEnvEntry>,
)

internal fun acpLaunchEnv(
    sessionId: String,
    provider: AcpProvider.Config?,
    credentialEnvKeys: List<String>,
    env: (String) -> String? = { System.getenv(it) },
): AcpLaunchEnv {
    val secrets = buildList {
        provider?.apiKey?.takeIf { it.isNotBlank() }
            ?.let { add(TerminalEnvEntry(AcpProvider.ENV_KEY, it)) }
        credentialEnvKeys.forEach { key ->
            env(key)?.takeIf { it.isNotBlank() }?.let { add(TerminalEnvEntry(key, it)) }
        }
    }
        // 同名键只留第一次出现的值：脚本按行 export，后写的会覆盖前写的。
        // provider 里配的 key 必须压过环境里同名的旧变量，否则用户改了设置却仍在用旧 key。
        .distinctBy { it.key }
    // 与 AcpOrphanReaper 的 marker 逐字一致，改一边必须改另一边。
    return AcpLaunchEnv(TerminalEnvEntry("ETA_ACP_SESSION", sessionId), secrets)
}

/** 标记内联 + 机密只给脚本路径。 */
internal fun acpEnvPrefix(marker: TerminalEnvEntry, script: String?): String =
    "export ${marker.key}=${AcpProvider.shellQuote(marker.value)}; " + (script?.let { "$it; " } ?: "")

internal class AcpClient(
    private val logger: AgentLogger,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
    /**
     * 把一条命令包成「在 Eta 的 Linux 工具环境里执行」的形式（chroot / proot 由调用方决定）。
     * 返回 null 表示环境未就绪。
     *
     * 为什么必须注入：`/opt/eta/node/...` 是 guest 视角的路径，宿主上根本不存在。
     * 原实现直接在 App 进程里 `File(...).canExecute()`，结论恒为 false ——
     * 于是 `acp_agent_list` 一直说「未安装」，`acp_delegate` 一直回 ACP_AGENT_NOT_INSTALLED，
     * 而代理其实可能装得好好的。启动进程同理：必须先进环境再 exec。
     */
    private val linuxShell: (String) -> String? = { null },
    /**
     * 把凭据写成「guest 内的一次性 0600 脚本」并返回可直接拼进命令的 source 片段。
     * 返回 null 表示环境未就绪——调用方必须**中止**，不能回退成拼值。
     */
    private val envScript: (List<TerminalEnvEntry>) -> String? = { null },
) {
    data class Agent(
        val id: String,
        val title: String,
        val executable: String,
        /** 在 Linux 环境内**实际探测到**的路径；空串表示没找到。 */
        val resolvedPath: String,
        /** 路径来源：explicit / glob / path / missing。 */
        val source: String,
        val credentialEnvKeys: List<String>,
        val credentialPresent: Boolean,
        val installed: Boolean,
    )

    data class Session(
        val id: String,
        val agentId: String,
        var acpSessionId: String,
        val cwd: String,
        val createdAtMs: Long,
        val taskPreview: String,
    ) {
        val ageSeconds: Int get() = ((System.currentTimeMillis() - createdAtMs) / 1000).toInt()
    }

    /**
     * 委派过程中的进度观察者。
     *
     * 为什么需要：`delegate()` 是阻塞调用，整个代理回合在它返回前对外界不可见。
     * 只有把 `session/update` 的每个增量抛出来，界面才能在工具卡片上显示
     * "正在读取 X / 正在执行命令"之类的实时进展，而不是一张静止的转圈卡片。
     *
     * 调用线程是 ACP 读线程（非主线程），实现方必须自行切线程；
     * 回调必须**不抛异常**（抛了会被读循环的 runCatching 吞掉，表现为进度中断）。
     */
    fun interface ProgressObserver {
        fun onProgress(kind: String, detail: String)
    }

    private class Live(
        val session: Session,
        val process: Process,
        val reader: BufferedReader,
        val writer: BufferedWriter,
        val thread: HandlerThread,
        val stopped: AtomicBoolean = AtomicBoolean(false),
    ) {
        val text = StringBuilder()
        val tools = StringBuilder()
        /** 「这次委派到底做了什么」的结构化汇总（步骤 / token / plan）。 */
        val stats = AcpUpdateAccumulator()
        /** 思考增量字数：只在结果里给个规模，不进正文（否则正文会被內部独白淹没）。 */
        var thoughtChars = 0
        /**
         * 代理请求过、而 Eta 没有实现的能力方法名（fs 读文件、terminal 执行等）。
         *
         * 记下来的理由：这类请求是「卡住」的高发原因 —— 回错格式代理不会降级，
         * 只会一直等。把方法名带进结果，下次看结果就知道卡在哪一步，
         * 而不是只有一个 ACP_TIMEOUT。
         */
        val unsupported = LinkedHashSet<String>()
        /** 代理往 stderr 写的东西：启动失败、崩溃原因都在这里。 */
        val stderr = StringBuilder()
        var finished = false
        var stopReason: String? = null
        var error: String? = null
        /** 本次委派请求的模型（null = 用代理默认）。 */
        var requestedModel: String? = null
        /**
         * 写进临时 CODEX_HOME 的 config.toml 的模型（null = 没生成配置）。
         *
         * 与 [requestedModel] 相同就不要再去 `session/set_model`：“配置里已经写好了”，
         * 再切一次不但没用，还会把本来能跑的会话弄失败 —— 真机上 codex-acp 的可用清单
         * 是 `gpt-5.6-sol[low]` 这种带档位的形式，而 config 里的 `gpt-5.6-sol` 本来就能用，
         * 切到带档位的反而回 Internal error（2026-10-28）。
         */
        var configModel: String? = null
        /** 本次委派显式指定的 Eta 提供商 id；null = 用代理自己的默认配置。 */
        var providerId: String? = null
        /** `session/new` 报的当前模型。 */
        var defaultModel: String? = null
        /** 设置成功后实际生效的模型。 */
        var activeModel: String? = null
        /** 代理报的可用模型清单（真机 codex-acp：41 个）。 */
        var availableModels: List<String> = emptyList()
        /** 本次委派的进度观察者；null 表示调用方不关心过程（例如 acp_session_status）。 */
        var progress: ProgressObserver? = null
    }

    private val live = ConcurrentHashMap<String, Live>()

    /** stderr 读线程登记表：teardown 时才知道该等谁，不在 Live 里塞可变引用。 */
    private val stderrPumpRef = ConcurrentHashMap<String, Thread>()
    private val closed = AtomicBoolean(false)

    /**
     * 插件注册进来的外部 ACP 代理（来自 `eta-plugin.json` 的 `acp` 段）。
     *
     * 与内置代理同权：安装插件即出现在 `acp_agent_list` 里、可被 `acp_delegate` 选用。
     * 用插件 id 前缀命名空间，避免与内置 id 或其它插件的代理撞名。
     */
    private val externalAgents = ConcurrentHashMap<String, AgentSpec>()

    /** 由插件运行时在安装\/卸载时调用，保持代理清单与已启用插件一致。 */
    fun replaceExternalAgents(agents: List<ExternalAgent>) {
        externalAgents.clear()
        agents.forEach { agent ->
            externalAgents[agent.id] = AgentSpec(
                id = agent.id,
                title = agent.title,
                executable = agent.executable,
                // 插件可以写通配（例如它自己管的版本目录）；不含 `*` 就按精确路径。
                executableGlobs = if (agent.executable.contains('*')) listOf(agent.executable) else emptyList(),
                commandNames = emptyList(),
                credentialEnvKeys = agent.credentialEnvKeys,
            )
        }
        // 代理清单变了，缓存里的探测结果一并作废：否则刚装的插件代理会显示成「未安装」。
        resolutions.clear()
        logger.info(
            "ACP external agents refreshed count=${externalAgents.size} " +
                "ids=${externalAgents.keys.sorted().joinToString(",")}"
        )
    }

    /** 插件声明的一个外部代理。 */
    data class ExternalAgent(
        val id: String,
        val title: String,
        val executable: String,
        val credentialEnvKeys: List<String>,
    )

    /** 已配置的代理清单。凭据是否存在只做「环境变量是否非空」的存在性判断，不读内容。 */
    fun agents(): List<Agent> = (BUILTIN_AGENTS + externalAgents.values).map { spec ->
        val envPresent = spec.credentialEnvKeys.any { key ->
            System.getenv(key)?.isNotBlank() == true
        }
        val resolution = resolveAgent(spec)
        Agent(
            id = spec.id,
            title = spec.title,
            executable = spec.executable,
            resolvedPath = resolution.path,
            source = resolution.source.name.lowercase(),
            credentialEnvKeys = spec.credentialEnvKeys,
            credentialPresent = spec.credentialEnvKeys.isEmpty() || envPresent ||
                credentialFilePresent(spec),
            installed = resolution.path.isNotEmpty(),
        )
    }.sortedBy { it.id }

    /** 默认代理优先选**真的装好了**的：列表里排第一但没装的，不该是默认值。 */
    fun defaultAgentId(): String {
        val all = agents()
        return all.firstOrNull { it.installed }?.id ?: all.firstOrNull()?.id ?: "codex"
    }

    fun sessions(): List<Session> = live.values.map { it.session }.sortedBy { it.createdAtMs }

    /**
     * 委派一个任务并阻塞等待完成。
     * @return Pair(是否成功, 结果 JSON 字符串)
     */
    fun delegate(
        agentId: String,
        task: String,
        cwd: String,
        timeoutSeconds: Int,
        model: String? = null,
        provider: AcpProvider.Config? = null,
        onProgress: ProgressObserver? = null,
    ): Pair<Boolean, String> {
        val spec = BUILTIN_AGENTS.firstOrNull { it.id == agentId }
            ?: externalAgents[agentId]
            ?: return false to errorJson("ACP_AGENT_UNKNOWN", "未知 ACP 代理：$agentId")
        if (!rootAvailable()) {
            return false to errorJson("ROOT_REQUIRED", "ACP 委派需要 Root 以进入 Linux 环境")
        }
        // App 重启不会带走上一代留下的代理进程：每次委派前先扫一遍（每个进程只扫一次）。
        AcpOrphanReaper.reapOnce()
        val resolution = resolveAgent(spec)
        if (resolution.path.isEmpty()) {
            return false to errorJson(
                "ACP_AGENT_NOT_INSTALLED",
                "在 Linux 工具环境里没有找到代理可执行文件。候选：" +
                    candidateList(spec).joinToString("、") + "；请先在该环境安装（或放到这些位置之一）",
            )
        }
        // 空列表 = 这个代理不需要凭据（例如本地脚本）。旧写法用 none{}，
        // 空列表会返回 true，于是「不需要凭据」被当成「缺凭据」，永远无法委派。
        val envPresent = spec.credentialEnvKeys.any { System.getenv(it)?.isNotBlank() == true }
        // 走 Eta 提供商时，key 来自内存里的 ProviderSetting 而不是环境变量/auth.json，
        // 不能再用旧判据把「Eta 已经有 key」误判成缺凭据。
        val credentialMissing = provider == null && spec.credentialEnvKeys.isNotEmpty() &&
            !envPresent && !credentialFilePresent(spec)
        if (credentialMissing) {
            return false to errorJson(
                "ACP_CREDENTIAL_MISSING",
                "缺少凭据：请设置 ${spec.credentialEnvKeys.joinToString(" 或 ")}，" +
                    "或在该环境里完成一次代理登录（例如 codex login）后重试",
            )
        }
        val launched = runCatching { launch(spec, resolution.path, task, cwd, model, provider) }
        if (launched.isFailure) {
            val reason = launched.exceptionOrNull()?.message ?: "无法启动代理进程"
            return false to errorJson("ACP_LAUNCH_FAILED", reason)
        }
        val handle = launched.getOrThrow()
        // 观察者在 awaitCompletion 之前挂上：代理的 initialize 往返就可能产出数百毫秒空窗，
        // 早挂一秒，用户就少一秒对着静止的卡片猜"它到底动没动"。
        handle.progress = onProgress
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + timeoutSeconds * 1000L
        try {
            awaitCompletion(handle, deadline, startedAt)
        } finally {
            // 无论成功、超时还是异常，都不能把进程和线程留在系统里。
            // 同时从 live 里摘掉：委派是阻塞调用，返回时结果已经交给调用方，
            // 再留在 map 里只会让 acp_agent_list 的 sessions 无限增长（旧实现就是这样）。
            live.remove(handle.session.id)
            teardown(handle)
        }
        val elapsed = System.currentTimeMillis() - startedAt
        val ok = handle.finished && handle.error == null
        val result = JSONObject()
            .put("ok", ok)
            .put("tool", "acp_delegate")
            .put("session_id", handle.session.id)
            .put("agent", spec.id)
            .put("provider", provider?.id ?: JSONObject.NULL)
            // 用户问的就是这个：这次委派用的是哪个模型。
            .put("model", handle.activeModel ?: handle.defaultModel ?: JSONObject.NULL)
            .put("model_requested", handle.requestedModel ?: JSONObject.NULL)
            .put("available_models", AcpModels.availableJson(AcpModels.Info(handle.defaultModel, handle.availableModels)))
            .put("cwd", cwd)
            .put("elapsed_ms", elapsed)
            .put("stop_reason", handle.stopReason ?: JSONObject.NULL)
            .put("text", handle.text.toString().take(MAX_RESULT_CHARS))
            .put("tool_activity", handle.tools.toString().take(MAX_TOOL_CHARS))
            .put("stderr_tail", stderrTail(handle))
            // 结构化回传：不只给一句正文，而是「跑了哪几步、每步多久、用了多少上下文」。
            // 真机（2026-10-27）：以前 90 秒、两步的委派在界面上只剩「完成」两个字。
            .put("steps", handle.stats.stepsJson())
            .put(
                "usage",
                JSONObject()
                    .put("used", handle.stats.usageUsed)
                    .put("size", handle.stats.usageSize),
            )
            .put("plan", JSONObject().put("done", handle.stats.planDone).put("total", handle.stats.planTotal))
            .put("thought_chars", handle.thoughtChars)
            // 代理请求过但 Eta 没实现的能力：超时 / 卡住时这是最直接的线索。
            .put(
                "unsupported_requests",
                JSONArray().also { array ->
                    // 拷贝一份再写：不在持有集合锁时调 JSONArray，避免将来改成回调时死锁。
                    val methods = synchronized(handle.unsupported) { handle.unsupported.toList() }
                    methods.forEach { method -> array.put(method) }
                },
            )
        if (!ok) {
            result.put("code", if (handle.error != null) "ACP_PROTOCOL_ERROR" else "ACP_TIMEOUT")
            val detail = handle.error ?: "超过 ${timeoutSeconds}s 未完成，已终止代理进程"
            result.put("message", withStderrHint(detail, stderrTail(handle)))
        }
        return ok to result.toString()
    }

    fun status(sessionId: String): Pair<Boolean, String> {
        val handle = live[sessionId]
            ?: return false to errorJson("ACP_SESSION_NOT_FOUND", "会话不存在或已结束：$sessionId")
        return true to JSONObject()
            .put("ok", true)
            .put("tool", "acp_session_status")
            .put("session_id", sessionId)
            .put("agent", handle.session.agentId)
            .put("provider", handle.providerId ?: JSONObject.NULL)
            .put("model", handle.activeModel ?: handle.defaultModel ?: JSONObject.NULL)
            .put("age_seconds", handle.session.ageSeconds)
            .put("finished", handle.finished)
            .put("stop_reason", handle.stopReason ?: JSONObject.NULL)
            .put("text", handle.text.toString().take(MAX_RESULT_CHARS))
            .put("tool_activity", handle.tools.toString().take(MAX_TOOL_CHARS))
            .put("stderr_tail", stderrTail(handle))
            .put("steps", handle.stats.stepsJson())
            .put(
                "usage",
                JSONObject()
                    .put("used", handle.stats.usageUsed)
                    .put("size", handle.stats.usageSize),
            )
            .toString()
    }

    fun closeSession(sessionId: String): Pair<Boolean, String> {
        if (sessionId.equals("all", ignoreCase = true)) {
            val count = live.keys.toList().count { id -> live.remove(id)?.let { teardown(it) }; true }
            return true to JSONObject()
                .put("ok", true)
                .put("tool", "acp_session_close")
                .put("closed", count)
                .put("id", "all")
                .toString()
        }
        val handle = live.remove(sessionId)
            ?: return false to errorJson("ACP_SESSION_NOT_FOUND", "会话不存在或已结束：$sessionId")
        teardown(handle)
        return true to JSONObject()
            .put("ok", true)
            .put("tool", "acp_session_close")
            .put("closed", 1)
            .put("id", sessionId)
            .toString()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        live.keys.toList().forEach { id -> live.remove(id)?.let { teardown(it) } }
    }

    private data class AgentSpec(
        val id: String,
        val title: String,
        val executable: String,
        /** 通配候选（例如多版本目录 `/opt/eta/node/<ver>/bin/codex-acp`）。 */
        val executableGlobs: List<String> = emptyList(),
        /** PATH 里的命令名，例如 `codex-acp`。 */
        val commandNames: List<String> = emptyList(),
        val credentialEnvKeys: List<String>,
        /**
         * 代理自己的登录态文件（guest 视角绝对路径）。存在且非空即视为凭据可用。
         *
         * codex-cli 支持 `codex login`，登录态写在 `~/.codex/auth.json`；
         * 这种情况下环境变量里什么都没有，只看 env 会一直报「缺少供应商凭证」，
         * 而代理其实已经能用 —— 真机验收时就撞上了这个（auth.json 在、delegate 仍被拒）。
         */
        val credentialPaths: List<String> = emptyList(),
    )

    private fun candidateList(spec: AgentSpec): List<String> =
        (listOf(spec.executable) + spec.executableGlobs + spec.commandNames).filter { it.isNotBlank() }

    private data class CachedResolution(
        val resolution: AcpExecutableResolver.Resolution,
        val atMs: Long,
    )

    private data class CachedCredential(
        val present: Boolean,
        val atMs: Long,
    )

    /** 探测缓存：UI 会轮询 `acp_agent_list`，而一次探测要进环境跑 sh，不该每次都付。 */
    private val resolutions = ConcurrentHashMap<String, CachedResolution>()

    /** 登录态文件的探测缓存；与路径探测同样是为了挡住轮询。 */
    private val credentials = ConcurrentHashMap<String, CachedCredential>()

    /**
     * 在 Linux 环境内解析代理路径（显式 → 通配 → PATH）。
     *
     * 缓存 [RESOLUTION_TTL_MS]；环境未就绪或没有 Root 时返回空结果，
     * 不做「猜一个路径」的兜底 —— 猜出来的结论只会让用户以为装好了。
     */
    private fun resolveAgent(spec: AgentSpec): AcpExecutableResolver.Resolution {
        val now = System.currentTimeMillis()
        resolutions[spec.id]?.let { cached ->
            if (now - cached.atMs < RESOLUTION_TTL_MS) return cached.resolution
        }
        val plan = AcpExecutableResolver.plan(
            explicit = listOf(spec.executable),
            globs = spec.executableGlobs,
            commands = spec.commandNames,
        )
        val resolution = AcpExecutableResolver.resolution(plan, probe(plan))
        resolutions[spec.id] = CachedResolution(resolution, now)
        return resolution
    }

    /**
     * 代理是否已经通过「自己的登录态文件」拿到凭据。
     *
     * 只看环境变量是不够的：`codex login` 把登录态写进 `~/.codex/auth.json` 之后，
     * 环境里依然没有任何 key，于是界面显示「缺少供应商凭证」、委派直接被拒 ——
     * 用户明明已经登录过，却被告知去配环境变量。这里把登录态文件也算作凭据来源。
     *
     * 判定只用 `[ -s 文件 ]`（存在且非空），**不读内容**：凭据不该被应用解析或外泄。
     */
    private fun credentialFilePresent(spec: AgentSpec): Boolean {
        if (spec.credentialPaths.isEmpty() || !rootAvailable()) return false
        val now = System.currentTimeMillis()
        credentials[spec.id]?.let { cached ->
            if (now - cached.atMs < CREDENTIAL_TTL_MS) return cached.present
        }
        val quoted = spec.credentialPaths.joinToString(" ") { "\"$it\"" }
        val inner = "for p in $quoted; do if [ -s \"\$p\" ]; then echo ETA_CRED_OK; break; fi; done"
        val wrapped = linuxShell(inner) ?: return false
        val present = runCatching {
            val process = ProcessBuilder("su", "-c", wrapped)
                .redirectErrorStream(false)
                .start()
            val out = StringBuilder()
            val pump = Thread {
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { line -> out.append(line).append('\n') }
                }
            }.apply {
                isDaemon = true
                name = "eta-acp-credential"
                start()
            }
            val finished = runCatching {
                process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
            if (!finished) {
                runCatching { process.destroyForcibly() }
                pump.join(200)
                return@runCatching false
            }
            pump.join(300)
            out.contains(ETA_CREDENTIAL_OK)
        }.getOrDefault(false)
        credentials[spec.id] = CachedCredential(present, now)
        return present
    }

    /**
     * 在环境内执行探测脚本，返回它输出的第一个路径。
     *
     * **stderr 不合并**：合并后 `command -v` 的报错行会被当成「探到的路径」，
     * 于是界面显示「已安装」，委派时却 exec 一个不存在的文件。
     *
     * **stdout 必须异步读**：旧实现是 `readText()` 之后才 `waitFor(timeout)`，
     * 而 readText 会阻塞到 EOF —— su 卡住（等授权、chroot 挂住）时
     * 这个 4 秒超时永远不会被执行，整条工具调用线程被永久占住。
     */
    private fun probe(plan: AcpExecutableResolver.Plan): String {
        if (plan.isEmpty || !rootAvailable()) return ""
        val wrapped = linuxShell(AcpExecutableResolver.probeScript(plan)) ?: return ""
        return runCatching {
            val process = ProcessBuilder("su", "-c", wrapped)
                .redirectErrorStream(false)
                .start()
            val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
            val pump = Thread {
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { line -> lines.add(line) }
                }
            }.apply {
                isDaemon = true
                name = "eta-acp-probe"
                start()
            }
            val finished = runCatching {
                process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
            if (!finished) {
                runCatching { process.destroyForcibly() }
                pump.join(200)
                logger.info("ACP probe timeout ms=$PROBE_TIMEOUT_MS wrapped=${wrapped.take(160)}")
                return@runCatching ""
            }
            pump.join(300)
            // 只取最后一行：环境启动脚本自己可能打印一行提示。
            lines.lastOrNull { it.isNotBlank() }.orEmpty().trim()
        }.getOrDefault("")
    }

    /**
     * 把代理 stderr 里最能说明问题的那句话带进错误信息。
     *
     * 代理回的 JSON-RPC 错误常常只有一句 `Internal error`，真正的原因只写在 stderr：
     * 真实例子是 `The 'gpt-5.5' model is not supported when using Codex with a ChatGPT account`。
     * 用户看到「Internal error」根本无从下手 —— 错误信息本身必须可操作。
     */
    private fun withStderrHint(message: String, stderr: String): String {
        if (stderr.isBlank()) return message
        // stderr 里的 JSON 会被转义成 \" ，先还原再匹配，省掉两套正则。
        val normalized = stderr.replace("\\\"", "\"")
        val hint = Regex("\"message\"\\s*:\\s*\"([^\"]{8,400})\"")
            .findAll(normalized)
            .lastOrNull()
            ?.groupValues
            ?.get(1)
            ?: Regex("(?m)^.*(?:ERROR|error:).*$").findAll(normalized).lastOrNull()?.value?.take(400)
            ?: return message
        val clean = hint.replace("\\n", " ").trim()
        if (clean.isEmpty() || message.contains(clean)) return message
        return "$message（代理侧：$clean）"
    }

    /** stderr 只保留尾部：代理可能刷几十万行日志，报错永远在最后几行。 */
    /**
     * stderr 尾部。
     *
     * 正常结束时滤掉「我们自己 kill 出来的噪声」：teardown 用 destroyForcibly 收进程树，
     * ash/busybox 会往 stderr 写 `Terminated`，于是**成功**的委派结果里永远挂着
     * `stderr_tail: Terminated`（真机 2026-10-28 每次都是），看着像出错。
     * 失败时原样返回 —— 那时每一行都可能是线索。
     */
    private fun stderrTail(handle: Live): String {
        val raw = synchronized(handle.stderr) { handle.stderr.toString() }
        return AcpStderrTail.tail(
            raw = raw,
            finishedOk = handle.finished && handle.error == null,
            maxChars = MAX_STDERR_CHARS,
        )
    }

    private fun launch(
        spec: AgentSpec,
        executablePath: String,
        task: String,
        cwd: String,
        model: String? = null,
        provider: AcpProvider.Config? = null,
    ): Live {
        // 每次委派都是一个真实进程 + 一个 HandlerThread；没有上限时并发委派会把内存和 PID 一起吃光。
        if (live.size >= MAX_LIVE_SESSIONS) {
            throw IllegalStateException(
                "同时进行的 ACP 会话已达上限 $MAX_LIVE_SESSIONS；先 acp_session_close 或等待当前委派结束",
            )
        }
        val sessionId = "acp${System.currentTimeMillis().toString(36)}"
        val thread = HandlerThread("eta-acp-$sessionId").apply { start() }
        val handler = Handler(thread.looper)
        val effectiveModel = model?.trim()?.takeIf { it.isNotEmpty() }
            ?: provider?.defaultModel?.trim()?.takeIf { it.isNotEmpty() }
        if (provider != null && effectiveModel == null) {
            throw IllegalStateException("指定了 Eta 提供商，但该提供商没有可用模型")
        }
        // 代理在 Linux rootfs 里运行：cwd 与可执行路径都按容器内视角给。
        val innerScript = buildString {
            // 只有走 Eta 提供商时才生成临时 CODEX_HOME；不碰用户全局 ~/.codex/config.toml。
            provider?.let { append(AcpProvider.launchScript(sessionId, it, requireNotNull(effectiveModel))) }
            append("cd ").append(shellQuote(cwd)).append(" 2>/dev/null || cd /workspace; ")
            append("exec ").append(shellQuote(executablePath))
        }
        val launchEnv = acpLaunchEnv(sessionId, provider, spec.credentialEnvKeys)
        // 含换行/NUL 的值会被脚本过滤掉（TerminalEnvRules）；那不是「环境没就绪」，
        // 必须指出是哪一项，否则用户会去查 rootfs 而永远修不好。
        launchEnv.secrets.firstOrNull { !TerminalEnvRules.isInjectable(it) }?.let { bad ->
            throw IllegalStateException(
                "凭据 ${bad.key} 的值无法安全注入（含换行/NUL 或超长）；已中止，避免 key 出现在 ps 命令行里",
            )
        }
        // 凭据**不能**拼成 `export KEY='value'`：那串会成为 `su -c` 的 argv，`ps` 里直接可读。
        // 改为写进 rootfs 的一次性 0600 脚本，命令行只留脚本路径（同终端 env.conf，§32.22）。
        val secretScript = launchEnv.secrets.takeIf { it.isNotEmpty() }?.let { secrets ->
            envScript(secrets) ?: throw IllegalStateException(
                "无法安全下发 ACP 凭据（Linux 工具环境未就绪）；已中止，避免 key 出现在 ps 命令行里",
            )
        }
        val envExports = acpEnvPrefix(launchEnv.marker, secretScript)
        val wrapped = linuxShell(envExports + innerScript)
            ?: throw IllegalStateException("Linux 工具环境未就绪（或无 Root），无法在环境内启动 ACP 代理")
        val process = ProcessBuilder("su", "-c", wrapped)
            .redirectErrorStream(false)
            .start()
        val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
        val writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
        val live_ = Live(
            session = Session(
                id = sessionId,
                agentId = spec.id,
                acpSessionId = "",
                cwd = cwd,
                createdAtMs = System.currentTimeMillis(),
                taskPreview = task.take(160),
            ),
            process = process,
            reader = reader,
            writer = writer,
            thread = thread,
        )
        live[sessionId] = live_
        live_.requestedModel = model?.trim()?.takeIf { it.isNotEmpty() }
        live_.configModel = provider?.let { effectiveModel }
        live_.providerId = provider?.id
        // stderr 必须持续读走：管道缓冲区（常见 64KB）写满后代理会卡在 write 上，
        // 表现为「initialize 之后一个字都不回」，而且看不出原因。只留尾部用于报错。
        val stderrPump = Thread {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(live_.stderr) {
                        live_.stderr.append(line).append('\n')
                        if (live_.stderr.length > MAX_STDERR_CHARS * 2) {
                            live_.stderr.delete(0, live_.stderr.length - MAX_STDERR_CHARS)
                        }
                    }
                    // 代理把「初始化失败」写在 stderr 里而不是退出：worker 已经死了，进程却还活着。
                    // 不在这里失败，就只能等到 timeout（真机 90 秒），界面上看着就是「卡住」。
                    if (live_.error == null && isFatalStderrLine(line)) {
                        live_.error = "代理初始化失败：${line.trim().take(MAX_STDERR_CHARS / 3)}"
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "eta-acp-stderr-$sessionId"
            start()
        }
        stderrPumpRef[sessionId] = stderrPump
        handler.post {
            runCatching {
                performHandshake(live_, task)
            }.onFailure { throwable ->
                val raw = throwable.message.orEmpty()
                // teardown() 关流会让阻塞中的 readLine 抛 IOException（"read interrupted by close()
                // on another thread"）。这是我们自己收尾的副产物，不是代理的错误：
                // 记进 error 会把「超时」误报成「协议错误」，真原因（超时 / stderr 致命行）反而被盖住。
                val selfClosed = isSelfClosedStreamRead(raw)
                if (live_.error == null && !selfClosed) {
                    live_.error = raw.ifBlank { throwable.javaClass.simpleName }
                }
            }
        }
        return live_
    }

    /** initialize → session/new → session/prompt，边收 session/update 边等 turn 结束。 */
    private fun performHandshake(handle: Live, task: String) {
        var nextId = 1
        fun send(method: String, params: JSONObject): Int {
            val id = nextId++
            val payload = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method)
                .put("params", params)
            synchronized(handle.writer) {
                handle.writer.write(payload.toString())
                handle.writer.newLine()
                handle.writer.flush()
            }
            return id
        }

        val initId = send(
            "initialize",
            JSONObject()
                .put("protocolVersion", 1)
                .put(
                    "clientCapabilities",
                    JSONObject()
                        .put("fs", JSONObject().put("readTextFile", true).put("writeTextFile", true))
                        .put("terminal", true),
                )
                .put(
                    "clientInfo",
                    JSONObject().put("name", "eta").put("title", "Eta").put("version", "1"),
                ),
        )

        var sessionNewId = -1
        var setModelId = -1
        var promptId = -1

        fun sendPrompt() {
            promptId = send(
                "session/prompt",
                JSONObject()
                    .put("sessionId", handle.session.acpSessionId)
                    .put(
                        "prompt",
                        JSONArray().put(
                            JSONObject().put("type", "text").put("text", task),
                        ),
                    ),
            )
        }
        while (!handle.stopped.get()) {
            val line = handle.reader.readLine() ?: break
            if (line.isBlank()) continue
            val msg = runCatching { JSONObject(line) }.getOrNull() ?: continue
            when {
                msg.has("method") && msg.has("id") -> {
                    // 代理发来的 request（客户端能力）。
                    //
                    // 只有 session/request_permission 有确定应答结构。其余方法
                    // （fs/read_text_file、fs/write_text_file、terminal/create …）属于
                    // **客户端能力**：不支持就必须按 JSON-RPC 回 -32601，代理才知道
                    // 「这条路走不通」，从而改用自己进程内的实现。
                    //
                    // 旧实现给所有方法都回 {"result":{"outcome":{}}}：对权限请求合法，
                    // 对 fs/read_text_file 却是畸形响应（缺 content 字段）。代理侧解析失败后
                    // 既不会降级、也不会结束回合 —— 现象就是「acp_delegate 一直卡着到超时」，
                    // 而日志里没有任何错误。
                    // 代理的请求 id 必须**原样回声**：codex-acp 的 session/request_permission
                    // 用 UUID 字符串当 id，旧代码 optInt 取成 -1，应答就匹配不上，
                    // 代理永远等不到权限结论 —— 表现是「说完一句就再也不动」。
                    val replyId = msg.opt("id")
                    val method = msg.optString("method")
                    if (method != AcpProtocol.PERMISSION_METHOD) {
                        synchronized(handle.unsupported) {
                            if (handle.unsupported.size < MAX_UNSUPPORTED_METHODS) {
                                handle.unsupported.add(method)
                            }
                        }
                    }
                    // 应答结构统一由 AcpProtocol 构造（有单测守着「不支持就回 -32601」）。
                    val reply = AcpProtocol.reply(
                        replyId = replyId,
                        method = method,
                        permissionOutcome = if (method == AcpProtocol.PERMISSION_METHOD) {
                            permissionDecision(msg.optJSONObject("params"))
                        } else {
                            null
                        },
                    )
                    synchronized(handle.writer) {
                        handle.writer.write(reply.toString())
                        handle.writer.newLine()
                        handle.writer.flush()
                    }
                }

                msg.has("method") -> {
                    // notification：session/update
                    val params = msg.optJSONObject("params")
                    val update = params?.optJSONObject("update")
                    collectUpdate(handle, update)
                }

                msg.has("id") -> {
                    val id = msg.optInt("id", -1)
                    val error = msg.optJSONObject("error")
                    if (error != null) {
                        val raw = error.optString("message").ifBlank { "ACP 返回错误" }
                        handle.error = if (id == setModelId) {
                            "这个代理不支持切换模型（session/set_model）：$raw；去掉 model 参数即用代理默认"
                        } else {
                            raw
                        }
                        handle.finished = true
                        return
                    }
                    val result = msg.optJSONObject("result") ?: JSONObject()
                    if (id == initId) {
                        sessionNewId = send(
                            "session/new",
                            JSONObject().put("cwd", handle.session.cwd).put("mcpServers", JSONArray()),
                        )
                    } else if (id == sessionNewId) {
                        handle.session.acpSessionId = result.optString("sessionId")
                        // 代理在 session/new 里已经把「当前模型 + 可用模型」告诉我们了：
                        // 不读它，用户就只能猜「这次到底用的什么模型」。
                        val models = AcpModels.parse(result)
                        handle.availableModels = models.available
                        handle.defaultModel = models.currentModelId
                        // 配置里已经有模型时：只有「请求的模型确实在代理清单里」才去切，
                        // 否则就用配置里的那个。理由：清单里是 `gpt-5.6-sol[low]` 这种带档位的
                        // 名字，切过去反而 Internal error；而不在清单里请求本来也切不成。
                        // 结果里的 model / model_requested 两列会把「实际用了谁」说清楚。
                        val configModel = handle.configModel
                        val choice = AcpModels.chooseWithConfig(handle.requestedModel, configModel, models)
                        when (choice) {
                            null -> {
                                handle.activeModel = configModel ?: handle.requestedModel
                                sendPrompt()
                            }
                            is AcpModels.Choice.Reject -> {
                                handle.error = choice.message
                                handle.finished = true
                                return
                            }
                            is AcpModels.Choice.Use -> {
                                setModelId = send(
                                    "session/set_model",
                                    JSONObject()
                                        .put("sessionId", handle.session.acpSessionId)
                                        .put("modelId", choice.modelId),
                                )
                            }
                        }
                    } else if (id == setModelId) {
                        // codex-acp 对成功的 set_model 回 `{}`（不校验名字），
                        // 所以这里只认「有没有返回 error」，不再指望它报错。
                        handle.activeModel = handle.requestedModel
                        sendPrompt()
                    } else if (id == promptId) {
                        handle.stopReason = result.optString("stopReason").ifBlank { "end_turn" }
                        handle.finished = true
                        return
                    }
                }
            }
        }
        if (!handle.finished) {
            handle.error = "代理进程在回合结束前退出了"
        }
    }

    /**
     * 权限决策。
     *
     * 旧策略是「危险词命中就拒绝」，而那张表里有 exec / shell / command / run / 执行 / 命令 ——
     * 匹配的又是整个 toolCall 文本。真机结果（PKX110，2026-10-27）：**每一次命令请求都被拒**，
     * codex 拿到 reject 就结束回合（`stop_reason=cancelled`，16 秒收场），委派完全不可用。
     *
     * 现在：默认放行（委派的本意就是让代理改代码、跑测试），只挡不可逆的破坏性操作，
     * 判据见 [shouldDenyAcpCommand]。Eta 的调用里仍然无法弹窗征询用户。
     */
    private fun permissionDecision(params: JSONObject?): JSONObject {
        val options = params?.optJSONArray("options") ?: JSONArray()
        val rawToolCall = collectText(params?.optJSONObject("toolCall"))
        if (shouldDenyAcpCommand(rawToolCall)) {
            val reject = firstOption(options, reject = true)
            if (reject != null) return reject
        }
        val allow = firstOption(options, reject = false)
        return allow ?: JSONObject().put("outcome", "cancelled")
    }

    private fun firstOption(options: JSONArray, reject: Boolean): JSONObject? {
        for (i in 0 until options.length()) {
            val option = options.optJSONObject(i) ?: continue
            val kind = option.optString("kind").lowercase()
            val name = option.optString("name").lowercase()
            val matches = if (reject) {
                kind.contains("reject") || kind.contains("deny") || name.contains("拒绝") || name.contains("否")
            } else {
                kind.contains("allow") || kind.contains("approve") || name.contains("允许") || name.contains("是")
            }
            if (matches) {
                return JSONObject().put("outcome", "selected").put("optionId", option.optString("optionId"))
            }
        }
        return null
    }

    private fun collectText(node: JSONObject?): String {
        if (node == null) return ""
        val sb = StringBuilder()
        node.keys().forEach { key ->
            val value = node.opt(key)
            when (value) {
                is String -> sb.append(key).append('=').append(value).append(' ')
                is JSONObject -> sb.append(collectText(value))
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        val item = value.opt(i)
                        if (item is JSONObject) sb.append(collectText(item))
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun collectUpdate(handle: Live, update: JSONObject?) {
        if (update == null) return
        // 先交给累加器：步骤/用量这类信息不需要逐条推给界面，但必须一条不漏地记下来。
        handle.stats.onUpdate(update)
        when (update.optString("sessionUpdate")) {
            "agent_message_chunk" -> {
                val text = update.optJSONObject("content")?.optString("text").orEmpty()
                if (text.isNotEmpty()) {
                    synchronized(handle.text) { handle.text.append(text) }
                    emitProgress(handle, "text", text)
                }
            }

            "agent_thought_chunk" -> {
                val text = update.optJSONObject("content")?.optString("text").orEmpty()
                if (text.isNotEmpty()) {
                    synchronized(handle.text) { handle.text.append("[thought] ").append(text) }
                    handle.thoughtChars += text.length
                    emitProgress(handle, "thought", text)
                }
            }

            "tool_call" -> {
                val title = update.optString("title")
                val kind = update.optString("kind")
                if (title.isNotEmpty()) {
                    synchronized(handle.tools) {
                        handle.tools.append("→ ").append(title)
                        if (kind.isNotEmpty()) handle.tools.append(" (").append(kind).append(')')
                        handle.tools.append('\n')
                    }
                    // 工具标题是"代理现在在做什么"的最强信号，优先于思考与文本。
                    emitProgress(handle, "tool_call", if (kind.isEmpty()) title else "$title ($kind)")
                }
            }

            "tool_call_update" -> {
                val status = update.optString("status")
                if (status == "failed" || status == "error") {
                    val title = update.optString("title").ifBlank { "工具调用" }
                    synchronized(handle.tools) {
                        handle.tools.append("✗ ").append(update.optString("title"))
                            .append(" status=").append(status).append('\n')
                    }
                    // 失败必须立刻可见：代理可能就此停下，用户在界面上先看到而不是等结果。
                    emitProgress(handle, "failed", "$title 失败")
                }
            }

            else -> Unit
        }
    }

    /**
     * 把一条增量交给观察者。
     *
     * 两个约束：**不抛异常**（抛了会被读循环的 runCatching 吞掉，进度静默中断），
     * **限长**（代理可能一次刷出几十 KB 的中间输出，界面只需要看得清的一行）。
     */
    private fun emitProgress(handle: Live, kind: String, detail: String) {
        val observer = handle.progress ?: return
        // text / thought 是**增量**：单独一条常常只是「的」「了」这种碎片，贴到卡片上等于没有信息。
        // 改用累积正文的尾部 —— 用户能连着读，看到代理此刻在说什么，而不是只有工具标题。
        val source = if (kind == "text" || kind == "thought") {
            synchronized(handle.text) {
                val all = handle.text.toString()
                if (all.length <= MAX_PROGRESS_CHARS) {
                    all
                } else {
                    "…" + all.takeLast(MAX_PROGRESS_CHARS - 1)
                }
            }
        } else {
            detail.take(MAX_PROGRESS_CHARS)
        }
        val line = source.trim().replace('\n', ' ')
        if (line.isEmpty()) return
        runCatching { observer.onProgress(kind, line) }
    }

    private fun awaitCompletion(handle: Live, deadlineMs: Long, startedAtMs: Long) {
        var lastBeatAt = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadlineMs) {
            if (handle.finished) return
            if (handle.error != null) return
            if (!handle.process.isAlive && !handle.finished) {
                // 进程退出但还没收到 turn 结束：给读线程一点时间把缓冲排空。
                Thread.sleep(150)
                if (handle.finished) return
                handle.error = "代理进程已退出（exit=${runCatching { handle.process.exitValue() }.getOrDefault(-1)}）"
                return
            }
            val now = System.currentTimeMillis()
            if (now - lastBeatAt >= PROGRESS_HEARTBEAT_MS) {
                lastBeatAt = now
                emitHeartbeat(handle, startedAtMs)
            }
            Thread.sleep(80)
        }
        if (!handle.finished) {
            handle.error = null
            handle.finished = false
        }
    }

    /**
     * 委派期间的心跳。
     *
     * 代理跑长任务时可能几分钟不产出任何事件，卡片完全静止 —— 用户无法区分「在干活」和「已卡死」。
     * 每 [PROGRESS_HEARTBEAT_MS] 发一条「已运行 Ns + 最近一条输出」，至少让人知道它还活着，
     * 也知道卡在哪一步。
     */
    private fun emitHeartbeat(handle: Live, startedAtMs: Long) {
        val elapsed = (System.currentTimeMillis() - startedAtMs) / 1000
        val recent = synchronized(handle.text) {
            handle.text.toString().trim().takeLast(HEARTBEAT_EXCERPT_CHARS).replace('\n', ' ')
        }
        emitProgress(
            handle,
            "heartbeat",
            if (recent.isEmpty()) "已运行 ${elapsed}s，代理尚未输出内容" else "已运行 ${elapsed}s｜最近：$recent",
        )
    }

    private fun teardown(handle: Live) {
        handle.stopped.set(true)
        // 先收整棵树：只杀直接子进程（su）会把 sh/node/平台二进制留下，
        // 它们被 init 收养后继续跑（真机抓到 8 个，其中 6 个父链还挂着 App，孤儿判据收不到）。
        runCatching { AcpOrphanReaper.killSessionProcesses(handle.session.id) }
        runCatching { handle.process.destroyForcibly() }
        runCatching { handle.process.errorStream.close() }
        stderrPumpRef.remove(handle.session.id)
        runCatching { handle.reader.close() }
        runCatching {
            synchronized(handle.writer) { handle.writer.close() }
        }
        handle.thread.quitSafely()
        // 临时 CODEX_HOME 里可能含自定义头的值（部分中转站把 key 放在头里）。
        // 会话结束就删，不指望 /tmp 的周期性清理。
        if (handle.providerId != null) purgeCodexHome(handle.session.id)
    }

    /**
     * 删除这次委派的临时 CODEX_HOME。
     *
     * 路径是 **guest 内** 的 `/tmp/...`，必须用 [linuxShell] 包装后再从宿主执行，
     * 否则删的是宿主同名目录。放到后台线程：teardown 可能发生在主线程，
     * 一个 `su` 进程就足够卡出掉帧。
     */
    private fun purgeCodexHome(sessionId: String) {
        val wrapped = linuxShell("rm -rf " + shellQuote("/tmp/eta-codex-$sessionId")) ?: return
        Thread {
            runCatching {
                val process = ProcessBuilder("su", "-c", wrapped).start()
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun errorJson(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message.take(300))
            .toString()

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val MAX_RESULT_CHARS = 12_000
        const val MAX_TOOL_CHARS = 4_000

        /** 回给模型的 stderr 尾部长度：够看清崩溃原因，又不至于淹没结果。 */
        const val MAX_STDERR_CHARS = 1_500

        /** 同时存活的 ACP 会话上限（每个都是一个进程 + 一个线程）。 */
        const val MAX_LIVE_SESSIONS = 4

        /**
         * 单条进度文本的长度上限。
         *
         * 原来是 160（「副标题只有一行」）。但 ACP 委派期间循环里只有这一条信息流，
         * 160 字连一段工具输出都放不下 —— 用户看到的就是「卡着不动」。
         * 现在卡片在运行中显示最多 3 行，这个上限按 3 行的可读量给。
         */
        const val MAX_PROGRESS_CHARS = 400

        /** 委派没有新事件时的心跳间隔：界面上「还在跑」与「已卡死」必须能区分。 */
        const val PROGRESS_HEARTBEAT_MS = 20_000L

        /** 心跳里带上的最近输出长度。 */
        const val HEARTBEAT_EXCERPT_CHARS = 120

        /** 结果里最多回报多少个「未实现的能力方法」，够定位就停。 */
        const val MAX_UNSUPPORTED_METHODS = 8

        /**
         * 内置代理。
         *
         * `executable` 只是一个**兜底候选**：真正以环境内探测结果为准。
         * 原来这里写死 `/opt/eta/node/26.8.1/bin/codex-acp`，node 一升级就整条失效；
         * 现在同时给通配（任意版本目录）与 PATH 命令名，装在哪都能找到。
         */
        val BUILTIN_AGENTS = listOf(
            AgentSpec(
                id = "codex",
                title = "Codex",
                executable = "/opt/eta/node/26.8.1/bin/codex-acp",
                executableGlobs = listOf(
                    "/opt/eta/node/*/bin/codex-acp",
                    "/usr/local/bin/codex-acp",
                ),
                commandNames = listOf("codex-acp"),
                credentialEnvKeys = listOf("CODEX_API_KEY", "OPENAI_API_KEY"),
                // 用 `codex login` 登录过就够了，不强制用户再配一遍环境变量。
                credentialPaths = listOf("/root/.codex/auth.json"),
            ),
        )

        /** 探测结果的缓存时长：足够挡住 UI 轮询，又不至于让「刚装好」等一分钟。 */
        const val RESOLUTION_TTL_MS = 30_000L

        /** 登录态探测的缓存时长：比路径探测长一点，登录不会每 30 秒变一次。 */
        const val CREDENTIAL_TTL_MS = 60_000L

        /** 探测脚本的确认标记。 */
        const val ETA_CREDENTIAL_OK = "ETA_CRED_OK"

        /** 单次探测的等待上限。环境启动本身要几十毫秒到一两百毫秒。 */
        const val PROBE_TIMEOUT_MS = 4_000L
    }
}

/**
 * 代理 stderr 里的「致命」行。
 *
 * 真机实测（PKX110，2026-10-27）：codex-acp 的初始化通知发不出去（设备访问不了
 * `https://chatgpt.com/backend-api/wham/apps`），它把
 * `ERROR rmcp::transport::worker: worker quit with fatal: ...` 写进 stderr，
 * 但进程本身还活着、也不退出。旧实现会一直等到 timeout（实测 90 秒），
 * 用户看到的就是「ACP 委派卡住，一句话都不回」。
 *
 * 只认几种明确的致命措辞，不拿 `ERROR` 当判据：代理正常工作时也会往 stderr 写 ERROR。
 * 每组是「必须同时出现」的片段，多段的组就是为了不把可重试的普通报错当成致命。
 */
private val FATAL_STDERR_PATTERNS: List<List<String>> = listOf(
    listOf("worker quit with fatal"),
    listOf("fatal error"),
    listOf("panic:"),
    listOf("failed to initialize"),
    // 2026-10-27 真机（PKX110）：codex-acp 报
    // `codex_models_manager::manager: failed to refresh available models: timeout waiting for child process to exit`，
    // 进程活着、不回话、也不退出 → 用户干等 180 秒。要求同时出现 timeout，
    // 免得一次可重试的刷新失败就把还能用的回合杀掉。
    listOf("failed to refresh available models", "timeout"),
    listOf("timeout waiting for child process to exit"),
)

internal fun isFatalStderrLine(line: String): Boolean {
    val lower = line.lowercase()
    return FATAL_STDERR_PATTERNS.any { group -> group.all { lower.contains(it) } }
}

/**
 * 这个异常是不是「我们自己关流」造成的。
 *
 * 超时 / 取消时 [AcpClient.teardown] 会关掉管道，阻塞在 readLine 的握手线程随即抛 IOException。
 * 它不是代理的错误，不能写进 error：否则超时会被报成「协议错误」，
 * 用户拿到的就是一句毫无信息量的 "read interrupted by close() on another thread"
 * 而看不到真正的原因（超时 / stderr 致命行）。
 */
internal fun isSelfClosedStreamRead(message: String): Boolean =
    message.contains("close()", ignoreCase = true) ||
        message.contains("Stream closed", ignoreCase = true)
