package io.github.mangi.eta.agent.device

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.github.mangi.eta.R

/**
 * 真机探针专用：一个**密码框**（内容被遮蔽、读不出来）加一段**很长的提示语**。
 *
 * 为什么需要它（fix185）：`TextInputVerification` 的密码分支长期只有单测。
 * 无障碍 `ACTION_SET_TEXT` 写密码框更是**从不校验**（一律 `verified = false`）——
 * 因为「读不到内容」被当成了「什么都验证不了」。而掩码长度通常就是字符数：
 * 长度从 0 变成 8 是能证明写入发生的。
 *
 * 同时覆盖另一个缺口：长提示语（hint）。空输入框会显示提示语，
 * 一旦被当成「已有内容」，追加输入会把提示语拼进结果、清空后读回也会误判。
 * 这里把提示语写到 [HINT_LENGTH_TARGET] 字以上，让这种污染一眼可见。
 *
 * 刻意**不给种子**：空密码框 + 提示语，正是「提示语不能算正文」的真实场景。
 *
 * 只由 [VirtualDisplayDebugHost] 的 STEP8d 启动；`exported=false`、没有启动入口、
 * 不在任何用户路径上。写入的文本只存在于这个窗口，关闭即消失，不落盘。
 */
class PasswordProbeActivity : Activity() {

    companion object {
        /** 提示语的目标长度：超过常见阈值（40 字），提示语被当正文时读数会明显异常。 */
        const val HINT_LENGTH_TARGET = 60
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
                text = context.getString(R.string.probe_password_title)
                setTextColor(Color.BLACK)
                textSize = 16f
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        field = EditText(this).apply {
            hint = context.getString(R.string.probe_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            // 2026-10-27 真机（ColorOS / A16）实测：只设 inputType 时无障碍层仍报
            // `password=false`、内容明文可读 —— 密码分支因此一次都没被测到。
            // 显式装 PasswordTransformationMethod，与 XML 的 textPassword 等价。
            transformationMethod = PasswordTransformationMethod.getInstance()
            setTextColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            isSingleLine = true
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
}
