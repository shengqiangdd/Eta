package io.github.mangi.eta.ui.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 保屏的即时状态，只给界面读。
 *
 * 开关打开后有两种看起来一样的状态：「正在保屏」和「开着但当前没有任务」。
 * 用户没法从开关本身判断功能有没有生效，设置页据此显示一句实况。
 */
internal object AgentKeepScreenStatus {
    var enabled by mutableStateOf(false)
        private set
    var holding by mutableStateOf(false)
        private set
    var runningUnpaused by mutableStateOf(0)
        private set

    fun publish(enabled: Boolean, holding: Boolean, runningUnpaused: Int) {
        this.enabled = enabled
        this.holding = holding
        this.runningUnpaused = runningUnpaused
    }
}
