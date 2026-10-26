package io.github.mangi.eta.agent.device

/**
 * 拼 Root Shell 文本命令时的转义规则（纯逻辑，可单测）。
 *
 * 为什么单独拎出来：这些字符串最终会进 `su -c '<script>'`，
 * 模型给的文本原样进入就是命令注入；而 `input text` 自己还有一套 `%s` 约定。
 * 两条规则混在一起最容易出错，所以拆成纯函数逐个测。
 */
internal object RootShellText {

    /**
     * POSIX 单引号转义：把 `'` 变成 `'\''`，其余字符在单引号内都是字面量。
     *
     * 只包单引号不包双引号：双引号内 `$`、`` ` ``、`\` 仍会被 shell 解释，
     * 而模型传来的文本里这些字符完全可能出现。
     */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * `input text` 的参数编码：空格要写成 `%s`。
     *
     * 这是 `input` 命令自己的约定（它按空白切参数），不是 shell 规则。
     * 注意：字面量 `%s` 无法通过这条通路输入 —— 已知限制，不假装支持。
     */
    fun encodeInputText(value: String): String = value.replace(" ", "%s")

    /** `input text` 只能打 ASCII 可打印字符；其余要走剪贴板粘贴。 */
    fun isAsciiPrintable(value: String): Boolean =
        value.isNotEmpty() && value.all { it.code in 32..126 }
}
