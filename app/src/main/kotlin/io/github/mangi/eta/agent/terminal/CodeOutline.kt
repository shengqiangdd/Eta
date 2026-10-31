package io.github.mangi.eta.agent.terminal

/**
 * 轻量代码大纲：回答「这个文件里有哪些符号、在第几行」，而不是把正文整段读进来。
 *
 * 为什么需要：手机端上下文更贵，三千行的文件按 16 KB 一段要读十几次，而改代码前
 * 通常只需要知道类 / 函数在哪一行，再按 `read_file(start_line=…)` 精确跳过去。
 *
 * 只做行级正则，不建 AST：
 * - 宁可少报 —— 漏掉的符号仍能用 `grep_files` 找到；
 * - 不要为了多报，把注释、字符串里的内容当成定义。
 *
 * 解析是纯函数（语言 + 文本 → 符号列表），可以直接单测，不依赖文件后端。
 */
internal object CodeOutline {

    /** 符号种类；与 [Symbol.kind] 的取值一致，也用于 `kind` 过滤。 */
    const val KIND_CLASS = "class"
    const val KIND_INTERFACE = "interface"
    const val KIND_OBJECT = "object"
    const val KIND_ENUM = "enum"
    const val KIND_STRUCT = "struct"
    const val KIND_TRAIT = "trait"
    const val KIND_IMPL = "impl"
    const val KIND_MODULE = "module"
    const val KIND_TYPE = "type"
    const val KIND_CONST = "const"
    const val KIND_FUNCTION = "function"
    const val KIND_EXTENSION = "extension"

    /** 全部可过滤的种类；顺序用于错误提示，避免让模型猜。 */
    val kinds: List<String> = listOf(
        KIND_CLASS, KIND_INTERFACE, KIND_OBJECT, KIND_ENUM, KIND_STRUCT, KIND_TRAIT,
        KIND_IMPL, KIND_MODULE, KIND_TYPE, KIND_CONST, KIND_FUNCTION, KIND_EXTENSION,
    )

    enum class Language(val label: String) {
        KOTLIN("kotlin"),
        JAVA("java"),
        GO("go"),
        RUST("rust"),
        PYTHON("python"),
        JAVASCRIPT("javascript"),
        SWIFT("swift"),
        SHELL("shell"),
        UNKNOWN("unknown"),
    }

    data class Symbol(
        /** 相对路径；单文件解析时为空串，由调用方补上。 */
        val file: String,
        /** 1 起始行号。 */
        val line: Int,
        val kind: String,
        val name: String,
        /** 原始行（去掉行首缩进、截断），让模型不用再读一遍就知道大致长什么样。 */
        val signature: String,
    )

    /** 签名最多保留多少字符：够看清参数，又不至于让大纲变成正文。 */
    private const val MAX_SIGNATURE_CHARS = 200

    private data class Rule(
        val kind: String,
        val regex: Regex,
        val nameGroup: Int = 1,
        /** 命中后从这一组取更精确的 kind（例如 Kotlin 的 `enum class`）。 */
        val kindGroup: Int? = null,
    )

    /**
     * 按扩展名判断语言。大小写不敏感（`.KT` 也认）。
     *
     * 未支持的语言返回 [Language.UNKNOWN]，调用方应明确报「不支持」而不是返回空列表 ——
     * 空列表会被读成「这个文件里没有符号」。
     */
    fun languageOf(fileName: String): Language = when (fileName.substringAfterLast('.', "").lowercase()) {
        "kt", "kts" -> Language.KOTLIN
        "java" -> Language.JAVA
        "go" -> Language.GO
        "rs" -> Language.RUST
        "py", "pyi" -> Language.PYTHON
        "ts", "tsx", "js", "jsx", "mjs", "cjs" -> Language.JAVASCRIPT
        "swift" -> Language.SWIFT
        "sh", "bash" -> Language.SHELL
        else -> Language.UNKNOWN
    }

    fun supports(fileName: String): Boolean = languageOf(fileName) != Language.UNKNOWN

    /**
     * 解析一段文本。
     *
     * @param limit 最多返回多少个符号；到上限就停止，调用方据 `truncated` 说明「还有更多」。
     */
    fun parse(fileName: String, text: String, limit: Int = 200): List<Symbol> {
        if (limit <= 0 || text.isEmpty()) return emptyList()
        val lines = text.split('\n')
        return parseLines(fileName, lines, limit)
    }

