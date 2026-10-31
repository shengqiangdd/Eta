package io.github.mangi.eta.agent.tool

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Eta 自审：把「本机可观测的事实」收敛成**可行动**的缺陷清单。
 *
 * 三条设计约束（与 §32.38 的教训同源）：
 * 1. **只把有证据的写成缺陷**：`findings` 里每条都必须带原始证据片段；
 *    推测与线索放 `clues`，并标 [Confidence]。
 * 2. **说清口径**：PSS 不等于 allocated、窗口内没有崩溃不等于没崩过 —— 一律写进 `notes`。
 * 3. **不吞掉「测不了」**：没有 root / 没有源码时，对应域进 `unavailable` 并说明原因，
 *    绝不用「未发现缺陷」冒充「已检查且健康」。
 *
 * 这里是**纯逻辑**：root 命令、dropbox 文件、`dumpsys` 输出都由调用方读好传进来。
 * 因此可以在普通 JVM 单测里把每一种判定都喂一遍，不需要 Robolectric。
 */
internal object AgentSelfAudit {

    internal enum class Severity(val rank: Int) { P0(0), P1(1), P2(2) }

    /** 有证据 = OBSERVED；由证据推断出来、还需要人确认 = INFERRED。 */
    internal enum class Confidence { OBSERVED, INFERRED }

    internal enum class Domain { CRASH, RESOURCE, CAPABILITY, CODE }

    internal data class CrashFile(val name: String, val timeMillis: Long, val text: String)

    /**
     * 计数为 -1 表示「读不到」。真机上常常没有 git，这时绝不能报成「工作区干净」。
     */
    internal data class CodeClues(
        val sourceRoot: String,
        val todoCount: Int,
        val uncommittedFiles: Int,
    )

    internal data class Input(
        val packageName: String,
        val versionName: String,
        val nowMillis: Long,
        val windowHours: Int,
        val domains: Set<Domain>,
        val rootAvailable: Boolean,
        val crashFiles: List<CrashFile> = emptyList(),
        val processStartedMillis: Long? = null,
        /** APK 的 `lastUpdateTime`。装包/更新也会把进程换掉，所以「刚起」不等于「崩了」。 */
        val installUpdatedMillis: Long? = null,
        val heapLimitMb: Int? = null,
        val javaHeapPssKb: Int? = null,
        val capabilities: Map<String, Boolean> = emptyMap(),
        val codeClues: CodeClues? = null,
    )

    internal data class Finding(
        val id: String,
        val severity: Severity,
        val confidence: Confidence,
        val title: String,
        val evidence: List<String>,
        val impact: String,
        val fix: String,
        val verify: String,
    )

    internal data class Clue(val title: String, val evidence: String)

    internal data class Report(
        val findings: List<Finding>,
        val clues: List<Clue>,
        val unavailable: List<String>,
        val notes: List<String>,
        val windowHours: Int,
        val checkedDomains: List<String>,
    )

    private val EXCEPTION_LINE = Regex("^([A-Za-z_$][A-Za-z0-9_.$]*(?:Exception|Error|Throwable))(?::.*)?$")
    private val CODE_FRAME = Regex("\\bat [^\\s(]+\\((?:[^():]+\\.(?:kt|java):\\d+)\\)")
    private const val MAX_EVIDENCE_LINES = 4

    /** 能力缺失的说明与修复路径；顺序稳定，便于测试与阅读。 */
    private val CAPABILITY_SPECS = listOf(
        Triple(
            "accessibility",
            "无障碍服务未连接：屏幕观察与手势工具不可用（GUI 类任务会直接失败）",
            "设置 → 无障碍 → 已下载的服务 → 启用 Eta，然后回到应用重试",
        ),
        Triple(
            "notifications",
            "通知使用权未授予：读不到通知栏内容与通知历史",
            "设置 → 通知 → 通知使用权 → 允许 Eta",
        ),
        Triple(
            "usage",
            "使用情况访问未授予：无法统计应用使用时长与最近打开的应用",
            "设置 → 应用 → 特殊权限 → 使用情况访问 → 允许 Eta",
        ),
    )

