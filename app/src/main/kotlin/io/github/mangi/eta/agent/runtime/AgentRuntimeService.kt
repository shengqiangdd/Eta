package io.github.mangi.eta.agent.runtime

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import android.app.Service
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import io.github.mangi.eta.agent.trace.AgentRunTraceStore
import io.github.mangi.eta.ui.MainActivity
import io.github.mangi.eta.ui.app.AppVisibility
import android.os.Build
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.PowerManager
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import android.window.OnBackInvokedCallback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.itos.coui.effect.LocalCouiContourGlowEnabled
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.overlay.AgentHapticFeedback
import io.github.mangi.eta.agent.overlay.AgentOverlayGlow
import io.github.mangi.eta.agent.overlay.AgentOverlayPreferencePolicy
import io.github.mangi.eta.agent.overlay.ScreenCornerRadii
import io.github.mangi.eta.agent.overlay.AgentOverlayCapsule
import io.github.mangi.eta.agent.overlay.AgentOverlayClearance
import io.github.mangi.eta.agent.overlay.AgentResultCard
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayController
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplaySnapshot
import io.github.mangi.eta.agent.overlay.AgentOverlayPhase
import io.github.mangi.eta.agent.overlay.AgentOverlayState
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.mangi.eta.agent.overlay.applyEvent
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.app.AgentAppTheme
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 模块进程内的通用 Agent Runtime。
 *
 * 支持多个 run 并发（按 runId 管理 session）；浮层与结果卡只跟随 primary run。
 * Hook 入口只发送请求和接收结果；模型调用、工具执行、运行状态浮窗都在本服务中完成。
 */
