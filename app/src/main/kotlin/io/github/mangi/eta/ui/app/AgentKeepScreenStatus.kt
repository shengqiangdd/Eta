package io.github.mangi.eta.ui.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
    // 计数用 Int 专用 State：普通 mutableStateOf(0) 会把每次写入装箱成 Integer，
    // 在每帧都会读的界面里是白白的分配（Compose 的 AutoboxingStateCreation 也这么建议）。
    var runningUnpaused by mutableIntStateOf(0)
        private set

    fun publish(enabled: Boolean, holding: Boolean, runningUnpaused: Int) {
        this.enabled = enabled
        this.holding = holding
        this.runningUnpaused = runningUnpaused
    }
}
