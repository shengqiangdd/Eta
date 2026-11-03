package io.github.mangi.eta.agent.runtime

/** 用户任务的运行引用；通知停止只消费这里登记的任务，不接管 Root daemon。 */
internal class ExecutionLeaseRegistry {
    /**
     * [agentRunId] 只有 Agent run 才非空，且是不带 `run:` 前缀的裸 runId。
     *
     * 租约同时覆盖终端会话、后台命令和 Linux 启动（id 形如 `terminal-background-commands`）。
     * 通知栏的暂停/补充只能发给真正的 Agent run：用错 id 时 Runtime 只会回一个“没这个 run”，
     * 看起来像“点了没反应”。所以在这里就按类型区分，而不是在通知层猜前缀。
     */
    private data class Lease(
        val owner: Long?,
        val allowBoundFallback: Boolean,
        val agentRunId: String?,
        val onStop: () -> Unit,
    )
    private val leases = linkedMapOf<String, Lease>()
    private var activeOwner: Long? = null

    @Synchronized fun acquire(
        id: String,
        allowBoundFallback: Boolean = false,
        agentRunId: String? = null,
        onStop: () -> Unit,
    ): Boolean {
        require(id.isNotBlank())
        if (id in leases) return false
        leases[id] = Lease(activeOwner, allowBoundFallback, agentRunId, onStop)
        return true
    }

    @Synchronized fun release(id: String) { leases.remove(id) }
    @Synchronized fun count(): Int = leases.size

    /**
     * 当前 Agent run 的裸 runId，按启动顺序（老 → 新）。
     *
     * 通知栏的「暂停/继续」必须作用在真实存在的 run 上：这里只暴露 id，
     * 不把执行器或回调交给通知层，避免通知能绕过 Runtime 直接终止任务。
     */
    @Synchronized fun agentRunIds(): List<String> = leases.values.mapNotNull { it.agentRunId }

    /**
     * 最近启动的 Agent run id；没有 Agent run 时返回 null（终端会话不算）。
     *
     * 「补充指令」只发给最新一条：同时跑多个会话时，用户刚发起的那个才是他正在看的；
     * 广播给全部 run 会把一句补充塞进无关任务。
     */
    @Synchronized fun latestAgentRunId(): String? = leases.values.lastOrNull { it.agentRunId != null }?.agentRunId

    @Synchronized fun attachOwner(owner: Long) {
        activeOwner = owner
        leases.replaceAll { _, lease -> if (lease.owner == null) lease.copy(owner = owner) else lease }
    }

    @Synchronized fun closeOwnerIfIdle(owner: Long): Boolean {
        if (leases.isNotEmpty()) return false
        if (activeOwner == owner) activeOwner = null
        return true
    }

    /** 旧服务销毁时，不能取消在其 stopSelf 之后为下一次启动登记的任务。 */
    @Synchronized fun drainOwner(owner: Long): List<() -> Unit> {
        if (activeOwner == owner) activeOwner = null
        val owned = leases.filterValues { it.owner == owner }
        owned.keys.forEach(leases::remove)
        return owned.values.map { it.onStop }
    }

    @Synchronized fun drain(startFailed: Boolean = false): List<() -> Unit> = leases.values
        .filterNot { startFailed && it.allowBoundFallback }
        .map { it.onStop }
        .also { leases.clear() }
}
