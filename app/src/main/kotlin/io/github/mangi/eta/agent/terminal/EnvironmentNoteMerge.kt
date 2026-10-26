package io.github.mangi.eta.agent.terminal

import org.json.JSONObject

/**
 * 把一条环境提示合并进工具回执的 `environment_note` 字段。
 *
 * 为什么单独抽出来：`JSONObject.optString` 对 JSON null 会返回**字面量 "null"**，
 * 直接参与拼接就会得到「null 本次 environment=…」这种噪音前缀（真机实测踩到过，
 * 是 fix142 的自动切换提示引入的）；回执本身是给模型读的，前缀污染会被当成字段值的一部分。
 *
 * 抽出来才能用单测守住三种情况：键不存在 / 值是 null / 已有实际内容。
 */
internal object EnvironmentNoteMerge {

    /** note 为 null 时原样返回，不往回执里塞空字段。 */
    fun merge(result: String, note: String?): String {
        if (note == null) return result
        return runCatching {
            val json = JSONObject(result)
            val existing = existingNote(json)
            json.put("environment_note", if (existing.isEmpty()) note else "$existing $note")
        }.getOrElse { result }.toString()
    }

    /**
     * 已有的提示文本。三种情况统一收敛成「空串」：
     * 键不存在、值是 JSON null、值恰好是字面量 "null"（旧实现写坏的数据）。
     */
    private fun existingNote(json: JSONObject): String {
        if (!json.has("environment_note") || json.isNull("environment_note")) return ""
        return json.optString("environment_note").trim().takeUnless { it == "null" }.orEmpty()
    }
}
