package io.github.mangi.eta.ui.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.agent.runtime.AgentExecutionService

/**
 * 保屏的即时状态，只给界面读。
 *
 * 开关打开后有两种看起来一样的状态：「正在保屏」和「开着但当前没有任务」。
 * 用户没法从开关本身判断功能有没有生效，设置页据此显示一句实况。
 *
 * `holding` 直接转发 `AgentExecutionService.keepScreenOnState`，
 * 不再根据本地 `isStreaming` 镜像重算一次：屏幕锁归服务持有，
 * 界面显示的必须是**那把锁的真实状态**，否则 Activity 重建后镜像失真，
 * 设置页会告诉用户「正在保屏」而屏幕其实会熄（或者反过来）。
 */
internal object AgentKeepScreenStatus {
    var enabled by mutableStateOf(false)
        private set
    var holding by mutableStateOf(false)
        private set

    fun publish(enabled: Boolean) {
        this.enabled = enabled
        this.holding = AgentExecutionService.keepScreenOnState
    }
}
