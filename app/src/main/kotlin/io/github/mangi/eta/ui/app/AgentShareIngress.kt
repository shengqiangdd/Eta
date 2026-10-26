package io.github.mangi.eta.ui.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class SharedContentRequest(
    val token: Long,
    val plan: SharedContentPlan,
)

/**
 * 分享内容进入 Compose 的进程内通道。
 *
 * 为什么不是「Activity 直接调用 AgentAppState」：Activity 收到 intent 时 Compose 可能
 * 还没组合（冷启动），也可能已经在跑（onNewIntent）。用 Compose 状态表达
 * 「有一条待处理分享」，两种时序都能在界面就绪后恰好处理一次。
 *
 * 为什么带 token 而不是简单布尔：连续分享两次相同内容时，若只比对内容，
 * 第二次会被当成「已经处理过」；用自增 token 才能保证每次都触发。
 */
internal object AgentShareIngress {
    private var nextToken = 0L

    var pending by mutableStateOf<SharedContentRequest?>(null)
        private set

    fun offer(plan: SharedContentPlan) {
        if (plan.isEmpty) return
        pending = SharedContentRequest(token = ++nextToken, plan = plan)
    }

    fun consume(token: Long) {
        if (pending?.token == token) pending = null
    }
}
