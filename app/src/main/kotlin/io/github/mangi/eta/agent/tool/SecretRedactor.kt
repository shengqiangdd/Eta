package io.github.mangi.eta.agent.tool

/**
 * 工具结果里的凭据打码。
 *
 * 终端/文件工具能读到配置文件、数据库和日志，里面经常直接躺着 API key。
 * 一旦原样进入模型上下文或会话记录，就等于把密钥发给了模型和所有能看到这段对话的人。
 * 这里只做保守替换：命中常见的 key/value、Bearer 和 `sk-` 形态才打码，普通正文不动。
 */
internal object SecretRedactor {
    private const val MASK = "<已隐藏>"

    /** `"api_key": "..."`、`api_key=...`、`password: ...` 等赋值形态。 */
    private val ASSIGNMENT = Regex(
        """(?i)\b(api[_-]?key|access[_-]?token|auth[_-]?token|secret|password|passwd|pwd)\b(\s*[:=]\s*)(['"]?)([^\s'",;}]{8,})""",
    )

    /** `Authorization: Bearer eyJ...`。 */
    private val BEARER = Regex("""(?i)\b(Bearer)\s+[A-Za-z0-9._~+/=-]{12,}""")

    /** OpenAI 风格的 `sk-...`；也覆盖 URL 后面直接拼接 key 的畸形输出（那里前面是 `1`，没有词边界）。 */
    private val SK_KEY = Regex("""sk-[A-Za-z0-9_-]{16,}""")

    /** JSON 里单独成字段的凭据，先匹配以保留字段名。 */
    private val JSON_FIELD = Regex(
        """(?i)"(api[_-]?key|access[_-]?token|auth[_-]?token|secret|password|passwd)"\s*:\s*"[^"]*"""",
    )

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var result = JSON_FIELD.replace(text) { match ->
            "\"${match.groupValues[1]}\": \"$MASK\""
        }
        result = ASSIGNMENT.replace(result) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}${match.groupValues[3]}$MASK"
        }
        result = BEARER.replace(result) { match ->
            "${match.groupValues[1]} $MASK"
        }
        return SK_KEY.replace(result, MASK)
    }
}