    fun audit(input: Input): Report {
        val findings = mutableListOf<Finding>()
        val clues = mutableListOf<Clue>()
        val unavailable = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val since = input.nowMillis - input.windowHours.coerceIn(1, 720) * 3_600_000L

        if (Domain.CRASH in input.domains) {
            collectCrashes(input, since, findings, clues, unavailable, notes)
        }
        if (Domain.RESOURCE in input.domains) {
            collectResource(input, findings, unavailable, notes)
        }
        if (Domain.CAPABILITY in input.domains) {
            collectCapabilities(input, findings)
        }
        if (Domain.CODE in input.domains) {
            collectCode(input, clues, unavailable)
        }

        return Report(
            findings = findings.sortedWith(compareBy({ it.severity.rank }, { it.title })),
            clues = clues,
            unavailable = unavailable,
            notes = notes,
            windowHours = input.windowHours.coerceIn(1, 720),
            checkedDomains = input.domains.map { it.name.lowercase(Locale.US) }.sorted(),
        )
    }

    private fun collectCrashes(
        input: Input,
        since: Long,
        findings: MutableList<Finding>,
        clues: MutableList<Clue>,
        unavailable: MutableList<String>,
        notes: MutableList<String>,
    ) {
        if (!input.rootAvailable) {
            // 没有 root 就读不到 /data/system/dropbox。这里必须说清楚，不能返回「暂无崩溃」。
            unavailable += "崩溃与 ANR：读取系统 dropbox 需要 root；当前无法判断有没有崩过"
            return
        }
        val inWindow = input.crashFiles.filter { it.timeMillis >= since }
        val older = input.crashFiles.size - inWindow.size
        if (older > 0) notes += "另有 $older 个崩溃/ANR 文件早于 ${input.windowHours} 小时窗口，未计入"
        val mine = inWindow.filter { it.text.contains(input.packageName) }
        val foreign = inWindow.size - mine.size
        if (foreign > 0) notes += "窗口内 $foreign 个崩溃文件属于其他应用，已排除"
        if (mine.isEmpty()) {
            notes += "窗口内没有本应用的崩溃文件；仓库只保留最近若干条，「没有」不等于「没发生过」"
        }
        mine.sortedByDescending { it.timeMillis }.forEach { file ->
            findings += classifyFailure(input.packageName, file)
        }
        if (mine.size >= 2) {
            val uptime = input.processStartedMillis?.let { input.nowMillis - it }
            if (uptime != null && uptime in 0 until 30 * 60_000L) {
                // 「进程刚起」有两个原因：崩了重启，或者刚装包/更新把进程换掉了。
                // 真机上踩过第二种：装完包进程刚起 217 秒，而窗口内两次崩溃都发生在装包之前，
                // 却被报成 P0「崩溃循环」。只把**本次更新之后**的崩溃当成循环证据。
                val install = input.installUpdatedMillis?.takeIf { it > 0L }
                val afterInstall = if (install == null) mine else mine.filter { it.timeMillis >= install }
                if (afterInstall.size >= 2) {
                    findings += Finding(
                        id = "restart-loop",
                        severity = Severity.P0,
                        confidence = Confidence.INFERRED,
                        title = "疑似崩溃循环：${input.windowHours} 小时内 ${afterInstall.size} 次崩溃，当前进程只跑了 ${uptime / 1000} 秒",
                        evidence = afterInstall.sortedByDescending { it.timeMillis }.take(MAX_EVIDENCE_LINES)
                            .map { it.name },
                        impact = "用户会反复看到闪退或白屏，进行中的任务无法完成",
                        fix = "先修最新一次崩溃（它最可能仍在复现），修完观察一个完整会话周期；只修旧的会被新的掩盖",
                        verify = "连续运行 ≥ 1 小时且窗口内崩溃计数不再增加",
                    )
                } else if (install != null) {
                    clues += Clue(
                        title = "进程刚起（${uptime / 1000} 秒）与最近一次装包/更新（${stamp(install)}）重合：" +
                            "窗口内 ${mine.size} 次崩溃都早于本次更新，不能据此判定崩溃循环",
                        evidence = "lastUpdateTime=${stamp(install)}；崩溃：" +
                            mine.sortedByDescending { it.timeMillis }.take(MAX_EVIDENCE_LINES)
                                .joinToString("、") { it.name },
                    )
                }
            }
        }
    }

