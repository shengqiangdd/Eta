package io.github.mangi.eta.agent.device

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.github.mangi.eta.R

/**
 * 真机探针专用：一个**拒绝 `ACTION_SET_TEXT`、只接受系统粘贴**的输入框。
 *
 * 为什么需要它（fix181）：`AgentAccessibilityService.pasteText` 的粘贴兜底分支只在目标
 * 拒绝 `ACTION_SET_TEXT` 时才会执行。而 Compose 的 `BasicTextField` 和普通 `EditText`
 * 都接受 `ACTION_SET_TEXT` —— 于是 fix177 改过的那段判定长期只有代码级证据，没有真机样本。
 * 去真实应用里找一个「拒绝 SET_TEXT」的控件是碰运气，不如自己提供一个受控样本。
 *
 * 探针按「当前前台的可编辑节点」定位它，不依赖提示语文案（提示语走资源，跟随系统语言）。
 *
 * 只由 [VirtualDisplayDebugHost] 的 TEXT3g 样本启动；`exported=false`、没有启动入口、
 * 不在任何用户路径上。写入的文本只存在于这个窗口，关闭即消失，不落盘。
 */
class PasteOnlyProbeActivity : Activity() {

    private lateinit var field: PasteOnlyEditText

    companion object {
        /**
         * 预置内容。为什么不能是空框（fix181 第三次真机）：空 `EditText` 在无障碍层不给
         * `text`（null），`pasteText` 第一步就判 `TEXT_CONTENT_UNAVAILABLE` —— 那是正确行为
         * （读不到原有文本就不能重建追加结果），但样本也就走不到粘贴兜底。
         */
        private const val SEED = "seed"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 不要因为探针而弹出输入法：这个样本只需要输入焦点，不需要键盘。
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(24), dp(96), dp(24), dp(24))
            setBackgroundColor(Color.WHITE)
        }
        root.addView(
            TextView(this).apply {
                text = context.getString(R.string.probe_paste_only_title)
                setTextColor(Color.BLACK)
                textSize = 16f
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        field = PasteOnlyEditText(this).apply {
            // 不要设 hint（fix181 第二次真机验证）：无障碍层可能把 hint 内容当成节点正文，
            // 而「非空正文 + `-1` 选区」会被 append 通路判成 TEXT_SELECTION_UNAVAILABLE。
            // 真正为空时也不行（第三次真机）：空 EditText 的无障碍 `text` 是 null，
            // append 通路会判 TEXT_CONTENT_UNAVAILABLE（不允许可靠读取已有文本）。
            // 所以预置一段种子文本，光标停在末尾 —— 这正是「已有内容还要追加」的真实场景。
            setText(SEED)
            setSelection(SEED.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            isSingleLine = false
            minLines = 4
            // 探针要的是**可靠的输入连接与光标位置**，不是可见键盘：
            // 键盘由 window.setSoftInputMode(HIDDEN) 抑制，这里不关 InputConnection。
            // 踩过的坑（fix181 第一次真机）：光标未建立时无障碍读到的 selection 是 -1，
            // append 通路直接报 TEXT_SELECTION_UNAVAILABLE，压根到不了粘贴兜底。
            // 光标由系统在加载时重建为 0，这里再确认一次落在种子末尾。
            setSelection(SEED.length)
        }
        root.addView(
            field,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) },
        )
        setContentView(root)
        field.post {
            field.requestFocusFromTouch()
            field.setSelection(0)
            logSelection("post")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        // 窗口拿到焦点后光标才真正建立；此时再确认一次（探针紧接着就会读 selection）。
        field.setSelection(SEED.length)
        field.requestFocusFromTouch()
        logSelection("windowFocus")
    }

    /** 诊断用：无障碍读到的 selection 就是这里的状态（写入 -1 时 append 通路根本走不到）。 */
    private fun logSelection(tag: String) {
        Log.i(
            "EtaPasteProbe",
            "$tag focused=${field.isFocused} viewFocus=${field.hasFocus()} textLen=${field.text?.length} " +
                "sel=${field.selectionStart},${field.selectionEnd} " +
                "inputType=${field.inputType}",
        )
    }

    /** 只把 `ACTION_SET_TEXT` 挡掉；`ACTION_PASTE` 等动作照常交给基类。 */
    class PasteOnlyEditText(context: Context) : EditText(context) {
        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) return false
            return super.performAccessibilityAction(action, arguments)
        }
    }
}
