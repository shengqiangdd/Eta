package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 上下文体量的**本地**度量与压缩触发判定。
 *
 * 为什么不直接用 `message.toString().length`：那会为每条消息生成一份完整的 JSON 字符串。
 * 真机事故（PKX110 / Android 16，2026-10-19）正是这类「整份序列化」在 2.79M 字符的历史上
 * 申请 11,161,552 字节而失败——度量本身成了压垮进程的那次分配。
 * 这里改为按字段累加长度：字符串取 `length`（O(1) 且不复制），多模态数组逐 part 累加。
 */
internal object AgentContextBudget {

    /**
     * 会话历史的字符预算上限（字符，不是字节）。
     *
     * 依据：主流模型窗口最大约 100 万 token 量级，而 1 字符平均不到 1 token；
     * 取 120 万字符作为「无论哪个模型都必然装不下」的绝对上限。
     * 它只在**拿不到远端 usage** 时兜底触发压缩，避免历史无限增长到序列化时爆堆。
     *
     * 定这个数而不是更小的值，是为了保留既有语义：小幅超出窗口的会话不会被本地估算误判压缩。
     */
    const val HARD_CONTEXT_CHARS = 1_200_000L

    /**
     * 字符 → token 的**估算**除数，只用于拿不到远端 usage 时判断「窗口用掉多少」。
     *
     * 实测依据（PKX110 / Android 16，2026-10-23 生产库）：本会话 history 440,850 字符
     * （按字段累加，含 reasoning 与工具参数），端点回报 `ctx=92,714` tokens
     * （其中含系统提示与工具定义），折算约 4.6~5.9 字符/token。
     *
     * 取 4 仍偏保守（略高估 token），但不再像原先的 2 那样**高估一倍以上**：
     * 那是把「实际只用了 36%」的会话本地估成 86%，在没有 usage 的端点上会
     * 触发一次根本没必要的压缩（多一次摘要请求，还会丢细节）。
     */
    private const val CHARS_PER_TOKEN_ESTIMATE = 4L

    /**
     * 字符硬上限的折算除数，只用于「宁可早压缩也别在序列化时爆堆」的兜底。
     *
     * 与 [CHARS_PER_TOKEN_ESTIMATE] 故意分开：估算要尽量准，兜底要尽量早。
     * 按 2 字符/token 折算，900K token 的窗口对应 1.8M 字符的硬上限（见 [hardLimitFor]）。
     */
    private const val CHARS_PER_TOKEN_FLOOR = 2L

    /** 与 [AgentContextSession] 保持一致的触发比例：窗口用掉 85% 就该腾空间。 */
    const val TRIGGER_RATIO = 0.85

    /**
     * 用户没填 `contextWindow` 时假设的窗口（token）。
     *
     * 128K 是主流端点的**下限**。真机上绝大多数人不会去填这个值，而原实现在窗口未知时
     * 直接不压缩，于是上下文一直涨：实测 22 轮 / 1422 条消息 / 21.4 万 token，一次都没压。
     *
     * 注意：当前取值下 128,000 × [TRIGGER_RATIO] = 108,800 **高于**
     * [ABSOLUTE_COMPACT_TOKENS]，所以窗口未知时实际阈值由绝对上限决定，这个常量
     * 影响不到结果。它不是死代码（调低或调高绝对上限都会生效），但“窗口未知时
     * 在 10.88 万压”这个读法当前不成立，别照着它推算行为。
     */
    const val DEFAULT_ASSUMED_CONTEXT_WINDOW = 128_000

    /**
     * 绝对压缩上限（token）：不管配置的窗口多大，超过它就压。
     *
     * 真机上「窗口填得很大」比「没填」更常见地导致失控：`tokens >= window * 0.85`
     * 永远不成立，于是 23.9 万 token、1533 条消息一次都不压（2026-11 实测）。
     * 10 万是个很保守的值 —— 现代端点几乎都能装，而压完仍保留足够的近期原文。
     */
    const val ABSOLUTE_COMPACT_TOKENS = 100_000

