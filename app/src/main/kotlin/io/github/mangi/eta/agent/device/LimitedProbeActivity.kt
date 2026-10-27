package io.github.mangi.eta.agent.device

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.github.mangi.eta.R

/**
 * 真机探针专用：一个**会截断并改写**写入内容的输入框。
 *
 * 为什么需要它（fix184）：`TextInputVerification.judge` 在 REPLACE 模式下原来只判
 * 「读回来的文本和写入前不一样」就算 verified —— 输入框把内容截断（长度上限）或
 * 自动改写（大写、加分隔符）时，文本确实变了，但和要写的内容对不上。
 * 真实应用里这种输入框很常见（验证码 6 位、昵称 10 字、自动大写的英文标题），
 * 而这条判据长期只有单测，没有能把「截断」造出来的真机样本。
 *
 * 这里用两个输入过滤器一起保证「读回来的内容一定和写入参数不同」：
 * - [InputFilter.LengthFilter] 上限 [MAX_CHARS] 个字符；
 * - 小写字母转大写。
 * 于是写入 30 个 `x` 后，框里最多是 10 个 `X`：既不是原文，也不是写入值。
 *
 * 只由 [VirtualDisplayDebugHost] 的 STEP9d 样本启动；`exported=false`、没有启动入口、
 * 不在任何用户路径上。写入的文本只存在于这个窗口，关闭即消失，不落盘。
 */
class LimitedProbeActivity : Activity() {

    companion object {
        /** 预置内容。与 PasteOnlyProbeActivity 同理：空框在无障碍层可能没有 text，先给一段种子。 */
        private const val SEED = "seed"

        /** 长度上限。取值小到一定能截断探针写入的 30 个字符。 */
        private const val MAX_CHARS = 10
    }

    private lateinit var field: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 探针只需要焦点与写入结果，不需要键盘。
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
                text = context.getString(R.string.probe_limited_title)
                setTextColor(Color.BLACK)
                textSize = 16f
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        field = EditText(this).apply {
            // 种子文本会被 UpperCaseFilter 转成 SEED —— 判据只关心「和原文不同」，不影响样本。
            setText(SEED)
            setSelection(SEED.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(InputFilter.LengthFilter(MAX_CHARS), UpperCaseFilter())
            setTextColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            isSingleLine = false
            minLines = 3
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
            field.setSelection(field.text.length)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        // 窗口拿到焦点后光标才真正建立；探针随后会读「聚焦的可编辑节点」。
        field.requestFocusFromTouch()
        field.setSelection(field.text.length)
    }

    /** 小写转大写：让读回来的内容与写入参数确定不同，模拟「自动大写」的输入框。 */
    private class UpperCaseFilter : InputFilter {
        override fun filter(
            source: CharSequence,
            start: Int,
            end: Int,
            dest: Spanned,
            dstart: Int,
            dend: Int,
        ): CharSequence? {
            val chunk = source.subSequence(start, end).toString()
            val upper = chunk.uppercase()
            return if (upper == chunk) null else upper
        }
    }
}
