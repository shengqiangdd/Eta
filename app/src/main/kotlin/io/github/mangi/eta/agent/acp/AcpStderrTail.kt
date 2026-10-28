package io.github.mangi.eta.agent.acp

/**
 * 委派结果里 `stderr_tail` 的裁剪规则。
 *
 * 为什么要单独一层：`teardown()` 用 `destroyForcibly()` 收整棵进程树，ash / busybox 会把
 * `Terminated` 写进子进程的 stderr；而结果 JSON 是在 teardown **之后**构造的，于是
 * **每次成功的委派都带着** `stderr_tail: Terminated`（真机 2026-10-28 每次都复现），
 * 用户看到的第一反应就是「是不是出错了」。
 *
 * 只在「正常结束」时过滤这类收尾噪声；失败路径原样保留 —— 那时候每一行都可能是线索。
 * 抽成对象而不是留在 [AcpClient] 里，是为了能用普通单测守住（不需要设备）。
 */
internal object AcpStderrTail {

    /** 收进程树时由 shell 写进 stderr 的整行内容。 */
    private val TERMINATION_NOISE = setOf(
        "Terminated",
        "Killed",
        "Terminated (core dumped)",
    )

    fun tail(raw: String, finishedOk: Boolean, maxChars: Int): String {
        val text = if (finishedOk) {
            raw.lineSequence()
                .filterNot { line -> line.trim() in TERMINATION_NOISE }
                .joinToString("\n")
        } else {
            raw
        }
        return text.trim().takeLast(maxChars.coerceAtLeast(0))
    }
}
