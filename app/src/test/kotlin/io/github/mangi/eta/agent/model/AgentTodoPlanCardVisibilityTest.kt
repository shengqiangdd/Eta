package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底部任务清单卡的显示规则。
 *
 * 用户的要求很直接：这张卡不要常驻，已完成的项不要占地方。规则本身简单，
 * 但它是「一眼扫过去」的界面，退化成常驻或列表越拖越长都不会报错，
 * 只会在每天的使用里慢慢变成噪音 —— 所以从 UI 里抽出来用测试钉住。
 */
class AgentTodoPlanCardVisibilityTest {
    private fun plan(vararg items: Pair<String, AgentTodoStatus>) =
        AgentTodoPlan(items.map { (content, status) -> AgentTodoItem(content, status) })

    @Test
    fun emptyPlanDoesNotShowCard() {
        assertFalse(AgentTodoPlan(emptyList()).shouldShowCard)
    }

    @Test
    fun fullyCompletedPlanStopsShowingTheCard() {
        // 关键一条：活干完了卡就该消失。留着会让用户以为还有事没做完。
        val done = plan(
            "查日志" to AgentTodoStatus.COMPLETED,
            "写修复" to AgentTodoStatus.COMPLETED,
        )
        assertTrue(done.allCompleted)
        assertFalse(done.shouldShowCard)
    }

    @Test
    fun planWithWorkLeftShowsCard() {
        val running = plan(
            "查日志" to AgentTodoStatus.COMPLETED,
            "写修复" to AgentTodoStatus.IN_PROGRESS,
            "跑门禁" to AgentTodoStatus.PENDING,
        )
        assertTrue(running.shouldShowCard)
    }

    @Test
    fun pendingOnlyPlanAlsoShowsCard() {
        // 只有待办、还没开工时同样要显示，否则用户看不到计划。
        assertTrue(plan("跑门禁" to AgentTodoStatus.PENDING).shouldShowCard)
    }

    @Test
    fun openItemsDropCompletedButKeepOrder() {
        val mixed = plan(
            "查日志" to AgentTodoStatus.COMPLETED,
            "写修复" to AgentTodoStatus.IN_PROGRESS,
            "补测试" to AgentTodoStatus.COMPLETED,
            "跑门禁" to AgentTodoStatus.PENDING,
        )
        assertEquals(listOf("写修复", "跑门禁"), mixed.openItems.map { it.content })
    }

    @Test
    fun openItemsIsEmptyWhenEverythingIsDone() {
        val done = plan(
            "查日志" to AgentTodoStatus.COMPLETED,
            "写修复" to AgentTodoStatus.COMPLETED,
        )
        assertTrue(done.openItems.isEmpty())
    }

    @Test
    fun progressCountsStillCoverCompletedItems() {
        // 标题里的 1/3 必须算上已完成项，否则「进度」会随着勾掉而倒退。
        val mixed = plan(
            "查日志" to AgentTodoStatus.COMPLETED,
            "写修复" to AgentTodoStatus.IN_PROGRESS,
            "跑门禁" to AgentTodoStatus.PENDING,
        )
        assertEquals(1, mixed.completedCount)
        assertEquals(3, mixed.items.size)
    }
}
