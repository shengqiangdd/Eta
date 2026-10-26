package io.github.mangi.eta.agent.device

/**
 * 虚拟屏的准入与回收规则（纯逻辑，可单测）。
 *
 * 为什么需要它：`VirtualDisplayController.create` 原本没有任何上限。每块虚拟屏挂着一个
 * `ImageReader`，缓冲是 `width × height × 4 字节 × 2 帧`——1080×1920 就是 **16.6 MB**。
 * 也就是说「多建几块」不是零成本的，建到第 10 块时已经吃掉 160 MB 图形内存，
 * 在手机上足够把整个进程拖垮。工具描述里那句「任务结束必须释放」只是模型的自觉，
 * 不是机制；模型忘了、或中途崩溃，屏幕就一直留在系统里。
 *
 * 这里把两件事做成确定性的：
 * 1. **准入**：会话数与估算缓冲都有上限，超了直接拒绝并给出可执行的下一步；
 * 2. **回收**：超过空闲时长的会话由 `sweep` 挑出来，由控制器释放。
 *
 * 空闲判定用「最后一次使用」而不是创建时间：一直有人在操作的虚拟屏不该被收走。
 */
internal object VirtualDisplayBudget {

    /** 同时存活的虚拟屏上限。4 块足够「并行试用」，也是 1080×1920 下约 66 MB 的缓冲。 */
    const val MAX_SESSIONS = 4

    /** 所有会话的估算缓冲上限。 */
    const val MAX_TOTAL_BYTES = 96L * 1024 * 1024

    /** 空闲多久回收：10 分钟没有 list/launch/截图/坐标动作就算闲置。 */
    const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L

    /** ImageReader 默认双缓冲。 */
    private const val BUFFERS_PER_DISPLAY = 2

    /** 估算一块虚拟屏占用的图形内存。 */
    fun bufferBytes(width: Int, height: Int): Long =
        width.coerceAtLeast(1).toLong() * height.coerceAtLeast(1) * 4L * BUFFERS_PER_DISPLAY

    /** 一个已存在会话的准入视图：只需要尺寸与最后使用时间，不碰 DisplayManager。 */
    data class Existing(
        val id: String,
        val width: Int,
        val height: Int,
        val lastUsedAtMs: Long,
    )

    sealed interface Admission {
        data object Allowed : Admission

        /** 被拒绝：`code` 进工具错误码，`message` 直接给模型看（要写清怎么办）。 */
        data class Rejected(val code: String, val message: String) : Admission
    }

    fun usedBytes(existing: List<Existing>): Long =
        existing.sumOf { bufferBytes(it.width, it.height) }

    /**
     * 判断还能不能再建一块。
     *
     * 两种拒绝分别给不同错误码：数量超限、缓冲超限。分开才能让模型知道该「释放一块」
     * 还是「用更小的尺寸」。
     */
    fun admit(
        existing: List<Existing>,
        width: Int,
        height: Int,
        maxSessions: Int = MAX_SESSIONS,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
    ): Admission {
        if (existing.size >= maxSessions) {
            return Admission.Rejected(
                code = "VIRTUAL_DISPLAY_LIMIT",
                message = "虚拟屏已达上限 $maxSessions 块（当前 ${existing.size} 块）。" +
                    "请先 virtual_display_release 释放不用的（id=all 释放全部），或复用已有的一块。",
            )
        }
        val used = usedBytes(existing)
        val wanted = bufferBytes(width, height)
        if (used + wanted > maxTotalBytes) {
            return Admission.Rejected(
                code = "VIRTUAL_DISPLAY_MEMORY",
                message = "估算缓冲不足：已有 ${
                    used / (1024 * 1024)
                } MB，再建一块 ${width}x$height 需要 ${wanted / (1024 * 1024)} MB，" +
                    "上限 ${maxTotalBytes / (1024 * 1024)} MB。请先释放，或改用更小的尺寸（例如 720x1280）。",
            )
        }
        return Admission.Allowed
    }

    /**
     * 挑出该回收的空闲会话。
     *
     * `nowMs - lastUsedAtMs > idleTimeoutMs` 才算空闲：刚好等于阈值不收，
     * 避免「倒数到 0 的那一毫秒」把正在用的屏收走。
     */
    fun sweep(existing: List<Existing>, nowMs: Long, idleTimeoutMs: Long = IDLE_TIMEOUT_MS): List<String> =
        existing.filter { nowMs - it.lastUsedAtMs > idleTimeoutMs }.map { it.id }
}
