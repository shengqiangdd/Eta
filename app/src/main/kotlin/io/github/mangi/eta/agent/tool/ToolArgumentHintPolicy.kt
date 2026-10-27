package io.github.mangi.eta.agent.tool

/**
 * 「模型传了本工具不认识的参数」必须说出来，不能静默丢掉。
 *
 * 背景（157 轮）：`scroll_element` 的 `display_id` 被整条忽略 —— 模型写「在主屏滚动」，
 * 动作仍在快照所属的虚拟屏上、返回 `ok=true`，模型完全无从察觉。
 * 那处已在 fix167 补上校验；但**参数名本身**仍可能写错（`displayId`、`observationID`、
 * `coordinateSpace` —— 模型很爱用驼峰），而 `JSONObject.optXxx("display_id")` 只会拿到 null，
 * 工具照常执行，结果里也看不出任何异常。
 *
 * 这里只做「提示」，不改动作是否执行、也不改 `ok`：
 * - 归一化后能对上某个声明参数（`displayId` → `display_id`）→ 明确给出正确名字；
 * - 完全对不上 → 告诉它这个参数不会被读取，并列出可用参数。
 *
 * 纯 JVM：不引用 android 类型，直接单测覆盖。
 */
internal object ToolArgumentHintPolicy {

    /** @param provided 本次调用实际带上的参数名 */
    data class Hint(
        val provided: String,
        /** 归一化后匹配到的声明参数名；完全对不上时为 null。 */
        val suggestion: String?,
    )

    /** @param declared 本工具 schema 里声明的参数名 */
    fun review(declared: Set<String>, provided: Collection<String>): List<Hint> {
        if (declared.isEmpty()) return emptyList()
        val byNormalized = declared.associateBy { normalize(it) }
        return provided
            .filter { it.isNotBlank() && it !in declared }
            .map { key -> Hint(provided = key, suggestion = byNormalized[normalize(key)]) }
    }

    /** 大小写与下划线都不算区别：`displayId` / `Display_Id` / `display_id` → `displayid`。 */
    fun normalize(key: String): String = key.lowercase().filter { it.isLetterOrDigit() }

    fun message(hint: Hint, declared: Set<String>): String {
        val suggestion = hint.suggestion
        return if (suggestion != null && suggestion != hint.provided) {
            "参数「${hint.provided}」不会被使用：本工具叫「$suggestion」，请按这个名字重发（本次已按缺省值执行）"
        } else {
            "参数「${hint.provided}」不会被使用：本工具的参数是 ${declared.sorted().joinToString("、")}（本次已按缺省值执行）"
        }
    }
}
