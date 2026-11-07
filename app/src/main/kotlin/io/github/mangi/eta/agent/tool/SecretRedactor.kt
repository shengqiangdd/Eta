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

    /** 打码后的固定标记，供单测断言用（不能反向推断出原文）。 */
    internal const val MASK_FOR_TEST = MASK

    /**
     * `"api_key": "..."`、`api_key=...` 等赋值形态。
     *
     * 两条约束都是真机踩出来的：
     *
     * 1. **`pwd` 不再单独算凭据名**。它把 shell 的 `$PWD` / `export PWD=...` 当成密码，
     *    于是命令文本被改写成 `PWD=<已隐藏>` —— 之后 `git config --add safe.directory "$PWD"`
     *    真的会拿到字面量 `<已隐藏>`，报 `safe.directory 'D' not absolute`。
     *    真实的密码变量一般叫 `password`/`passwd`/`DB_PWD`，`pwd` 单独出现几乎总是 shell 内建。
     * 2. **左边必须是赋值的起点**（行首、分隔符或引号之后），右边必须是引号包住或
     *    不属于 shell 变量语法的值。这样 `$PWD`、`${PWD}`、`FOO=$PWD/x` 一律不动。
     */
    private val ASSIGNMENT = Regex(
        """(?i)(?<![$\w-])(api[_-]?key|access[_-]?token|auth[_-]?token|client[_-]?secret|secret[_-]?key|password|passwd)(\s*[:=]\s*)("([^"]{8,})"|'([^']{8,})'|([^\s"',;}]{8,}))""",
    )

    /**
     * `DB_PASSWD=...`、`MYSQL_PASSWORD=...` 这类带前缀的名字。
     *
     * 上面的 [ASSIGNMENT] 要求名字左边不是词字符，否则 `$PWD` 会被误伤；
     * 代价是 `DB_PASSWD` 也匹配不上。这里单独补一条：**必须要下划线前缀**，
     * 所以 `$PWD` / `PWD` 仍然不会被当成凭据。
     */
    private val PREFIXED_ASSIGNMENT = Regex(
        """(?i)\b([A-Za-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+)*_(?:API[_-]?KEY|TOKEN|SECRET|PASSWD|PASSWORD))(\s*[:=]\s*)("([^"]{8,})"|'([^']{8,})'|([^\s"',;}]{8,}))""",
    )

    /** `Authorization: Bearer eyJ...`。 */
    private val BEARER = Regex("""(?i)\b(Bearer)\s+[A-Za-z0-9._~+/=-]{12,}""")

    /** OpenAI 风格的 `sk-...`；也覆盖 URL 后面直接拼接 key 的畸形输出（那里前面是 `1`，没有词边界）。 */
    private val SK_KEY = Regex("""sk-[A-Za-z0-9_-]{16,}""")

    /** JSON 里单独成字段的凭据，先匹配以保留字段名。 */
    private val JSON_FIELD = Regex(
        """(?i)"(api[_-]?key|access[_-]?token|auth[_-]?token|client[_-]?secret|secret[_-]?key|password|passwd)"\s*:\s*"[^"]*"""",
    )

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var result = JSON_FIELD.replace(text) { match ->
            "\"${match.groupValues[1]}\": \"$MASK\""
        }
        result = ASSIGNMENT.replace(result) { match ->
            val name = match.groupValues[1]
            val separator = match.groupValues[2]
            // 保留原来的引号形态：`"pw"` 不该变成 `"<已隐藏>"` 里再套一层。
            val replacement = when {
                match.groupValues[4].isNotEmpty() -> "\"$MASK\""
                match.groupValues[5].isNotEmpty() -> "'$MASK'"
                else -> MASK
            }
            "$name$separator$replacement"
        }
        result = PREFIXED_ASSIGNMENT.replace(result) { match ->
            val replacement = when {
                match.groupValues[4].isNotEmpty() -> "\"$MASK\""
                match.groupValues[5].isNotEmpty() -> "'$MASK'"
                else -> MASK
            }
            "${match.groupValues[1]}${match.groupValues[2]}$replacement"
        }
        result = BEARER.replace(result) { match ->
            "${match.groupValues[1]} $MASK"
        }
        return SK_KEY.replace(result, MASK)
    }
}
