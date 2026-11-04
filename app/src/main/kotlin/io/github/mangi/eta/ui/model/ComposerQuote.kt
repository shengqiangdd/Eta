package io.github.mangi.eta.ui.model

/**
 * 输入框里待发送的「引用」。
 *
 * 对齐 dsh 0.2.2：聊天里的文字可以引用进输入框，发送后以引用块出现在消息里。
 * 这里只带渲染需要的最小字段，不持有消息本体 —— 切会话或进程重建后旧引用不该复活，
 * 也不参与持久化（引用是「这次要说什么」的一部分，不是会话数据）。
 */
data class ComposerQuoteUi(
    val messageId: String,
    val author: String,
    val excerpt: String,
) {
    val isBlank: Boolean get() = excerpt.isBlank()
}

/** 引用最多带多少字符：再长会把一条提问顶满整屏，反而看不见自己写的内容。 */
internal const val COMPOSER_QUOTE_MAX_CHARS = 600

/**
 * 把消息正文压成引用摘要：去掉首尾空白，超长截断并补省略号。
 *
 * 截断按字符而不是字节，且不把代理对（emoji）切成两半 —— 切坏了会渲染成替换字符，
 * 而这段文字会被原样发给模型。
 */
fun composerQuoteExcerpt(text: String): String {
    val normalized = text.trim()
    if (normalized.length <= COMPOSER_QUOTE_MAX_CHARS) return normalized
    var cut = normalized.take(COMPOSER_QUOTE_MAX_CHARS)
    if (cut.lastOrNull()?.isHighSurrogate() == true) cut = cut.dropLast(1)
    return cut.trimEnd() + "…"
}

/**
 * 引用前缀：逐行加 `>`，保证多行正文仍是一个完整的 Markdown 引用块。
 *
 * 作者行用加粗而不是把「引用」二字写死在这里：作者名由界面按当前语言传进来，
 * 模型侧只是把这段文字当成用户消息的一部分。
 */
fun ComposerQuoteUi.toPromptPrefix(): String {
    if (excerpt.isBlank()) return ""
    val lines = ArrayList<String>()
    val authorLabel = author.trim()
    if (authorLabel.isNotEmpty()) lines += "> **$authorLabel**"
    excerpt.lineSequence().forEach { line ->
        lines += if (line.isBlank()) ">" else "> $line"
    }
    return lines.joinToString("\n") + "\n\n"
}

/** 把引用拼到用户正文前面；没有引用时原样返回，调用方不需要自己分支。 */
fun composeQuotedMessage(quote: ComposerQuoteUi?, text: String): String {
    if (quote == null || quote.isBlank) return text
    return quote.toPromptPrefix() + text
}
