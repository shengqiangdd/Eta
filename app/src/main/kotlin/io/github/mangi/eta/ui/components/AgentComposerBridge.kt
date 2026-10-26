package io.github.mangi.eta.ui.components

/**
 * 聊天输入框的进程内注入通道。
 *
 * 虚拟屏上的中文输入既不能用 `input text`（KeyCharacterMap 返回 null，会 NPE），
 * 也不该走系统剪贴板（会覆盖/清空用户剪贴板，还会闪「已复制」提示）。
 * 这里让输入框自己注册一个写入回调，工具层通过 am start extra 触发，
 * 直接改 Compose 的 TextFieldState —— 不经过 IME、不碰剪贴板。
 */
internal object AgentComposerBridge {
    @Volatile
    private var sink: ((String) -> Unit)? = null

    fun attach(handler: (String) -> Unit) {
        sink = handler
    }

    fun detach() {
        sink = null
    }

    /** 返回 true 表示输入框在当前界面里，文本已经写入。 */
    fun appendText(text: String): Boolean {
        val handler = sink ?: return false
        handler(text)
        return true
    }
}