    private fun classifyFailure(packageName: String, file: CrashFile): Finding {
        val isAnr = file.name.contains("anr")
        val lines = file.text.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList()
        val exception = lines.firstOrNull { EXCEPTION_LINE.matches(it.trim()) }?.trim()
        val frames = lines.map { it.trim() }.filter { CODE_FRAME.containsMatchIn(it) }.take(MAX_EVIDENCE_LINES)
        val evidence = buildList {
            add("文件：${file.name}")
            exception?.let { add("异常：$it") }
            addAll(frames)
            if (isEmpty() || (exception == null && frames.isEmpty())) {
                add("未从文件里解析出异常行与代码栈帧（格式可能与预期不同）")
            }
        }
        return if (isAnr) {
            Finding(
                id = "anr-${file.timeMillis}",
                severity = Severity.P0,
                confidence = if (frames.isEmpty()) Confidence.INFERRED else Confidence.OBSERVED,
                title = "ANR：主线程长时间无响应",
                evidence = evidence,
                impact = "系统弹出「应用无响应」，用户只能等待或强行关闭；后台任务可能被一并取消",
                fix = "看主线程当时在做什么：同步文件/网络 IO、等锁、还是等 Binder 对端；把跨进程与磁盘操作移出主线程",
                verify = "复现原操作路径，主线程不再出现 > 5 秒的连续阻塞",
            )
        } else {
            val title = exception?.substringBefore(':')?.let { "崩溃：$it" } ?: "崩溃：未能识别异常类型"
            Finding(
                id = "crash-${file.timeMillis}",
                severity = Severity.P0,
                confidence = if (exception == null) Confidence.INFERRED else Confidence.OBSERVED,
                title = title,
                evidence = evidence,
                impact = "进程直接中止：用户看到闪退，本轮未保存的结果可能丢失",
                fix = fixFor(exception),
                verify = "按证据里的栈帧复现原路径不再崩溃；该栈帧不再出现在后续日志里",
            )
        }
    }

    private fun fixFor(exception: String?): String = when {
        exception == null -> "先补上下文（崩溃文件缺少异常行），再按首帧路径写最小复现"
        exception.contains("OutOfMemoryError") ->
            "定位首帧处的大对象分配，确认它是否在重组/循环/每帧刷新里被重复创建；先把一次性分配移出热路径，再考虑缩小数据规模"
        exception.contains("StackOverflowError") -> "检查递归的终止条件与最大深度"
        exception.contains("NullPointerException") ->
            "沿首帧链找可空字段的来源，明确它「必须存在」还是「允许缺失」，再决定抛错还是兜底"
        exception.contains("IllegalStateException") ->
            "确认调用顺序的前置条件，把隐式依赖改成显式校验，避免在错误状态下继续执行"
        exception.contains("Timeout") -> "检查是对端无响应还是本地等待策略不合理，补超时后的降级路径"
        else -> "按首帧路径写一条最小复现，先把它固定住再谈优化"
    }

