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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.overlay.localizedText
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.config.Prefs
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
    /**
     * 屏幕保活锁。
     *
     * **必须由前台服务持有，不能放在 ViewModel 里**：Agent 操作别的应用时，Eta 的
     * Activity 会被系统回收（内存压力 / ROM 后台管控），而前台服务活着。
     * 旧实现的屏幕锁跑在 `viewModelScope` 里，Activity 一销毁协程就被取消，
     * 锁不再续期，到期后屏幕开始按系统超时熄灭——但 CPU 锁在服务里还活着，
     * 于是任务继续跑，用户看到的是「任务在跑，屏幕却会灭」。
     *
     * 生命周期与 [wakeLock] 完全一致：前台服务在跑就续期，任务清空即释放。
     */
    private val screenWakeLock by lazy { AgentScreenWakeLock(applicationContext) }
    private var screenWakeLockRenewedAtElapsed = 0L
    private val screenWakeLockRenew = object : Runnable {
        override fun run() {
            if (!foregroundActive) {
                releaseScreenWakeLock()
                return
            }
            renewScreenWakeLock()
        }
    }
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
            renewScreenWakeLock()
            // 服务刚起来时窗口 flag 还停在初始值 false；这里用权威状态补一次，
            // 否则 Activity 已在后台重建、没人触发重组时，前台窗口不会保屏。
            publishKeepScreenOn()
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
        releaseScreenWakeLock()
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
            releaseScreenWakeLock()
            // 服务即将自停：窗口 flag 必须同时收掉，否则界面还停在「有任务」的旧值上。
            publishKeepScreenOn()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            renewWakeLock()
            renewScreenWakeLock()
            publishKeepScreenOn()
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

    /**
     * 续期屏幕锁。
     *
     * 三个条件缺一不可，全部来自**权威来源**：
     * - `foregroundActive`：服务自己是否已进入前台；
     * - `agentRunIds()`：服务登记的 Agent run 租约，任务结束就会被 [release] 摘掉；
     * - `runStatus`：服务端回传的语义状态，用来排除暂停。
     *
     * 只看前两者里的第一个（旧行为）会让服务因终端命令等其它租约存活时继续亮屏；
     * 完全不看任务（上一版）会让会话结束后一直亮到天亮。两个方向都踩过，别再退回去。
     *
     * 用户关掉「运行时保持亮屏」开关时必须立即释放：Agent 在后台跑几十分钟，
     * 不能因为用户关了开关还继续亮着。开关值是全局偏好，直接读最新值，不缓存：
     * 用户在任务中途关掉开关，下一轮续期就要生效。
     */
    private fun renewScreenWakeLock() {
        val paused = runStatus is AgentOverlayStatus.Paused
        val hold = AgentKeepScreenLockPolicy.shouldHold(
            foregroundActive = foregroundActive,
            hasRunningTask = leases.agentRunIds().isNotEmpty(),
            paused = paused,
            enabled = keepScreenOnEnabled(),
        )
        if (!hold) {
            releaseScreenWakeLock()
            return
        }
        val now = SystemClock.elapsedRealtime()
        // 与 CPU 锁同样的节奏：同一把锁不必每次状态刷新都跨 binder 重置超时。
        if (screenWakeLockRenewedAtElapsed != 0L &&
            now - screenWakeLockRenewedAtElapsed < SCREEN_WAKE_LOCK_RENEW_INTERVAL_MS
        ) {
            return
        }
        screenWakeLock.setHeld(true)
        screenWakeLockRenewedAtElapsed = now
        mainHandler.removeCallbacks(screenWakeLockRenew)
        mainHandler.postDelayed(screenWakeLockRenew, SCREEN_WAKE_LOCK_RENEW_INTERVAL_MS)
    }

    /** 立即按当前开关重算：开关切换必须马上生效，不能等下一轮续期。 */
    private fun refreshScreenWakeLock() {
        screenWakeLockRenewedAtElapsed = 0L
        renewScreenWakeLock()
    }

    private fun releaseScreenWakeLock() {
        mainHandler.removeCallbacks(screenWakeLockRenew)
        screenWakeLockRenewedAtElapsed = 0L
        screenWakeLock.setHeld(false)
    }

    private fun keepScreenOnEnabled(): Boolean =
        Prefs.isEnabled(Prefs.Keys.AGENT_KEEP_SCREEN_ON)

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
        /**
         * 屏幕锁续期周期。
         *
         * [AgentScreenWakeLock] 的 12 小时上限是防泄漏兜底；它不会自动续期，
         * 状态没变化时也没人再调 acquire。长任务期间必须定期推一把，
         * 否则过了上限屏幕就会开始熄灭。
         */
        private const val SCREEN_WAKE_LOCK_RENEW_INTERVAL_MS = 30L * 60L * 1000L
        private val leases = ExecutionLeaseRegistry()
        private val ownerSequence = AtomicLong()
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var instance: AgentExecutionService? = null
        private var runStatus: AgentOverlayStatus? = null
        private var activeRunCount = 0
        private var publishedStatusText: String? = null

        /**
         * 用户切换「运行时保持亮屏」后立即重算屏幕锁。
         *
         * 没有这个入口，开关只能在下一轮续期（最长 30 分钟）才生效：
         * 用户关掉开关却看到屏幕继续亮半小时，会以为开关坏了。
         * 服务没在跑时什么也不做——锁本来就不该存在。
         */
        fun refreshKeepScreen() {
            mainHandler.post {
                // 开关切换同时影响窗口 flag 与服务持有的唤醒锁，两边一起重算。
                publishKeepScreenOn()
                instance?.takeIf { it.foregroundActive }?.refreshScreenWakeLock()
            }
        }

        /**
         * 「现在是否应该保屏」的权威答案，供前台窗口的 FLAG_KEEP_SCREEN_ON 使用。
         *
         * 窗口 flag 以前算的是 `agentState.anyRunningUnpausedConversation` 这个 UI 镜像，
         * 和跨应用保屏是两套判据：镜像在 Activity 回收重建后会失真（运行态不入库），
         * 于是窗口 flag 可能和服务持有的唤醒锁说两套话。这里让两者共用同一个
         * [AgentKeepScreenLockPolicy]，只把「让不让屏幕亮」这件事留一个决策点。
         *
         * 与 [AgentScreenWakeLock] 不同，窗口 flag 只需在前台有效，不要求 `foregroundActive`：
         * 用户正看着界面、任务正在推进时，即便服务那一刻还没提升为前台也该保屏。
         * 因此第一个参数固定传 `true`，由调用方自己保证只在窗口可见时置位。
         */
        fun shouldKeepScreenOn(): Boolean = AgentKeepScreenLockPolicy.shouldHold(
            foregroundActive = true,
            hasRunningTask = leases.agentRunIds().isNotEmpty(),
            paused = runStatus is AgentOverlayStatus.Paused,
            enabled = Prefs.isEnabled(Prefs.Keys.AGENT_KEEP_SCREEN_ON),
        )

        /**
         * [shouldKeepScreenOn] 的可观察版本，供 Compose 直接读取。
         *
         * 普通函数不算快照读，服务端状态变了下窗口 flag 不会重算；这里把结果发布成
         * `State`，每次 [refreshNotification]（任务增删、状态变化、开关切换都会走到）
         * 重新写一次，Compose 自然重组。只在值真的变化时写入，避免逐帧无谓失效。
         */
        var keepScreenOnState by mutableStateOf(false)
            private set

        /** 把 [keepScreenOnState] 与当前权威状态对齐；由 [refreshNotification] 调用。 */
        private fun publishKeepScreenOn() {
            val next = shouldKeepScreenOn()
            if (keepScreenOnState != next) keepScreenOnState = next
        }

        /**
         * Agent run 的当前状态，供通知与状态栏胶囊展示；null 表示没有 Agent run 在跑。
         * 只在显示文字变化时刷新通知，避免流式事件把通知更新频率推到系统限流。
         */
        fun updateRunStatus(context: Context, status: AgentOverlayStatus?, activeRunCount: Int) {
            mainHandler.post {
                runStatus = status
                this.activeRunCount = activeRunCount
                /*
                 * 窗口 flag 必须在文本判重**之前**发布：
                 * 暂停\/继续时文案有可能刚好相同（多会话汇总文案不随状态变），
                 * 一旦提前 return，调度就变了而 flag 不跟着变，暂停后屏幕继续亮。
                 */
                publishKeepScreenOn()
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