    /**
     * 手动压缩的下限（字符）。低于它就不该再花一次模型请求。
     *
     * 依据（canary 真机，2026-10-25）：上下文只有 1,796 字符时点「压缩上下文」，
     * 端点照样跑完一次完整摘要 —— out 18,946 token（其中 17,262 是 reasoning）、耗时约 2.5 分钟，
     * 最后以 CONTEXT_NO_REDUCTION 失败。那个量级的上下文再怎么压也不可能更短，
     * 用户点这个按钮本意是省 token，不该反过来花掉两万。
     *
     * 取 8,000 字符（约 2,000 token）：低于这个量级，摘要与原文已经同一数量级，收益可以忽略。
     */
    const val MIN_MANUAL_COMPACT_CHARS = 8_000L

    /**
     * 手动压缩可以跳过的理由；不跳过时返回 null。
     *
     * 抽成纯函数是为了让「什么时候不该花这次请求」有单测守着，而不是埋在会话状态机里。
     */
    fun manualCompactionSkipNote(historyChars: Long): String? {
        if (historyChars >= MIN_MANUAL_COMPACT_CHARS) return null
        val chars = if (historyChars <= 0L) "0" else {
            historyChars.toString().reversed().chunked(3).joinToString(",").reversed()
        }
        return "上下文只有 $chars 字符，已经足够小，无需压缩。"
    }

    /**
     * 压缩时应该**保留多少字符的近期原文**。
     *
     * 为什么需要它：压缩的切分点原先取「最大可安全切分点」——能压多少压多少，尾部几乎总是空的。
     * 真机一次自动压缩把 499,495 字符压到 4,923，只剩摘要 + 最新请求；
     * 模型因此不知道刚才看过什么、改过什么，下一轮又去重读同一批文件，省下的 token 原样花回去。
     * 文档里写的「摘要 + 最新用户请求 + 尾部」当时从未成立。
     *
     * 取「最多 1/5、最多 80,000 字符」，并留出 [MIN_COMPACT_GAIN_CHARS] 的收益空间：
     * 太小的历史保留尾部会把压缩本身变得没有意义，那就不保留。
     */
    const val RETAIN_TAIL_MAX_CHARS = 80_000L

    /** 低于这个历史体量时不保留尾部：压缩本来就没多少可省，留一截反而白跑一次摘要。 */
    const val MIN_COMPACT_GAIN_CHARS = 20_000L

    /**
     * 保留尾部最多占窗口的比例。
     *
     * 80,000 字符（约 20,000 token）在 200k 窗口里占 10%，没问题；但在 32k 窗口里占 62%
     * ——压缩完仍然是满的，下一轮立刻再触发一次压缩，白花一次摘要请求。
     * 所以保留量必须同时受窗口限制，而不只看历史比例。
     */
    const val RETAIN_TAIL_WINDOW_RATIO = 0.15

    /**
     * @param contextWindowTokens 模型窗口（token）；≤ 0 表示未知，此时不按窗口限制。
     */
    fun retainTailChars(historyChars: Long, contextWindowTokens: Int): Long {
        if (historyChars <= 0L) return 0L
        val byRatio = historyChars / 5
        val byWindow = if (contextWindowTokens <= 0) {
            Long.MAX_VALUE
        } else {
            (contextWindowTokens.toLong() * CHARS_PER_TOKEN_ESTIMATE * RETAIN_TAIL_WINDOW_RATIO).toLong()
        }
        val gainRoom = (historyChars - MIN_COMPACT_GAIN_CHARS).coerceAtLeast(0L)
        return minOf(byRatio, RETAIN_TAIL_MAX_CHARS, byWindow, gainRoom)
    }

    fun estimateTokens(chars: Long): Long = if (chars <= 0L) 0L else chars / CHARS_PER_TOKEN_ESTIMATE

