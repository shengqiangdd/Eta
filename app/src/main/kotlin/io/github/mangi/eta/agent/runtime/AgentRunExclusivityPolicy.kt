package io.github.mangi.eta.agent.runtime

/**
 * 同一会话只允许一个活跃 run。
 *
 * 多会话并发是上游的设计（每个 runId 一个 session，浮层只跟随 primary），但**同一会话**
 * 同时跑两个 run 只会互相破坏：两边都往同一份 transcript 里写消息、都抢浮层、都改运行态。
 * 用户看到的现象是「会话一直显示运行中，得自己点一次停止才解锁」。
 *
 * 触发路径：客户端判断「是否运行中」用的是本地状态；一旦它与服务端脱节（进程被回收、
 * 恢复没接上、停止没收到终态），下一条消息就会以「新消息」而不是「插话」发出去，
 * 于是同一会话里又起了一个 run。
 *
 * 这里只做纯判断，方便单测；真正的取消由 `AgentRuntimeService` 执行。
 */
internal object AgentRunExclusivityPolicy {

    /**
     * 返回应该被新 run 取代的旧 run。
     *
     * @param incomingRunId 即将启动的 run
     * @param incomingConversationId 它的会话；为空（不是 App 内发起）时不参与互斥
     * @param active 当前活跃 run → 所属会话（未知会话为 null）
     * @param terminal 已进入终态、只等收尾的 run，不再重复取消
     */
    fun supersededRuns(
        incomingRunId: String,
        incomingConversationId: String?,
        active: Map<String, String?>,
        terminal: Set<String> = emptySet(),
    ): List<String> {
        if (incomingConversationId.isNullOrBlank()) return emptyList()
        return active.entries
            .filter { (runId, conversationId) ->
                runId != incomingRunId &&
                    conversationId == incomingConversationId &&
                    runId !in terminal
            }
            .map { it.key }
            .sorted()
    }
}
