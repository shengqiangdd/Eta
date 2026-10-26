package io.github.mangi.eta.agent.acp

import android.os.Handler
import android.os.HandlerThread
import io.github.mangi.eta.agent.device.RootAccess
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
        /** 代理往 stderr 写的东西：启动失败、崩溃原因都在这里。 */
        val stderr = StringBuilder()
        var finished = false
        var stopReason: String? = null
        var error: String? = null
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
        onProgress: ProgressObserver? = null,
    ): Pair<Boolean, String> {
        val spec = BUILTIN_AGENTS.firstOrNull { it.id == agentId }
            ?: externalAgents[agentId]
            ?: return false to errorJson("ACP_AGENT_UNKNOWN", "未知 ACP 代理：$agentId")
        if (!rootAvailable()) {
            return false to errorJson("ROOT_REQUIRED", "ACP 委派需要 Root 以进入 Linux 环境")
        }
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
        val credentialMissing = spec.credentialEnvKeys.isNotEmpty() &&
            !envPresent && !credentialFilePresent(spec)
        if (credentialMissing) {
            return false to errorJson(
                "ACP_CREDENTIAL_MISSING",
                "缺少凭据：请设置 ${spec.credentialEnvKeys.joinToString(" 或 ")}，" +
                    "或在该环境里完成一次代理登录（例如 codex login）后重试",
            )
        }
        val launched = runCatching { launch(spec, resolution.path, task, cwd) }
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
            awaitCompletion(handle, deadline)
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
            .put("cwd", cwd)
            .put("elapsed_ms", elapsed)
            .put("stop_reason", handle.stopReason ?: JSONObject.NULL)
            .put("text", handle.text.toString().take(MAX_RESULT_CHARS))
            .put("tool_activity", handle.tools.toString().take(MAX_TOOL_CHARS))
            .put("stderr_tail", stderrTail(handle))
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
            .put("age_seconds", handle.session.ageSeconds)
            .put("finished", handle.finished)
            .put("stop_reason", handle.stopReason ?: JSONObject.NULL)
            .put("text", handle.text.toString().take(MAX_RESULT_CHARS))
            .put("tool_activity", handle.tools.toString().take(MAX_TOOL_CHARS))
            .put("stderr_tail", stderrTail(handle))
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
    private fun stderrTail(handle: Live): String =
        synchronized(handle.stderr) { handle.stderr.toString().takeLast(MAX_STDERR_CHARS) }

    private fun launch(spec: AgentSpec, executablePath: String, task: String, cwd: String): Live {
        // 每次委派都是一个真实进程 + 一个 HandlerThread；没有上限时并发委派会把内存和 PID 一起吃光。
        if (live.size >= MAX_LIVE_SESSIONS) {
            throw IllegalStateException(
                "同时进行的 ACP 会话已达上限 $MAX_LIVE_SESSIONS；先 acp_session_close 或等待当前委派结束",
            )
        }
        val sessionId = "acp${System.currentTimeMillis().toString(36)}"
        val thread = HandlerThread("eta-acp-$sessionId").apply { start() }
        val handler = Handler(thread.looper)
        // 代理在 Linux rootfs 里运行：cwd 与可执行路径都按容器内视角给。
        val innerScript = buildString {
            append("cd ").append(shellQuote(cwd)).append(" 2>/dev/null || cd /workspace; ")
            append("exec ").append(shellQuote(executablePath))
        }
        // 凭据在**环境内** export：值不经过宿主 shell，也不会出现在宿主 ps 的命令行里。
        val envExports = spec.credentialEnvKeys
            .mapNotNull { key ->
                System.getenv(key)?.takeIf { it.isNotBlank() }?.let { "export $key=${shellQuote(it)}; " }
            }
            .joinToString("")
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
                if (live_.error == null) {
                    live_.error = throwable.message ?: throwable.javaClass.simpleName
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

        var sessionCreated = false
        var promptSent = false
        var promptId = -1
        while (!handle.stopped.get()) {
            val line = handle.reader.readLine() ?: break
            if (line.isBlank()) continue
            val msg = runCatching { JSONObject(line) }.getOrNull() ?: continue
            when {
                msg.has("method") && msg.has("id") -> {
                    // 代理发来的 request：权限请求要回答，其它直接回空结果。
                    val replyId = msg.optInt("id", -1)
                    val method = msg.optString("method")
                    val outcome = if (method == "session/request_permission") {
                        permissionDecision(msg.optJSONObject("params"))
                    } else {
                        JSONObject()
                    }
                    val reply = JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", replyId)
                        .put("result", JSONObject().put("outcome", outcome))
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
                        handle.error = error.optString("message").ifBlank { "ACP 返回错误" }
                        handle.finished = true
                        return
                    }
                    val result = msg.optJSONObject("result") ?: JSONObject()
                    if (id == initId) {
                        send("session/new", JSONObject().put("cwd", handle.session.cwd).put("mcpServers", JSONArray()))
                        sessionCreated = true
                    } else if (sessionCreated && !promptSent) {
                        handle.session.acpSessionId = result.optString("sessionId")
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
                        promptSent = true
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
     * 权限决策：只读放行，写文件/执行命令一律拒绝。
     * 理由：Eta 的工具调用里没有办法弹窗征询用户，
     * 而"允许代理改用户磁盘"是不可逆的。拒绝会让代理停下来把情况写进文本，交由用户决定。
     */
    private fun permissionDecision(params: JSONObject?): JSONObject {
        val options = params?.optJSONArray("options") ?: JSONArray()
        val rawKind = collectText(params?.optJSONObject("toolCall"))
        val destructive = DESTRUCTIVE_HINTS.any { rawKind.contains(it, ignoreCase = true) }
        if (destructive) {
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
        val line = detail.trim().take(MAX_PROGRESS_CHARS).replace('\n', ' ')
        if (line.isEmpty()) return
        runCatching { observer.onProgress(kind, line) }
    }

    private fun awaitCompletion(handle: Live, deadlineMs: Long) {
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
            Thread.sleep(80)
        }
        if (!handle.finished) {
            handle.error = null
            handle.finished = false
        }
    }

    private fun teardown(handle: Live) {
        handle.stopped.set(true)
        runCatching { handle.process.destroyForcibly() }
        runCatching { handle.process.errorStream.close() }
        stderrPumpRef.remove(handle.session.id)
        runCatching { handle.reader.close() }
        runCatching {
            synchronized(handle.writer) { handle.writer.close() }
        }
        handle.thread.quitSafely()
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

        /** 单条进度行的长度上限：界面副标题只有一行，长了只会被截断。 */
        const val MAX_PROGRESS_CHARS = 160

        /**
         * 命中这些关键词的工具调用一律拒绝代理执行。
         * 宁可漏放（让代理停下来问），不可错放（替用户改盘）。
         */
        val DESTRUCTIVE_HINTS = listOf(
            "write", "edit", "patch", "create", "delete", "remove", "move", "rename",
            "exec", "shell", "command", "terminal", "bash", "run",
            "写", "改", "删", "执行", "命令", "终端",
        )

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
