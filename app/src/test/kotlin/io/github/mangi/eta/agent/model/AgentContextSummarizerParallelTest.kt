package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 分批摘要的并行执行。
 *
 * 背景（PKX110 / Android 16，中转端点 `myc.sq6.top:7863`，同一段 3,000 字历史）：
 * 3 个并发请求全部 200、墙钟 10.43 s ≈ 最慢的单请求；2 并发墙钟 8.02 s。
 * 串行时 3~4 批的墙钟就是各批之和 —— 这是压缩慢的结构性原因，与思考档位无关。
 *
 * 这里守三件事：并行确实发生、并发度不超过常量、出错时抛的还是原来的错误码。
 * 不用「墙钟更短」当判据：CI 机器负载会让它偶发变红，in-flight 峰值才是直接的证据。
 */
class AgentContextSummarizerParallelTest {

    private val config = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid",
        apiKey = "fixture",
        model = "fixture",
        systemPrompt = "固定约束",
        contextWindow = 128_000,
    )

    /** 每批上限 240K 字符：三条 200K 字符的历史必然切成 3 批（再加一次合并）。 */
    private fun history(): List<AgentModelClient.ConversationMessage> = buildList {
        repeat(3) { index ->
            add(AgentModelClient.ConversationMessage(role = "user", content = "第 ${index + 1} 问"))
            add(AgentModelClient.ConversationMessage(role = "assistant", content = "历史事实".repeat(50_000)))
        }
    }

    private fun provider(
        delayMs: Long = 0,
        inFlight: AtomicInteger = AtomicInteger(),
        maxInFlight: AtomicInteger = AtomicInteger(),
        requests: AtomicInteger = AtomicInteger(),
        text: (ProviderRequest) -> String = { "此前任务已完成。" },
    ) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(
            EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false,
        )

        override fun complete(
            request: ProviderRequest,
            runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit,
        ): ProviderResponse {
            requests.incrementAndGet()
            val now = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { previous -> if (previous > now) previous else now }
            try {
                if (delayMs > 0) Thread.sleep(delayMs)
                return ProviderResponse(
                    JSONObject().put("role", "assistant").put("content", text(request)).put("finish_reason", "stop"),
                )
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    @Test
    fun batchesRunInParallelWithinTheConfiguredLimit() {
        val maxInFlight = AtomicInteger()
        val requests = AtomicInteger()
        val summarizer = AgentContextSummarizer(
            config,
            provider(delayMs = 120, maxInFlight = maxInFlight, requests = requests),
            AgentRunController(),
            roleplay = false,
        )

        val outcome = summarizer.summarize(history())

        assertEquals("三条 200K 字符的历史应切成 3 批", 3, outcome.batches)
        // 各批摘要都很短（“此前任务已完成。”）时，拼接后远低于 SUMMARY_TARGET_CHARS，
        // 于是**跳过合并**：只发 3 次请求，而不是 3 + 1。
        assertEquals(
            "短摘要应跳过合并：${outcome.batches} 批 → ${requests.get()} 次请求",
            outcome.batches,
            requests.get(),
        )
        assertTrue(
            "应真的同时进行（峰值 ${maxInFlight.get()}）：串行实现下这里恒为 1",
            maxInFlight.get() >= 2,
        )
        assertTrue(
            "并发度不能超过常量：峰值 ${maxInFlight.get()} > ${AgentContextSummarizer.MAX_PARALLEL_BATCHES}",
            maxInFlight.get() <= AgentContextSummarizer.MAX_PARALLEL_BATCHES,
        )
    }

    @Test
    fun shortBatchSummariesSkipTheMergeRequest() {
        // 真机依据（2026-11）：1.44M 字符压缩成 3 批，合并后正文只有 1,482 字符，
        // 远低于 MAX_SUMMARY_CHARS=12,000 —— 为这点文字再跑一轮模型请求纯属白等。
        val requests = AtomicInteger()
        val batches = StringBuilder()
        val summarizer = AgentContextSummarizer(
            config,
            provider(
                requests = requests,
                text = { request ->
                    val dumped = request.messages.toString()
                    if (dumped.contains("此前摘要：")) "合并后的摘要" else "简短摘要"
                },
            ),
            AgentRunController(),
            roleplay = false,
        )

        val outcome = summarizer.summarize(history())

        assertEquals(3, outcome.batches)
        assertEquals("3 批各一次，不应有第 4 次合并请求", 3, requests.get())
        assertTrue("跳过合并时应保留各批的内容", outcome.text.contains("简短摘要"))
    }

    @Test
    fun longBatchSummariesStillGoThroughMerge() {
        // 反向守卫生机：拼接后**超过** MERGE_SKIP_MAX_CHARS 时必须回到合并，
        // 否则摘要会无限膨胀，最后又被 MAX_SUMMARY_CHARS 判不合格。
        val requests = AtomicInteger()
        val summarizer = AgentContextSummarizer(
            config,
            provider(
                requests = requests,
                text = { request ->
                    if (request.messages.toString().contains("此前摘要：")) {
                        "合并后的摘要"
                    } else {
                        // 每批 5,000 字符：3 批拼接 = 15,000 > MERGE_SKIP_MAX_CHARS(12,000)
                        "细".repeat(5_000)
                    }
                },
            ),
            AgentRunController(),
            roleplay = false,
        )

        val outcome = summarizer.summarize(history())

        assertEquals(3, outcome.batches)
        assertEquals("超长时必须多一次合并请求", 4, requests.get())
        assertEquals("合并后的摘要", outcome.text)
    }

    @Test
    fun oneCharOverTheSkipThresholdGoesThroughMerge() {
        // 边界：拼接接头也占长度（joinToString("\n\n")，3 批多出 4 个字符），
        // 所以每批 4,000 时实际是 12,004 —— 已经超过 12,000，必须走合并。
        // 这个测试就是用真实字符串长度守住阈值的闭合端，不依赖"差一点"的巧合。
        val requests = AtomicInteger()
        val summarizer = AgentContextSummarizer(
            config,
            provider(
                requests = requests,
                text = { request ->
                    if (request.messages.toString().contains("此前摘要：")) "合并后的摘要"
                    else "细".repeat(4_000)
                },
            ),
            AgentRunController(),
            roleplay = false,
        )

        val outcome = summarizer.summarize(history())

        assertEquals(3, outcome.batches)
        assertEquals("12,004 已超 12,000，必须合并", 4, requests.get())
        assertEquals("合并后的摘要", outcome.text)
    }

    @Test
    fun justUnderTheSkipThresholdSkips() {
        // 反向边界：每批 3,998 字符，3 批 + 4 个接头 = 11,998 < 12,000，应当跳过。
        val requests = AtomicInteger()
        val summarizer = AgentContextSummarizer(
            config,
            provider(
                requests = requests,
                text = { request ->
                    if (request.messages.toString().contains("此前摘要：")) "合并后的摘要"
                    else "细".repeat(3_998)
                },
            ),
            AgentRunController(),
            roleplay = false,
        )

        val outcome = summarizer.summarize(history())

        assertEquals(3, outcome.batches)
        assertEquals("11,998 未超 12,000，应跳过合并", 3, requests.get())
        assertEquals(11_998, outcome.text.length)
    }

    @Test
    fun mergeStillSeesBatchesInOldestFirstOrder() {
        // 合并阶段依赖「老 → 新」的顺序：并行只改变完成先后，不能改变拼接顺序。
        val mergeInput = StringBuilder()
        val summarizer = AgentContextSummarizer(
            config,
            provider(
                delayMs = 30,
                text = { request ->
                    val dumped = request.messages.toString()
                    if (dumped.contains("此前摘要：")) {
                        mergeInput.append(dumped)
                        "合并后的摘要"
                    } else {
                        // 每批的回执带上自己的批号，合并输入里就能看出顺序。
                        // 同时要足够长（3 × 5000 = 15000 > MERGE_SKIP_MAX_CHARS 12000），
                        // 否则会被「短摘要跳过合并」逻辑接走，这里就永远测不到顺序。
                        val batchTag = Regex("第 (\\d+) 问").find(dumped)?.groupValues?.get(1) ?: "?"
                        "第 $batchTag 批摘要" + "细".repeat(5_000)
                    }
                },
            ),
            AgentRunController(),
            roleplay = false,
        )

        val outcome = summarizer.summarize(history())

        assertEquals(3, outcome.batches)
        val merged = mergeInput.toString()
        assertTrue("合并输入应包含三段摘要：$merged", merged.contains("批摘要"))
        val first = merged.indexOf("第 1 批摘要")
        val second = merged.indexOf("第 2 批摘要")
        val third = merged.indexOf("第 3 批摘要")
        assertTrue(
            "合并顺序应为老 → 新：1=$first 2=$second 3=$third",
            first in 0 until second && second < third,
        )
    }

    @Test
    fun batchFailureKeepsTheOriginalReasonCode() {
        // 并行实现最容易踩的坑：worker 里的异常被 ExecutionException 包住，
        // 压缩事件只剩「CONTEXT_SUMMARY_FAILED」，真实的 CONTEXT_SUMMARY_INVALID 丢了。
        val summarizer = AgentContextSummarizer(
            config,
            // 空正文 → 两次尝试都判「返回内容为空」且不可兜底 → 抛 CODE_INVALID。
            provider(text = { "" }),
            AgentRunController(),
            roleplay = false,
        )

        try {
            summarizer.summarize(history())
            fail("空摘要必须判失败")
        } catch (failure: AgentModelFailure) {
            assertEquals(AgentContextSummarizer.CODE_INVALID, failure.code)
        }
    }
}
