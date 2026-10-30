package io.github.mangi.eta.agent.model

/**
 * 发送按钮的重复提交保护。
 *
 * 输入栏不再在点击时抢先清空文字（那会把「被拒的提交」变成当场吞字，见
 * `AgentChatInputBar` 主按钮），清空改由真正消费掉草稿的一侧发出信号。代价是：
 * 点了发送到输入框真正清空之间有一帧，这一帧里按钮看起来还是「可发送」——
 * 手快点两下就会把同一段文字发两遍（排队时会排两条）。
 *
 * 所以按「相同文本 + 很短的时间窗」把第二次点击判成误触。不同的文本从不拦，
 * 超过窗口也不拦：用户看到提示后重试必须立刻能发出去。
 */
internal object AgentSubmitDedupPolicy {

    /** 默认去重窗口：远小于任何「看清楚提示再重试」的间隔，又覆盖得了一帧到几帧内的连点。 */
    const val DEFAULT_WINDOW_MS: Long = 400L

    /**
     * 这次点击是不是上一次提交的重复。
     *
     * @param previousText 上一次真的交给发送路径的文本；null 表示还没有提交过。
     * @param previousAtMs 上一次提交的时刻（同一时钟，毫秒）。
     * @param text 这次要提交的文本。
     */
    fun isDuplicate(
        previousText: String?,
        previousAtMs: Long,
        text: String,
        nowMs: Long,
        windowMs: Long = DEFAULT_WINDOW_MS,
    ): Boolean {
        if (previousText == null) return false
        if (previousText != text) return false
        // 时钟回拨（用户改时间、NTP 校正）时 nowMs - previousAtMs 会是负数：
        // 那是「更早的一次」，不能当成连点拦下来。
        val elapsed = nowMs - previousAtMs
        return elapsed in 0..windowMs
    }
}
