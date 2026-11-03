package io.github.mangi.eta.agent.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.overlay.localizedText
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.ui.MainActivity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** 只在用户任务存活期间持有前台执行生命周期；进程被系统停止后不重放任务。 */
internal class AgentExecutionService : Service() {
    private val stopQueue = ExecutionStopQueue { failure ->
        AndroidAgentLogger.warn("Execution task stop failed: type=${failure.safeLogType()}")
    }
    private val owner = ownerSequence.incrementAndGet()
    private var foregroundActive = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockRenewedAtElapsed = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    /**
     * 通知栏动作的落点。
     *
     * `AgentRuntimeClient` 的暂停/继续/补充都走 Binder，不能放在 onStartCommand
     * 的主线程上等：运行时正忙时一次绑定可能要几百毫秒，主线程卡住会直接 ANR。
     * 单线程也保证用户连点两次「暂停」时两次请求按顺序到达。
     */
    private val controlExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "eta-execution-control").apply { isDaemon = true }
    }
    /** 长时间没有状态刷新（例如一个几十分钟的编译）时也要续期，不能只靠 notify 触发。 */
    private val wakeLockRenew = object : Runnable {
        override fun run() {
            if (!foregroundActive) {
                releaseWakeLock()
                return
            }
            renewWakeLock()
        }
    }
    private val openPendingIntent by lazy {
        PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
    private val stopPendingIntent by lazy {
        PendingIntent.getService(
            this, 1, Intent(this, AgentExecutionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
    private val stopAction by lazy {
        Notification.Action.Builder(null, getString(R.string.execution_stop), stopPendingIntent).build()
    }
    private val togglePausePendingIntent by lazy {
        PendingIntent.getService(
            this, 2, Intent(this, AgentExecutionService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
    private val supplementPendingIntent by lazy {
        PendingIntent.getService(
            this, 3, Intent(this, AgentExecutionService::class.java).setAction(ACTION_SUPPLEMENT),
            /*
             * RemoteInput 的文本由系统在发送时填进 Intent，PendingIntent 必须是 MUTABLE。
             * 目标组件是写死的本服务，不是隐式 Intent，因此不存在被第三方填充的风险。
             */
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }
    @Volatile private var startRejected = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        leases.attachOwner(owner)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.execution_channel), NotificationManager.IMPORTANCE_LOW),
        )
        ensureForeground()
    }

    private fun ensureForeground() {
        if (foregroundActive || startRejected) return
        leases.attachOwner(owner)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
            foregroundActive = true
            renewWakeLock()
        } catch (failure: RuntimeException) {
            startRejected = true
            AndroidAgentLogger.warn("Execution service foreground failed: type=${failure.safeLogType()}")
            stopTasks(startFailed = true)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTasks()
            ACTION_TOGGLE_PAUSE -> togglePause()
            ACTION_SUPPLEMENT -> submitSupplement(intent)
            else -> {
                // 每次新的运行都重新尝试提升前台：上一次失败可能只是当时应用在后台，
                // 永久记住“提升被拒”会让之后的运行全程失去前台保护（退到后台就被杀）。
                startRejected = false
                ensureForeground()
                refreshNotification()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * 暂停 / 继续所有已登记的任务。
     *
     * 标签按最后一次已知状态决定：暂停中显示「继续」，否则显示「暂停」。
     * 状态本身由 Runtime 回传，点击后不在这里伪造本地状态 —— 否则真实结果和界面
     * 会各说一套（例如运行时正在提交终态，暂停请求会被拒绝）。
     */
    private fun togglePause() {
        val resume = runStatus is AgentOverlayStatus.Paused
        val ids = leases.agentRunIds()
        if (ids.isEmpty()) return
        controlExecutor.execute {
            val client = AgentRuntimeClient(applicationContext, AndroidAgentLogger)
            var accepted = 0
            ids.forEach { runId ->
                val ok = if (resume) client.resumeRun(runId) else client.pauseRun(runId)
                if (ok) accepted += 1
            }
            AndroidAgentLogger.info(
                "Execution notification pause action: resume=$resume accepted=$accepted total=${ids.size}",
            )
            mainHandler.post { if (instance === this) refreshNotification() }
        }
    }

    /**
     * 从通知栏直接把一句话交给正在跑的 run（steering），不打断当前步骤。
     *
     * 只发给最新启动的任务：多会话并行时，用户刚发起、正在等的那个才对应他眼前的任务。
     */
    private fun submitSupplement(intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_SUPPLEMENT)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val runId = leases.latestAgentRunId() ?: return
        controlExecutor.execute {
            val client = AgentRuntimeClient(applicationContext, AndroidAgentLogger)
            val sent = client.sendSupplement(runId, text) { accepted ->
                AndroidAgentLogger.info(
                    "Execution notification supplement: accepted=$accepted chars=${text.length}"
                )
                if (!accepted) {
                    // 通知栏没有聊天页的草稿可保留，只能明确告诉用户这条没进当前 run。
                    mainHandler.post {
                        Toast.makeText(
                            applicationContext,
                            R.string.execution_steer_rejected,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                mainHandler.post { if (instance === this) refreshNotification() }
            }
            if (!sent) {
                AndroidAgentLogger.info("Execution notification supplement: sent=false chars=${text.length}")
                mainHandler.post {
                    Toast.makeText(
                        applicationContext,
                        R.string.execution_steer_unavailable,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                /*
                 * RemoteInput 的输入态只有重新 notify 同一 id 才会收起；不刷新的话
                 * 通知会一直停在「正在发送」，用户以为没发出去。
                 */
                mainHandler.post { if (instance === this) refreshNotification() }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 用户从最近任务划掉 App 不等于要停掉正在跑的 Agent 任务：服务仍在前台，
     * 这里只重新确认一次前台状态。部分 ROM 会借着这次回调回收进程，因此也要留日志。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val tasks = leases.count()
        if (tasks > 0) {
            AndroidAgentLogger.info("Execution service kept after task removed: tasks=$tasks")
            startRejected = false
            ensureForeground()
            refreshNotification()
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        val pending = leases.count()
        if (pending > 0) {
            // 只有系统销毁服务（进程被回收、ROM 清理后台）才会走到这里带着活跃任务。
            // 留下日志才能把“运行被系统中断”和“用户主动停止”区分开。
            AndroidAgentLogger.warn("Execution service destroyed with $pending active task(s), cancelling")
        }
        // 销毁时同样收回本服务拥有的任务。回收在独立有界工作线程上完成，不阻塞 Main。
        releaseWakeLock()
        controlExecutor.shutdownNow()
        stopQueue.close(leases.drainOwner(owner))
        super.onDestroy()
    }

    private fun stopTasks(startFailed: Boolean = false) {
        val callbacks = leases.drain(startFailed)
        stopQueue.submit(callbacks) {
            mainHandler.post { if (instance === this) refreshNotification() }
        }
    }

    private fun refreshNotification() {
        if (leases.closeOwnerIfIdle(owner)) {
            foregroundActive = false
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            renewWakeLock()
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
    }

    /**
     * 任务期间持一把 PARTIAL_WAKE_LOCK。
     *
     * 前台服务只保证进程不被杀，不保证 CPU 不休眠：息屏后设备进入 Doze / suspend，
     * 线程与协程会被一起冻住，表现就是「软件没被杀，但亮屏才继续」。
     * 超时是防泄漏兜底：任务状态每次刷新（工具进度、任务数变化）都会续期，
     * 正常长任务不会因为超时被中断；服务销毁或任务清空时立即释放。
     */
    private fun renewWakeLock() {
        if (!foregroundActive) return
        val lock = wakeLock ?: run {
            val manager = getSystemService(PowerManager::class.java) ?: return
            manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
        }
        val now = SystemClock.elapsedRealtime()
        // 状态刷新可能很频繁；同一把 60 分钟锁只在过半后才续期，避免每次进度都跨 binder 重置超时。
        if (lock.isHeld && now - wakeLockRenewedAtElapsed < WAKE_LOCK_RENEW_INTERVAL_MS) return
        try {
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            wakeLockRenewedAtElapsed = now
            mainHandler.removeCallbacks(wakeLockRenew)
            mainHandler.postDelayed(wakeLockRenew, WAKE_LOCK_RENEW_INTERVAL_MS)
        } catch (failure: RuntimeException) {
            AndroidAgentLogger.warn("Execution service wake lock failed: type=${failure.safeLogType()}")
        }
    }

    private fun releaseWakeLock() {
        mainHandler.removeCallbacks(wakeLockRenew)
        wakeLockRenewedAtElapsed = 0L
        val lock = wakeLock ?: return
        if (lock.isHeld) runCatching { lock.release() }
    }

    private fun notification(): Notification {
        val status = runStatus
        val taskCount = if (activeRunCount > 0) activeRunCount else leases.count()
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.execution_title))
            .setContentText(executionText(this, status, taskCount))
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        // 暂停/补充只对 Agent run 有意义：终端会话、后台命令这类租约没有可暂停的推理。
        // 对它们显示按钮只会得到一个“点了没反应”的通知。
        if (leases.agentRunIds().isNotEmpty()) {
            builder.addAction(supplementAction())
            builder.addAction(togglePauseAction())
        }
        builder.addAction(stopAction)
        // 只有 Agent run 在跑时才申请提升为 Live Update；终端会话、Linux 启动等长驻任务不属于"正在进行、需要持续关注"的活动。
        if (status != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder
                .setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
                .setShortCriticalText(status.shortText())
            // 请求方法从 API 36.1 才公开；标准 extra 也可供支持该协议的 API 36 系统读取。
            builder.extras.putBoolean("android.requestPromotedOngoing", true)
        }
        return builder.build()
    }

    /** 通知栏「补充」：展开一个输入框，回车即把这句话交给正在跑的 run。 */
    private fun supplementAction(): Notification.Action =
        Notification.Action.Builder(
            null,
            getString(R.string.execution_supplement),
            supplementPendingIntent,
        )
            .addRemoteInput(
                RemoteInput.Builder(KEY_SUPPLEMENT)
                    .setLabel(getString(R.string.execution_supplement_hint))
                    .build(),
            )
            .build()

    private fun togglePauseAction(): Notification.Action {
        val label = if (runStatus is AgentOverlayStatus.Paused) {
            R.string.execution_resume
        } else {
            R.string.execution_pause
        }
        return Notification.Action.Builder(null, getString(label), togglePausePendingIntent).build()
    }

    /** 状态栏胶囊约 7 个字符可完整显示；更长时只取动作，工具名留在展开态。 */
    private fun AgentOverlayStatus.shortText(): String = when (this) {
        is AgentOverlayStatus.RunningTool, is AgentOverlayStatus.HostedToolRunning -> getString(R.string.execution_chip_running)
        AgentOverlayStatus.Paused -> getString(R.string.overlay_paused)
        AgentOverlayStatus.Stopping -> getString(R.string.overlay_stopping)
        AgentOverlayStatus.GeneratingAnswer -> getString(R.string.execution_chip_answering)
        // 排队等屏幕时状态栏还写「思考中」就是在说假话。用短文案，7 字符以内才不被截断。
        AgentOverlayStatus.WaitingForGui -> getString(R.string.overlay_waiting_gui)
        else -> getString(R.string.overlay_reasoning)
    }

    companion object {
        private const val CHANNEL = "eta_execution"
        private const val NOTIFICATION_ID = 1107
        private const val ACTION_STOP = "io.github.mangi.eta.action.STOP_USER_EXECUTION"
        private const val ACTION_TOGGLE_PAUSE = "io.github.mangi.eta.action.TOGGLE_USER_EXECUTION_PAUSE"
        private const val ACTION_SUPPLEMENT = "io.github.mangi.eta.action.SUPPLEMENT_USER_EXECUTION"
        /** RemoteInput 的结果键；必须与通知动作里注册的键一致。单测直接比对它，防止两边写歪。 */
        internal const val KEY_SUPPLEMENT = "eta.execution.supplement"
        /** 与通知「正在执行」同生命周期；任务空闲即释放，不会常驻。 */
        private const val WAKE_LOCK_TAG = "eta:agent-execution"
        /** 防泄漏上限；每次任务状态刷新都会续期。 */
        private const val WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1000L
        /** 锁过半才续期，减少状态刷新带来的 wakelock 重获取。 */
        private const val WAKE_LOCK_RENEW_INTERVAL_MS = 10L * 60L * 1000L
        private val leases = ExecutionLeaseRegistry()
        private val ownerSequence = AtomicLong()
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var instance: AgentExecutionService? = null
        private var runStatus: AgentOverlayStatus? = null
        private var activeRunCount = 0
        private var publishedStatusText: String? = null

        /**
         * Agent run 的当前状态，供通知与状态栏胶囊展示；null 表示没有 Agent run 在跑。
         * 只在显示文字变化时刷新通知，避免流式事件把通知更新频率推到系统限流。
         */
        fun updateRunStatus(context: Context, status: AgentOverlayStatus?, activeRunCount: Int) {
            mainHandler.post {
                runStatus = status
                this.activeRunCount = activeRunCount
                val count = if (activeRunCount > 0) activeRunCount else leases.count()
                val text = executionText(context, status, count)
                if (text == publishedStatusText) return@post
                publishedStatusText = text
                instance?.takeIf { it.foregroundActive }?.refreshNotification()
            }
        }

        private fun executionText(context: Context, status: AgentOverlayStatus?, taskCount: Int): String {
            val summary = context.resources.getQuantityString(R.plurals.execution_summary, taskCount, taskCount)
            return when {
                status == null -> summary
                taskCount <= 1 -> status.localizedText(context.resources)
                else -> "$summary · ${status.localizedText(context.resources)}"
            }
        }

        /** 必须从有效的用户入口取得引用，再创建会话或子进程；失败时调用方不启动任务。 */
        fun acquire(
            context: Context,
            id: String,
            allowBoundFallback: Boolean = false,
            agentRunId: String? = null,
            onStop: () -> Unit,
        ): Boolean {
            if (!leases.acquire(id, allowBoundFallback, agentRunId, onStop)) return true
            // 曾经失败过一次（例如当时应用在后台，系统拒绝提升前台服务）不能永久判定不可用：
            // 用户回到界面后再发起运行，系统会重新放行。这里每次都重试，失败只记日志。
            return try {
                context.applicationContext.startForegroundService(Intent(context, AgentExecutionService::class.java))
                true
            } catch (failure: RuntimeException) {
                leases.release(id)
                AndroidAgentLogger.warn("Execution service start rejected: type=${failure.safeLogType()}")
                false
            }
        }

        fun release(id: String) {
            leases.release(id)
            mainHandler.post { instance?.refreshNotification() }
        }
    }
}
