package io.github.mangi.eta.agent.runtime

import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/** Runtime 事件的稳定 JSON 投影；只编码 IPC 已公开的安全字段。 */
internal object AgentEventJsonCodec {
    fun encode(event: AgentEvent): String {
        require(event !is AgentEvent.SearchSuggestions) { "SEARCH_SUGGESTIONS_NOT_PERSISTABLE" }
        return bundleToJson(AgentRuntimeWire.eventToBundle(event)).toString()
    }

    fun decode(raw: String): AgentEvent? = runCatching {
        AgentRuntimeWire.eventFromBundle(jsonToBundle(JSONObject(raw)))?.recoveryProjection()
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun bundleToJson(bundle: Bundle): JSONObject =
        JSONObject().also { json ->
            bundle.keySet().forEach { key ->
                when (val value = bundle.get(key)) {
                    is String -> json.put(key, value)
                    is Boolean -> json.put(key, value)
                    is Number -> json.put(key, value)
                    is ArrayList<*> -> json.put(key, JSONArray(value))
                    null -> json.put(key, JSONObject.NULL)
                }
            }
        }

    /**
     * JSON → Bundle。
     *
     * 数字必须按 [Number] 统一处理，**不能**靠 `is Long` / `is Int` 分流：
     * `JSONObject(String)` 解析出的数字只要放得进 int 就是 `Integer`，
     * 与 Bundle 里 `putLong` 写的 `elapsed_ms` 类型不一致。
     * Bundle 是强类型的 —— `putInt` 的键用 `getLong` 读会静默得到 0，
     * 于是耗时字段在「事件 → JSON 归档 → 读回」这条路上恒为 0。
     * 读取端（[AgentRuntimeWire.eventFromBundle] 的 optionalElapsedMs）
     * 已兼容两种数字类型，这里按值域选 int\/long 只是为了让 Bundle 自身自洽。
     */
    private fun jsonToBundle(json: JSONObject): Bundle =
        Bundle().also { bundle ->
            json.keys().forEach { key ->
                when (val value = json.opt(key)) {
                    is String -> bundle.putString(key, value)
                    is Boolean -> bundle.putBoolean(key, value)
                    is JSONArray -> bundle.putStringArrayList(
                        key,
                        ArrayList((0 until value.length()).map { index -> value.optString(index) }),
                    )
                    is Number -> {
                        val asLong = value.toLong()
                        if (asLong in Int.MIN_VALUE..Int.MAX_VALUE) {
                            bundle.putInt(key, asLong.toInt())
                        } else {
                            bundle.putLong(key, asLong)
                        }
                    }
                    else -> Unit
                }
            }
        }
}