internal class AgentRuntimeService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val mainHandler = Handler(Looper.getMainLooper())
    private val resultIo = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "agent-result-io") }
    private val serviceMessenger = Messenger(IncomingHandler())

    /** 多会话并发：每个 runId 一个 session；浮层只跟随 [primaryRunId]。 */
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, AgentRuntimeSession>()

    /**
     * 手机界面只有一个操作所有者。
     *
     * 多会话并行时，两个 run 的 GUI 动作若不做互斥，会各自按自己看到的节点去点同一块屏幕 ——
     * 表现为「点了没反应」或「点到别的界面」。这里持有全局锁，各 run 通过
     * [GuiControlCoordinator.openRun] 领取自己的 [GuiControlCoordinator.RunControl]。
     */
    private val guiControl = GuiControlCoordinator()
    @Volatile
    private var primaryRunId: String? = null
    private val activeSession: AgentRuntimeSession?
        get() = primaryRunId?.let { sessions[it] }
    private val pendingStartRequests = java.util.concurrent.ConcurrentHashMap<Long, PendingStartRequest>()
    private val startRequestCounter = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * Runtime 自己持一把 PARTIAL_WAKE_LOCK，不再只依赖前台服务。
     *
     * 真机上出现过「root 已授权 → FGS 启动被后台限制 → allowBoundFallback 放行」的路径：
     * run 在跑，但没有前台服务、也没有任何 wake lock，息屏后 CPU 进入 suspend，
     * 界面看起来就是「任务停住，亮屏才继续」。这把锁只按 session 存活持有，10 分钟续期一次，
     * 60 分钟超时只是防泄漏兜底。
     */
    private var runtimeWakeLock: PowerManager.WakeLock? = null
    private val runtimeWakeLockRenew = object : Runnable {
        override fun run() {
            if (sessions.values.none { !it.isTerminal }) {
                releaseRuntimeWakeLock()
                return
            }
            acquireRuntimeWakeLock()
        }
    }

    private data class PendingStartRequest(
        val generation: Long,
        val incoming: AgentRuntimeWire.IncomingRunRequest,
        val replyTo: Messenger?,
    )

    private var windowManager: WindowManager? = null
    private var glowView: ComposeView? = null
    private var capsuleView: ComposeView? = null
    private var resultCardView: ComposeView? = null
    private var glowParams: WindowManager.LayoutParams? = null
    private var capsuleParams: WindowManager.LayoutParams? = null
    private var resultCardParams: WindowManager.LayoutParams? = null
    private var resultCardBack: Pair<OnBackInvokedDispatcher, OnBackInvokedCallback>? = null

    /** 浮层与执行通知共用同一份运行状态；写入只发生在主线程。 */
    private val state = object {
        private val holder = mutableStateOf(AgentOverlayState.Initial)
        var value: AgentOverlayState
            get() = holder.value
            set(next) {
                holder.value = next
                AgentExecutionService.updateRunStatus(
                    this@AgentRuntimeService,
                    next.status.takeIf { activeSession?.isTerminal == false },
                    // 本地 Runtime 只有 primary 一条主 run；并发 run 计数交给租约数兜底。
                    activeRunCount = if (activeSession?.isTerminal == false) 1 else 0,
                )
            }
    }
    private val capsuleExpanded = mutableStateOf(false)
    /** Agent 手势穿过胶囊时为 true：窗口不可触摸，胶囊淡出让路。 */
    private val capsuleYielded = mutableStateOf(false)
    /** 后台虚拟屏快照：胶囊的微缩取景器、装裱视窗与结果卡片的「打开画面」都读它。 */
    private val virtualDisplayState = mutableStateOf(VirtualDisplaySnapshot.Inactive)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val restoreCapsule = Runnable { setCapsuleYielded(false) }
    private val clearanceHost = object : AgentOverlayClearance.Host {
        override fun capsuleTouchBounds(): Rect? {
            val view = capsuleView?.takeIf { it.isAttachedToWindow } ?: return null
            val location = IntArray(2).also(view::getLocationOnScreen)
            val slop = dpToPx(CAPSULE_CLEARANCE_SLOP_DP)
            return Rect(
                location[0] - slop,
                location[1] - slop,
                location[0] + view.width + slop,
                location[1] + view.height + slop,
            )
        }

        override fun yieldCapsule() = setCapsuleYielded(true)

        override fun scheduleCapsuleRestore(delayMs: Long) {
            mainHandler.removeCallbacks(restoreCapsule)
            mainHandler.postDelayed(restoreCapsule, delayMs)
        }

        override fun cancelRestore() = mainHandler.removeCallbacks(restoreCapsule)
    }
    private var hasExecutedForegroundTool = false
    /**
     * 本轮是否已经“露脸”过一次。触觉反馈只在首次露脸时触发：
     * 早前用 capsuleView == null 当判据，用户关掉胶囊后它恒为 null，
     * 于是每一个前台工具事件都会再震一次；只开边缘流光时同理。
     */
    private var overlayRevealed = false
    /**
     * 结果卡片"回到 Eta"打开的会话：App 内发起的 run 带会话 ID；系统助手等外部入口
     * 带来源与会话键，由界面导入归档后打开。两者都没有时只回到首页。
     */
    private var resultConversationId: String? = null
    private var resultArchive: Pair<String, String>? = null

    /** 一条 run 完成后应该打开的位置；两项都不可用时只回到首页。 */
    private data class RunOpenTarget(
        val conversationId: String? = null,
        val archiveSource: String? = null,
        val archiveKey: String? = null,
    ) {
        val canOpen: Boolean
            get() = conversationId != null || (archiveSource != null && archiveKey != null)
    }

    /**
     * 按 runId 记住「这条 run 属于哪个会话」。
     *
     * 原来只有 `resultConversationId` 一个 service 级字段：并发 run 时后启动的会覆盖前一个，
     * 于是后台 run 完成的通知可能打开另一个会话（甚至刚启动、还没有结果的那个）。
     * 这里按 runId 存，每条 run 只读自己那一条。
     */
    private val runOpenTargets = java.util.concurrent.ConcurrentHashMap<String, RunOpenTarget>()
    /** 供多会话状态同步判断这是不是 App 内发起的 run。 */
    private val runHandoffSources = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * runId → 所属会话。多会话并发是允许的，但**同一会话**不能同时跑两个 run：
     * 两个 run 会互相覆盖消息、运行态和配置，界面表现就是「会话一直运行中，只能手动结束」。
     */
    private val runConversationIds = java.util.concurrent.ConcurrentHashMap<String, String>()
    private class RunSupplements {
        val lock = Any()
        val items = mutableListOf<AgentUiHandoffPayload.Supplement>()
        var nextIndex = 1
    }
    private val supplementsByRun = java.util.concurrent.ConcurrentHashMap<String, RunSupplements>()
    /** 每个已完成 run 的上下文；用于「完成后补充指令」按 runId 继续。 */
    private val completedContextsLock = Any()
    private val completedContexts = LinkedHashMap<String, CompletedRunContext>()
    private val hideToken = Any()
    /** 设置页改「显示悬浮思考胶囊」时立刻生效；必须强引用，SharedPreferences 只持弱引用。 */
    private var overlayPreferencesListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        AgentOverlayClearance.attach(clearanceHost)
        registerOverlayPreferenceListener()
        serviceScope.launch {
            VirtualDisplayController.snapshots.collect { snapshot ->
                val wasActive = virtualDisplayState.value.active
                virtualDisplayState.value = snapshot
                if (wasActive != snapshot.active) {
                    if (!snapshot.active) VirtualDisplayController.setStageExpanded(false)
                    updateCapsuleWindowBounds()
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        if (intent.action != AgentRuntimeWire.ACTION_BIND) return null
        return serviceMessenger.binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 只按“有没有 run 在执行”决定去留：任何一次多余的 start 命令（含系统补发）都不该
        // 停掉正在跑的任务，否则退到后台时服务重启一次就等于把会话掐了。
        if (sessions.isEmpty()) {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (sessions.values.any { !it.isTerminal }) {
            AndroidAgentLogger.debug {
                "Agent runtime client unbound while run is active; detached run continues"
            }
        }
        return false
    }

    override fun onDestroy() {
        pendingStartRequests.values.toList().forEach { pending ->
            pending.incoming.close()
            sendRequestIngestedTo(pending.replyTo, pending.incoming.request.runId)
            sendResultTo(
                pending.replyTo,
                AgentRuntimeWire.RunResult(
                    runId = pending.incoming.request.runId,
                    ok = false,
                    content = "",
                    error = "Agent Runtime 服务已停止",
                ),
            )
        }
        pendingStartRequests.clear()
        sessions.values.toList().forEach { it.cancel("Agent Runtime 服务已停止") }
        sessions.clear()
        releaseRuntimeWakeLock()
        primaryRunId = null
        synchronized(completedContextsLock) { completedContexts.clear() }
        resultIo.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        AgentOverlayClearance.detach(clearanceHost)
        unregisterOverlayPreferenceListener()
        serviceScope.cancel()
        VirtualDisplayController.release(this)
        unregisterResultCardBack()
        resultCardView?.let { view -> runCatching { windowManager?.removeView(view) } }
        capsuleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        resultCardView = null
        capsuleView = null
        glowView = null
        resultCardParams = null
        capsuleParams = null
        glowParams = null
        windowManager = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == AgentRuntimeWire.MSG_START_RUN) {
                AndroidAgentLogger.info(
                    "Agent run request arrived: sendingUid=${msg.sendingUid} self=${Process.myUid()} hasData=${msg.data != null}"
                )
            }
            if (!isMessageSenderAllowed(msg)) {
                if (msg.what == AgentRuntimeWire.MSG_START_RUN) {
                    AndroidAgentLogger.warn("Agent run request rejected: sender not allowed uid=${msg.sendingUid}")
                    AgentRuntimeWire.closeImageDescriptors(msg.data)
                    // 明确回一个失败结果：调用方不必干等到启动超时才知道自己被拒了。
                    sendResultTo(
                        msg.replyTo,
                        AgentRuntimeWire.RunResult(
                            runId = "",
                            ok = false,
                            content = "",
                            error = "Agent Runtime 拒绝了来自其他应用的请求",
                        ),
                    )
                }
                return
            }
            when (msg.what) {
                AgentRuntimeWire.MSG_START_RUN -> {
                    val data = msg.data
                    if (data == null) {
                        finishWithFailure("Agent Runtime 请求缺少消息体", msg.replyTo)
                        return
                    }
                    val incoming = runCatching {
                        AgentRuntimeWire.incomingRunRequestFromBundle(data)
                    }.getOrElse { throwable ->
                        AndroidAgentLogger.warnThrottled("runtime_invalid_start_request") {
                            "Agent runtime rejected invalid start request: type=${throwable.safeLogType()}"
                        }
                        finishWithFailure("Agent Runtime 请求格式无效", msg.replyTo)
                        return
                    }
                    val request = incoming.request
                    if (request.runId.isBlank() || (request.operation != AgentRuntimeWire.OP_COMPACT && request.prompt.isBlank() && incoming.images.isEmpty() && !incoming.hasDeferredPrompt)) {
                        incoming.close()
                        finishWithFailure("Agent Runtime 请求缺少 runId 或用户输入", msg.replyTo)
                        return
                    }
                    ingestRunRequest(incoming, msg.replyTo)
                }

                AgentRuntimeWire.MSG_CANCEL -> {
                    val runId = msg.data?.let(AgentRuntimeWire::runIdFromBundle).orEmpty()
                    if (runId.isNotBlank()) cancelRun(runId)
                }

                AgentRuntimeWire.MSG_SUPPLEMENT -> {
                    val data = msg.data ?: return
                    val runId = AgentRuntimeWire.runIdFromBundle(data)
                    val text = data.getString(AgentRuntimeWire.KEY_SUPPLEMENT_TEXT).orEmpty()
                    val accepted = requestSupplement(text, expectedRunId = runId)
                    // 回执是界面能否清草稿的唯一依据：发送成功不等于被接纳（run 可能刚结束）。
                    msg.replyTo?.let { reply ->
                        val ack = Message.obtain(null, AgentRuntimeWire.MSG_SUPPLEMENT_ACK)
                        ack.data = AgentRuntimeWire.supplementAckBundle(runId, accepted)
                        runCatching { reply.send(ack) }.onFailure { error ->
                            AndroidAgentLogger.warnThrottled("runtime_supplement_ack_failed") {
                                "Agent runtime supplement ack failed: type=${error.safeLogType()}"
                            }
                        }
                    }
                }

                AgentRuntimeWire.MSG_PAUSE_RUN -> {
                    val runId = msg.data?.let(AgentRuntimeWire::runIdFromBundle).orEmpty()
                    if (runId.isNotBlank()) pauseRun(runId)
                }

                AgentRuntimeWire.MSG_RESUME_RUN -> {
                    val runId = msg.data?.let(AgentRuntimeWire::runIdFromBundle).orEmpty()
                    if (runId.isNotBlank()) resumeRun(runId)
                }

                AgentRuntimeWire.MSG_UPDATE_MODEL -> {
                    val data = msg.data
                    if (data == null) {
                        AndroidAgentLogger.warnThrottled("runtime_update_model_missing_data") {
                            "Agent runtime ignored model update without payload"
                        }
                        return
                    }
                    val request = runCatching { AgentRuntimeWire.runRequestFromBundle(data) }
                        .getOrElse { throwable ->
                            AndroidAgentLogger.warnThrottled("runtime_update_model_invalid") {
                                "Agent runtime rejected invalid model update: type=${throwable.safeLogType()}"
                            }
                            return
                        }
                    updateRunModel(request.runId, request.config)
                }

                AgentRuntimeWire.MSG_ACK_RESULT -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    // 客户端已经取走并确认了结果：checkpoint 再留着没有任何用途，
                    // 只会让 runtime_inflight_runs 越积越多（真机上装一次包就多一条）。
                    dispatchResultIo {
                        AgentRuntimeResultStore.remove(this@AgentRuntimeService, runId)
                        runCatching { AgentRunCheckpointStore.remove(this@AgentRuntimeService, runId) }
                    }
                }

                AgentRuntimeWire.MSG_READ_CONTEXT_RESULT -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    val owner = msg.data.getString("context_owner").orEmpty()
                    val replyTo = msg.replyTo
                    dispatchResultIo {
                        val target = AgentRuntimeResultStore.readOwned(this@AgentRuntimeService, runId, owner)
                        sendResultNow(replyTo, target?.result ?: AgentRuntimeWire.RunResult(
                            runId, false, "", "完整运行结果不可用", contextSnapshotRef = runId,
                        ))
                    }
                }

                AgentRuntimeWire.MSG_DRAIN_RESULTS -> {
                    sendDrainedResults(msg.replyTo, msg.data?.getBoolean("complete_result_refs") == true)
                }

                AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN -> {
                    sendActiveRun(
                        msg.replyTo,
                        msg.data?.let(AgentRuntimeWire::runIdFromBundle).orEmpty(),
                    )
                }

                AgentRuntimeWire.MSG_QUERY_ACTIVE_RUNS -> sendActiveRuns(msg.replyTo)

                AgentRuntimeWire.MSG_ATTACH_RUN -> {
                    attachRun(
                        runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return),
                        replyTo = msg.replyTo,
                    )
                }
            }
        }
    }

    private fun ingestRunRequest(
        incoming: AgentRuntimeWire.IncomingRunRequest,
        replyTo: Messenger?,
    ) {
        // 多会话并发：并发的启动请求各自准备，不再用「新请求替换旧请求」。
        val generation = startRequestCounter.incrementAndGet()
        val pending = PendingStartRequest(generation, incoming, replyTo)
        pendingStartRequests[generation] = pending
        thread(name = "agent-runtime-image-ingest") {
            val ingestStartedAt = android.os.SystemClock.elapsedRealtime()
            val prepared = runCatching {
                val request = AgentRuntimeImageTransfer.materialize(incoming)
                if (!AgentRuntimeRequestConfigResolver.requiresRuntimeConfig(request)) {
                    request
                } else {
                    val runtimeConfig = runBlocking {
                        val identity = AgentRuntimeRequestConfigResolver.oauthIdentity(request)
                        if (identity == null) {
                            RuntimeConfigRepository.currentRuntimeConfig()
                        } else {
                            // 会话绑定的订阅身份优先：全局默认切走不该让旧会话失效。
                            // 只有它确实不可用（连接停用/删除、模型下架）时才回落到默认，
                            // 由 applyRuntimeConfig 的身份校验报出可理解的错误。
                            RuntimeConfigRepository.oauthRuntimeConfigFor(
                                identity.providerId,
                                identity.authMode,
                                identity.model,
                            ) ?: RuntimeConfigRepository.currentRuntimeConfig()
                        }
                    } ?: throw RuntimeConfigUnavailableException()
                    AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request, runtimeConfig)
                }
            }
            // 与客户端 `request history:` 是同一笔账的两半：客户端编码+落盘，服务端读回+解析。
            // 只对成功准备的请求打；失败时下游已有 warnThrottled，不在这里重复报（§32.30）。
            prepared.getOrNull()?.let { request ->
                val historyChars = request.history.sumOf {
                    it.content.length + it.contentJson.length + it.reasoningContent.length + it.toolCallsJson.length
                }
                // 同样落盘：这条要与客户端两行在同一份文件里才能拼成一次请求的完整账（§32.31）。
                AndroidAgentLogger.infoPersisted(
                    "run request: ingest=${android.os.SystemClock.elapsedRealtime() - ingestStartedAt}ms " +
                        "history=${request.history.size} chars=$historyChars"
                )
            }
            mainHandler.post {
                if (pendingStartRequests.remove(generation) !== pending) {
                    // 被 onDestroy / cancelRun 消费，或服务已停止；客户端会另收失败结果。
                    AndroidAgentLogger.warn(
                        "Agent run request dropped: gen=$generation pending=${pendingStartRequests[generation] === pending} " +
                            "ok=${prepared.isSuccess}"
                    )
                    return@post
                }
                sendRequestIngestedTo(replyTo, incoming.request.runId)
                AndroidAgentLogger.info(
                    "Agent run prepared: id=${incoming.request.runId.take(8)} ok=${prepared.isSuccess}"
                )
                prepared.fold(
                    onSuccess = { request ->
                        val permissions = AgentRuntimePolicy.permissions(
                            Prefs.localAgentPreferences()
                        )
                        startRun(
                            request.copy(
                                config = AgentRuntimePolicy.constrain(request.config, permissions),
                            ),
                            replyTo,
                        )
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("runtime_request_prepare_failed") {
                            "Agent runtime request preparation failed: type=${throwable.safeLogType()}"
                        }
                        finishWithFailure(
                            when (throwable) {
                                is AgentRuntimeImageTransfer.ImageTransferException ->
                                    throwable.message ?: "Agent Runtime 无法读取图片"
                                is RuntimeConfigUnavailableException ->
                                    "请先在 Eta 中配置可用的模型"
                                else -> "Agent Runtime 无法准备请求"
                            },
                            replyTo,
                        )
                    },
                )
            }
        }
    }

    /**
     * 同一会话只允许一个活跃 run。
     *
     * 为什么需要：客户端判断「是否运行中」用的是本地状态，一旦它与服务端脱节（进程被回收、
     * 恢复没接上、或停止没收到终态），用户的下一条消息就会以「新消息」而不是「插话」发出去，
     * 于是同一会话里同时跑起两个 run。两个 run 各自写消息、各自抢浮层，用户看到的就是
     * 「会话永远不结束，得自己点停止」。
     *
     * 以新 run 为准：取消同会话里更早的活跃 run；不同会话的并发不受影响。
     */
    private fun supersedeSameConversationRuns(request: AgentRuntimeWire.RunRequest) {
        val conversationId = conversationIdOf(request) ?: return
        runConversationIds[request.runId] = conversationId
        val active = sessions.values.associate { it.runId to runConversationIds[it.runId] }
        val terminal = sessions.values
            .filter { it.isTerminal }
            .mapTo(mutableSetOf()) { it.runId }
        AgentRunExclusivityPolicy.supersededRuns(
            incomingRunId = request.runId,
            incomingConversationId = conversationId,
            active = active,
            terminal = terminal,
        ).forEach { staleRunId ->
            AndroidAgentLogger.warn(
                "Agent runtime same-conversation run superseded: old=${staleRunId.take(8)} " +
                    "new=${request.runId.take(8)}"
            )
            cancelRun(staleRunId)
        }
    }

    /** 这次 run 属于哪个会话（只认 App 内发起的 handoff）。 */
    private fun conversationIdOf(request: AgentRuntimeWire.RunRequest): String? =
        request.handoff
            ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { handoff ->
                runCatching { AgentUiHandoffPayload.from(handoff.payload).conversationId }.getOrNull()
            }
            ?.takeIf { it.isNotBlank() }

    private fun startRun(
        request: AgentRuntimeWire.RunRequest,
        replyTo: Messenger? = null,
    ) {
        supersedeSameConversationRuns(request)
        // 多会话并发：不取消其它会话的已有 run；新 run 只接管浮层（primary）。
        // 新一轮运行会让旧结果卡片失去意义：用户可能没点关闭就继续输入，
        // 旧卡片若留在屏幕上，会在整个新任务期间继续盖住底部并显示过期状态。
        removeResultCard()
        val session = AgentRuntimeSession(
            runId = request.runId,
            operation = request.operation,
            eventSink = { event -> sendEventTo(replyTo, event) },
            resultSink = { result -> sendResultTo(replyTo, result) },
        )
        // Root 入口保留原有绑定服务生命周期；新增 FGS 不能成为厂商后台入口的新前置权限。
        val allowBoundFallback = RootAccess.isGranted
        val executionHeld = AgentExecutionService.acquire(
            this, "run:${request.runId}", allowBoundFallback = allowBoundFallback,
            // 通知栏的暂停/补充只认这个裸 runId；终端会话的租约不参与。
            agentRunId = request.runId,
        ) { session.controller.cancel() }
        AndroidAgentLogger.info(
            "Agent run dispatch: id=${request.runId.take(8)} foreground=$executionHeld rootFallback=$allowBoundFallback"
        )
        if (!executionHeld && !allowBoundFallback) {
            session.complete(AgentRuntimeWire.RunResult(
                runId = request.runId, ok = false, content = "",
                error = "无法启动后台执行服务，请返回 Eta 后重试",
            )) {}
            return
        }
        sessions[request.runId] = session
        acquireRuntimeWakeLock()
        primaryRunId = request.runId
        synchronized(completedContextsLock) { completedContexts.remove(request.runId) }
        runCatching {
            startService(Intent(this, AgentRuntimeService::class.java).setAction(ACTION_KEEP_ALIVE))
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_keep_alive_start_failed") {
                "Agent runtime keep-alive start failed: type=${throwable.safeLogType()}"
            }
        }
        mainHandler.removeCallbacksAndMessages(hideToken)
        state.value = AgentOverlayState.Initial
        capsuleExpanded.value = false
        setCapsuleWindowHeight(CAPSULE_COLLAPSED_HEIGHT_DP)
        hasExecutedForegroundTool = false
        overlayRevealed = false
        val openTarget = request.openTarget()
        runOpenTargets[request.runId] = openTarget
        runHandoffSources[request.runId] = request.handoff?.source.orEmpty()
        resultConversationId = openTarget.conversationId
        resultArchive = openTarget.archiveSource?.let { source ->
            openTarget.archiveKey?.let { key -> source to key }
        }
        val runSupplements = RunSupplements()
        if (request.handoff?.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
            val payload = AgentUiHandoffPayload.from(request.handoff.payload)
            runSupplements.items += payload.supplements
            runSupplements.nextIndex = payload.lastSupplementIndex + 1
        }
        supplementsByRun[request.runId] = runSupplements

        thread(name = "agent-runtime") {
            try {
                executeRun(session, request)
            } finally {
                refreshRunStatus()
                AgentExecutionService.release("run:${request.runId}")
            }
        }
    }

    private fun AgentRuntimeWire.RunRequest.openTarget(): RunOpenTarget {
        val handoff = handoff ?: return RunOpenTarget()
        return if (handoff.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
            RunOpenTarget(
                conversationId = AgentUiHandoffPayload.from(handoff.payload)
                    .conversationId
                    .takeIf(String::isNotBlank),
            )
        } else {
            RunOpenTarget(
                archiveSource = handoff.source,
                archiveKey = AgentExternalArchivePayload.from(handoff.payload)
                    ?.conversationKey
                    ?.takeIf(String::isNotBlank),
            )
        }
    }

    /** 结果卡片指向自己那条 run 的会话；后台 run 的完成不应改变卡片的落点。 */
    private fun applyOpenTarget(target: RunOpenTarget?) {
        resultConversationId = target?.conversationId
        resultArchive = target?.archiveSource?.let { source ->
            target.archiveKey?.let { key -> source to key }
        }
    }

    private fun executeRun(
        session: AgentRuntimeSession,
        request: AgentRuntimeWire.RunRequest,
    ) {
        val outcome = AgentRuntimeRunExecutor(
            context = this,
            currentPermissions = ::currentRuntimePermissions,
            snapshotRequest = { it.withRunSupplements() },
            onAcceptedEvent = { event, entrySurfaceGuard ->
                handleAcceptedRunEvent(session, event, entrySurfaceGuard)
            },
            persistArtifacts = ::persistRunArtifacts,
            guiControl = guiControl,
        ).execute(session, request)
        if (!outcome.shouldUpdateHost) return
        postTerminalOverlay(
            session = session,
            result = outcome.result,
            entrySurfaceGuard = outcome.entrySurfaceGuard,
            completedContext = outcome.response?.let { completedResponse ->
                outcome.completedRequest?.let { completedRequest ->
                    CompletedRunContext(
                        request = completedRequest,
                        response = completedResponse,
                    )
                }
            },
        )
    }

    private fun handleAcceptedRunEvent(
        session: AgentRuntimeSession,
        event: AgentEvent,
        entrySurfaceGuard: EntrySurfaceGuard?,
    ) {
        if (sessions[session.runId] !== session) return
        // 并发 run 中只有 primary 更新浮层；其它 run 的事件仍由 session 自己的 eventSink 发给客户端。
        if (primaryRunId != session.runId) return
        val revealsForegroundOperation = AgentOverlayVisibilityPolicy.shouldRevealFor(event)
        val requiresEntrySurfaceDismissal =
            AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(event)
        val entrySurfaceReady = if (requiresEntrySurfaceDismissal && entrySurfaceGuard != null) {
            runCatching { entrySurfaceGuard.dismissOnce() }.getOrDefault(false)
        } else {
            true
        }
        mainHandler.post {
            if (sessions[session.runId] !== session || primaryRunId != session.runId) return@post
            if (
                AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(
                    event,
                    entrySurfaceReady,
                )
            ) {
                hasExecutedForegroundTool = true
            }
            if (session.isTerminal) return@post
            runCatching {
                state.value = state.value.applyEvent(event)
                if (revealsForegroundOperation && entrySurfaceReady) {
                    // 两个浮层都可以被用户单独关掉，因此“首次露脸”不能用 capsuleView 是否为空来判断。
                    if (!overlayRevealed) {
                        overlayRevealed = true
                        AgentHapticFeedback.perform(
                            this,
                            AgentHapticFeedback.Type.RUN_STARTED,
                        )
                    }
                    ensureOverlayVisible()
                }
            }.onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_overlay_event_failed") {
                    "Agent runtime overlay event failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    private fun persistRunArtifacts(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult,
        events: List<AgentEvent>,
    ) {
        // outbox 是终态与在途 checkpoint 之间的提交点；失败时保留 checkpoint 供下次恢复。
        persistCompletedRun(request, result)
        runCatching { persistArchivedRun(request, result, events) }
            .onFailure { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime archive persistence failed: type=${throwable.safeLogType()}"
                )
            }
    }

    /**
     * 后台 run 完成时，如果 App 不在前台，发一条可点击的通知。
     *
     * 为什么只在后台发：用户就在 App 里时，会话列表已经有运行圆点和结果；
     * 再弹通知只会打扰。App 退到后台时用户看不到任何变化，补一条通知才不多不少。
     */
    private fun notifyBackgroundRunFinished(
        session: AgentRuntimeSession,
        result: AgentRuntimeWire.RunResult,
        openTarget: RunOpenTarget? = null,
    ) {
        if (AppVisibility.foreground.value) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(
                    BACKGROUND_RUN_CHANNEL,
                    getString(R.string.background_run_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val titleRes = when {
            result.ok -> R.string.background_run_finished
            result.error == "已停止" -> R.string.background_run_stopped
            else -> R.string.background_run_failed
        }
        val title = getString(titleRes)
        val body = result.content.trim().ifBlank { result.error.orEmpty() }.take(120).ifBlank { title }
        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply {
                // 通知必须带上「这条 run 属于哪个会话」：否则点开只是回到 App 当前页面，
                // 用户在后台跑了两个任务时根本分不清哪条完成了。
                when {
                    openTarget?.conversationId != null -> {
                        action = MainActivity.ACTION_OPEN_CONVERSATION_ID
                        putExtra(MainActivity.EXTRA_CONVERSATION_ID, openTarget.conversationId)
                    }
                    openTarget?.archiveSource != null && openTarget.archiveKey != null -> {
                        action = MainActivity.ACTION_OPEN_ARCHIVED_CONVERSATION
                        putExtra(MainActivity.EXTRA_HANDOFF_SOURCE, openTarget.archiveSource)
                        putExtra(MainActivity.EXTRA_CONVERSATION_KEY, openTarget.archiveKey)
                    }
                }
            }
        val pendingIntent = PendingIntent.getActivity(
            this,
            session.runId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, BACKGROUND_RUN_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        runCatching { manager.notify(session.runId.hashCode(), notification) }
    }

    private fun acquireRuntimeWakeLock() {
        val manager = getSystemService(PowerManager::class.java) ?: return
        val lock = runtimeWakeLock ?: manager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, RUNTIME_WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
            .also { runtimeWakeLock = it }
        runCatching { lock.acquire(RUNTIME_WAKE_LOCK_TIMEOUT_MS) }
            .onFailure { failure ->
                AndroidAgentLogger.warn("Runtime wake lock failed: type=${failure.safeLogType()}")
            }
        mainHandler.removeCallbacks(runtimeWakeLockRenew)
        mainHandler.postDelayed(runtimeWakeLockRenew, RUNTIME_WAKE_LOCK_RENEW_MS)
    }

    private fun releaseRuntimeWakeLockIfIdle() {
        if (sessions.values.any { !it.isTerminal }) return
        releaseRuntimeWakeLock()
    }

    private fun releaseRuntimeWakeLock() {
        mainHandler.removeCallbacks(runtimeWakeLockRenew)
        runtimeWakeLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
    }

    private fun postTerminalOverlay(
        session: AgentRuntimeSession,
        result: AgentRuntimeWire.RunResult,
        entrySurfaceGuard: EntrySurfaceGuard?,
        completedContext: CompletedRunContext? = null,
    ) {
        mainHandler.post {
            val openTarget = runOpenTargets.remove(session.runId)
            runHandoffSources.remove(session.runId)
            runConversationIds.remove(session.runId)
            if (sessions[session.runId] !== session) return@post
            sessions.remove(session.runId)
            // run 已进入终态：checkpoint 只服务于「进程被杀后恢复」，终态之后留着它
            // 只会让 runtime_inflight_runs 只增不减（真机上积了 3 条：09:11/09:14/09:16），
            // 下次启动的恢复逻辑还要对着一堆旧日志做无用功。结果本身已经落在
            // runtime_results 里，客户端取的是那份，不依赖这里的重放日志。
            dispatchResultIo {
                runCatching { AgentRunCheckpointStore.remove(this@AgentRuntimeService, session.runId) }
                    .onFailure { failure ->
                        AndroidAgentLogger.warnThrottled("runtime_checkpoint_cleanup_failed") {
                            "Agent runtime checkpoint cleanup failed: type=${failure.safeLogType()}"
                        }
                    }
            }
            releaseRuntimeWakeLockIfIdle()
            supplementsByRun.remove(session.runId)
            val showOverlay = primaryRunId == session.runId || primaryRunId == null
            if (primaryRunId == session.runId) primaryRunId = null
            rememberCompletedContext(session.runId, completedContext)
            // 有 primary 时，后台 run 不抢浮层（结果已交给对应客户端）；没有 primary 时，
            // 最后完成的 run 可以补一张结果卡，避免后台跑完毫无提示。
            if (!showOverlay) {
                notifyBackgroundRunFinished(session, result, openTarget)
                return@post
            }
            applyOpenTarget(openTarget)
            // App 已经不在前台时，用户既看不到会话里的结果，也点不到浮层：
            // 这里补一条可点击的通知，点开直接回到这次 run 的会话。
            // 只在后台补，前台仍然只显示结果卡，避免同一件事提示两次。
            if (!AppVisibility.foreground.value) {
                notifyBackgroundRunFinished(session, result, openTarget)
                return@post
            }
            runCatching {
                if (result.ok) {
                    enterFinalState(
                        state.value.copy(
                            phase = AgentOverlayPhase.FINISHED,
                            status = AgentOverlayStatus.ResultReady,
                            detailText = result.content.trim().ifBlank { state.value.detailText },
                        ),
                        keepVisible = entrySurfaceGuard?.wasTriggered == true,
                    )
                } else {
                    enterFinalState(
                        AgentOverlayState(
                            phase = AgentOverlayPhase.FAILED,
                            status = if (result.error == "已停止") {
                                AgentOverlayStatus.Stopped
                            } else {
                                AgentOverlayStatus.RunFailed
                            },
                            detailText = result.error.orEmpty(),
                        ),
                        keepVisible = entrySurfaceGuard?.wasTriggered == true,
                    )
                }
            }.onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_terminal_overlay_failed") {
                    "Agent runtime terminal overlay failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    private fun sendEventTo(
        target: Messenger?,
        event: AgentEvent,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_EVENT)
            msg.data = AgentRuntimeWire.eventToBundle(event)
            target?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_event_delivery_failed") {
                "Agent runtime event delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun dispatchResultIo(block: () -> Unit) {
        try {
            resultIo.execute {
                try { block() } catch (failure: Exception) {
                    AndroidAgentLogger.warnThrottled("runtime_result_io_failed") {
                        "Agent runtime result I/O failed: type=${failure.safeLogType()}"
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            AndroidAgentLogger.info("Agent runtime result delivery deferred after service stop")
        }
    }

    private fun sendResultTo(target: Messenger?, result: AgentRuntimeWire.RunResult) {
        val queuedAt = SystemClock.elapsedRealtime()
        dispatchResultIo {
            // resultIo 是单线程：等待时间能直接看出前面有没有别的回传在堵着（§32.26）。
            sendResultNow(target, result, SystemClock.elapsedRealtime() - queuedAt)
        }
    }

    private fun sendResultNow(
        target: Messenger?,
        result: AgentRuntimeWire.RunResult,
        waitMs: Long = 0,
    ) {
        runCatching {
            val startedAt = SystemClock.elapsedRealtime()
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_RESULT)
            msg.data = AgentRuntimeWire.toBundle(result, cacheDir)
            logResultDeliveryCost(waitMs, SystemClock.elapsedRealtime() - startedAt, result)
            AgentWireText.send(target, msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_result_delivery_failed") {
                "Agent runtime result delivery failed: type=${throwable.safeLogType()}"
            }
            // 不把传输失败伪装成已交付终态；引用使新客户端保留 outbox，等待完整恢复。
            val fallback = AgentRuntimeWire.RunResult(result.runId, false, "",
                "完整结果传输失败，已保存的历史未删除。请重新打开会话恢复。",
                contextSnapshotRef = result.runId, operation = result.operation)
            try {
                target?.send(Message.obtain(null, AgentRuntimeWire.MSG_RESULT).apply {
                    data = AgentRuntimeWire.toBundle(fallback)
                })
            } catch (deliveryFailure: Exception) {
                AndroidAgentLogger.warnThrottled("runtime_result_failure_notice_undelivered") {
                    "Agent runtime result notice undelivered: type=${deliveryFailure.safeLogType()}"
                }
            }
        }
    }

    /**
     * 每轮回传只记一行：等待（resultIo 是单线程，前面有活就会排队）+ 构造（两次编码）+ 规模。
     * 长对话卡不卡，以后看这一行就够了，不用再 SIGQUIT 抓栈（§32.26）。
     */
    private fun logResultDeliveryCost(
        waitMs: Long,
        buildMs: Long,
        result: AgentRuntimeWire.RunResult,
    ) {
        val chars = result.transcript.sumOf {
            it.content.length + it.reasoningContent.length + it.contentJson.length + it.toolCallsJson.length
        }
        AndroidAgentLogger.info(
            "result delivery: wait=${waitMs}ms build=${buildMs}ms messages=${result.transcript.size} chars=$chars",
        )
    }

    private fun sendRequestIngestedTo(
        target: Messenger?,
        runId: String,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_REQUEST_INGESTED)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            target?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_ingest_ack_failed") {
                "Agent runtime ingest acknowledgement failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendDrainedResults(replyTo: Messenger?, referencesOnly: Boolean) {
        dispatchResultIo { sendDrainedResultsNow(replyTo, referencesOnly) }
    }

    private fun sendDrainedResultsNow(replyTo: Messenger?, referencesOnly: Boolean) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_DRAIN_RESULTS_RESPONSE)
            msg.data = AgentRuntimeWire.completedRunsToBundle(
                if (referencesOnly) AgentRuntimeResultStore.pendingPage(this) else AgentRuntimeResultStore.list(this).take(8)
            )
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_drain_results_failed") {
                "Agent runtime drain results failed: type=${throwable.safeLogType()}"
            }
        }
    }

    /**
     * 多会话并发时只回 primary 不够：后台会话没有 primary，界面就无法把它标成运行中。
     * 这里一次性返回所有非终态 run（含会话 ID、操作与暂停态），由 App 侧逐条对齐。
     */
    private fun sendActiveRuns(replyTo: Messenger?) {
        runCatching {
            val runs = sessions.values
                .filter { !it.isTerminal }
                .map { session ->
                    AgentRuntimeWire.ActiveRunSnapshot(
                        runId = session.runId,
                        conversationId = runOpenTargets[session.runId]?.conversationId.orEmpty(),
                        operation = session.operation,
                        handoffSource = runHandoffSources[session.runId].orEmpty(),
                        paused = session.controller.isPaused,
                    )
                }
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_QUERY_ACTIVE_RUNS_RESPONSE)
            msg.data = AgentRuntimeWire.activeRunsToBundle(runs)
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_active_runs_delivery_failed") {
                "Agent runtime active runs delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendActiveRun(replyTo: Messenger?, requestedRunId: String = "") {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN_RESPONSE)
            // 指定了 runId 就只回答「这个 run 是否活跃」：它已经结束时返回空串，
            // 不能回退成 primary —— 否则客户端会把自己这一轮误判成「另一个仍在跑的 run」。
            val runId = if (requestedRunId.isNotBlank()) {
                requestedRunId.takeIf(sessions::containsKey).orEmpty()
            } else {
                primaryRunId.orEmpty()
            }
            val paused = runId.isNotBlank() && sessions[runId]?.controller?.isPaused == true
            // primary 为空只说明 UI 没有主 run：后台会话可能仍在执行。
            // 恢复逻辑靠这个总数判定「能不能算中断」，只回 primary 会让后台会话被误杀。
            val runningCount = sessions.values.count { !it.isTerminal }
            msg.data = AgentRuntimeWire.activeRunBundle(runId, paused, runningCount)
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_active_run_delivery_failed") {
                "Agent runtime active run delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun attachRun(runId: String, replyTo: Messenger?) {
        val session = sessions[runId]
        val attached = replyTo != null &&
            runId.isNotBlank() &&
            session?.runId == runId &&
            session.attach(
                eventSink = { event -> sendEventTo(replyTo, event) },
                resultSink = { result -> sendResultTo(replyTo, result) },
                onReplayComplete = { sendAttachRunResponse(runId, replyTo, attached = true) },
            )
        if (!attached) sendAttachRunResponse(runId, replyTo, attached = false)
    }

    private fun sendAttachRunResponse(runId: String, replyTo: Messenger?, attached: Boolean) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_ATTACH_RUN_RESPONSE)
            msg.data = AgentRuntimeWire.attachRunResponseBundle(runId, attached)
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_attach_run_delivery_failed") {
                "Agent runtime attach response failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun persistCompletedRun(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult
    ) {
        val handoff = request.handoff ?: return
        AgentRuntimeResultStore.add(
            this,
            AgentRuntimeWire.CompletedRun(
                handoff = handoff,
                result = result,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private fun persistArchivedRun(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult,
        events: List<AgentEvent>
    ) {
        val handoff = request.handoff ?: return
        AgentExternalArchivePayload.from(handoff.payload) ?: return
        val userImagePreviews = if (
            handoff.source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE
        ) {
            request.images
                .asSequence()
                .take(MAX_ARCHIVED_USER_IMAGE_PREVIEWS)
                .mapNotNull { image ->
                    AgentImageCodec.previewFromReference(this, image)?.reference
                }
                .toList()
        } else {
            emptyList()
        }
        AgentRunArchiveStore.add(
            this,
            AgentRunArchiveStore.ArchivedRun(
                handoff = handoff,
                events = events,
                result = result,
                createdAt = System.currentTimeMillis(),
                userImagePreviews = userImagePreviews,
            )
        )
    }

    private fun rememberCompletedContext(runId: String, context: CompletedRunContext?) {
        if (context == null) return
        synchronized(completedContextsLock) {
            completedContexts.remove(runId)
            completedContexts[runId] = context
            while (completedContexts.size > 8) {
                val oldest = completedContexts.keys.firstOrNull() ?: break
                completedContexts.remove(oldest)
            }
        }
    }

    private fun hasCompletedContext(runId: String): Boolean =
        synchronized(completedContextsLock) { completedContexts.containsKey(runId) }

    private fun completedContext(runId: String): CompletedRunContext? =
        synchronized(completedContextsLock) { completedContexts[runId] }

    private fun latestCompletedContext(): CompletedRunContext? =
        synchronized(completedContextsLock) { completedContexts.values.lastOrNull() }

    /** 执行通知/状态栏只跟随 primary；其它并发 run 仍由前台服务租约计数体现。 */
    private fun refreshRunStatus() {
        val primary = activeSession
        AgentExecutionService.updateRunStatus(
            this,
            state.value.status.takeIf { primary != null && !primary.isTerminal },
            activeRunCount = if (primary != null && !primary.isTerminal) 1 else 0,
        )
    }

    private fun finishWithFailure(
        message: String,
        replyTo: Messenger? = null,
    ) {
        sendResultTo(
            replyTo,
            AgentRuntimeWire.RunResult(runId = "", ok = false, content = "", error = message),
        )
        if (sessions.isNotEmpty()) return
        enterFinalState(
            AgentOverlayState(
                phase = AgentOverlayPhase.FAILED,
                status = AgentOverlayStatus.RunFailed,
                detailText = message
            )
        )
    }

    private fun requestStop() {
        // 胶囊只代表 primary：停止按钮就只停这一个 run。多会话并发下把其它会话的
        // 后台 run 一起杀掉，是用户在当前界面看不到的破坏（与 fix109 的 per-run 控制一致）。
        val primary = primaryRunId
        if (primary != null) {
            val isPrimaryPending = pendingStartRequests.values.any { it.incoming.request.runId == primary }
            if (sessions.containsKey(primary) || isPrimaryPending) {
                cancelRun(primary)
                return
            }
        }
        // 没有 primary（浮层已收起）时才回退到原来的行为：把仍在跑的 run 全部停掉，
        // 避免「点了停止却什么都没发生」。
        val pending = pendingStartRequests.values.toList()
        val running = sessions.keys.toList()
        if (pending.isEmpty() && running.isEmpty()) {
            dismissAndStop()
            return
        }
        pending.forEach { cancelRun(it.incoming.request.runId) }
        running.forEach(::cancelRun)
    }

    private fun cancelRun(runId: String) {
        if (runId.isBlank()) return
        pendingStartRequests.values.firstOrNull { it.incoming.request.runId == runId }?.let { pending ->
            // 还没派发的 run 也记一行：它没有 session、不是 primary，不打日志的话
            // 「点了停止但什么都没发生」在 logcat 上完全看不到痕迹。
            AndroidAgentLogger.info("Agent run stop requested: id=${runId.take(8)} state=pending")
            pendingStartRequests.remove(pending.generation)
            pending.incoming.close()
            sendRequestIngestedTo(pending.replyTo, runId)
            sendResultTo(
                pending.replyTo,
                AgentRuntimeWire.RunResult(
                    runId = runId,
                    ok = false,
                    content = "",
                    error = "已停止",
                ),
            )
            return
        }
        val session = sessions[runId]
        if (session == null) {
            AndroidAgentLogger.info("Agent run stop ignored: id=${runId.take(8)} reason=session_missing")
            return
        }
        if (session.isTerminal) {
            AndroidAgentLogger.info("Agent run stop ignored: id=${runId.take(8)} reason=terminal")
            return
        }
        // 「requested」必须打在真正动手之前：否则 cancel() 一旦抛错或阻塞，
        // 这一行就永远不出现，事后分不清「没收到停止请求」和「收到了但停不下来」。
        AndroidAgentLogger.info(
            "Agent run stop requested: id=${runId.take(8)} state=running primary=${primaryRunId == runId}",
        )
        session.controller.cancel()
        if (primaryRunId == runId) {
            state.value = state.value.copy(status = AgentOverlayStatus.Stopping)
        }
    }

    private fun pauseRun(runId: String) {
        val session = sessions[runId]
        if (session == null) {
            AndroidAgentLogger.info("Agent run pause ignored: id=${runId.take(8)} reason=session_missing")
            return
        }
        if (session.isTerminal) {
            AndroidAgentLogger.info("Agent run pause ignored: id=${runId.take(8)} reason=terminal")
            return
        }
        // 持有 GUI 锁的 run 交给协调器暂停：它要等当前动作真正结束，再把状态置成 UserPaused
        // 并推进代号 —— 这样恢复之后，暂停前拿到的观察快照自动作废，模型必须重新 observe_screen。
        // 没持有锁的 run（纯文本推理）不需要这套，直接 pause 即可。
        val guiLocked = guiControl.pauseForUser(runId)
        if (!guiLocked) session.controller.pause()
        AndroidAgentLogger.info(
            "Agent run paused: id=${runId.take(8)} gui_lock=$guiLocked primary=${primaryRunId == runId}",
        )
        if (primaryRunId == runId) {
            state.value = state.value.copy(
                phase = AgentOverlayPhase.PAUSED,
                status = AgentOverlayStatus.Paused,
            )
        }
    }

    private fun resumeRun(runId: String) {
        val session = sessions[runId]
        if (session == null) {
            AndroidAgentLogger.info("Agent run resume ignored: id=${runId.take(8)} reason=session_missing")
            return
        }
        if (session.isTerminal) {
            AndroidAgentLogger.info("Agent run resume ignored: id=${runId.take(8)} reason=terminal")
            return
        }
        val guiLocked = guiControl.resumeForUser(runId)
        if (!guiLocked) session.controller.resume()
        AndroidAgentLogger.info(
            "Agent run resumed: id=${runId.take(8)} gui_lock=$guiLocked primary=${primaryRunId == runId}",
        )
        if (primaryRunId == runId) {
            state.value = state.value.copy(
                phase = AgentOverlayPhase.RUNNING,
                status = AgentOverlayStatus.Continuing,
            )
        }
    }

    private fun requestPause() {
        primaryRunId?.let(::pauseRun)
    }

    /**
     * 运行中热切换模型配置。
     *
     * 只改这个 run 的后续模型回合；不重建 session、不发新请求、不动已经跑完的工具结果。
     * 日志里把「从哪个模型切到哪个模型」写清楚，否则事后排查「为什么这轮回答风格变了」没有依据。
     */
    private fun updateRunModel(runId: String, config: AgentModelClient.ModelConfig) {
        val session = sessions[runId] ?: run {
            AndroidAgentLogger.warnThrottled("runtime_update_model_unknown_run") {
                "Agent runtime model update ignored: run not found"
            }
            return
        }
        if (!session.updateModel(config)) {
            AndroidAgentLogger.warnThrottled("runtime_update_model_not_running") {
                "Agent runtime model update ignored: run is not running"
            }
            return
        }
        AndroidAgentLogger.info(
            "agent_model_hot_swapped run=$runId model=${config.model} " +
                "effort=${config.effectiveReasoningEffort.wireValue} provider=${config.providerId}"
        )
        // 追踪里也记一条：只靠 meta 里的启动模型，事后分不清「从第几轮开始换了模型」。
        AgentRunTraceStore.recordModelChange(
            runId = runId,
            model = config.model,
            provider = config.providerId,
            effort = config.effectiveReasoningEffort.wireValue,
        )
    }

    private fun requestResume() {
        primaryRunId?.let(::resumeRun)
    }

    private fun requestSupplement(text: String, expectedRunId: String? = null): Boolean {
        val supplementText = text.trim()
        if (supplementText.isBlank()) return false
        // 入口层指定了目标 run 时先核对：run 已经换人（用户发了新任务）就丢弃这条补充，
        // 否则会把本该给上一轮的指令塞进新任务。
        val targetRunId = expectedRunId?.takeIf { it.isNotBlank() } ?: primaryRunId
        if (!expectedRunId.isNullOrBlank() &&
            !sessions.containsKey(expectedRunId) &&
            !hasCompletedContext(expectedRunId)
        ) {
            AndroidAgentLogger.warn("Agent runtime supplement dropped: run changed")
            return false
        }
        setCapsuleInputMode(focusable = false)
        targetRunId?.let(sessions::get)?.let { session ->
            val event = session.steer(supplementText) {
                recordSupplementEvent(session.runId, supplementText)
            }
            if (event == null) {
                if (!session.isTerminal) {
                    if (primaryRunId == session.runId) {
                        state.value = state.value.copy(
                            status = AgentOverlayStatus.Finishing,
                        )
                    }
                    return false
                }
            } else {
                AndroidAgentLogger.info(
                    "Agent runtime supplement received: index=${event.index}, chars=${event.text.length}"
                )
                if (primaryRunId == session.runId) {
                    state.value = state.value.applyEvent(event)
                }
                return true
            }
        }

        val completed = if (!expectedRunId.isNullOrBlank()) {
            completedContext(expectedRunId) ?: return false
        } else {
            latestCompletedContext() ?: return false
        }
        if (completed.request.operation != AgentRuntimeWire.OP_CHAT ||
            completed.request.handoff?.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
            state.value = state.value.copy(status = AgentOverlayStatus.ContinuationUnavailable)
            return false
        }
        val continuationRequest = AgentContinuationBuilder.build(
            request = completed.request,
            response = completed.response,
            supplement = supplementText,
        )
        startRun(continuationRequest)
        return true
    }

    private fun recordSupplementEvent(runId: String, text: String): AgentEvent.UserSupplementReceived {
        val run = supplementsByRun[runId]
        if (run == null) {
            return AgentEvent.UserSupplementReceived(index = 0, text = text)
        }
        val supplement = synchronized(run.lock) {
            AgentUiHandoffPayload.Supplement(
                index = run.nextIndex++,
                text = text,
                createdAt = System.currentTimeMillis(),
            ).also { run.items += it }
        }
        return AgentEvent.UserSupplementReceived(
            index = supplement.index,
            text = supplement.text,
        )
    }

    private fun ensureOverlayVisible() {
        if (!overlayCapsuleEnabled() && !overlayGlowEnabled()) {
            // 两个浮层都关掉：撤掉已有窗口即可，任务继续在后台跑（停止入口在执行通知里）。
            // 结果卡片不属于“思考输出”，仍按原有逻辑在任务结束后展示。
            removeAmbientWindows()
            return
        }
        showOverlay()
    }

    /** 思考胶囊是否展示；读本地 Agent 配置，默认开启。 */
    private fun overlayCapsuleEnabled(): Boolean =
        overlayPreferenceEnabled(Prefs.Keys.AGENT_OVERLAY_CAPSULE_ENABLED)

    /** 屏幕边缘流光是否展示；它不遮挡内容，只提示 Agent 正在操作前台界面，默认开启。 */
    private fun overlayGlowEnabled(): Boolean {
        val preferences = Prefs.localAgentPreferences() ?: return true
        val glowKey = Prefs.Keys.AGENT_OVERLAY_GLOW_ENABLED
        val capsuleKey = Prefs.Keys.AGENT_OVERLAY_CAPSULE_ENABLED
        val glowConfigured = runCatching { preferences.contains(glowKey) }.getOrDefault(false)
        val glowValue = runCatching {
            preferences.getBoolean(glowKey, Prefs.Keys.BOOLEAN_DEFAULTS.getValue(glowKey))
        }.getOrDefault(true)
        val capsuleConfigured = runCatching { preferences.contains(capsuleKey) }.getOrDefault(false)
        val capsuleValue = runCatching {
            preferences.getBoolean(capsuleKey, Prefs.Keys.BOOLEAN_DEFAULTS.getValue(capsuleKey))
        }.getOrDefault(true)
        // 旧版只有一个开关，关掉它对两个浮层都生效；升级兼容规则见 AgentOverlayPreferencePolicy。
        return AgentOverlayPreferencePolicy.glowEnabled(
            glowConfigured = glowConfigured,
            glowValue = glowValue,
            capsuleConfigured = capsuleConfigured,
            capsuleValue = capsuleValue,
        )
    }

    private fun overlayPreferenceEnabled(key: String): Boolean {
        val preferences = Prefs.localAgentPreferences() ?: return true
        return runCatching {
            preferences.getBoolean(key, Prefs.Keys.BOOLEAN_DEFAULTS.getValue(key))
        }.getOrDefault(true)
    }

    /**
     * 设置页在任务运行中改开关也要立刻生效：关掉就撤窗口（任务继续在后台跑），
     * 重新打开时若仍有 run 在执行就补回浮层，不必等下一次工具事件。
     */
    private fun registerOverlayPreferenceListener() {
        val preferences = Prefs.localAgentPreferences() ?: return
        if (overlayPreferencesListener != null) return
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != Prefs.Keys.AGENT_OVERLAY_CAPSULE_ENABLED &&
                key != Prefs.Keys.AGENT_OVERLAY_GLOW_ENABLED
            ) {
                return@OnSharedPreferenceChangeListener
            }
            mainHandler.post { applyOverlayVisibilityPreference() }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        overlayPreferencesListener = listener
    }

    private fun unregisterOverlayPreferenceListener() {
        val listener = overlayPreferencesListener ?: return
        overlayPreferencesListener = null
        runCatching { Prefs.localAgentPreferences()?.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun applyOverlayVisibilityPreference() {
        if (!overlayCapsuleEnabled()) removeCapsuleWindow()
        if (!overlayGlowEnabled()) removeGlowWindow()
        if (!overlayCapsuleEnabled() && !overlayGlowEnabled()) return
        val session = activeSession ?: return
        if (session.isTerminal) return
        showOverlay()
    }

    /** 胶囊展开后的「隐藏胶囊」：写入胶囊开关后立即撤掉胶囊，边缘流光（若开启）仍提示任务在运行。 */
    private fun hideOverlayFromCapsule() {
        // 同时把“流光”的当前值显式落库：旧版只有一个开关，从未写过流光键时
        // 兼容规则会拿胶囊的值当流光的值——那样这里刚写 false，流光也会跟着消失，
        // 而提示文案说的是“边缘流光仍提示运行中”，等于文案在说假话。
        val glowNow = overlayGlowEnabled()
        Prefs.localAgentPreferences()
            ?.edit()
            ?.putBoolean(Prefs.Keys.AGENT_OVERLAY_CAPSULE_ENABLED, false)
            ?.putBoolean(Prefs.Keys.AGENT_OVERLAY_GLOW_ENABLED, glowNow)
            ?.apply()
        removeCapsuleWindow()
        Toast.makeText(this, R.string.overlay_hidden_hint, Toast.LENGTH_LONG).show()
    }

    private fun showOverlay() {
        val capsuleEnabled = overlayCapsuleEnabled()
        val glowEnabled = overlayGlowEnabled()
        if (!capsuleEnabled && !glowEnabled) return
        val needGlow = glowEnabled && glowView == null
        val needCapsule = capsuleEnabled && capsuleView == null
        if (!needGlow && !needCapsule) return
        // 设置在运行中改掉时，后续每个“需要露脸”的事件都会重新判断；关掉后不再新建窗口。
        // TYPE_ACCESSIBILITY_OVERLAY 免 SYSTEM_ALERT_WINDOW 权限；仅回退态（无障碍未启用）才需检查
        if (AgentAccessibilityService.current() == null && !Settings.canDrawOverlays(this)) return
        val wm = overlayContext().getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        if (needGlow) {
            // ── 氛围光窗口：全屏触摸穿透，彩虹光圈，截图时被 takeScreenshotOfWindow 过滤 ─
            val glow = createOverlayComposeView {
                AgentOverlayGlow(
                    state = state.value,
                    corners = screenCornerRadii(),
                    virtualDisplay = virtualDisplayState.value,
                )
            }
            val glowLp = glowLayoutParams()
            runCatching { wm.addView(glow, glowLp) }
                .onSuccess {
                    glowView = glow
                    glowParams = glowLp
                }
                .onFailure { throwable ->
                    AndroidAgentLogger.warnThrottled("runtime_glow_add_view_failed") {
                        "Agent runtime glow addView failed: type=${throwable.safeLogType()}"
                    }
                }
        }

        if (needCapsule) {
            // ── 状态胶囊窗口：状态栏下方居中，收起时一句状态，点击展开控制 ─────
            val capsule = createOverlayComposeView {
                AgentOverlayCapsule(
                    state = state.value,
                    expanded = capsuleExpanded.value,
                    yielded = capsuleYielded.value,
                    onToggleExpanded = ::toggleCapsule,
                    onCollapsedSettled = ::onCapsuleCollapsedSettled,
                    onPause = { requestPause() },
                    onResume = { requestResume() },
                    onStop = { requestStop() },
                    onSupplementModeChange = ::setCapsuleInputMode,
                    onSupplement = { text -> requestSupplement(text) },
                    onHide = ::hideOverlayFromCapsule,
                    virtualDisplay = virtualDisplayState.value,
                    onTakeOverVirtualDisplay = {
                        VirtualDisplayController.setUserControlling(true)
                        requestPause()
                    },
                    onResumeVirtualDisplay = {
                        VirtualDisplayController.setUserControlling(false)
                        requestResume()
                    },
                    onFullscreenVirtualDisplay = ::requestFullscreenVirtualDisplay,
                    onTapVirtualCanvas = { nx, ny, longPress ->
                        VirtualDisplayController.dispatchUserTapOnCanvas(nx, ny, longPress)
                    },
                    onSwipeVirtualCanvas = { sx, sy, ex, ey, durationMs ->
                        VirtualDisplayController.dispatchUserSwipeOnCanvas(sx, sy, ex, ey, durationMs)
                    },
                )
            }
            val capsuleLp = capsuleLayoutParams()
            runCatching { wm.addView(capsule, capsuleLp) }
                .onSuccess {
                    capsuleView = capsule
                    capsuleParams = capsuleLp
                }
                .onFailure { throwable ->
                    AndroidAgentLogger.warnThrottled("runtime_capsule_add_view_failed") {
                        "Agent runtime capsule addView failed: type=${throwable.safeLogType()}"
                    }
                }
        }
    }

    /**
     * 让路时同步切换触摸穿透：标志位立刻生效保证手势不被截走，视觉过渡交给胶囊内的动画。
     * 恢复时先淡入再收回触摸，两者顺序无关紧要，因为恢复发生在手势结束之后。
     */
    private fun setCapsuleYielded(yielded: Boolean) {
        if (!yielded) mainHandler.removeCallbacks(restoreCapsule)
        capsuleYielded.value = yielded
        val wm = windowManager ?: return
        val capsule = capsuleView ?: return
        val lp = capsuleParams ?: return
        val nextFlags = if (yielded) {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (lp.flags == nextFlags) return
        lp.flags = nextFlags
        runCatching { wm.updateViewLayout(capsule, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_clearance_failed") {
                "Agent runtime capsule clearance update failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun toggleCapsule() {
        val expand = !capsuleExpanded.value
        // 先把窗口扩到展开档再开始动画；收起由胶囊在动画结束后回调 onCollapsedSettled 缩窗。
        if (expand) updateCapsuleWindowBounds(true)
        capsuleExpanded.value = expand
    }

    private fun onCapsuleCollapsedSettled() {
        if (!capsuleExpanded.value) updateCapsuleWindowBounds(false)
    }

    /** 展开/收起时的高宽档位：虚拟屏激活时收起为微缩镜宽度、展开为装裱视窗尺寸。 */
    private fun updateCapsuleWindowBounds(expanding: Boolean = capsuleExpanded.value) {
        val virtual = virtualDisplayState.value.active
        val heightDp = when {
            expanding && virtual -> CAPSULE_STAGE_HEIGHT_DP
            expanding -> CAPSULE_CONTROLS_HEIGHT_DP
            else -> CAPSULE_COLLAPSED_HEIGHT_DP
        }
        setCapsuleWindowHeight(heightDp)
    }

    private fun setCapsuleWindowHeight(heightDp: Int) {
        val wm = windowManager ?: return
        val capsule = capsuleView ?: return
        val lp = capsuleParams ?: return
        val widthDp = when {
            !virtualDisplayState.value.active -> CAPSULE_WINDOW_WIDTH_DP
            heightDp >= CAPSULE_CONTROLS_HEIGHT_DP -> CAPSULE_STAGE_WINDOW_WIDTH_DP
            else -> CAPSULE_MICRO_WINDOW_WIDTH_DP
        }
        val height = dpToPx(heightDp)
        val width = dpToPx(widthDp)
        if (lp.height == height && lp.width == width) return
        lp.width = width
        lp.height = height
        runCatching { wm.updateViewLayout(capsule, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_resize_failed") {
                "Agent runtime capsule resize failed: type=${throwable.safeLogType()}"
            }
        }
    }

    /** 把后台虚拟屏的画面整屏投射到物理主屏（胶囊展开面板里的「全屏」）。 */
    private fun requestFullscreenVirtualDisplay() {
        capsuleExpanded.value = false
        VirtualDisplayController.setStageExpanded(false)
        thread(name = "eta-vd-fullscreen-handoff", isDaemon = true) {
            VirtualDisplayController.handoffToMainDisplay(this@AgentRuntimeService)
        }
    }

    private fun showResultCard() {
        if (resultCardView != null) return
        // 结果卡片不依赖运行浮层开关：用户关掉思考胶囊后，任务结束仍然应该看到结果。
        if (AgentAccessibilityService.current() == null && !Settings.canDrawOverlays(this)) return
        val wm = windowManager
            ?: (overlayContext().getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?: return
        windowManager = wm
        val card = createOverlayComposeView {
            AgentResultCard(
                state = state.value,
                onClose = ::dismissAndStop,
                onOpenEta = ::openEtaFromResult,
                hasVirtualDisplayApp = virtualDisplayState.value.active &&
                    virtualDisplayState.value.packageName.isNotBlank(),
                onOpenVirtualApp = {
                    thread(name = "eta-vd-result-handoff", isDaemon = true) {
                        VirtualDisplayController.handoffToMainDisplay(this@AgentRuntimeService)
                        mainHandler.post { removeResultCard() }
                    }
                },
            )
        }
        val lp = resultCardLayoutParams()
        runCatching { wm.addView(card, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_result_card_add_view_failed") {
                "Agent runtime result card addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        resultCardView = card
        resultCardParams = lp
        registerResultCardBack(card)
        card.requestFocus()
    }

    /**
     * 结果卡片是任务结束后的停留界面，系统返回应先关闭它，而不是退回底下的应用。
     * 窗口需可获焦才能收到返回；卡片外仍是 FLAG_NOT_TOUCH_MODAL，触摸照常穿透到下层。
     */
    /**
     * 回到 Eta 本体并定位到本次任务的会话。卡片窗口可见时属于前台可见的用户操作，
     * 允许从服务启动 Activity；启动失败时保留卡片，不让用户丢失结果。
     */
    private fun openEtaFromResult() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply {
                val conversationId = resultConversationId
                val archive = resultArchive
                when {
                    conversationId != null -> {
                        action = MainActivity.ACTION_OPEN_CONVERSATION_ID
                        putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
                    }
                    archive != null -> {
                        action = MainActivity.ACTION_OPEN_ARCHIVED_CONVERSATION
                        putExtra(MainActivity.EXTRA_HANDOFF_SOURCE, archive.first)
                        putExtra(MainActivity.EXTRA_CONVERSATION_KEY, archive.second)
                    }
                }
            }
        val options = ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                pendingIntentBackgroundActivityStartMode = if (Build.VERSION.SDK_INT >= 36) {
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                } else {
                    @Suppress("DEPRECATION")
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
            }
        }
        val launched = runCatching {
            PendingIntent.getActivity(
                this, RESULT_OPEN_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ).send(this, 0, null, null, null, null, options.toBundle())
        }
        if (launched.isFailure) {
            AndroidAgentLogger.warn("Agent runtime result open failed: type=${launched.exceptionOrNull()?.safeLogType()}")
            return
        }
        dismissAndStop()
    }

    private fun registerResultCardBack(view: View) {
        unregisterResultCardBack()
        val dispatcher = view.findOnBackInvokedDispatcher() ?: run {
            AndroidAgentLogger.warn("Agent runtime result card back dispatcher unavailable")
            return
        }
        val callback = OnBackInvokedCallback(::dismissAndStop)
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        resultCardBack = dispatcher to callback
    }

    private fun unregisterResultCardBack() {
        val (dispatcher, callback) = resultCardBack ?: return
        resultCardBack = null
        runCatching { dispatcher.unregisterOnBackInvokedCallback(callback) }
    }

    private fun createOverlayComposeView(content: @Composable () -> Unit): ComposeView =
        ComposeView(overlayContext()).apply {
            setViewTreeLifecycleOwner(this@AgentRuntimeService)
            setViewTreeSavedStateRegistryOwner(this@AgentRuntimeService)
            setContent {
                val appearance by AppearanceSettingsRepository.settingsFlow()
                    .collectAsState(initial = AppearanceSettings())
                AgentAppTheme(
                    appearance = appearance,
                    applyInterfaceScale = false,
                ) {
                    // 部分 ROM 会给 TYPE_ACCESSIBILITY_OVERLAY 分配软件 Canvas；Miuix 与 COUI 的
                    // RuntimeShader 只检查系统版本，因此系统浮层统一使用无 RuntimeShader 的圆角回退。
                    CompositionLocalProvider(
                        LocalSquircleEnabled provides false,
                        LocalCouiContourGlowEnabled provides false,
                    ) {
                        content()
                    }
                }
            }
        }

    /**
     * 胶囊窗口使用固定宽度与分档高度，动画只在窗口内进行。
     * WRAP_CONTENT 会让每一帧的尺寸变化都触发 WindowManager 重新布局整个窗口，这是卡顿的来源；
     * 但窗口内透明区域同样会吞掉触摸（包括 Agent 自己注入的点击），所以高度只在需要时扩大：
     * 展开前先扩到目标档，收起动画结束后再缩回，见 [setCapsuleWindowHeight]。
     */
    private fun capsuleLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            dpToPx(CAPSULE_WINDOW_WIDTH_DP),
            dpToPx(CAPSULE_COLLAPSED_HEIGHT_DP),
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 贴在状态栏下方居中：远离多数应用的主要点击区，也和系统胶囊、流体云的位置习惯一致。
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeightPx()
            windowAnimations = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }

    private fun statusBarHeightPx(): Int {
        val metrics = (overlayContext().getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?.currentWindowMetrics ?: return dpToPx(28)
        return metrics.windowInsets
            .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout())
            .top
            .takeIf { it > 0 } ?: dpToPx(28)
    }

    private fun resultCardLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            resultCardWindowHeightPx(),
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 半屏底部居中，窗口外触摸穿透
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

    private fun overlayType(): Int =
        // 无障碍服务可用时用 TYPE_ACCESSIBILITY_OVERLAY（免 SYSTEM_ALERT_WINDOW 权限，且截图
        // filterValidWindows 可过滤）；需用无障碍服务 context 创建，否则 BadTokenException
        if (AgentAccessibilityService.current() != null)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun overlayContext(): Context =
        AgentAccessibilityService.current() ?: this

    @Suppress("DEPRECATION")
    private fun glowLayoutParams(): WindowManager.LayoutParams {
        // 真实屏幕高度（含状态栏 + 导航栏），MATCH_PARENT 在部分设备不含系统栏
        val realHeight = runCatching {
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealSize(point)
            point.y
        }.getOrDefault(WindowManager.LayoutParams.MATCH_PARENT)
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            realHeight,
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 全屏覆盖（含状态栏/导航栏），触摸穿透不拦截页面操作；
            // TYPE_ACCESSIBILITY_OVERLAY 让 takeScreenshotOfWindow 过滤掉，对 Agent 透明
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // 非应用窗口默认按系统栏与挖孔收缩窗口框，光带画出的圆角就会整体下移，和物理屏幕角错位。
            // 显式不避让任何 inset，窗口原点才与屏幕原点重合，Display 上报的圆角半径才对得上。
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    /**
     * 屏幕四角的物理圆角半径。光效窗口覆盖整屏，直接取 Display 上报的值；
     * 部分 ROM 不上报时用 0，兜底按常见大圆角机型估一个保守值，宁可略小也不让光带越出屏幕。
     */
    private fun screenCornerRadii(): ScreenCornerRadii {
        val display = (overlayContext().getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val fallback = dpToPx(FALLBACK_SCREEN_CORNER_DP).toFloat()
        fun radius(position: Int): Float =
            display?.getRoundedCorner(position)?.radius?.takeIf { it > 0 }?.toFloat() ?: fallback
        return ScreenCornerRadii(
            topLeft = radius(android.view.RoundedCorner.POSITION_TOP_LEFT),
            topRight = radius(android.view.RoundedCorner.POSITION_TOP_RIGHT),
            bottomRight = radius(android.view.RoundedCorner.POSITION_BOTTOM_RIGHT),
            bottomLeft = radius(android.view.RoundedCorner.POSITION_BOTTOM_LEFT),
        )
    }

    private fun setCapsuleInputMode(focusable: Boolean) {
        setCapsuleWindowHeight(
            if (focusable) CAPSULE_SUPPLEMENT_HEIGHT_DP
            else if (capsuleExpanded.value) CAPSULE_CONTROLS_HEIGHT_DP
            else CAPSULE_COLLAPSED_HEIGHT_DP,
        )
        val wm = windowManager ?: return
        val capsule = capsuleView ?: return
        val lp = capsuleParams ?: return
        val nextFlags = if (focusable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (lp.flags == nextFlags) return
        lp.flags = nextFlags
        runCatching { wm.updateViewLayout(capsule, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_focus_update_failed") {
                "Agent runtime capsule focus update failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun resultCardWindowHeightPx(): Int =
        (resources.displayMetrics.heightPixels * RESULT_CARD_HEIGHT_RATIO).toInt()

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun enterFinalState(finalState: AgentOverlayState, keepVisible: Boolean = false) {
        state.value = finalState

        if (hasExecutedForegroundTool) {
            // 撤掉胶囊和边缘光，改显半屏结果卡片，不自动关闭，用户手动关闭
            capsuleExpanded.value = false
            removeAmbientWindows()
            showResultCard()
            mainHandler.removeCallbacksAndMessages(hideToken)
        } else {
            dismissAndStop()
        }
    }

    private fun removeAmbientWindows() {
        removeCapsuleWindow()
        removeGlowWindow()
    }

    private fun removeCapsuleWindow() {
        mainHandler.removeCallbacks(restoreCapsule)
        capsuleYielded.value = false
        capsuleExpanded.value = false
        capsuleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        capsuleView = null
        capsuleParams = null
    }

    private fun removeGlowWindow() {
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView = null
        glowParams = null
    }

    private fun removeResultCard() {
        unregisterResultCardBack()
        resultCardView?.let { view -> runCatching { windowManager?.removeView(view) } }
        resultCardView = null
        resultCardParams = null
    }

    private fun dismissAndStop() {
        mainHandler.removeCallbacks(restoreCapsule)
        capsuleYielded.value = false
        removeResultCard()
        // 还有 run 在执行时不能停服务，也不能拆当前运行的胶囊/光效：
        // stopSelf 会走到 onDestroy 取消 activeSession。典型触发是用户关掉上一轮的结果卡片、
        // 紧接着又发了新消息 —— 那一下会把新 run 直接掐掉。
        if (sessions.isNotEmpty() || pendingStartRequests.isNotEmpty()) {
            AndroidAgentLogger.warn("Agent runtime stop skipped: active run still executing")
            return
        }
        capsuleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        capsuleView = null
        glowView = null
        capsuleParams = null
        glowParams = null
        windowManager = null
        stopSelf()
    }

    private fun isMessageSenderAllowed(msg: Message): Boolean {
        val uid = msg.sendingUid
        if (uid == Process.myUid()) return true
        val packages = runCatching {
            packageManager.getPackagesForUid(uid)
        }.getOrNull().orEmpty()
        // 允许本应用包名（Canary/正式包并存时，入口与 Runtime 可能不在同一 uid）。
        return packages.any { it == packageName || it in ModuleConfig.AGENT_RUNTIME_ENTRY_PACKAGES }
    }

    private fun isNightMode(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun currentRuntimePermissions(): AgentRuntimePolicy.Permissions =
        AgentRuntimePolicy.permissions(
            Prefs.localAgentPreferences()
        )

    private fun AgentRuntimeWire.RunRequest.withRunSupplements(): AgentRuntimeWire.RunRequest {
        val handoff = handoff ?: return this
        if (handoff.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) return this
        val supplements = supplementsByRun[runId]?.let { run ->
            synchronized(run.lock) { run.items.toList() }
        }.orEmpty()
        if (supplements.isEmpty()) return this
        val payload = AgentUiHandoffPayload.from(handoff.payload).copy(
            supplements = supplements,
        )
        return copy(
            handoff = handoff.copy(payload = payload.toJson())
        )
    }

    private companion object {
        /** 与 AgentExecutionService 同量级：10 分钟续期、60 分钟兜底。 */
        private const val RUNTIME_WAKE_LOCK_TAG = "eta:agent-runtime"
        private const val RUNTIME_WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1000L
        private const val RUNTIME_WAKE_LOCK_RENEW_MS = 10L * 60L * 1000L
        const val ACTION_KEEP_ALIVE = "io.github.mangi.eta.agent.runtime.KEEP_ALIVE"
        const val RESULT_CARD_HEIGHT_RATIO = 0.5f
        private const val RESULT_OPEN_REQUEST_CODE = 1108
        /** 后台 run 完成通知；与执行通知分开，避免影响前台服务通知的生命周期。 */
        private const val BACKGROUND_RUN_CHANNEL = "eta_background_runs"
        private const val CAPSULE_WINDOW_WIDTH_DP = 272
        private const val CAPSULE_MICRO_WINDOW_WIDTH_DP = 290
        private const val CAPSULE_STAGE_WINDOW_WIDTH_DP = 324
        private const val CAPSULE_STAGE_HEIGHT_DP = 524
        private const val CAPSULE_COLLAPSED_HEIGHT_DP = 68
        private const val CAPSULE_CONTROLS_HEIGHT_DP = 124
        private const val CAPSULE_SUPPLEMENT_HEIGHT_DP = 260
        /** 注入点击只是一个点，目标控件常贴着胶囊边缘，判定范围向外多留一点。 */
        private const val CAPSULE_CLEARANCE_SLOP_DP = 8
        private const val FALLBACK_SCREEN_CORNER_DP = 28
        const val MAX_ARCHIVED_USER_IMAGE_PREVIEWS = 4
    }

    private data class CompletedRunContext(
        val request: AgentRuntimeWire.RunRequest,
        val response: AgentModelClient.ModelResponse.Text,
    )

    private class RuntimeConfigUnavailableException : IllegalStateException()
}
