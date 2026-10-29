package io.github.mangi.eta.ui.app

/**
 * 判定本地的「运行中」状态里，哪些已经被服务端明确否认。
 *
 * 抽成纯函数是因为这类判定一旦出错，两种后果都很难在真机上复现：
 * 漏清 -> 界面卡在「运行中」，停止 / 插话 / 发送全部静默失效（真机上出现过）；
 * 错清 -> 一个真的在跑的 run 被标成结束，用户会以为任务丢了。
 *
 * 三道保护必须同时成立才算残留：
 * 1. 服务端快照里没有它；
 * 2. 也没有待恢复的 checkpoint；
 * 3. 不是客户端当前正在跟随的 run。
 */
internal object AgentStaleRunReconciler {

    data class Stale(
        val runIds: Set<String>,
        val conversationIds: Set<String>,
    ) {
        val isEmpty: Boolean get() = runIds.isEmpty() && conversationIds.isEmpty()
    }

    fun find(
        localRunToConversation: Map<String, String>,
        streamingConversationIds: Set<String>,
        liveRunIds: Set<String>,
        liveConversationIds: Set<String>,
        pendingRunIds: Set<String>,
        currentRunId: String?,
    ): Stale {
        val staleRunIds = localRunToConversation.keys
            .filterNot { it in liveRunIds || it in pendingRunIds || it == currentRunId }
            .toSet()
        // 只有「清理后仍然指向某会话的 run」才能保护该会话：被判定为残留的 run
        // 马上就要被摘掉，它指向的会话应该跟着一起归零，否则会留下没有 run 的
        // 「运行中」——正是死按钮的形态。
        val stillTracked = localRunToConversation.keys - staleRunIds
        val trackedConversationIds = stillTracked.mapNotNullTo(mutableSetOf()) { localRunToConversation[it] }
        val staleConversationIds = streamingConversationIds
            .filterNot { it in liveConversationIds || it in trackedConversationIds }
            .toSet()
        return Stale(staleRunIds, staleConversationIds)
    }
}