    fun parseLines(fileName: String, lines: List<String>, limit: Int = 200): List<Symbol> {
        if (limit <= 0) return emptyList()
        val rules = rulesOf(languageOf(fileName)) ?: return emptyList()
        val symbols = ArrayList<Symbol>(minOf(limit, 64))
        for (index in lines.indices) {
            if (symbols.size >= limit) break
            val raw = lines[index]
            if (raw.isEmpty()) continue
            val line = raw.trimEnd()
            val trimmed = line.trimStart()
            if (trimmed.isEmpty() || isCommentLine(trimmed)) continue
            for (rule in rules) {
                val match = rule.regex.find(trimmed) ?: continue
                val name = match.groupValues.getOrNull(rule.nameGroup).orEmpty()
                if (name.isEmpty()) continue
                val kind = normalizeKind(
                    raw = rule.kindGroup
                        ?.let { group -> match.groupValues.getOrNull(group).orEmpty() }
                        ?.takeIf { it.isNotEmpty() }
                        ?: rule.kind,
                    line = trimmed,
                )
                symbols.add(
                    Symbol(
                        file = "",
                        line = index + 1,
                        kind = kind,
                        name = name,
                        signature = trimmed.take(MAX_SIGNATURE_CHARS),
                    ),
                )
                break
            }
        }
        return symbols
    }

    /**
     * 把语言关键字收敛成统一种类。
     *
     * `enum class` 在 Kotlin 里是「枚举」，不是「类」；Java 的 `@interface` 是注解类型。
     * 这两处若原样透传，结果里会出现 class / @interface，用户按 kind 过滤就找不到它们。
     */
    private fun normalizeKind(raw: String, line: String): String = when (raw) {
        "@interface" -> KIND_INTERFACE
        "mod" -> KIND_MODULE
        KIND_CLASS -> if (ENUM_CLASS.containsMatchIn(line)) KIND_ENUM else KIND_CLASS
        else -> raw
    }

    private val ENUM_CLASS = Regex("\\benum\\s+class\\b")

    /**
     * 注释行直接跳过。
     *
     * 这不是完整的词法分析：只挡住最常见的三种写法（行注释、块注释续行、Python / Shell 注释）。
     * 行尾注释仍会保留在签名里 —— 它本来就属于这一行的内容。
     */
    private fun isCommentLine(trimmed: String): Boolean =
        trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*") ||
            trimmed.startsWith("#")

    private fun rulesOf(language: Language): List<Rule>? = when (language) {
        Language.KOTLIN -> kotlinRules
        Language.JAVA -> javaRules
        Language.GO -> goRules
        Language.RUST -> rustRules
        Language.PYTHON -> pythonRules
        Language.JAVASCRIPT -> javascriptRules
        Language.SWIFT -> swiftRules
        Language.SHELL -> shellRules
        Language.UNKNOWN -> null
    }

    // 修饰符前缀（Kotlin）：注解、可见性、open/abstract 等都可能出现，且顺序不固定。
    private const val KOTLIN_MODIFIERS =
        "(?:@\\w+(?:\\([^)]*\\))?\\s*)*(?:(?:public|private|protected|internal|open|abstract|sealed|data|value|enum|annotation|inner|inline|override|suspend|operator|infix|tailrec|external|actual|expect|const|lateinit|companion|final|static)\\s+)*"

    private val kotlinRules = listOf(
        // enum class Foo：kind 取枚举本身，而不是笼统的 class。
        Rule(
            kind = KIND_CLASS,
            regex = Regex("^$KOTLIN_MODIFIERS(class|interface|object)\\s+([A-Za-z_]\\w*)"),
            nameGroup = 2,
            kindGroup = 1,
        ),
        // fun <T> List<T>.foo(...)：泛型与扩展接收者都要能穿过。
        Rule(
            kind = KIND_FUNCTION,
            regex = Regex(
                "^(?:@\\w+(?:\\([^)]*\\))?\\s*)*(?:(?:public|private|protected|internal|open|abstract|inline|override|suspend|operator|infix|tailrec|external|actual|expect|final|static)\\s+)*" +
                    "fun\\s+(?:<[^>]*>\\s*)?(?:[A-Za-z_][\\w.<>,?* ]*\\.)?([A-Za-z_]\\w*)\\s*\\(",
            ),
        ),
    )

    // 注解也要算前缀：`@Override public String toString()` 若不认注解，整个方法会漏掉。
    private const val JAVA_MODIFIERS =
        "(?:@\\w+(?:\\([^)]*\\))?\\s*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp|sealed|non-sealed)\\s+)*"

    private val javaRules = listOf(
        Rule(
            kind = KIND_CLASS,
            regex = Regex("^$JAVA_MODIFIERS(class|interface|enum|record|@interface)\\s+([A-Za-z_$][\\w$]*)"),
            nameGroup = 2,
            kindGroup = 1,
        ),
        // 方法：必须至少有一个修饰符（static/final/…），否则会把 return foo(...) 误当成定义。
        Rule(
            kind = KIND_FUNCTION,
            regex = Regex(
                "^(?:@\\w+(?:\\([^)]*\\))?\\s*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)\\s+)+" +
                    "(?:<[^>]*>\\s*)?[\\w.$\\[\\]<>, ?]+\\s+([A-Za-z_$][\\w$]*)\\s*\\(",
            ),
        ),
    )

