package io.github.mangi.eta.agent.terminal

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 守护任务的定期探活。
 *
 * 命令任务（`terminal_job`）结束时由完成线程写 `completed_at`，事件、卡片、自动续跑立刻能看到；
 * 守护任务（`terminal_daemon`）**没有完成线程**，进程什么时候没了只有巡检知道。原来只有
 * `terminal_daemon list`（模型主动查）才会巡检，于是记录会一直停在「运行中」：
 * 卡片永远转圈、`<background_tasks>` 一直说它还在、自动续跑永远不会被触发。
 *
 * 这里每 [INTERVAL_MS] 扫一次，**只在存在未结束的守护任务时才真的起 shell**
 * （没有任务时只读一次 JSON，不开进程）。
 */
internal object DetachedTaskProbe {

    /** 探活间隔。守护任务是长生命周期，5 分钟粒度足够，也不会给设备加持续的 fork 负担。 */
    const val INTERVAL_MS = 5 * 60_000L

    /**
     * 一次探活。
     *
     * @return 是否真的执行了巡检（没有未结束的守护任务时为 false，不会起 shell）。
     */
    suspend fun probeOnce(context: Context): Boolean {
        val app = context.applicationContext
        val records = withContext(Dispatchers.IO) {
            DetachedTaskSupervisor.readRecords(DetachedTaskSupervisor.defaultRecordsFile(app))
        }
        if (!needsDaemonProbe(records)) return false
        val pending = records.count { it.completedAt == null && !it.commandTask }
        val probed = withContext(Dispatchers.IO) {
            runCatching { TerminalRuntime.backgroundJobs(app).probe() }.getOrDefault(-1)
        }
        // 只在真的巡检时打一条，两个作用：① 真机上能确认「这一轮起了 shell」；
        // ② 反过来也能证明「没有待巡检的守护任务时它没开进程」—— 日志不出现就是证据。
        // debug 构建才留（Release 会删掉整次调用），不会给发布版加日志噪音。
        AndroidAgentLogger.debug { "Agent detached task probe: pending=$pending records=$probed" }
        return true
    }
}

/**
 * 记录里还有没结束的守护任务吗？—— 决定要不要为探活起 shell。
 *
 * 命令任务不需要：它的完成线程会自己写 `completed_at`。
 * 抽成纯函数是为了能本机测（[DetachedTaskProbe.probeOnce] 要 Context 和真实 shell）。
 */
internal fun needsDaemonProbe(records: List<DetachedTask>): Boolean =
    records.any { it.completedAt == null && !it.commandTask }
