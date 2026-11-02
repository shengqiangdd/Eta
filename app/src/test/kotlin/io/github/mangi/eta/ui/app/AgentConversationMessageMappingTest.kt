package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 消息与库行之间的 token 映射。
 *
 * 为什么不用 [AgentConversationPersistenceTest] 守：那是 Robolectric 测试，而 Robolectric 的
 * SQLite native 只有 x86_64，本机 arm64 跑不起来。这里直接测两个映射函数（纯逻辑，不碰 Room），
 * 「库层面真的落盘/读回」交给能在 x86 上跑的环境。
 *
 * 守的是本轮最容易漏的一条：写了新列却不读、或读了却当成 0。
 */
class AgentConversationMessageMappingTest {

    @Test
    fun providerPromptTotalIsWrittenIntoTheRow() = with(AgentConversationStore) {
        val row = requireNotNull(
            AgentMessageUi(
                id = "answer",
                content = "回答",
                usage = TokenUsageUi(
                    contextTokens = 1_000,
                    inputTokens = 450,
                    outputTokens = 800,
                    cachedTokens = 200,
                    promptTokens = 1_250,
                ),
            ).toEntityOrNull("conversation", 0),
        )

        assertEquals(1_250, row.promptTokens)
        // 厂商原样输入也要保留：面板仍按它显示「输入」，只有总量用 prompt。
        assertEquals(450, row.inputTokens)
    }

    @Test
    fun providerPromptTotalIsReadBackIntoTheMessage() = with(AgentConversationStore) {
        val message = storedRow(promptTokens = 1_250, inputTokens = 450, outputTokens = 800)
            .toMessageOrNull() as AgentMessageUi

        assertEquals(1_250, message.usage?.promptTokens)
        assertEquals(450, message.usage?.inputTokens)
    }

    @Test
    fun legacyRowsWithoutPromptTotalKeepItMissingInsteadOfZero() = with(AgentConversationStore) {
        val message = storedRow(promptTokens = null, inputTokens = 450, outputTokens = 800)
            .toMessageOrNull() as AgentMessageUi

        // 读成 0 会让导出/详情显示「总输入 0」，比不显示更糟。
        assertNull(message.usage?.promptTokens)
        assertEquals(450, message.usage?.inputTokens)
    }

    @Test
    fun messageWithOnlyThePromptTotalStillExposesUsage() = with(AgentConversationStore) {
        val message = storedRow(promptTokens = 1_250, inputTokens = null, outputTokens = null)
            .toMessageOrNull() as AgentMessageUi

        // isEmpty 必须把 prompt 算进去：否则只剩总输入的消息会被整块丢掉。
        assertNotNull(message.usage)
        assertEquals(1_250, message.usage?.promptTokens)
    }

    @Test
    fun messageWithoutAnyUsageMapsToNull() = with(AgentConversationStore) {
        val message = storedRow(promptTokens = null, inputTokens = null, outputTokens = null)
            .toMessageOrNull() as AgentMessageUi

        assertNull(message.usage)
    }

    private fun storedRow(
        promptTokens: Int?,
        inputTokens: Int?,
        outputTokens: Int?,
    ) = io.github.mangi.eta.data.db.ConversationMessageEntity(
        id = "answer",
        conversationId = "conversation",
        sortIndex = 0,
        type = "assistant",
        content = "回答",
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        promptTokens = promptTokens,
    )
}
