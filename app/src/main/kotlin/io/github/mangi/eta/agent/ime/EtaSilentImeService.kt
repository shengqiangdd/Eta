package io.github.mangi.eta.agent.ime

import android.annotation.SuppressLint
import android.inputmethodservice.InputMethodService
import android.view.View

/**
 * 无 UI 输入法：虚拟屏操作期间临时启用，避免系统把输入法弹到主屏。
 *
 * 虚拟屏没有自己的输入法窗口，点击输入框时系统会把 IME 显示请求落到主屏
 * （打游戏时弹键盘非常致命）。这个 IME 不提供任何输入视图：
 * - onEvaluateInputViewShown 返回 false，系统不会显示键盘；
 * - 输入仍由 input text / 进程内注入完成，不依赖 IME。
 */
class EtaSilentImeService : InputMethodService() {
    override fun onCreateInputView(): View = View(this)

    // 这里故意不调用 super：整个类的目的就是让系统永远不要显示输入视图。
    @SuppressLint("MissingSuperCall")
    override fun onEvaluateInputViewShown(): Boolean = false
}
