package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentSelfAuditToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "eta_self_audit",
                description = "只读地检查 Eta 自身的运行状况，返回三段内容：findings（有证据的缺陷，按 P0/P1/P2 排序，每条带 evidence/impact/fix/verify）、clues（线索，不是缺陷）、unavailable（这次测不了的域与原因）。" +
                    "用于回答「Eta 现在有什么问题、该怎么改」：先跑它拿事实，再基于 evidence 做归因，不要凭空猜缺陷。" +
                    "崩溃与 ANR 域需要 root（读系统 dropbox）；没有 root 时该域会明确写进 unavailable，此时不得声称「没有崩溃」。" +
                    "资源域用 Java Heap PSS 与堆上限的比值判断压力，PSS 不等于已分配量，只能看趋势。" +
                    "本工具不修改任何系统状态或配置。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "scope",
                                JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray(listOf("all", "crash", "resource", "capability", "code")))
                                    .put(
                                        "description",
                                        "要检查的域，默认 all。crash=崩溃与 ANR（需 root）；resource=堆压力与进程存活；" +
                                            "capability=无障碍/通知使用权/使用情况访问；code=源码里的 TODO 与未提交改动（仅开发机）。",
                                    ),
                            )
                            .put(
                                "hours",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "回溯多少小时内的崩溃与 ANR，1-720，默认 48。"),
                            )
                            .put(
                                "source_root",
                                JSONObject()
                                    .put("type", "string")
                                    .put(
                                        "description",
                                        "可选。开发机上的 Eta 源码目录，用于 code 域；不传则自动探测 /data/local/tmp/eta/eta-v2。" +
                                            "真机发布包通常没有源码，此时 code 域会记为 unavailable。",
                                    ),
                            ),
                    )
                    .put("required", JSONArray()),
            )
        )
    }
}