    private val goRules = listOf(
        // 方法带接收者：func (r *Repo) Save(...)
        Rule(
            kind = KIND_FUNCTION,
            regex = Regex("^func\\s+(?:\\([^)]*\\)\\s*)?([A-Za-z_]\\w*)\\s*[\\[(]"),
        ),
        Rule(
            kind = KIND_TYPE,
            regex = Regex("^type\\s+([A-Za-z_]\\w*)\\s+(struct|interface)\\b"),
            kindGroup = 2,
        ),
        Rule(kind = KIND_TYPE, regex = Regex("^type\\s+([A-Za-z_]\\w*)\\s+")),
    )

    private const val RUST_VISIBILITY = "(?:pub(?:\\([^)]*\\))?\\s+)?"
    private const val RUST_FN_PREFIX = "(?:const\\s+|async\\s+|unsafe\\s+|extern\\s+\"[^\"]*\"\\s+)*"

    private val rustRules = listOf(
        Rule(
            kind = KIND_FUNCTION,
            regex = Regex("^$RUST_VISIBILITY$RUST_FN_PREFIX" + "fn\\s+([A-Za-z_]\\w*)"),
        ),
        Rule(
            kind = KIND_STRUCT,
            regex = Regex("^$RUST_VISIBILITY(struct|enum|trait|union)\\s+([A-Za-z_]\\w*)"),
            nameGroup = 2,
            kindGroup = 1,
        ),
        // impl Foo / impl Bar for Baz：名字取整行更直观，单独处理会丢掉 for 后面的类型。
        Rule(
            kind = KIND_IMPL,
            regex = Regex("^$RUST_VISIBILITY" + "impl\\b\\s*(?:<[^>]*>\\s*)?([A-Za-z_]\\w*)"),
        ),
        Rule(
            kind = KIND_MODULE,
            regex = Regex("^$RUST_VISIBILITY(mod|type|const|static)\\s+([A-Za-z_]\\w*)"),
            nameGroup = 2,
            kindGroup = 1,
        ),
    )

    private val pythonRules = listOf(
        Rule(kind = KIND_CLASS, regex = Regex("^class\\s+([A-Za-z_]\\w*)")),
        Rule(kind = KIND_FUNCTION, regex = Regex("^(?:async\\s+)?def\\s+([A-Za-z_]\\w*)\\s*\\(")),
    )

    private const val JS_DECLARATION = "(?:export\\s+)?(?:default\\s+)?(?:declare\\s+)?"

    private val javascriptRules = listOf(
        Rule(
            kind = KIND_CLASS,
            regex = Regex("^$JS_DECLARATION(?:abstract\\s+)?(class|interface|enum|type|namespace)\\s+([A-Za-z_$][\\w$]*)"),
            nameGroup = 2,
            kindGroup = 1,
        ),
        Rule(
            kind = KIND_FUNCTION,
            regex = Regex("^$JS_DECLARATION(?:async\\s+)?function\\s*\\*?\\s*([A-Za-z_$][\\w$]*)"),
        ),
        // const Foo = () => … / const foo = function …：React 组件基本都是这个形状。
        Rule(
            kind = KIND_FUNCTION,
            regex = Regex(
                "^$JS_DECLARATION(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*(?::[^=]+)?=\\s*" +
                    "(?:async\\s*)?(?:function\\b|\\([^)]*\\)\\s*=>|[A-Za-z_$][\\w$]*\\s*=>)",
            ),
        ),
    )

    private const val SWIFT_MODIFIERS =
        "(?:(?:public|private|internal|open|fileprivate|static|final|override|mutating|nonisolated|@\\w+)\\s+)*"

    // Swift 的关键字与统一的 kind 名字对不上（func→function、protocol→interface），
    // 拆成几条规则，避免结果里冒出模型和用户都不认识的种类。
    private val swiftRules = listOf(
        Rule(kind = KIND_FUNCTION, regex = Regex("^$SWIFT_MODIFIERS" + "func\\s+([A-Za-z_]\\w*)")),
        Rule(kind = KIND_INTERFACE, regex = Regex("^$SWIFT_MODIFIERS" + "protocol\\s+([A-Za-z_]\\w*)")),
        Rule(
            kind = KIND_CLASS,
            regex = Regex("^$SWIFT_MODIFIERS" + "(class|struct|enum|extension|actor)\\s+([A-Za-z_]\\w*)"),
            nameGroup = 2,
            kindGroup = 1,
        ),
    )

    private val shellRules = listOf(
        Rule(
            kind = KIND_FUNCTION,
            regex = Regex("^(?:function\\s+)?([A-Za-z_][\\w-]*)\\s*\\(\\)\\s*\\{"),
        ),
    )
}
