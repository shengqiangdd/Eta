package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 声明会话级任务清单工具；清单数据本身由 [AgentTodoPlan] 表达。 */
internal object AgentTodoToolCatalog {
    const val WRITE = "todo_write"

    val names: Set<String> = setOf(WRITE)

    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = WRITE,
                description = "Record and update a task list to plan multi-step work and show progress; " +
                    "skip it for trivial single-step tasks. Add one todo per concrete step before you start. " +
                    "While work remains, keep exactly one todo `in_progress` (Eta runs steps one at a time). " +
                    "Mark each todo `completed` as soon as it is done. " +
                    "Every call replaces the whole list: always pass the COMPLETE list, and pass an empty array to clear it. " +
                    "The list persists across turns of this conversation and is shown to the user.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "todos",
                            JSONObject()
                                .put("type", "array")
                                .put("maxItems", AgentTodoPlan.MAX_ITEMS)
                                .put(
                                    "description",
                                    "The COMPLETE task list, replacing any previous list. Empty array clears it.",
                                )
                                .put(
                                    "items",
                                    JSONObject()
                                        .put("type", "object")
                                        .put("additionalProperties", false)
                                        .put(
                                            "properties",
                                            JSONObject()
                                                .put(
                                                    "content",
                                                    JSONObject()
                                                        .put("type", "string")
                                                        .put("maxLength", AgentTodoPlan.MAX_CONTENT_CHARS)
                                                        .put("description", "What the task is — a short imperative line."),
                                                )
                                                .put(
                                                    "status",
                                                    JSONObject()
                                                        .put("type", "string")
                                                        .put(
                                                            "enum",
                                                            JSONArray()
                                                                .put(AgentTodoStatus.PENDING.wire)
                                                                .put(AgentTodoStatus.IN_PROGRESS.wire)
                                                                .put(AgentTodoStatus.COMPLETED.wire),
                                                        )
                                                        .put("description", "pending (not started) | in_progress (now) | completed (done)."),
                                                ),
                                        )
                                        .put("required", JSONArray().put("content").put("status")),
                                ),
                        ),
                    )
                    .put("required", JSONArray().put("todos")),
            ),
        )
    }
}
