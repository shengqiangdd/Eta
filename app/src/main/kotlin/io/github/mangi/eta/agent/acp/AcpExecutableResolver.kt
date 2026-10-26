package io.github.mangi.eta.agent.acp

/**
 * 在 **Linux 工具环境内**探测 ACP 代理的可执行文件。
 *
 * 为什么必须进环境探测：`/opt/eta/node/...` 是 guest 视角的路径，宿主上看不到。
 * 原实现用 `File(spec.executable).canExecute()` 在 App 进程里判断，结论恒为 false，
 * `acp_agent_list` 永远显示「未安装」——不是没装，是问错了地方。
 *
 * 另一个问题是路径写死。内置代理的可执行文件写成 `/opt/eta/node/26.8.1/bin/codex-acp`，
 * 一旦 Linux 环境里的 node 换了版本（升级/重装），这个路径就不存在了。
 * 所以候选按三层给：显式路径 → 通配展开（多版本目录）→ PATH 里的命令名。
 *
 * 这里只生成**候选清单与探测脚本**（纯字符串，可单测）；真正执行由 [AcpClient] 负责。
 */
internal object AcpExecutableResolver {

    /** 路径来源，用于 `acp_agent_list` 里说明「是怎么找到的」。 */
    enum class Source { EXPLICIT, GLOB, PATH, MISSING }

    data class Plan(
        val explicit: List<String>,
        val commands: List<String>,
        val globs: List<String>,
    ) {
        /** 按单次 `command -v` 顺序检查的精确候选：显式路径优先于 PATH 命令名。 */
        val exact: List<String> get() = explicit + commands

        val isEmpty: Boolean get() = exact.isEmpty() && globs.isEmpty()
    }

    data class Resolution(val path: String, val source: Source)

    fun plan(
        explicit: List<String>,
        globs: List<String>,
        commands: List<String>,
    ): Plan = Plan(
        explicit = explicit.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        commands = commands.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        globs = globs.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
    )

    /**
     * 探测脚本：按 plan 顺序输出**第一个**可执行路径，找不到就什么都不输出。
     *
     * 通配段故意不加引号：要让 shell 自己展开版本目录（`/opt/eta/node/<ver>/...`）。
     * 这些通配串由代码或插件 manifest 给出，不含空格与元字符干扰；
     * 精确候选一律用单引号包住。
     */
    fun probeScript(plan: Plan): String = buildString {
        if (plan.exact.isNotEmpty()) {
            append("for eta_p in ")
            plan.exact.forEach { append(shellQuote(it)).append(' ') }
            append("; do ")
            append("if [ -n \"${'$'}eta_p\" ] && command -v \"${'$'}eta_p\" >/dev/null 2>&1; ")
            append("then printf '%s' \"${'$'}eta_p\"; exit 0; fi; done; ")
        }
        plan.globs.forEach { glob ->
            append("for eta_p in ").append(glob).append("; do ")
            append("if [ -x \"${'$'}eta_p\" ]; then printf '%s' \"${'$'}eta_p\"; exit 0; fi; done; ")
        }
        append("exit 0")
    }

    /** 把探测到的路径归类，供结果里显示来源。 */
    fun classify(plan: Plan, path: String): Source = when {
        path.isBlank() -> Source.MISSING
        path in plan.explicit -> Source.EXPLICIT
        path in plan.commands -> Source.PATH
        else -> Source.GLOB
    }

    fun resolution(plan: Plan, probed: String): Resolution {
        val path = probed.trim()
        return Resolution(path = path, source = classify(plan, path))
    }

    /** 与 ACP 启动脚本同一套引号规则：单引号包裹，内部单引号转义。 */
    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
