package io.github.mangi.eta.agent.terminal

import android.content.Context
import io.github.mangi.eta.agent.runtime.BackgroundResumeStore
import io.github.mangi.eta.config.Prefs
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 「后台任务结束后自动续跑」的**进程级**触发点，也是唯一自动触发点。
 *
 * 为什么需要它：原来的自动续跑写在卡片组件里（`LaunchedEffect`），只有会话界面正在组合、
 * 且卡片那一帧恰好读到新记录时才会触发。用户切到别的会话、切到桌面、或界面被系统回收之后，
 * 任务结束就只是磁盘上多了一个 `completed_at` —— 没有任何人发起下一轮。
 *
 * 第十二批起卡片里的那份自动续跑**删掉了**：同一件事有两个触发点，就是两套判定、
 * 两处状态，真机上已经因为「只修一处」漏过一次（§32.58）。卡片只保留手动「继续」。
 *
 * 事件不再丢：水位线（`seen_until`）与链长都落盘。旧实现拿「进程启动时刻」当水位线，
 * 进程在任务刚结束、还没扫描时被杀，这条任务重启后就永远不会被续 —— 它比新的启动时刻早。
 *
 * 边界（文档同步）：
 * - 只在 App 进程存活时工作；进程被系统杀掉后不再触发，回来时靠下一次记录写入或 30 秒兜底。
 * - 记录本身没有探活：命令任务结束时由完成线程写盘，能立刻看到；守护任务（`terminal_daemon`）
 *   退出靠 `DetachedTaskProbe` 的 5 分钟探活写回（第十一批），因此最坏延迟约 5 分钟。
 * - 分发器没注册（没有会话状态在内存里）时什么也不做，不会凭空发消息。
 */
internal object BackgroundAutoResumeWatcher {

    /** 兜底扫描间隔。记录变化会立即触发，这里只是「万一信号丢了」的保险。 */
    const val FALLBACK_INTERVAL_MS = 30_000L

    private val started = AtomicBoolean(false)
    private val running = Mutex()

    /** 由会话状态层注册；null 表示当前没有会话状态在内存里。 */
    @Volatile
    private var dispatcher: BackgroundResumeDispatcher? = null

    fun interface BackgroundResumeDispatcher {
        /**
         * 发起一轮续跑。
         *
         * 返回 true 表示**真的发出去了**（调用方据此记「已续」）；
         * false 表示这一刻不能发（会话正在跑、会话不存在、内容为空），下个 tick 再试。
         */
        suspend fun dispatch(scopeId: String, task: DetachedTask): Boolean
    }

    fun install(dispatcher: BackgroundResumeDispatcher) {
        this.dispatcher = dispatcher
    }

    /** 只注销自己：新会话已经注册了新的分发器时，旧实例的注销不能把它清掉。 */
    fun uninstall(dispatcher: BackgroundResumeDispatcher) {
        if (this.dispatcher === dispatcher) this.dispatcher = null
    }

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            DetachedTaskSupervisor.changes.collect { tick(appContext) }
        }
        scope.launch {
            while (true) {
                delay(FALLBACK_INTERVAL_MS)
                tick(appContext)
            }
        }
    }

    private suspend fun tick(context: Context) {
        val current = dispatcher ?: return
        if (!autoResumeEnabled(context)) return
        val records = withContext(Dispatchers.IO) {
            DetachedTaskSupervisor.readRecords(DetachedTaskSupervisor.defaultRecordsFile(context))
        }
        if (records.isEmpty()) return
        running.withLock {
            runOnce(
                records = records,
                scopeFor = { scopeId ->
                    BackgroundResumeMemory.scopeFor(BackgroundResumeStore.forApp(context), scopeId)
                },
                dispatch = { scopeId, task -> current.dispatch(scopeId, task) },
            )
        }
    }

    /**
     * 一次扫描：对每个「有归属的会话」做一次 [planAutomationTick] 并执行它。
     *
     * 返回真正发出的条数（测试与日志用）。抽成独立函数是为了能本机测：
     * 不依赖 Android、不依赖协程调度器。
     */
    internal suspend fun runOnce(
        records: List<DetachedTask>,
        scopeFor: (String) -> BackgroundResumeScope,
        dispatch: suspend (String, DetachedTask) -> Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): Int {
        if (records.isEmpty()) return 0
        // 运行中的任务也要参与：作用域第一次出现时，水位线要立在「现在」，
        // 这样随后结束的那条才会严格晚于水位线。只筛「已完成」会把首次初始化拖到任务结束之后。
        val owned = records.filter { it.sessionScope != null }
        if (owned.isEmpty()) return 0
        var sent = 0
        // 每个会话各自判定、各自预算：A 会话续过不该消耗 B 会话的次数。
        for ((scopeId, tasks) in owned.groupBy { it.sessionScope.orEmpty() }) {
            if (scopeId.isBlank()) continue
            val scope = scopeFor(scopeId)
            // 磁盘记忆没读完之前不能判「没续过」，否则重启后会把同一批任务再续一遍。
            if (!scope.isLoaded) scope.loadFromStore()
            if (!scope.isLoaded) continue
            when (
                val plan = planAutomationTick(
                    records = tasks,
                    scopeId = scopeId,
                    handled = scope.resumed.toSet(),
                    seenUntil = scope.seenUntil,
                    chain = scope.autoResumed,
                    nowMs = nowMs,
                )
            ) {
                is AutomationTick.Idle -> Unit
                is AutomationTick.Initialize -> {
                    // 第一次见到这个作用域：只立水位线，不补发历史任务。
                    scope.markSeenUntil(plan.at)
                    scope.persist()
                }
                is AutomationTick.Discard -> {
                    // 链满了：明确放弃这一批，水位线推到它们最晚的结束时刻，
                    // 否则每 30 秒都会把同一批候选重新算一遍。
                    scope.markSeenUntil(plan.through)
                    scope.persist()
                }
                is AutomationTick.Dispatch -> {
                    // 先发再记账：发不出去（会话在跑）就不该占掉这次预算，也不该推进水位线。
                    if (!dispatch(scopeId, plan.task)) continue
                    scope.markAutoResumed(plan.task.id)
                    scope.markSeenUntil(plan.task.completedAt ?: 0L)
                    scope.persist()
                    sent += 1
                }
            }
        }
        return sent
    }

    /** 开关必须读**本地** Agent prefs：它不在 `LOCAL_AGENT_KEYS` 里，用 `Prefs.isEnabled` 会永远读到默认值。 */
    private fun autoResumeEnabled(context: Context): Boolean {
        val default = Prefs.Keys.BOOLEAN_DEFAULTS[Prefs.Keys.AGENT_BACKGROUND_RESUME_AUTO] ?: false
        return runCatching { Prefs.localAgentPreferences(context).getBoolean(Prefs.Keys.AGENT_BACKGROUND_RESUME_AUTO, default) }
            .getOrDefault(default)
    }
}
