package io.github.mangi.eta.agent.device

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.github.mangi.eta.R

/**
 * 真机探针专用：一个**不截断、不改写**的输入框。
 *
 * 为什么需要它：`STEP8` 拿第三方应用（便签）当输入目标，而它一旦改版、首页没有搜索入口，
 * 整段就会 `skipped pkg=… 三轮内没有可编辑节点` —— 于是「正常写入应当被判 verified」
 * 这条**正向**断言在真机上就没有覆盖了，只剩 `STEP9d` 那条负向（截断必须报 unknown）。
 * 2026-10-27 实测正是这个状态：两个方向只留了一个。
 *
 * 与 [LimitedProbeActivity] 的区别只有一点：不加任何 `InputFilter`。
 * 写入什么就该读回什么，判据要求 `verification=verified` 且字段内容严格等于写入值。
 *
 * 只由 [VirtualDisplayDebugHost] 的 STEP8b 启动；`exported=false`、没有启动入口、
 * 不在任何用户路径上。写入的文本只存在于这个窗口，关闭即消失，不落盘。
 */
class PlainTextProbeActivity : Activity() {

    companion object {
        /** 预置内容：空框在无障碍层可能没有 text，夹具先给一段种子。 */
        private const val SEED = "seed"
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
                text = context.getString(R.string.probe_plain_title)
                setTextColor(Color.BLACK)
                textSize = 16f
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        field = EditText(this).apply {
            setText(SEED)
            setSelection(SEED.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
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
}