    /** 只用于把人能读的时间写进句子，不参与任何判定。 */
    private fun stamp(millis: Long): String = DateTimeFormatter
        .ofPattern("MM-dd HH:mm:ss", Locale.US)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(millis))

    private fun collectResource(
        input: Input,
        findings: MutableList<Finding>,
        unavailable: MutableList<String>,
        notes: MutableList<String>,
    ) {
        val limitMb = input.heapLimitMb
        val heapKb = input.javaHeapPssKb
        if (limitMb == null || limitMb <= 0 || heapKb == null || heapKb <= 0) {
            unavailable += "内存压力：未能读取堆上限或 Java Heap 占用"
            return
        }
        val ratio = heapKb / (limitMb * 1024.0)
        notes += String.format(
            Locale.US,
            "Java Heap PSS ≈ %.1f%% / %d MB（%d KB；PSS 与 allocated 不等价，只用于看趋势）",
            ratio * 100,
            limitMb,
            heapKb,
        )
        val severity = when {
            ratio >= 0.90 -> Severity.P0
            ratio >= 0.75 -> Severity.P1
            else -> null
        } ?: return
        findings += Finding(
            id = "heap-pressure",
            severity = severity,
            confidence = Confidence.INFERRED,
            title = String.format(Locale.US, "Java 堆接近上限：%.0f%% / %d MB", ratio * 100, limitMb),
            evidence = listOf("Java Heap PSS = $heapKb KB", "堆上限 = $limitMb MB"),
            impact = "继续增长就会 OOM 闪退；长会话、大图、流式刷新时最先暴露",
            fix = "找最近新增的「每帧/每条消息」分配点，优先把可复用的中间结构缓存起来，避免重复构建整表",
            verify = "同样的操作强度下 Java Heap PSS 不再单调上升",
        )
    }

    private fun collectCapabilities(input: Input, findings: MutableList<Finding>) {
        for ((key, label, path) in CAPABILITY_SPECS) {
            val granted = input.capabilities[key] ?: continue
            if (granted) continue
            // 有 root 时通知读取可由 root 兜底，因此只作为线索而不是缺陷。
            if (key == "notifications" && input.rootAvailable) continue
            findings += Finding(
                id = "capability-$key",
                severity = Severity.P1,
                confidence = Confidence.OBSERVED,
                title = label,
                evidence = listOf("capability[$key] = false", "root = ${input.rootAvailable}"),
                impact = "依赖该能力的工具会被直接拒绝，模型只能改用更绕的方式或放弃",
                fix = "系统设置里补齐权限：$path",
                verify = "重新调用依赖该能力的工具不再返回 *_REQUIRED/*_UNAVAILABLE",
            )
        }
    }

    private fun collectCode(
        input: Input,
        clues: MutableList<Clue>,
        unavailable: MutableList<String>,
    ) {
        val code = input.codeClues
        if (code == null) {
            unavailable += "代码线索：未提供源码目录（真机发布包通常没有源码，属正常）"
            return
        }
        val todoKnown = code.todoCount >= 0
        val gitKnown = code.uncommittedFiles >= 0
        if (!todoKnown) {
            clues += Clue("TODO/FIXME 扫描失败或超时，本次无法统计", code.sourceRoot)
        }
        if (!gitKnown) {
            // 设备上通常没有 git。以前这里会把空输出当成 0，报出「工作区干净」——那是假话。
            clues += Clue("读不到 git 状态（这台设备通常没有 git），未提交改动数未知", code.sourceRoot)
        }
        if (todoKnown && code.todoCount > 0) {
            clues += Clue(
                title = "源码里有 ${code.todoCount} 个文件标了 TODO/FIXME/XXX",
                evidence = code.sourceRoot,
            )
        }
        if (gitKnown && code.uncommittedFiles > 0) {
            clues += Clue(
                title = "工作区有 ${code.uncommittedFiles} 个未提交改动",
                evidence = code.sourceRoot,
            )
        }
        if (todoKnown && gitKnown && code.todoCount == 0 && code.uncommittedFiles == 0) {
            clues += Clue(title = "源码里没有 TODO/FIXME，工作区是干净的", evidence = code.sourceRoot)
        }
    }
}
