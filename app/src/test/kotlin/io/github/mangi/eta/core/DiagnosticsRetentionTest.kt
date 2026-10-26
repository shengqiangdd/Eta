package io.github.mangi.eta.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 诊断数据回收的判据测试。
 *
 * 重点守两件事：**最近的永远不删**（正在看的 run 详情不能自己消失）、
 * **只在这两个目录里删**（不能误伤数据库和会话）。
 */
class DiagnosticsRetentionTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    private fun file(dir: File, name: String, bytes: Int, mtime: Long): File =
        File(dir, name).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(mtime)
        }

    @Test
    fun `超出预算从旧到新回收，最近若干个始终保留`() {
        val dir = temporary.newFolder("run-traces")
        // 5 个 3MB，预算 8MB：保留最新 2 个（6MB），第 3 个开始超预算。
        (0 until 5).forEach { i -> file(dir, "run-$i.jsonl", 3 * 1024 * 1024, now - i * 1000L) }
        val victims = DiagnosticsRetention.selectRunTraces(
            dir,
            budgetBytes = 8L * 1024 * 1024,
            minKeep = 2,
        )
        assertEquals(3, victims.size)
        assertTrue(victims.none { it.name == "run-0.jsonl" || it.name == "run-1.jsonl" })
    }

    @Test
    fun `最小保留数优先于预算`() {
        val dir = temporary.newFolder("run-traces")
        file(dir, "big.jsonl", 20 * 1024 * 1024, now)
        val victims = DiagnosticsRetention.selectRunTraces(
            dir,
            budgetBytes = 8L * 1024 * 1024,
            minKeep = 1,
        )
        assertTrue(victims.isEmpty())
    }

    @Test
    fun `只认得 jsonl，不碰同目录里的其他文件`() {
        val dir = temporary.newFolder("run-traces")
        (0 until 4).forEach { i -> file(dir, "run-$i.jsonl", 4 * 1024 * 1024, now - i * 1000L) }
        val keep = file(dir, "notes.txt", 4 * 1024 * 1024, now - 9000L)
        val victims = DiagnosticsRetention.selectRunTraces(
            dir,
            budgetBytes = 8L * 1024 * 1024,
            minKeep = 1,
        )
        assertTrue(keep !in victims)
        // 4 个 4MB：第 1 个（最新）保留；第 2 个累计 8MB 恰好不超；第 3、4 个超预算被删。
        assertEquals(2, victims.size)
    }

    @Test
    fun `spill 只回收够老且不在最近几个之内的目录`() {
        val dir = temporary.newFolder("spill")
        val fresh = File(dir, "run-new").apply { mkdirs(); setLastModified(now) }
        val old = File(dir, "run-old").apply { mkdirs(); setLastModified(now - 10 * day) }
        val older = File(dir, "run-older").apply { mkdirs(); setLastModified(now - 20 * day) }
        val victims = DiagnosticsRetention.selectSpillDirs(
            dir,
            now = now,
            maxAgeMs = 7 * day,
            minKeep = 1,
        )
        assertTrue(fresh !in victims)
        assertEquals(listOf(old.name, older.name), victims.map { it.name })
    }

    @Test
    fun `保留集本身超过硬上限时按预算强删，最新一份永不删`() {
        val dir = temporary.newFolder("run-traces")
        // 24 个 1MB = 24MB；minKeep=20 单靠它自己就占 20MB（预算 8MB 的 2.5 倍）。
        (0 until 24).forEach { i -> file(dir, "run-$i.jsonl", 1024 * 1024, now - i * 1000L) }
        val victims = DiagnosticsRetention.selectRunTraces(
            dir,
            budgetBytes = 8L * 1024 * 1024,
            minKeep = 20,
        )
        // 24MB > 8MB×2 → 下限退到 1，删到 ≤ 8MB：删 16 个，留 8 个，最新一份不动。
        assertEquals(16, victims.size)
        assertTrue(victims.none { it.name == "run-0.jsonl" })
        assertTrue(victims.any { it.name == "run-23.jsonl" })
    }

    @Test
    fun `run 真的删掉文件并统计释放空间`() {
        val root = temporary.newFolder("files")
        val traces = File(root, "run-traces").apply { mkdirs() }
        // 25 个 400KB = 10MB，超过 8MB 预算：最近 20 个按 minKeep 保留，多出的 5 个被删。
        (0 until 25).forEach { i -> file(traces, "run-$i.jsonl", 400 * 1024, now - i * 1000L) }
        val result = DiagnosticsRetention.run(root, now)
        assertEquals(5, result.deleted)
        assertTrue(result.freedBytes >= 5L * 400 * 1024)
        assertEquals(20, traces.listFiles()!!.count { it.name.endsWith(".jsonl") })
    }
}
