package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 同进程请求把历史直接交给 Runtime，不再走「JSON 编码 → 临时文件 → 读回 → JSON 解析」（§32.33）。
 *
 * 为什么这是一笔真账：`AgentRuntimeService` 在 AndroidManifest 里没有 `android:process`，
 * 与界面同进程；Messenger 的目标 binder 是本地对象时 Bundle 不经过 Parcel。
 * 也就是说这段往返**从来没有跨进程收益**，只是把客户端手里已有的 List 变成字符串、
 * 写进文件、再读回来解析成同一批对象。41 万字符时实测：
 * encode 38 ms + write 6 ms（客户端）+ read 13 ms + decode 39 ms（服务端）。
 *
 * 语义不变：交接的是 [AgentConversationCodec.sanitizeTranscript] 之后的消息，
 * 与原来 `decodeTranscript(encodeTranscriptForStorage(...))` 的结果逐字段一致（§32.25）。
 *
 * 三条硬约束，缺一就会变成难查的内存或数据问题：
 * 1. **单次消费**：`take` 取出即删，同一次请求不会被解析两遍；重复读会拿到 null 而不是旧数据。
 * 2. **不无限堆积**：客户端 offer 了但服务端始终没来取（发送失败、服务被杀）时，
 *    条目必须靠 TTL 与容量上限自清，否则每个请求都留一份几十 MB 的历史。
 * 3. **不静默降级**：读侧若看到 `transport=process` 却取不到条目，说明真的跨进程了，
 *    必须立刻失败。静默回退成空历史会让对话"莫名失忆"，比崩溃难查得多。
 */
internal object AgentWireHistoryHandoff {

    /** 从 offer 到 take 正常只有毫秒级；60 秒足够覆盖线程调度，又不会长期占内存。 */
    private const val TTL_NANOS = 60_000_000_000L

    /** 多会话并发时同时挂起的请求很少；超过就丢最旧的，宁可让它走失败路径也不吃内存。 */
    private const val MAX_ENTRIES = 8

    private class Entry(
        val messages: List<AgentModelClient.ConversationMessage>,
        val createdAtNanos: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val sequence = AtomicLong()

    /** 登记一次交接，返回给 Bundle 携带的句柄。 */
    fun offer(messages: List<AgentModelClient.ConversationMessage>): String {
        val now = System.nanoTime()
        purgeExpired(now)
        trimTo(MAX_ENTRIES - 1)
        val id = "h" + sequence.incrementAndGet()
        // 浅拷贝列表结构：消息本身是不可变 data class，但调用方可能继续持有并改动原 List。
        entries[id] = Entry(ArrayList(messages), now)
        return id
    }

    /** 取出并删除；句柄为空、不存在或已过期都返回 null。 */
    fun take(id: String?): List<AgentModelClient.ConversationMessage>? {
        if (id.isNullOrBlank()) return null
        val entry = entries.remove(id) ?: return null
        if (System.nanoTime() - entry.createdAtNanos > TTL_NANOS) return null
        return entry.messages
    }

    /** 请求被拒绝或关闭时丢掉未消费的条目，不等 TTL。 */
    fun discard(id: String?) {
        if (!id.isNullOrBlank()) entries.remove(id)
    }

    /** 仅用于单测与诊断。 */
    fun pendingCount(): Int = entries.size

    fun clear() {
        entries.clear()
    }

    private fun purgeExpired(now: Long) {
        entries.entries.removeAll { now - it.value.createdAtNanos > TTL_NANOS }
    }

    private fun trimTo(maxEntries: Int) {
        while (entries.size > maxEntries) {
            val oldest = entries.entries.minByOrNull { it.value.createdAtNanos } ?: return
            entries.remove(oldest.key, oldest.value)
        }
    }
}
