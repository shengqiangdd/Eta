package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `top_memory_apps` 的命令契约与输出解析。
 *
 * 样本取自真机（PKX110 / Android 16，2026-10-21）两种 ps 实现的**真实输出**，
 * 不是手写的理想数据：BusyBox 会把 RSS 人类化、给 args 加 `{comm}` 前缀，
 * toybox 的 comm 是二进制名（`app_process64`）。
 */
class AgentProcessRssListTest {
    private val toyboxSample =
        """
          PID    RSS ARGS                      
            1   6724 init second_stage
            2      0 [kthreadd]
            3      0 [pool_workqueue_release]
        17907 305192 io.github.mangi.eta
         5890 440123 com.android.systemui
        """.trimIndent()

    private val busyboxSample =
        """
        PID   RSS  COMMAND
            1 6724 /system/bin/init second_stage
            2    0 [kthreadd]
        17907 298m {ithub.mangi.eta} io.github.mangi.eta
         5890 440m {ndroid.systemui} com.android.systemui
        """.trimIndent()

    @Test
    fun `command only uses columns both ps implementations accept`() {
        assertEquals(emptyList<String>(), AgentProcessRssList.unsupportedColumns(AgentProcessRssList.COMMAND))
        // 大写列名在 BusyBox 上会被拒（`ps: bad -o argument 'PID'`），所以命令里不许出现。
        assertTrue(AgentProcessRssList.columnsOf(AgentProcessRssList.COMMAND).none { it != it.lowercase() })
    }

    @Test
    fun `old name column would be rejected by the contract`() {
        // 回归：曾用 `ps -A -o PID,RSS,NAME`，在 BusyBox 下整条命令失败。
        assertEquals(
            listOf("PID", "RSS", "NAME"),
            AgentProcessRssList.unsupportedColumns("ps -A -o PID,RSS,NAME"),
        )
    }

    @Test
    fun `toybox output keeps full process names`() {
        val rows = AgentProcessRssList.parse(toyboxSample, 10)
        assertEquals(listOf(5890, 17907, 1, 2, 3), rows.map { it.pid })
        assertEquals("com.android.systemui", rows.first().name)
        assertEquals("io.github.mangi.eta", rows[1].name)
        assertEquals(6724L, rows.first { it.pid == 1 }.rssKb)
        assertTrue(rows.any { it.name == "[kthreadd]" })
    }

    @Test
    fun `busybox output is parsed despite human readable rss and comm prefix`() {
        val rows = AgentProcessRssList.parse(busyboxSample, 10)
        val app = rows.first { it.pid == 17907 }
        assertEquals("io.github.mangi.eta", app.name)
        assertEquals(298L * 1024L, app.rssKb)
        assertEquals("init", rows.first { it.pid == 1 }.name)
    }

    @Test
    fun `rss suffixes follow binary units and unknown formats are rejected`() {
        assertEquals(331L * 1024L, AgentProcessRssList.parseRssKb("331m"))
        assertEquals(1_610_612_736L / 1024L, AgentProcessRssList.parseRssKb("1.5g"))
        assertEquals(6724L, AgentProcessRssList.parseRssKb("6724"))
        // 后缀 k 表示 KiB（BusyBox 的基准单位），换算是 ×1
        assertEquals(2L, AgentProcessRssList.parseRssKb("2K"))
        // 认不出来就跳过，不能把 `331m` 当 331 KB，也不能把负数带进排行。
        assertNull(AgentProcessRssList.parseRssKb("n/a"))
        assertNull(AgentProcessRssList.parseRssKb("-1"))
        assertNull(AgentProcessRssList.parseRssKb(""))
    }

    @Test
    fun `malformed lines are skipped instead of failing the batch`() {
        val stdout =
            """
            PID RSS ARGS
            not-a-pid 100 init
            10 not-a-number init
            11 100
            12 -1 broken
                  
            13 2048 ok
            """.trimIndent()
        val rows = AgentProcessRssList.parse(stdout, 10)
        assertEquals(listOf(13), rows.map { it.pid })
    }

    @Test
    fun `limit is clamped into the published range`() {
        assertEquals(1, AgentProcessRssList.parse(toyboxSample, 0).size)
        assertEquals(2, AgentProcessRssList.parse(toyboxSample, 2).size)
        // 样本只有 5 行有效数据：上限夹取既不能越界，也不能臆造行。
        assertEquals(5, AgentProcessRssList.parse(toyboxSample, 999).size)
    }

    @Test
    fun `argument lists are reduced to a readable name`() {
        assertEquals("sh", AgentProcessRssList.displayName("sh -c printf 'x'"))
        assertEquals("busybox", AgentProcessRssList.displayName("/data/adb/ksu/bin/busybox ash -c true"))
        assertEquals("[kworker/R-rcu_g]", AgentProcessRssList.displayName("[kworker/R-rcu_g]"))
        assertEquals("io.github.mangi.eta", AgentProcessRssList.displayName("{ithub.mangi.eta} io.github.mangi.eta"))
        assertEquals("", AgentProcessRssList.displayName("   "))
    }
}
