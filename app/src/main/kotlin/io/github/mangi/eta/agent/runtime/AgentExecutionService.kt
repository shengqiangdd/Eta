package io.github.mangi.eta.agent.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.overlay.localizedText
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.ui.MainActivity
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
        if (intent?.action == ACTION_STOP) {
            stopTasks()
        } else {
            // 每次新的运行都重新尝试提升前台：上一次失败可能只是当时应用在后台，
            // 永久记住“提升被拒”会让之后的运行全程失去前台保护（退到后台就被杀）。
            startRejected = false
            ensureForeground()
            refreshNotification()
        }
        return START_NOT_STICKY
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
        } catch (failure: RuntimeException) {
            AndroidAgentLogger.warn("Execution service wake lock failed: type=${failure.safeLogType()}")
        }
    }

    private fun releaseWakeLock() {
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
            .addAction(stopAction)
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

    /** 状态栏胶囊约 7 个字符可完整显示；更长时只取动作，工具名留在展开态。 */
    private fun AgentOverlayStatus.shortText(): String = when (this) {
        is AgentOverlayStatus.RunningTool, is AgentOverlayStatus.HostedToolRunning -> getString(R.string.execution_chip_running)
        AgentOverlayStatus.Paused -> getString(R.string.overlay_paused)
        AgentOverlayStatus.Stopping -> getString(R.string.overlay_stopping)
        AgentOverlayStatus.GeneratingAnswer -> getString(R.string.execution_chip_answering)
        else -> getString(R.string.overlay_reasoning)
    }

    companion object {
        private const val CHANNEL = "eta_execution"
        private const val NOTIFICATION_ID = 1107
        private const val ACTION_STOP = "io.github.mangi.eta.action.STOP_USER_EXECUTION"
        /** 与通知「正在执行」同生命周期；任务空闲即释放，不会常驻。 */
        private const val WAKE_LOCK_TAG = "eta:agent-execution"
        /** 防泄漏上限；每次任务状态刷新都会续期。 */
        private const val WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1000L
        /** 锁过半才续期，减少状态刷新带来的 wakelock 重获取。 */
        private const val WAKE_LOCK_RENEW_INTERVAL_MS = 30L * 60L * 1000L
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
            onStop: () -> Unit,
        ): Boolean {
            if (!leases.acquire(id, allowBoundFallback, onStop)) return true
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
