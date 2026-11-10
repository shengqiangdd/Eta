package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.acp.AcpDelegationMode
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.agent.trace.AgentRunTraceStore
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.roleplay.RoleplayRunContext
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.GeminiModelCapabilities
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.github.mangi.eta.agent.terminal.TerminalExecutionContext
import org.json.JSONArray
import org.json.JSONObject

internal object AgentModelClient {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val traceFormatter = AgentTraceFormatter()

    fun loadConfig(): ModelConfig {
        val runtimeJson = Prefs.getString(Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON)
        if (runtimeJson.isNotBlank()) {
            runCatching {
                json.decodeFromString<ModelConfig>(runtimeJson)
            }.getOrNull()?.let { runtime ->
                val thinkingAllowed = Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED)
                val effort = if (thinkingAllowed) {
                    runtime.effectiveReasoningEffort
                } else {
                    ReasoningEffort.OFF
                }
                return runtime.copy(
                    terminalTools = Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS),
                    browserTools = Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS),
                    deviceDirectTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
                    deviceSensitiveReadTools =
                        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
                    deviceSensitiveActionTools =
                        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
                    disabledTools = AgentToolDisableFilter.parse(Prefs.getLocalAgentString(Prefs.Keys.AGENT_DISABLED_TOOLS)),
                    thinkingEnabled = effort.enablesReasoning,
                    reasoningEffort = effort,
                    autoCompactionEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED),
                    subagentTools = Prefs.isEnabled(Prefs.Keys.AGENT_SUBAGENT_ENABLED),
                    acpDelegationMode = Prefs.getLocalAgentString(Prefs.Keys.AGENT_ACP_DELEGATION_MODE)
                        ?: runtime.acpDelegationMode,
                    acpDefaultAgent = Prefs.getLocalAgentString(Prefs.Keys.AGENT_ACP_DEFAULT_AGENT)
                        ?: runtime.acpDefaultAgent,
                )
            }
        }
        return ModelConfig(
            providerId = "builtin-openai",
            providerName = "OpenAI",
            providerType = ProviderTypes.OPENAI_COMPATIBLE,
            providerSourceType = ProviderSourceRegistry.resolve(
                providerId = "builtin-openai",
                baseUrl = "https://api.openai.com/v1",
                providerType = ProviderTypes.OPENAI_COMPATIBLE,
            ),
            baseUrl = "https://api.openai.com/v1",
            apiKey = "",
            model = "gpt-5.5",
            modelDisplayName = "GPT-5.5",
            systemPrompt = BuiltinProviders.DEFAULT_SYSTEM_PROMPT,
            autoCompactionEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED),
            subagentTools = Prefs.isEnabled(Prefs.Keys.AGENT_SUBAGENT_ENABLED),
            acpDelegationMode = Prefs.getLocalAgentString(Prefs.Keys.AGENT_ACP_DELEGATION_MODE)
                ?: AcpDelegationMode.ASK.wire,
            acpDefaultAgent = Prefs.getLocalAgentString(Prefs.Keys.AGENT_ACP_DEFAULT_AGENT).orEmpty(),
            terminalTools = Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            browserTools = Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS),
            deviceDirectTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
            deviceSensitiveReadTools =
                Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
            deviceSensitiveActionTools =
                Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
            disabledTools = AgentToolDisableFilter.parse(Prefs.getLocalAgentString(Prefs.Keys.AGENT_DISABLED_TOOLS)),
            thinkingEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED),
            reasoningEffort = ReasoningEffort.fromLegacy(
                Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED)
            ),
        )
    }

    fun complete(
        config: ModelConfig,
        prompt: String,
        toolExecutor: ToolExecutor,
        images: List<ModelImage> = emptyList(),
        history: List<ConversationMessage> = emptyList(),
        provider: AgentProviderClient = ProviderClientFactory.getClient(config),
        runController: AgentRunController = AgentRunController(),
        skillContext: SkillContext = SkillContext.EMPTY,
        memoryContext: AgentMemoryContext = AgentMemoryContext.DISABLED,
        additionalTools: JSONArray = JSONArray(),
        executionContext: TerminalExecutionContext? = null,
        capabilitiesProvider: () -> AgentToolCapabilities = { AgentToolCapabilities(rootAvailable = false) },
        sessionId: String = java.util.UUID.randomUUID().toString(),
        compactOnly: Boolean = false,
        operationId: String = sessionId,
        initialUserMessageId: String = "user-$operationId",
        initialSupplementIndex: Int = 0,
        roleplayContext: RoleplayRunContext? = null,
        rewriteReply: Boolean = false,
        assistantScreenContext: String = "",
        goalPlan: String = "",
        todoPlan: String = "",
        /** 会话级子代理台账段落；由运行时读盘得到，本轮内保持固定。 */
        taskLedgerPlan: String = "",
        /** 本机后台任务段落；同样本轮内固定（与台账一起在 run 开始时读一次）。 */
        backgroundTasks: String = "",
        /** 后台任务结束事件段落；与任务段一起在 run 开始时读一次。 */
        backgroundEvents: String = "",
        toolSpill: AgentToolSpill? = null,
        /** 摘要分批进度出口；默认静默，由运行时接 AndroidAgentLogger（纯单测不能碰 android.util.Log）。 */
        compactionLog: (String) -> Unit = {},
        /** 摘要请求的 token 用量出口；默认静默，由运行时接到用量账本。 */
        compactionUsage: (AgentTokenUsage) -> Unit = {},
        /** 每轮实际模型出口；运行时接到 AndroidAgentLogger，用于追溯热切换是否生效。 */
        roundLog: (String) -> Unit = {},
        /** 工作模式：决定工具裁剪与系统提示段（见 [AgentMode]）。 */
        mode: AgentMode = AgentMode.DEFAULT,
        /** 已启用插件的 prompt 扩展；由运行时从 PluginRuntime 取，进入系统提示。 */
        pluginPrompt: String = "",
        toolFilter: ((JSONArray) -> JSONArray)? = null,
        onContextSnapshot: (AgentContextSnapshot) -> Unit = {},
        onTranscript: (List<ConversationMessage>) -> Unit = {},
        /** 工具层读取轮次的通道；null 表示不接收执行中进度（纯单测、压缩等路径）。 */
        progressScope: ProgressScope? = null,
        onEvent: (AgentEvent) -> Unit = {},
    ): ModelResponse.Text {
        config.validate()
        // 运行追踪：只记录观测数据，写失败由 store 内部吞掉，不影响对话。
        AgentRunTraceStore.begin(
            runId = operationId,
            model = config.modelDisplayName.ifBlank { config.model },
            provider = config.providerName,
            purpose = if (rewriteReply) "rewrite" else if (compactOnly) "compact" else "chat",
            contextWindow = config.knownContextWindow,
        )
        val tracedOnEvent: (AgentEvent) -> Unit = { event ->
            AgentRunTraceStore.record(operationId, event)
            onEvent(event)
        }
        val initialCapabilities = capabilitiesProvider()
        val messages = AgentPromptBuilder.buildInitialMessages(
            config,
            prompt,
            images,
            history,
            skillContext,
            memoryContext,
            rootAvailable = initialCapabilities.rootAvailable,
            roleplayContext = roleplayContext,
            executionContext = executionContext,
            goalPlan = goalPlan,
            todoPlan = todoPlan,
            taskLedgerPlan = taskLedgerPlan,
            backgroundTasks = backgroundTasks,
            backgroundEvents = backgroundEvents,
            mode = mode,
            pluginPrompt = pluginPrompt,
        )
        if (rewriteReply) {
            messages.put(messages.length() - 1, AgentConversationCodec.userTextMessage(
                "请只改写下面这条角色回复，保持已有事实与实际工具结果，以当前角色设定改善表达。" +
                    "这不是重新执行任务；不得调用任何工具、重读设备、更新记忆或编造缺失证据。只输出替代正文。\n" +
                    "<reply_to_rewrite>\n$prompt\n</reply_to_rewrite>",
            ))
        } else if (!compactOnly) {
            messages.getJSONObject(messages.length() - 1).put("_eta_message_id", initialUserMessageId)
            AssistantScreenContextProjection.attach(messages.getJSONObject(messages.length() - 1), assistantScreenContext)
        }
        if (!compactOnly && config.providerType == ProviderTypes.GEMINI) {
            GeminiEphemeralState.markRunStart(messages.getJSONObject(messages.length() - 1))
        }
        if (compactOnly) messages.remove(messages.length() - 1)
        val transcript = JSONArray()
        // 旧 history 中的无效消息可能在组装时被跳过，系统边界不能由 history 条数倒推。
        val systemCount = AgentPromptBuilder.buildSystemMessages(
            config = config,
            skillContext = skillContext,
            memoryContext = memoryContext,
            rootAvailable = initialCapabilities.rootAvailable,
            roleplayContext = roleplayContext,
            executionContext = executionContext,
            goalPlan = goalPlan,
            todoPlan = todoPlan,
            taskLedgerPlan = taskLedgerPlan,
            backgroundTasks = backgroundTasks,
            backgroundEvents = backgroundEvents,
            mode = mode,
            pluginPrompt = pluginPrompt,
        ).length()
        fun toolsFor(capabilities: AgentToolCapabilities): JSONArray {
            if (rewriteReply) return JSONArray()
            val tools = AgentToolCatalog.build(
                terminalTools = config.terminalTools,
                browserTools = config.browserTools,
                deviceDirectTools = config.deviceDirectTools,
                deviceSensitiveReadTools = config.deviceSensitiveReadTools,
                deviceSensitiveActionTools = config.deviceSensitiveActionTools,
                skillGitHubDiscovery = true,
                skillGitHubInstall = true,
                memoryTools = memoryContext.enabled,
                memoryWritable = roleplayContext == null,
                capabilities = capabilities,
                localWebSearch = !config.usesHostedWebSearch,
                mode = mode,
            )
            for (index in 0 until additionalTools.length()) {
                tools.put(additionalTools.opt(index))
            }
            val filtered = toolFilter?.invoke(tools) ?: tools
            // 逐项禁用放在 toolFilter **之后**：toolFilter 可能是子代理或插件按自己的
            // 策略增删工具，用户显式禁用的工具不该被它再塞回来。空集合时直接返回原对象。
            return AgentToolDisableFilter.apply(filtered, config.disabledTools)
        }
        val tools = toolsFor(initialCapabilities)
        tracedOnEvent(
            AgentEvent.RunStarted(
                initialImages = images.size,
                initialImageBytes = images.sumOf { it.bytes },
                toolCount = tools.length(),
                terminalTools = config.terminalTools
            )
        )
        // 工具目录与系统提示都是请求前缀，按轮重算会让一次瞬时状态抖动废掉整段缓存。
        // Root 授权真正变化时整体重建；其余能力只增不减：短暂不可用的工具仍保留，执行时自行报告错误码。
        var promptRootAvailable = initialCapabilities.rootAvailable
        var publishedTools = tools
        fun toolsForRound(): JSONArray {
            val capabilities = capabilitiesProvider()
            if (capabilities.rootAvailable != promptRootAvailable) {
                val systemMessages = AgentPromptBuilder.buildSystemMessages(
                    config = config,
                    skillContext = skillContext,
                    memoryContext = memoryContext,
                    rootAvailable = capabilities.rootAvailable,
                    roleplayContext = roleplayContext,
                    executionContext = executionContext,
                    goalPlan = goalPlan,
                    todoPlan = todoPlan,
                    taskLedgerPlan = taskLedgerPlan,
                    backgroundTasks = backgroundTasks,
                    backgroundEvents = backgroundEvents,
                    mode = mode,
                    pluginPrompt = pluginPrompt,
                )
                for (index in 0 until systemMessages.length()) {
                    messages.put(index, systemMessages.getJSONObject(index))
                }
                promptRootAvailable = capabilities.rootAvailable
                publishedTools = toolsFor(capabilities)
                return publishedTools
            }
            val published = toolNames(publishedTools)
            val added = toolsFor(capabilities).let { fresh ->
                (0 until fresh.length()).map(fresh::getJSONObject).filter { toolName(it) !in published }
            }
            if (added.isNotEmpty()) {
                publishedTools = JSONArray(publishedTools.toString()).also { merged -> added.forEach(merged::put) }
            }
            return publishedTools
        }
        val loop = AgentLoop(
            transcript = transcript,
            systemCount = systemCount,
            operationId = operationId,
            toolSpill = toolSpill,
            compactionLog = compactionLog,
            roundLog = roundLog,
            compactionUsage = compactionUsage,
            onContextSnapshot = if (rewriteReply) ({ _ -> }) else onContextSnapshot,
            onTranscript = onTranscript,
            sessionId = sessionId,
            config = config,
            messages = messages,
            tools = tools,
            provider = provider,
            // 目录在运行内只增不减，可见不再等于可用：执行前用同一规则复核，
            // 中途失去的能力返回与目录过滤一致的错误码，而不是让底层接口给出含糊的失败。
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                val unavailable = AgentToolRequirements.find(call.name)
                    ?.let { capabilitiesProvider().unavailableCode(call.name) }
                if (unavailable == null) {
                    toolExecutor.execute(call)
                } else {
                    ToolResult(
                        JSONObject().put("ok", false).put("code", unavailable)
                            .put("message", "该工具当前不可用（$unavailable），本次未执行").toString(),
                    )
                }
            },
            runController = runController,
            traceFormatter = traceFormatter,
            onEvent = tracedOnEvent,
            progressScope = progressScope,
            modelRetry = AgentModelRetry(
                policy = AgentRetryPolicy(
                    maxRetries = config.modelMaxRetries.coerceIn(0, AgentRetryPolicy.MAX_RETRIES_LIMIT),
                ),
            ),
            onToolTrace = { round, call, result, elapsedMs ->
                AgentRunTraceStore.recordTool(
                    runId = operationId,
                    round = round,
                    name = call.name,
                    arguments = call.argumentsJson,
                    result = result.content,
                    elapsedMs = elapsedMs,
                    ok = traceFormatter.isSuccessResult(result),
                    imageCount = result.images.size,
                    sensitive = result.sensitive,
                )
            },
            purpose = if (rewriteReply) ProviderRequestPurpose.REPLY_REWRITE else ProviderRequestPurpose.CHAT,
            roleplayContext = roleplayContext,
            initialSupplementIndex = initialSupplementIndex,
            toolsForRound = ::toolsForRound,
        )
        val result = try {
            if (compactOnly) loop.compactOnly() else loop.run()
        } catch (throwable: Throwable) {
            AgentRunTraceStore.finish(
                runId = operationId,
                ok = false,
                error = if (runController.isCancelled) "已停止" else {
                    throwable.message ?: throwable.javaClass.simpleName
                },
            )
            throw AgentModelExecutionException(
                cause = throwable,
                contextSnapshot = if (rewriteReply) null else loop.contextSnapshot(),
                reasoningContent = loop.reasoningSnapshot(),
                transcript = AgentToolBatchRecovery.completeInterrupted(AgentConversationCodec.transcript(
                    transcript,
                    0,
                    loop.sensitiveToolCallIdsSnapshot(),
                )),
            )
        }
        AgentRunTraceStore.finish(operationId, ok = true, contentChars = result.content.length)
        return ModelResponse.Text(
            content = result.content,
            contextSnapshot = if (rewriteReply) null else loop.contextSnapshot(),
            reasoningContent = result.reasoningContent,
            transcript = AgentConversationCodec.transcript(
                transcript,
                0,
                result.sensitiveToolCallIds,
            ),
        )
    }

    private fun toolName(tool: JSONObject): String? = tool.optJSONObject("function")?.optString("name")

    private fun toolNames(tools: JSONArray): Set<String> =
        (0 until tools.length()).mapNotNullTo(mutableSetOf()) { toolName(tools.getJSONObject(it)) }

    private fun ModelConfig.validate() {
        require(baseUrl.isNotBlank()) { "请先配置 API 地址" }
        when {
            // 同一句报错可能来自任意一个没密钥的内置服务商，必须在消息里点明是谁：
            // 否则「模型列表能拉到、发消息却说没 Key」会被误读成登录失效。
            authMode == ProviderAuthModes.API_KEY -> require(apiKey.isNotBlank()) {
                val where = listOf(providerName, baseUrl)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (where.isEmpty()) "请先配置 API Key" else "请先配置 API Key（$where）"
            }
            ProviderAuthModes.isOAuth(authMode) -> {
                require(providerId.isNotBlank()) { "账号登录缺少服务商标识，请重新选择服务商" }
                require(apiKey.isEmpty()) { "账号登录配置不能包含 API Key" }
            }
            else -> error("不支持的服务商认证方式")
        }
        require(model.isNotBlank()) { "请先配置模型名" }
        require(
            reasoningCapabilities?.mandatory != true ||
                effectiveReasoningEffort != ReasoningEffort.OFF
        ) { "当前模型强制启用思考，不能选择 Off 或禁用思考权限" }
        if (extraBodyJson.isNotBlank()) {
            runCatching { JSONObject(extraBodyJson) }
                .getOrElse { throwable ->
                    error("额外请求体 JSON 无效：${throwable.message ?: throwable.javaClass.simpleName}")
                }
        }
    }

    fun buildUserHistoryMessage(
        text: String,
        images: List<ModelImage>,
    ): ConversationMessage =
        AgentConversationCodec.durableMessage(AgentConversationCodec.userMessage(text, images))

    internal fun summarizeOpenUriArguments(argumentsJson: String): String =
        traceFormatter.summarizeOpenUriArguments(argumentsJson)

    internal fun summarizeBrowserToolArguments(argumentsJson: String): String =
        traceFormatter.summarizeBrowserArguments(argumentsJson)

    internal fun summarizeToolResult(toolName: String, result: ToolResult): String =
        traceFormatter.summarizeResult(toolName, result)

    @Serializable
    data class ModelConfig(
        val providerId: String = "",
        val providerName: String = "",
        val providerType: String = ProviderTypes.OPENAI_COMPATIBLE,
        val providerSourceType: String = "",
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val modelDisplayName: String = "",
        val contextWindow: Int? = null,
        val systemPrompt: String,
        val anthropicVersion: String = AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION,
        val openAiEndpointMode: String = OpenAiEndpointMode.CHAT_COMPLETIONS,
        val hostedWebSearchEnabled: Boolean = false,
        val supportsSearchSuggestions: Boolean = false,
        val terminalTools: Boolean = false,
        val browserTools: Boolean = true,
        val deviceDirectTools: Boolean = true,
        val deviceSensitiveReadTools: Boolean = false,
        val deviceSensitiveActionTools: Boolean = false,
        val thinkingEnabled: Boolean = false,
        val reasoningEffort: ReasoningEffort? = null,
        val reasoningCapabilities: ModelReasoningCapabilities? = null,
        val extraBodyJson: String = "",
        val customHeaders: List<CustomHeader> = emptyList(),
        val customBody: List<CustomBody> = emptyList(),
        val autoCompactionEnabled: Boolean = Prefs.Keys.BOOLEAN_DEFAULTS.getValue(Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED),
        /** 模型请求失败（限流/5xx/网络）时的重试次数；0 表示只尝试一次。 */
        val modelMaxRetries: Int = 3,
        val subagentTools: Boolean = Prefs.Keys.BOOLEAN_DEFAULTS.getValue(Prefs.Keys.AGENT_SUBAGENT_ENABLED),
        /**
         * 复杂编码任务是否交给 ACP 编码代理；`off` / `ask` / `auto`。
         *
         * 存字符串是因为它是三态：用两个布尔表达「关闭 / 询问 / 自动」会出现
         * 两个都 true 这种无法解释的组合。
         */
        val acpDelegationMode: String = AcpDelegationMode.ASK.wire,
        /** 默认委派给哪个代理；空表示用第一个可用的。 */
        val acpDefaultAgent: String = "",
        val authMode: String = ProviderAuthModes.API_KEY,
        /**
         * 用户逐项禁用的工具名；空集合表示不裁剪。
         *
         * 与 `terminalTools` 那组分类开关的区别：分类开关只能整组开关，这里能只关掉
         * 组里的个别工具。由 [AgentToolDisableFilter] 在工具数组送出前统一裁剪。
         */
        val disabledTools: Set<String> = emptySet(),
    ) {
        val usesHostedWebSearch: Boolean
            get() = hostedWebSearchEnabled && when (providerType) {
                ProviderTypes.OPENAI_COMPATIBLE -> openAiEndpointMode == OpenAiEndpointMode.RESPONSES
                ProviderTypes.GEMINI -> supportsSearchSuggestions && GeminiModelCapabilities.supportsHostedSearch(model)
                else -> false
            }

        val hostedWebSearchToolName: String
            get() = if (providerType == ProviderTypes.GEMINI) "google_search" else "web_search"

        val effectiveReasoningEffort: ReasoningEffort
            get() = reasoningEffort ?: ReasoningEffort.fromLegacy(thinkingEnabled)

        /** 未知窗口不阻断对话：只关闭依赖窗口的自动压缩，手动压缩与服务商溢出错误照常可用。 */
        val knownContextWindow: Int?
            get() = contextWindow?.takeIf { it > 0 }
    }

    @Serializable
    data class ConversationMessage(
        val role: String,
        val content: String = "",
        val contentJson: String = "",
        val toolCallId: String = "",
        val reasoningContent: String = "",
        val toolCallsJson: String = "",
        val contextSummary: Boolean = false,
        val compactedUserTurns: Int = 0,
        val summaryThroughUserTurn: Int = 0,
        val messageId: String = "",
    )

    fun interface ToolExecutor {
        fun execute(toolCall: ToolCall): ToolResult
    }

    /**
     * 让工具层知道"当前是第几轮"的窄接口。
     *
     * 只解决一件事：`acp_delegate` 这类长时工具在自己的线程上汇报进度时，
     * 需要把轮次写进 [AgentEvent.ToolProgress]；而轮次只有编排层知道。
     *
     * **必须按 toolCallId 传递**，不能用 ThreadLocal：
     * 进度是从工具自己拉起的线程（ACP 的 `eta-acp-*` HandlerThread）上报的，
     * 与编排线程不是同一个线程，ThreadLocal 在那里恒为初始值 0，
     * 结果是所有进度事件都被当成"轮次未知"丢弃（界面一条都收不到）。
     *
     * 契约：`enterRound` 在工具开始前调用，`exitRound` 在工具结束后调用，
     * 两者必须用同一个 toolCallId 配对；同一 toolCallId 在 exitRound 之后
     * 再来报进度即视为迟到增量，调用方自行丢弃。
     */
    interface ProgressScope {
        fun enterRound(round: Int, toolCallId: String)
        fun exitRound(toolCallId: String)
    }

    data class ToolCall(
        val id: String,
        val name: String,
        val argumentsJson: String,
        val round: Int = 1,
    )

    data class ToolResult(
        val content: String,
        val images: List<ModelImage> = emptyList(),
        /**
         * 敏感结果仍会供当前 Agent loop 使用，但工具参数与原始结果不会进入持久会话。
         * 最终 assistant 自己组织的答复不受此标记影响。
         */
        val sensitive: Boolean = false,
        val command: String? = null,
    )

    /** 图片引用：入口侧可为本地 URI/路径，进入模型协议前必须解析为远程 URL 或 data URL。 */
    data class ModelImage(
        val reference: String,
        val mimeType: String,
        val bytes: Int,
        val width: Int? = null,
        val height: Int? = null,
        val source: String = "unknown",
        /** 截图已具有可上传编码，跨进程物化时保留字节，不走附件转码。 */
        val preserveOriginal: Boolean = false,
    )

    sealed interface ModelResponse {
        data class Text(
            val content: String,
            val reasoningContent: String = "",
            val transcript: List<ConversationMessage> = emptyList(),
            val contextSnapshot: AgentContextSnapshot? = null,
        ) : ModelResponse
    }

}

internal class AgentModelExecutionException(
    cause: Throwable,
    val reasoningContent: String,
    val transcript: List<AgentModelClient.ConversationMessage>,
    val contextSnapshot: AgentContextSnapshot? = null,
) : RuntimeException(cause.message ?: cause.javaClass.simpleName, cause)
