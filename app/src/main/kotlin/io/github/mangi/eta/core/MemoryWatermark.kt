package io.github.mangi.eta.core

/**
 * 内存水位的格式化与判定。
 *
 * 为什么要单独抽出来：OOM 崩溃日志里只有「分配失败」那一刻的堆上限，
 * 看不出之前是怎么涨上去的。系统每次内存紧张都会回调 [android.app.Application.onTrimMemory]，
 * 那里是**唯一能在崩溃前留下堆水位的地方**。把格式化和阈值判定放在这里，
 * 就能在 JVM 单测里固定住，不用起 Android 进程。
 */
internal object MemoryWatermark {

    /** 采样点：只会由系统内存回调触发，不在热路径上。 */
    enum class Trigger(val label: String) {
        RUNNING_LOW("running-low"),
        COMPLETE("complete"),
        LOW_MEMORY("low-memory"),
        ;

        companion object {
            // 用字面量而不是 ComponentCallbacks2.* 常量：那是 android.jar 里的字段，
            // 纯 JVM 单测直接引用会抛 "Stub!"（未 mocking 的 android.jar 字段）。
            // 三个值都是 AOSP 固定值，不会变（见 frameworks/base ComponentCallbacks2.java）：
            //   TRIM_MEMORY_COMPLETE         = 80
            //   TRIM_MEMORY_RUNNING_CRITICAL = 15
            //   TRIM_MEMORY_RUNNING_LOW      = 10
            private const val LEVEL_COMPLETE = 80
            private const val LEVEL_RUNNING_CRITICAL = 15
            private const val LEVEL_RUNNING_LOW = 10

            /**
             * onTrimMemory 的 level 落到哪个触发器。
             *
             * 只采两个前台运行态等级：COMPLETE(80) 与 RUNNING_LOW(10)。
             * 不能写成单纯的 ``level >= 10''——后台等级 MODERATE(60)、BACKGROUND(40)、
             * UI_HIDDEN(20) 都比 10 大，会把它们误判成 RUNNING_LOW。
             * 那三个等级意味着进程已不可见（或即将不可见），此时采到的水位与
             * 用户实际遇到的内存压力无关，只会往 boot.log 里塞噪声。
             *
             * RUNNING_CRITICAL(15) 归到 RUNNING_LOW：它也是前台压力，只是更重。
             */
            fun fromTrimLevel(level: Int): Trigger? = when (level) {
                LEVEL_COMPLETE -> COMPLETE
                LEVEL_RUNNING_LOW, LEVEL_RUNNING_CRITICAL -> RUNNING_LOW
                // 其余（5/20/40/60 以及未来新增的中间值）都不采。
                else -> null
            }
        }
    }

    /**
     * 一行水位记录。
     *
     * 单位统一用 MB、保留一位小数：boot.log 是给人看的，精确到字节反而更难扫。
     * [usedMb] 取 totalMemory - freeMemory（当前已分配且在用），
     * 而不是 maxMemory - freeMemory（后者会把尚未申请的余量算成已用，虚高）。
     */
    fun format(
        trigger: Trigger,
        usedBytes: Long,
        maxBytes: Long,
    ): String {
        val usedMb = usedBytes / 1024.0 / 1024.0
        val maxMb = maxBytes / 1024.0 / 1024.0
        val percent = if (maxBytes > 0L) usedBytes * 100.0 / maxBytes else 0.0
        return "内存水位[${trigger.label}] 已用 %.1fMB / 上限 %.0fMB (%.0f%%)".format(
            usedMb,
            maxMb,
            percent,
        )
    }

    /**
     * 是否值得记这次采样。
     *
     * 同一个级别在短时间内反复触发时（系统会在压力期间连发多次），只保留第一
     * 次，否则 boot.log 会被同一行淹没。返回 true 表示这次应该写。
     *
     * @param lastAtMs 上一次同级别记录的时间戳（本进程内），没有则传 null
     * @param nowMs 当前时间
     */
    fun shouldRecord(
        trigger: Trigger,
        lastAtMs: Long?,
        nowMs: Long,
        windowsMs: Long = DEFAULT_WINDOW_MS,
    ): Boolean {
        if (lastAtMs == null) return true
        // 时钟回拨（NTP 校正、时区无关的时间跳变）时 lastAtMs 可能比 now 还大，
        // 此时差值为负，会让「窗口内」恒成立从而永久静音——所以显式放行。
        val elapsed = nowMs - lastAtMs
        if (elapsed < 0L) return true
        return elapsed >= windowsMs
    }

    /** 同级别两个采样点之间至少隔这么久。太密对定位没有帮助，只会挤掉别的日志。 */
    const val DEFAULT_WINDOW_MS: Long = 60_000L
}