    /**
     * 会话历史的字符数合计。
     *
     * 只统计会真正发给模型的内容；`_eta_observation` 之类的瞬时观察消息也算在内——
     * 它们在内存里同样占字符，序列化时一样要分配。
     */
    fun contextChars(messages: JSONArray, fromIndex: Int = 0): Long {
        var total = 0L
        for (index in fromIndex until messages.length()) {
            total += messageChars(messages.optJSONObject(index))
        }
        return total
    }

    fun messagesChars(messages: List<AgentModelClient.ConversationMessage>): Long {
        var total = 0L
        messages.forEach { total += messageCharsOf(it) }
        return total
    }

    /** 单条历史消息的字符数；与 [messageChars] 同样不做整份序列化。 */
    fun messageCharsOf(message: AgentModelClient.ConversationMessage): Long =
        message.content.length.toLong() +
            message.contentJson.length.toLong() +
            message.reasoningContent.length.toLong() +
            message.toolCallsJson.length.toLong()

    /**
     * 单条消息的字符数。
     *
     * 不能用 `optString("content")`：当 content 是 JSONArray（多模态）时它会把整个数组
     * 序列化成字符串，正是我们要避免的大分配。
     */
    fun messageChars(message: JSONObject?): Long {
        if (message == null) return 0L
        var total = 0L
        when (val content = message.opt("content")) {
            is String -> total += content.length
            is JSONArray -> {
                for (index in 0 until content.length()) {
                    val part = content.optJSONObject(index) ?: continue
                    // 文本块、图片 data URL、输入音频等都在这里，按字段累加即可。
                    total += part.optString("text").length
                    total += part.optString("image_url").length
                    total += part.optString("input_text").length
                    total += part.optString("data").length
                }
            }
            is JSONObject -> {
                total += content.optString("text").length
                total += content.optString("image_url").length
            }
        }
        total += message.optString("reasoning_content").length
        val calls = message.optJSONArray("tool_calls")
        if (calls != null) {
            for (index in 0 until calls.length()) {
                val function = calls.optJSONObject(index)?.optJSONObject("function") ?: continue
                total += function.optString("arguments").length
            }
        }
        return total
    }

    /**
     * 是否应当执行上下文压缩。
     *
     * 三条判据，满足任一条就压缩：
     * 1. [force]：用户或调用方明确要求；
     * 2. 字符数超过**该窗口对应的硬上限**（[hardLimitFor]）：不依赖远端 usage；
     * 3. token 数达到**生效阈值**，即 `min(窗口 × [triggerRatio], [ABSOLUTE_COMPACT_TOKENS])`。
     *
     * 第 2 条针对真机实测到的一类端点：请求里带了 `stream_options.include_usage`，
     * 端点却全程不回 usage，于是第 3 条永远不成立，历史一直涨到序列化时 OOM
     * （canary 库实测 1,143 条消息里 1,140 条没有 input_tokens，历史 2.79M 字符）。
     *
     * ## 为什么第 3 条是 min 而不是单纯的比例
     *
     * 两个真实事故方向相反，只能取更早的那个：
     * - 比例失效（fix145）：用户把 `contextWindow` 填得很大（真机实测 23.9 万 token、
     *   1533 条消息），`tokens >= window * 0.85` 永不成立，一次 compress 都没触发，
     *   而每多一轮都在为这几十万 token 付费。
     * - 比例过早（本轮的旧用例）：若信任窗口，900K 窗口的模型要到 76.5 万 token 才压。
     *
     * 真机证据胜过假设场景：无法判断“用户填的大窗口是真的还是夸张”，所以保留 10 万兜底。
     * 代价是窗口大于 `ABSOLUTE_COMPACT_TOKENS / [triggerRatio]`（临界点 117,648，不是
     * 117,647：后者 × 0.85 = 99,999.95，仍在比例侧）时，实际触发点固定在 10 万
     * （例如 256K 窗口在 39% 就压）。这是**有意选择**，不是副作用：
     * 早压的代价是一次摘要请求，晚压的代价是每轮为几十万 token 付费。
     *
     * 窗口未知时见 [effectiveTriggerTokens] 对 [DEFAULT_ASSUMED_CONTEXT_WINDOW] 的说明：
     * 当前取值下假设值不改变结果，真实阈值是 10 万。
     * 安全阀只在“窗口未知”时越过 [autoCompactionEnabled]：那时用户关掉自动压缩就再没有任何
     * 依据能拦住增长，而窗口已知时仍然尊重用户的开关。
     *
     * 不接 `triggerRatio` 参数：生产只有 [AgentContextSession] 一个调用点、永远传
     * [TRIGGER_RATIO]。以前它开在公开签名上只为让测试绕过绝对上限，那是给生产函数
     * 开测试后门。需要验比例与上限的边界时直接测 [effectiveTriggerTokens]。
     */
    fun shouldCompact(
        force: Boolean,
        autoCompactionEnabled: Boolean,
        contextWindow: Int?,
        observedInputTokens: Int?,
        historyChars: Long,
    ): Boolean {
        if (force) return true
        val window = contextWindow?.takeIf { it > 0 }
        if (historyChars > hardLimitFor(window)) {
            return autoCompactionEnabled || window == null
        }
        if (!autoCompactionEnabled) return false
        val tokens = observedInputTokens?.takeIf { it >= 0 }
            ?: estimateTokens(historyChars).toInt()
        return tokens >= effectiveTriggerTokens(window, TRIGGER_RATIO)
    }

