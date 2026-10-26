package io.github.mangi.eta.core

import java.io.File

/**
 * 诊断数据的保留策略。
 *
 * 为什么需要：`files/run-traces`（一次 run 一个 JSONL，单文件可达 3 MB）与
 * `files/agent/spill`（工具结果溢出目录）都只增不减 —— 实测这台设备上 run-traces 32.5 MB、
 * spill 126 个目录，从装完起就没清过。它们都是**可丢失的观测数据**（丢了只影响回溯，不影响对话），
 * 一直留着却会实打实挤占存储，所以按「预算 + 最小保留数」回收。
 *
 * 三条约束：
 * - 只在这两个目录内删东西，绝不碰数据库、会话、附件、备份；
 * - 最新的一条永远保留（正在看的 run 详情不能自己消失）；
 * - 任何异常静默降级：诊断回收失败绝不能让 App 起不来。
 */
internal object DiagnosticsRetention {

    private const val RUN_TRACE_DIR = "run-traces"
    private const val SPILL_DIR = "agent/spill"

    /** run-traces 总量预算；单文件可达 3 MB，8 MB 大约够留最近十几次运行。 */
    private const val RUN_TRACE_BUDGET_BYTES = 8L * 1024 * 1024

    /** 无论多大，最近多少个 trace 一律保留。 */
    private const val RUN_TRACE_MIN_KEEP = 20

    /**
     * 最小保留数的硬上限倍数。
     *
     * 为什么需要：`RUN_TRACE_MIN_KEEP=20` 配上单个 trace 可达 3 MB，意味着“最小保留”
     * 本身就能占 60 MB —— 预算 8 MB 完全变成摆设（实测 24 个文件 8.4 MB，
     * 每次启动都“没超预算可删”）。所以再加一道：保留集本身超过 `预算 × 本倍数` 时，
     * 最小保留数退到 1，按最旧的先删，直到回到预算以内。最新的一份永远不删。
     */
    private const val RUN_TRACE_HARD_CAP_FACTOR = 2

    /** spill 目录超过这个年龄才回收（工具结果溢出通常只在排查最近的 run 时有用）。 */
    private const val SPILL_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    private const val SPILL_MIN_KEEP = 3

    data class Result(val deleted: Int, val freedBytes: Long)

    fun run(filesDir: File, now: Long = System.currentTimeMillis()): Result {
        val traceDir = File(filesDir, RUN_TRACE_DIR)
        val spillDir = File(filesDir, SPILL_DIR)
        val traceFiles = traceDir.listFiles { file -> file.isFile && file.name.endsWith(".jsonl") }.orEmpty()
        val traceBytes = traceFiles.sumOf { it.length() }
        val spillCount = spillDir.listFiles { file -> file.isDirectory }?.size ?: 0
        val victims = ArrayList<File>()
        victims += selectRunTraces(traceDir)
        victims += selectSpillDirs(spillDir, now)
        var deleted = 0
        var freed = 0L
        victims.forEach { file ->
            val size = if (file.isDirectory) directorySize(file) else file.length()
            val ok = runCatching { file.deleteRecursively() }.getOrDefault(false)
            if (ok) {
                deleted++
                freed += size
            }
        }
        // 每次启动都记一行（含「什么都没删」）：早期只在删了才记，结果无法区分
        // 「没超预算」与「根本没跑 / 跑挂了」，在真机上只能靠猜。
        runCatching {
            AndroidAgentLogger.infoPersisted(
                "diagnostics retention traces=${traceFiles.size}/${traceBytes / 1024}KB " +
                    "spill=$spillCount deleted=$deleted freed=${freed / 1024}KB"
            )
        }
        return Result(deleted, freed)
    }

    /**
     * 从旧到新删到预算以内，但默认保留最近 [minKeep] 个。
     *
     * 保留集本身超过 `budgetBytes × hardCapFactor` 时，[minKeep] 退到 1（见
     * [RUN_TRACE_HARD_CAP_FACTOR]）；最新的一份不论多大都不删。
     */
    internal fun selectRunTraces(
        dir: File,
        budgetBytes: Long = RUN_TRACE_BUDGET_BYTES,
        minKeep: Int = RUN_TRACE_MIN_KEEP,
        hardCapFactor: Int = RUN_TRACE_HARD_CAP_FACTOR,
    ): List<File> {
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".jsonl") }
            ?: return emptyList()
        val newestFirst = files.sortedByDescending { it.lastModified() }
        var total = newestFirst.sumOf { it.length() }
        if (total <= budgetBytes) return emptyList()
        // 下限在开始时**定一次**：一边删一边重算会因为“总量已降到 2 倍以内”
        // 又把下限抬回 minKeep，于是永远停在 2 倍预算上，删不到预算本身。
        val floor = if (total > budgetBytes * hardCapFactor) 1 else minKeep
        val victims = ArrayList<File>()
        for (index in newestFirst.indices.reversed()) {
            if (total <= budgetBytes || index < floor) break
            val file = newestFirst[index]
            victims += file
            total -= file.length()
        }
        return victims
    }

    /** 只回收「够老且不是最近几个」的目录。 */
    internal fun selectSpillDirs(
        dir: File,
        now: Long = System.currentTimeMillis(),
        maxAgeMs: Long = SPILL_MAX_AGE_MS,
        minKeep: Int = SPILL_MIN_KEEP,
    ): List<File> {
        val dirs = dir.listFiles { file -> file.isDirectory } ?: return emptyList()
        val newestFirst = dirs.sortedByDescending { it.lastModified() }
        return newestFirst.withIndex()
            .filter { (index, file) -> index >= minKeep && now - file.lastModified() > maxAgeMs }
            .map { it.value }
    }

    private fun directorySize(dir: File): Long {
        val children = dir.listFiles() ?: return 0L
        var total = 0L
        children.forEach { child ->
            total += if (child.isDirectory) directorySize(child) else child.length()
        }
        return total
    }
}
