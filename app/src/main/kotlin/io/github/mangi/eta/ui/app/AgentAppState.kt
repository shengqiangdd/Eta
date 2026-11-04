package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentContextSnapshot

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.text.format.DateFormat
import android.widget.Toast
import java.io.File
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import io.github.mangi.eta.BuildConfig
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.AgentFileReferenceGateway
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.runtime.BackgroundResumeStore
import io.github.mangi.eta.agent.terminal.BackgroundAutoResumeWatcher
import io.github.mangi.eta.agent.terminal.BackgroundResumeMemory
import io.github.mangi.eta.agent.terminal.DetachedTask
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.AgentFileReferencePolicy
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.CharacterMacros
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.RoleplayMessageLink
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.repository.CharacterRepository
import io.github.mangi.eta.data.repository.ConversationHistoryExporter
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.runtime.AgentExternalArchivePayload
import io.github.mangi.eta.agent.runtime.AgentRunArchiveStore
import io.github.mangi.eta.agent.runtime.AgentRunCheckpointStore
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AppExitCause
import io.github.mangi.eta.agent.runtime.AppExitReporter
import io.github.mangi.eta.agent.runtime.AgentUiHandoffPayload
import io.github.mangi.eta.agent.runtime.GuiControlStatus
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.backup.AutoBackupOutcome
import io.github.mangi.eta.data.backup.AutoBackupRepository
import io.github.mangi.eta.data.repository.EtaBackupRepository
import io.github.mangi.eta.data.repository.EtaBackupSummary
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.ConversationHistoryLoadState
import io.github.mangi.eta.ui.model.ConversationModeUi
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.model.MessageEditUiState
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.QueuedMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.contentMatches
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class AgentAppState(
    context: Context,
    private val scope: CoroutineScope,
    initialConversations: AgentConversationStore.Snapshot? = null,
) {
    /**
     * 启动快照。
     *
     * 传了就同步用（测试与备份导入路径），不传就先拿空快照把界面画出来，
     * 真正加载放到后台 —— 见 [init] 里的说明。
     */
    private val bootSnapshot = initialConversations ?: AgentConversationStore.Snapshot.empty()
    private val appContext = context.applicationContext
    // 保屏唤醒锁归状态层持有：Compose 在后台会停止重组，靠 UI 副作用释放会一直拖到用户回前台。
    private val screenWakeLock = AgentScreenWakeLock(appContext)
    private var keepScreenEnabled = agentBooleanForUi(Prefs.Keys.AGENT_KEEP_SCREEN_ON)
    /** 长任务运行期间的保屏续期循环；不再需要保屏时取消。 */
    private var keepScreenRenewJob: Job? = null
    private val runConversationIds = mutableMapOf<String, String>()
    private val runMessageProjector = AgentRunMessageProjector()
    private val runEventCoalescer = AgentRunEventCoalescer()
    private val runEventFlushJobs = mutableMapOf<String, Job>()
    /**
     * 批量回放（启动恢复 / checkpoint 重放）期间抑制逐事件重算会话摘要。
     * 一次回放可能有上千个事件，逐次重算会让主线程在启动时卡住甚至 ANR。
     */
    private var replayTraceBatchDepth = 0
    private var currentRunId: String? = null
    private var currentRunJob: Job? = null

    /**
     * 后台任务结束后自动续跑的分发器（注册给 [BackgroundAutoResumeWatcher]）。
     *
     * 单独存一份引用：Activity 重建时新旧实例会先后 install —— 旧实例注销时只能清自己，
     * 不能把新实例刚注册的分发器一并清掉。
     */
    private val backgroundResumeDispatcher =
        BackgroundAutoResumeWatcher.BackgroundResumeDispatcher { scopeId, task ->
            resumeConversationAfterBackgroundTask(scopeId, task)
        }

    /**
     * 为一个**不是当前正在看**的会话发起续跑。
     *
     * 与 `sendCurrentMessage` 的差别：全程按 conversationId 操作，不碰输入框、草稿、
     * 当前选中会话与编辑态 —— 那些都是“用户正在这个会话里打字”才存在的概念。
     *
     * 返回 false 表示这一刻不能发（会话不在内存里 / 正在跑 / 消息还没载入），
     * 调用方（watcher）不记「已续」，下个 tick 再试。
     */
    internal suspend fun resumeConversationAfterBackgroundTask(conversationId: String, task: DetachedTask): Boolean {
        if (conversationId.isBlank()) return false
        var state = conversationsById[conversationId] ?: return false
        // 同一会话同时只能有一个 run：正在跑就别插队（等它跑完 watcher 会再来）。
        if (state.isStreaming) return false
        // 消息被释放过（用户切走后释内存）时先载回来：history 不完整就发，模型看到的是残缺上下文。
        // 这一步代价是真实的（大会话要读一大段 JSON），所以只在真的要续跑时才做。
        if (state.messagesDeferred) {
            if (!ensureMessagesLoaded(listOf(conversationId))) return false
            state = conversationsById[conversationId] ?: return false
            if (state.isStreaming || state.messagesDeferred) return false
        }
        if (conversationId.isReadOnlyExternalArchiveConversation()) return false
        val prompt = io.github.mangi.eta.agent.terminal.backgroundResumePrompt(
            appContext.getString(R.string.background_tasks_resume_prompt),
            task,
        ).trim()
        if (prompt.isBlank()) return false
        val runId = "run-${java.util.UUID.randomUUID()}"
        val userMessage = UserMessageUi(id = "user-$runId", content = prompt)
        val userHistoryMessage = AgentModelClient.buildUserHistoryMessage(
            text = prompt,
            images = emptyList(),
        ).copy(messageId = userMessage.id)
        launchConversationRun(
            conversationId = conversationId,
            runId = runId,
            prompt = prompt,
            images = emptyList(),
            history = state.history,
            userHistoryMessage = userHistoryMessage,
            messages = state.messages + userMessage,
            state = state.copy(input = "", pendingImages = emptyList(), pendingFileReferences = emptyList()),
            reasoningEffort = state.reasoningEffort,
            // 用户正在看的会话：这就是 primary；否则只变更那个会话的状态，
            // 不能把当前会话的「停止」按钮指向一条它看不到的 run。
            asPrimary = conversationId == selectedConversationId,
            // 这一轮不是用户发起的：不能把自动续跑链清零，否则上限形同虚设。
            automatic = true,
        )
        return true
    }

    /**
     * 用户在场（发了一条消息）：这条自动续跑链到此为止。
     *
     * 走的是与 watcher 同一个作用域对象，`resetChain()` 内部先读盘再清零 ——
     * 未加载就写盘会把磁盘上的「续过哪些」「水位线」一起抹成空。
     * 写失败会经 `BackgroundResumeMemory.onPersistFailed` 留一条 warn（第十七批）。
     */
    private fun resetBackgroundResumeChain(conversationId: String) {
        if (conversationId.isBlank()) return
        val resumeScope = BackgroundResumeMemory.scopeFor(
            BackgroundResumeStore.forApp(appContext),
            conversationId,
        )
        scope.launch(Dispatchers.Main.immediate) {
            runCatching {
                // 与 watcher 的 runOnce 拿同一把锁：不串行的话，两边都可能先读旧水位线、后写盘，
                // 后写者把新值盖回去，重启后已处理的任务会被重放。
                BackgroundResumeMemory.mutexFor(conversationId).withLock { resumeScope.resetChain() }
            }
        }
    }

    /** Activity 结束时注销分发器；进程级 watcher 本身继续存活。 */
    internal fun detachBackgroundResume() {
        BackgroundAutoResumeWatcher.uninstall(backgroundResumeDispatcher)
    }
    private val persistenceLock = Any()
    private var persistenceJob: Job? = null
    // 导入期间暂停保存：旧状态的增量保存会删除刚导入的会话；导入成功后整体重载，失败时数据库已回滚。
    private var persistencePaused = false

    /**
     * 会话落盘是否失败。
     *
     * 失败原本只写日志 —— 用户侧的表现是「切会话 / 重启后消息不见了」，
     * 而界面上没有任何提示，看起来像数据凭空消失。这个标记让失败能被说出来。
     */
    var persistenceFailed by mutableStateOf(false)
        private set

    /**
     * 会话还没从库里载入完时收到的分享内容。
     *
     * 不能直接落库：`applyLoadedSnapshot` 会用库里的整体状态替换内存，此时新建的会话
     * 会被一起冲掉（用户看不到任何痕迹）。先挂在这里，载入完成后再真正处理。
     */
    private var pendingSharedContent: SharedContentPlan? = null

    /**
     * 会话列表还没载入完时请求打开的目标会话（通知 / 外部深链）。
     *
     * 不能直接丢掉：冷启动时 `conversationsById` 还是空的，`selectConversation` 找不到会话，
     * 而调用方通常已经把这个一次性请求消费掉了 —— 结果就是「点了通知只打开 App，没进对话」。
     */
    private var pendingConversationSelection: String? = null

    private val runtimeRecoveryInProgress = AtomicBoolean(false)
    private val defaultThinkingEnabled = agentBooleanForUi(Prefs.Keys.AGENT_THINKING_ENABLED)
    @Volatile
    private var conversationPersistence = AgentConversationPersistence(bootSnapshot)
    /** 已经在排队载入消息的会话，避免重复发起（见 loadDeferredMessages）。 */
    // 用 Compose 状态装：切到大会话时要立刻显示「正在载入」，而不是先闪一屏空会话。
    private var deferredMessageLoads by mutableStateOf<Set<String>>(emptySet())
    private val deferredHistoryLoads = mutableSetOf<String>()
    private val deferredJournalLoads = mutableSetOf<String>()
    /** 会话消息尚未载入时先记下要接的事件流，等载入完成再接（否则轨迹会写进空表）。 */
    private val pendingAttachRuns = linkedMapOf<String, String>()
    /** 切走之后延迟释放其它会话的消息内存（见 scheduleMessageRelease）。 */
    private var messageReleaseJob: Job? = null
    private var currentReasoningCapabilities: ModelReasoningCapabilities? = null
    private var providers: List<ProviderSetting> = emptyList()
    private var defaultModelId: String? = null
    private var defaultProviderId: String? = null
    private var fileAttachmentOwnerVersion = 0L

    private var selectedConversationId: String? = bootSnapshot.selectedConversationId
    /** 会话是否已经从库里载入过；深链 / 分享要等它为 true 才能落地。 */
    var conversationsLoaded by mutableStateOf(initialConversations != null)
        private set
    private var conversationsById: Map<String, AgentChatHomeUiState> = bootSnapshot.conversationsById

    /**
     * 草稿的权威副本（纯内存，不参与 Compose 快照）。
     *
     * updateDraft 每个字符都会写这里，但**不写 homeState** —— 否则输入一个字就把整段聊天区
     * 重新组合一次（本机大会话上万条消息，这是可见的卡顿）。
     * 代价是 homeState.input 会滞后：任何 `homeState.copy(...)` 再落回会话时都可能把草稿抹成旧值，
     * 所以 updateConversation 统一用这张表校正 input。
     */
    private val draftsByConversation = HashMap<String, String>()

    /**
     * `ask_user` 的回答：消息已经落成、还没起新 run（见 [answerPendingAsk]）。
     *
     * 它不能和排队消息混在一起 —— 回答不是「等你做完这句再说」，而是「我在回答你问的问题」。
     */
    private val pendingAskAnswers = PendingAskAnswers()
    private var conversationTitles: Map<String, String> = bootSnapshot.titles
    private var conversationUpdatedAt: Map<String, Long> = bootSnapshot.updatedAt
    /** 每个会话的消息条数；来自 SQL 统计，未载入消息的会话也有值（见 Snapshot.messageCounts）。 */
    private var conversationMessageCounts: Map<String, Int> = bootSnapshot.messageCounts

    var homeState by mutableStateOf(
        selectedConversationId?.let(conversationsById::get) ?: emptyChatState(defaultThinkingEnabled)
    )
        private set

    /** 是否有会话正在运行；保屏等全局判断用它，而非只反映选中会话的 homeState。 */
    var anyRunningConversation by mutableStateOf(false)
        private set

    /**
     * 是否有会话仍在推进（运行且未暂停）。
     *
     * 暂停中的 run 不会再产生新内容，保屏只跟随「还会变化」的 run；否则用户暂停任务后
     * 屏幕会继续常亮，白白耗电。
     */
    var anyRunningUnpausedConversation by mutableStateOf(false)
        private set

    private fun refreshAnyRunningConversation() {
        anyRunningConversation = conversationsById.values.any { it.isStreaming }
        anyRunningUnpausedConversation = conversationsById.values.any { it.isStreaming && !it.isPaused }
        applyKeepScreenLock()
    }

    /** 设置页的保屏开关变化时由界面层同步进来；开关关掉要立刻释放唤醒锁。 */
    fun updateKeepScreenEnabled(enabled: Boolean) {
        keepScreenEnabled = enabled
        applyKeepScreenLock()
    }

    private fun applyKeepScreenLock() {
        val runningUnpaused = conversationsById.values.count { it.isStreaming && !it.isPaused }
        val shouldHold = keepScreenEnabled && runningUnpaused > 0
        screenWakeLock.setHeld(shouldHold)
        AgentKeepScreenStatus.publish(keepScreenEnabled, shouldHold, runningUnpaused)
        if (shouldHold) startKeepScreenRenew() else stopKeepScreenRenew()
    }

    /**
     * 12 小时上限只是防泄漏兜底：长任务里锁会被系统到期释放，而状态不再变化时也没有新的刷新，
     * 结果就是「任务还在跑，屏幕却开始按系统超时熄灭」。运行期间每 30 分钟续期一次。
     */
    private fun startKeepScreenRenew() {
        if (keepScreenRenewJob?.isActive == true) return
        keepScreenRenewJob = scope.launch {
            while (true) {
                delay(KEEP_SCREEN_RENEW_INTERVAL_MS)
                if (!keepScreenEnabled || !anyRunningUnpausedConversation) break
                screenWakeLock.renew()
            }
        }
    }

    private fun stopKeepScreenRenew() {
        keepScreenRenewJob?.cancel()
        keepScreenRenewJob = null
    }

    var modelPickerState by mutableStateOf(AgentModelPickerUiState())
        private set

    /**
     * 上一次进程被系统终止的性质（非 [AppExitCause.NONE] 时聊天页顶部给一条可关闭的结论）。
     *
     * 进程被 SIGKILL 时没有任何回调，启动后只有系统记录能说明原因；不告诉用户，
     * 他看到的就只是「会话突然断了」，会误判成 Eta 自己的缺陷。
     */
    var lastExitCause by mutableStateOf(AppExitCause.NONE)
        private set

    var conversationPaneState by mutableStateOf(
        ConversationPaneUiState(
            conversations = emptyList(),
            selectedConversationId = selectedConversationId,
            searchQuery = "",
        )
    )
        private set

    init {
        if (BuildConfig.DEBUG) {
            // 真机验证用的注入点：只清本地运行态、不停服务端 run（复现失配，见 docs §21.3）。
            // release 构建里 BuildConfig.DEBUG 是编译期 false，这段会被裁掉。
            AgentDebugInjections.forgetLocalRunState = { conversationId ->
                debugForgetLocalRunState(conversationId)
            }
        }
        // 冷启动就先把“开着但当前没有任务”的真实状态发布给设置页：
        // 以前只有“运行状态变化”才会发布，冷启动且没跑过任务时设置页会错报“已关闭”。
        applyKeepScreenLock()
        // 进程级自动续跑：任务结束不再依赖「卡片那张组合恰好活着」。
        // 分发器就是这个状态对象：只有它知道会话历史、模型与会话级预算。
        BackgroundAutoResumeWatcher.install(backgroundResumeDispatcher)
        refreshConversationSummaries()
        observeRuntimeSelection()
        observeAppVisibility()
        detectLastExitCause()
        runtimeRecoveryInProgress.set(true)
        scope.launch(Dispatchers.IO) {
            try {
                // 启动加载曾经是主线程上的 runBlocking（AgentAppState 构造默认参数），
                // 真机实测主线程在这里等 ~700ms：会话 journal 5.7M 字符 + 2054 条消息，
                // 首帧被拖到 +1236ms。现在先空状态出首帧，这里后台加载完再整体替换。
                // 顺序不能反：下面的对账逻辑依赖已加载的会话状态。
                if (initialConversations == null) {
                    // 加载期间暂停保存：此时内存是空状态，任何增量保存都会把库写成空。
                    persistencePaused = true
                    val snapshot = runCatching { AgentConversationStore.load(appContext) }.getOrNull()
                    withContext(Dispatchers.Main.immediate) {
                        if (snapshot != null) {
                            applyLoadedSnapshot(snapshot)
                            synchronized(persistenceLock) { persistencePaused = false }
                            // 补做必须在暂停解除之后：分享要新建会话并落库。
                            flushPendingEntryRequests()
                        } else {
                            // 加载失败就**保持暂停保存**：此时内存是空状态，
                            // 一旦恢复增量保存，下一次 persist 会把库里已有的会话当成"被删除"写掉。
                            // 宁可不保存（用户看到空列表但数据还在），也不能覆盖用户数据。
                            AndroidAgentLogger.warn("startup conversation load failed; persistence stays paused")
                            // 分享内容靠「载入后补做」落地，载入本身失败就没有可落地的会话；
                            // 直接丢掉并如实告知，好过一直挂着等一个不会到来的重载。
                            val droppedShare = pendingSharedContent
                            pendingSharedContent = null
                            if (droppedShare != null) {
                                Toast.makeText(
                                    appContext,
                                    appContext.getString(R.string.shared_content_load_failed),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }
                }
                recoverRuntimeRuns()
                importArchivedExternalRuns()
            } finally {
                runtimeRecoveryInProgress.set(false)
            }
        }
    }

    private fun observeRuntimeSelection() {
        scope.launch(Dispatchers.IO) {
            combine(
                RuntimeConfigRepository.selectedProviderIdFlow(),
                RuntimeConfigRepository.selectedModelIdFlow(),
                ProviderRepository.providersFlow(),
            ) { providerId, modelId, providers ->
                Triple(providerId, modelId, providers)
            }
                .distinctUntilChanged()
                .collectLatest { (providerId, modelId, providers) ->
                    withContext(Dispatchers.Main) {
                        this@AgentAppState.providers = providers
                        defaultProviderId = providerId
                        defaultModelId = modelId
                        refreshConversationModel()
                    }
                }
        }
    }

    /**
     * 选择器、思考强度与上下文窗口统一投影自当前会话的有效模型：
     * 会话绑定的模型仍可用时用它，否则用默认模型。回落只影响显示与发送，不改写会话绑定，
     * 模型重新启用后会话自动回到原模型；真正发送后才把实际模型写入绑定。
     */
    private fun refreshConversationModel() {
        val bound = providers.enabledModel(homeState.modelId)
        val pickerState = if (bound != null) {
            AgentModelPickerProjector.project(providers, bound.provider.id, bound.model.id)
        } else {
            AgentModelPickerProjector.project(providers, defaultProviderId, defaultModelId)
        }
        modelPickerState = pickerState.copy(isChanging = modelPickerState.isChanging)
        val effective = bound ?: providers.enabledModel(pickerState.selectedModel?.id)
        currentReasoningCapabilities = effective?.let { (provider, model) ->
            RuntimeConfigRepository.reasoningCapabilities(provider, model)
        }
        val resolved = homeState.withCurrentReasoningCapabilities()
        val conversationId = selectedConversationId
        if (conversationId == null) homeState = resolved
        else updateConversation(conversationId, resolved, updateTimestamp = false)
    }

    private fun AgentChatHomeUiState.withCurrentReasoningCapabilities(): AgentChatHomeUiState {
        val normalized = currentReasoningCapabilities?.normalize(reasoningEffort) ?: ReasoningEffort.OFF
        return copy(
            thinkingEnabled = normalized.enablesReasoning,
            reasoningEffort = normalized,
            availableReasoningEfforts = currentReasoningCapabilities?.selectableEfforts.orEmpty(),
        )
    }

    private val AgentChatHomeUiState.effectiveModelId: String?
        get() = providers.enabledModel(modelId)?.model?.id ?: modelPickerState.selectedModel?.id

    fun refreshRuntimeResults() {
        if (!runtimeRecoveryInProgress.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                recoverRuntimeRuns()
                importArchivedExternalRuns()
            } finally {
                runtimeRecoveryInProgress.set(false)
            }
        }
    }

    suspend fun inspectBackup(input: InputStream): EtaBackupSummary =
        EtaBackupRepository.inspect(appContext, input)

    /** 导入前的整库快照；失败会抛异常，由界面中止导入。 */
    suspend fun snapshotBeforeImport() {
        EtaBackupRepository.snapshotBeforeImport(appContext)
    }

    suspend fun exportBackup(output: OutputStream): EtaBackupSummary =
        EtaBackupRepository.export(appContext, output)

    /**
     * 到期就自动备份一次；返回 null 表示这次没做事（未开启 / 没到期 / 正在跑任务）。
     *
     * 正在跑任务时跳过：导出要遍历会话与消息全表，不该插在用户等回复的时候。
     */
    suspend fun runAutoBackupIfDue(): AutoBackupOutcome? {
        val busy = withContext(Dispatchers.Main.immediate) {
            currentRunId != null || conversationsById.values.any { it.isStreaming }
        }
        return AutoBackupRepository.runIfDue(appContext, busy)
    }

    suspend fun importBackup(input: InputStream): EtaBackupSummary {
        val locallyBusy = withContext(Dispatchers.Main.immediate) {
            currentRunId != null || conversationsById.values.any { it.isStreaming }
        }
        if (locallyBusy) {
            throw IllegalStateException(appContext.getString(R.string.agent_error_stop_running_task))
        }

        val activeRunQuery = withContext(Dispatchers.IO) {
            AgentRuntimeClient(appContext, AndroidAgentLogger).queryActiveRun()
        }
        when (val active = activeRunQuery) {
            is AgentRuntimeClient.ActiveRunQuery.Known -> {
                if (active.runId != null) {
                    throw IllegalStateException(appContext.getString(R.string.agent_error_stop_running_task))
                }
            }
            AgentRuntimeClient.ActiveRunQuery.Unavailable -> {
                throw IllegalStateException(appContext.getString(R.string.agent_error_runtime_state_unknown))
            }
        }

        val pendingPersistence = synchronized(persistenceLock) {
            check(!persistencePaused) { appContext.getString(R.string.agent_error_backup_importing) }
            persistencePaused = true
            persistenceJob
        }
        var imported = false
        var reloaded = false
        try {
            pendingPersistence?.join()
            val summary = EtaBackupRepository.import(appContext, input)
            imported = true
            reloadConversationsAfterBackup()
            reloaded = true
            return summary
        } finally {
            // 导入已提交但重载失败时内存仍是旧状态，继续暂停，避免覆盖导入结果；重启后按库内数据加载。
            if (!imported || reloaded) {
                synchronized(persistenceLock) { persistencePaused = false }
            }
            // 导入期间收到的分享 / 深链同样在内存被整体替换后补做。
            if (imported && reloaded) flushPendingEntryRequests()
            // 导入失败时数据库已回滚并与保存基线一致，补写暂停期间的会话变更。
            if (!imported) withContext(Dispatchers.Main.immediate + NonCancellable) { persistConversations() }
        }
    }

    private suspend fun reloadConversationsAfterBackup() {
        val snapshot = withContext(Dispatchers.IO) {
            AgentConversationStore.load(appContext)
        }
        withContext(Dispatchers.Main.immediate) {
            applyLoadedSnapshot(snapshot)
        }
    }

    /**
     * 把一份加载好的快照整体应用为当前状态。
     *
     * 启动时的后台加载与备份导入后的重载共用这一处：两条路径的状态字段必须完全一致，
     * 各写一份迟早会漏（例如漏掉 `fileAttachmentOwnerVersion` 就会让附件指向旧会话）。
     * 调用方负责切到 Main。
     */
    private fun applyLoadedSnapshot(snapshot: AgentConversationStore.Snapshot) {
        selectedConversationId = snapshot.selectedConversationId
        conversationsById = snapshot.conversationsById
        refreshAnyRunningConversation()
        conversationTitles = snapshot.titles
        conversationUpdatedAt = snapshot.updatedAt
        conversationMessageCounts = snapshot.messageCounts
        conversationPersistence = AgentConversationPersistence(snapshot)
        fileAttachmentOwnerVersion += 1
        homeState = selectedConversationId
            ?.let(conversationsById::get)
            ?: emptyChatState(defaultThinkingEnabled)
        refreshConversationModel()
        conversationPaneState = conversationPaneState.copy(
            selectedConversationId = selectedConversationId,
            searchQuery = "",
        )
        refreshConversationSummaries()
        conversationsLoaded = true
    }

    /**
     * 补做载入期间挂起的入口请求（会话深链 / 分享）。
     *
     * 必须在 `persistencePaused` 解除之后调用：这些操作要新建会话并落库，
     * 暂停期间执行的话只会留在内存里，进程一被杀就没了。
     * 切换到 Main 再执行：导入路径的 finally 不保证在主线程。
     */
    private fun flushPendingEntryRequests() {
        if (pendingConversationSelection == null && pendingSharedContent == null) return
        scope.launch(Dispatchers.Main.immediate) {
            pendingConversationSelection?.let { requestedId ->
                pendingConversationSelection = null
                selectConversation(requestedId)
            }
            pendingSharedContent?.let { plan ->
                pendingSharedContent = null
                applySharedContent(plan)
            }
        }
    }

    /** 用 checkpoint、终态 outbox 与 active session 一次性对账，避免用进程存活推断 run 状态。 */
    private suspend fun recoverRuntimeRuns() {
        healStaleRunsAfterStartup()
        val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
        val checkpoints = withContext(Dispatchers.IO) {
            AgentRunCheckpointStore.list(appContext)
        }
        val initialCompletedQuery = client.queryCompletedRuns()
        if (initialCompletedQuery is AgentRuntimeClient.CompletedRunsQuery.Unavailable) {
            AndroidAgentLogger.warnThrottled("agent_ui_drain_results_failed") {
                "Agent UI pending result recovery failed"
            }
        }
        val initialCompletedRuns =
            (initialCompletedQuery as? AgentRuntimeClient.CompletedRunsQuery.Known)
                ?.runs
                .orEmpty()
        val activeRunQuery = client.queryActiveRun()
        // primary 只反映「当前浮层跟随的那条」；并发会话必须用全量快照，否则后台会话永远显示未运行。
        val activeRunsQuery = client.queryActiveRuns()
        val activeSessions = (activeRunsQuery as? AgentRuntimeClient.ActiveRunsQuery.Known)
            ?.runs
            .orEmpty()
        val terminalRaceQuery = if (
            activeRunQuery is AgentRuntimeClient.ActiveRunQuery.Known && checkpoints.isNotEmpty()
        ) {
            client.queryCompletedRuns()
        } else {
            initialCompletedQuery
        }
        val terminalRaceCompletedRuns =
            (terminalRaceQuery as? AgentRuntimeClient.CompletedRunsQuery.Known)
                ?.runs
                .orEmpty()
        val completedRuns = (initialCompletedRuns + terminalRaceCompletedRuns)
            .associateBy { completed ->
                completed.result.runId.ifBlank { completed.handoff.id }
            }
            .values
            .toList()
        val activeStateKnown = activeRunQuery is AgentRuntimeClient.ActiveRunQuery.Known
        val terminalStateKnown = terminalRaceQuery is AgentRuntimeClient.CompletedRunsQuery.Known
        val activeRunId = (activeRunQuery as? AgentRuntimeClient.ActiveRunQuery.Known)?.runId
        val activePaused = (activeRunQuery as? AgentRuntimeClient.ActiveRunQuery.Known)?.paused == true
        // primary 为空不代表没有 run 在跑：必须用服务端回报的总数判定，否则后台会话会被判中断。
        val activeRunCount = (activeRunQuery as? AgentRuntimeClient.ActiveRunQuery.Known)
            ?.runningCount
            ?: AgentRuntimeWire.UNKNOWN_RUN_COUNT
        val locallyObservedRunId = withContext(Dispatchers.Main) { currentRunId }
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = checkpoints,
            completedRuns = completedRuns,
            activeStateKnown = activeStateKnown,
            terminalStateKnown = terminalStateKnown,
            activeRunId = activeRunId,
            locallyObservedRunId = locallyObservedRunId,
            activeRunCount = activeRunCount,
        )
        val orphanRewrites = if (activeStateKnown && terminalStateKnown) withContext(Dispatchers.Main) {
            val observed = checkpoints.mapTo(mutableSetOf()) { it.runId }.apply {
                addAll(completedRuns.map { it.result.runId })
                activeRunId?.let(::add)
                currentRunId?.let(::add)
            }
            conversationsById.flatMap { (id, state) ->
                state.roleplayMessages.pendingRewrites.keys.filterNot { it in observed }.map { id to it }
            }
        } else emptyList()
        // 服务端明确回报的快照里已经没有这些 run，本地却还挂着「运行中」。
        // 这是死按钮的真正来源：必须在正常恢复流程之外单独兜底，
        // 否则 plan 全空时这里直接 return，假运行态永远没人清。
        val staleRunStatePresent = withContext(Dispatchers.Main) {
            activeRunsQuery is AgentRuntimeClient.ActiveRunsQuery.Known &&
                hasStaleRunState(activeSessions, checkpoints)
        }
        if (
            plan.completed.isEmpty() &&
            plan.interrupted.isEmpty() &&
            plan.reattach == null &&
            orphanRewrites.isEmpty() &&
            activeSessions.none { it.conversationId.isNotBlank() } &&
            !staleRunStatePresent
        ) {
            return
        }

        val acknowledgeAfterSave = mutableListOf<String>()
        val removeAfterSave = mutableListOf<String>()
        // 恢复目标会话的消息若还没载入，先读进来：否则恢复出来的消息会被 deferred 挡住而不落盘。
        ensureMessagesLoaded(
            (plan.completed.mapNotNull { AgentUiHandoffPayload.from(it.result.handoff.payload).conversationId } +
                plan.interrupted.mapNotNull { AgentUiHandoffPayload.from(it.handoff.payload).conversationId } +
                listOfNotNull(plan.reattach?.let { AgentUiHandoffPayload.from(it.handoff.payload).conversationId })),
        )

        val changed = withContext(Dispatchers.Main) {
            var stateChanged = false
            for (recoveryPlan in plan.completed) {
                val completedRun = recoveryPlan.result
                val runId = completedRun.result.runId.ifBlank { completedRun.handoff.id }
                val payload = AgentUiHandoffPayload.from(completedRun.handoff.payload)
                val conversationId = payload.conversationId
                val state = conversationsById[conversationId] ?: continue
                recoveryPlan.checkpoint?.let { checkpoint ->
                    stateChanged = restoreCheckpointTrace(
                        checkpoint = checkpoint,
                        interrupted = false,
                    ) || stateChanged
                }
                val result = completedRun.result
                val current = conversationsById[conversationId] ?: state
                val recovery = AgentPendingResultRecovery.apply(
                    state = current,
                    runId = runId,
                    result = result,
                    promptSupplement = payload.promptSupplement,
                    supplements = payload.supplements,
                )
                // alreadyApplied 只表示历史无需再合并；恢复阶段留下的误报仍可能被清理，
                // 只要状态变了就必须落盘，否则下次重启又从头清一遍。
                if (recovery.alreadyApplied && recovery.state == current) {
                    acknowledgeAfterSave += runId
                    continue
                }
                updateConversation(conversationId, recovery.state)
                acknowledgeAfterSave += runId
                stateChanged = true
            }

            for (checkpoint in plan.interrupted) {
                removeAfterSave += checkpoint.runId
                stateChanged = restoreCheckpointTrace(
                    checkpoint = checkpoint,
                    interrupted = true,
                ) || stateChanged
            }
            // 只回 primary 会漏掉后台会话：它们的 run 还在跑，界面却显示未运行。
            stateChanged = syncActiveSessions(
                snapshots = activeSessions,
                alreadyAttachedRunId = plan.reattach?.runId,
            ) || stateChanged
            // 先接上真在跑的，再清掉服务端已经不认的 —— 顺序反了会把刚接上的清掉。
            if (activeRunsQuery is AgentRuntimeClient.ActiveRunsQuery.Known) {
                stateChanged = reconcileStaleRunState(activeSessions, checkpoints) || stateChanged
            }
            orphanRewrites.forEach { (conversationId, runId) ->
                conversationsById[conversationId]?.let { state ->
                    updateConversation(conversationId, RoleplayConversationReducer.applyRewrite(state, runId,
                        AgentRuntimeWire.RunResult(runId, false, "", appContext.getString(R.string.agent_notice_rewrite_interrupted),
                            operation = AgentRuntimeWire.OP_REWRITE_REPLY)))
                    stateChanged = true
                }
            }
            if (stateChanged) refreshConversationSummaries()
            stateChanged || acknowledgeAfterSave.isNotEmpty() || removeAfterSave.isNotEmpty()
        }

        if (changed) {
            val saved = withContext(Dispatchers.Main) { persistConversations() }.await()
            if (saved) {
                acknowledgeAfterSave.forEach(client::ackResult)
                removeAfterSave.forEach { runId ->
                    AgentRunCheckpointStore.remove(appContext, runId)
                }
            }
        }

        plan.reattach?.let { checkpoint ->
            withContext(Dispatchers.Main) {
                startReattachedRun(
                    checkpoint = checkpoint,
                    paused = activePaused && checkpoint.runId == activeRunId,
                )
            }
        }
    }

    /** 把安全事件恢复为 UI 轨迹；半截回复不进入模型 history，设备工具也不会重放。 */
    private suspend fun restoreCheckpointTrace(
        checkpoint: AgentRunCheckpointStore.Checkpoint,
        interrupted: Boolean,
    ): Boolean {
        val runId = checkpoint.runId
        if (runId.isBlank()) return false
        val conversationId = AgentUiHandoffPayload
            .from(checkpoint.handoff.payload)
            .conversationId
        var existing = conversationsById[conversationId] ?: return false
        if (existing.messagesDeferred) {
            ensureMessagesLoaded(listOf(conversationId))
            existing = conversationsById[conversationId] ?: return false
            // 读不出来就不要拿空表重建：宁可让会话停在「载入中」，也不能把它清空。
            if (existing.messagesDeferred) return false
        }
        if (AgentRuntimeHistoryReducer.wasApplied(existing, runId)) return false
        // checkpoint 里已有 run_finished：模型这一轮已经跑完，丢的只是终态结果的发布。
        // 这时再补「任务已中断」是误报——用户看到的是一段完整回答后面又跟一条已中断，
        // 而且每次重启都会再补一条。真机上已复现（run 结束前进程被杀）。
        val finishedBeforeResult = AgentRunRecoveryCoordinator.finishedBeforeResult(checkpoint)
        val markInterrupted = interrupted && !finishedBeforeResult
        if (interrupted && finishedBeforeResult) {
            AndroidAgentLogger.infoPersisted(
                "run finished before result: runId=$runId events=${checkpoint.events.size}"
            )
        }
        if (checkpoint.operation == AgentRuntimeWire.OP_REWRITE_REPLY) {
            val restored = RoleplayConversationReducer.restorePendingRewrite(existing, runId, checkpoint.rewriteTargetMessageId)
            if (markInterrupted) updateConversation(conversationId, RoleplayConversationReducer.applyRewrite(
                restored, runId, AgentRuntimeWire.RunResult(runId, false, "", appContext.getString(R.string.agent_notice_rewrite_interrupted),
                    operation = AgentRuntimeWire.OP_REWRITE_REPLY, rewriteTargetMessageId = checkpoint.rewriteTargetMessageId),
            )) else updateConversation(conversationId, restored)
            return markInterrupted || restored != existing
        }

        runConversationIds[runId] = conversationId
        updateConversation(conversationId, existing.copy(isStreaming = true, isCompacting = checkpoint.operation == AgentRuntimeWire.OP_COMPACT))
        restoreRunEventsOffMain(runId, checkpoint.events)
        flushPendingRunDelta(runId)
        updateRunTrace(runId) { messages ->
            val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
            val finalizedText = runMessageProjector.finalizeText(runId, finalizedThinking)
            if (markInterrupted) {
                val interruptedTools = runMessageProjector.interruptRunningTools(
                    reason = appContext.getString(R.string.system_notice_interrupted),
                    messages = runMessageProjector.finishContextCompaction(runId, finalizedText, appContext.getString(R.string.agent_notice_compaction_interrupted)),
                )
                val noticeId = "interrupted-$runId"
                if (interruptedTools.any { it.id == noticeId }) {
                    interruptedTools
                } else {
                    runMessageProjector.insertRunNotice(
                        runId = runId,
                        notice = SystemNoticeMessageUi(
                            id = noticeId,
                            code = SystemNoticeCode.Interrupted,
                        ),
                        messages = interruptedTools,
                    )
                }
            } else {
                runMessageProjector.finalizeRun(runId, finalizedText)
            }
        }
        // 已跑完的 run 同样要把 transcript 接进 history：结果没发布，checkpoint 里的
        // 提交记录就是这一轮唯一的模型历史来源；不接会让下一轮重复前面的工具调用。
        // 只在 interrupted 分支接：终态结果已存在时由 AgentPendingResultRecovery 负责，
        // 这里先标记 applied 会让那边误判 alreadyApplied，把真正的回答丢掉。
        if (interrupted &&
            (checkpoint.contextSnapshot != null || checkpoint.transcript.isNotEmpty())
        ) {
            applyConversationHistoryResult(runId, checkpoint.transcript, checkpoint.contextSnapshot, true)
        }
        conversationsById[conversationId]?.let { state ->
            updateConversation(conversationId, RoleplayConversationReducer.linkRun(state, runId))
        }
        setConversationStreaming(runId, false)
        runMessageProjector.clearRun(runId)
        runConversationIds.remove(runId)
        conversationUpdatedAt = conversationUpdatedAt +
            (conversationId to checkpoint.updatedAt)
        return true
    }

    private fun startReattachedRun(checkpoint: AgentRunCheckpointStore.Checkpoint, paused: Boolean = false) {
        val runId = checkpoint.runId
        val conversationId = AgentUiHandoffPayload
            .from(checkpoint.handoff.payload)
            .conversationId
        var existing = conversationsById[conversationId] ?: return
        if (existing.messagesDeferred) {
            scope.launch(Dispatchers.IO) {
                val ok = ensureMessagesLoaded(listOf(conversationId))
                if (ok) withContext(Dispatchers.Main) { startReattachedRun(checkpoint, paused) }
            }
            return
        }
        if (runConversationIds.containsValue(conversationId) || AgentRuntimeHistoryReducer.wasApplied(existing, runId)) return

        runConversationIds[runId] = conversationId
        currentRunId = runId
        val restored = if (checkpoint.operation == AgentRuntimeWire.OP_REWRITE_REPLY) {
            RoleplayConversationReducer.restorePendingRewrite(existing, runId, checkpoint.rewriteTargetMessageId)
        } else existing
        updateConversation(conversationId, restored.copy(
            isStreaming = true,
            // 恢复时用服务端查询到的真实暂停状态：否则重进应用后界面会显示「暂停」
            // 而实际 run 仍在跑（或反过来）。
            isPaused = paused,
            isCompacting = checkpoint.operation == AgentRuntimeWire.OP_COMPACT,
        ))
        refreshConversationSummaries()
        attachRunningRun(runId, asPrimary = true)
    }

    /**
     * 把所有仍在运行的会话对齐到界面。
     *
     * `queryActiveRun` 只回 primary；一个后台会话在跑、primary 为空时，恢复流程会把它
     * 当成「没有 run」，界面就一直显示未运行。这里按全量快照逐条补齐：
     * 已在跟踪的只同步暂停/压缩态，没跟踪的接上事件流。
     */
    private fun syncActiveSessions(
        snapshots: List<AgentRuntimeWire.ActiveRunSnapshot>,
        alreadyAttachedRunId: String?,
    ): Boolean {
        var changed = false
        for (snapshot in snapshots) {
            val runId = snapshot.runId
            val conversationId = snapshot.conversationId
            if (runId.isBlank() || conversationId.isBlank()) continue
            if (runId == alreadyAttachedRunId) continue
            val state = conversationsById[conversationId] ?: continue
            val tracked = runConversationIds.values.contains(conversationId)
            if (tracked) {
                if (!state.isStreaming || state.isPaused != snapshot.paused ||
                    state.isCompacting != (snapshot.operation == AgentRuntimeWire.OP_COMPACT)
                ) {
                    updateConversation(
                        conversationId,
                        state.copy(
                            isStreaming = true,
                            isPaused = snapshot.paused,
                            isCompacting = snapshot.operation == AgentRuntimeWire.OP_COMPACT,
                        ),
                        updateTimestamp = false,
                    )
                    changed = true
                }
                continue
            }
            if (AgentRuntimeHistoryReducer.wasApplied(state, runId)) continue
            if (state.messagesDeferred) {
                pendingAttachRuns[runId] = conversationId
                runConversationIds[runId] = conversationId
                if (!state.isStreaming) {
                    updateConversation(
                        conversationId,
                        state.copy(isStreaming = true, isPaused = snapshot.paused),
                        updateTimestamp = false,
                    )
                    changed = true
                }
                continue
            }
            runConversationIds[runId] = conversationId
            updateConversation(
                conversationId,
                state.copy(
                    isStreaming = true,
                    isPaused = snapshot.paused,
                    isCompacting = snapshot.operation == AgentRuntimeWire.OP_COMPACT,
                ),
                updateTimestamp = false,
            )
            attachRunningRun(runId, asPrimary = false)
            changed = true
        }
        return changed
    }

    /**
     * 用服务端的活跃快照校准本地残留。
     *
     * 这是「停止 / 插话点了没反应」的兜底：只要服务端明确回报某个 run 不在了，
     * 本地指向它的映射、去抖记录、以及会话上的「运行中」标记就都该收掉，
     * 否则界面会一直显示一条早已结束的任务，所有针对它的操作都静默落空。
     *
     * 三道保护：正在跑的 [currentRunId]、还有 checkpoint 待恢复的 run、
     * 以及快照本身报上来的 run 都不动。只在查询**成功**时调用。
     */
    private fun reconcileStaleRunState(
        snapshots: List<AgentRuntimeWire.ActiveRunSnapshot>,
        checkpoints: List<AgentRunCheckpointStore.Checkpoint>,
    ): Boolean {
        val stale = findStaleRunState(snapshots, checkpoints)
        if (stale.isEmpty) return false
        // 先摘映射，再会话：会话的判定依赖「清理后还有没有 run 指向它」。
        stale.runIds.forEach { runId ->
            runConversationIds.remove(runId)
            pendingAttachRuns.remove(runId)
            stopRequestedAt.remove(runId)
            stopIntentByInterrupt.remove(runId)
        }
        stale.conversationIds.forEach { id ->
            val state = conversationsById[id] ?: return@forEach
            updateConversation(
                id,
                state.copy(isStreaming = false, isPaused = false, isCompacting = false, isGuiWaiting = false),
                updateTimestamp = false,
            )
        }
        return true
    }

    private fun findStaleRunState(
        snapshots: List<AgentRuntimeWire.ActiveRunSnapshot>,
        checkpoints: List<AgentRunCheckpointStore.Checkpoint>,
    ): AgentStaleRunReconciler.Stale = AgentStaleRunReconciler.find(
        localRunToConversation = runConversationIds,
        streamingConversationIds = conversationsById.filterValues { it.isStreaming }.keys,
        liveRunIds = snapshots.mapNotNullTo(mutableSetOf()) { it.runId.takeIf(String::isNotBlank) },
        liveConversationIds = snapshots.mapNotNullTo(mutableSetOf()) { it.conversationId.takeIf(String::isNotBlank) },
        pendingRunIds = checkpoints.mapNotNullTo(mutableSetOf()) { it.runId.takeIf(String::isNotBlank) },
        currentRunId = currentRunId,
    )

    /** [reconcileStaleRunState] 的只读预判：没有残留就不必把恢复流程整个跑一遍。 */
    private fun hasStaleRunState(
        snapshots: List<AgentRuntimeWire.ActiveRunSnapshot>,
        checkpoints: List<AgentRunCheckpointStore.Checkpoint>,
    ): Boolean = !findStaleRunState(snapshots, checkpoints).isEmpty

    /** 接上一条仍在运行的事件流；[asPrimary] 决定是否占用 UI 的当前 run。 */
    private fun attachRunningRun(runId: String, asPrimary: Boolean) {
        if (asPrimary) currentRunId = runId
        val job = scope.launch(Dispatchers.IO) {
            val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
            val outcome = client.attachRun(
                runId = runId,
                onReplay = { events -> restoreRunEvents(runId, events) },
                onEvent = { event -> enqueueRunEvent(runId, event) },
            )
            when (outcome) {
                is AgentRuntimeClient.AttachOutcome.Completed -> withContext(Dispatchers.Main) {
                    applyRunResult(
                        runId = runId,
                        result = outcome.result,
                        acknowledgeRuntimeResult = true,
                    )
                }
                AgentRuntimeClient.AttachOutcome.NotActive -> {
                    withContext(Dispatchers.Main) {
                        if (currentRunId == runId) {
                            currentRunId = null
                            currentRunJob = null
                        }
                        setConversationStreaming(runId, false)
                        runConversationIds.remove(runId)
                    }
                    recoverRuntimeRuns()
                }
                AgentRuntimeClient.AttachOutcome.Unavailable -> withContext(Dispatchers.Main) {
                    // 查询失败不等于 run 结束：保留「运行中」，自己安排重试。
                    // 只靠「下一次前台对账」不够：用户此刻已经在前台，不会再触发一次 ON_RESUME。
                    if (asPrimary) currentRunJob = null
                    scheduleAttachReconcile()
                }
            }
        }
        if (asPrimary) currentRunJob = job
    }

    /**
     * attach 没问出来时的对账重试。
     *
     * 为什么不直接清运行态：那正是「后台久了切回前台，界面显示未运行、其实还在跑」的来源。
     * 这里最多试 6 次（1s、之后每 5s），只要有一次拿到明确快照就交给 [recoverRuntimeRuns]
     * 走正常对账：run 真结束了会被 [reconcileStaleRunState] 收掉，不会一直挂着。
     */
    private fun scheduleAttachReconcile() {
        if (attachReconcileScheduled) return
        attachReconcileScheduled = true
        scope.launch {
            try {
                repeat(6) { index ->
                    delay(if (index == 0) 1_000L else 5_000L)
                    val known = withContext(Dispatchers.IO) {
                        AgentRuntimeClient(appContext, AndroidAgentLogger).queryActiveRuns() is
                            AgentRuntimeClient.ActiveRunsQuery.Known
                    }
                    if (known) {
                        recoverRuntimeRuns()
                        return@launch
                    }
                }
            } finally {
                attachReconcileScheduled = false
            }
        }
    }

    private suspend fun importArchivedExternalRuns() {
        val archivedRuns = withContext(Dispatchers.IO) {
            AgentRunArchiveStore.list(appContext)
                .filter { AgentExternalArchivePayload.from(it.handoff.payload) != null }
        }
        if (archivedRuns.isEmpty()) return

        withContext(Dispatchers.Main) {
            val importedRunIds = archivedRuns.mapNotNull { archivedRun ->
                importExternalRun(archivedRun)
            }
            refreshConversationSummaries()
            persistConversations {
                importedRunIds.forEach { runId ->
                    AgentRunArchiveStore.remove(appContext, runId)
                }
            }
        }
    }

    suspend fun openAssistantConversation(conversationKey: String): Boolean =
        openArchivedConversation(AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, conversationKey)

    /** 打开外部入口按 [source] 与 [conversationKey] 归档的会话；先导入尚未入库的归档结果。 */
    suspend fun openArchivedConversation(source: String, conversationKey: String): Boolean {
        if (source.isBlank() || conversationKey.isBlank()) return false
        importArchivedExternalRuns()
        return withContext(Dispatchers.Main.immediate) {
            val conversationId = archiveConversationId(
                source = source,
                conversationKey = conversationKey,
            )
            if (conversationsById[conversationId] == null) {
                false
            } else {
                selectConversation(conversationId)
                true
            }
        }
    }

    private fun importExternalRun(archivedRun: AgentRunArchiveStore.ArchivedRun): String? {
        val runId = archivedRun.result.runId.ifBlank { archivedRun.handoff.id }
        if (runId.isBlank()) return null
        val payload = AgentExternalArchivePayload.from(archivedRun.handoff.payload) ?: return null
        val conversationId = archiveConversationId(
            source = archivedRun.handoff.source,
            conversationKey = payload.conversationKey,
        )
        val archivedEffort = payload.reasoningEffort
            ?: payload.thinkingEnabled?.let(ReasoningEffort::fromLegacy)
            ?: ReasoningEffort.fromLegacy(defaultThinkingEnabled)
        val existingState = conversationsById[conversationId] ?: emptyChatState(
            archivedEffort.enablesReasoning
        ).copy(reasoningEffort = archivedEffort)
        val alreadyImported = AgentRuntimeHistoryReducer.wasApplied(existingState, runId) ||
            existingState.messages.any {
                it is AgentMessageUi &&
                    (it.id == "assistant-$runId" || it.id.startsWith("assistant-$runId-")) &&
                    !it.isStreaming
            }
        if (alreadyImported) return runId

        if (conversationTitles[conversationId].isNullOrBlank()) {
            conversationTitles = conversationTitles + (conversationId to payload.title)
        }
        runConversationIds[runId] = conversationId
        // 外部归档会话导入的消息里已经含用户原文，不能再让草稿把它「恢复」成未发送状态。
        draftsByConversation.remove(conversationId)
        updateConversation(
            conversationId,
            existingState.copy(
                input = "",
                isStreaming = true,
                thinkingEnabled = archivedEffort.enablesReasoning,
                reasoningEffort = archivedEffort,
                pendingImages = emptyList(),
                messages = existingState.messages +
                    UserMessageUi(
                        id = "user-$runId",
                        content = payload.userText,
                        images = archivedRun.userImagePreviews,
                    ) +
                    AgentMessageUi(
                        id = "assistant-$runId",
                        content = "",
                        isStreaming = true,
                        renderMarkdown = false,
                    ),
            )
        )
        archivedRun.events.forEach { event -> applyRunEvent(runId, event) }
        applyRunResult(runId, archivedRun.result)
        conversationUpdatedAt = conversationUpdatedAt + (conversationId to archivedRun.createdAt)
        return runId
    }

    fun updateThinkingEnabled(enabled: Boolean) {
        updateReasoningEffort(ReasoningEffort.fromLegacy(enabled))
    }

    fun updateReasoningEffort(effort: ReasoningEffort) {
        val normalized = currentReasoningCapabilities?.normalize(effort) ?: ReasoningEffort.OFF
        updateCurrentConversation(
            homeState.copy(
                thinkingEnabled = normalized.enablesReasoning,
                reasoningEffort = normalized,
            )
        )
        if (selectedConversationId != null) persistConversations()
        hotSwapModelIfRunning()
    }

    /**
     * 当前模型 + 思考等级 → Runtime 配置。
     *
     * 开跑与运行中热切换共用这一份拼装：两处各写一份时，新加的工具开关很容易只加进其中一处，
     * 表现为「新会话尊重开关、中途换模型后又不尊重了」。
     */
    private suspend fun runtimeConfigFor(
        modelId: String?,
        effort: ReasoningEffort,
    ): AgentModelClient.ModelConfig? {
        // 没有可用模型时返回 null：调用方按「未配置模型」报错，而不是拼一份空配置发下去。
        val base = modelId?.takeIf { it.isNotBlank() }
            ?.let { RuntimeConfigRepository.runtimeConfigFor(it) }
            ?: return null
        val requested = base.copy(
            terminalTools = agentBooleanForUi(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            browserTools = agentBooleanForUi(Prefs.Keys.AGENT_BROWSER_TOOLS),
            deviceDirectTools = agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
            deviceSensitiveReadTools =
                agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
            deviceSensitiveActionTools =
                agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
            thinkingEnabled = effort.enablesReasoning,
            reasoningEffort = effort,
        )
        // 档位要按**目标**模型的能力归一。切到不支持当前档位（或不支持思考）的模型时，
        // 原样发下去会在 Provider 侧 require 抛异常 → 整个 run 直接失败；
        // 这里取不高于请求档位的最近一档，能力信息缺失时保持原值（由 Provider 侧兜底）。
        val normalized = requested.reasoningCapabilities?.normalize(effort) ?: effort
        return requested.copy(
            thinkingEnabled = normalized.enablesReasoning,
            reasoningEffort = normalized,
        )
    }

    /** 热切换串行锁：连续快速切模型 / 档位时保证「用户最后一次操作」最后到达 Runtime。 */
    private val hotSwapLock = Mutex()

    /**
     * 会话正在跑时把当前模型 / 等级推给 Runtime：不重建 run、不重发消息，
     * 从下一个模型回合生效。没有活跃 run 时什么也不做（下一轮开跑自然读到新值）。
     */
    private fun hotSwapModelIfRunning() {
        val conversationId = selectedConversationId ?: return
        val runId = runIdForConversation(conversationId) ?: return
        val state = homeState
        val effort = if (agentBooleanForUi(Prefs.Keys.AGENT_THINKING_ENABLED)) {
            state.reasoningEffort
        } else {
            ReasoningEffort.OFF
        }
        scope.launch(Dispatchers.IO) {
            hotSwapLock.withLock {
                // 串行是为了保序：并发读取配置时，先点的旧值可能后到，把用户最后选的档位覆盖掉。
                val config = runtimeConfigFor(state.effectiveModelId, effort) ?: return@launch
                val sent = AgentRuntimeClient(appContext, AndroidAgentLogger).updateRunModel(runId, config)
                withContext(Dispatchers.Main) {
                val label = config.modelDisplayName.ifBlank { config.model }
                val message = appContext.getString(
                    if (sent) R.string.chat_model_hot_swap_applied else R.string.chat_model_hot_swap_failed,
                    label,
                )
                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 聊天内切换同时改当前会话绑定与默认模型；默认模型供新会话和系统助手入口使用。 */
    fun selectModel(modelId: String) {
        if (
            modelPickerState.isChanging ||
            modelPickerState.selectedModel?.id == modelId
        ) {
            return
        }
        updateCurrentConversation(homeState.copy(modelId = modelId))
        refreshConversationModel()
        if (selectedConversationId != null) persistConversations()
        // 运行中不再拦下切换：改的是当前会话的模型，下个模型回合就用新值。
        hotSwapModelIfRunning()
        modelPickerState = modelPickerState.copy(isChanging = true)
        scope.launch(Dispatchers.IO) {
            try {
                RuntimeConfigRepository.setSelectedModelId(modelId)
                RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, appContext.getString(R.string.state_ui_model_switching_failed_please_try_again_later_4af439), Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    modelPickerState = modelPickerState.copy(isChanging = false)
                }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        conversationPaneState = conversationPaneState.copy(searchQuery = query)
        refreshConversationSummaries()
    }

    /**
     * 打开一个会话；返回 false 表示当前内存里还没有这个会话。
     *
     * 调用方（通知 / 外部深链）要区分「已经进去了」和「会话还没载入」：
     * 后者如果被当成成功消费掉，用户看到的就是「点了通知只打开 App」。
     */
    fun selectConversation(conversationId: String): Boolean {
        if (homeState.messageEdit != null) cancelMessageEdit()
        val state = conversationsById[conversationId] ?: run {
            // 只有「还没载入」才值得挂起；已经载入过却找不到，说明会话真的被删了，
            // 挂起来等于让一个永不发生的重试把一次性请求永远占着。
            if (!conversationsLoaded) pendingConversationSelection = conversationId
            return false
        }
        fileAttachmentOwnerVersion += 1
        selectedConversationId = conversationId
        homeState = state
        refreshConversationModel()
        if (state.modelId != null && providers.enabledModel(state.modelId) == null) {
            modelPickerState.selectedModel?.let { fallback ->
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.conversation_model_unavailable, fallback.displayName),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        conversationPaneState = conversationPaneState.copy(selectedConversationId = conversationId)
        if (state.messagesDeferred) loadDeferredMessages(conversationId)
        if (state.historyDeferred) loadDeferredHistory(conversationId)
        if (state.journalDeferred) loadDeferredJournal(conversationId)
        // 回答落成消息后旧 run 收尾时若这里没被选中，起 run 的那一步仍会执行；
        // 这里补一次只是兜住「收尾回调丢了」的情况。
        maybeLaunchPendingAskAnswer(conversationId)
        persistConversations()
        scheduleMessageRelease(conversationId)
        // 界面说在跑、本地却没有可对应的 run：先对一次账，别让用户盯着一个
        // 点了没反应的停止按钮。正常在跑的会话都有映射，不会走到这里。
        if (state.isStreaming && runIdForConversation(conversationId) == null) {
            scope.launch { recoverRuntimeRuns() }
        }
        return true
    }

    /**
     * 系统分享 / 选中文本进入 Eta：新建一个会话，文本填进输入框，图片与文件走正常附件通道。
     *
     * 为什么不塞进当前会话：用户可能正在写别的对话，外部内容混进来会打乱上下文，
     * 事后也分不清哪句是自己的。
     * 为什么不自动发送：分享源是任意第三方应用，自动提交等于让外部内容直接驱动 Agent
     * 执行设备操作；停在输入框让用户按一次发送，代价很小。
     */
    fun acceptSharedContent(plan: SharedContentPlan) {
        if (plan.isEmpty) {
            // 从系统分享面板点了 Eta 却什么都没发生，会被当成「点了没反应」，必须说出来。
            Toast.makeText(appContext, R.string.shared_content_empty, Toast.LENGTH_LONG).show()
            return
        }
        if (!conversationsLoaded || persistencePaused) {
            // 载入未完成时新建会话会被紧随其后的整体替换冲掉；等它完成再补做。
            pendingSharedContent = plan
            return
        }
        applySharedContent(plan)
    }

    private fun applySharedContent(plan: SharedContentPlan) {
        createConversation()
        val conversationId = newConversationId()
        selectedConversationId = conversationId
        val title = defaultConversationTitle(plan.text, emptyList())
            .ifBlank { appContext.getString(R.string.shared_content_conversation_title) }
        // 必须建立在 createConversation() 已经投影好的状态上：重新 emptyChatState 会把
        // refreshConversationModel() 刚算出的模型与思考能力丢掉（现象是新会话思考选项为空）。
        homeState = homeState.copy(input = plan.text)
        conversationTitles = conversationTitles + (conversationId to title)
        updateConversation(conversationId, homeState)
        conversationPaneState = conversationPaneState.copy(
            selectedConversationId = conversationId,
            searchQuery = "",
        )
        refreshConversationSummaries()
        persistConversations()
        plan.imageUris.forEach(::attachImage)
        if (plan.fileUris.isNotEmpty()) attachFiles(plan.fileUris)
        Toast.makeText(appContext, sharedContentToast(plan), Toast.LENGTH_LONG).show()
    }

    /** 截断 / 超限必须说出来：用户以为全部发出去了，比多一条提示糟糕得多。 */
    private fun sharedContentToast(plan: SharedContentPlan): String = buildString {
        append(appContext.getString(R.string.shared_content_ready))
        plan.notes.forEach { note ->
            append('\n')
            append(
                when (note) {
                    is SharedContentNote.TextTruncated -> appContext.getString(
                        R.string.shared_content_text_truncated,
                        note.keptChars,
                    )
                    is SharedContentNote.ImagesLimited -> appContext.getString(
                        R.string.shared_content_images_limited,
                        note.accepted,
                        note.offered,
                    )
                    is SharedContentNote.FilesLimited -> appContext.getString(
                        R.string.shared_content_files_limited,
                        note.accepted,
                        note.offered,
                    )
                },
            )
        }
    }

    /** 读一次系统退出记录（只在启动时），有非正常退出就摆到聊天页顶部。 */
    private fun detectLastExitCause() {
        scope.launch(Dispatchers.IO) {
            val cause = AppExitReporter.detectAndMark(appContext)
            if (cause != AppExitCause.NONE) {
                withContext(Dispatchers.Main) { lastExitCause = cause }
            }
        }
    }

    /** 用户已经看到过这条结论，别再占位。 */
    fun dismissExitNotice() {
        lastExitCause = AppExitCause.NONE
    }

    /**
     * 前后台切换时调整会话消息的内存占用。
     *
     * 退后台：释放当前会话消息（[shouldReleaseBackgroundMessages] 给出例外），让后台进程的堆
     * 回到只含元数据的量级 —— 这是被系统回收概率最高的一段生命周期。实测本机 ColorOS 在
     * RSS 400MB+ 时，前台服务（importance 125）也照杀（exit-info: o-kill(4008)）。
     *
     * 回前台：当前会话若处于 [AgentChatUiState.messagesDeferred]，立刻重载。用户看到的是
     * 「正在载入」，而不是一屏空白。
     */
    private fun observeAppVisibility() {
        scope.launch {
            var wasForeground = AppVisibility.foreground.value
            AppVisibility.foreground.collect { foreground ->
                if (foreground == wasForeground) return@collect
                wasForeground = foreground
                if (foreground) {
                    reloadMessagesAfterBackground()
                } else {
                    releaseMessagesForBackground()
                }
            }
        }
    }

    private suspend fun releaseMessagesForBackground() {
        val activeId = selectedConversationId ?: return
        val state = conversationsById[activeId] ?: return
        if (state.isStreaming || state.messageEdit != null || state.isCompacting) return
        val releaseMessages = shouldReleaseBackgroundMessages(
            isStreaming = state.isStreaming,
            isEditing = state.messageEdit != null,
            isCompacting = state.isCompacting,
            messagesDeferred = state.messagesDeferred,
            messageCount = state.messages.size,
        )
        // 即使消息不到阈值，history/journal 也可能常驻；退后台时一起释放。
        val releaseRuntime = !state.historyDeferred || !state.journalDeferred
        if (!releaseMessages && !releaseRuntime) return
        // 先落盘再释放：释放会把内存基线与状态一起标成「未载入」，
        // 如果之前还有没写进库的消息，先释放就等于丢 —— 进程随后被系统回收时再也补不回来。
        persistConversations().await()
        val rows = state.messages.size
        updateConversation(
            activeId,
            state.copy(
                messages = if (releaseMessages) emptyList() else state.messages,
                messagesDeferred = state.messagesDeferred || releaseMessages,
                history = emptyList(),
                historyDeferred = true,
                journal = emptyList(),
                journalDeferred = true,
                previewMessage = if (releaseMessages) state.messages.lastOrNull() else state.previewMessage,
            ),
            updateTimestamp = false,
        )
        // 基线必须同步换成「未载入」版本，否则落盘侧会把空表当成真的空会话写回。
        val persistence = conversationPersistence
        withContext(Dispatchers.IO) { persistence.release(activeId) }
        AndroidAgentLogger.info("conversation.memory.released background id=${activeId.take(13)} rows=$rows")
    }

    private fun reloadMessagesAfterBackground() {
        val activeId = selectedConversationId ?: return
        val state = conversationsById[activeId] ?: return
        if (state.messagesDeferred) loadDeferredMessages(activeId)
        if (state.historyDeferred) loadDeferredHistory(activeId)
        if (state.journalDeferred) loadDeferredJournal(activeId)
    }

    fun createConversation() {
        if (homeState.messageEdit != null) cancelMessageEdit()
        fileAttachmentOwnerVersion += 1
        selectedConversationId = null
        homeState = emptyChatState(defaultThinkingEnabled)
        refreshConversationModel()
        conversationPaneState = conversationPaneState.copy(
            selectedConversationId = null,
            searchQuery = "",
        )
        refreshConversationSummaries()
        scheduleMessageRelease(null)
    }

    fun startCharacterConversation(binding: RoleplayBinding, greeting: String) {
        createConversation()
        val id = newConversationId()
        val greetingId = "greeting-$id"
        val text = CharacterMacros.expand(
            text = greeting, card = CharacterCardCodec.decodeJson(binding.cardSnapshotJson),
            userName = binding.userName, userDescription = binding.userDescription,
        )
        val transcript = if (text.isBlank()) emptyList() else listOf(
            AgentModelClient.ConversationMessage(role = "assistant", content = text, messageId = greetingId),
        )
        selectedConversationId = id
        homeState = emptyChatState(defaultThinkingEnabled).withCurrentReasoningCapabilities().copy(
            roleplay = binding,
            history = transcript,
            journal = transcript,
            messages = if (text.isBlank()) emptyList() else listOf(AgentMessageUi(greetingId, text, characterEditable = true)),
            roleplayMessages = RoleplayMessageState(links = if (text.isBlank()) emptyMap() else mapOf(
                greetingId to RoleplayMessageLink(greetingId),
            )),
        )
        conversationTitles = conversationTitles + (id to binding.characterName)
        updateConversation(id, homeState)
        conversationPaneState = conversationPaneState.copy(selectedConversationId = id, searchQuery = "")
        refreshConversationSummaries()
        persistConversations()
    }

    fun selectReplyCandidate(messageId: String, index: Int) {
        if (rejectWhileMessagesLoading()) return
        if (homeState.isStreaming || homeState.messageEdit != null) return
        val updated = RoleplayConversationReducer.select(homeState, messageId, index) ?: return
        updateCurrentConversation(updated)
        refreshConversationSummaries()
        persistConversations()
    }

    /**
     * 置顶 / 取消置顶。
     *
     * 为什么不动 updatedAt：置顶是「摆放位置」而不是「内容变化」，
     * 改时间会让它同时跳到「最近」分组的顶上，等于一次操作两个效果。
     */
    fun toggleConversationPin(conversationId: String) {
        val state = conversationsById[conversationId] ?: return
        updateConversation(
            conversationId,
            state.copy(isPinned = !state.isPinned),
            updateTimestamp = false,
        )
        refreshConversationSummaries()
        persistConversations()
    }

    /**
     * 删除会话。
     *
     * 运行中的会话不能直接删：run 还在跑，删了只会让它继续在后台烧 token，而界面上
     * 再也找不到它去停止。先请求停止，等运行态真的落下（stop 是异步的，要等当前这一步
     * 返回）再删；停不下来就保留会话并明确提示，不假装已经删掉。
     */
    fun deleteConversation(conversationId: String) {
        if (conversationsById[conversationId]?.isStreaming == true) {
            requestStop(conversationId)
            deleteConversationWhenSettled(conversationId)
            return
        }
        deleteSettledConversation(conversationId)
    }

    /** 等运行态落下再删；[attempts] 用完仍没停住就保留会话。 */
    private fun deleteConversationWhenSettled(conversationId: String, attempts: Int = 40) {
        scope.launch {
            repeat(attempts) { index ->
                delay(if (index == 0) 200L else 500L)
                // 两个信号一起看：UI 的运行态可能因为报错提前落下，而 run 表里还挂着；
                // 只要任一边还在，就继续等——删太早会让 run 在后台裸跑。
                val stillRunning = runIdForConversation(conversationId) != null ||
                    conversationsById[conversationId]?.isStreaming == true
                if (!stillRunning) {
                    deleteSettledConversation(conversationId)
                    return@launch
                }
            }
            toastNotice(R.string.conversation_delete_kept)
        }
    }

    private fun deleteSettledConversation(conversationId: String) {
        val wasSelected = selectedConversationId == conversationId
        pendingAskAnswers.remove(conversationId)
        conversationsById = conversationsById - conversationId
        refreshAnyRunningConversation()
        conversationTitles = conversationTitles - conversationId
        conversationUpdatedAt = conversationUpdatedAt - conversationId
        if (wasSelected) {
            fileAttachmentOwnerVersion += 1
            val nextId = conversationsById.keys.firstOrNull()
            if (nextId != null) {
                selectedConversationId = nextId
                homeState = conversationsById.getValue(nextId)
            } else {
                selectedConversationId = null
                homeState = emptyChatState(defaultThinkingEnabled)
            }
            refreshConversationModel()
        }
        conversationPaneState = conversationPaneState.copy(selectedConversationId = selectedConversationId)
        refreshConversationSummaries()
        persistConversations()
    }

    fun renameConversation(conversationId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        conversationTitles = conversationTitles + (conversationId to trimmed)
        conversationUpdatedAt = conversationUpdatedAt + (conversationId to System.currentTimeMillis())
        refreshConversationSummaries()
        persistConversations()
    }

    suspend fun exportConversationMarkdown(conversationId: String): String? {
        // 未载入的会话内 messages 是空表：先读进来，否则会导出一个只有标题的空文件。
        ensureMessagesLoaded(listOf(conversationId))
        val state = conversationsById[conversationId] ?: return null
        if (state.messagesDeferred) return null
        val title = conversationTitles[conversationId]?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.conversation_unnamed)
        return ConversationMarkdownExporter.export(
            title = title,
            messages = state.messages,
            labels = ConversationMarkdownExporter.Labels(
                user = appContext.getString(R.string.conversation_export_user),
                assistant = appContext.getString(R.string.conversation_export_assistant),
                thinking = appContext.getString(R.string.conversation_export_thinking),
                toolLineFormat = appContext.getString(R.string.conversation_export_tool_line),
                toolsLineFormat = appContext.getString(R.string.conversation_export_tools_line),
                argumentsFormat = appContext.getString(R.string.conversation_export_tool_arguments),
                resultFormat = appContext.getString(R.string.conversation_export_tool_result),
                imagesFormat = appContext.getString(R.string.conversation_export_images),
                usageLineFormat = appContext.getString(R.string.conversation_export_usage),
                usageLineFormatLegacy = appContext.getString(R.string.conversation_export_usage_legacy),
                toolStatusRunning = appContext.getString(R.string.tool_status_running),
                toolStatusSuccess = appContext.getString(R.string.tool_status_success),
                toolStatusFailed = appContext.getString(R.string.tool_status_failed),
                toolStatusUnknown = appContext.getString(R.string.tool_status_unknown),
                noticeStopped = noticeText(SystemNoticeCode.Stopped),
                noticeEmptyResult = noticeText(SystemNoticeCode.EmptyResult),
                noticeModelRetry = noticeText(SystemNoticeCode.ModelRetry),
                noticeContextCompaction = noticeText(SystemNoticeCode.ContextCompaction),
                noticeRuntimeFailed = noticeText(SystemNoticeCode.RuntimeFailed),
                noticeInterrupted = noticeText(SystemNoticeCode.Interrupted),
            ),
        )
    }

    /**
     * 完整历史导出（含工具调用与思考）：直接从数据库逐块搬运，不经过内存里的消息列表。
     *
     * 不能复用 [exportConversationMarkdown]：大会话的 journal 有 3100 万字符，
     * 先载入内存会触发 4M/8M 的 journal 上限而被跳过（导出成空文件），
     * 未载入的会话 messages 也是空表。
     */
    suspend fun exportConversationHistory(
        conversationId: String,
        output: OutputStream,
    ): ConversationHistoryExporter.Summary = ConversationHistoryExporter.export(
        context = appContext,
        conversationId = conversationId,
        title = conversationTitles[conversationId]?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.conversation_unnamed),
        output = output,
    )

    private fun noticeText(code: SystemNoticeCode): String = appContext.getString(
        when (code) {
            SystemNoticeCode.Stopped -> R.string.system_notice_stopped
            SystemNoticeCode.EmptyResult -> R.string.system_notice_empty_result
            SystemNoticeCode.ContextCompaction -> R.string.context_compaction
            SystemNoticeCode.ModelRetry -> R.string.system_notice_model_retry
            SystemNoticeCode.RuntimeFailed -> R.string.system_notice_runtime_failed
            SystemNoticeCode.Interrupted -> R.string.system_notice_interrupted
        },
    )

    /**
     * 运行中「插话发送」：把这句话作为当前 run 的最新用户消息插进去，**不停止 run**。
     *
     * 对齐 dsh 的 steer：插话进入当前步骤之后的边界，由 AgentLoop 当作用户消息追加；
     * 当前正在飞的模型请求和正在跑的工具不被打断。它可能改变任务方向，所以提示词按
     * 「当前最新指令」解释，而不是「在原任务上再加一条」。
     *
     * 与 [sendCurrentMessage] 的运行中行为（排队，见 [queueCurrentMessage]）分开：
     * 点发送的直觉是「这条先记下」；点插话才代表「现在换问题/改方向」。
     * 空闲时没有可插的话，直接退回普通发送。
     */
    fun steerCurrentMessage(submittedText: String? = null) {
        if (rejectWhileMessagesLoading()) return
        if (homeState.isCompacting) return
        val prompt = (submittedText ?: homeState.input).trim()
        val pendingImages = homeState.pendingImages
        val pendingFileReferences = homeState.pendingFileReferences
        if (prompt.isBlank() && pendingImages.isEmpty() && pendingFileReferences.isEmpty()) return
        if (!homeState.isStreaming) {
            sendCurrentMessage(submittedText)
            return
        }
        // 与主发送按钮同一条规则：编辑态的内容不能被当成「插话」发出去 —— enqueue 会清掉
        // messageEdit，用户刚改的那条消息白改，语义也从「改这条」变成「补一句」。
        if (rejectMessageEditWhileStreaming(prompt)) return
        if (selectedConversationId?.isReadOnlyExternalArchiveConversation() == true) return

        // 插话协议只带文字：附件没法塞进当前 run，排队而不是悄悄把 run 停掉。
        if (pendingImages.isNotEmpty() || pendingFileReferences.isNotEmpty()) {
            queueCurrentMessage(prompt)
            toastNotice(R.string.chat_steer_attachment_queued)
            return
        }
        val runId = runIdForConversation(selectedConversationId)
        if (runId == null) {
            queueCurrentMessage(prompt)
            toastNotice(R.string.chat_steer_unavailable)
            return
        }
        val conversationId = selectedConversationId
        val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
        scope.launch(Dispatchers.IO) {
            val sent = client.sendSupplement(runId, prompt) { accepted ->
                if (accepted) {
                    consumeDraftAfterSteer(conversationId, prompt)
                    toastNotice(R.string.chat_steer_sent)
                } else {
                    // 没被接纳（run 刚结束/不是对话 run）：草稿留着，让用户决定重发还是排队。
                    toastNotice(R.string.chat_steer_rejected)
                }
            }
            if (!sent) {
                withContext(Dispatchers.Main) {
                    if (homeState.input.trim() == prompt) {
                        if (homeState.isStreaming) {
                            // run 还活着：按原计划排队，等这次收尾再发。
                            queueCurrentMessage(prompt)
                            toastNotice(R.string.chat_steer_unavailable)
                        } else {
                            // run 刚好结束：不要再排一条永远等不到收尾的消息，直接发。
                            sendCurrentMessage(prompt)
                        }
                    } else {
                        toastNotice(R.string.chat_steer_unavailable)
                    }
                }
            }
        }
    }

    /** 插话被接纳后再清草稿；用户中途改了字就不动，避免把新输入吞掉。 */
    private fun consumeDraftAfterSteer(conversationId: String?, text: String) {
        if (conversationId == null || selectedConversationId != conversationId) return
        if (homeState.input.trim() != text.trim()) return
        setDraftText(conversationId, "")
        updateCurrentConversation(
            homeState.copy(
                input = "",
                // 只清这次送出的文字。发送后用户可能又贴了图，那些不属于这句话，不能顺手清掉。
                inputSyncVersion = homeState.inputSyncVersion + 1,
            )
        )
    }

    /**
     * 把队列里的某条作为「插话」送进当前 run：不打断当前步骤，也不结束 run。
     *
     * 空闲时仍走原来的直接发送；运行中则只支持纯文字——附件塞不进当前 run，
     * 留在队列里并说明，而不是悄悄把 run 停掉。
     *
     * 编辑态与压缩中直接拒绝：界面上的入口已经置灰，这里兜住键盘/无障碍/恢复态。
     */
    fun steerQueuedMessage(id: String) {
        // 输入框里有草稿也可以插话：这条队列消息独立送出，草稿原样保留。
        if (homeState.messageEdit != null) {
            toastNotice(R.string.chat_queue_steer_blocked_by_edit)
            return
        }
        if (homeState.isCompacting) {
            toastNotice(R.string.chat_queue_steer_while_compacting)
            return
        }
        val queued = homeState.queuedMessages.firstOrNull { it.id == id } ?: return
        if (!homeState.isStreaming) {
            val (nextState, promoted) = AgentMessageQueueReducer.promoteToInterrupt(homeState, id)
            if (promoted == null) return
            updateCurrentConversation(nextState)
            // 空闲：直接发。发不出去时 [dispatchQueuedMessage] 自己会把它放回队首。
            dispatchQueuedMessage(promoted)
            return
        }
        if (queued.pendingImages.isNotEmpty() || queued.pendingFileReferences.isNotEmpty()) {
            toastNotice(R.string.chat_steer_attachment_queued)
            return
        }
        val runId = runIdForConversation(selectedConversationId)
        if (runId == null) {
            toastNotice(R.string.chat_steer_unavailable)
            return
        }
        val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
        scope.launch(Dispatchers.IO) {
            val sent = client.sendSupplement(runId, queued.content) { accepted ->
                if (accepted) {
                    val next = AgentMessageQueueReducer.remove(homeState, id)
                    if (next !== homeState) updateCurrentConversation(next)
                    toastNotice(R.string.chat_steer_sent)
                } else {
                    toastNotice(R.string.chat_steer_rejected)
                }
            }
            if (!sent) {
                withContext(Dispatchers.Main) {
                    toastNotice(R.string.chat_steer_unavailable)
                }
            }
        }
    }

    /** 撤回排队消息继续编辑：与输入框里的草稿合并，不覆盖用户正在打的内容。 */
    fun recallQueuedMessage(id: String, currentDraftText: String) {
        val nextState = AgentMessageQueueReducer.recallToDraft(
            state = homeState,
            currentInputText = currentDraftText,
            id = id,
        ) ?: return
        updateCurrentConversation(nextState)
    }

    fun removeQueuedMessage(id: String) {
        val nextState = AgentMessageQueueReducer.remove(homeState, id)
        if (nextState !== homeState) updateCurrentConversation(nextState)
    }

    /**
     * 发出队列里的这条消息。
     *
     * 调用方已经把它从队列里弹出来了，所以「发不出去」时**必须**把它放回某处 —— 具体路径
     * 全部收在 [AgentMessageQueueReducer.planDispatch] 里用单测钉住（压缩中、编辑态、
     * 带附件但草稿位被占，这些以前都是直接丢消息）。这里只负责执行。
     *
     * 返回是否真的发出去了。
     */
    private fun dispatchQueuedMessage(item: QueuedMessageUi): Boolean {
        val (next, plan) = AgentMessageQueueReducer.planDispatch(homeState, item)
        return when (plan) {
            AgentMessageQueueReducer.DispatchPlan.SendNow -> {
                updateCurrentConversation(next.copy(input = item.content))
                sendCurrentMessage(item.content)
                true
            }
            AgentMessageQueueReducer.DispatchPlan.MovedToDraft,
            AgentMessageQueueReducer.DispatchPlan.Deferred,
            -> {
                if (next !== homeState) updateCurrentConversation(next)
                false
            }
        }
    }

    /**
     * 运行中要「保存编辑」时的统一拒绝。
     *
     * 编辑内容属于既有的那条消息，而两条发送路径都只会把它当**新消息**入队/插话，
     * 并且 [AgentMessageQueueReducer.enqueue] 会顺手清掉 messageEdit —— 用户刚改的那条
     * 既没保存、又多出一条不该出现的排队消息。所以运行中一律拒绝，保留现场。
     */
    private fun rejectMessageEditWhileStreaming(restoreText: String? = null): Boolean {
        if (homeState.messageEdit == null) return false
        // 点击路径会先把本地输入框清空再调进来：只弹提示等于把用户刚改的内容吃掉。
        // 把文字放回草稿位并抬一次 inputSyncVersion，输入框就会重新显示它。
        val text = restoreText ?: homeState.input
        updateCurrentConversation(
            homeState.copy(
                input = text,
                inputSyncVersion = homeState.inputSyncVersion + 1,
            ),
        )
        Toast.makeText(
            appContext,
            appContext.getString(R.string.chat_edit_while_streaming),
            Toast.LENGTH_SHORT,
        ).show()
        return true
    }

    /**
     * 假运行态下把队首「插话」放出去。
     *
     * 插话本来靠 run 收尾后的 [drainQueueAfterRun] 接力；但如果服务端已经没有这个 run
     * （界面还挂着运行中），就永远等不到收尾。这里直接弹出并发送，避免消息卡死在队列里。
     *
     * 返回是否真的发出去了：以前无论发没发都弹「任务已经结束」，用户看到消息已经出现在
     * 对话里、同时被告知「已结束」，像是操作失败。
     */
    private fun dispatchPendingInterrupt(conversationId: String): Boolean {
        if (conversationId != selectedConversationId) return false
        val state = conversationsById[conversationId] ?: return false
        if (state.isStreaming || state.isCompacting) return false
        if (state.messageEdit != null) return false
        val head = state.queuedMessages.firstOrNull { it.immediateInterrupt } ?: return false
        val (next, promoted) = AgentMessageQueueReducer.promoteToInterrupt(state, head.id)
        updateConversation(conversationId, next, updateTimestamp = false)
        if (promoted == null) return false
        return dispatchQueuedMessage(promoted)
    }

    /** 回答 ask_user 的接力：请求结束旧 run，但**不清空队列**；收尾后由 maybeLaunchPendingAskAnswer 接上新回答。 */
    private fun requestInterruptCurrentRun() {
        // 与「停止」共用同一条解析路径：本地映射缺失时也会向服务端对账，
        // 确认没有运行中的 run 就把假运行态收掉，排队消息才有机会被发出。
        // byInterrupt=true：这里的停止只是给「回答」让位的手段，收尾后允许队列头立刻发出；
        // 用户点停止按钮走默认 false，队列一律保留，绝不再自己跑起来。
        requestStop(selectedConversationId, byInterrupt = true)
    }

    /**
     * 运行中「排队发送」：不打断当前回合，按 FIFO 等它自然结束后自动发出。
     *
     * 与 [steerCurrentMessage] 是两条完全不同的路：这里不调 requestStop，
     * 所以界面不会先闪出「未运行」再突然跑起来。用户点发送时的意思通就是
     * 「把这句话记下，等你这轮做完再说」。
     */
    private fun queueCurrentMessage(prompt: String) {
        if (prompt.isBlank() && homeState.pendingImages.isEmpty() &&
            homeState.pendingFileReferences.isEmpty()
        ) {
            return
        }
        if (selectedConversationId?.isReadOnlyExternalArchiveConversation() == true) return
        val queued = QueuedMessageUi(
            id = "queue-${UUID.randomUUID()}",
            content = prompt,
            pendingImages = homeState.pendingImages,
            pendingFileReferences = homeState.pendingFileReferences,
            immediateInterrupt = false,
        )
        // 草稿马上要变成排队消息：**先**清权威草稿表，再落状态。
        // 反过来的话，updateConversation 里的草稿校正看到表里还有这段文字，
        // 会把 enqueue 刚清空的 input 又填回去（界面表现为「排了队，字还在框里」）。
        setDraftText(selectedConversationId, "")
        updateCurrentConversation(AgentMessageQueueReducer.enqueue(homeState, queued))
        Toast.makeText(
            appContext,
            appContext.getString(R.string.chat_queued_hint),
            Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * 回答 `ask_user` 的提问。
     *
     * 与普通发送的关键区别：回答**永远不进排队队列**。
     *
     * 空闲时就是一次普通发送。旧 run 还活着时（模型问完问题通常还要把这一轮收尾）先把回答
     * 落成一条真正的用户消息并落盘，再请求停掉旧 run，等它收尾后用这条消息起新 run。
     * 以前这里走的是「插话入队」：用户看到自己的回答挂在队列条上，切走会话还会一直发不出去，
     * 回来又看到「等你回答」的卡片 —— 2026-10-30 真机反馈。
     *
     * 先落消息还有一个直接好处：任何进程死亡/切换会话都不会让回答消失。
     */
    fun answerPendingAsk(answerText: String) {
        if (rejectWhileMessagesLoading()) return
        if (homeState.isCompacting) return
        val prompt = answerText.trim()
        if (prompt.isBlank()) return
        val conversationId = selectedConversationId
        if (conversationId != null && conversationId.isReadOnlyExternalArchiveConversation()) return
        if (!homeState.isStreaming) {
            sendCurrentMessage(prompt)
            return
        }
        // 运行中停在编辑态：这条输入属于编辑框，落成新消息会把用户刚改的内容变成弃稿。
        if (rejectMessageEditWhileStreaming(prompt)) return
        val target = conversationId ?: newConversationId().also { selectedConversationId = it }
        val runId = "run-${UUID.randomUUID()}"
        val answer = PendingAskAnswer(
            runId = runId,
            messageId = "user-$runId",
            prompt = prompt,
            reasoningEffort = homeState.reasoningEffort,
        )
        // 同一条提问连点两个选项：**后一次选择生效**，并把之前那条回答消息一起换掉。
        // 以前这里直接 return：第一次没发出去（旧 run 迟迟不收尾）之后再点，界面毫无变化，
        // 用户看到的就是「点了没反应、切回来还让我重答」。
        val replaced = pendingAskAnswers.put(target, answer)
        val userMessage = UserMessageUi(
            id = answer.messageId,
            content = prompt,
            images = emptyList(),
        )
        val baseMessages = if (replaced == null) {
            homeState.messages
        } else {
            homeState.messages.filterNot { it.id == replaced.messageId }
        }
        updateCurrentConversation(homeState.copy(messages = baseMessages + userMessage))
        persistConversations()
        if (replaced != null) {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.ask_answer_updated),
                Toast.LENGTH_SHORT,
            ).show()
        }
        requestInterruptCurrentRun()
    }

    /**
     * 旧 run 收尾后把待发的回答接上。
     *
     * 不看「当前选中哪个会话」：回答属于它自己的会话，切走了也得照发 —— 这正是以前排队通道
     * 漏掉的那一段（`drainQueueAfterRun` 只处理选中会话）。
     */
    private fun maybeLaunchPendingAskAnswer(conversationId: String) {
        if (pendingAskAnswers.get(conversationId) == null) return
        val state = conversationsById[conversationId] ?: run {
            pendingAskAnswers.remove(conversationId)
            return
        }
        // 消息还没载入时开新 run，会把空消息表当作会话内容。等载入完成后再接。
        if (state.messagesDeferred) return
        val answer = pendingAskAnswers.takeIfIdle(
            conversationId = conversationId,
            isStreaming = state.isStreaming,
            isCompacting = state.isCompacting,
        ) ?: return
        launchConversationRun(
            conversationId = conversationId,
            runId = answer.runId,
            prompt = answer.prompt,
            images = emptyList(),
            // 回答已经在 messages 里；history 由 launchConversationRun 追加这一条用户消息，
            // 和普通发送完全同一条路径。
            history = state.history,
            userHistoryMessage = AgentModelClient.buildUserHistoryMessage(
                text = answer.prompt,
                images = emptyList(),
            ).copy(messageId = answer.messageId),
            messages = state.messages,
            state = state,
            reasoningEffort = answer.reasoningEffort,
        )
    }

    fun sendCurrentMessage(submittedText: String? = null) {
        if (rejectWhileMessagesLoading()) return
        if (homeState.isCompacting) return
        val prompt = (submittedText ?: homeState.input).trim()
        val pendingImages = homeState.pendingImages
        val pendingFileReferences = homeState.pendingFileReferences
        if (homeState.isStreaming) {
            // 运行中停在编辑态：这条输入属于编辑框，不是新消息。入队会把它当新消息、
            // 并顺手清掉 messageEdit（编辑白做），所以明确拒绝并保留现场。
            if (rejectMessageEditWhileStreaming(prompt)) return
            // 运行中发送 = 排队，不结束当前 run；想改变方向请用输入栏的「插话」。
            // 排队保证「发新消息」不会被误当成 steering，等这一轮自然结束后再发。
            queueCurrentMessage(prompt)
            return
        }
        if (prompt.isBlank() && pendingImages.isEmpty() && pendingFileReferences.isEmpty()) {
            return
        }
        homeState.messageEdit?.takeIf { it.preserveFollowingMessages }?.let { edit ->
            val updated = RoleplayConversationReducer.edit(homeState, edit.targetMessageId, prompt) ?: return
            // 编辑正文被写进历史后，这一轮剩下的草稿是「编辑前那串」：先对齐权威草稿表，
            // 再落状态（同排队路径：updateConversation 会按表校正 input）。
            setDraftText(selectedConversationId, edit.previousInput)
            updateCurrentConversation(updated.copy(
                input = edit.previousInput,
                pendingImages = edit.previousImages,
                pendingFileReferences = edit.previousFileReferences,
                messageEdit = null,
                // 「保存编辑」把编辑正文写进了历史，输入框里的那串字从此不再属于草稿：
                // 抬一次同步序号，让输入框按上一版草稿（edit.previousInput）重设。
                inputSyncVersion = updated.inputSyncVersion + 1,
            ))
            refreshConversationSummaries()
            persistConversations()
            return
        }
        val fileReferences = pendingFileReferences.map { it.reference }
        if (
            !AgentFileReferencePolicy.canSend(
                references = fileReferences,
                terminalToolsEnabled = agentBooleanForUi(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            )
        ) {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.state_ui_file_path_reference_requires_opening_the_termina_deca4c),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val runtimePrompt = AgentFileReferencePromptCodec.format(prompt, fileReferences)

        val edit = homeState.messageEdit
        if (edit != null && rejectRevisionWithoutJournal()) return
        if (edit == null && selectedConversationId?.isReadOnlyExternalArchiveConversation() == true) {
            moveCurrentDraftToNewConversation()
        }

        val editBoundary = edit?.let {
            AgentConversationRevisionReducer.boundary(homeState, it.targetMessageId)
        }
        if (edit != null && editBoundary == null) {
            cancelMessageEdit()
            return
        }

        val conversationId = selectedConversationId ?: newConversationId().also {
            selectedConversationId = it
        }
        val runId = "run-${UUID.randomUUID()}"
        val userMessage = UserMessageUi(
            id = editBoundary?.userMessage?.id ?: "user-$runId",
            content = runtimePrompt,
            images = pendingImages.map { it.dataUrl },
            isEdited = editBoundary != null,
        )
        val history = editBoundary?.historyPrefix ?: homeState.history
        val messages = if (editBoundary == null) {
            homeState.messages + userMessage
        } else {
            homeState.messages.take(editBoundary.userMessageIndex) + userMessage
        }
        val userHistoryMessage = AgentModelClient.buildUserHistoryMessage(
            text = runtimePrompt,
            images = pendingImages.toHistoryImages(),
        ).copy(messageId = userMessage.id)

        val currentTitle = conversationTitles[conversationId]
        val oldAutoTitle = editBoundary
            ?.takeIf { it.userMessageIndex == 0 }
            ?.userMessage
            ?.content
            ?.defaultConversationTitleFromMessage()
        val nextAutoTitle = defaultConversationTitle(prompt, fileReferences)
        val title = if (
            editBoundary?.userMessageIndex == 0 &&
            (currentTitle == oldAutoTitle || currentTitle.isNullOrBlank())
        ) {
            nextAutoTitle
        } else {
            currentTitle?.takeIf(String::isNotBlank) ?: nextAutoTitle
        }

        conversationTitles = conversationTitles + (conversationId to title)
        conversationPaneState = conversationPaneState.copy(selectedConversationId = conversationId)
        // 草稿已经变成用户消息：权威表要同步清掉，否则 updateConversation 的草稿校正
        // 会把刚发出去的文字又「恢复」回输入框。
        draftsByConversation.remove(conversationId)
        launchConversationRun(
            conversationId = conversationId,
            runId = runId,
            prompt = runtimePrompt,
            images = pendingImages,
            history = history,
            userHistoryMessage = userHistoryMessage,
            messages = messages,
            state = homeState.copy(
                journal = editBoundary?.journalPrefix ?: homeState.journal,
                input = "",
                pendingImages = emptyList(),
                pendingFileReferences = emptyList(),
                messageEdit = null,
                // 发送成功是「草稿被消费」的其中一条路径。输入栏不再自己 clearText，
                // 没有这个序号输入框会一直停在刚发出去的那段文字上。
                inputSyncVersion = homeState.inputSyncVersion + 1,
            ),
            reasoningEffort = homeState.reasoningEffort,
        )
    }

    /**
     * 把权威草稿表对齐到指定文本（空串等于清掉）。
     *
     * 单独抽出来是因为顺序真的重要：`updateConversation` 会用这张表校正 `input`，
     * 所以「这次要清掉 / 要换成别的文字」必须**先**写表，再落状态。
     */
    private fun setDraftText(conversationId: String?, text: String) {
        val id = conversationId ?: return
        if (text.isEmpty()) draftsByConversation.remove(id) else draftsByConversation[id] = text
    }

    /**
     * 输入草稿写回会话状态（仅内存）。
     *
     * 输入框的文本只存在于 TextFieldState：一旦 `updateConversation` 用会话状态覆盖
     * `homeState`（恢复提示、后台结果、刷新模型都会），输入框会按旧的 `input` 重设，用户
     * 正在打的字就没了；切换会话时新组件也只拿得到旧状态里的空串。这里只写 map、不写
     * `homeState.input`，避免每个字符都让整段聊天重组。
     */
    fun updateDraft(text: String) {
        val conversationId = selectedConversationId
        if (conversationId == null) {
            if (homeState.input != text) homeState = homeState.copy(input = text)
            return
        }
        draftsByConversation[conversationId] = text
        val current = conversationsById[conversationId] ?: return
        if (current.messageEdit != null || current.input == text) return
        conversationsById = conversationsById + (conversationId to current.copy(input = text))
    }

    fun beginMessageEdit(messageId: String) {
        if (rejectWhileMessagesLoading()) return
        if (homeState.isStreaming || homeState.messageEdit != null) return
        if (rejectRevisionWithoutJournal()) return
        if (homeState.roleplay != null) {
            if (messageId !in homeState.roleplayMessages.links) return
            val content = when (val message = homeState.messages.firstOrNull { it.id == messageId }) {
                is UserMessageUi -> message.content
                is AgentMessageUi -> message.content
                else -> return
            }
            updateCurrentConversation(homeState.copy(
                input = content, pendingImages = emptyList(), pendingFileReferences = emptyList(),
                messageEdit = MessageEditUiState(messageId, homeState.input, homeState.pendingImages,
                    homeState.pendingFileReferences, hasLaterTurns = false, preserveFollowingMessages = true),
            ))
            return
        }
        val boundary = AgentConversationRevisionReducer.boundary(homeState, messageId) ?: return
        val images = boundary.userMessage.images.mapIndexed { index, dataUrl ->
            PendingImageUi(
                id = "edit-${boundary.userMessage.id}-$index",
                uri = dataUrl,
                dataUrl = dataUrl,
                mimeType = dataUrl.imageMimeType(),
            )
        }
        val parsedPrompt = AgentFileReferencePromptCodec.parse(boundary.userMessage.content)
        val fileReferences = parsedPrompt.references.mapIndexed { index, reference ->
            PendingFileReferenceUi(
                id = "edit-${boundary.userMessage.id}-file-$index",
                reference = reference,
            )
        }
        updateCurrentConversation(
            homeState.copy(
                input = parsedPrompt.request,
                pendingImages = images,
                pendingFileReferences = fileReferences,
                messageEdit = MessageEditUiState(
                    targetMessageId = boundary.userMessage.id,
                    previousInput = homeState.input,
                    previousImages = homeState.pendingImages,
                    previousFileReferences = homeState.pendingFileReferences,
                    hasLaterTurns = boundary.laterTurnCount > 0,
                ),
            )
        )
        if (boundary.contextWasCompacted) showCompactedRevisionNotice()
    }

    fun cancelMessageEdit() {
        val edit = homeState.messageEdit ?: return
        updateCurrentConversation(
            homeState.copy(
                input = edit.previousInput,
                pendingImages = edit.previousImages,
                pendingFileReferences = edit.previousFileReferences,
                messageEdit = null,
            )
        )
    }

    fun messageRevisionImpact(messageId: String): MessageRevisionImpact? =
        if (homeState.journalDeferred) null
        else if (homeState.roleplay != null && messageId.startsWith("greeting-")) MessageRevisionImpact(0)
        else AgentConversationRevisionReducer.boundary(homeState, messageId)?.let { boundary ->
            MessageRevisionImpact(laterTurnCount = boundary.laterTurnCount)
        }

    fun deleteMessageTurn(messageId: String) {
        if (rejectWhileMessagesLoading()) return
        if (homeState.isStreaming || homeState.messageEdit != null) return
        if (rejectRevisionWithoutJournal()) return
        val conversationId = selectedConversationId ?: return
        if (homeState.roleplay != null && messageId.startsWith("greeting-")) {
            val originalId = homeState.roleplayMessages.links[messageId]?.transcriptMessageId ?: return
            val updated = homeState.copy(
                messages = homeState.messages.filterNot { it.id == messageId },
                journal = homeState.journal.filterNot { it.messageId == originalId },
                history = homeState.history.filterNot { it.messageId == originalId || it.contextSummary },
                roleplayMessages = homeState.roleplayMessages.copy(
                    links = homeState.roleplayMessages.links - messageId,
                    revisions = homeState.roleplayMessages.revisions - messageId,
                ),
            )
            updateConversation(conversationId, updated.copy(history = RoleplayConversationReducer.projectJournal(updated)))
            refreshConversationSummaries()
            persistConversations()
            return
        }
        val removed = AgentConversationRevisionReducer.deleteFromTurn(homeState, messageId) ?: return
        val retainedIds = removed.messages.mapTo(mutableSetOf()) { it.id }
        val revised = if (removed.roleplay == null) removed else removed.copy(roleplayMessages = removed.roleplayMessages.copy(
            links = removed.roleplayMessages.links.filterKeys { it in retainedIds },
            revisions = removed.roleplayMessages.revisions.filterKeys { it in retainedIds },
        ))
        if (revised.messages.isEmpty()) {
            conversationsById = conversationsById - conversationId
            refreshAnyRunningConversation()
            conversationTitles = conversationTitles - conversationId
            conversationUpdatedAt = conversationUpdatedAt - conversationId
            fileAttachmentOwnerVersion += 1
            selectedConversationId = null
            homeState = emptyChatState(defaultThinkingEnabled)
            refreshConversationModel()
            conversationPaneState = conversationPaneState.copy(selectedConversationId = null)
            refreshConversationSummaries()
            persistConversations()
            return
        }
        updateConversation(conversationId, revised)
        refreshConversationSummaries()
        persistConversations()
    }

    fun regenerateMessage(messageId: String) {
        if (rejectWhileMessagesLoading()) return
        if (homeState.isStreaming || homeState.messageEdit != null) return
        if (rejectRevisionWithoutJournal()) return
        val conversationId = selectedConversationId ?: return
        if (homeState.roleplay != null) {
            val target = homeState.messages.filterIsInstance<AgentMessageUi>().firstOrNull { it.id == messageId } ?: return
            val prefix = RoleplayConversationReducer.rewriteHistory(homeState, messageId) ?: return
            val runId = "run-${UUID.randomUUID()}"
            launchConversationRun(
                conversationId, runId, target.content, emptyList(), prefix, null, homeState.messages,
                homeState.copy(roleplayMessages = homeState.roleplayMessages.copy(
                    pendingRewrites = homeState.roleplayMessages.pendingRewrites + (runId to messageId),
                )), homeState.reasoningEffort, operation = AgentRuntimeWire.OP_REWRITE_REPLY,
                rewriteTargetMessageId = messageId,
            )
            return
        }
        val boundary = AgentConversationRevisionReducer.boundary(homeState, messageId) ?: return
        val images = boundary.userMessage.images.mapIndexed { index, dataUrl ->
            PendingImageUi(
                id = "regenerate-${boundary.userMessage.id}-$index",
                uri = dataUrl,
                dataUrl = dataUrl,
                mimeType = dataUrl.imageMimeType(),
            )
        }
        val runId = "run-${UUID.randomUUID()}"
        val userHistoryMessage = AgentModelClient.buildUserHistoryMessage(
            text = boundary.userMessage.content,
            images = images.toHistoryImages(),
        )
        if (boundary.contextWasCompacted) showCompactedRevisionNotice()
        launchConversationRun(
            conversationId = conversationId,
            runId = runId,
            prompt = boundary.userMessage.content,
            images = images,
            history = boundary.historyPrefix,
            userHistoryMessage = userHistoryMessage,
            messages = homeState.messages.take(boundary.userMessageIndex + 1),
            state = homeState.copy(journal = boundary.journalPrefix),
            reasoningEffort = homeState.reasoningEffort,
        )
    }

    fun compactCurrentContext() {
        if (rejectWhileMessagesLoading()) return
        val conversationId = selectedConversationId ?: return
        if (runIdForConversation(conversationId) != null || !homeState.canCompactContext || modelPickerState.isChanging) return
        launchConversationRun(
            conversationId = conversationId,
            runId = java.util.UUID.randomUUID().toString(),
            prompt = "", images = emptyList(), history = homeState.history,
            userHistoryMessage = null, messages = homeState.messages, state = homeState,
            reasoningEffort = homeState.reasoningEffort, operation = AgentRuntimeWire.OP_COMPACT,
        )
    }

    private fun launchConversationRun(
        conversationId: String,
        runId: String,
        prompt: String,
        images: List<PendingImageUi>,
        history: List<AgentModelClient.ConversationMessage>,
        userHistoryMessage: AgentModelClient.ConversationMessage?,
        messages: List<AgentChatMessageUi>,
        state: AgentChatHomeUiState,
        reasoningEffort: ReasoningEffort,
        operation: String = AgentRuntimeWire.OP_CHAT,
        rewriteTargetMessageId: String? = null,
        /**
         * 是否把这条 run 设成「当前会话的 primary」。
         *
         * 后台会话（用户没看着）的 run 传 false：`currentRunId` / `currentRunJob` 是界面
         * 「停止」按钮指向的目标，不能被一条看不见的 run 抢走。
         */
        asPrimary: Boolean = true,
        /**
         * 这一轮是不是自动续跑发起的。
         *
         * 只有自动续跑允许不清零链长；其余入口（用户发消息、语音、分享、回答提问、
         * 排队消息的补发）都说明用户在场或在推动对话，应当开始新的一条链。
         */
        automatic: Boolean = false,
    ) {
        if (!automatic) resetBackgroundResumeChain(conversationId)
        runConversationIds[runId] = conversationId
        if (asPrimary) currentRunId = runId
        // 发送即绑定：会话记住本轮实际使用的模型，回落到默认模型时也随之更新。
        val runModelId = state.effectiveModelId

        updateConversation(
            conversationId,
            state.copy(
                modelId = runModelId,
                isStreaming = true,
                // 新 run 从「未被暂停」开始：上一条 run 的暂停标记不能带到新任务上。
                isPaused = false,
                history = if (operation == AgentRuntimeWire.OP_REWRITE_REPLY) state.history else history + listOfNotNull(userHistoryMessage),
                // deferred 会话不把新增写进 journal：空表退回 history 会用压缩过的投影冒充完整历史。
                // 落盘侧同样拒写（见 AgentConversationStore.saveChanges），库里原有完整历史保持不变。
                journal = if (state.journalDeferred) emptyList() else state.journal.ifEmpty { state.history } +
                    listOfNotNull(userHistoryMessage),
                isCompacting = operation == AgentRuntimeWire.OP_COMPACT,
                messages = messages,
                messageEdit = null,
            )
        )
        refreshConversationSummaries()
        val initialPersistence = persistConversations()

        val preparationJob = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            // write-ahead：用户消息未提交前不把可能产生副作用的 run 交给 Runtime。
            // 但保存不能无限等：没有上限时界面会一直停在“停止”按钮上，用户既等不到回答
            // 也没法重试（实测过一次）。超时按保存失败处理，至少给出一条明确的错误。
            val persisted = withTimeoutOrNull(PREPARE_TIMEOUT_MS) { initialPersistence.await() }
            if (persisted != true) {
                AndroidAgentLogger.warnThrottled("agent_run_prepare_timeout") {
                    "Agent run preparation failed: persisted=$persisted"
                }
                withContext(Dispatchers.Main) {
                    applyRunResult(
                        runId,
                        AgentRuntimeWire.RunResult(
                            runId = runId,
                            ok = false,
                            content = "",
                            error = appContext.getString(R.string.conversation_persistence_failed),
                        )
                    )
                }
                return@launch
            }
            state.roleplay?.let { binding ->
                try {
                    CharacterRepository.initialize(appContext)
                    CharacterRepository.get(binding.characterId)?.let { profile ->
                        val updatedBinding = binding.copy(
                            cardSnapshotJson = CharacterCardCodec.encodeJson(profile.card),
                            characterName = profile.card.name, avatarPath = profile.avatarPath,
                        )
                        val saved = withContext(Dispatchers.Main) {
                            conversationsById[conversationId]?.let { current ->
                                updateConversation(conversationId, current.copy(roleplay = updatedBinding))
                            }
                            persistConversations()
                        }.await()
                        check(saved) { appContext.getString(R.string.agent_error_roleplay_settings_save) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    AndroidAgentLogger.warnThrottled("character_run_prepare_failed") {
                        "Character preparation failed: type=${failure.safeLogType()}"
                    }
                    withContext(Dispatchers.Main) {
                        applyRunResult(runId, AgentRuntimeWire.RunResult(runId, false, "", appContext.getString(R.string.agent_error_roleplay_settings_io),
                            operation = operation, rewriteTargetMessageId = rewriteTargetMessageId))
                    }
                    return@launch
                }
            }
            val permittedReasoningEffort = if (
                agentBooleanForUi(Prefs.Keys.AGENT_THINKING_ENABLED)
            ) {
                reasoningEffort
            } else {
                ReasoningEffort.OFF
            }
            val config = runtimeConfigFor(runModelId, permittedReasoningEffort)
            if (config == null) {
                withContext(Dispatchers.Main) {
                    applyRunResult(
                        runId,
                        AgentRuntimeWire.RunResult(
                            runId = runId,
                            ok = false,
                            content = "",
                            error = appContext.getString(R.string.state_ui_please_configure_the_model_provider_and_model_fi_a36e15),
                        )
                    )
                }
                return@launch
            }
            val modelImages = images.map { p ->
                AgentModelClient.ModelImage(
                    reference = p.uri,
                    mimeType = p.mimeType,
                    bytes = 0,
                    source = "user_attach",
                )
            }
            if (withContext(Dispatchers.Main) { runId in stopRequestedAt }) {
                withContext(Dispatchers.Main) {
                    applyRunResult(runId, AgentRuntimeWire.RunResult(runId, false, "", appContext.getString(R.string.agent_notice_stopped), operation = operation))
                }
                return@launch
            }
            AndroidAgentLogger.info("Agent run prepare done: id=${runId.take(8)}")
            val result = runInterruptible {
                AgentRuntimeClient(appContext, AndroidAgentLogger).run(
                    request = AgentRuntimeWire.RunRequest(
                        operation = operation,
                        rewriteTargetMessageId = rewriteTargetMessageId,
                        runId = runId,
                        prompt = prompt,
                        config = config,
                        images = modelImages,
                        history = history,
                        handoff = AgentRuntimeWire.EntryHandoff(
                            id = runId,
                            source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                            payload = conversationId,
                        ),
                    ),
                    onEvent = { event -> enqueueRunEvent(runId, event) },
                )
            }
            AndroidAgentLogger.info(
                "Agent run returned: id=${runId.take(8)} ok=${result.ok} err=${result.error?.take(40)}"
            )
            withContext(Dispatchers.Main) {
                applyRunResult(runId, result, acknowledgeRuntimeResult = true)
            }
        }
        if (asPrimary) currentRunJob = preparationJob
        if (!RootAccess.isGranted) {
            val leaseId = "prepare:$runId"
            val acquired = AgentExecutionService.acquire(appContext, leaseId) {
                scope.launch(Dispatchers.Main.immediate) {
                    if (currentRunId == runId) stopCurrentRun()
                }
            }
            if (!acquired) {
                preparationJob.cancel()
                applyRunResult(runId, AgentRuntimeWire.RunResult(
                    runId = runId,
                    ok = false,
                    content = "",
                    error = appContext.getString(R.string.capability_background_failed),
                ))
                return
            }
            preparationJob.invokeOnCompletion { AgentExecutionService.release(leaseId) }
        }
        preparationJob.start()
    }

    private fun List<PendingImageUi>.toHistoryImages(): List<AgentModelClient.ModelImage> =
        map { image ->
            AgentModelClient.ModelImage(
                reference = image.dataUrl,
                mimeType = image.mimeType,
                bytes = image.dataUrl.length,
                source = image.uri,
            )
        }

    private fun String.imageMimeType(): String =
        takeIf { startsWith("data:") }
            ?.substringAfter("data:")
            ?.substringBefore(';')
            ?.takeIf { it.startsWith("image/") }
            ?: "image/jpeg"

    private fun String.defaultConversationTitle(): String =
        lineSequence().firstOrNull().orEmpty().trim().take(MAX_TITLE_CHARS)

    private fun defaultConversationTitle(
        request: String,
        references: List<AgentFileReference>,
    ): String = AgentFileReferencePolicy
        .titleSource(request, references)
        .defaultConversationTitle()

    private fun String.defaultConversationTitleFromMessage(): String {
        val parsed = AgentFileReferencePromptCodec.parse(this)
        return defaultConversationTitle(parsed.request, parsed.references)
    }

    /**
     * journal 因体积过大没有载入内存的会话，不能改写历史。
     *
     * 为什么必须拒绝而不是「凑合算一个边界」：编辑/重试/删除都要靠 journal 精确定位用户轮次，
     * 此刻 journal 是空表，唯一能退回的 history 可能已经被摘要压缩过 —— 用它切分会把早期原文
     * 永久丢掉（数据损坏），而不是省内存。明确拒绝比静默改坏数据诚实。
     */
    /**
     * 切走之后释放其它会话的消息内存。
     *
     * 为什么这么做：`messages` 一旦载入就常驻，切走不释放。实测四个会话（共 20,238 条）
     * 依次打开后 Dalvik 从 64.9MB 涨到 76.9MB（docs §155），而用户真正在看的只有一个。
     *
     * 为什么不会丢数据：释放时把 `messagesDeferred` 一起置 true，落盘侧看到它就整段跳过
     * 消息写入（AgentConversationStore.saveChanges）；同时保存基线也换成「未载入」版本，
     * 否则基线自己还持有整份消息。改写类入口（发送 / 编辑 / 删除 / 重新生成 / 压缩）
     * 都会先经过 rejectWhileMessagesLoading()，此期间只会提示「正在载入」。
     */
    private fun scheduleMessageRelease(activeId: String?) {
        messageReleaseJob?.cancel()
        messageReleaseJob = scope.launch {
            // 延迟一小段再释放：连续切换会话时，不必把上一个会话刚载入的消息立刻丢掉。
            delay(MessageReleaseDelayMs)
            if (selectedConversationId != activeId) return@launch
            releaseInactiveMessageLoads(activeId)
        }
    }

    private suspend fun releaseInactiveMessageLoads(activeId: String?) {
        val messageIds = mutableSetOf<String>()
        val runtimeIds = mutableSetOf<String>()
        conversationsById.forEach { (id, state) ->
            if (id == activeId || state.isStreaming || state.messageEdit != null || state.isCompacting) return@forEach
            if (shouldReleaseConversationMessages(
                    isActive = id == activeId,
                    isStreaming = state.isStreaming,
                    isEditing = state.messageEdit != null,
                    isCompacting = state.isCompacting,
                    messagesDeferred = state.messagesDeferred,
                    messageCount = state.messages.size,
                )
            ) {
                messageIds += id
            }
            if (!state.historyDeferred || !state.journalDeferred) runtimeIds += id
        }
        val ids = messageIds + runtimeIds
        if (ids.isEmpty()) return
        ids.forEach { id ->
            val state = conversationsById[id] ?: return@forEach
            val releaseMessages = id in messageIds
            updateConversation(
                id,
                state.copy(
                    messages = if (releaseMessages) emptyList() else state.messages,
                    messagesDeferred = state.messagesDeferred || releaseMessages,
                    history = emptyList(),
                    historyDeferred = true,
                    journal = emptyList(),
                    journalDeferred = true,
                    previewMessage = if (releaseMessages) state.messages.lastOrNull() else state.previewMessage,
                ),
                updateTimestamp = false,
            )
        }
        val persistence = conversationPersistence
        withContext(Dispatchers.IO) {
            ids.forEach { persistence.release(it) }
        }
        AndroidAgentLogger.info(
            "conversation.memory.released count=${ids.size} messages=${messageIds.size} runtime=${runtimeIds.size}"
        )
    }

    /**
     * 切换会话后按需把消息读进内存（启动时只载入当前会话，见 [AgentChatUiState.messagesDeferred]）。
     */
    private fun loadDeferredMessages(conversationId: String) {
        if (conversationId in deferredMessageLoads) return
        deferredMessageLoads = deferredMessageLoads + conversationId
        scope.launch(Dispatchers.IO) {
            try {
                val ok = ensureMessagesLoaded(listOf(conversationId), requireSelected = true)
                if (ok) {
                    // 待发的回答要在消息真的回到内存之后才能起 run，否则会把空消息表
                    // 写回会话（界面上整段聊天被清空）。
                    withContext(Dispatchers.Main) { maybeLaunchPendingAskAnswer(conversationId) }
                }
                if (!ok) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            appContext,
                            appContext.getString(R.string.conversation_messages_load_failed_1f6a92),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AndroidAgentLogger.error(
                    "Agent conversation messages load failed: ${error.safeLogType()}",
                )
            } finally {
                // 无论成功、失败还是抛异常都要放开，否则这个会话会永远卡在「正在载入」。
                withContext(NonCancellable) {
                    withContext(Dispatchers.Main) {
                        deferredMessageLoads = deferredMessageLoads - conversationId
                    }
                }
            }
        }
    }

    /**
     * 按需把某个会话的 history 读回内存（启动时非当前会话不还原）。
     */
    private fun loadDeferredHistory(conversationId: String) {
        if (!deferredHistoryLoads.add(conversationId)) return
        scope.launch(Dispatchers.IO) {
            try {
                val startedNs = System.nanoTime()
                val history = AgentConversationStore.loadHistory(appContext, conversationId)
                AndroidAgentLogger.info(
                    "history load ${conversationId.take(13)} rows=${history.size} " +
                        "ms=${(System.nanoTime() - startedNs) / 1_000_000}",
                )
                withContext(Dispatchers.Main) {
                    if (selectedConversationId != conversationId) return@withContext
                    val current = conversationsById[conversationId] ?: return@withContext
                    if (!current.historyDeferred) return@withContext
                    val updated = current.copy(history = history, historyDeferred = false)
                    updateConversation(conversationId, updated, updateTimestamp = false)
                    conversationPersistence.adopt(
                        conversationId = conversationId,
                        state = updated,
                        title = conversationTitles[conversationId].orEmpty(),
                        updatedAt = conversationUpdatedAt[conversationId] ?: 0L,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AndroidAgentLogger.error("Agent conversation history load failed: ${error.safeLogType()}")
            } finally {
                withContext(NonCancellable) {
                    withContext(Dispatchers.Main) { deferredHistoryLoads.remove(conversationId) }
                }
            }
        }
    }

    /**
     * 按需把体积在预算内的 journal 补回内存；超大会话返回 null，继续保持 deferred。
     */
    private fun loadDeferredJournal(conversationId: String) {
        if (!deferredJournalLoads.add(conversationId)) return
        scope.launch(Dispatchers.IO) {
            try {
                val startedNs = System.nanoTime()
                val journal = AgentConversationStore.loadJournalIfWithinLimit(appContext, conversationId)
                if (journal == null) {
                    AndroidAgentLogger.info("journal deferred large id=${conversationId.take(13)}")
                    return@launch
                }
                AndroidAgentLogger.info(
                    "journal load ${conversationId.take(13)} rows=${journal.size} " +
                        "ms=${(System.nanoTime() - startedNs) / 1_000_000}",
                )
                withContext(Dispatchers.Main) {
                    if (selectedConversationId != conversationId) return@withContext
                    val current = conversationsById[conversationId] ?: return@withContext
                    if (!current.journalDeferred) return@withContext
                    val updated = current.copy(journal = journal, journalDeferred = false)
                    updateConversation(conversationId, updated, updateTimestamp = false)
                    conversationPersistence.adopt(
                        conversationId = conversationId,
                        state = updated,
                        title = conversationTitles[conversationId].orEmpty(),
                        updatedAt = conversationUpdatedAt[conversationId] ?: 0L,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AndroidAgentLogger.error("Agent conversation journal load failed: ${error.safeLogType()}")
            } finally {
                withContext(NonCancellable) {
                    withContext(Dispatchers.Main) { deferredJournalLoads.remove(conversationId) }
                }
            }
        }
    }

    /**
     * 把指定会话的消息读进内存，成功与否返回给调用方。
     *
     * 载入后把这份内容登记为保存基线：否则下一次保存会把「基线是空表」误判成
     * 「整个会话被清空」，白白重写几千条消息。
     */
    private suspend fun ensureMessagesLoaded(
        conversationIds: Collection<String>,
        requireSelected: Boolean = false,
    ): Boolean {
        var ok = true
        for (id in conversationIds.distinct()) {
            if (requireSelected && selectedConversationId != id) continue
            if (conversationsById[id]?.messagesDeferred != true) continue
            // 界面自己发起的 run 也并进去：切走再切回来时，运行时可能已经把主 run 让给了别人。
            val locallyObservedRunId = withContext(Dispatchers.Main) { currentRunId }
            val loaded = runCatching {
                AgentConversationStore.loadMessages(
                    appContext,
                    id,
                    extraRunIds = setOfNotNull(locallyObservedRunId),
                )
            }
                .onFailure { error ->
                    AndroidAgentLogger.error(
                        "Agent conversation messages load failed: ${error.safeLogType()}",
                    )
                }
                .getOrNull()
            if (loaded == null) {
                ok = false
                continue
            }
            withContext(Dispatchers.Main) {
                if (requireSelected && selectedConversationId != id) return@withContext
                val current = conversationsById[id] ?: return@withContext
                if (!current.messagesDeferred) return@withContext
                val updated = current.copy(messages = loaded.messages, messagesDeferred = false, previewMessage = null)
                updateConversation(id, updated, updateTimestamp = false)
                pendingAttachRuns
                    .filterValues { it == id }
                    .keys
                    .toList()
                    .forEach { runId ->
                        pendingAttachRuns.remove(runId)
                        attachRunningRun(runId, asPrimary = false)
                    }
                if (!loaded.unaligned) {
                    conversationPersistence.adopt(
                        conversationId = id,
                        state = updated,
                        title = conversationTitles[id].orEmpty(),
                        updatedAt = conversationUpdatedAt[id] ?: 0L,
                    )
                }
                refreshConversationSummaries()
            }
        }
        return ok
    }

    /** 该会话的 journal 是否正在按需载入，避免用户连点触发多次全量读取。 */
    private val journalLoadsInFlight = mutableSetOf<String>()

    private fun prepareJournalForRevision(conversationId: String) {
        if (!journalLoadsInFlight.add(conversationId)) return
        Toast.makeText(
            appContext,
            appContext.getString(R.string.conversation_journal_loading_3a71d5),
            Toast.LENGTH_SHORT,
        ).show()
        scope.launch {
            var loaded = false
            var tooLargeChars = 0L
            try {
                val result = AgentConversationStore.loadJournalResult(appContext, conversationId)
                loaded = !result.journal.isNullOrEmpty()
                if (result.tooLarge) tooLargeChars = result.referencedChars
                if (loaded) {
                    withContext(Dispatchers.Main) {
                        val state = conversationsById[conversationId] ?: return@withContext
                        updateConversation(
                            conversationId,
                            state.copy(journal = result.journal.orEmpty(), journalDeferred = false),
                        )
                        persistConversations()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AndroidAgentLogger.warn("journal load failed: ${error.safeLogType()}")
            } finally {
                withContext(NonCancellable) {
                    withContext(Dispatchers.Main) {
                        journalLoadsInFlight.remove(conversationId)
                        val message = when {
                            tooLargeChars > 0 -> appContext.getString(
                                R.string.conversation_journal_too_large_5f2c1a,
                                (tooLargeChars / 10_000).coerceAtLeast(1),
                            )
                            loaded -> appContext.getString(R.string.conversation_journal_ready_9c4e02)
                            else -> appContext.getString(R.string.conversation_journal_failed_5d18ab)
                        }
                        Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /**
     * journal 因体积过大没有常驻内存时，按需把它读回来（而不是直接拒绝改写）。
     *
     * 原来的做法是弹一句「历史过大，请新建会话」然后什么都不做 —— 用户被卡在这里，
     * 唯一出路是丢掉这段会话。实际上这段历史的代价只是一次性读取（实测数千条的会话
     * 也就几百毫秒、十几 MB），只有用户真的要编辑 / 重新生成 / 删除时才需要付。
     *
     * 读回后**不自动重放刚才的操作**：重放要在异步回调里重跑一整条 UI 指令
     * （发送、删除、重生成），参数与状态都可能已经变了。让用户再点一次更安全，也更可预期。
     */
    private fun rejectRevisionWithoutJournal(): Boolean {
        if (!homeState.journalDeferred) return false
        val conversationId = selectedConversationId
        if (conversationId == null) {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.state_ui_this_conversation_s_history_is_too_large_a71f3d),
                Toast.LENGTH_LONG,
            ).show()
            return true
        }
        prepareJournalForRevision(conversationId)
        return true
    }

    /**
     * 会话消息还没载入时拒绝改写。
     *
     * 此时 [AgentChatUiState.messages] 是空表，任何「按现有消息推导新状态」的操作都会把会话写空
     * （见 messagesDeferred）。宁可提示「正在载入」，也不要用空表算出结果。
     */
    private fun rejectWhileMessagesLoading(): Boolean {
        if (!homeState.messagesDeferred && !homeState.historyDeferred) return false
        Toast.makeText(
            appContext,
            appContext.getString(R.string.conversation_messages_loading_5c31d8),
            Toast.LENGTH_SHORT,
        ).show()
        return true
    }

    private fun showCompactedRevisionNotice() {
        Toast.makeText(
            appContext,
            appContext.getString(R.string.state_ui_the_earlier_context_has_been_compressed_and_will_cf6c86),
            Toast.LENGTH_LONG,
        ).show()
    }

    fun attachImage(uri: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val image = AgentImageCodec.fromReference(
                    context = appContext,
                    value = uri,
                    source = "user_attach",
                )
                if (image == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            appContext,
                            appContext.getString(R.string.state_ui_unable_to_read_this_image_please_try_again_or_us_d94978),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    return@launch
                }
                val preview = AgentImageCodec.previewFromReference(appContext, image) ?: image
                val pending = PendingImageUi(
                    id = "img-${UUID.randomUUID()}",
                    // 后续发送使用首次读取后的稳定引用，不再依赖 ROM Photo Picker URI 的授权生命周期。
                    uri = image.reference,
                    dataUrl = preview.reference,
                    mimeType = image.mimeType,
                )
                withContext(Dispatchers.Main) {
                    updateCurrentConversation(homeState.copy(pendingImages = homeState.pendingImages + pending))
                }
            } finally {
                val selectedUri = Uri.parse(uri)
                if (selectedUri.scheme == ContentResolver.SCHEME_CONTENT) {
                    runCatching {
                        appContext.contentResolver.releasePersistableUriPermission(
                            selectedUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
            }
        }
    }

    fun removePendingImage(id: String) {
        updateCurrentConversation(homeState.copy(pendingImages = homeState.pendingImages.filterNot { it.id == id }))
    }

    fun attachFiles(uris: List<String>) {
        if (uris.isEmpty()) return
        val workspace = attachmentWorkspace()
        resolveAndAttachFileReferences {
            val gateway = AgentFileReferenceGateway(appContext, AndroidAgentLogger, workspace = workspace)
            uris.map { value ->
                // 应用内选择器给的是绝对路径（scheme 为 null），系统选择器给的是 content URI：
                // 两个入口都得能进来，否则附件菜单在虚拟屏上等于不可用。
                val uri = value.toUri()
                if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                    gateway.resolveDocumentUri(
                        uri = uri,
                        expectedKind = AgentFileReferenceKind.File,
                    )
                } else {
                    gateway.resolveAbsolutePath(value, expectedKind = AgentFileReferenceKind.File)
                }
            }
        }
    }

    fun attachFolder(uri: String) {
        val workspace = attachmentWorkspace()
        val resolved = uri.toUri()
        resolveAndAttachFileReferences {
            val gateway = AgentFileReferenceGateway(appContext, AndroidAgentLogger, workspace = workspace)
            listOf(
                if (resolved.scheme == ContentResolver.SCHEME_CONTENT) {
                    gateway.resolveDocumentUri(
                        uri = resolved,
                        expectedKind = AgentFileReferenceKind.Directory,
                    )
                } else {
                    gateway.resolveAbsolutePath(uri, expectedKind = AgentFileReferenceKind.Directory)
                }
            )
        }
    }

    /** 附件导入到当前对话的工作区，而不是所有对话共用的全局目录。 */
    private fun attachmentWorkspace(): File =
        File(TerminalRuntime.sessionWorkspacePath(appContext, selectedConversationId.orEmpty()))

    fun attachFilePath(path: String) {
        resolveAndAttachFileReferences {
            listOf(AgentFileReferenceGateway(AndroidAgentLogger).resolveAbsolutePath(path))
        }
    }

    fun removePendingFileReference(id: String) {
        updateCurrentConversation(
            homeState.copy(
                pendingFileReferences = homeState.pendingFileReferences.filterNot { it.id == id }
            )
        )
    }

    private fun resolveAndAttachFileReferences(
        resolver: () -> List<AgentFileReferenceGateway.Resolution>,
    ) {
        val ownerVersion = fileAttachmentOwnerVersion
        scope.launch(Dispatchers.IO) {
            val resolutions = resolver()
            val references = resolutions.mapNotNull { resolution ->
                (resolution as? AgentFileReferenceGateway.Resolution.Success)?.reference
            }
            val failures = resolutions.mapNotNull { resolution ->
                (resolution as? AgentFileReferenceGateway.Resolution.Failure)?.error
            }
            withContext(Dispatchers.Main) {
                if (ownerVersion != fileAttachmentOwnerVersion) {
                    Toast.makeText(appContext, appContext.getString(R.string.state_ui_conversation_switched_selected_path_not_added_5bf91e), Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val existingPaths = homeState.pendingFileReferences
                    .mapTo(mutableSetOf()) { it.reference.absolutePath }
                val additions = references
                    .distinctBy { it.absolutePath }
                    .filter { existingPaths.add(it.absolutePath) }
                    .map { reference ->
                        PendingFileReferenceUi(
                            id = "file-${UUID.randomUUID()}",
                            reference = reference,
                        )
                    }
                if (additions.isNotEmpty()) {
                    updateCurrentConversation(
                        homeState.copy(
                            pendingFileReferences = homeState.pendingFileReferences + additions
                        )
                    )
                }
                val message = when {
                    failures.size == 1 && references.isEmpty() -> failures.single().userMessage
                    failures.isNotEmpty() -> appContext.resources.getQuantityString(
                        R.plurals.file_references_added_with_failures,
                        failures.size,
                        additions.size,
                        failures.size,
                    )
                    additions.isEmpty() -> appContext.getString(R.string.state_ui_the_selected_path_has_been_added_42b432)
                    else -> null
                }
                if (message != null) {
                    Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val AgentFileReferenceGateway.Error.userMessage: String
        get() = when (this) {
            AgentFileReferenceGateway.Error.UnsupportedDocumentProvider ->
                appContext.getString(R.string.capability_import_denied)
            AgentFileReferenceGateway.Error.InvalidPath -> appContext.getString(R.string.state_ui_please_enter_a_valid_absolute_path_6afeb4)
            AgentFileReferenceGateway.Error.PathNotFound -> appContext.getString(R.string.state_ui_the_path_does_not_exist_or_is_no_longer_accessib_a9776e)
            AgentFileReferenceGateway.Error.UnsupportedFileType -> appContext.getString(R.string.state_ui_only_supports_normal_files_and_folders_4adea0)
            AgentFileReferenceGateway.Error.TypeMismatch -> appContext.getString(R.string.state_ui_the_selected_project_type_does_not_match_3a5c49)
            AgentFileReferenceGateway.Error.RootUnavailable -> appContext.getString(R.string.state_ui_root_is_not_available_and_the_path_cannot_be_ver_fc4c81)
            AgentFileReferenceGateway.Error.AccessDenied -> appContext.getString(R.string.capability_import_denied)
            AgentFileReferenceGateway.Error.ImportFailed -> appContext.getString(R.string.capability_import_failed)
            AgentFileReferenceGateway.Error.ImportTooLarge -> appContext.getString(R.string.capability_import_too_large)
            AgentFileReferenceGateway.Error.ValidationTimedOut -> appContext.getString(R.string.state_ui_path_verification_timed_out_please_try_again_703687)
        }

    /**
     * 「已经请求过停止」的时间表，只用来做几秒的去抖。
     *
     * 原来是只增不减的集合：第一次停止没生效（服务端繁忙、消息丢了、run 卡在
     * 不可中断的步骤）之后，再点停止就永远 return —— 真机上表现为
     * 「停止按钮完全没反应」。改成按时间节流，用户随时可以重试。
     */
    private val stopRequestedAt = mutableMapOf<String, Long>()

    /**
     * 这条 run 最后一次「停止请求」是谁发的：true = 插话按钮，false = 用户真的想停下。
     *
     * 为什么不能只靠 [stopRequestedAt]：那张表只用于去抖，而且会在「停止监视器」确认
     * run 从活跃列表消失时被清掉。队列要不要在收尾后自动续发，取决于用户**最后一次**
     * 意图；监视器抢先清标记会把「用户点了停止」退化成「自然结束」，真机表现就是
     * 「会话明明已经停了，过几秒又自己跑起来」。
     */
    private val stopIntentByInterrupt = mutableMapOf<String, Boolean>()

    private fun claimStopRequest(runId: String): Boolean {
        val now = System.currentTimeMillis()
        val last = stopRequestedAt[runId]
        if (last != null && now - last < STOP_RETRY_INTERVAL_MS) return false
        stopRequestedAt[runId] = now
        return true
    }

    /** [resolveRunIdForConversation] 的三种结局：有 run / 确实没有 / 问不到。 */
    private sealed interface RunResolution {
        data class Active(val runId: String) : RunResolution
        data object NoActiveRun : RunResolution
        data object Unavailable : RunResolution
    }

    /**
     * 解析某会话当前可操作的 runId。
     *
     * 正常情况直接用本地映射，不发 IPC。但恢复流程可能只把会话标成「运行中」
     * 而没留下映射（服务端快照与本地对不齐、attach 超时等），此时静默返回
     * 会让停止 / 插话变成死按钮 —— 真机上复现过。本地查不到就向服务端要一次
     * 全量快照：找到就补上映射继续；明确没有就清掉假运行态；
     * **查询失败时什么都不动**，绝不把「问不到」当成「没在跑」。
     */
    private suspend fun resolveRunIdForConversation(conversationId: String?): RunResolution {
        val id = conversationId ?: return RunResolution.NoActiveRun
        runIdForConversation(id)?.let { return RunResolution.Active(it) }
        val query = withContext(Dispatchers.IO) {
            AgentRuntimeClient(appContext, AndroidAgentLogger).queryActiveRuns()
        }
        return withContext(Dispatchers.Main) {
            when (query) {
                is AgentRuntimeClient.ActiveRunsQuery.Known -> {
                    val match = query.runs.firstOrNull { it.conversationId == id }
                    if (match == null) {
                        clearStaleStreaming(id)
                        RunResolution.NoActiveRun
                    } else {
                        runConversationIds[match.runId] = id
                        RunResolution.Active(match.runId)
                    }
                }
                AgentRuntimeClient.ActiveRunsQuery.Unavailable -> RunResolution.Unavailable
            }
        }
    }

    /**
     * 清掉「界面说在跑、却没有任何本地 run 指向它」的假运行标记。
     *
     * 只归零运行态，不动消息与历史；真正的 run 结束仍由 applyRunResult 收尾。
     */
    private fun clearStaleStreaming(conversationId: String) {
        if (runConversationIds.containsValue(conversationId)) return
        val state = conversationsById[conversationId] ?: return
        if (!state.isStreaming && !state.isPaused && !state.isCompacting && !state.isGuiWaiting) return
        updateConversation(
            conversationId,
            state.copy(isStreaming = false, isPaused = false, isCompacting = false, isGuiWaiting = false),
            updateTimestamp = false,
        )
        refreshConversationSummaries()
    }

    /**
     * 调试专用：只把**本地**运行态弄丢，服务端那条 run 照常跑。
     *
     * 复现 §11 那种「客户端以为没在跑、服务端还在跑」的失配 —— 只有失配时才会走到
     * `AgentRuntimeService.supersedeSameConversationRuns`（同会话新 run 顶掉旧 run）。
     * 正常路径不可达：release 构建里 [AgentDebugInjections] 永远不会被赋值。
     *
     * 只归零运行态，不动消息与历史；服务端 run 的终态回来时会按普通失败收尾。
     */
    fun debugForgetLocalRunState(conversationId: String? = null) {
        if (!BuildConfig.DEBUG) return
        val target = conversationId ?: selectedConversationId ?: return
        val dropped = runConversationIds.filterValues { it == target }.keys.toList()
        dropped.forEach { runConversationIds.remove(it) }
        if (currentRunId in dropped) currentRunId = null
        if (dropped.isNotEmpty()) {
            stopRequestedAt.keys.removeAll(dropped.toSet())
            stopIntentByInterrupt.keys.removeAll(dropped.toSet())
        }
        clearStaleStreaming(target)
        AndroidAgentLogger.warn(
            "Agent debug injection: forgot local run state conv=${target.take(13)} " +
                "dropped=${dropped.firstOrNull()?.take(8) ?: "-"} leftRunning=true"
        )
    }

    fun stopCurrentRun() = requestStop(selectedConversationId)

    /**
     * 从会话列表停止某个会话正在跑的 run。
     *
     * 后台 run 在 fix108 之后可以继续跑，但原来只能先切进那个会话再点停止；
     * 列表长按给出入口后，用户不必打断自己正在看的会话。
     */
    fun stopConversationRun(conversationId: String) = requestStop(conversationId)

    /**
     * 停止某会话的 run，并且**每一种结局都要有反馈**。
     *
     * 关键是第三条：以前 `runIdForConversation(...) ?: return` 让「界面显示运行中、
     * 但本地没有 runId」这种状态变成一个点了毫无反应的按钮。现在：
     * 找到并发出停止 / 服务端确认已无任务并清掉假运行态 / 连不上就明说。
     */
    private fun requestStop(conversationId: String?, byInterrupt: Boolean = false) {
        if (conversationId == null) return
        scope.launch {
            when (val resolution = resolveRunIdForConversation(conversationId)) {
                is RunResolution.Active -> {
                    // 意图先落表再去抖：用户先后点「停止」和「插话」时，生效的必须是最后一次点击。
                    stopIntentByInterrupt[resolution.runId] = byInterrupt
                    if (claimStopRequest(resolution.runId)) {
                        withContext(Dispatchers.IO) {
                            AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(resolution.runId)
                        }
                    }
                    // 停止只代表「请求已送达」。cancel 是异步的：run 要等当前这一步（多半是一次
                    // 在飞的模型请求或工具调用）返回后，才会在步骤边界退出。所以「发完立刻查一次」
                    // 几乎必然还查到它活着——真机上就是这样：运行确实停了，界面却一直挂着
                    // “正在执行”，用户只能靠再发一条消息把它顶掉。
                    // 因此这里改成后台轮询到它真的消失为止，并在收尾时给一次明确反馈。
                    watchStoppedRunUntilGone(resolution.runId)
                }
                RunResolution.NoActiveRun -> {
                    // 界面挂着假运行态、服务端其实没有 run：插话入队的消息不会再有 run 收尾
                    // 来衔接，必须在这里放出去，否则用户点插话只看到「已完成」，消息永远躺在队列里。
                    val dispatched = byInterrupt && dispatchPendingInterrupt(conversationId)
                    // 同一个坑对「回答」也成立：回答落在消息里，但不会再有收尾回调来起 run。
                    if (byInterrupt) maybeLaunchPendingAskAnswer(conversationId)
                    toastNotice(
                        if (dispatched) R.string.chat_interrupt_sent_after_finish
                        else R.string.chat_run_already_finished,
                    )
                }
                RunResolution.Unavailable -> toastNotice(R.string.chat_stop_unavailable)
            }
        }
    }

    private var staleHealScheduled = false

    /** attach 没问出来时的对账重试是否已在跑（避免并发叠加多次重试）。 */
    private var attachReconcileScheduled = false

    private fun toastNotice(resId: Int) {
        Toast.makeText(appContext, resId, Toast.LENGTH_SHORT).show()
    }

    /**
     * 启动后的兜底自愈：把「服务端已经不认、本地还挂着」的 run 收掉。
     *
     * 为什么需要它：进程刚起来时 runtime 服务往往还没绑定好，`queryActiveRuns()` 会
     * 8 秒超时返回 Unavailable。一次性的对账在这种情况下只能放弃（把 Unavailable 当
     * 「还在跑」更安全），于是界面就一直显示运行中，用户得自己点一次停止才能解锁。
     * 真机上装包重启后就是这样。
     *
     * 这里每 5 秒重试、最多 30 秒：只要有一次拿到明确快照，就把不在快照里的 checkpoint
     * 删掉并收掉对应会话的运行态。拿到明确快照前绝不动手——宁可多等，也不误杀真在跑的 run。
     */
    private fun healStaleRunsAfterStartup() {
        if (staleHealScheduled) return
        staleHealScheduled = true
        scope.launch {
            repeat(6) {
                delay(5_000L)
                val query = withContext(Dispatchers.IO) {
                    AgentRuntimeClient(appContext, AndroidAgentLogger).queryActiveRuns()
                }
                if (query !is AgentRuntimeClient.ActiveRunsQuery.Known) return@repeat
                val alive = query.runs.mapTo(mutableSetOf()) { it.runId }
                val checkpoints = withContext(Dispatchers.IO) { AgentRunCheckpointStore.list(appContext) }
                val stale = checkpoints.filterNot { it.runId in alive }
                if (stale.isEmpty()) return@launch
                stale.forEach { checkpoint ->
                    val conversationId = AgentUiHandoffPayload
                        .from(checkpoint.handoff.payload)
                        .conversationId
                    AndroidAgentLogger.infoPersisted(
                        "stale heal: 清理不在活跃快照里的 run=${checkpoint.runId} conversation=$conversationId"
                    )
                    withContext(Dispatchers.IO) { AgentRunCheckpointStore.remove(appContext, checkpoint.runId) }
                    if (conversationId.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            runConversationIds.remove(checkpoint.runId)
                            stopRequestedAt.remove(checkpoint.runId)
                            stopIntentByInterrupt.remove(checkpoint.runId)
                            val state = conversationsById[conversationId]
                            if (state?.isStreaming == true) {
                                updateConversation(
                                    conversationId,
                                    state.copy(isStreaming = false, isPaused = false, isCompacting = false, isGuiWaiting = false),
                                )
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) { refreshConversationSummaries() }
                return@launch
            }
        }
    }

    /**
     * 盯着一条「已请求停止」的 run，直到它从服务端的活跃列表里消失。
     *
     * 为什么不 await 在 requestStop 里：单次查询最长要等 8 秒（RESPONSE_TIMEOUT_SECONDS），
     * 而 run 退出可能要在当前步骤跑完之后，同步等会把停止按钮的点击处理整个拖住。
     *
     * 只有「明确查到它不在了」才收掉本地运行态。查询失败（Unavailable）不算不在：
     * 把连不上当成已停止，会让仍在跑的 run 在界面上显示成已停，比多等一会儿更糟。
     */
    private fun watchStoppedRunUntilGone(runId: String, attempts: Int = 12) {
        scope.launch {
            repeat(attempts) { index ->
                delay(if (index == 0) 200L else 1_000L)
                val absent = withContext(Dispatchers.IO) {
                    when (val query = AgentRuntimeClient(appContext, AndroidAgentLogger).queryActiveRuns()) {
                        is AgentRuntimeClient.ActiveRunsQuery.Known ->
                            query.runs.none { it.runId == runId }
                        AgentRuntimeClient.ActiveRunsQuery.Unavailable -> null
                    }
                }
                if (absent == true) {
                    AndroidAgentLogger.infoPersisted("stop watch: run 已退出活跃列表，收尾 runId=$runId 第${index + 1}次查询")
                    withContext(Dispatchers.Main) {
                        setConversationStreaming(runId, false)
                        runConversationIds.remove(runId)
                        // 这里**不能**清 stopRequestedAt：run 的最终结果随后才到，清掉会让
                        // 「用户主动停止」被当成自然结束，队列于是自动续发 —— 真机表现是
                        // 「点了停止、界面已显示未运行，几秒后又自己跑起来」。标记留到
                        // applyRunResult 收尾时统一清理；卡死的记录由 reconcileStaleRunState 兜底。
                        refreshConversationSummaries()
                        toastNotice(R.string.chat_stop_done)
                    }
                    return@launch
                }
            }
            AndroidAgentLogger.infoPersisted("stop watch: $attempts 次查询后仍在活跃列表，放弃收尾 runId=$runId")
            withContext(Dispatchers.Main) {
                toastNotice(R.string.chat_stop_still_running)
            }
        }
    }

    /**
     * 暂停/继续「当前选中会话」的 run（不是全局 primary）。
     *
     * 多会话并发时用户可能正看着一个后台 run：入口必须先解析它自己的 runId，
     * 否则会把另一个会话暂停掉。命令发出后再向服务端查询一次校准：
     * 暂停只在 AgentRunController 的检查点生效，客户端不能把「已发送」当成「已暂停」；
     * 命令发不出去则回滚本地状态，不假装成功。
     */
    fun toggleCurrentRunPause() {
        val conversationId = selectedConversationId ?: return
        scope.launch {
            // 与停止共用解析：本地映射缺失时先向服务端对账，别把按钮做成死键。
            val resolution = resolveRunIdForConversation(conversationId)
            if (resolution !is RunResolution.Active) {
                toastNotice(
                    if (resolution is RunResolution.NoActiveRun) {
                        R.string.chat_run_already_finished
                    } else {
                        R.string.chat_stop_unavailable
                    },
                )
                return@launch
            }
            val runId = resolution.runId
            val target = !(conversationsById[conversationId]?.isPaused == true)
            applyRunPaused(runId, target)
            val sent = withContext(Dispatchers.IO) {
                val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
                if (target) client.pauseRun(runId) else client.resumeRun(runId)
            }
            if (!sent) {
                applyRunPaused(runId, !target)
                return@launch
            }
            val confirmed = withContext(Dispatchers.IO) {
                val query = AgentRuntimeClient(appContext, AndroidAgentLogger).queryActiveRun(runId)
                (query as? AgentRuntimeClient.ActiveRunQuery.Known)
                    ?.takeIf { it.runId == runId }
                    ?.paused
            }
            if (confirmed != null) applyRunPaused(runId, confirmed)
        }
    }

    private fun applyRunPaused(runId: String, paused: Boolean) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationsById[conversationId] ?: return
        if (!state.isStreaming || state.isPaused == paused) return
        updateConversation(conversationId, state.copy(isPaused = paused), updateTimestamp = false)
    }

    private fun isReplyRewrite(runId: String): Boolean =
        conversationsById[conversationIdForRun(runId)]?.roleplayMessages?.pendingRewrites?.containsKey(runId) == true

    private fun restoreRunEvents(runId: String, events: List<AgentEvent>) {
        if (isReplyRewrite(runId)) return
        // 回放可能包含上千个流式分片：先把同一内容块的相邻增量合并成一整段，
        // 否则每个分片都会复制整段思考/正文并重排消息列表，主线程在启动恢复时会卡死（实测 ANR）。
        val replayStartNs = System.nanoTime()
        val replayEvents = compactReplayEvents(events)
        val conversationId = conversationIdForRun(runId) ?: return
        flushPendingRunDelta(runId)
        val state = conversationsById[conversationId] ?: return
        // 恢复是完整快照：先清除同一 run 的旧投影，再一次发布，避免历史增量重复追加
        // 或中途的 Running 状态使已结束的思考重新展开、播放动画。
        replayTraceBatchDepth++
        try {
            // 投影只做纯计算、不碰 Compose 状态，所以可以先算完再一次性提交；
            // 这也是 restoreRunEventsOffMain 能把它整体搬到后台线程的前提。
            val projected = projectReplayMessages(runId, state.messages, replayEvents)
            Snapshot.withMutableSnapshot {
                updateConversation(
                    conversationId = conversationId,
                    state = state.copy(messages = projected),
                    updateTimestamp = false,
                )
            }
        } finally {
            replayTraceBatchDepth--
            if (replayTraceBatchDepth == 0) {
                // 批量期间没有逐事件重算，结束后统一刷新一次；否则侧栏会停在回放前的旧摘要。
                refreshConversationSummaries()
                // 回放耗时进日志：下次真机重启就能看到「事件数 / 合并后数量 / 毫秒」，
                // 不必再靠 ANR 栈反推。
                AndroidAgentLogger.infoPersisted(
                    "run replay $runId events=${events.size} compacted=${replayEvents.size} " +
                        "ms=${(System.nanoTime() - replayStartNs) / 1_000_000}"
                )
            }
        }
    }

    /**
     * 纯投影：把回放事件应用到消息列表，返回新列表。
     *
     * 不读也不写任何 Compose 状态，因此可以在任意线程调用（[restoreRunEventsOffMain] 就是这么用的）。
     * 这里刻意用**独立的** projector 实例：共享实例的 thinkingStartedAt 会被主线程的流式更新
     * 同时读写，后台线程碰它就是数据竞争。
     */
    private fun projectReplayMessages(
        runId: String,
        initialMessages: List<AgentChatMessageUi>,
        replayEvents: List<AgentEvent>,
    ): List<AgentChatMessageUi> {
        // 重建轨迹并按原位置插回：追加到末尾会让上一次被杀的内容跑到最新消息下面，
        // 连带「任务已中断」提示也像刚发生的一样（见 AgentRunReplayProjection）。
        return AgentRunReplayProjection.project(runId, initialMessages, replayEvents)
    }

    /**
     * 启动恢复专用：回放的纯计算放到 Default 线程，主线程只做一次列表替换。
     *
     * 564 条事件在主线程实测 480 ms；事件到几万条时必然顶到 ANR 阈值。搬走计算后，
     * 主线程只剩「读当前消息 → 一次性写入」，耗时只与消息条数有关、与事件数无关。
     * 日志里的 `mainTotal=` 是主线程四段（flush / 合并 / 提交 / 刷新摘要）的实际占用，
     * `commit=` 单独给出提交段；两者都可与 fix147c 的整段耗时直接对比。
     */
    private suspend fun restoreRunEventsOffMain(runId: String, events: List<AgentEvent>) {
        if (isReplyRewrite(runId)) return
        val conversationId = conversationIdForRun(runId) ?: return
        val replayStartNs = System.nanoTime()
        var mainNs = 0L
        val flushStartNs = System.nanoTime()
        flushPendingRunDelta(runId)
        val state = conversationsById[conversationId] ?: return
        val initialMessages = state.messages
        mainNs += System.nanoTime() - flushStartNs
        val compactStartNs = System.nanoTime()
        val replayEvents = compactReplayEvents(events)
        mainNs += System.nanoTime() - compactStartNs
        var commitMs = -1L
        var fallback = false
        replayTraceBatchDepth++
        try {
            val projected = withContext(Dispatchers.Default) {
                projectReplayMessages(runId, initialMessages, replayEvents)
            }
            val current = conversationsById[conversationId]
            if (current == null || current.messages !== initialMessages) {
                // 后台算的时候消息被别的路径改过：退回同步实现，宁可慢一点也不能覆盖新内容。
                fallback = true
                restoreRunEvents(runId, events)
                return
            }
            val commitStartNs = System.nanoTime()
            Snapshot.withMutableSnapshot {
                updateConversation(
                    conversationId = conversationId,
                    state = current.copy(messages = projected),
                    updateTimestamp = false,
                )
            }
            commitMs = (System.nanoTime() - commitStartNs) / 1_000_000
            mainNs += System.nanoTime() - commitStartNs
        } finally {
            replayTraceBatchDepth--
            if (replayTraceBatchDepth == 0) {
                val refreshStartNs = System.nanoTime()
                refreshConversationSummaries()
                mainNs += System.nanoTime() - refreshStartNs
                AndroidAgentLogger.infoPersisted(
                    "run replay $runId events=${events.size} compacted=${replayEvents.size} " +
                        "ms=${(System.nanoTime() - replayStartNs) / 1_000_000} " +
                        "mainTotal=${mainNs / 1_000_000} commit=$commitMs " +
                        "offmain=${if (fallback) 0 else 1}"
                )
            }
        }
    }

    /**
     * 把回放事件里同一 (round, kind, index) 的相邻增量合并。
     *
     * 只合并「紧邻」的增量：中间只要夹了别的事件（块结束、工具调用、用量……）就断开，
     * 因而不会改变事件先后语义；文本与思考都是纯追加，合并安全。
     */
    private fun compactReplayEvents(events: List<AgentEvent>): List<AgentEvent> {
        if (events.size < 2) return events
        val compacted = ArrayList<AgentEvent>(events.size)
        var pending: AgentEvent.AssistantBlockDelta? = null
        val buffer = StringBuilder()
        var pendingChars = 0

        fun drain() {
            val head = pending ?: return
            val unchanged = buffer.length == head.delta.length && pendingChars == head.deltaChars
            compacted += if (unchanged) head else head.copy(
                delta = buffer.toString(),
                deltaChars = pendingChars,
            )
            pending = null
            buffer.setLength(0)
            pendingChars = 0
        }

        for (event in events) {
            val delta = event as? AgentEvent.AssistantBlockDelta
            if (delta != null && delta.kind != AgentEvent.AssistantBlockKind.TOOL_CALL && delta.delta.isNotEmpty()) {
                val head = pending
                if (head != null && head.round == delta.round && head.kind == delta.kind && head.index == delta.index) {
                    buffer.append(delta.delta)
                    pendingChars += delta.deltaChars
                    continue
                }
                drain()
                pending = delta
                buffer.append(delta.delta)
                pendingChars = delta.deltaChars
            } else {
                drain()
                compacted += event
            }
        }
        drain()
        return compacted
    }

    private fun enqueueRunEvent(runId: String, event: AgentEvent) {
        if (event is AgentEvent.AssistantBlockDelta) {
            if (event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL || event.delta.isEmpty()) return

            runEventCoalescer.append(runId, event)?.let { ready ->
                applyRunEvent(runId, ready)
            }
            scheduleRunDeltaFlush(runId)
            return
        }

        flushPendingRunDelta(runId)
        applyRunEvent(runId, event)
    }

    private fun scheduleRunDeltaFlush(runId: String) {
        if (runEventFlushJobs[runId]?.isActive == true) return
        runEventFlushJobs[runId] = scope.launch {
            delay(STREAM_UI_UPDATE_INTERVAL_MS)
            runEventFlushJobs.remove(runId)
            flushPendingRunDelta(runId)
        }
    }

    private fun flushPendingRunDelta(runId: String) {
        runEventFlushJobs.remove(runId)?.cancel()
        runEventCoalescer.flush(runId)?.let { event ->
            applyRunEvent(runId, event)
        }
    }

    private fun applyRunEvent(
        runId: String,
        event: AgentEvent,
        persistSupplement: Boolean = true,
    ) {
        if (isReplyRewrite(runId)) {
            if (event is AgentEvent.RunStarted && runId in stopRequestedAt) scope.launch(Dispatchers.IO) {
                AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(runId)
            }
            return
        }
        when (event) {
            is AgentEvent.UserSupplementReceived ->
                insertSupplementMessage(runId, event.index, event.text, persist = persistSupplement)
            is AgentEvent.GuiControlChanged -> updateGuiWaiting(runId, event.status)
            is AgentEvent.RunStarted -> if (runId in stopRequestedAt) scope.launch(Dispatchers.IO) {
                AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(runId)
            }
            // 流式增量只刷新消息，不推进会话时间戳，也不重算侧栏摘要；其余可见变化两者都更新。
            // 执行中进度同理：它每秒可能来四条，每次都推进时间戳会让会话在侧栏里不停跳到顶部。
            is AgentEvent.AssistantBlockDelta, is AgentEvent.ToolProgress ->
                updateMessages(runId, updateTimestamp = false) { messages ->
                    runMessageProjector.applyEvent(runId, event, messages)
                }
            else -> updateRunTrace(runId) { messages -> runMessageProjector.applyEvent(runId, event, messages) }
        }
    }

    /**
     * 手机控制权在别的 run 手里时，本会话是「排队」不是「卡住」。
     * 只改内存态且不推进时间戳：否则排队会把会话顶到侧栏第一条。
     */
    private fun updateGuiWaiting(runId: String, status: GuiControlStatus) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationsById[conversationId] ?: return
        val waiting = status == GuiControlStatus.Waiting
        if (state.isGuiWaiting == waiting) return
        updateConversation(conversationId, state.copy(isGuiWaiting = waiting), updateTimestamp = false)
    }

    private fun applyRunResult(
        runId: String,
        result: AgentRuntimeWire.RunResult,
        acknowledgeRuntimeResult: Boolean = false,
    ) {
        val deferredConversationId = conversationIdForRun(runId)
            ?.takeIf { conversationsById[it]?.messagesDeferred == true }
        if (deferredConversationId != null) {
            // 空表接终态 = 把整段聊天替换成一条结果；先载入，再走同一条落盘路径。
            scope.launch {
                val ok = ensureMessagesLoaded(listOf(deferredConversationId))
                if (ok) withContext(Dispatchers.Main) {
                    applyRunResult(runId, result, acknowledgeRuntimeResult)
                }
            }
            return
        }
        flushPendingRunDelta(runId)
        val rewriting = result.operation == AgentRuntimeWire.OP_REWRITE_REPLY || isReplyRewrite(runId)
        // 用户点过停止 / 插话才会在这张表里；必须在下面的 remove 之前取，否则永远读到 false。
        val explicitlyStoppedByUser = stopRequestedAt.containsKey(runId)
        val stoppedForInterrupt = stopIntentByInterrupt.remove(runId) == true
        stopRequestedAt.remove(runId)
        if (runId == currentRunId) {
            currentRunId = null
            currentRunJob = null
        }
        if (rewriting) {
            val conversationId = conversationIdForRun(runId)
            val existing = conversationsById[conversationId]
            if (conversationId != null && existing != null && result.contextSnapshotRef.isBlank()) {
                updateConversation(conversationId, RoleplayConversationReducer.applyRewrite(existing, runId, result))
            } else setConversationStreaming(runId, false)
            runMessageProjector.clearRun(runId)
            runConversationIds.remove(runId)
            refreshConversationSummaries()
            persistConversations(onSaved = if (acknowledgeRuntimeResult && result.contextSnapshotRef.isBlank()) {
                { AgentRuntimeClient(appContext, AndroidAgentLogger).ackResult(runId) }
            } else null)
            if (conversationId != null && conversationId != selectedConversationId) {
                scheduleMessageRelease(selectedConversationId)
            }
            return
        }
        updateRunTrace(runId) { messages ->
            runMessageProjector.finishContextCompaction(runId,
                runMessageProjector.finalizeRun(runId, messages),
                if (result.ok) appContext.getString(R.string.agent_notice_compaction_done) else result.error ?: appContext.getString(R.string.agent_notice_compaction_stopped))
        }
        if (result.contextSnapshotRef.isBlank()) {
            applyConversationHistoryResult(runId, result.transcript, result.contextSnapshot, !result.ok || result.contextSnapshot != null)
        }
        when {
            result.operation == AgentRuntimeWire.OP_COMPACT ||
                conversationsById[conversationIdForRun(runId)]?.isCompacting == true -> updateMessages(runId) { messages ->
                    AgentRunMessageProjector.mergeCompactionResultNotice(
                        runId = runId,
                        messages = messages,
                        ok = result.ok,
                        detail = if (result.ok) appContext.getString(R.string.agent_notice_compaction_done) else result.error ?: appContext.getString(R.string.agent_notice_compaction_failed),
                    )
                }
            else -> {
                val notice = when {
                    result.ok && result.content.isNotBlank() -> null
                    result.ok -> SystemNoticeCode.EmptyResult
                    result.error == LEGACY_STOPPED_ERROR ||
                        result.error == SYNTHETIC_STATUS_STOPPED ||
                        result.error == appContext.getString(R.string.agent_notice_stopped) ->
                        SystemNoticeCode.Stopped
                    else -> SystemNoticeCode.RuntimeFailed
                }
                updateMessages(runId) { messages ->
                    AgentRunMessageProjector.applyResult(
                        runId,
                        AgentRunMessageProjector.withoutInterruptedNotice(runId, messages),
                        result.content,
                        notice,
                        detail = result.error.takeIf { notice == SystemNoticeCode.RuntimeFailed },
                    )
                }
            }
        }
        setConversationStreaming(runId, false)
        conversationIdForRun(runId)?.let { id -> conversationsById[id]?.let {
            updateConversation(id, RoleplayConversationReducer.linkRun(it, runId))
        } }
        val completedConversationId = conversationIdForRun(runId)
        runMessageProjector.clearRun(runId)
        runConversationIds.remove(runId)
        refreshConversationSummaries()
        if (completedConversationId != null && completedConversationId != selectedConversationId) {
            // 后台会话跑完后立即安排释放，避免并发 run 的历史一直留在内存里。
            scheduleMessageRelease(selectedConversationId)
        }
        val saved = persistConversations(
            onSaved = if (acknowledgeRuntimeResult && result.contextSnapshotRef.isBlank()) {
                {
                    AgentRuntimeClient(appContext, AndroidAgentLogger).ackResult(runId)
                }
            } else {
                null
            }
        )
        // 排队 / 插话消息要等结果落盘之后再衔接发出：否则「取消」与「新请求」之间会写历史打架。
        if (completedConversationId != null) {
            scope.launch {
                saved.await()
                withContext(Dispatchers.Main) {
                    // 回答优先：它就是这一轮的「下一句」，不需要跟队列抢。
                    // 不看选中会话 —— 用户切走了，旧 run 收尾也得把回答接上。
                    maybeLaunchPendingAskAnswer(completedConversationId)
                    drainQueueAfterRun(
                        conversationId = completedConversationId,
                        result = result,
                        explicitlyStoppedByUser = explicitlyStoppedByUser,
                        stoppedForInterrupt = stoppedForInterrupt,
                    )
                }
            }
        }
    }

    /**
     * run 收尾后衔接队列：队首带「立即发送」标记时优先发出，用户主动停止时保留队列。
     *
     * 上游 3.3.5 的这段接线在合并时整段丢了（[AgentMessageQueueReducer.pollNextToSendAfterRun]
     * 变成只有单测在调用的死代码），旧实现的表现就是：插话会先打断 run，但消息永远停在
     * 「插话中」，用户怎么点都没反应。
     *
     * 只处理当前选中的会话：[dispatchQueuedMessage] 依赖 homeState 与草稿位，后台会话的队列
     * 这里不处理，也**没有**「切回时自动补发」的钩子 —— 它要等那个会话自己再跑一轮，
     * 或用户点队列条的「立即插话发送」。改注释前先确认这条链路仍然成立。
     */
    private fun drainQueueAfterRun(
        conversationId: String,
        result: AgentRuntimeWire.RunResult,
        explicitlyStoppedByUser: Boolean,
        stoppedForInterrupt: Boolean,
    ) {
        if (conversationId != selectedConversationId) return
        val state = conversationsById[conversationId] ?: return
        if (state.queuedMessages.isEmpty()) return
        if (state.isStreaming || state.isCompacting) return
        // 用户正在打字时不抢草稿位；队列原样保留到下一次 run 收尾再试，绝不静默丢消息。
        // 注意这里**没有**「切回会话时补发」的钩子（见上面的函数说明）：切回不会触发
        // drainQueueAfterRun，只能等该会话自己再跑一轮，或用户点队列条的「立即插话发送」。
        val head = state.queuedMessages.first()
        if (state.input.isNotBlank() && head.pendingImages.isEmpty() && head.pendingFileReferences.isEmpty()) {
            // 不抢草稿位，但要把队首的「插话中」降级成「排队中」：否则这条消息永远发不出去，
            // 队列条却一直显示插话中 —— 用户会以为插话卡死（真机实测确实会停在这里）。
            val demoted = AgentMessageQueueReducer.demoteBlockedInterruptHead(state)
            if (demoted !== state) updateConversation(conversationId, demoted, updateTimestamp = false)
            return
        }
        // 回答 ask_user 的接力也会 requestStop（run 的 error 同样是「已停止」），但它要的是「换一条继续说」，
        // 不能算「用户想停下」。只有最后一次点击是停止按钮时，才冻结整个队列。
        val stoppedByUser = AgentMessageQueueReducer.isQueueFrozen(
            explicitlyStoppedByUser = explicitlyStoppedByUser,
            stoppedForInterrupt = stoppedForInterrupt,
            errorLooksStopped = !result.ok && (result.error == LEGACY_STOPPED_ERROR ||
                result.error == SYNTHETIC_STATUS_STOPPED ||
                result.error == appContext.getString(R.string.agent_notice_stopped)),
        )
        val (afterPopState, nextQueued) = AgentMessageQueueReducer.pollNextToSendAfterRun(
            state = state,
            stoppedByUser = stoppedByUser,
        )
        if (afterPopState !== state) updateConversation(conversationId, afterPopState, updateTimestamp = false)
        if (nextQueued != null) dispatchQueuedMessage(nextQueued)
    }

    private fun updateRunTrace(
        runId: String,
        transform: (List<AgentChatMessageUi>) -> List<AgentChatMessageUi>,
    ) {
        val changed = updateMessages(runId, transform = transform)
        // 批量回放期间只改消息，摘要由 restoreRunEvents 结束时统一重算一次。
        if (changed && replayTraceBatchDepth == 0) refreshConversationSummaries()
    }

    private fun insertSupplementMessage(
        runId: String,
        index: Int,
        text: String,
        persist: Boolean = true,
    ) {
        updateMessages(runId) { messages ->
            AgentPendingResultRecovery.mergeSupplements(
                runId = runId,
                supplements = listOf(
                    AgentUiHandoffPayload.Supplement(
                        index = index,
                        text = text,
                        createdAt = System.currentTimeMillis(),
                    )
                ),
                messages = messages,
            )
        }
        refreshConversationSummaries()
        if (persist) persistConversations()
    }

    private fun updateMessages(
        runId: String,
        updateTimestamp: Boolean = true,
        transform: (List<AgentChatMessageUi>) -> List<AgentChatMessageUi>,
    ): Boolean {
        val conversationId = conversationIdForRun(runId) ?: return false
        val state = conversationsById[conversationId] ?: return false
        // 未载入时内存里是空表：任何轨迹投影都会把整段会话替换成几条事件，
        // 用户看到的就是「聊天框被清空」。等载入完成后再重置轨迹。
        if (state.messagesDeferred) return false
        val messages = transform(state.messages)
        // 投影对无可见变化的事件返回原列表；此时不推进会话时间戳，避免请求开始、轮次开始等事件改变排序。
        if (messages === state.messages) return false
        updateConversation(
            conversationId = conversationId,
            state = state.copy(messages = messages),
            updateTimestamp = updateTimestamp,
        )
        return true
    }

    private fun applyConversationHistoryResult(
        runId: String,
        additions: List<AgentModelClient.ConversationMessage>,
        snapshot: AgentContextSnapshot? = null,
        retainPendingSupplements: Boolean = snapshot != null,
    ) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationsById[conversationId] ?: return
        val outcome = AgentRuntimeHistoryReducer.apply(state, runId, additions, snapshot, retainPendingSupplements)
        if (!outcome.alreadyApplied) updateConversation(conversationId, outcome.state)
    }

    private fun updateCurrentConversation(state: AgentChatHomeUiState) {
        val conversationId = selectedConversationId
        if (conversationId == null) {
            homeState = state
        } else {
            updateConversation(conversationId, state)
        }
    }

    private fun moveCurrentDraftToNewConversation() {
        val from = selectedConversationId
        val draft = homeState
        // 草稿搬走之后紧接着就会作为新会话的消息发出：旧会话那一份必须同步清掉。
        // 只清表不够 —— updateConversation 拿表校正，表空时会保留快照里的 input，
        // 用户点回这个只读归档会话仍会看到刚发出去的文字。
        if (from != null) {
            draftsByConversation.remove(from)
            conversationsById[from]?.takeIf { it.input.isNotEmpty() }?.let { stored ->
                conversationsById = conversationsById + (from to stored.copy(input = ""))
            }
        }
        selectedConversationId = null
        homeState = emptyChatState(defaultThinkingEnabled).copy(
            input = draft.input,
            thinkingEnabled = draft.reasoningEffort.enablesReasoning,
            reasoningEffort = draft.reasoningEffort,
            availableReasoningEfforts = currentReasoningCapabilities?.selectableEfforts.orEmpty(),
            pendingImages = draft.pendingImages,
            pendingFileReferences = draft.pendingFileReferences,
            modelId = draft.modelId,
        )
        conversationPaneState = conversationPaneState.copy(selectedConversationId = null)
    }

    // ---- 历史分页 ----
    // 会话窗口只装最近 MESSAGE_WINDOW_SIZE 条；更早的仍在库里，上翻时按需补。
    // 状态放这里而不是会话状态里：它描述的是「这一次翻页请求」，切走即作废。
    var historyLoadState by mutableStateOf(ConversationHistoryLoadState.Idle)
        private set

    /** 当前选中的会话是否还在从库里读消息；true 时界面显示载入态。 */
    val isLoadingSelectedConversation: Boolean
        get() = homeState.messagesDeferred &&
            selectedConversationId?.let { it in deferredMessageLoads } == true

    fun updateHistoryViewport(atLatest: Boolean) {
        // 回到底部说明用户已经看过最新一屏；一次失败不应该永久卡住「载入更早」。
        if (atLatest && historyLoadState == ConversationHistoryLoadState.Failed) {
            historyLoadState = ConversationHistoryLoadState.Idle
        }
    }

    fun loadEarlierMessages() {
        val conversationId = selectedConversationId ?: return
        val state = conversationsById[conversationId] ?: return
        if (historyLoadState == ConversationHistoryLoadState.Loading) return
        // messagesWindowStart == 0 表示内存里就是完整会话，没有更早的页可拉。
        if (state.messagesWindowStart <= 0) return
        if (state.messagesDeferred || state.isStreaming || state.messageEdit != null) return
        historyLoadState = ConversationHistoryLoadState.Loading
        scope.launch {
            val loaded = runCatching {
                AgentConversationStore.loadMessagesBefore(appContext, conversationId, state.messagesWindowStart)
            }.getOrElse { throwable ->
                AndroidAgentLogger.warnThrottled("agent_history_page_failed") {
                    "history page failed: type=${throwable.safeLogType()}"
                }
                historyLoadState = ConversationHistoryLoadState.Failed
                return@launch
            }
            val current = conversationsById[conversationId]
            if (current == null || current.messagesWindowStart != state.messagesWindowStart) {
                // 期间切走或重新载入过：这页对不上当前窗口，丢弃而不是硬拼。
                historyLoadState = ConversationHistoryLoadState.Idle
                return@launch
            }
            historyLoadState = ConversationHistoryLoadState.Idle
            updateConversation(
                conversationId,
                current.copy(
                    // 只前置消息本身，不碰 journal/history：那两份由保存侧按窗口处理。
                    messages = loaded.messages + current.messages,
                    messagesWindowStart = loaded.windowStart,
                ),
                updateTimestamp = false,
            )
        }
    }

    private fun updateConversation(
        conversationId: String,
        state: AgentChatHomeUiState,
        updateTimestamp: Boolean = true,
    ) {
        // 编辑态由 messageEdit 决定输入框内容，不能被草稿覆盖。
        val drafted = if (state.messageEdit == null) {
            draftsByConversation[conversationId]
                ?.takeIf { it.isNotEmpty() && it != state.input }
                ?.let { state.copy(input = it) }
                ?: state
        } else {
            state
        }
        conversationsById = conversationsById + (conversationId to drafted)
        refreshAnyRunningConversation()
        if (updateTimestamp) {
            conversationUpdatedAt = conversationUpdatedAt + (conversationId to System.currentTimeMillis())
        }
        if (conversationId == selectedConversationId) {
            homeState = drafted
        }
    }

    private fun setConversationStreaming(runId: String, isStreaming: Boolean) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationsById[conversationId] ?: return
        // 同一会话可能已经有新的 run（互斥取消旧 run 后立刻续跑、插话后新开）：
        // 旧 run 的终态不能把新 run 的运行态抹掉。否则界面显示「未运行」，
        // 用户下一条消息就会以「新消息」而不是「插话」发出去。
        if (!isStreaming && runConversationIds.any { (other, owner) ->
                other != runId && owner == conversationId
            }
        ) {
            return
        }
        updateConversation(conversationId, state.copy(
            isStreaming = isStreaming,
            // 暂停状态只属于「还在跑的 run」；结束/恢复事件一到就必须清掉，
            // 否则下一次发送时会带着上一条 run 的暂停标记。
            isPaused = state.isPaused && isStreaming,
            isCompacting = state.isCompacting && isStreaming,
        ))
    }

    private fun conversationIdForRun(runId: String): String? = runConversationIds[runId]

    /** 当前会话正在跑的 run；多会话并发时不能用全局 currentRunId 代替。 */
    private fun runIdForConversation(conversationId: String?): String? =
        conversationId?.let { target ->
            runConversationIds.entries.lastOrNull { it.value == target }?.key
        }

    private fun conversationStateForRun(runId: String): AgentChatHomeUiState {
        val conversationId = conversationIdForRun(runId) ?: return emptyChatState(defaultThinkingEnabled)
        return conversationsById[conversationId] ?: emptyChatState(defaultThinkingEnabled)
    }

    private fun refreshConversationSummaries() {
        // 先算查询状态：下面的摘要要带上命中条数，unsearched 也要按「历史是否已覆盖」决定。
        val query = conversationPaneState.searchQuery.trim()
        requestHistorySearch(query)
        // 历史命中只在「已按当前查询词查完」时使用，避免把上一轮的结果当成这一轮的结果。
        val historyHits = if (query.isNotEmpty() && historySearchAppliedQuery == query) historySearchHits else emptyMap()
        // 全库搜索真的搜过了才叫「没有漏搜」；查询还没走完、或者失败了，都要如实说明。
        val historyCovered = query.isEmpty() ||
            (historySearchAppliedQuery == query && historySearchSucceeded)
        // 正在查（还没收敛到这一轮）时界面显示「正在搜索历史」，不要假装「没有参与搜索」。
        val searchingHistory = query.isNotEmpty() && historySearchAppliedQuery != query
        val unsearched = if (historyCovered) 0 else conversationsById.count { (_, state) -> state.messagesDeferred }
        val summaries = conversationsById.entries
            // 置顶优先，其余按最近更新时间；置顶的顺序也按更新时间稳定。
            .sortedWith(
                compareByDescending<Map.Entry<String, AgentChatHomeUiState>> { it.value.isPinned }
                    .thenByDescending { conversationUpdatedAt[it.key] ?: 0L },
            )
            .map { (id, state) ->
                val lastMessage = state.messages.lastOrNull() ?: state.previewMessage
                ConversationSummaryUi(
                    id = id,
                    title = conversationTitles[id].orEmpty().ifBlank {
                        appContext.getString(R.string.conversation_unnamed)
                    },
                    preview = when (lastMessage) {
                        is UserMessageUi -> AgentFileReferencePromptCodec
                            .parse(lastMessage.content)
                            .let { parsed ->
                                AgentFileReferencePolicy.titleSource(
                                    request = parsed.request,
                                    references = parsed.references,
                                )
                            }
                        is AgentMessageUi -> lastMessage.content.ifBlank {
                            appContext.getString(R.string.conversation_preview_reasoning)
                        }
                        is SystemNoticeMessageUi -> noticeText(lastMessage.code)
                        is ThinkingMessageUi -> appContext.getString(R.string.conversation_preview_reasoning)
                        is ToolActivityMessageUi -> appContext.getString(
                            R.string.conversation_preview_tool_call,
                            lastMessage.toolName,
                        )
                        else -> appContext.getString(R.string.conversation_preview_empty)
                    }.take(MAX_PREVIEW_CHARS),
                    timeLabel = if (state.isStreaming) {
                        appContext.getString(R.string.time_now)
                    } else {
                        conversationUpdatedAt[id]?.let { timestamp ->
                            ConversationTimeLabels.label(
                                timestampMillis = timestamp,
                                locale = appContext.resources.configuration.locales[0],
                                use24HourClock = DateFormat.is24HourFormat(appContext),
                                yesterdayLabel = appContext.getString(R.string.time_yesterday),
                                recentLabel = appContext.getString(R.string.time_recent),
                            )
                        } ?: appContext.getString(R.string.time_recent)
                    },
                    updatedAtMillis = conversationUpdatedAt[id] ?: 0L,
                    mode = ConversationModeUi.Chat,
                    characterName = state.roleplay?.characterName,
                    characterAvatarPath = state.roleplay?.avatarPath,
                    isActiveRun = state.isStreaming,
                    isPaused = state.isPaused,
                    isPinned = state.isPinned,
                    contentMatchCount = historyHits[id] ?: 0,
                    messageCount = conversationMessageCounts[id] ?: 0,
                )
            }
        conversationPaneState = conversationPaneState.copy(
            selectedConversationId = selectedConversationId,
            unsearchedConversationCount = unsearched,
            isSearchingHistory = searchingHistory,
            conversations = if (query.isBlank()) {
                summaries
            } else {
                contentMatchCache.keys.retainAll(conversationsById.keys)
                summaries.filter { summary ->
                    summary.title.contains(query, ignoreCase = true) ||
                        summary.preview.contains(query, ignoreCase = true) ||
                        conversationContentMatches(summary.id, query) ||
                        historyHits.containsKey(summary.id)
                }
            },
        )
    }

    // ── 全库内容搜索 ──────────────────────────────────────────────
    // 未载入的会话 messages 是空表，内存扫描覆盖不到；改成让 SQLite 查全库，只回会话 id 与命中条数。
    // 一次全表匹配在本机（1.9 万条 / 1400 万字符）约百毫秒级，所以输入时防抖，不逐字扫。
    private var historySearchJob: Job? = null
    private var historySearchRequestedQuery: String = ""
    private var historySearchAppliedQuery: String = ""
    private var historySearchHits: Map<String, Int> = emptyMap()
    private var historySearchSucceeded: Boolean = false

    private fun requestHistorySearch(query: String) {
        if (query.isEmpty()) {
            historySearchJob?.cancel()
            historySearchJob = null
            historySearchRequestedQuery = ""
            historySearchAppliedQuery = ""
            historySearchHits = emptyMap()
            historySearchSucceeded = false
            return
        }
        // 已按同一个词查完、且没有新任务在等：不重复扫库。
        if (query == historySearchAppliedQuery && historySearchJob == null) return
        if (query == historySearchRequestedQuery) return
        historySearchRequestedQuery = query
        historySearchJob?.cancel()
        historySearchJob = scope.launch {
            delay(HISTORY_SEARCH_DEBOUNCE_MS)
            // null 表示这一轮失败了：仍要收敛状态，否则界面会一直停在「正在搜索历史」。
            val hits = runCatching {
                AgentConversationStore.searchConversationContent(appContext, query)
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                // 查不到就当作「历史未覆盖」，由 unsearchedConversationCount 继续如实提示。
                AndroidAgentLogger.warnThrottled("conversation_history_search_failed") {
                    "全库内容搜索失败（query length=${query.length}）: ${error.message}"
                }
                null
            }
            if (historySearchRequestedQuery != query) return@launch
            historySearchHits = hits.orEmpty()
            historySearchSucceeded = hits != null
            historySearchAppliedQuery = query
            historySearchJob = null
            refreshConversationSummaries()
        }
    }

    // 内容匹配按（查询词, 会话状态引用）缓存：刷新摘要时未变化的会话不重复全文扫描。
    private val contentMatchCache = mutableMapOf<String, ContentMatchCacheEntry>()

    private fun conversationContentMatches(conversationId: String, query: String): Boolean {
        val state = conversationsById[conversationId] ?: return false
        val cached = contentMatchCache[conversationId]
        if (cached != null && cached.query == query && cached.state === state) {
            return cached.matches
        }
        val matches = state.contentMatches(query) { code -> noticeText(code) }
        contentMatchCache[conversationId] = ContentMatchCacheEntry(query, state, matches)
        return matches
    }

    /**
     * 立刻把当前内存状态落盘。
     *
     * 用在退到后台时：进程随后被系统回收，不会再有下一次「用户操作触发保存」，
     * 最后一批消息就只能丢。没有选中会话时什么也不做。
     */
    fun flushPersistence() {
        if (selectedConversationId == null) return
        if (persistencePaused) return
        persistConversations()
    }

    private fun persistConversations(onSaved: (() -> Unit)? = null): Deferred<Boolean> {
        val selected = selectedConversationId
        val conversations = conversationsById
        val titles = conversationTitles
        val timestamps = conversationUpdatedAt
        return synchronized(persistenceLock) {
            // 未保存会阻止结果回执和 write-ahead 运行，由导入后的重载或失败后的补写收尾。
            if (persistencePaused) return CompletableDeferred(false)
            val previous = persistenceJob
            val persistence = conversationPersistence
            scope.async(Dispatchers.IO) {
                try {
                    previous?.join()
                    persistence.save(appContext,
                        AgentConversationStore.Snapshot(selected, conversations, titles, timestamps))
                    onSaved?.invoke()
                    if (persistenceFailed) {
                        withContext(Dispatchers.Main) { persistenceFailed = false }
                    }
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    AndroidAgentLogger.error(
                        "Agent conversation persistence failed: type=${throwable.safeLogType()}"
                    )
                    // 只记日志等于「静默丢数据」：用户看不到任何异常，只会发现消息没了。
                    // 从 false 翻到 true 时提示一次，避免高频重试刷屏。
                    withContext(Dispatchers.Main) {
                        if (!persistenceFailed) {
                            persistenceFailed = true
                            Toast.makeText(
                                appContext,
                                appContext.getString(R.string.conversation_save_failed_retry),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                    false
                }
            }.also { persistenceJob = it }
        }
    }

    private companion object {
        const val MAX_TITLE_CHARS = 24
        const val MAX_PREVIEW_CHARS = 48
        const val LEGACY_STOPPED_ERROR = "已停止"
        const val SYNTHETIC_STATUS_STOPPED = "eta_status:stopped"
        // 数据状态以较粗粒度发布，文字显现由独立的帧时钟连续推进。
        const val STREAM_UI_UPDATE_INTERVAL_MS = 80L
        /** 保屏续期间隔：远小于 12 小时上线，长任务不会悄悄失去保屏。 */
        const val KEEP_SCREEN_RENEW_INTERVAL_MS = 30L * 60L * 1000L
        /** 用户消息落盘等待上限：超过就按保存失败报错，避免界面永远停在“停止”。 */
        const val PREPARE_TIMEOUT_MS = 8_000L
        /** 同一个 run 的重复「停止」去抖窗口：只防连点，不做永久抑制。 */
        const val STOP_RETRY_INTERVAL_MS = 3_000L
        // 内容搜索要扫全库正文（本机 1.9 万条 / 1400 万字符约百毫秒级）：等用户停手再查，不逐字扫。
        const val HISTORY_SEARCH_DEBOUNCE_MS = 300L

        fun emptyChatState(thinkingEnabled: Boolean): AgentChatHomeUiState =
            AgentChatHomeUiState(
                messages = emptyList(),
                history = emptyList(),
                journal = emptyList(),
                input = "",
                isStreaming = false,
                thinkingEnabled = thinkingEnabled,
            )

        fun newConversationId(): String = "conv-${UUID.randomUUID()}"
    }
}

internal data class MessageRevisionImpact(
    val laterTurnCount: Int,
)

private data class ContentMatchCacheEntry(
    val query: String,
    val state: AgentChatHomeUiState,
    val matches: Boolean,
)

private const val EXTERNAL_ARCHIVE_CONVERSATION_PREFIX = "archive-"

private fun String.isReadOnlyExternalArchiveConversation(): Boolean =
    startsWith(EXTERNAL_ARCHIVE_CONVERSATION_PREFIX)

private fun archiveConversationId(source: String, conversationKey: String): String {
    val prefix = if (source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE) {
        ASSISTANT_CONVERSATION_PREFIX
    } else {
        EXTERNAL_ARCHIVE_CONVERSATION_PREFIX
    }
    return prefix + stableArchiveId("$source:$conversationKey")
}

private const val ASSISTANT_CONVERSATION_PREFIX = "assistant-"

private fun stableArchiveId(value: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun agentBooleanForUi(key: String): Boolean {
    return Prefs.isEnabled(key)
}

/** 释放消息内存的延迟：连续切换会话时，不把上一个会话刚载入的内容立刻丢掉。 */
internal const val MessageReleaseDelayMs = 1_000L

/**
 * 释放阈值（条数）。8,435 条的会话实测占 38.9MB Dalvik，约 4.6KB/条（docs §155）；
 * 800 条约 3~4MB，而重新载入一次的代价是 100~250ms，再小就不值得反复加载。
 */
internal const val MESSAGE_RELEASE_THRESHOLD = 800

/**
 * 切走之后是否该释放这个会话的消息内存。
 *
 * 流式中、编辑中、压缩中都不释放：这三种状态的 [AgentChatUiState.messages] 还可能被写回，
 * 释放会让正在进行的改动落到空表上。已经 deferred 的不用再释放。
 */
internal fun shouldReleaseConversationMessages(
    isActive: Boolean,
    isStreaming: Boolean,
    isEditing: Boolean,
    isCompacting: Boolean,
    messagesDeferred: Boolean,
    messageCount: Int,
): Boolean = !isActive && !isStreaming && !isEditing && !isCompacting &&
    !messagesDeferred && messageCount >= MESSAGE_RELEASE_THRESHOLD

/**
 * 退到后台时是否释放**当前**会话的消息内存（[shouldReleaseConversationMessages] 只放行非活跃会话）。
 *
 * 门槛沿用 [MESSAGE_RELEASE_THRESHOLD]：几百条以下释放收益有限，反而多一次重载。
 * 流式生成 / 编辑 / 压缩期间绝不释放：这些状态持有尚未落盘的内容。
 */
internal fun shouldReleaseBackgroundMessages(
    isStreaming: Boolean,
    isEditing: Boolean,
    isCompacting: Boolean,
    messagesDeferred: Boolean,
    messageCount: Int,
): Boolean = !isStreaming && !isEditing && !isCompacting && !messagesDeferred &&
    messageCount >= MESSAGE_RELEASE_THRESHOLD