    /**
     * 生效触发阈值（token）：`min(窗口 × triggerRatio, [ABSOLUTE_COMPACT_TOKENS])`。
     *
     * 抽成纯函数，是为了让“比例与绝对上限谁先到”有单点可测：拿到 [contextWindow]
     * 就能算出真实阈值，不必靠给 [shouldCompact] 加测试专用参数来验证分支。
     *
     * ## [DEFAULT_ASSUMED_CONTEXT_WINDOW] 目前的真实地位
     *
     * 它**是活的**：`min` 的两侧都会随它变。只是 128,000 × 0.85 = 108,800 **高于**
     * 10 万，所以窗口未知时 `min` 取到绝对上限，这个假设值当前影响不了结果。
     * 换句话说，它现在的唯一作用是“窗口未知时按 128K 估”这一意图的载体，以及一个
     * 安全阀：**把 [ABSOLUTE_COMPACT_TOKENS] 将来调高到 108,800 以上，它立刻开始生效；
     * 调低 [DEFAULT_ASSUMED_CONTEXT_WINDOW] 到 117,647 以下，它也立刻生效**（后者会让
     * 未知窗口比现在更早压缩，属于收紧而非放宽）。两条都在
     * `AgentContextBudgetTest.effectiveTriggerIsMinOfWindowRatioAndAbsoluteLimit` 里钉住。
     *
     * 不删它的理由：删了就得在 [shouldCompact] 里写 `window ?: 128_000`，把这个决定
     * 藏进表达式，而它在 [AgentMemoryContextBuilder] 里还有一份同值的独立定义。
     */
    internal fun effectiveTriggerTokens(contextWindow: Int?, triggerRatio: Double): Double {
        val assumed = contextWindow?.takeIf { it > 0 } ?: DEFAULT_ASSUMED_CONTEXT_WINDOW
        return minOf(assumed * triggerRatio, ABSOLUTE_COMPACT_TOKENS.toDouble())
    }

    /**
     * 该窗口对应的字符硬上限。
     *
     * 窗口已知时按 [CHARS_PER_TOKEN_FLOOR] 折算并取下限 [HARD_CONTEXT_CHARS]：
     * 900K token 的模型对应 1.8M 字符，不能用对数个小窗口安全的 1.2M 去提前打断它。
     */
    fun hardLimitFor(contextWindow: Int?): Long {
        val fromWindow = (contextWindow?.toLong() ?: 0L).coerceAtLeast(0L) * CHARS_PER_TOKEN_FLOOR
        return maxOf(HARD_CONTEXT_CHARS, fromWindow)
    }
}
