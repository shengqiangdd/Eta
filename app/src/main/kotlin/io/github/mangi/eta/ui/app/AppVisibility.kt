package io.github.mangi.eta.ui.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程级「App 是否在前台」信号。
 *
 * 为什么需要它：会话消息一旦载入就常驻堆（AgentChatUiState.messages），选中会话可能有几千条。
 * App 退到后台后这些内存没有任何用处，却是系统回收进程时最显眼的账面压力 ——
 * 实测本机（PKX110 / ColorOS）RSS 400MB+ 时，即便 importance 已是前台服务（125），
 * 仍会被 o-kill(4008) 干掉，exit-info 里留下的是「会话断了」。
 *
 * 所以把「后台常驻大堆」换成「用的时候才占」：退后台释放当前会话消息，回前台按需重载。
 *
 * 用独立 object 而不是 EtaApp 的字段：AgentAppState 是纯 Kotlin 类（有单测覆盖），
 * 不该为了一个可见性信号把 Application 子类拉进依赖。
 */
internal object AppVisibility {
    private val _foreground = MutableStateFlow(true)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    fun setForeground(value: Boolean) {
        _foreground.value = value
    }

    /** 单测用：恢复到默认值，避免用例间互相影响。 */
    fun reset() {
        _foreground.value = true
    }
}
