package io.github.mangi.eta.ui.app

import io.github.mangi.eta.data.model.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentPendingAskAnswersTest {

    private fun answer(
        runId: String = "run-1",
        prompt: String = "选项甲",
    ) = PendingAskAnswer(
        runId = runId,
        messageId = "user-$runId",
        prompt = prompt,
        reasoningEffort = ReasoningEffort.DEFAULT,
    )

    @Test
    fun reselectingReplacesThePendingAnswer() {
        // 重选：后一次选择生效，并返回被顶掉的那条（调用方用它换掉旧消息，避免回答写两遍）。
        val store = PendingAskAnswers()
        assertNull(store.put("conv", answer(runId = "run-1", prompt = "选项甲")))
        val replaced = store.put("conv", answer(runId = "run-2", prompt = "选项乙"))
        assertEquals("run-1", replaced?.runId)
        assertEquals("run-2", store.get("conv")?.runId)
        assertEquals("选项乙", store.get("conv")?.prompt)
    }

    @Test
    fun differentConversationsAreIndependent() {
        val store = PendingAskAnswers()
        assertNull(store.put("a", answer(runId = "run-a")))
        assertNull(store.put("b", answer(runId = "run-b")))
        assertEquals("run-a", store.get("a")?.runId)
        assertEquals("run-b", store.get("b")?.runId)
    }

    @Test
    fun waitsWhileTheOldRunIsStillAlive() {
        // 旧 run 没停就起新 run 会和取消抢历史写入：必须原样扣着。
        val store = PendingAskAnswers()
        store.put("conv", answer())
        assertNull(store.takeIfIdle("conv", isStreaming = true, isCompacting = false))
        assertEquals("run-1", store.get("conv")?.runId)
    }

    @Test
    fun waitsWhileCompacting() {
        val store = PendingAskAnswers()
        store.put("conv", answer())
        assertNull(store.takeIfIdle("conv", isStreaming = false, isCompacting = true))
        assertEquals("run-1", store.get("conv")?.runId)
    }

    @Test
    fun handsOverExactlyOnceWhenIdle() {
        val store = PendingAskAnswers()
        store.put("conv", answer())
        assertEquals("run-1", store.takeIfIdle("conv", isStreaming = false, isCompacting = false)?.runId)
        // 取走后不能再发一遍。
        assertNull(store.takeIfIdle("conv", isStreaming = false, isCompacting = false))
        assertNull(store.get("conv"))
    }

    @Test
    fun deletingAConversationDropsItsAnswer() {
        val store = PendingAskAnswers()
        store.put("conv", answer())
        store.remove("conv")
        assertNull(store.get("conv"))
        assertNull(store.takeIfIdle("conv", isStreaming = false, isCompacting = false))
    }

    @Test
    fun unknownConversationHasNothingToLaunch() {
        val store = PendingAskAnswers()
        assertNull(store.takeIfIdle("missing", isStreaming = false, isCompacting = false))
    }
}
