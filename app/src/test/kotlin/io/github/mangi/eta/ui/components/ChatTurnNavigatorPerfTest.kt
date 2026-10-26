package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Test

/**
 * 回合导航的性能预算（§158）。
 *
 * 为什么单独测：真机上有 8,435 条消息 / 58 次提问的会话，而点一次「上一个/下一个」
 * 会走「锚点过滤 → moveTurnIndex → 滚动请求」这条路。锚点过滤是全量扫描，
 * 连续跳 58 次就是 58 次全量扫描 —— 没人测过它到底花多少。
 * 判据：在最大会话规模下单次操作必须远低于一个动画帧（16ms）的若干倍，
 * 否则用户连点时会觉得「点了没反应」。
 */
class ChatTurnNavigatorPerfTest {
    private fun user(id: String) = UserMessageUi(id = id, content = "问 $id")

    private fun agent(id: String) = AgentMessageUi(id = id, content = "答 $id")

    private fun thinking(id: String) = ThinkingMessageUi(id = id, content = "想", isStreaming = false)

    /** 复刻真机最大会话的规模：58 次提问、每次提问后跟约 145 条过程消息。 */
    private fun hugeConversation(turns: Int, perTurn: Int): List<AgentChatMessageUi> =
        (0 until turns).flatMap { turn ->
            listOf(user("u$turn")) +
                (0 until perTurn).flatMap { step ->
                    listOf(thinking("t${turn}_$step"), agent("a${turn}_$step"))
                }
        }

    @Test
    fun anchorScanStaysCheapOnTheLargestConversation() {
        val messages = hugeConversation(turns = 58, perTurn = 72) // 8,353 条，接近真机 8,435
        // 预热，避免第一次的类加载与 JIT 进入统计。
        repeat(3) { chatTurnAnchorIds(messages) }

        val runs = 200
        val start = System.nanoTime()
        repeat(runs) { chatTurnAnchorIds(messages) }
        val avgMicros = (System.nanoTime() - start) / runs / 1000

        println("锚点扫描 8,353 条 / 58 回合：平均 $avgMicros µs/次")
        // 单次预算 1ms：即便连点 50 次也只有 50ms，落在「感觉即时」范围内。
        assert(avgMicros < 1000) { "锚点扫描过慢：$avgMicros µs/次" }
    }

    @Test
    fun fullTurnSequenceCostsLessThanOneSecond() {
        val messages = hugeConversation(turns = 58, perTurn = 72)
        val anchors = chatTurnAnchorIds(messages)

        // 模拟用户从第 1 回合一路点「下一个」到最后：每次点击都重算锚点、重算归属、再取目标。
        // 这正是界面上的路径（turnCount / entryTurns 都是 remember(messages) 算出来的，
        // 而消息列表在跳转过程中不变，所以每次点击的输入完全一致）。
        val start = System.nanoTime()
        var current = currentTurnIndex(turnIndexByEntryStarts(messages.map { it is UserMessageUi }), 0)
        repeat(anchors.size - 1) {
            val target = moveTurnIndex(current, anchors.size, 1) ?: return@repeat
            chatTurnAnchorIds(messages).getOrNull(target)
            current = target
        }
        val totalMs = (System.nanoTime() - start) / 1_000_000

        println("完整走一遍 58 回合（含锚点扫描）：$totalMs ms")
        assert(totalMs < 1000) { "连续跳转累计过慢：$totalMs ms" }
    }
}
