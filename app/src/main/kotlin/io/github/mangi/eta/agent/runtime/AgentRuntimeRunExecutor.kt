package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityKeeper
import io.github.mangi.eta.agent.browser.BrowserSessionRegistry
import io.github.mangi.eta.agent.model.AgentConversationToolCatalog
import io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog
import io.github.mangi.eta.agent.subagent.SubagentExecutor
import io.github.mangi.eta.agent.tool.ConversationHistorySource
import io.github.mangi.eta.agent.tool.ConversationHistoryTool
import io.github.mangi.eta.agent.tool.JsonArrayElementScanner
import io.github.mangi.eta.data.db.ConversationDao
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.agent.model.AgentGoalPhase
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelExecutionException
import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.model.AgentMode
import io.github.mangi.eta.agent.model.AgentToolSpill
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.agent.memory.AgentLearningRecorder
import io.github.mangi.eta.agent.memory.AgentSelfBrief
import io.github.mangi.eta.agent.memory.ConversationMemoryPins
import io.github.mangi.eta.agent.trace.AgentSelfStatsLoader
import io.github.mangi.eta.agent.roleplay.CharacterMemoryTools
import io.github.mangi.eta.agent.terminal.BackgroundEvents
import io.github.mangi.eta.agent.terminal.BackgroundTasksSection
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.roleplay.RoleplayRunContext
import io.github.mangi.eta.agent.mcp.McpRunSnapshot
import io.github.mangi.eta.agent.usage.AgentUsageLedger
import io.github.mangi.eta.agent.mcp.McpToolExecutor
import io.github.mangi.eta.agent.mcp.RoutingToolExecutor
import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.mangi.eta.agent.skill.SkillCompatibilityChecker
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.skill.PublicGitHubSkillSource
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.PendingSkillConflictCapabilityParser
import io.github.mangi.eta.agent.tool.ToolExecutionDecision
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单次 Runtime run 的阻塞执行器。
 *
 * 它只拥有模型、工具和终态提交，不持有 Service、Messenger、Compose 或 WindowManager 状态。
 * 所有外部副作用都通过窄回调交回宿主。
 */
