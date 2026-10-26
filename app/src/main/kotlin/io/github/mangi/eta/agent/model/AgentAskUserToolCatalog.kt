package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * `ask_user`：把「必须先由用户决定」的问题显式交还给用户，并就此停下等待。
 *
 * 为什么不做成阻塞等待：Eta 的 run 跑在后台服务里，用户在聊天界面的下一条消息本来就会作为补充指令
 * 到达（steering）。再叠一层「工具内等待」只会让 run 长时间挂住、还需要额外的跨进程唤醒通道；
 * 而这里真正要治的问题是「模型不问就自己假设答案」，靠一条明确语义的工具 + 停止指令即可解决。
 *
 * 所以本工具的契约是：校验并规整问题 → 结果里带着问题和选项回到对话 → 模型立即结束本轮。
 */
internal object AgentAskUserToolCatalog {
    const val NAME = "ask_user"
    const val MAX_QUESTION_CHARS = 500
    const val MAX_OPTIONS = 6
    const val MAX_OPTION_CHARS = 120

    /** 提问 + 可选项；`options` 为空表示自由回答。 */
    data class Ask(val question: String, val options: List<String>)

    val names: Set<String> = setOf(NAME)

    fun appendTo(tools: JSONArray) {
        tools.put(schema())
    }

    fun schema(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", NAME)
                .put(
                    "description",
                    "遇到必须由用户决定、无法从设备上查到的信息时（例如要选哪个应用、是否同意某项改动、" +
                        "两个方案要哪个），用它向用户提问，然后立即结束本轮等待回答。" +
                        "不要在提问后继续执行依赖该答案的步骤，也不要替用户假设答案。",
                )
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "question",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "要问用户的问题，一句话说清楚，最多 $MAX_QUESTION_CHARS 字")
                                        .put("maxLength", MAX_QUESTION_CHARS),
                                )
                                .put(
                                    "options",
                                    JSONObject()
                                        .put("type", "array")
                                        .put("description", "可选的候选项，用户可以直接选；留空表示自由回答")
                                        .put("maxItems", MAX_OPTIONS)
                                        .put(
                                            "items",
                                            JSONObject()
                                                .put("type", "string")
                                                .put("maxLength", MAX_OPTION_CHARS),
                                        ),
                                ),
                        )
                        .put("required", JSONArray().put("question"))
                        .put("additionalProperties", false),
                ),
        )

    /** 给界面用：参数不合法时返回 null，不要因为展示失败影响工具调用本身。 */
    fun askFromArguments(argumentsJson: String): Ask? = runCatching {
        parse(JSONObject(argumentsJson.ifBlank { "{}" }))
    }.getOrNull()

    /** 校验并规整参数；不合法时抛 [IllegalArgumentException]，消息直接给模型看。 */
    fun parse(args: JSONObject): Ask {
        val question = args.optString("question").trim()
        require(question.isNotEmpty()) { "question 不能为空" }
        require(question.length <= MAX_QUESTION_CHARS) { "question 最多 $MAX_QUESTION_CHARS 字，请精简" }
        val raw = args.optJSONArray("options") ?: JSONArray()
        require(raw.length() <= MAX_OPTIONS) { "options 最多 $MAX_OPTIONS 个" }
        val options = (0 until raw.length()).map { index ->
            val option = raw.optString(index).trim()
            require(option.isNotEmpty()) { "options 第 ${index + 1} 项为空" }
            require(option.length <= MAX_OPTION_CHARS) { "options 第 ${index + 1} 项最多 $MAX_OPTION_CHARS 字" }
            option
        }
        require(options.distinct().size == options.size) { "options 有重复项" }
        return Ask(question, options)
    }
}
