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
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.AgentFileReferenceGateway
import io.github.mangi.eta.agent.device.RootAccess
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
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.repository.EtaBackupRepository
import io.github.mangi.eta.data.repository.EtaBackupSummary
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
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
    private val deferredMessageLoads = mutableSetOf<String>()
    private val deferredHistoryLoads = mutableSetOf<String>()
    private val deferredJournalLoads = mutableSetOf<String>()
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
        // 冷启动就先把“开着但当前没有任务”的真实状态发布给设置页：
        // 以前只有“运行状态变化”才会发布，冷启动且没跑过任务时设置页会错报“已关闭”。
        applyKeepScreenLock()
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
        if (
            plan.completed.isEmpty() &&
            plan.interrupted.isEmpty() &&
            plan.reattach == null && orphanRewrites.isEmpty()
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
                val recovery = AgentPendingResultRecovery.apply(
                    state = conversationsById[conversationId] ?: state,
                    runId = runId,
                    result = result,
                    promptSupplement = payload.promptSupplement,
                    supplements = payload.supplements,
                )
                if (recovery.alreadyApplied) {
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
        val existing = conversationsById[conversationId] ?: return false
        if (AgentRuntimeHistoryReducer.wasApplied(existing, runId)) return false
        if (checkpoint.operation == AgentRuntimeWire.OP_REWRITE_REPLY) {
            val restored = RoleplayConversationReducer.restorePendingRewrite(existing, runId, checkpoint.rewriteTargetMessageId)
            if (interrupted) updateConversation(conversationId, RoleplayConversationReducer.applyRewrite(
                restored, runId, AgentRuntimeWire.RunResult(runId, false, "", appContext.getString(R.string.agent_notice_rewrite_interrupted),
                    operation = AgentRuntimeWire.OP_REWRITE_REPLY, rewriteTargetMessageId = checkpoint.rewriteTargetMessageId),
            )) else updateConversation(conversationId, restored)
            return interrupted || restored != existing
        }

        runConversationIds[runId] = conversationId
        updateConversation(conversationId, existing.copy(isStreaming = true, isCompacting = checkpoint.operation == AgentRuntimeWire.OP_COMPACT))
        restoreRunEventsOffMain(runId, checkpoint.events)
        flushPendingRunDelta(runId)
        updateRunTrace(runId) { messages ->
            val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
            val finalizedText = runMessageProjector.finalizeText(runId, finalizedThinking)
            if (interrupted) {
                val interruptedTools = runMessageProjector.interruptRunningTools(
                    reason = appContext.getString(R.string.system_notice_interrupted),
                    messages = runMessageProjector.finishContextCompaction(runId, finalizedText, appContext.getString(R.string.agent_notice_compaction_interrupted)),
                )
                val noticeId = "interrupted-$runId"
                if (interruptedTools.any { it.id == noticeId }) {
                    interruptedTools
                } else {
                    interruptedTools + SystemNoticeMessageUi(
                        id = noticeId,
                        code = SystemNoticeCode.Interrupted,
                    )
                }
            } else {
                runMessageProjector.finalizeRun(runId, finalizedText)
            }
        }
        if (interrupted && (checkpoint.contextSnapshot != null || checkpoint.transcript.isNotEmpty())) {
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
        val existing = conversationsById[conversationId] ?: return
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
        currentRunJob = scope.launch(Dispatchers.IO) {
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
                            setConversationStreaming(runId, false)
                        }
                    }
                    recoverRuntimeRuns()
                }
                AgentRuntimeClient.AttachOutcome.Unavailable -> withContext(Dispatchers.Main) {
                    if (currentRunId == runId) {
                        currentRunId = null
                        currentRunJob = null
                        setConversationStreaming(runId, false)
                        refreshConversationSummaries()
                    }
                }
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
        persistConversations()
        scheduleMessageRelease(conversationId)
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

    fun deleteConversation(conversationId: String) {
        val wasSelected = selectedConversationId == conversationId
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
     * 运行中「插话发送」：先把这条消息入队并请求中止当前 run，run 收尾后优先发出。
     *
     * 与 [sendCurrentMessage] 的运行中行为（steering，见 [steerCurrentRun]）刻意区分：
     * steering 不中断当前 run，只把补充指令送进去；插话则明确要「别按现在的方案继续」。
     * 空闲时没有可插的话，直接退回普通发送。
     */
    fun interruptAndSendCurrentMessage(submittedText: String? = null) {
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
        if (selectedConversationId?.isReadOnlyExternalArchiveConversation() == true) return
        val queued = QueuedMessageUi(
            id = "queue-${UUID.randomUUID()}",
            content = prompt,
            pendingImages = pendingImages,
            pendingFileReferences = pendingFileReferences,
            immediateInterrupt = true,
        )
        updateCurrentConversation(AgentMessageQueueReducer.enqueue(homeState, queued))
        requestInterruptCurrentRun()
    }

    /** 把队列里的某条提升为「立即插话」：运行中打断当前 run，空闲时直接发出。 */
    fun steerQueuedMessage(id: String) {
        val (nextState, promoted) = AgentMessageQueueReducer.promoteToInterrupt(homeState, id)
        if (promoted == null) return
        updateCurrentConversation(nextState)
        if (nextState.isStreaming && !nextState.isCompacting) {
            requestInterruptCurrentRun()
        } else {
            dispatchQueuedMessage(promoted)
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
     * 只有输入框为空时才自动占用草稿位：用户正在打字时把消息插进去会吞掉他没发的内容。
     * 附件无法直接走发送路径，恢复成草稿让用户确认后再发。
     */
    private fun dispatchQueuedMessage(item: QueuedMessageUi) {
        if (homeState.isStreaming || homeState.isCompacting) return
        if (item.pendingImages.isEmpty() && item.pendingFileReferences.isEmpty()) {
            if (homeState.input.isNotBlank()) return
            updateCurrentConversation(homeState.copy(input = item.content))
            sendCurrentMessage(item.content)
            return
        }
        if (homeState.input.isBlank() && homeState.pendingImages.isEmpty() &&
            homeState.pendingFileReferences.isEmpty()
        ) {
            updateCurrentConversation(
                homeState.copy(
                    input = item.content,
                    pendingImages = item.pendingImages,
                    pendingFileReferences = item.pendingFileReferences,
                ),
            )
        }
    }

    /** 请求中止当前会话的 run，但**不清空队列**：插话及队列续发都靠它。 */
    private fun requestInterruptCurrentRun() {
        val runId = runIdForConversation(selectedConversationId) ?: return
        if (!stopRequestedRunIds.add(runId)) return
        scope.launch(Dispatchers.IO) {
            AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(runId)
        }
    }

    fun sendCurrentMessage(submittedText: String? = null) {
        if (rejectWhileMessagesLoading()) return
        if (homeState.isCompacting) return
        val prompt = (submittedText ?: homeState.input).trim()
        val pendingImages = homeState.pendingImages
        val pendingFileReferences = homeState.pendingFileReferences
        if (homeState.isStreaming) {
            // 运行中不再只有“停止”：把这条输入当作 steering 送进当前 run，
            // 当前步骤结束后模型就能看到，不必中断重来。
            steerCurrentRun(prompt)
            return
        }
        if (prompt.isBlank() && pendingImages.isEmpty() && pendingFileReferences.isEmpty()) {
            return
        }
        homeState.messageEdit?.takeIf { it.preserveFollowingMessages }?.let { edit ->
            val updated = RoleplayConversationReducer.edit(homeState, edit.targetMessageId, prompt) ?: return
            updateCurrentConversation(updated.copy(
                input = edit.previousInput,
                pendingImages = edit.previousImages,
                pendingFileReferences = edit.previousFileReferences,
                messageEdit = null,
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
            ),
            reasoningEffort = homeState.reasoningEffort,
        )
    }

    /**
     * 运行中把输入作为补充指令送入当前 run（steering）。
     * 返回 false 表示没有活跃 run 或发送失败，调用方可以退回原行为。
     */
    fun steerCurrentRun(text: String): Boolean {
        val prompt = text.trim()
        if (prompt.isBlank()) return false
        val runId = runIdForConversation(selectedConversationId) ?: return false
        /*
         * 这里必须切到 IO：AgentRuntimeClient 是同步客户端，AgentRuntimeConnection.acquire()
         * 开头就 check(不在主线程)。而这个入口是用户点「发送」直接进来的，
         * 之前在主线程调 sendSupplement 会立刻抛 IllegalStateException —— 表现为
         * 「会话运行中再发消息直接闪退」。
         */
        val conversationId = selectedConversationId
        // 暂停中发的补充指令不会「当前步骤结束后继续」——run 正挂在检查点上，
        // 要等用户点「继续」才会被消费。文案必须说真话。
        val paused = conversationId?.let { conversationsById[it]?.isPaused == true } == true
        homeState = homeState.copy(input = "")
        scope.launch(Dispatchers.IO) {
            val sent = AgentRuntimeClient(appContext, AndroidAgentLogger).sendSupplement(runId, prompt)
            withContext(Dispatchers.Main) {
                if (!sent) {
                    // 发不出去就把刚打的字还给用户，不能既没送出去又清空输入框。
                    if (selectedConversationId == conversationId && homeState.input.isBlank()) {
                        homeState = homeState.copy(input = prompt)
                    }
                    Toast.makeText(
                        appContext,
                        appContext.getString(R.string.chat_steering_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@withContext
                }
                val messageRes = if (paused) R.string.chat_steering_queued_paused else R.string.chat_steering_sent
                Toast.makeText(appContext, appContext.getString(messageRes), Toast.LENGTH_SHORT).show()
            }
        }
        return true
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
    ) {
        runConversationIds[runId] = conversationId
        currentRunId = runId
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
            if (withContext(Dispatchers.Main) { runId in stopRequestedRunIds }) {
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
        currentRunJob = preparationJob
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
        if (!deferredMessageLoads.add(conversationId)) return
        scope.launch(Dispatchers.IO) {
            try {
                val ok = ensureMessagesLoaded(listOf(conversationId), requireSelected = true)
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
                    withContext(Dispatchers.Main) { deferredMessageLoads.remove(conversationId) }
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

    private val stopRequestedRunIds = mutableSetOf<String>()

    fun stopCurrentRun() {
        val runId = runIdForConversation(selectedConversationId) ?: return
        if (!stopRequestedRunIds.add(runId)) return
        scope.launch(Dispatchers.IO) {
            AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(runId)
        }
    }

    /**
     * 从会话列表停止某个会话正在跑的 run。
     *
     * 后台 run 在 fix108 之后可以继续跑，但原来只能先切进那个会话再点停止；
     * 列表长按给出入口后，用户不必打断自己正在看的会话。
     */
    fun stopConversationRun(conversationId: String) {
        val runId = runIdForConversation(conversationId) ?: return
        if (!stopRequestedRunIds.add(runId)) return
        scope.launch(Dispatchers.IO) {
            AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(runId)
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
        val runId = runIdForConversation(selectedConversationId) ?: return
        val conversationId = conversationIdForRun(runId) ?: return
        val target = !(conversationsById[conversationId]?.isPaused == true)
        applyRunPaused(runId, target)
        scope.launch(Dispatchers.IO) {
            val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
            val sent = if (target) client.pauseRun(runId) else client.resumeRun(runId)
            if (!sent) {
                withContext(Dispatchers.Main) { applyRunPaused(runId, !target) }
                return@launch
            }
            val query = client.queryActiveRun(runId)
            val confirmed = (query as? AgentRuntimeClient.ActiveRunQuery.Known)
                ?.takeIf { it.runId == runId }
                ?.paused
            if (confirmed != null) {
                withContext(Dispatchers.Main) { applyRunPaused(runId, confirmed) }
            }
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
        val projector = AgentRunMessageProjector()
        var messages = projector.resetForReplay(
            runId = runId,
            messages = initialMessages,
            replaySupplementIndexes = replayEvents.filterIsInstance<AgentEvent.UserSupplementReceived>()
                .mapTo(mutableSetOf()) { it.index },
        )
        for (event in replayEvents) {
            messages = if (event is AgentEvent.UserSupplementReceived) {
                // 与 insertSupplementMessage 同一条合并逻辑，只是不在这里落盘、不刷新摘要。
                AgentPendingResultRecovery.mergeSupplements(
                    runId = runId,
                    supplements = listOf(
                        AgentUiHandoffPayload.Supplement(
                            index = event.index,
                            text = event.text,
                            createdAt = System.currentTimeMillis(),
                        )
                    ),
                    messages = messages,
                )
            } else {
                projector.applyEvent(runId, event, messages)
            }
        }
        return messages
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
            if (event is AgentEvent.RunStarted && runId in stopRequestedRunIds) scope.launch(Dispatchers.IO) {
                AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(runId)
            }
            return
        }
        when (event) {
            is AgentEvent.UserSupplementReceived ->
                insertSupplementMessage(runId, event.index, event.text, persist = persistSupplement)
            is AgentEvent.RunStarted -> if (runId in stopRequestedRunIds) scope.launch(Dispatchers.IO) {
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

    private fun applyRunResult(
        runId: String,
        result: AgentRuntimeWire.RunResult,
        acknowledgeRuntimeResult: Boolean = false,
    ) {
        flushPendingRunDelta(runId)
        val rewriting = result.operation == AgentRuntimeWire.OP_REWRITE_REPLY || isReplyRewrite(runId)
        stopRequestedRunIds.remove(runId)
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
                        runId, messages, result.content, notice,
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
        persistConversations(
            onSaved = if (acknowledgeRuntimeResult && result.contextSnapshotRef.isBlank()) {
                {
                    AgentRuntimeClient(appContext, AndroidAgentLogger).ackResult(runId)
                }
            } else {
                null
            }
        )
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
        val draft = homeState
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

    private fun updateConversation(
        conversationId: String,
        state: AgentChatHomeUiState,
        updateTimestamp: Boolean = true,
    ) {
        conversationsById = conversationsById + (conversationId to state)
        refreshAnyRunningConversation()
        if (updateTimestamp) {
            conversationUpdatedAt = conversationUpdatedAt + (conversationId to System.currentTimeMillis())
        }
        if (conversationId == selectedConversationId) {
            homeState = state
        }
    }

    private fun setConversationStreaming(runId: String, isStreaming: Boolean) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationsById[conversationId] ?: return
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