internal class AgentRuntimeRunExecutor(
    context: Context,
    private val currentPermissions: () -> AgentRuntimePolicy.Permissions,
    private val snapshotRequest: (AgentRuntimeWire.RunRequest) -> AgentRuntimeWire.RunRequest,
    private val onAcceptedEvent: (AgentEvent, EntrySurfaceGuard?) -> Unit,
    private val persistArtifacts: (
        AgentRuntimeWire.RunRequest,
        AgentRuntimeWire.RunResult,
        List<AgentEvent>,
    ) -> Unit,
    /** 手机界面只有一个操作所有者：多会话并行时由它排队，用户暂停/恢复也由它推进代号。 */
    private val guiControl: GuiControlCoordinator,
) {
    data class Outcome(
        val result: AgentRuntimeWire.RunResult,
        val entrySurfaceGuard: EntrySurfaceGuard?,
        val completedRequest: AgentRuntimeWire.RunRequest? = null,
        val response: AgentModelClient.ModelResponse.Text? = null,
        val shouldUpdateHost: Boolean,
    )

    private val appContext = context.applicationContext

    fun execute(
        session: AgentRuntimeSession,
        request: AgentRuntimeWire.RunRequest,
    ): Outcome {
        val runController = session.controller
        val archivedEvents = mutableListOf<AgentEvent>()
        var entrySurfaceGuard: EntrySurfaceGuard? = null
        var toolExecutor: AutoCloseable? = null
        var toolsBinding: AgentRunController.ResourceBinding? = null
        var browserBinding: AutoCloseable? = null
        var guiRun: GuiControlCoordinator.RunControl? = null
        var response: AgentModelClient.ModelResponse.Text? = null
        var cancelled = false
        var checkpointRecorder: AgentRunCheckpointRecorder? = null
        val timing = AgentRunTiming(AndroidAgentLogger)

        // 失败清理需要在 try 外拿到会话 id。
        var memoryConversationId: String? = null
        val result = try {
            val contextWindow = request.config.knownContextWindow
            checkpointRecorder = AgentRunCheckpointRecorder.create(appContext, request)
            entrySurfaceGuard = EntrySurfaceGuard.from(
                handoff = request.handoff,
                logger = AndroidAgentLogger,
                etaVoiceSurfaceDismissal = {
                    EtaAssistantOverlayService.dismissForForegroundOperation(appContext)
                },
            )
            val skillIndexService = SkillRuntime.createIndexService(appContext)
            val skillLoader = SkillRuntime.createLoader(appContext)
            val skillResourceReader = SkillRuntime.createResourceReader(appContext)
            val skillPackageInstaller = SkillRuntime.createPackageInstaller(appContext)
            val githubSkillSource = PublicGitHubSkillSource(
                cacheRoot = appContext.cacheDir,
                baseClient = AgentHttpClient.client,
            )
            val skillContext = SkillContext(
                installedSkills = skillIndexService.listInstalledSkills()
                    .filter { SkillCompatibilityChecker.evaluate(it).available },
            )
            val memoryEnabled = runBlocking { AgentMemoryRepository.isEnabled() }
            val uiPayload = request.handoff
                ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
                ?.let { AgentUiHandoffPayload.from(it.payload) }
            val conversationId = uiPayload?.conversationId
                ?.takeIf { it.isNotBlank() }
            memoryConversationId = conversationId
            // 任务清单按会话作用域持久化：跨轮、跨进程重启都还在；没有会话（语音等一次性入口）时退回 runId。
            val todoScopeId = conversationId ?: request.runId
            val todoStore = AgentTodoStore.forApp(appContext)
            val todoPlan = runCatching { todoStore.load(todoScopeId) }
                .getOrNull()
                ?.takeIf { !it.isEmpty }
            // 长期目标与清单同一作用域、分开存储：清单说这一步做什么，目标说做到什么算完成。
            // 已完成的目标留在文件里可查，但不再注入系统提示。
            val goalStore = AgentGoalStore.forApp(appContext)
            val goalPlan = runCatching { goalStore.load(todoScopeId) }
                .getOrNull()
                ?.takeIf { it.phase != AgentGoalPhase.COMPLETED }
            // 子代理台账与清单同一作用域：模型不必每轮先调 tasks，也知道派过谁、哪个还能 resume。
            // 读失败当「没有台账」处理 —— 系统提示少一段不该让整轮对话起不来。
            val taskLedgerPlan = runCatching {
                AgentTaskLedgerStore.forApp(appContext).load(todoScopeId).promptSection()
            }.getOrElse { throwable ->
                AndroidAgentLogger.warnThrottled("agent_task_ledger_prompt_failed") {
                    "Agent task ledger prompt unavailable: type=${throwable.safeLogType()}"
                }
                ""
            }
            // 后台任务（terminal_job / terminal_daemon）的只读快照：子代理台账回答「派了谁」，
            // 它回答「这台机器上还有什么在跑」「哪些已经结束」。用 readRecords 而不是 list ——
            // list 会探活、认领、补结束时间并清理记录，每轮构建提示都做这些既慢又有副作用。
            val detachedRecords = runCatching {
                DetachedTaskSupervisor.readRecords(DetachedTaskSupervisor.defaultRecordsFile(appContext))
            }.getOrElse { throwable ->
                AndroidAgentLogger.warnThrottled("agent_background_tasks_prompt_failed") {
                    "Agent background task records unavailable: type=${throwable.safeLogType()}"
                }
                emptyList()
            }
            val backgroundTasksNow = System.currentTimeMillis()
            val backgroundTasksPlan = runCatching {
                BackgroundTasksSection.render(detachedRecords, backgroundTasksNow)
            }.getOrElse { throwable ->
                AndroidAgentLogger.warnThrottled("agent_background_tasks_prompt_failed") {
                    "Agent background tasks prompt unavailable: type=${throwable.safeLogType()}"
                }
                ""
            }
            // 结束事件：水位线记在会话作用域里，避免同一个收尾每轮重报。
            // 渲染成功才推进水位线；推进失败只会让下一次重报一遍，不会丢事件。
            val backgroundEventsPlan = runCatching {
                val store = BackgroundEventStore.forApp(appContext)
                val events = BackgroundEvents.collect(detachedRecords, store.loadDeliveredUntil(todoScopeId))
                val section = BackgroundEvents.render(events, backgroundTasksNow)
                if (events.isNotEmpty()) store.markDeliveredUntil(todoScopeId, events.last().at)
                section
            }.getOrElse { throwable ->
                AndroidAgentLogger.warnThrottled("agent_background_events_prompt_failed") {
                    "Agent background events prompt unavailable: type=${throwable.safeLogType()}"
                }
                ""
            }
            val roleplayContext = conversationId?.let { id ->
                runBlocking { RoleplayRunContext.resolve(appContext, id, contextWindow, memoryEnabled) }
            }
            if (request.operation == AgentRuntimeWire.OP_REWRITE_REPLY) {
                require(roleplayContext != null) { "只有角色会话可以改写角色回复" }
                val target = request.rewriteTargetMessageId?.takeIf { it.isNotBlank() && it.length <= 256 }
                    ?: throw IllegalArgumentException("缺少有效的角色回复目标")
                require(runBlocking {
                    EtaDatabase.get(appContext).conversationDao().hasAssistantMessage(conversationId, target)
                }) { "角色回复目标不存在或不属于当前会话" }
            }
            val characterMemoryTools = roleplayContext?.let { roleplay ->
                CharacterMemoryTools(appContext, roleplay.characterId) {
                    runBlocking { AgentMemoryRepository.isEnabled() }
                }
            }
            val memoryContext = if (memoryEnabled) {
                runCatching {
                    ConversationMemoryPins.contextFor(
                        conversationId = conversationId,
                        snapshot = AgentMemoryRepository.snapshot(),
                        contextWindow = request.config.contextWindow,
                        // 自评只在本会话首次注入时算一次；之后由 pin 冻结，
                        // 否则「我自己又跑了一轮」会让系统提示每轮都变、前缀缓存失效。
                        selfBriefSource = {
                            runCatching {
                                AgentSelfBrief.render(AgentSelfStatsLoader.load())
                            }.getOrElse { throwable ->
                                AndroidAgentLogger.warnThrottled("agent_self_brief_failed") {
                                    "Agent self brief unavailable: type=${throwable.safeLogType()}"
                                }
                                ""
                            }
                        },
                    )
                }.getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_context_failed") {
                        "Agent memory context unavailable: type=${throwable.safeLogType()}"
                    }
                    AgentMemoryContextBuilder.empty(request.config.contextWindow)
                }
            } else {
                AgentMemoryContext.DISABLED
            }
            if (request.config.terminalTools) {
                // 启动探测由 App 拥有；模型首轮等待其结果，避免把检测中的授权当成普通设备。
                runBlocking {
                    while (io.github.mangi.eta.agent.device.RootAccess.state.value.isChecking) {
                        runController.throwIfCancelled()
                        kotlinx.coroutines.delay(100)
                    }
                }
            }
            val pendingSkillConflict = PendingSkillConflictCapabilityParser.parse(request.history)
            val mcpSnapshot = runBlocking {
                runCatching { McpRunSnapshot.load() }.getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_mcp_snapshot_failed") {
                        "MCP tool snapshot unavailable: type=${throwable.safeLogType()}"
                    }
                    McpRunSnapshot.EMPTY
                }
            }
            val mcpTools = JSONArray().also(mcpSnapshot::appendModelTools)
            // 长时工具的执行中进度转发器。
            //
            // 为什么必须在这一层：进度产生于**工具内部**（ACP 读线程），
            // 而 round 只存在于 AgentModelClient.complete 的闭包里 —— 工具层拿不到。
            //
            // 为什么不能再用 ThreadLocal：`enterRound` 由编排线程调用，
            // 而 `onProgress` 是从 ACP 的 `eta-acp-*` HandlerThread 上调的。
            // 两个线程不同，读线程的 ThreadLocal 恒为初始值 0，
            // 于是 `round <= 0` 的丢弃分支命中 **100% 的进度事件** ——
            // UI、overlay、checkpoint 全都接好了，却一条都收不到。
            //
            // 改成按 toolCallId 维度的映射：executeTool 开始时登记
            // `toolCallId -> round`，结束时移除。无论进度从哪个线程来，
            // 都能按事件自带的 toolCallId 查到正确轮次；
            // 查不到（工具已结束、或登记前就来报）就丢弃，仍然不会挂错卡片。
            val progressRounds = ToolProgressRoundRegistry()
            val toolProgressForwarder: (AgentEvent.ToolProgress) -> Unit = forwarder@{ progress ->
                val round = progressRounds.roundOf(progress.toolCallId)
                // 轮次未知（工具在登记之前就汇报，或运行已进入收尾）→ 丢弃。
                // 宁可少几条进度，也不能把错误轮次写进事件：那会让进度挂到别人的卡片上。
                if (round <= 0) return@forwarder
                acceptEvent(
                    session,
                    progress.copy(round = round),
                    archivedEvents,
                    entrySurfaceGuard,
                    checkpointRecorder,
                    usageConfig = request.config,
                    usageOperation = request.operation,
                )
            }
            val progressScope: AgentModelClient.ProgressScope =
                object : AgentModelClient.ProgressScope {
                    override fun enterRound(round: Int, toolCallId: String) {
                        progressRounds.enter(round, toolCallId)
                    }

                    override fun exitRound(toolCallId: String) {
                        progressRounds.exit(toolCallId)
                    }
                }
            // 页面按会话复用：同会话的下一轮运行、以及用户点开的浏览器面板，
            // 必须看到同一组 WebView，否则 Agent 打开的页面在界面上凭空消失。
            val browserKey = AgentConversationIdentity.fromHandoff(request.handoff, request.runId)
            browserBinding = BrowserSessionRegistry.acquireRun(browserKey, request.runId)
            // 抢占/排队都要在工具执行前完成：否则第一个 GUI 动作会绕开互斥直接点到屏幕上。
            guiRun = guiControl.openRun(request.runId, runController) { status ->
                acceptEvent(
                    session,
                    AgentEvent.GuiControlChanged(status),
                    archivedEvents,
                    entrySurfaceGuard,
                    checkpointRecorder,
                    usageConfig = request.config,
                    usageOperation = request.operation,
                )
            }
            val executor = AgentLocalTools(
                context = appContext,
                logger = AndroidAgentLogger,
                browserRunId = request.runId,
                browserSession = BrowserSessionRegistry.get(browserKey),
                guiControl = guiRun,
                browserToolsEnabled = {
                    request.config.browserTools && currentPermissions().browserTools
                },
                terminalToolsEnabled = {
                    request.config.terminalTools && currentPermissions().terminalTools
                },
                deviceDirectToolsEnabled = {
                    request.config.deviceDirectTools && currentPermissions().deviceDirectTools
                },
                deviceSensitiveReadToolsEnabled = {
                    request.config.deviceSensitiveReadTools &&
                        currentPermissions().deviceSensitiveReadTools
                },
                deviceSensitiveActionToolsEnabled = {
                    request.config.deviceSensitiveActionTools &&
                        currentPermissions().deviceSensitiveActionTools
                },
                memoryToolsEnabled = {
                    runBlocking { AgentMemoryRepository.isEnabled() }
                },
                memoryWritable = roleplayContext == null,
                screenshotExcludedPackages = {
                    entrySurfaceGuard?.consumeScreenshotExcludedPackages().orEmpty()
                },
                beforeToolExecution = { toolName ->
                    val requiresAccessibility =
                        AgentToolRequirements.requiresAccessibility(toolName)
                    if (
                        !requiresAccessibility &&
                        !AgentOverlayVisibilityPolicy.requiresEntrySurfaceDismissal(toolName)
                    ) {
                        ToolExecutionDecision.Allow
                    } else {
                        val accessibility = if (requiresAccessibility) {
                            AgentAccessibilityKeeper.ensureEnabledForGuiOperation(appContext)
                        } else {
                            null
                        }
                        when {
                            accessibility != null && !accessibility.available ->
                                ToolExecutionDecision.Reject(
                                    code = accessibility.code,
                                    message = accessibility.message,
                                )
                            entrySurfaceGuard?.dismissOnce() == false ->
                                ToolExecutionDecision.Reject(
                                    code = "ENTRY_SURFACE_NOT_READY",
                                    message = "入口窗口关闭未完成；本次工具未执行，请勿在当前任务中重复调用",
                                )
                            else -> ToolExecutionDecision.Allow
                        }
                    }
                },
                skillIndexService = skillIndexService,
                skillLoader = skillLoader,
                skillResourceReader = skillResourceReader,
                githubSkillSource = githubSkillSource,
                skillPackageInstaller = skillPackageInstaller,
                runAvailableSkillIds = skillContext.installedSkills.mapTo(mutableSetOf()) { it.id },
                pendingSkillConflict = pendingSkillConflict,
                todoScopeId = todoScopeId,
                goalScopeId = todoScopeId,
                // 每个会话一个工作区；语音/一次性入口没有 conversationId 时沿用全局工作区。
                workspaceScopeId = conversationId ?: "",
                // 账本按 runId 分文件：一次运行一份，界面在运行详情里读。
                changeScopeId = request.runId,
                // 文件检查点按会话共享：用户下一轮说「撤销上一轮修改」仍能恢复。
                backupScopeId = conversationId ?: request.runId,
                // 长时工具的执行中进度：ACP 代理的 session/update 增量经此处进入事件流，
                // 由 AgentLoop 补上 round 后转发。留空则 acp_delegate 退回原有的"静默执行"。
                onToolProgress = toolProgressForwarder,

                virtualDisplayEnabled = { currentPermissions().virtualDisplay },
            )
            val routingExecutor = RoutingToolExecutor(
                local = executor,
                mcp = McpToolExecutor(mcpSnapshot),
            )
            toolExecutor = routingExecutor
            toolsBinding = runController.register(routingExecutor::close)
            timing.preparationFinished(skillContext.installedSkills.size)
            val historyTool = conversationId?.let { id ->
                val conversationDao = EtaDatabase.get(appContext).conversationDao()
                ConversationHistoryTool(object : ConversationHistorySource {
                    override suspend fun scan(
                        fromIndex: Int,
                        budget: Int,
                        query: String?,
                    ): JsonArrayElementScanner.Page {
                        val checkpoint = conversationDao.contextCheckpointRow(id)
                        val journal = checkpoint?.journalJson.orEmpty()
                        val journalPage = scannerFor(conversationDao, id, "journal", journal)
                            .scan(fromIndex, budget, query)
                        if (journalPage.totalMessages > 0) return journalPage
                        // journal 为空（新会话）：退回模型上下文 history，同一种 JSON 数组，走同一条路。
                        val history = checkpoint?.historyJson.orEmpty()
                        if (history.isEmpty()) return journalPage
                        return scannerFor(conversationDao, id, "history", history)
                            .scan(fromIndex, budget, query)
                    }

                    override fun liveMessages(): List<AgentModelClient.ConversationMessage> = session.transcript
                })
            }
            // Subagent 工具：开关打开时注册 schema，实际执行前再用当前权限裁决一次；
            // 权限可能在运行期间被关掉，所以判断放在调用时而不是注册时。
            val subagentAllowed = {
                request.config.subagentTools && currentPermissions().subagentTools
            }
            val subagentExecutor = SubagentExecutor(
                // 子代理会话按会话隔离：同一会话内 resume 可用，跨会话互不可见。
                conversationKey = conversationId ?: request.runId,
                parentController = runController,
                configProvider = {
                    val rawSubagentConfig = runBlocking {
                        RuntimeConfigRepository.subagentRuntimeConfig(request.config)
                    }
                    AgentRuntimePolicy.constrain(rawSubagentConfig, currentPermissions())
                },
                capabilitiesProvider = { AgentToolCapabilities.capture(appContext) },
                baseToolExecutor = routingExecutor,
                skillContext = skillContext,
                memoryContext = memoryContext,
                mcpTools = mcpTools,
                executionContext = executor.terminalExecutionContext,
                onProgress = { progressEvent ->
                    acceptEvent(
                        session,
                        progressEvent,
                        archivedEvents,
                        entrySurfaceGuard,
                        checkpointRecorder,
                    )
                },
                // 任务台账与清单/目标同一作用域：同一会话跨轮可见，跨会话互不可见。
                // 由子代理执行器写入，`tasks` 工具只读它，两边共用同一批文件。
                taskLedger = AgentTaskLedgerStore.forApp(appContext),
                taskScopeId = todoScopeId,
            )
            val runTools = JSONArray(mcpTools.toString()).also { tools ->
                if (subagentAllowed()) AgentSubagentToolCatalog.appendSchemas(tools)
                if (historyTool != null) tools.put(AgentConversationToolCatalog.schema())
                if (characterMemoryTools != null && memoryEnabled) CharacterMemoryTools.appendSchemas(tools)
            }
            val runToolExecutor = AgentModelClient.ToolExecutor { call ->
                if (call.name in AgentSubagentToolCatalog.names) {
                    if (!subagentAllowed()) {
                        AgentModelClient.ToolResult(
                            JSONObject()
                                .put("ok", false)
                                .put("code", "SUBAGENT_DISABLED")
                                .put("message", "Subagent 权限已关闭")
                                .toString(),
                        )
                    } else if (call.name == AgentSubagentToolCatalog.PROFILE_TOOL_NAME) {
                        subagentExecutor.executeProfileManagement(call)
                    } else {
                        subagentExecutor.execute(call.round, call)
                    }
                } else if (call.name == AgentConversationToolCatalog.READ_HISTORY && historyTool != null) {
                    historyTool.execute(call)
                } else if (call.name in CharacterMemoryTools.NAMES && characterMemoryTools != null) {
                    characterMemoryTools.execute(call)
                } else routingExecutor.execute(call).also { result ->
                    if (call.name == "memory_write") {
                        runCatching { JSONObject(result.content) }.getOrNull()
                            ?.takeIf { it.optBoolean("ok") }
                            ?.let { ConversationMemoryPins.recordOwnWrite(conversationId, it.optString("revision")) }
                    }
                    /*
                     * 失败经验旁路记账：工具名 + 错误码 + 错误首行写进 MEMORY.md 的「经验教训」章节，
                     * 下一次系统提示会带上高频条目。这里是同步写（几十毫秒级），但每次 run 每签名只落一次、
                     * 跨 run 还有 60 秒冷却，且失败静默，不影响工具结果。
                     */
                    // 角色会话里记忆是只读的（memoryWritable=false）：工具失败经验也不该写进用户的
                    // MEMORY.md —— 那是现实记忆，剧情 run 没有权限往里追加内容。
                    if (memoryEnabled && roleplayContext == null) {
                        AgentLearningRecorder.record(call.name, result.content)
                    }
                }
            }
            val completedResponse = AgentModelClient.complete(
                config = request.config,
                sessionId = request.effectiveModelSessionId,
                operationId = request.runId,
                initialUserMessageId = uiPayload?.promptMessageId(request.runId) ?: "user-${request.runId}",
                initialSupplementIndex = uiPayload?.lastSupplementIndex ?: 0,
                roleplayContext = roleplayContext,
                rewriteReply = request.operation == AgentRuntimeWire.OP_REWRITE_REPLY,
                compactOnly = request.operation == AgentRuntimeWire.OP_COMPACT,
                onContextSnapshot = { snapshot ->
                    // 压缩会把历史里的记忆写入摘要掉，下一轮需要重新注入最新记忆。
                    ConversationMemoryPins.forget(conversationId)
                    val committed = snapshot.copy(operationId = request.runId)
                    AgentRunCheckpointStore.saveContext(appContext, request.runId, committed)
                    session.updateContext(committed)
                },
                onTranscript = { transcript ->
                    AgentRunCheckpointStore.saveTranscript(appContext, request.runId, transcript)
                    session.updateTranscript(transcript)
                },
                capabilitiesProvider = { AgentToolCapabilities.capture(appContext) },
                prompt = request.prompt,
                assistantScreenContext = request.assistantScreenContext,
                goalPlan = goalPlan?.promptSection().orEmpty(),
                todoPlan = todoPlan?.promptSection().orEmpty(),
                taskLedgerPlan = taskLedgerPlan,
                backgroundTasks = backgroundTasksPlan,
                backgroundEvents = backgroundEventsPlan,
                toolSpill = AgentToolSpill.forApp(appContext),
                // 摘要分批进度只在这里接真日志：库内类默认静默，否则纯 JVM 单测会撞到未实现的 android.util.Log。
                // 判定日志走 infoPersisted：它只在原因变化时发一条，量很小，且正是事后要追问的「为何没压」；
                // 普通 info 会被后续的 DEBUG 事件顶出 logcat 环形缓冲。
                compactionLog = { line ->
                    if (line.startsWith("agent_context_decision")) {
                        AndroidAgentLogger.infoPersisted(line)
                    } else {
                        AndroidAgentLogger.info(line)
                    }
                },
                roundLog = { AndroidAgentLogger.info(it) },
                // 压缩是嵌套的独立请求，不会走 UsageReceived；在这里直接记账，purpose 单独标出来。
                // 键名必须与其它入口一致用 "compact"：曾用 "compaction" 写过，结果面板上同一用途被拆成两条
                // （一条显示「Context compaction」、一条裸的 "compaction"）。
                compactionUsage = { usage -> recordUsage(usage, request.config, "compact") },
                mode = runCatching {
                    Prefs.initLocal(appContext)
                    val stored = Prefs.getLocalAgentString(Prefs.Keys.AGENT_MODE)
                    if (stored.isNullOrBlank()) {
                        // 旧键兼容：升级用户没有 AGENT_MODE 时，计划模式照旧生效。
                        if (Prefs.isEnabled(Prefs.Keys.AGENT_PLAN_MODE)) AgentMode.PLAN else AgentMode.DEFAULT
                    } else {
                        AgentMode.fromId(stored)
                    }
                }.getOrDefault(AgentMode.DEFAULT),
                pluginPrompt = executor.pluginPrompt(),
                toolExecutor = runToolExecutor,
                images = request.images,
                history = request.history,
                runController = runController,
                skillContext = skillContext,
                memoryContext = memoryContext,
                additionalTools = runTools,
                executionContext = executor.terminalExecutionContext,
                // 轮次广播通道：让 acp_delegate 的执行中进度带上 round，从而挂回同一张工具卡片。
                progressScope = progressScope,
            ) { event ->
                timing.accept(event)
                acceptEvent(
                    session,
                    event,
                    archivedEvents,
                    entrySurfaceGuard,
                    checkpointRecorder,
                    usageConfig = request.config,
                    usageOperation = request.operation,
                )
            }
            response = completedResponse
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = true,
                content = completedResponse.content,
                reasoningContent = completedResponse.reasoningContent,
                transcript = completedResponse.transcript,
                contextSnapshot = completedResponse.contextSnapshot?.copy(operationId = request.runId),
                operation = request.operation,
                rewriteTargetMessageId = request.rewriteTargetMessageId,
            )
        } catch (throwable: Throwable) {
            // 失败时历史是否完整落盘不确定，放弃固定的记忆快照；记忆未变时重新注入的内容逐字节相同。
            ConversationMemoryPins.forget(memoryConversationId)
            cancelled = runController.isCancelled || throwable is AgentRunCancelledException
            val modelFailure = throwable as? AgentModelExecutionException
            val message = if (cancelled) {
                "已停止"
            } else {
                throwable.message ?: throwable.javaClass.simpleName
            }
            if (cancelled) {
                AndroidAgentLogger.info("Agent runtime stopped: id=${request.runId.take(8)}")
            } else {
                val requestFailure = modelFailure?.cause as? AgentModelFailure
                AndroidAgentLogger.error(
                    "Agent runtime failed: type=${throwable.safeLogType()}, " +
                        "model_code=${requestFailure?.code.orEmpty()}, " +
                        "cause_type=${requestFailure?.cause?.safeLogType().orEmpty()}"
                )
                val event = AgentEvent.RunFailed(message)
                runCatching {
                    acceptEvent(
                        session,
                        event,
                        archivedEvents,
                        entrySurfaceGuard,
                        checkpointRecorder,
                        usageConfig = request.config,
                        usageOperation = request.operation,
                    )
                }.onFailure { checkpointFailure ->
                    AndroidAgentLogger.error(
                        "Agent runtime failure checkpoint failed: " +
                            "type=${checkpointFailure.safeLogType()}"
                    )
                    session.emit(event)
                }
            }
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = false,
                content = "",
                error = message,
                reasoningContent = modelFailure?.reasoningContent.orEmpty(),
                transcript = modelFailure?.transcript.orEmpty(),
                contextSnapshot = modelFailure?.contextSnapshot?.copy(operationId = request.runId) ?: session.contextSnapshot,
                operation = request.operation,
                rewriteTargetMessageId = request.rewriteTargetMessageId,
            )
        } finally {
            runCatching { toolsBinding?.close() }
            runCatching { toolExecutor?.close() }
            // 先关工具（此时仍持有操作权），再交还锁：反过来会让别的 run 在清理工具时抢进屏幕。
            runCatching { guiRun?.close() }
            runCatching { browserBinding?.close() }
        }

        if (cancelled && session.isTerminal) {
            runCatching {
                persistArtifacts(snapshotRequest(request), result, archivedEvents)
            }.onFailure { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime cancelled result persistence failed: " +
                        "type=${throwable.safeLogType()}"
                )
            }
            return Outcome(
                result = result,
                entrySurfaceGuard = entrySurfaceGuard,
                shouldUpdateHost = true,
            )
        }

        val completedRequest = runCatching { snapshotRequest(request) }
            .getOrElse { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime request snapshot failed: type=${throwable.safeLogType()}"
                )
                request
            }
        val committed = session.complete(result) {
            runCatching { checkpointRecorder?.seal() }
                .onFailure { throwable ->
                    AndroidAgentLogger.error(
                        "Agent runtime checkpoint seal failed: type=${throwable.safeLogType()}"
                    )
                }
            runCatching { persistArtifacts(completedRequest, result, archivedEvents) }
                .onFailure { throwable ->
                    AndroidAgentLogger.error(
                        "Agent runtime artifact persistence failed: type=${throwable.safeLogType()}"
                    )
                }
        }
        return Outcome(
            result = result,
            entrySurfaceGuard = entrySurfaceGuard,
            completedRequest = completedRequest.takeIf { committed },
            response = response.takeIf { committed },
            shouldUpdateHost = committed,
        )
    }

    private fun acceptEvent(
        session: AgentRuntimeSession,
        event: AgentEvent,
        archivedEvents: MutableList<AgentEvent>,
        entrySurfaceGuard: EntrySurfaceGuard?,
        checkpointRecorder: AgentRunCheckpointRecorder?,
        usageConfig: AgentModelClient.ModelConfig? = null,
        usageOperation: String = "",
    ) {
        checkpointRecorder?.accept(event)
        if (event is AgentEvent.UsageReceived) recordUsage(event.usage, usageConfig, usageOperation)
        if (!session.emit(event)) return
        archivedEvents += event
        if (event is AgentEvent.ModelRetryScheduled) {
            AndroidAgentLogger.warn("Agent runtime event: ${event.toLogLine()}")
        } else if (event !is AgentEvent.AssistantBlockDelta) {
            AndroidAgentLogger.debug { "Agent runtime event: ${event.toLogLine()}" }
        }
        runCatching { onAcceptedEvent(event, entrySurfaceGuard) }
            .onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_event_projection_failed") {
                    "Agent runtime event projection failed: type=${throwable.safeLogType()}"
                }
            }
    }

    /**
     * 用量只做本地观测：写失败不影响对话，也不改变模型请求。
     * 口径见 [AgentUsageLedger]：cached 由服务商返回，可能已计入 input。
     */
    private fun recordUsage(
        usage: AgentTokenUsage,
        config: AgentModelClient.ModelConfig?,
        operation: String,
    ) {
        if (usage.isEmpty || config == null) return
        runCatching {
            AgentUsageLedger.get().record(
                AgentUsageLedger.Record.fromUsage(
                    ts = System.currentTimeMillis(),
                    provider = config.providerId.ifBlank { config.providerName },
                    model = config.model.ifBlank { config.modelDisplayName },
                    purpose = operation,
                    usage = usage,
                ),
            )
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("usage_ledger_write_failed") {
                "usage ledger write failed: type=${throwable.safeLogType()}"
            }
        }
    }
}

/**
 * 把 checkpoint 里的**存储值**（内联文本或分块引用）包成按块读的扫描器。
 *
 * 关键点：全程不调用 `restoreText` —— 真机上单个会话的 journal 有 3100 万字符，
 * 整段还原就是给分页读一条 8 KB 的历史先付 62 MB 的 String。
 */
private fun scannerFor(
    dao: ConversationDao,
    conversationId: String,
    field: String,
    stored: String,
): JsonArrayElementScanner = JsonArrayElementScanner { from, limit ->
    dao.readStoredChunks("conversation_context_checkpoints", conversationId, field, stored, from, limit)
}
