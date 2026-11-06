package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.memory.AgentFailureHints
import io.github.mangi.eta.agent.trace.AgentSelfStats
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

    internal enum class Domain { CRASH, RESOURCE, CAPABILITY, CODE, AGENT }

    internal data class CrashFile(val name: String, val timeMillis: Long, val text: String)

    /**
     * 崩溃按「是否发生在当前安装包之后」分组。
     *
     * 为什么必须分组：旧版本的崩溃和当前版本并列、都标 P0 时，用户会以为现在还在崩。
     * 真机取证（2026-11-03，PKX110）：两条 P0 都早于当前安装包 —— 其中 OOM 那条
     * 早在 §32.37 就修掉了，却照样被报成「进程直接中止，用户看到闪退」。
     */
    internal data class CrashSplit(
        val current: List<CrashFile>,
        val historical: List<CrashFile>,
    )

    /**
     * @param installUpdatedMillis 当前安装包的 lastUpdateTime；null / ≤0 表示读不到
     *
     * 读不到安装时间时**全部算当前**：那不是「都旧了」，而是「不知道新旧」，
     * 把崩溃藏进历史组等于替用户假定它们已经修好了。
     */
    internal fun splitCrashesByInstall(
        crashes: List<CrashFile>,
        installUpdatedMillis: Long?,
    ): CrashSplit {
        val install = installUpdatedMillis?.takeIf { it > 0L }
            ?: return CrashSplit(current = crashes, historical = emptyList())
        val (historical, current) = crashes.partition { it.timeMillis < install }
        return CrashSplit(current = current, historical = historical)
    }

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
        /**
         * boot.log 的尾部文本（启动打点在里面）。null 表示没读到文件，
         * 与「读了但没有冷启动记录」不是同一个意思。
         */
        val startupLog: String? = null,
        val capabilities: Map<String, Boolean> = emptyMap(),
        val codeClues: CodeClues? = null,
        /**
         * 自己的近期表现。null 表示「没读到追踪记录」，与「读了但是空的」不是同一个意思：
         * 前者要进 `unavailable`，后者只能进 `notes`。
         */
        val selfStats: AgentSelfStats.Stats? = null,
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

    /** 平台/框架帧：这些行动不了，改不了一点。 */
    private val PLATFORM_FRAME = Regex(
        "^at (java|javax|kotlin|kotlinx|android|com\\.android|androidx|dalvik|libcore|org\\.chromium|com\\.google\\.android)\\.",
    )

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
        if (Domain.AGENT in input.domains) {
            collectAgent(input, findings, clues, unavailable, notes)
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
        val split = splitCrashesByInstall(
            mine.sortedByDescending { it.timeMillis },
            input.installUpdatedMillis,
        )
        // 只把「当前安装包之后」的崩溃当缺陷：旧版本的崩溃证明不了现在还在崩。
        split.current.forEach { file ->
            findings += classifyFailure(input.packageName, file, input.installUpdatedMillis)
        }
        // 历史崩溃不当缺陷，但也不能默默丢掉：「已修」和「没再发生」是两件事，
        // 用户需要知道旧版本里崩过什么，只是不该为此紧张。
        if (split.historical.isNotEmpty()) {
            val install = input.installUpdatedMillis?.takeIf { it > 0L }
            clues += Clue(
                title = "另有 ${split.historical.size} 次崩溃发生在当前安装包" +
                    (install?.let { "（安装于 ${stamp(it)}）" } ?: "") +
                    "之前：只能证明旧版本的问题，但不能据此认为已修",
                evidence = split.historical.take(MAX_EVIDENCE_LINES)
                    .joinToString("、") { "${it.name}（${stamp(it.timeMillis)}）" },
            )
        }
        if (split.current.size >= 2) {
            val uptime = input.processStartedMillis?.let { input.nowMillis - it }
            if (uptime != null && uptime in 0 until 30 * 60_000L) {
                // 「进程刚起」有两个原因：崩了重启，或者刚装包/更新把进程换掉了。
                // 真机上踩过第二种：装完包进程刚起 217 秒，而窗口内两次崩溃都发生在装包之前，
                // 却被报成 P0「崩溃循环」。分组之后这里只剩本次更新之后的崩溃。
                findings += Finding(
                    id = "restart-loop",
                    severity = Severity.P0,
                    confidence = Confidence.INFERRED,
                    title = "疑似崩溃循环：${input.windowHours} 小时内 ${split.current.size} 次崩溃，当前进程只跑了 ${uptime / 1000} 秒",
                    evidence = split.current.take(MAX_EVIDENCE_LINES).map { it.name },
                    impact = "用户会反复看到闪退或白屏，进行中的任务无法完成",
                    fix = "先修最新一次崩溃（它最可能仍在复现），修完观察一个完整会话周期；只修旧的会被新的掩盖",
                    verify = "连续运行 ≥ 1 小时且窗口内崩溃计数不再增加",
                )
            }
        }
    }

    private fun classifyFailure(packageName: String, file: CrashFile, installUpdatedMillis: Long? = null): Finding {
        val isAnr = file.name.contains("anr")
        val lines = file.text.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList()
        val exception = lines.firstOrNull { EXCEPTION_LINE.matches(it.trim()) }?.trim()
        val allFrames = lines.map { it.trim() }.filter { CODE_FRAME.containsMatchIn(it) }
        val frames = allFrames.take(MAX_EVIDENCE_LINES)
        val actionable = allFrames.firstOrNull { it.contains("$packageName.") }
            ?: allFrames.firstOrNull { !PLATFORM_FRAME.containsMatchIn(it) }
        val evidence = buildList {
            add("文件：${file.name}")
            exception?.let { add("异常：$it") }
            addAll(frames)
            // 前几帧常常是 HashMap.resize / ConscryptEngine.unwrap 这类平台代码，
            // 真正能改的那一帧往往被截在 4 帧之外 —— §32.37 那次 OOM 自己的帧就在第 5 位，
            // 工具给的证据里只剩「去改 HashMap」。把首个可改的帧补上。
            if (actionable != null && actionable !in frames) add("首个可改的帧：$actionable")
            val install = installUpdatedMillis?.takeIf { it > 0L }
            if (install != null && file.timeMillis < install) {
                add("早于当前安装包（${stamp(install)}）：证明的是旧版本的问题")
            }
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
        // 启动分段放在内存判断之前：内存读不到时也该能看到启动账目（第 78 轮同类问题）。
        startupBreakdown(input.startupLog)?.let { notes += it }
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
            unavailable += "代码线索：源码目录不可用（未提供，或设备上找不到该路径；真机发布包通常没有源码，属正常）"
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
                title = "源码里有 ${code.todoCount} 个文件的注释标了 TODO/FIXME",
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

    /**
     * 自己做得怎么样 —— 唯一一个「主语是 Eta 本身」的域。
     *
     * 判定原则（比设备域更严，因为这里很容易变成自我批评表演）：
     * 1. **只报能落到具体动作的**：「失败率偏高」不算，要能说出「先读再改」这种改法。
     * 2. **样本不足不报**：调用次数太少时失败率毫无意义（1/2 = 50%）。
     * 3. **分工具看，不看全局**：全局失败率 4.6% 看着很好，而 edit_file 17.6% 才是真问题。
     * 4. **失败按错误码分组**：READ_REQUIRED 与 FILE_CHANGED 需要完全不同的动作。
     */
    private fun collectAgent(
        input: Input,
        findings: MutableList<Finding>,
        clues: MutableList<Clue>,
        unavailable: MutableList<String>,
        notes: MutableList<String>,
    ) {
        val stats = input.selfStats
        if (stats == null) {
            unavailable += "自我表现：没读到运行追踪（run-traces 为空或不可读）；本次无法评价自己的工具使用"
            return
        }
        if (!stats.hasData) {
            notes += "自我表现：还没有可用的运行记录，下一次运行后再看"
            return
        }
        notes += String.format(
            Locale.US,
            "自我表现口径：最近 %d 次运行（其中 %d 次用过工具），工具调用 %d 次、失败 %d 次（%.1f%%）",
            stats.runs,
            stats.runsWithTools,
            stats.toolCalls,
            stats.toolFailures,
            stats.toolFailureRate * 100,
        )
        // 只读了一部分追踪明细时必须说清楚，否则 per-tool 归因会漏掉一部分失败却看不出来。
        // 说清「为什么没读」：实际是按体积预算没选中展开（pickForDetail），
        // 文件都还在。旧文案写成「文件已回收或损坏」，会把人往「数据丢了」带——
        // 2026-11-06 我看到这句就去查磁盘和保留策略，结果 80 个文件全在、行数正常。
        if (stats.unreadFailureRuns > 0) {
            notes += "其中 ${stats.unreadFailureRuns} 次运行的明细未展开（按体积预算只展开了部分，不是数据丢失；" +
                "要纳入统计就缩小 hours 窗口）：它们计入上面的失败总数，但没参与下面的按工具归因"
        }

        // ---- 1. 具体工具的失败集中在哪 ----
        stats.tools.forEach { tool ->
            // 样本不足不报：调用次数太少时失败率是噪声。
            if (tool.calls < MIN_CALLS_FOR_RATE) return@forEach
            if (tool.failureRate < HIGH_FAILURE_RATE) return@forEach
            val topCode = tool.failureCodes.entries.firstOrNull()
            val codeName = topCode?.key
            findings += Finding(
                id = "agent-tool-fail-${tool.name}",
                severity = if (tool.failureRate >= SEVERE_FAILURE_RATE) Severity.P1 else Severity.P2,
                confidence = Confidence.OBSERVED,
                title = String.format(
                    Locale.US,
                    "我调用 %s 时失败率 %.0f%%（%d/%d 次）",
                    tool.name,
                    tool.failureRate * 100,
                    tool.failures,
                    tool.calls,
                ),
                evidence = buildList {
                    add("工具：${tool.name}")
                    add("调用 ${tool.calls} 次，失败 ${tool.failures} 次")
                    tool.failureCodes.entries.take(MAX_EVIDENCE_LINES).forEach { (code, count) ->
                        add("失败码 $code：$count 次")
                    }
                    val uncategorized = tool.failures - tool.failureCodes.values.sum()
                    if (uncategorized > 0) add("另有 $uncategorized 次失败没有错误码（只能看 message）")
                },
                impact = "这部分失败是本可以避免的重做：同样的请求要再花一遍 token 与时间，长任务里会累积成明显延迟",
                fix = agentFixFor(tool.name, codeName),
                verify = "按上面那条改法做之后，新跑的 ${MIN_CALLS_FOR_RATE} 次以上同类调用里该失败码不再出现",
            )
        }

        // ---- 1.5 耗时：到底哪里慢 ----
        // 失败率只能回答「哪里错」，回答不了「为什么等那么久」。耗时数据一直在追踪里
        // （每个工具记录中有 `ms`），但以前只被展示层用，自检看不到，于是「自检全绿」
        // 完全不代表「不慢」。这里把它接上，并且不拿 calls 当分母（没记录的不算 0 秒）。
        val timedTools = stats.tools.filter { it.timedCalls > 0 }
        if (timedTools.isNotEmpty()) {
            val byMax = timedTools.sortedByDescending { it.maxMs }
            notes += "耗时（有记录的调用）：最慢单次 " + byMax.take(SLOW_TOOLS_LISTED).joinToString("、") {
                "${it.name} ${secondsOf(it.maxMs)}"
            } + "；平均 " + byMax.take(2).joinToString("、") {
                "${it.name} ${secondsOf(it.avgMs)}"
            }
            byMax.firstOrNull { it.maxMs >= SLOW_CALL_MS }?.let { tool ->
                val waiting = tool.name == "terminal_job"
                findings += Finding(
                    id = "agent-tool-slow-${tool.name}",
                    severity = Severity.P2,
                    confidence = Confidence.OBSERVED,
                    title = "${tool.name} 有单次调用花了 ${secondsOf(tool.maxMs)}",
                    evidence = buildList {
                        add("工具：${tool.name}")
                        add("最慢 ${secondsOf(tool.maxMs)}（平均 ${secondsOf(tool.avgMs)}，共 ${tool.timedCalls} 次有耗时记录）")
                        add("本次窗口内调用 ${tool.calls} 次")
                        if (waiting) add("terminal_job 的耗时含等待后台任务本身，不一定都是工具开销")
                    },
                    impact = if (waiting) {
                        "长任务占住了这一轮：期间不能做别的，也不知道进行到哪一步"
                    } else {
                        "用户要盯着屏幕等这么久，期间看不出进展；接近超时上限时结果还可能是被截断的"
                    },
                    fix = if (waiting) {
                        "把单个后台任务拆小，或断点式检查（多次 read 日志）而不是一次等到结束"
                    } else {
                        "拆小命令；预期超过 30 秒的改用 terminal_job 后台跑并分次读日志"
                    },
                    verify = "新跑的同类调用里不再出现 ≥ ${secondsOf(SLOW_CALL_MS)} 的单次耗时",
                )
            }
        }

        // ---- 2. 空转：多轮往返却没调任何工具 ----
        // 只统计已展开的明细。unreadFailureRuns>0 说明有运行没读成，
        // 这时「未发现空转」不能当成「没有空转」。
        val worstIdle = stats.idleRuns.firstOrNull()
        if (worstIdle != null && stats.idleRuns.size >= IDLE_RUNS_FOR_FINDING) {
            findings += Finding(
                id = "agent-idle-runs",
                severity = Severity.P2,
                confidence = Confidence.OBSERVED,
                title = "有 ${stats.idleRuns.size} 次运行多轮往返却没有调用任何工具",
                evidence = stats.idleRuns.take(MAX_EVIDENCE_LINES).map {
                    "${it.runId.take(20)}…：${it.rounds} 轮、0 次工具调用"
                },
                impact = "纯对话多轮不算问题；但若是「该动手时没动手」，用户看到的是反复解释而不是结果",
                fix = "检查这些回合是不是在重复确认已足够确定的事；能直接执行的就执行，把不确定性写成一句假设再往下做",
                verify = "后续运行里「多轮且零工具调用」的占比下降，且用户没有重复催促",
            )
        } else if (worstIdle != null) {
            clues += Clue(
                title = "有 ${stats.idleRuns.size} 次运行多轮往返却没调工具（单次不构成问题，供观察）",
                evidence = "${worstIdle.runId.take(20)}…：${worstIdle.rounds} 轮",
            )
        } else if (stats.unreadFailureRuns > 0) {
            // 没发现空转不等于没有：空转要从全文看「几轮 + 零工具」，
            // 没展开的运行里有没有空转，本次是不知道的。
            notes += "空转检测：有 ${stats.unreadFailureRuns} 次运行的明细没展开，未发现空转不代表它们里没有"
        }

        // ---- 3. 上下文压缩：长会话里的信息丢失风险 ----
        if (stats.compactionRuns >= COMPACTION_RUNS_FOR_CLUE) {
            clues += Clue(
                title = "有 ${stats.compactionRuns} 次运行触发过上下文压缩（共 ${stats.compactions} 次）：" +
                    "压缩会丢弃早期细节，长任务里要主动把关键结论写进文件或记忆",
                evidence = "窗口内 ${stats.runs} 次运行",
            )
        }

        // ---- 4. 网络重试：不是我写错了，但也影响交付 ----
        if (stats.retryRuns >= RETRY_RUNS_FOR_CLUE) {
            clues += Clue(
                title = "有 ${stats.retryRuns} 次运行发生过模型请求重试（共 ${stats.retries} 次），多为网络或限流，非工具用法问题",
                evidence = "窗口内 ${stats.runs} 次运行",
            )
        }

        // ---- 5. 整体失败率：只在真的高时才报 ----
        if (stats.runsWithTools >= MIN_RUNS_FOR_OVERALL && stats.toolFailureRate >= SEVERE_FAILURE_RATE) {
            findings += Finding(
                id = "agent-overall-fail",
                severity = Severity.P1,
                confidence = Confidence.OBSERVED,
                title = String.format(
                    Locale.US,
                    "整体工具失败率 %.0f%%（%d/%d），高于本仓常见水平（中位数约 5%%）",
                    stats.toolFailureRate * 100,
                    stats.toolFailures,
                    stats.toolCalls,
                ),
                evidence = listOf(
                    "用过工具的运行 ${stats.runsWithTools} 次",
                    "工具调用 ${stats.toolCalls} 次、失败 ${stats.toolFailures} 次",
                ),
                impact = "失败重做直接消耗时间与额度，而且在长任务里会放大成「跑很久还是没结果」",
                fix = "先看上面列出的具体工具与失败码，从次数最多的那个改起；不要笼统地「小心一点」",
                verify = "连续若干个运行窗口内整体失败率回到个位数",
            )
        }
    }

    /**
     * 失败码 → 具体改法。
     *
     * 表本身在 [AgentFailureHints]，与「失败经验」共用同一份口径：
     * 自审给模型看的建议，和写进记忆的改法必须是同一句话，
     * 否则模型会从两个地方学到两套做法。
     */
    private fun agentFixFor(tool: String, code: String?): String = AgentFailureHints.fixFor(tool, code)

    /** 毫秒转成人看得懂的秒：小于 1 秒给两位小数（否则显示 0.0s）。 */
    private fun secondsOf(millis: Long): String {
        val seconds = millis / 1000.0
        return if (seconds >= 1.0) {
            String.format(Locale.US, "%.1fs", seconds)
        } else {
            String.format(Locale.US, "%.2fs", seconds)
        }
    }

    /** 样本门槛与阈值。数值取自本仓 72 份真实追踪记录（中位失败率 5.2%）。 */
    private const val MIN_CALLS_FOR_RATE = 8
    /**
     * 单次调用多慢值得单独拎出来：30 秒。
     *
     * 拿真机最近 40 次运行的实测定的（除 terminal / terminal_job 外，
     * 所有工具的单次耗时都在 10 秒以内，多数不到 3 秒）；terminal 最慢一次 180.2 秒，
     * 正好是它的超时上限 —— 那时结果多半已经被截断，用户白等 3 分钟。
     */
    private const val SLOW_CALL_MS = 30_000L
    /** 耗时榜里最多列几个工具：再多也不是「哪里慢」而是全量报表了。 */
    private const val SLOW_TOOLS_LISTED = 3
    private const val MIN_RUNS_FOR_OVERALL = 10
    private const val HIGH_FAILURE_RATE = 0.12f
    private const val SEVERE_FAILURE_RATE = 0.25f
    private const val IDLE_RUNS_FOR_FINDING = 2
    private const val COMPACTION_RUNS_FOR_CLUE = 3
    private const val RETRY_RUNS_FOR_CLUE = 3
}

/** boot.log 一行：`11-06 12:35:52.016 [1708] startup first-frame ui=+281ms process=+4087ms` */
private val STARTUP_LINE = Regex("""\[(\d+)] startup ([\w-]+) (.*)""")
private val PROCESS_MS = Regex("""process=\+(\d+)ms""")
private val UI_MS = Regex("""ui=\+(\d+)ms""")

/**
 * 最近一次冷启动的分段账。
 *
 * 为什么要单独算：实测冷启 4 秒，但 Application 只占 101ms、首帧 281ms，
 * **中间 3.7 秒在系统侧**（进程起来到 Activity 创建之间），代码里根本没有执行点。
 * 不把这个口径写清楚，看到 4 秒只会去优化渲染和初始化，方向从一开始就是错的。
 *
 * 返回 null 表示日志里找不到完整的同进程记录（比如只写了 first-frame 却没写 app-ready）。
 */
internal fun startupBreakdown(log: String?): String? {
    if (log.isNullOrBlank()) return null
    val rows = log.lineSequence().mapNotNull { line ->
        val match = STARTUP_LINE.find(line) ?: return@mapNotNull null
        Triple(match.groupValues[1], match.groupValues[2], match.groupValues[3])
    }.toList()
    // 取最后一条 first-frame，再按它的 pid 回找同一进程的其它打点：
    // 混进别的进程会把两个进程的时间拼成一个假的耗时。
    val firstFrame = rows.lastOrNull { it.second == "first-frame" } ?: return null
    val pid = firstFrame.first
    fun processMsOf(label: String): Int? = rows
        .lastOrNull { it.first == pid && it.second == label }
        ?.third
        ?.let { PROCESS_MS.find(it)?.groupValues?.get(1)?.toIntOrNull() }
    val appReady = processMsOf("app-ready") ?: return null
    val activityCreated = processMsOf("activity-created") ?: return null
    val firstFrameUi = UI_MS.find(firstFrame.third)?.groupValues?.get(1)?.toIntOrNull()
    val wait = (activityCreated - appReady).coerceAtLeast(0)
    return "最近冷启：Application ${appReady}ms → 等待 ${wait}ms → 首帧 ${firstFrameUi?.toString() ?: "?"}ms" +
        "（等待段在系统侧，代码里没有执行点；优化渲染／初始化改不到它）"
}
