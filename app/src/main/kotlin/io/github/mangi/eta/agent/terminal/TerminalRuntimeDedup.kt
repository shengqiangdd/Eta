package io.github.mangi.eta.agent.terminal

import org.json.JSONObject

/**
 * `terminal` 结果的 `runtime` 块去重。
 *
 * 真机 trace：一条 `printf ok` 的输出是 **2 个字符**，却带回 **384 字符** 的 runtime
 * （`host_identity`/`uid`/`uid_scope`/`shell_provider`/`shell_executable`/
 * `available_commands`/`capability_scope`）。这些东西在同一次会话里**每次调用都完全相同**，
 * 一场几十轮的会话下来是几万 Token 的纯重复。
 *
 * 抽成纯对象是为了能直接单测：`TerminalSession` 需要真实进程，构造太重。
 */
internal object TerminalRuntimeDedup {
    /**
     * @param previousSignature 上次下发时的指纹；null 表示还没下发过。
     * @param current 本轮的 runtime 块。
     * @return 第一项是新的指纹（调用方保存），第二项是**本次要下发的块**；
     *         为 null 表示「和上次一模一样」，调用方写 `runtime: null` 即可。
     */
    fun diff(previousSignature: String?, current: JSONObject): Pair<String, JSONObject?> {
        val signature = current.toString()
        return if (signature == previousSignature) signature to null else signature to current
    }
}
