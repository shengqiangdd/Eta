package io.github.mangi.eta.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.ui.app.AgentDebugInjections

/**
 * **仅 debug 构建存在**的注入入口（release 源集里没有这个类）。
 *
 * 用途：把「客户端本地运行态」清掉，但**不停服务端那条 run** —— 复现 §11 的运行态失配，
 * 从而让下一条普通消息以「新 run」下发，走到 `supersedeSameConversationRuns`。
 *
 * 用法（需要 App 进程活着，状态层已经注册好注入点）：
 * ```
 * am broadcast -n <applicationId>/io.github.mangi.eta.debug.DebugRunStateReceiver \
 *   -a io.github.mangi.eta.DEBUG_FORGET_RUN_STATE --es conversation_id conv-xxxx
 * ```
 * 不传 `conversation_id` 时作用于当前选中的会话。
 */
class DebugRunStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FORGET_RUN_STATE) return
        val inject = AgentDebugInjections.forgetLocalRunState
        if (inject == null) {
            AndroidAgentLogger.warn("debug injection: forgetLocalRunState 未注册（界面还没起来？）")
            return
        }
        inject(intent.getStringExtra(EXTRA_CONVERSATION_ID))
    }

    companion object {
        const val ACTION_FORGET_RUN_STATE = "io.github.mangi.eta.DEBUG_FORGET_RUN_STATE"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
    }
}
