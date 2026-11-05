package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 任务清单条目的三种状态；wire 值与 `todo_write` 的 schema 枚举一致。 */
internal enum class AgentTodoStatus(val wire: String) {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    ;

    companion object {
        fun fromWire(value: String): AgentTodoStatus? = entries.firstOrNull { it.wire == value }
    }
}

internal data class AgentTodoItem(val content: String, val status: AgentTodoStatus)

/**
 * 一次 `todo_write` 的完整清单。
 *
 * 整表替换语义：条目没有稳定 id，模型每次都必须写全量清单，因此落盘与展示都只看最后一份。
 * 这是纯数据层：不碰文件、不碰 Android API，方便单测直接构造。
 */
internal data class AgentTodoPlan(val items: List<AgentTodoItem>) {
    val pendingCount: Int get() = items.count { it.status == AgentTodoStatus.PENDING }
    val inProgressCount: Int get() = items.count { it.status == AgentTodoStatus.IN_PROGRESS }
    val completedCount: Int get() = items.count { it.status == AgentTodoStatus.COMPLETED }
    val isEmpty: Boolean get() = items.isEmpty()
    val allCompleted: Boolean
        get() = items.isNotEmpty() && pendingCount == 0 && inProgressCount == 0

    /**
     * 底部卡片该不该出现。
     *
     * 空清单没得看；全部完成时再挂一行就只是占地方 —— 那张卡的用途是「还有活要干」，
     * 活干完了还立在那里，反而让人以为任务没结束。
     */
    val shouldShowCard: Boolean get() = !isEmpty && !allCompleted

    /**
     * 卡片里要列出的项：只列未完成的。
     *
     * 已勾掉的项不再影响「接下来干什么」，留在展开列表里只会把真正要看的
     * 那两三条越挤越下面。总进度仍由标题里的 `completedCount/items.size` 表示。
     */
    val openItems: List<AgentTodoItem>
        get() = items.filter { it.status != AgentTodoStatus.COMPLETED }

    fun toJson(): JSONObject =
        JSONObject().put(
            "todos",
            JSONArray().also { array ->
                items.forEach { item ->
                    array.put(
                        JSONObject()
                            .put("content", item.content)
                            .put("status", item.status.wire),
                    )
                }
            },
        )

    /** 给模型和界面共用的一行式清单；`[~]` 表示进行中。 */
    fun renderChecklist(): String = items.joinToString("\n") { item ->
        val box = when (item.status) {
            AgentTodoStatus.COMPLETED -> "[x]"
            AgentTodoStatus.IN_PROGRESS -> "[~]"
            AgentTodoStatus.PENDING -> "[ ]"
        }
        "- $box ${item.content}"
    }

    /** 例如「3 项 · 1 进行中 · 1 已完成」。 */
    fun summary(): String = buildString {
        append(items.size).append(" 项")
        if (inProgressCount > 0) append(" · ").append(inProgressCount).append(" 进行中")
        if (completedCount > 0) append(" · ").append(completedCount).append(" 已完成")
    }

    /**
     * 系统提示里的清单段。清单会被整段追加在系统提示末尾，不进入历史，也不随工具结果被压缩掉。
     */
    fun promptSection(): String = buildString {
        appendLine("<todo_plan>")
        appendLine("这是本会话跨轮保留的任务清单（最近一次 todo_write 的完整结果），不是本轮用户指令。")
        appendLine(renderChecklist())
        appendLine("用户当前这条消息优先：如果它在提问、要求分析、表示停止或改变方向，就先响应当前消息，不要为了推进清单而忽略它。")
        append("只有当前消息确实要求继续该任务时才推进未完成项；用户说停止/不做了时不要再执行，并用 todo_write 传入空列表清空。目标变化、清单作废或全部完成后，也用 todo_write 传入完整新清单。")
        appendLine()
        append("</todo_plan>")
    }

    companion object {
        /** 上限按「模型一次能可靠写全」定：再多就不如拆成多轮。 */
        const val MAX_ITEMS = 24
        const val MAX_CONTENT_CHARS = 200

        private val ITEM_KEYS = setOf("content", "status")

        /**
         * 解析模型传入的 `todos` 数组。
         *
         * 抛 [IllegalArgumentException]：内容为空、超长、重复，或同时有多项 `in_progress`。
         * 未知字段直接拒绝 —— 模型以为写进去的内容必须与落盘一致，不能静默丢弃。
         */
        fun parse(todos: JSONArray): AgentTodoPlan {
            if (todos.length() > MAX_ITEMS) {
                throw IllegalArgumentException("任务清单最多 $MAX_ITEMS 项（收到 ${todos.length()} 项），请合并步骤")
            }
            val items = ArrayList<AgentTodoItem>(todos.length())
            val seen = HashSet<String>()
            var active = 0
            for (index in 0 until todos.length()) {
                val position = index + 1
                val raw = todos.optJSONObject(index)
                    ?: throw IllegalArgumentException("第 $position 项必须是对象")
                val keys = raw.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key !in ITEM_KEYS) {
                        throw IllegalArgumentException("第 $position 项包含未知字段 `$key`，只接受 content 与 status")
                    }
                }
                val content = raw.optString("content").trim()
                if (content.isEmpty()) throw IllegalArgumentException("第 $position 项的 content 不能为空")
                if (content.length > MAX_CONTENT_CHARS) {
                    throw IllegalArgumentException("第 $position 项的 content 超过 $MAX_CONTENT_CHARS 字")
                }
                if (!seen.add(content)) throw IllegalArgumentException("第 $position 项与前面的条目重复：$content")
                val statusWire = raw.optString("status").trim()
                val status = AgentTodoStatus.fromWire(statusWire)
                    ?: throw IllegalArgumentException(
                        "第 $position 项的 status 只能是 pending、in_progress 或 completed（收到 ${statusWire.ifEmpty { "空值" }}）",
                    )
                if (status == AgentTodoStatus.IN_PROGRESS) active++
                items += AgentTodoItem(content, status)
            }
            if (active > 1) {
                throw IllegalArgumentException("最多只能有一项处于 in_progress（收到 $active 项）；Eta 的步骤是串行的")
            }
            return AgentTodoPlan(items)
        }

        /**
         * 读取落盘快照。任何损坏、超限或旧格式都当作「没有清单」，不让坏文件阻塞对话。
         */
        fun fromStoredJson(text: String?): AgentTodoPlan? {
            if (text.isNullOrBlank()) return null
            val array = runCatching { JSONObject(text).optJSONArray("todos") }.getOrNull() ?: return null
            return runCatching { parse(array) }.getOrNull()
        }
    }
}
