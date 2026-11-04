package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemorySnapshot

/**
 * 记忆上下文按会话固定，保证系统提示在多轮对话间逐字节不变。
 *
 * 记忆位于系统提示中，而系统提示是请求前缀的开头：每次写入都重建它，会让该会话此前的
 * 全部历史失去缓存。本会话自己写入的内容已经以工具调用的形式留在历史里，模型无需从系统
 * 提示再看一遍，因此只要记忆没有被其他来源（别的会话、用户在设置页编辑）改动，就继续沿用
 * 首次注入的快照；被外部改动或上下文被压缩（历史里的写入可能已被摘要掉）时才重新注入。
 *
 * 只在进程内保存：进程重启后首轮重新注入一次，代价是一次缓存未命中，不影响正确性；
 * 写入仍以 revision 做乐观并发校验，沿用旧快照不会覆盖他处的修改。
 */
internal object ConversationMemoryPins {
    private class Pin(
        val context: AgentMemoryContext,
        val contextWindow: Int?,
        var knownRevision: String,
        /**
         * 首次注入时定下的自评块。
         *
         * **必须单独存**，不能跟着 [context] 一起按 revision 重建：
         * 记忆被改（revision 变）→ context 重建 → 若自评也跟着重算，
         * 系统提示就会因为「我自己的 trace 又多了一条」而变，前缀缓存照样失效。
         * 自评反映的是「这段时期我的表现」，本来就不需要每轮精确 —— 会话内冻结反而更可信。
         */
        var selfBrief: String = "",
    )

    private const val MAX_PINS = 32

    private val pins = object : LinkedHashMap<String, Pin>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pin>?): Boolean = size > MAX_PINS
    }

    @Synchronized
    fun contextFor(
        conversationId: String?,
        snapshot: AgentMemorySnapshot,
        contextWindow: Int?,
        /** 自评块的数据源；null 表示本次没取（读不到或未启用），沿用会话内已定的那份。 */
        selfBriefSource: (() -> String)? = null,
    ): AgentMemoryContext {
        if (conversationId != null) {
            val pin = pins[conversationId]
            if (pin != null && pin.knownRevision == snapshot.revision && pin.contextWindow == contextWindow) {
                return pin.context
            }
        }
        var context = AgentMemoryContextBuilder.build(snapshot, contextWindow)
        val existing = conversationId?.let { pins[it] }
        // 优先沿用会话内已经定下的自评块；只有首轮（没有 pin）才真正去取。
        val brief = existing?.selfBrief?.takeIf { it.isNotEmpty() }
            ?: selfBriefSource?.invoke().orEmpty()
        if (brief.isNotEmpty()) context = context.copy(selfBrief = brief)
        if (conversationId != null) {
            pins[conversationId] = Pin(context, contextWindow, snapshot.revision, brief)
        }
        return context
    }

    /** 本会话的 memory_write 成功后调用：这次变化已在历史中，不算外部改动。 */
    @Synchronized
    fun recordOwnWrite(conversationId: String?, revision: String) {
        if (conversationId == null || revision.isBlank()) return
        pins[conversationId]?.knownRevision = revision
    }

    @Synchronized
    fun forget(conversationId: String?) {
        if (conversationId != null) pins.remove(conversationId)
    }
}
