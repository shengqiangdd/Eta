package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentRunReplayProjectionTest {
    /**
     * 真机事故：run 被杀后重进，整段旧轨迹被重新追加到末尾，
     * 于是它连同「任务已中断」排在用户后来发的消息下面，
     * 看起来像「刚发的这条又被打断了一次」。回放必须回到它自己的位置。
     */
    @Test
    fun replayKeepsTraceAfterItsPromptInsteadOfAfterNewerMessages() {
        val messages = listOf(
            UserMessageUi(id = "user-run-a", content = "旧问题"),
            AgentMessageUi(id = "assistant-run-a", content = "旧回答"),
            UserMessageUi(id = "user-run-b", content = "新问题"),
            ThinkingMessageUi(id = "run-b-thinking-1-0", content = "新思考", isStreaming = true),
        )

        val replayed = AgentRunReplayProjection.project(
            runId = "run-a",
            messages = messages,
            events = textAnswer(round = 1, text = "回放后的回答"),
        )

        assertEquals(
            listOf("user-run-a", "assistant-run-a-1-0", "user-run-b", "run-b-thinking-1-0"),
            replayed.map { it.id },
        )
        assertEquals("回放后的回答", (replayed[1] as AgentMessageUi).content)
    }

    /** 用户消息不在（老数据 / 已删除）时，退回「原来轨迹的第一条位置」，仍然不能追加到末尾。 */
    @Test
    fun replayFallsBackToOriginalTracePositionWhenPromptIsMissing() {
        val messages = listOf(
            ThinkingMessageUi(id = "run-a-thinking-1-0", content = "旧思考", isStreaming = false),
            UserMessageUi(id = "user-run-b", content = "新问题"),
        )

        val replayed = AgentRunReplayProjection.project(
            runId = "run-a",
            messages = messages,
            events = textAnswer(round = 1, text = "回放后的回答"),
        )

        assertEquals(listOf("assistant-run-a-1-0", "user-run-b"), replayed.map { it.id })
    }

    /** 补充输入（插话）属于同一条轨迹，回放后仍应排在它自己的回答之前。 */
    @Test
    fun replayRestoresSupplementBeforeItsAnswer() {
        val messages = listOf(UserMessageUi(id = "user-run-a", content = "问题"))

        val replayed = AgentRunReplayProjection.project(
            runId = "run-a",
            messages = messages,
            events = listOf(AgentEvent.UserSupplementReceived(index = 1, text = "补充")) +
                textAnswer(round = 2, text = "回答"),
        )

        assertEquals(
            listOf("user-run-a", "user-run-a-supplement-1", "assistant-run-a-2-0"),
            replayed.map { it.id },
        )
    }

    /**
     * 同一条错位问题的另一半：提示只应停在自己那条轨迹的末尾。
     * 旧实现 `messages + notice` 会把它放到整段对话最后，也就是更新的问题下面。
     */
    @Test
    fun interruptedNoticeStaysWithItsOwnTrace() {
        val messages = listOf(
            UserMessageUi(id = "user-run-a", content = "旧问题"),
            AgentMessageUi(id = "assistant-run-a", content = "半截回答", isStreaming = true),
            UserMessageUi(id = "user-run-b", content = "新问题"),
            AgentMessageUi(id = "assistant-run-b", content = "新回答", isStreaming = true),
        )
        val notice = SystemNoticeMessageUi(id = "interrupted-run-a", code = SystemNoticeCode.Interrupted)

        val placed = AgentRunMessageProjector().insertRunNotice("run-a", notice, messages)

        assertEquals(
            listOf("user-run-a", "assistant-run-a", "interrupted-run-a", "user-run-b", "assistant-run-b"),
            placed.map { it.id },
        )
        assertEquals(placed, AgentRunMessageProjector().insertRunNotice("run-a", notice, placed))
    }

    private fun textAnswer(round: Int, text: String): List<AgentEvent> = listOf(
        AgentEvent.AssistantBlockStart(
            round = round,
            kind = AgentEvent.AssistantBlockKind.TEXT,
            index = 0,
        ),
        AgentEvent.AssistantBlockDelta(
            round = round,
            kind = AgentEvent.AssistantBlockKind.TEXT,
            index = 0,
            deltaChars = text.length,
            delta = text,
        ),
        AgentEvent.AssistantBlockEnd(
            round = round,
            kind = AgentEvent.AssistantBlockKind.TEXT,
            index = 0,
            contentChars = text.length,
        ),
    )
}