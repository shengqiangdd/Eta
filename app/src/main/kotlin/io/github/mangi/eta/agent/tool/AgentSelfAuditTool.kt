package io.github.mangi.eta.agent.tool

import android.content.Context
import android.provider.Settings
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.core.AgentLogger
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * `eta_self_audit` 的取数层：把设备上的事实读出来交给 [AgentSelfAudit] 判定。
 *
 * 只读。唯一的写操作是「不写」—— 不改配置、不停进程、不动系统设置。
 *
 * 安全：所有 root 命令都是**固定串**，唯一拼接进去的是系统给出的 dropbox 文件名与包名，
 * 两者都先过白名单正则。模型参数不会进入命令。
 */
internal class AgentSelfAuditTool(
    private val context: Context,
    private val rootExecutor: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean,
    private val logger: AgentLogger,
) {
    fun run(args: JSONObject): String = runCatching {
        val domains = parseDomains(args.optString("scope", "all"))
        val hours = args.optInt("hours", DEFAULT_HOURS).coerceIn(1, MAX_HOURS)
        val root = rootAvailable()
        val crashFiles = if (AgentSelfAudit.Domain.CRASH in domains) {
            readCrashFiles(hours)
        } else {
            emptyList()
        }
        val meminfo = if (AgentSelfAudit.Domain.RESOURCE in domains) readJavaHeapPssKb() else null
        val input = AgentSelfAudit.Input(
            packageName = context.packageName,
            versionName = versionName(),
            nowMillis = System.currentTimeMillis(),
            windowHours = hours,
            domains = domains,
            rootAvailable = root,
            crashFiles = crashFiles,
            processStartedMillis = processStartedMillis(),
            heapLimitMb = heapLimitMb(),
            javaHeapPssKb = meminfo,
            capabilities = readCapabilities(),
            codeClues = if (AgentSelfAudit.Domain.CODE in domains) readCodeClues(args) else null,
        )
        val report = AgentSelfAudit.audit(input)
        toJson(report, input).toString()
    }.getOrElse { throwable ->
        logger.warn("eta_self_audit 失败：${throwable.message}")
        JSONObject()
            .put("ok", false)
            .put("error", throwable.message ?: throwable::class.java.simpleName)
            .toString()
    }

    private fun parseDomains(scope: String): Set<AgentSelfAudit.Domain> = when (scope.trim().lowercase(Locale.US)) {
        "crash" -> setOf(AgentSelfAudit.Domain.CRASH)
        "resource" -> setOf(AgentSelfAudit.Domain.RESOURCE)
        "capability" -> setOf(AgentSelfAudit.Domain.CAPABILITY)
        "code" -> setOf(AgentSelfAudit.Domain.CODE)
        else -> AgentSelfAudit.Domain.entries.toSet()
    }

    /**
     * dropbox 里只保留最近若干条崩溃；文件名自带毫秒时间戳，先按窗口过滤再读内容，
     * 避免每次自审都把几十个历史文件读一遍。
     */
    private fun readCrashFiles(hours: Int): List<AgentSelfAudit.CrashFile> {
        if (!rootAvailable()) return emptyList()
        val listing = rootExecutor.execute("ls -1 $DROPBOX_DIR").stdout
        if (listing.isBlank()) return emptyList()
        val since = System.currentTimeMillis() - hours.coerceIn(1, MAX_HOURS) * 3_600_000L
        val names = listing.lineSequence()
            .map { it.trim() }
            .filter { DROPBOX_NAME.matches(it) }
            .mapNotNull { name ->
                val stamp = name.substringAfter('@').substringBefore('.').toLongOrNull() ?: return@mapNotNull null
                if (stamp < since) null else stamp to name
            }
            .sortedByDescending { it.first }
            .take(MAX_CRASH_FILES)
            .map { it.second }
            .toList()
        return names.mapNotNull { name ->
            val body = rootExecutor.execute("cat $DROPBOX_DIR/$name").stdout
            if (body.isBlank()) return@mapNotNull null
            AgentSelfAudit.CrashFile(
                name = name,
                timeMillis = name.substringAfter('@').substringBefore('.').toLongOrNull() ?: 0L,
                text = body.take(MAX_CRASH_CHARS),
            )
        }
    }

    /** `dumpsys meminfo` 的 App Summary 里 `Java Heap:` 行第三列是 Pss(KB)。 */
    private fun readJavaHeapPssKb(): Int? {
        if (!rootAvailable()) return null
        val pkg = context.packageName
        if (!SAFE_PACKAGE.matches(pkg)) return null
        val output = rootExecutor.execute("dumpsys meminfo $pkg").stdout
        if (output.isBlank()) return null
        val line = output.lineSequence().firstOrNull { it.contains("Java Heap:") } ?: return null
        return line.trim().split(WHITESPACE).getOrNull(2)?.toIntOrNull()
    }

    private fun heapLimitMb(): Int? {
        val max = Runtime.getRuntime().maxMemory()
        if (max <= 0L) return null
        return (max / (1024L * 1024L)).toInt()
    }

    private fun processStartedMillis(): Long? {
        val started = File("/proc/self").lastModified()
        return if (started > 0L) started else null
    }

    private fun versionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrNull().orEmpty()

    private fun readCapabilities(): Map<String, Boolean> {
        val accessibility = runCatching {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            val recovery = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
            )
            (enabled.contains(context.packageName) || enabled.contains(ACCESSIBILITY_SERVICE)) && recovery == "1"
        }.getOrDefault(false)

        val notifications = runCatching {
            val listeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ).orEmpty()
            listeners.contains(context.packageName)
        }.getOrDefault(false)

        val usage = runCatching {
            // 与「使用情况」工具共用同一判断，避免两处对同一权限得出不同结论。
            AgentPersonalContextTools.hasUsageAccess(context)
        }.getOrDefault(false)

        return linkedMapOf(
            "accessibility" to accessibility,
            "notifications" to notifications,
            "usage" to usage,
        )
    }

    /**
     * 代码线索只在开发机上有意义（真机发布包没有源码）。整域失败不影响运行级结论，
     * 因此任何一步读不到都返回 null，由核心逻辑记为 unavailable。
     */
    private fun readCodeClues(args: JSONObject): AgentSelfAudit.CodeClues? {
        if (!rootAvailable()) return null
        val configured = args.optString("source_root", "").trim()
        val root = if (configured.isNotEmpty() && SAFE_SOURCE_PATH.matches(configured)) {
            configured
        } else {
            DEFAULT_SOURCE_ROOTS.firstOrNull { candidate ->
                rootExecutor.execute("test -d $candidate && echo yes").stdout.trim() == "yes"
            } ?: return null
        }
        val files = rootExecutor.execute(
            "grep -rIl -e TODO -e FIXME --include=*.kt $root",
            timeoutMillis = CODE_SCAN_TIMEOUT_MS,
            maxOutputBytes = CODE_SCAN_MAX_BYTES,
        )
        // 0 = 有匹配，1 = 无匹配；其余（含超时的 -2）都算「读不到」，不能当成 0 条。
        val todoFiles = if (files.exitCode == 0 || files.exitCode == 1) {
            files.stdout.lineSequence().count { it.isNotBlank() }
        } else {
            -1
        }
        val git = rootExecutor.execute(
            "git -C $root status --porcelain",
            timeoutMillis = CODE_SCAN_TIMEOUT_MS,
            maxOutputBytes = CODE_SCAN_MAX_BYTES,
        )
        // 设备上通常没有 git：这时 stdout 为空且退出码非 0，必须记为未知而不是「干净」。
        val uncommitted = if (git.exitCode == 0) {
            git.stdout.lineSequence().count { it.isNotBlank() }
        } else {
            -1
        }
        return AgentSelfAudit.CodeClues(
            sourceRoot = root,
            todoCount = todoFiles,
            uncommittedFiles = uncommitted,
        )
    }

    private fun toJson(report: AgentSelfAudit.Report, input: AgentSelfAudit.Input): JSONObject = JSONObject()
        .put("ok", true)
        .put("package", input.packageName)
        .put("version", input.versionName)
        .put("captured_at", input.nowMillis)
        .put("window_hours", report.windowHours)
        .put("checked_domains", JSONArray(report.checkedDomains))
        .put("root", input.rootAvailable)
        .put("finding_count", report.findings.size)
        .put(
            "findings",
            JSONArray().also { array ->
                report.findings.forEach { finding ->
                    array.put(
                        JSONObject()
                            .put("id", finding.id)
                            .put("severity", finding.severity.name)
                            .put("confidence", finding.confidence.name.lowercase(Locale.US))
                            .put("title", finding.title)
                            .put("evidence", JSONArray(finding.evidence))
                            .put("impact", finding.impact)
                            .put("fix", finding.fix)
                            .put("verify", finding.verify),
                    )
                }
            },
        )
        .put(
            "clues",
            JSONArray().also { array ->
                report.clues.forEach { clue ->
                    array.put(JSONObject().put("title", clue.title).put("evidence", clue.evidence))
                }
            },
        )
        .put("unavailable", JSONArray(report.unavailable))
        .put("notes", JSONArray(report.notes))

    private companion object {
        const val DEFAULT_HOURS = 48
        const val MAX_HOURS = 720
        const val MAX_CRASH_FILES = 8
        const val MAX_CRASH_CHARS = 8_000
        const val CODE_SCAN_TIMEOUT_MS = 8_000L
        const val CODE_SCAN_MAX_BYTES = 64 * 1024
        const val DROPBOX_DIR = "/data/system/dropbox"
        const val ACCESSIBILITY_SERVICE = "AgentAccessibilityService"
        val DROPBOX_NAME = Regex("^data_(app_crash|anr)@\\d+\\.txt$")
        val SAFE_PACKAGE = Regex("^[A-Za-z0-9._]+$")
        val SAFE_SOURCE_PATH = Regex("^/[A-Za-z0-9._/\\-]+$")
        val WHITESPACE = Regex("\\s+")
        val DEFAULT_SOURCE_ROOTS = listOf("/data/local/tmp/eta/eta-v2")
    }
}
