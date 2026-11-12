package io.github.mangi.eta.agent.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话保留策略。
 *
 * 这些判定原先一条都没实现（`requestRemove` 在生产里从未被调用、挂载引用根本不存在），
 * 导致 entries 只增不减，而每个 Entry 背后可能是一个 WebView。真机三次 OOM
 * （256MB 堆用满、发生在长时间使用后）与此吻合。
 */
class BrowserSessionRetentionTest {

    private fun candidate(
        key: String,
        runs: Int = 0,
        mounted: Int = 0,
        lastUsedAt: Long = 0L,
    ) = BrowserSessionRetention.Candidate(key, runs, mounted, lastUsedAt)

    // ---- 能不能就地销毁 ----

    @Test
    fun `三个条件都满足才能销毁`() {
        assertTrue(
            BrowserSessionRetention.canDispose(candidate("c1"), removalRequested = true),
        )
    }

    @Test
    fun `有运行在跑就不动`() {
        // 抽走的话，工具执行到一半的页面会消失。
        assertFalse(
            BrowserSessionRetention.canDispose(
                candidate("c1", runs = 1),
                removalRequested = true,
            ),
        )
    }

    @Test
    fun `界面挂着就不动`() {
        // 抽走的话，用户眼前的浏览器面板会变空白。
        assertFalse(
            BrowserSessionRetention.canDispose(
                candidate("c1", mounted = 1),
                removalRequested = true,
            ),
        )
    }

    @Test
    fun `没要求移除就不能销毁`() {
        // 同会话下一轮要能看到同一组页面，不能因为「空闲」就回收。
        assertFalse(
            BrowserSessionRetention.canDispose(candidate("c1"), removalRequested = false),
        )
    }

    // ---- 超限淘汰 ----

    @Test
    fun `未超限不淘汰`() {
        val all = (1..8).map { candidate("c$it", lastUsedAt = it.toLong()) }
        assertEquals(emptyList<String>(), BrowserSessionRetention.selectEvictions(all, maxIdle = 8))
    }

    @Test
    fun `超限时先淘汰最久未用`() {
        val all = (1..11).map { candidate("c$it", lastUsedAt = it.toLong()) }
        val victims = BrowserSessionRetention.selectEvictions(all, maxIdle = 8)
        // 11 个 - 8 = 3 个要淘汰，且必须是最旧的 c1/c2/c3。
        assertEquals(listOf("c1", "c2", "c3"), victims)
    }

    @Test
    fun `正在跑和正在看的绝不淘汰`() {
        // c1 最旧，但有运行在跑；c2 次旧，但面板挂着。都不能动。
        val all = listOf(
            candidate("c1", runs = 1, lastUsedAt = 1),
            candidate("c2", mounted = 1, lastUsedAt = 2),
            candidate("c3", lastUsedAt = 3),
            candidate("c4", lastUsedAt = 4),
            candidate("c5", lastUsedAt = 5),
        )
        val victims = BrowserSessionRetention.selectEvictions(all, maxIdle = 2)
        // 需要淘汰 3 个，但只有 c3/c4/c5 可动，正好三个。
        assertEquals(listOf("c3", "c4", "c5"), victims)
    }

    @Test
    fun `可淘汰的不够时宁可暂时超限`() {
        // 3 个会话都挂着，上限 1：一个都不能动。宁可内存暂时超，也不能让页面空白。
        val all = listOf(
            candidate("c1", mounted = 1, lastUsedAt = 1),
            candidate("c2", mounted = 1, lastUsedAt = 2),
            candidate("c3", mounted = 1, lastUsedAt = 3),
        )
        assertEquals(emptyList<String>(), BrowserSessionRetention.selectEvictions(all, maxIdle = 1))
    }

    @Test
    fun `同一时刻的会话按 key 稳定排序`() {
        // lastUsedAt 相同（同一毫秒被取得）时必须有确定顺序，
        // 否则淘汰结果在不同运行之间摇摆，问题难复现。
        val all = listOf(
            candidate("b", lastUsedAt = 100),
            candidate("a", lastUsedAt = 100),
            candidate("c", lastUsedAt = 100),
        )
        assertEquals(listOf("a"), BrowserSessionRetention.selectEvictions(all, maxIdle = 2))
    }

    // ---- 一次性会话 ----

    @Test
    fun `run 前缀的键是一次性会话`() {
        assertTrue(BrowserSessionRetention.isRunScoped("run:abc123"))
        // 会话 id 与手动会话都不是一次性的，不能跑完就删。
        assertFalse(BrowserSessionRetention.isRunScoped("conv-1234"))
        assertFalse(BrowserSessionRetention.isRunScoped(BrowserSessionRegistry.MANUAL_SESSION_KEY))
    }
}
