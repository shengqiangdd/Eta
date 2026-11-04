package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ConversationMemoryPinsTest {
    private fun snapshot(content: String, revision: String) =
        AgentMemorySnapshot(content = content, revision = revision, byteSize = content.length, lineCount = 1)

    @Test
    fun ownWritesKeepTheSessionSnapshotSoTheSystemPromptDoesNotChange() {
        val id = "conv-own-${System.nanoTime()}"
        val first = ConversationMemoryPins.contextFor(id, snapshot("# 核心记忆\n旧", "r1"), 128_000)
        ConversationMemoryPins.recordOwnWrite(id, "r2")
        val next = ConversationMemoryPins.contextFor(id, snapshot("# 核心记忆\n新", "r2"), 128_000)
        assertSame(first, next)
    }

    @Test
    fun externalChangesCompactionOrModelSwitchReinjectTheLatestMemory() {
        val id = "conv-ext-${System.nanoTime()}"
        ConversationMemoryPins.contextFor(id, snapshot("# 核心记忆\n旧", "r1"), 128_000)
        val external = ConversationMemoryPins.contextFor(id, snapshot("# 核心记忆\n他处修改", "r9"), 128_000)
        assertEquals("r9", external.revision)

        ConversationMemoryPins.recordOwnWrite(id, "r10")
        ConversationMemoryPins.forget(id)
        assertEquals("r10", ConversationMemoryPins.contextFor(id, snapshot("# 核心记忆\n写入", "r10"), 128_000).revision)

        val switched = ConversationMemoryPins.contextFor(id, snapshot("# 核心记忆\n写入", "r10"), 32_000)
        assertEquals(AgentMemoryContextBuilder.coreBudgetChars(32_000), switched.coreBudgetChars)
    }

    // ---- 自评块（selfBrief）：会话内必须冻结 ----

    @Test
    fun selfBriefIsFetchedOnlyOncePerSession() {
        val id = "conv-brief-once-${System.nanoTime()}"
        var calls = 0
        val source = {
            calls += 1
            "自评第 $calls 次"
        }
        val first = ConversationMemoryPins.contextFor(id, snapshot("# 核心记忆\n记忆", "r1"), 128_000, source)
        val second = ConversationMemoryPins.contextFor(id, snapshot("# 核心记忆\n记忆", "r1"), 128_000, source)
        assertEquals(first.selfBrief, second.selfBrief)
        assertEquals("命中 pin 时不应再取数", 1, calls)
        ConversationMemoryPins.forget(id)
    }

    @Test
    fun selfBriefSurvivesExternalMemoryChange() {
        // 这是设计的关键：记忆被外部改动会让 context 重建，
        // 但自评反映的是「这段时期我的表现」，跟着重算只会让缓存白白失效。
        val id = "conv-brief-freeze-${System.nanoTime()}"
        val first = ConversationMemoryPins.contextFor(
            id, snapshot("# 核心记忆\n记忆", "r1"), 128_000,
        ) { "冻结的自评" }
        var asked = false
        val rebuilt = ConversationMemoryPins.contextFor(
            id, snapshot("# 核心记忆\n他处修改", "r9"), 128_000,
        ) {
            asked = true
            "被重算的自评"
        }
        assertEquals("冻结的自评", first.selfBrief)
        assertEquals("重建 context 不应重算自评", "冻结的自评", rebuilt.selfBrief)
        assertEquals("r9", rebuilt.revision) // 记忆本身确实刷新了
        assertEquals(false, asked)
        ConversationMemoryPins.forget(id)
    }

    @Test
    fun newSessionGetsItsOwnSelfBrief() {
        val a = "conv-brief-a-${System.nanoTime()}"
        val b = "conv-brief-b-${System.nanoTime()}"
        assertEquals(
            "会话 A",
            ConversationMemoryPins.contextFor(a, snapshot("# 核心记忆\n记忆", "r1"), 128_000) { "会话 A" }.selfBrief,
        )
        assertEquals(
            "会话 B",
            ConversationMemoryPins.contextFor(b, snapshot("# 核心记忆\n记忆", "r1"), 128_000) { "会话 B" }.selfBrief,
        )
        ConversationMemoryPins.forget(a)
        ConversationMemoryPins.forget(b)
    }

    @Test
    fun emptySelfBriefDoesNotGetInjected() {
        val id = "conv-brief-empty-${System.nanoTime()}"
        val context = ConversationMemoryPins.contextFor(
            id, snapshot("# 核心记忆\n记忆", "r1"), 128_000,
        ) { "" }
        assertEquals("", context.selfBrief)
        ConversationMemoryPins.forget(id)
    }
}
