package io.github.mangi.eta.agent.device

/**
 * 「写完读回」的等待策略（fix185）。
 *
 * 为什么把它抽出来：写入动作返回只代表动作被受理，界面要过一小会儿才把文本状态提交出来。
 * 这段「等多久、隔多久再看、主线程上要不要等」原先写死在无障碍服务里（300ms / 40ms），
 * 只有读代码能看，没有任何用例守着 —— 而它一旦被改错（等太久 → 每次输入都卡；
 * 等太短 → 写成功了却报未确认），真机上很难定位。
 *
 * 现在用一个可注入时钟的实现表达同一套语义，单测用虚拟时间覆盖：
 * - 第一次就读到 → 立刻返回，不白等；
 * - 一直读不到 → 最多等 [timeoutMs]，不会无限重试；
 * - **主线程只试一次**：Compose 的提交也在主线程排队，在主线程上阻塞等，
 *   等多久都读不到新值，只会把界面卡住。
 */
internal class ReadbackWindow(
    private val timeoutMs: Long,
    private val intervalMs: Long,
    private val clock: () -> Long,
    private val sleeper: (Long) -> Unit,
) {
    init {
        require(timeoutMs >= 0L) { "timeoutMs 不能为负" }
        require(intervalMs > 0L) { "intervalMs 必须为正" }
    }

    fun await(isMainThread: Boolean, attempt: () -> Boolean): Boolean {
        if (attempt()) return true
        if (isMainThread) return false
        val deadline = clock() + timeoutMs
        while (true) {
            val remaining = deadline - clock()
            if (remaining <= 0L) return false
            // 最后一段只睡剩余时间：否则最后一次检查会越过截止时间，白等一个间隔。
            sleeper(minOf(intervalMs, remaining))
            if (attempt()) return true
        }
    }
}

/**
 * 无障碍服务里几处超时的**唯一**来源（fix185）。
 *
 * 抽成纯 Kotlin 对象的原因有两条：
 * 1. 单测能守住「观察窗口必须长于主线程等待」这个不变量 —— 它一旦反了，
 *    滚动观察总在拿到信号前过期，表现为「滚动成功了却报没滚动」；
 * 2. 这些数字原先散在 Service 的 companion 里（private），只能靠读代码确认。
 */
internal object AgentAccessibilityTimeouts {
    /** 等无障碍主线程执行完一段准备工作的上限。 */
    const val MAIN_SYNC_SECONDS = 3L

    /** 写入后等界面把文本提交出来的窗口：够 Compose 提交一次，又不至于让失败场景白等。 */
    const val TEXT_READBACK_MS = 300L

    /** 读回轮询间隔。 */
    const val TEXT_READBACK_INTERVAL_MS = 40L

    /** 滚动手势本身的时长。 */
    const val SCROLL_GESTURE_MS = 300L

    /** 等手势回调的宽限时间。 */
    const val GESTURE_CALLBACK_GRACE_MS = 1_500L

    /** 滚动后确认位置变化的窗口。 */
    const val SCROLL_VERIFY_MS = 520L

    /** 滚动事件观察的有效期：必须覆盖「主线程等待 + 手势 + 回调 + 确认」整段。 */
    const val SCROLL_EVENT_OBSERVATION_MS =
        MAIN_SYNC_SECONDS * 1_000L + SCROLL_GESTURE_MS + GESTURE_CALLBACK_GRACE_MS + SCROLL_VERIFY_MS
}

/**
 * 文本写入在主线程准备阶段失败的两种情形（fix185）。
 *
 * 两者都**没有写过文本**（准备阶段只读不写），所以都可以安全重试；
 * 但原因不同：一种连「找节点」都没开始，另一种是主线程里抛了错。
 * 合并成一句「主线程无响应」，现场就丢了「为什么没写成」。
 *
 * 文案放在这里而不是留在 Service 里，是为了让「两句话必须不同」这条要求有测试守着。
 */
internal object TextWriteFailures {
    const val CODE = "SERVICE_TIMEOUT"

    fun notStartedMessage(): String =
        "无障碍服务主线程无响应，动作未执行；请重新观察后重试"

    fun outcomeUnknownMessage(): String =
        "无障碍层在准备写入时出错或超时，文本未写入；请重新观察后重试"
}
