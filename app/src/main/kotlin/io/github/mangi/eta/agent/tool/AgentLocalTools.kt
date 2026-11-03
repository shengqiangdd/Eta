package io.github.mangi.eta.agent.tool

import android.content.Context
import android.graphics.Rect
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.view.Display
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.browser.AgentBrowserSession
import io.github.mangi.eta.agent.device.DeviceControlUnavailableException
import io.github.mangi.eta.agent.device.DisplayScopeErrors
import io.github.mangi.eta.agent.device.RootShellDeviceController.Observation
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.device.RootShellDeviceController
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.VirtualDisplayBudget
import io.github.mangi.eta.agent.device.VirtualDisplayController as ManagedVirtualDisplayController
import io.github.mangi.eta.agent.device.VirtualDisplayHub
import io.github.mangi.eta.agent.device.VirtualDisplayOwnership
import io.github.mangi.eta.agent.device.VirtualDisplayTasks
import io.github.mangi.eta.agent.virtualdisplay.CanvasRoutingPolicy
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayController
import io.github.mangi.eta.agent.acp.AcpClient
import io.github.mangi.eta.agent.acp.AcpModels
import io.github.mangi.eta.agent.acp.AcpProvider
import io.github.mangi.eta.agent.acp.AgentAcpTools
import io.github.mangi.eta.agent.plugin.AgentPluginTools
import io.github.mangi.eta.agent.plugin.PluginMcpRegistry
import io.github.mangi.eta.agent.plugin.PluginRuntime
import io.github.mangi.eta.agent.plugin.PluginPackageInstaller
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.WebToolsSettingsRepository
import io.github.mangi.eta.agent.plugin.PluginManifest
import io.github.mangi.eta.agent.model.AgentAskUserToolCatalog
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.device.LocalNetworkPermission
import io.github.mangi.eta.agent.web.AgentWebTools
import io.github.mangi.eta.agent.web.WebHttpTransport
import io.github.mangi.eta.agent.model.AgentScreenObservationContract
import io.github.mangi.eta.agent.model.AgentTasksToolCatalog
import io.github.mangi.eta.agent.model.AgentTodoPlan
import io.github.mangi.eta.agent.model.AgentSensitiveToolPolicy
import io.github.mangi.eta.agent.model.AgentTerminalToolCatalog
import io.github.mangi.eta.agent.model.AgentFileToolCatalog
import io.github.mangi.eta.agent.overlay.AgentHapticFeedback
import io.github.mangi.eta.agent.overlay.AgentOverlayClearance
import io.github.mangi.eta.agent.overlay.GestureIndicator
import io.github.mangi.eta.agent.runtime.AgentAppContext
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.model.AgentGoal
import io.github.mangi.eta.agent.model.AgentGoalPhase
import io.github.mangi.eta.agent.model.AgentGoalToolCatalog
import io.github.mangi.eta.agent.runtime.AgentGoalStore
import io.github.mangi.eta.agent.runtime.AgentFileBackups
import io.github.mangi.eta.agent.runtime.GuiControlCoordinator
import io.github.mangi.eta.agent.runtime.AgentRunFileChanges
import io.github.mangi.eta.agent.runtime.AgentTaskLedgerStore
import io.github.mangi.eta.agent.runtime.AgentTodoStore
import io.github.mangi.eta.agent.skill.SkillCompatibilityChecker
import io.github.mangi.eta.agent.skill.SkillIndexService
import io.github.mangi.eta.agent.skill.SkillInstallErrorCode
import io.github.mangi.eta.agent.skill.SkillInstallResult
import io.github.mangi.eta.agent.skill.SkillLoader
import io.github.mangi.eta.agent.skill.SkillPackageInstaller
import io.github.mangi.eta.agent.skill.SkillParser
import io.github.mangi.eta.agent.skill.SkillResourceReader
import io.github.mangi.eta.agent.skill.SkillResourceReadResult
import io.github.mangi.eta.agent.skill.GitHubSkillRepositoryParser
import io.github.mangi.eta.agent.skill.GitHubSkillInspection
import io.github.mangi.eta.agent.skill.GitHubSkillRepository
import io.github.mangi.eta.agent.skill.GitHubSkillSourceException
import io.github.mangi.eta.agent.skill.PublicGitHubSkillSource
import io.github.mangi.eta.agent.terminal.FileAccessLedger
import io.github.mangi.eta.agent.terminal.FileBackupStore
import io.github.mangi.eta.agent.terminal.TerminalExecutionContext
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.agent.terminal.RootShellTerminalController
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.TerminalEnvEntry
import io.github.mangi.eta.agent.terminal.TerminalEnvInjection
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.data.repository.AgentMemoryException
import io.github.mangi.eta.data.repository.AgentMemoryMutation
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AgentMemoryWriteResult
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.runBlocking

internal class AgentLocalTools(
    private val context: Context,
    private val logger: AgentLogger,
    private val browserRunId: String = "",
    private val browserToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS)
    },
    private val terminalToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS)
    },
    private val deviceDirectToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS)
    },
    private val deviceSensitiveReadToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS)
    },
    private val deviceSensitiveActionToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS)
    },
    private val memoryToolsEnabled: () -> Boolean = {
        runBlocking { AgentMemoryRepository.isEnabled() }
    },
    private val memoryWritable: Boolean = true,
    private val screenshotExcludedPackages: () -> Set<String> = { emptySet() },
    private val screenObservationProvider: (
        (AgentScreenObservationContract.Options) -> RootShellDeviceController.Observation
    )? = null,
    private val beforeToolExecution: (String) -> ToolExecutionDecision = {
        ToolExecutionDecision.Allow
    },
    private val skillIndexService: SkillIndexService? = null,
    private val skillLoader: SkillLoader? = null,
    private val skillResourceReader: SkillResourceReader? = null,
    private val githubSkillSource: PublicGitHubSkillSource? = null,
    private val skillPackageInstaller: SkillPackageInstaller? = null,
    runAvailableSkillIds: Set<String> = emptySet(),
    pendingSkillConflict: PendingSkillConflictCapability? = null,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
    /** 后台虚拟屏（画布）总开关；关掉后 launch_app(display=auto/virtual) 一律回到物理主屏。 */
    private val virtualDisplayEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_VIRTUAL_DISPLAY_ENABLED)
    },
    /** 画布后端探测（LSPosed 可用的另一条创建路径）。 */
    private val lsposedAvailable: () -> Boolean = { EtaApp.serviceInstance != null },
    /**
     * GUI 独占锁。非空时，所有「会动屏幕」的工具都要先拿到它：多会话并行时只能有一个 run
     * 在操作手机；[GuiControlCoordinator.RunControl.withAction] 同时承担排队与用户暂停。
     */
    private val guiControl: GuiControlCoordinator.RunControl? = null,
    /** 会话级任务清单作用域；空串表示当前运行没有可持久化的会话（工具会明确报错而不是静默丢弃）。 */
    private val todoScopeId: String = "",
    /** 会话级长期目标作用域；与 todo 共用同一个会话 id，但分开存储。 */
    private val goalScopeId: String = "",
    /** 会话工作区作用域；空串（语音/一次性入口）沿用全局工作区。 */
    private val workspaceScopeId: String = "",
    /** 本次运行 id：把「工具自己改过哪些文件」聚成一份运行级账本；空串表示不记录。 */
    private val changeScopeId: String = "",
    /** 文件检查点作用域：同一会话跨轮可恢复；未提供时退回运行级。 */
    private val backupScopeId: String = changeScopeId,
    /**
     * 虚拟屏归属（会话级）：同一对话跨轮建的屏都算自己的，别的对话的屏默认不可见、不可释放。
     * 空串（语音 / 一次性入口）时退回运行级判断，见 [virtualDisplayRunId]。
     */
    private val virtualDisplayOwnerId: String = workspaceScopeId,
    /** 虚拟屏归属（运行级兜底）：没有会话时，只有本次运行建的屏算自己的。 */
    private val virtualDisplayRunId: String = changeScopeId,
    /**
     * 长时工具的执行中进度出口。
     *
     * 目前唯一的使用者是 `acp_delegate`：它把整个外部代理回合包在一次工具调用里，
     * 期间事件流上什么都没有，界面只剩一张静止的卡片。把代理的 session/update
     * 转成 [AgentEvent.ToolProgress] 抛回来，用户才能看到它在读什么、在跑什么。
     *
     * 调用线程是 ACP 读线程，实现方负责切到主线程；默认空转保持既有行为不变。
     */
    private val onToolProgress: ((AgentEvent.ToolProgress) -> Unit)? = null,
    /**
     * 浏览器会话；上游 3.3.5 起 [AgentBrowserSession] 由 object 改为 class（每会话一实例），
     * 调用方不传时按需懒创建，保持单会话路径的既有行为。
     */
    browserSession: AgentBrowserSession? = null,
) : AgentModelClient.ToolExecutor, AutoCloseable {

    private val closed = AtomicBoolean(false)
    @Volatile
    private var activeDisplayId: Int = Display.DEFAULT_DISPLAY
    private val browserSessionRef = lazy { browserSession ?: AgentBrowserSession() }
    private val todoStore by lazy { AgentTodoStore.forApp(context) }
    private val taskStore by lazy { AgentTaskLedgerStore.forApp(context) }
    private val goalStore by lazy { AgentGoalStore.forApp(context) }
    private val runFileChanges by lazy { AgentRunFileChanges.forApp(context) }
    private val virtualDisplays = ManagedVirtualDisplayController(context, logger, rootAvailable)
    private val acpShellSupervisor = ShellProcessSupervisor()

    /**
     * ACP 客户端。必须 lazy：它依赖下面的 [terminalExecutionContext]，
     * 而那个上下文在构造期才就绪。
     */
    private val acpClient: AcpClient by lazy {
        AcpClient(
            logger = logger,
            rootAvailable = rootAvailable,
            linuxShell = ::wrapForLinuxEnvironment,
            envScript = ::wrapEnvForLinux,
        )
    }

    /**
     * ACP 凭据的落点：与终端 env.conf 完全同一套（rootfs 内 `/tmp` 的一次性 0600 脚本）。
     * 返回 null 表示环境未就绪，[AcpClient] 会中止而不是把 key 拼回命令行。
     */
    private fun wrapEnvForLinux(entries: List<TerminalEnvEntry>): String? {
        val rootfs = terminalExecutionContext.linuxRootfsPath ?: return null
        if (!LinuxEnvironmentPaths.rootfsReady(rootfs)) return null
        return TerminalEnvInjection(
            hostDirectory = File(rootfs, "tmp"),
            shellDirectory = "/tmp",
            ownsDirectory = false,
        ).script(entries)
    }

    /**
     * 把一条命令包进 Linux 工具环境（chroot / proot 由 rootfs 路径判定）。
     *
     * 与终端会话复用同一套 payload 构造：ACP 与终端必须在同一个环境里，
     * 否则「终端里装好的代理」在 ACP 里永远显示未安装 —— 路径是 guest 视角的。
     */
    private fun wrapForLinuxEnvironment(command: String): String? {
        val rootfs = terminalExecutionContext.linuxRootfsPath ?: return null
        if (!LinuxEnvironmentPaths.rootfsReady(rootfs)) return null
        // ACP 与终端共用同一套「会话默认目录」；否则会话里 cd 到项目目录后，
        // acp_delegate 仍在 /workspace 跑，模型会看到两套不一致的 cwd。
        val cwd = terminalExecutionContext.linuxCwd
        val wrapped = if (cwd != io.github.mangi.eta.agent.terminal.TerminalRuntime.LINUX_WORKSPACE_PATH) {
            "cd ${io.github.mangi.eta.agent.terminal.shellQuote(cwd)} && $command"
        } else {
            command
        }
        return acpShellSupervisor.buildLinuxPayload(
            rootfsPath = rootfs,
            command = wrapped,
            sharedMounts = SharedFolderMounts.current(),
        )
    }
    val pluginRuntime = PluginRuntime(context, logger)

    init {
        // 进程重启后，已安装插件声明的 ACP 代理要恢复可见，
        // 否则用户会遇到"插件装着，acp_agent_list 里却没有"。
        syncExternalAcpAgents()
    }
    private val deviceController = RootShellDeviceController(
        logger = logger,
        screenshotExcludedPackages = screenshotExcludedPackages,
        rootAvailable = rootAvailable,
        virtualDisplays = virtualDisplays,
        packageNameProvider = { context.packageName },
        systemHookAvailable = { EtaApp.serviceInstance != null },
        appContext = context,
    )

    init {
        // 释放虚拟屏前先清掉屏上的任务：直接销毁显示可能让系统把任务迁回主屏。
        // 清理失败（仍有任务）时不释放，避免把虚拟屏里的应用顶到用户面前。
        virtualDisplays.taskCleaner = { displayId ->
            deviceController.closeTasksOnDisplay(displayId).ok
        }
    }
    private val rootCommandExecutor = BoundedRootCommandExecutor(logger, rootAvailable = rootAvailable)
    /** 自审：只读，不改任何系统状态；无 root 时崩溃域自动降级为 unavailable。 */
    private val selfAuditTool = AgentSelfAuditTool(context, rootCommandExecutor, rootAvailable, logger)
    private val structuredDeviceTools = AgentStructuredDeviceTools(
        context = context,
        logger = logger,
        root = rootCommandExecutor,
        rootAvailable = rootAvailable,
    )
    private val imageTools = AgentImageTools(context, rootCommandExecutor, rootAvailable)
    private val webTools = AgentWebTools(
        transport = WebHttpTransport(AgentHttpClient.client),
        localNetworkAccess = { LocalNetworkPermission.accessState(context).name.lowercase(Locale.ROOT) },
        // 用户在「网页搜索与读取」页保存的后端/密钥/模板，必须在每次调用时解析，
        // 否则设置页能保存但工具永远用内置默认值。
        runtimeConfig = { runBlocking { WebToolsSettingsRepository.resolveRuntimeConfig(context) } },
    )
    val terminalExecutionContext = LinuxEnvironmentSettingsRepository.current(context).terminalEnvironment.let { environment ->
        TerminalExecutionContext(
            linuxEnvironment = environment,
            linuxRootfsPath = LinuxEnvironmentPaths.rootfsDir(context, checkNotNull(environment.linuxDistribution)).absolutePath,
            userWorkspacePath = io.github.mangi.eta.agent.terminal.SessionWorkspaceStore.resolve(
                context,
                workspaceScopeId,
            ),
        ).also { execution ->
            // Linux 的 /workspace 是共享 bind，会话隔离只能落在默认 cwd 上。
            execution.linuxCwd = io.github.mangi.eta.agent.terminal.SessionWorkspaceStore.resolveLinuxCwd(
                context,
                workspaceScopeId,
                execution.linuxHostWorkspacePath,
                execution.linuxRootfsPath,
            )
        }
    }
    /** 本次运行的读取门禁与文件备份存储；备份按运行目录隔离。 */
    private val fileAccessLedger = FileAccessLedger()
    private val fileBackupStore: FileBackupStore? by lazy {
        if (backupScopeId.isBlank()) null else AgentFileBackups.forApp(context, backupScopeId)
    }

    private val terminalController = RootShellTerminalController(
        logger = logger,
        rootAvailable = rootAvailable,
        executionContext = terminalExecutionContext,
        backgroundJobs = io.github.mangi.eta.agent.terminal.TerminalRuntime.backgroundJobs(context),
        // 后台任务记录带上「哪个会话派的」：任务结束时只有该会话会收到自动续跑，
        // 不再让当前显示着的会话替别的会话花额度。
        backgroundScopeId = goalScopeId,
        detachedSupervisor = DetachedTaskSupervisor(
            logger = logger,
            recordsFile = DetachedTaskSupervisor.defaultRecordsFile(context),
            linuxRootfsPath = terminalExecutionContext.linuxRootfsPath,
            linuxSharedMountsProvider = { SharedFolderMounts.current() },
            rootAvailable = rootAvailable,
        ),
        linuxSharedMountsProvider = { SharedFolderMounts.current() },
        environmentScriptProvider = { environment ->
            // 值只写进 0600 临时脚本，命令行只出现路径：明文的 `export KEY='值'` 会进 argv，`ps` 可见。
            io.github.mangi.eta.agent.terminal.terminalEnvScript(
                entries = io.github.mangi.eta.agent.terminal.TerminalEnvFile(context.filesDir).effectiveEntries(),
                environment = environment,
                filesDir = context.filesDir,
                rootfsPath = terminalExecutionContext.linuxRootfsPath,
            )
        },
        fileAccessLedger = fileAccessLedger,
        fileBackupStore = fileBackupStore,
    )

    init {
        // 项目规则（AGENTS.md 等）从当前工作区读取；每次构建系统提示时动态刷新。
        io.github.mangi.eta.agent.model.AgentPromptBuilder.projectRulesProvider = {
            io.github.mangi.eta.agent.project.ProjectRules.load(
                terminalExecutionContext.androidWorkspacePath,
            )
        }
    }

    private val publishedObservation = AtomicReference(PublishedObservation())

    private val runAvailableSkillIds = runAvailableSkillIds
        .mapTo(mutableSetOf(), SkillParser::normalizeSkillLookup)
    private val mutatedSkillIds = ConcurrentHashMap.newKeySet<String>()
    private val skillTreeMutationUncertain = AtomicBoolean(false)
    private val pendingSkillConflict = AtomicReference(pendingSkillConflict)
    private val inspectedGitHubSnapshots =
        ConcurrentHashMap<String, GitHubInspectionSnapshot>()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        virtualDisplays.close()
        acpClient.close()
        publishedObservation.set(PublishedObservation())
        if (browserSessionRef.isInitialized()) browserSessionRef.value.interruptAgentAction(browserRunId)
        webTools.close()
        deviceController.closeVirtualImeGuard()
        terminalController.interruptAll()
        rootCommandExecutor.close()
        githubSkillSource?.close()
        inspectedGitHubSnapshots.clear()
        // 运行结束就交还操作权：否则别的会话会一直等在这把锁上。
        guiControl?.close()
    }

    /**
     * 把已启用插件声明的 ACP 代理同步进 AcpClient。
     *
     * 安装、卸载、启停插件后都要调用，否则"装完插件却看不到代理"。
     * 幂等：每次都按当前已启用插件全量重建，不会重复累加。
     */
    private fun syncExternalAcpAgents() {
        acpClient.replaceExternalAgents(pluginRuntime.externalAcpAgents())
    }

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val control = guiControl
        val result = if (control != null && AgentToolRequirements.requiresGuiControl(toolCall.name)) {
            try {
                control.withAction(guiScopeFor(toolCall)) { generation -> executeLocal(toolCall, generation) }
            } catch (timeout: GuiControlCoordinator.GuiWaitTimeoutException) {
                textResult(
                    errorResult(
                        "GUI_WAIT_TIMEOUT",
                        "等待手机操作权超时（${timeout.scope}，${timeout.timeoutMs}ms）：另一个会话正在操作这块屏。" +
                            "稍后重试；如果两边用的是不同的屏，请显式传 display_id，让两个会话各用各的。",
                    ),
                )
            }
        } else {
            executeLocal(toolCall, null)
        }
        return tagToolResult(toolCall.name, result)
    }

    /**
     * 这次动作要动哪块屏。
     *
     * 显式的 `display_id` 优先；没传时沿用最近一次观察所在的屏（[ImplicitDisplayPolicy]）。
     * 解析结果同时决定 GUI 操作权的 scope —— 不同屏的动作可以并行，同一块屏仍然排队。
     */
    private fun guiScopeFor(toolCall: AgentModelClient.ToolCall): String {
        val arguments = runCatching {
            JSONObject(toolCall.argumentsJson.ifBlank { "{}" })
        }.getOrDefault(JSONObject())
        return resolveGuiScope(
            explicitDisplayId = arguments.optInt("display_id", 0),
            publishedDisplayId = publishedObservation.get().displayId,
        )
    }

    /**
     * 让失败结果也带上 `tool`。
     *
     * 现状：成功结果一般有 `"tool"`，而 `NO_OBSERVATION` / `DISPLAY_RELEASED` / `MAIN_INPUT_ACTIVE`
     * 这类失败结果只有 `ok/code/message`。调用方（模型、自测脚本、审计）拿到一条裸错误时，
     * 只能靠上下文猜是哪个工具失败 —— 真机上就把 mock 的分支判错，整条剧本被静默收尾。
     * 已有 `tool` 的结果不覆盖；不是 JSON 对象的结果（数组/纯文本）原样返回。
     */
    private fun tagToolResult(
        name: String,
        result: AgentModelClient.ToolResult,
    ): AgentModelClient.ToolResult {
        val json = runCatching { JSONObject(result.content) }.getOrNull() ?: return result
        if (json.has("tool")) return result
        json.put("tool", name)
        return result.copy(content = json.toString())
    }

    /**
     * 真正的工具分发。
     *
     * [guiGeneration] 非空表示这次动作在 GUI 独占锁内执行：观察快照会记下当时的代号。
     * 用户暂停/恢复（或所有权换手）会推进代号，旧快照随即作废 —— 否则恢复之后模型会拿
     * 暂停前看到的节点去点现在的界面，点在完全不同的地方。
     */
    private fun executeLocal(
        toolCall: AgentModelClient.ToolCall,
        guiGeneration: Long?,
    ): AgentModelClient.ToolResult =
        runCatching {
            if (closed.get()) throw AgentRunCancelledException()
            val args = JSONObject(toolCall.argumentsJson.ifBlank { "{}" })
            if (guiGeneration != null && publishedObservation.get().generation != guiGeneration) {
                publishedObservation.set(PublishedObservation())
                if (AgentToolRequirements.requiresFreshObservation(toolCall.name)) {
                    return@runCatching textResult(
                        errorResult(
                            code = "NO_OBSERVATION",
                            message = "手机操作权已变化，请先调用 observe_screen 重新观察，再操作当前页面",
                        ),
                    )
                }
            }
            if (AgentToolRequirements.find(toolCall.name) != null &&
                AgentToolRequirements.rootDenied(toolCall.name, args, rootAvailable())
            ) {
                return@runCatching textResult(errorResult("ROOT_REQUIRED", "此操作需要 Root 授权，本次未执行"))
            }
            deviceToolPermissionError(toolCall.name)?.let { return@runCatching it }
            memoryToolPermissionError(toolCall.name)?.let { return@runCatching it }
            when (val decision = beforeToolExecution(toolCall.name)) {
                ToolExecutionDecision.Allow -> Unit
                is ToolExecutionDecision.Reject -> {
                    if (decision.code.startsWith("ACCESSIBILITY_")) publishedObservation.set(PublishedObservation())
                    return@runCatching textResult(
                        errorResult(
                            code = decision.code,
                            message = decision.message,
                        ),
                    )
                }
            }
            val produced = when (toolCall.name) {
                "eta_self_audit" -> textResult(selfAuditTool.run(args))
                "get_current_context" -> textResult(DeviceContextTool.current(context))
                "search_apps" -> textResult(searchApps(args))
                "launch_app" -> textResult(launchApp(args))
                "present_to_user" -> {
                    publishedObservation.set(PublishedObservation())
                    textResult(presentToUser(args))
                }
                "open_uri" -> textResult(openUri(args))
                "browser_use" -> browserUse(args, toolCall.id)
                "web_search", "fetch_url" -> {
                    if (!browserToolsEnabled()) textResult(errorResult("BROWSER_TOOLS_DISABLED", "请先启用网页搜索、读取与浏览器工具"))
                    else textResult(webTools.execute(toolCall.name, args))
                }
                "observe_screen" -> observeScreen(args, guiGeneration)
                "release_gui" -> {
                    // 主动交还操作权：别的会话的 GUI 动作不必再等这一轮。
                    publishedObservation.set(PublishedObservation())
                    guiControl?.release()
                    textResult(JSONObject().put("ok", true).put("tool", "release_gui").toString())
                }
                in AgentVirtualDisplayTools.names -> virtualDisplayTool(toolCall.name, args, guiGeneration)
                in AgentAcpTools.names -> acpTool(toolCall.name, args, toolCall.id)
                in AgentPluginTools.names -> pluginTool(toolCall.name, args)
                "tap" -> {
                    val displayId = resolveDisplayId(args)
                    afterAction(tap(args, displayId), displayId)
                }
                "tap_area" -> {
                    val displayId = resolveDisplayId(args)
                    afterAction(tapArea(args, displayId), displayId)
                }
                "tap_element" -> afterAction(tapElement(args))
                "long_press" -> {
                    val displayId = resolveDisplayId(args)
                    afterAction(longPress(args, displayId), displayId)
                }
                "long_press_element" -> afterAction(longPressElement(args))
                "swipe" -> {
                    val displayId = resolveDisplayId(args)
                    afterAction(swipe(args, displayId), displayId)
                }
                "scroll" -> {
                    val displayId = resolveDisplayId(args)
                    afterAction(
                        deviceController.scroll(
                            direction = args.optString("direction"),
                            amount = args.optString("amount"),
                            displayId = displayId,
                        ),
                        displayId,
                    )
                }
                "scroll_element" -> afterAction(scrollElement(args))
                "type_text" -> afterAction(typeText(args), actionDisplayOrSnapshot(args))
                // 旧会话或旧入口仍可能请求这些工具名；保留执行路径，但不再出现在模型目录里。
                "input_text" -> afterAction(inputText(args), actionDisplayOrSnapshot(args))
                "replace_text" -> afterAction(replaceText(args), actionDisplayOrSnapshot(args))
                "clear_text" -> afterAction(clearText(args), actionDisplayOrSnapshot(args))
                "set_clipboard" -> textResult(setClipboard(args))
                "get_clipboard" -> textResult(getClipboard())
                "paste_text" -> afterAction(pasteText(args), actionDisplayOrSnapshot(args))
                "press_key" -> {
                    val displayId = resolveDisplayId(args)
                    afterAction(
                        deviceController.pressKey(
                            button = args.optString("button"),
                            // 这里原先没传 display_id：虚拟屏上按 BACK/HOME 会作用到**主屏**，
                            // 而返回值仍是 ok —— 模型完全看不出动错了屏。实测缺陷。
                            displayId = displayId,
                            // fix166：显式写了 display_id 就必须按它注入（含显式 0=主屏），
                            // 否则会被当成「没指定」而交给无障碍全局动作，落点跟焦点跑。
                            explicitDisplayId = args.has("display_id"),
                        ),
                        displayId,
                    )
                }
                "wait" -> textResult(deviceController.waitMs(args.optInt("duration_ms", 1_000)))
                "wait_for_text" -> textResult(waitForText(args))
                "wait_for_package" -> textResult(waitForPackage(args))
                "open_system_panel" -> {
                    val displayId = resolveDisplayId(args, requireTask = false)
                    if (displayId != null) {
                        textResult(
                            errorResult(
                                "UNSUPPORTED_ON_VIRTUAL_DISPLAY",
                                "通知面板与快捷设置面板只属于主屏，虚拟屏上不可用",
                            ),
                        )
                    } else {
                        textResult(deviceController.openSystemPanel(args.optString("panel")))
                    }
                }
                in DEVICE_TOOL_NAMES ->
                    structuredDeviceTools.execute(toolCall.name, args)
                        ?: textResult(errorResult("UNKNOWN_TOOL", "未知设备工具"))
                "read_image" -> fileVisionTool { imageTools.readImage(args) }
                in AgentTerminalToolCatalog.names -> textResult(terminalTool {
                    // 参考 dsh 的 auto-review：只拦「一执行就可能弄坏设备」的命令。
                    val danger = DangerousCommandGuard.check(args.optString("command"))
                    if (danger != null) {
                        return@terminalTool errorResult(
                            "DANGEROUS_COMMAND_BLOCKED",
                            "命令命中危险模式（$danger），已阻止执行；如确需操作，请先让用户确认并说明用途",
                        )
                    }
                    if (toolCall.name == "terminal" && args.optString("action").equals("check_root", ignoreCase = true)) checkRoot()
                    else terminalController.executeTool(toolCall.name, args)
                })
                "working_directory" -> textResult(terminalTool { workingDirectory(args) })
                in AgentFileToolCatalog.names -> textResult(terminalTool {
                    val result = terminalController.fileTool(toolCall.name, args)
                    if (toolCall.name == "write_file" || toolCall.name == "edit_file" || toolCall.name == "edit_files" || toolCall.name == "restore_file") {
                        recordFileChange(toolCall.name, result)
                    } else {
                        result
                    }
                })
                "todo_write" -> textResult(todoWrite(args))
                AgentTasksToolCatalog.NAME -> textResult(tasks(args))
                AgentGoalToolCatalog.NAME -> textResult(goal(args))
                AgentAskUserToolCatalog.NAME -> textResult(askUser(args))
                "memory_get" -> textResult(memoryGet(args))
                "memory_write" -> textResult(memoryWrite(args))
                "skills_list" -> textResult(skillsList(args))
                "skills_read" -> textResult(skillsRead(args))
                "skills_read_resource" -> textResult(skillsReadResource(args))
                "skills_list_curated" -> textResult(skillsListCurated())
                "skills_inspect_github" -> textResult(skillsInspectGitHub(args))
                "skills_install_from_github" -> textResult(skillsInstallFromGitHub(args))
                else -> textResult(
                    errorResult(
                        code = "UNKNOWN_TOOL",
                        message = "未知工具：${toolCall.name}"
                    )
                )
            }
            annotateArgumentHints(toolCall.name, args, produced)
        }.getOrElse { throwable ->
            val code = when (throwable) {
                // 参数错与「屏不对」必须分开：原来两者都是 INVALID_ARGUMENT，
                // 模型会去改参数重来（真机上反复发生），而真正的修法是 observe / launch。
                is InvalidToolArgumentException -> throwable.code
                is org.json.JSONException -> "INVALID_ARGUMENT"
                is DeviceControlUnavailableException -> "ACCESSIBILITY_UNAVAILABLE"
                else -> "TOOL_ERROR"
            }
            val body = JSONObject(
                errorResult(
                    code = code,
                    message = throwable.message ?: throwable.javaClass.simpleName,
                ),
            )
            // 不可重试的错误必须说出来：只给错误码时，模型会把「屏不对」当成「参数不对」再试一次。
            if (throwable is InvalidToolArgumentException && !throwable.retryable) {
                body.put("retryable", false)
            }
            textResult(body.toString())
        }.let { result ->
            if (result.sensitive || !AgentSensitiveToolPolicy.isSensitive(toolCall.name)) {
                result
            } else {
                result.copy(sensitive = true)
            }
        }

    private fun deviceToolPermissionError(
        toolName: String,
    ): AgentModelClient.ToolResult? {
        val error = when {
            toolName in DEVICE_DIRECT_TOOL_NAMES && !deviceDirectToolsEnabled() ->
                "DEVICE_DIRECT_TOOLS_DISABLED" to "请先启用设备直达工具"
            toolName in DEVICE_SENSITIVE_READ_TOOL_NAMES && !deviceSensitiveReadToolsEnabled() ->
                "DEVICE_SENSITIVE_READ_TOOLS_DISABLED" to "请先允许读取敏感设备信息"
            toolName in DEVICE_SENSITIVE_ACTION_TOOL_NAMES && !deviceSensitiveActionToolsEnabled() ->
                "DEVICE_SENSITIVE_ACTION_TOOLS_DISABLED" to "请先允许敏感设备操作"
            else -> null
        } ?: return null
        return AgentModelClient.ToolResult(
            content = errorResult(error.first, error.second),
            sensitive = toolName in DEVICE_SENSITIVE_READ_TOOL_NAMES ||
                toolName in DEVICE_SENSITIVE_ACTION_TOOL_NAMES,
        )
    }

    private fun terminalTool(block: () -> String): String {
        if (!terminalToolsEnabled()) {
            return errorResult("TERMINAL_TOOLS_DISABLED", "请先启用终端/文件工具")
        }
        // 终端与文件工具最容易读到配置、数据库和日志里的密钥；在结果离开工具层之前打码，
        // 避免密钥进入模型上下文、会话记录和导出文件。
        return SecretRedactor.redact(block())
    }

    private fun fileVisionTool(block: () -> AgentModelClient.ToolResult): AgentModelClient.ToolResult {
        if (!terminalToolsEnabled()) {
            return textResult(errorResult("TERMINAL_TOOLS_DISABLED", "请先启用终端/文件工具"))
        }
        return block()
    }

    private fun memoryToolPermissionError(toolName: String): AgentModelClient.ToolResult? {
        if (toolName == "memory_write" && !memoryWritable) {
            return AgentModelClient.ToolResult(
                content = errorResult("REAL_MEMORY_READ_ONLY", "角色会话的现实记忆只读；剧情请使用角色记忆工具"),
                sensitive = true,
            )
        }
        if (toolName !in MEMORY_TOOL_NAMES || memoryToolsEnabled()) return null
        return AgentModelClient.ToolResult(
            content = errorResult("MEMORY_DISABLED", "记忆已在设置中关闭"),
            sensitive = true,
        )
    }

    /**
     * 整表替换的会话级任务清单。
     *
     * 校验失败一律不落盘：宁可让模型重写，也不要把半份清单当成「当前计划」。
     */
    private fun todoWrite(args: JSONObject): String {
        if (todoScopeId.isBlank()) {
            return errorResult("TODO_SCOPE_UNAVAILABLE", "当前运行没有会话作用域，任务清单不可用")
        }
        val todos = args.optJSONArray("todos")
            ?: return errorResult("INVALID_ARGUMENT", "todos 必须是数组；清空清单请传空数组")
        val plan = try {
            AgentTodoPlan.parse(todos)
        } catch (error: IllegalArgumentException) {
            return errorResult("INVALID_ARGUMENT", error.message ?: "任务清单格式不正确")
        }
        if (!todoStore.save(todoScopeId, plan)) {
            return errorResult("TODO_PERSIST_FAILED", "任务清单写入失败，本次未生效")
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "todo_write")
            .put("count", plan.items.size)
            .put("pending", plan.pendingCount)
            .put("in_progress", plan.inProgressCount)
            .put("completed", plan.completedCount)
            .put("cleared", plan.isEmpty)
            .put("todos", plan.toJson().getJSONArray("todos"))
            .put("checklist", plan.renderChecklist())
            .toString()
    }

    /**
     * 会话级任务台账的只读查询。
     *
     * 台账由 [io.github.mangi.eta.agent.subagent.SubagentExecutor] 在子代理生命周期里写，
     * 这里只负责展示。读失败（文件坏了）按"空台账"处理而不是报错：对模型来说
     * "看不到记录"和"没有记录"是同一个结论，而报错会让它以为工具坏了、进而放弃查证。
     */
    private fun tasks(args: JSONObject): String {
        if (todoScopeId.isBlank()) {
            return errorResult("TASK_SCOPE_UNAVAILABLE", "当前运行没有会话作用域，任务台账不可用")
        }
        val query = try {
            AgentTasksToolCatalog.parse(args)
        } catch (error: IllegalArgumentException) {
            return errorResult("INVALID_ARGUMENT", error.message ?: "tasks 参数不正确")
        }
        val ledger = taskStore.load(todoScopeId)
        val entries = ledger.withStatus(query.status).take(query.limit)
        return JSONObject()
            .put("ok", true)
            .put("tool", AgentTasksToolCatalog.NAME)
            .put("total", ledger.entries.size)
            .put("shown", entries.size)
            .put("summary", ledger.renderSummary())
            .put(
                "tasks",
                JSONArray().also { array ->
                    entries.forEach { entry ->
                        array.put(entry.toJson().put("line", entry.renderLine()))
                    }
                },
            )
            .toString()
    }

    /**
     * 长期目标：单目标 + revision 乐观并发。
     * 不自动续接轮次——移动端每一步都要花模型调用，是否继续由用户决定；
     * 这里只负责让目标跨轮「记得住、查得到、改得准」。
     */
    private fun goal(args: JSONObject): String {
        if (goalScopeId.isBlank()) {
            return errorResult("GOAL_SCOPE_UNAVAILABLE", "当前运行没有会话作用域，长期目标不可用")
        }
        val command = try {
            AgentGoalToolCatalog.parse(args)
        } catch (error: IllegalArgumentException) {
            return errorResult("INVALID_ARGUMENT", error.message ?: "goal 参数不正确")
        }
        return goalStore.withLock { goalLocked(command) }
    }

    /**
     * 持锁的读-校验-写：界面上的暂停/继续/编辑/清除走 [AgentGoalStore.mutate]，
     * 两边共用同一把锁，免得都从 revision N 出发、后写的那次悄悄盖掉先写的。
     */
    private fun goalLocked(command: AgentGoalToolCatalog.Command): String {
        val current = goalStore.load(goalScopeId)
        return when (command) {
            AgentGoalToolCatalog.Command.Get -> goalResult(current)
            is AgentGoalToolCatalog.Command.Create -> {
                if (current != null && current.phase != AgentGoalPhase.COMPLETED) {
                    errorResult(
                        "GOAL_EXISTS",
                        "已有未完成的目标（revision ${current.revision}）；请先 update 或 clear",
                    )
                } else {
                    val created = AgentGoal.create(command.objective)
                    if (!goalStore.save(goalScopeId, created)) {
                        errorResult("GOAL_PERSIST_FAILED", "目标写入失败，本次未生效")
                    } else {
                        goalResult(created)
                    }
                }
            }
            is AgentGoalToolCatalog.Command.Update -> {
                if (current == null) {
                    errorResult("GOAL_NOT_FOUND", "当前会话没有目标；先 action=create")
                } else if (current.revision != command.revision) {
                    errorResult(
                        "GOAL_STALE",
                        "目标已被更新（当前 revision ${current.revision}）；请重新 action=get",
                    )
                } else {
                    val updated = applyGoalUpdate(current, command)
                    if (!goalStore.save(goalScopeId, updated)) {
                        errorResult("GOAL_PERSIST_FAILED", "目标写入失败，本次未生效")
                    } else {
                        goalResult(updated)
                    }
                }
            }
            AgentGoalToolCatalog.Command.Clear -> {
                if (!goalStore.clear(goalScopeId)) {
                    errorResult("GOAL_PERSIST_FAILED", "目标清除失败，本次未生效")
                } else {
                    JSONObject().put("ok", true).put("tool", AgentGoalToolCatalog.NAME).put("cleared", true)
                        .toString()
                }
            }
        }
    }

    /**
     * 把一次成功的写入/编辑记进本次运行的账本。
     *
     * 只认工具结果里的 `path` / `lines_added` / `lines_removed` / `diff`：账本与工具结果必须同一来源，
     * 否则「卡片说改了、实际没改」这类不一致就无从发现。任何异常都不影响工具结果本身。
     */
    private fun recordFileChange(toolName: String, content: String): String {
        if (changeScopeId.isBlank()) return content
        val json = runCatching { JSONObject(content) }.getOrNull() ?: return content
        if (!json.optBoolean("ok", true)) return content
        if (toolName == "edit_files") {
            val files = json.optJSONArray("files") ?: return content
            for (index in 0 until files.length()) {
                val item = files.optJSONObject(index) ?: continue
                recordOneChange(toolName, item)
            }
            return content
        }
        recordOneChange(toolName, json)
        return content
    }

    private fun recordOneChange(toolName: String, json: JSONObject) {
        val path = json.optString("path").trim()
        if (path.isEmpty()) return
        runCatching {
            runFileChanges.record(
                runId = changeScopeId,
                path = path,
                tool = toolName,
                added = json.optInt("lines_added", 0),
                removed = json.optInt("lines_removed", 0),
                diff = json.optString("diff"),
            )
        }
    }

    private fun applyGoalUpdate(goal: AgentGoal, command: AgentGoalToolCatalog.Command.Update): AgentGoal {
        val phase = when (command.state) {
            AgentGoalToolCatalog.UpdateState.PAUSE -> AgentGoalPhase.PAUSED
            AgentGoalToolCatalog.UpdateState.RESUME -> AgentGoalPhase.ACTIVE
            AgentGoalToolCatalog.UpdateState.COMPLETE -> AgentGoalPhase.COMPLETED
            AgentGoalToolCatalog.UpdateState.BLOCKED -> AgentGoalPhase.BLOCKED
            AgentGoalToolCatalog.UpdateState.EDIT, null -> goal.phase
        }
        return goal.copy(
            revision = goal.revision + 1,
            objective = command.objective ?: goal.objective,
            phase = phase,
            blockedReason = if (phase == AgentGoalPhase.BLOCKED) {
                command.blockedReason ?: goal.blockedReason
            } else {
                null
            },
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun goalResult(goal: AgentGoal?): String = JSONObject()
        .put("ok", true)
        .put("tool", AgentGoalToolCatalog.NAME)
        .put("goal", goal?.toJson() ?: JSONObject.NULL)
        .put("summary", goal?.renderLine() ?: "当前没有目标")
        .toString()

    /**
     * 提问不是「发个消息」而是「到此为止」：结果里明确要求模型结束本轮，
     * 否则它可能问完继续按自己的猜测往下做，用户看到的答案就落在了已完成的动作之后。
     */
    private fun askUser(args: JSONObject): String {
        val ask = try {
            AgentAskUserToolCatalog.parse(args)
        } catch (error: IllegalArgumentException) {
            return errorResult("INVALID_ARGUMENT", error.message ?: "提问参数不正确")
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", AgentAskUserToolCatalog.NAME)
            .put("asked", true)
            .put("question", ask.question)
            .put("options", JSONArray(ask.options))
            .put("remaining", AgentAskUserToolCatalog.MAX_OPTIONS - ask.options.size)
            .put(
                "note",
                "问题已展示给用户。现在立即结束本轮并等待回答：用户的下一条消息会作为补充指令到达。" +
                    "不要自行假设答案，也不要继续执行依赖该答案的步骤。",
            )
            .toString()
    }

    private fun memoryGet(args: JSONObject): String = try {
        val result = AgentMemoryRepository.read(
            query = args.optString("query").takeIf(String::isNotBlank),
            startLine = args.optInt("start_line", 1),
            maxChars = args.optInt("max_chars", 12_000),
            section = args.optString("section").takeIf(String::isNotBlank),
        )
        JSONObject()
            .put("ok", true)
            .put("revision", result.snapshot.revision)
            .put("bytes", result.snapshot.byteSize)
            .put("line_count", result.snapshot.lineCount)
            .put("start_line", result.startLine ?: JSONObject.NULL)
            .put("end_line", result.endLine ?: JSONObject.NULL)
            .put("matched_lines", result.matchedLines)
            .put(
                "matched_headings",
                JSONArray().also { array -> result.matchedHeadings.forEach(array::put) },
            )
            .put("has_more", result.hasMore)
            .put("content", result.content)
            .toString()
    } catch (failure: AgentMemoryException) {
        errorResult(failure.code, failure.message ?: "记忆读取失败")
    }

    private fun memoryWrite(args: JSONObject): String = try {
        val revision = args.getString("revision")
        val mutation = when (args.getString("mode")) {
            "replace_section" -> AgentMemoryMutation.ReplaceSection(
                revision = revision,
                section = args.getString("section"),
                content = args.requiredText("content"),
            )
            "replace_range" -> AgentMemoryMutation.ReplaceRange(
                revision = revision,
                startLine = args.getInt("start_line"),
                endLine = args.getInt("end_line"),
                content = args.requiredText("content"),
            )
            "append" -> AgentMemoryMutation.Append(
                revision = revision,
                content = args.requiredText("content"),
            )
            "clear" -> AgentMemoryMutation.Clear(revision)
            else -> error("不支持的记忆写入模式")
        }
        when (val result = AgentMemoryRepository.mutate(mutation)) {
            is AgentMemoryWriteResult.Success -> JSONObject()
                .put("ok", true)
                .put("revision", result.snapshot.revision)
                .put("bytes", result.snapshot.byteSize)
                .put("line_count", result.snapshot.lineCount)
                .toString()
            is AgentMemoryWriteResult.Conflict -> JSONObject()
                .put("ok", false)
                .put("code", "MEMORY_CONFLICT")
                .put("message", "记忆已发生变化，请先调用 memory_get 获取最新内容")
                .put("revision", result.snapshot.revision)
                .put("bytes", result.snapshot.byteSize)
                .put("line_count", result.snapshot.lineCount)
                .toString()
        }
    } catch (failure: AgentMemoryException) {
        errorResult(failure.code, failure.message ?: "记忆写入失败")
    }

    private fun browserUse(args: JSONObject, toolCallId: String): AgentModelClient.ToolResult {
        if (!browserToolsEnabled()) {
            return textResult(errorResult("BROWSER_TOOLS_DISABLED", "请先启用网页浏览工具"))
        }
        val result = browserSessionRef.value.execute(
            context = context,
            args = args,
            runId = browserRunId,
            toolCallId = toolCallId,
            checkCancellation = { if (closed.get()) throw AgentRunCancelledException() },
        )
        return AgentModelClient.ToolResult(
            content = result.content,
            images = result.images.map { image ->
                AgentModelClient.ModelImage(
                    reference = image.dataUrl,
                    mimeType = image.mimeType,
                    bytes = image.bytes,
                    width = image.width,
                    height = image.height,
                    source = "agent_browser",
                    preserveOriginal = true,
                )
            },
        )
    }

    private fun observeScreen(args: JSONObject, generation: Long? = null): AgentModelClient.ToolResult {
        publishedObservation.set(PublishedObservation())
        val startedAt = SystemClock.elapsedRealtime()
        val options = AgentScreenObservationContract.resolve(args)
        // 观察允许在空屏或迁移后截图查看；动作才必须确认任务还在。
        val displayId = resolveDisplayId(args, requireTask = false)
        var observation = if (displayId == null) {
            screenObservationProvider?.invoke(options)
                ?: deviceController.observe(
                    includeScreenshot = options.includeScreenshot,
                    includeUiTree = options.includeUiTree,
                    maxNodes = options.maxNodes,
                    displayId = null,
                )
        } else {
            // 虚拟屏必须把 displayId 传到底；screenObservationProvider 只观察默认屏。
            deviceController.observe(
                includeScreenshot = options.includeScreenshot,
                includeUiTree = options.includeUiTree,
                maxNodes = options.maxNodes,
                displayId = displayId,
            )
        }
        // 虚拟屏截图可裁剪放大；有 UI 树时节点工具优先，截图用于核对坐标。
        if (displayId != null && options.includeScreenshot) {
            observation = cropObservation(observation, options.region)
        }
        publishedObservation.set(
            PublishedObservation(
                elements = observation.elementObservation,
                coordinateSpace = observation.coordinateSpace,
                displayId = displayId,
                // 只有真拿到节点才算「这一次观察」；失败的观察不能顶替旧代号。
                generation = generation.takeIf { observation.elementObservation != null },
            ),
        )
        logger.debug {
            "Agent local tool action=observe_screen outcome=completed display=${displayId ?: "default"} " +
                "observation=${observation.elementObservation?.id} " +
                "nodes=${observation.elementObservation?.nodes?.size ?: 0} " +
                "image=${observation.image?.bytes ?: 0} elapsed_ms=${SystemClock.elapsedRealtime() - startedAt} " +
                "coordinate=${observation.coordinateSpace?.summary()}"
        }
        return AgentModelClient.ToolResult(
            content = observation.content,
            images = listOfNotNull(observation.image)
        )
    }

    /**
     * 动作已经执行过之后，再确认它作用在哪块屏上（给 afterAction 用）。
     *
     * 解析会重新校验「屏还在、上面还有任务」，而动作本身可能刚刚把任务迁走或把屏用空了 ——
     * 那时不能报错（动作已经生效），退回快照屏即可。注意主屏解析成功时返回 null，
     * 所以不能用 `?:` 合并，必须靠异常区分。
     */
    private fun actionDisplayOrSnapshot(args: JSONObject): Int? =
        try {
            resolveDisplayId(args)
        } catch (_: InvalidToolArgumentException) {
            publishedObservation.get().displayId
        }

    /**
     * 解析本次动作要作用的显示。
     *
     * 显式传了 display_id 就用它（0 表示主屏）；没传时沿用**最近一次观察**所在的屏。
     * 这条规则是为了不让「在虚拟屏上操作」意外打到主屏：节点树可用后，
     * 漏传 display_id 仍会让部分工具回落到主屏焦点，用户会看到自己的屏幕被动。
     */
    private fun resolveDisplayId(args: JSONObject, requireTask: Boolean = true): Int? {
        val resolved = if (args.has("display_id")) {
            args.optInt("display_id", 0).takeIf { it != 0 }
        } else {
            // fix176：主屏归一成 null 的规则在 [ImplicitDisplayPolicy] 里（附真机取证）。
            // 少了这一步时，刚 observe 过主屏（displayId=0）之后的 type_text 会被当成
            // 「指定了屏」→ 交给虚拟屏通路 textOnDisplay(0)；那条通路用自己的验证口径，
            // 写入明明成功（文本已经落到输入框）却报 ok=false / ACTION_OUTCOME_UNKNOWN。
            // 真机 TEXT3c 就是这么红了两轮：报告 text=中文种子后缀，但 write=false。
            ImplicitDisplayPolicy.inherit(publishedObservation.get().displayId)?.let { inherited ->
                inherited
            } ?: activeDisplayId.takeIf { it != Display.DEFAULT_DISPLAY }
        }
        // 画布屏（上游 3.4.0）不在 virtual_display_* 注册表里：它由另一套控制器持有，
        // 但它确实存在且属于本进程。不先认这一种，launch_app(display=virtual) 之后的
        // 第一次 tap 会被下面的「注册表查不到」当成已释放，直接报错（合并后实测）。
        if (resolved != null && resolved == VirtualDisplayController.currentDisplayId()) {
            if (requireTask) ensureDisplayHasTask(resolved)
            return resolved
        }
        // 虚拟屏可能已经被释放或空闲回收。此时仍把动作注入那个 display，
        // `input -d` 会回退到默认屏 —— 用户会看到自己的主屏被点。宁可报错，也不能动主屏。
        if (resolved != null && virtualDisplays.findByDisplayId(resolved) == null) {
            // 注册表里查不到，不等于屏没了：虚拟屏是「进程内」的，别的进程建的屏本进程同样查不到。
            // 用「屏还在 + 它的 owner 不是本包」判定跨进程 —— 真机实测（Android 16 / SM8750）：
            //   · 别的进程建的屏：`dumpsys display` 里仍有 `Display 22:` 段，mBaseDisplayInfo 带
            //     `owner io.github.mangi.eta (uid 10629)`；
            //   · 本进程刚 release 的屏：2 秒后 `Display 24:` 段已经整段消失。
            // 不能拿 `dumpsys window displays` 的 `Window{` 行数当判据：那块带应用的跨进程屏
            // 实测是 0 行（该段 mCurrentFocus=null，窗口列表不在这里），会把跨进程误判成已释放。
            val (exists, owner) = probeDisplay(resolved)
            if (exists && owner != null && owner != context.packageName) {
                throw InvalidToolArgumentException(
                    message = "虚拟屏 $resolved 存在，但它不属于当前应用（创建者 $owner）；" +
                        "跨进程的屏在这里无法操作（观察与注入都会落到别处）。请在本进程内 " +
                        "virtual_display_create 再 virtual_display_launch 目标应用；不要原样重试这次调用",
                    code = DisplayScopeErrors.FOREIGN,
                    retryable = false,
                )
            }
            throw InvalidToolArgumentException(
                message = "虚拟屏 $resolved 已释放或不存在；本次动作未执行。" +
                    "请先 virtual_display_list 或 observe_screen 确认目标屏（需要时重新建屏 / 把应用拉回这块屏）；" +
                    "不要原样重试这次调用",
                code = DisplayScopeErrors.RELEASED,
                retryable = false,
            )
        }
        // 屏还在、但应用可能已经迁回主屏（canHostTasks=false 的系统行为）：
        // 此时对这块屏注入同样会落到别处，先确认它上面确有任务。
        if (resolved != null && requireTask) ensureDisplayHasTask(resolved)
        return resolved
    }

    /**
     * 这块 display 还在不在、创建者是谁。
     *
     * 返回 exists=false 表示 `dumpsys display` 里已经没有这个 Display 段（已释放）；
     * exists=true 但 owner=null 表示段在、但没解析出 owner（格式变了），调用方按 RELEASED 处理。
     *
     * 只在「注册表查不到」的错误路径上跑一次 dumpsys，正常路径零开销。
     * 为什么不用 `Window{` 行数：真机实测一块带应用的跨进程屏是 0 行窗口
     * （该 display 段 `mCurrentFocus=null`），会把跨进程错判成已释放。
     */
    private fun probeDisplay(displayId: Int): Pair<Boolean, String?> = runCatching {
        val process = ProcessBuilder(
            "su", "-c",
            "dumpsys display | sed -n '/^  Display $displayId:/,/^  Display [0-9]*:/p'",
        ).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        if (text.isBlank()) {
            false to null
        } else {
            true to Regex("owner (\\S+) \\(").find(text)?.groupValues?.get(1)
        }
    }.getOrDefault(false to null)

    private fun ensureDisplayHasTask(displayId: Int) {
        // 注意不能用 `dumpsys activity activities | grep displayId=N`：那个字符串也会出现在
        // DisplayContent 段（实测空虚拟屏也能匹配到 1 行），校验会误通过。
        // 用窗口数判断：空虚拟屏窗口数为 0，有应用时才大于 0。
        val count = runCatching {
            val process = ProcessBuilder(
                "su", "-c",
                "dumpsys window displays | sed -n '/Display: mDisplayId=$displayId/,/Display: mDisplayId=/p' | grep -c 'Window{'",
            ).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }.trim().toIntOrNull()
        }.getOrNull() ?: return
        if (count == 0) {
            throw InvalidToolArgumentException(
                message = "虚拟屏 $displayId 上没有可见窗口（应用可能已迁回主屏或退到后台）；本次动作未执行。" +
                    "请先 virtual_display_launch 把应用带回这块屏，或用 observe_screen 确认它还在；" +
                    "不要原样重试这次调用",
                code = DisplayScopeErrors.NO_VISIBLE_WINDOW,
                retryable = false,
            )
        }
    }

    /**
     * 只对虚拟屏截图做区域裁剪：模型看不到 UI 树时，小控件在整屏截图里只有几十像素，
     * 靠猜坐标很容易点偏；裁剪后放大 2 倍能看清边缘与文字。
     * 注意：坐标仍按整屏 normalized 换算，不是裁剪图的坐标。
     */
    private fun cropObservation(
        observation: RootShellDeviceController.Observation,
        region: AgentScreenObservationContract.Region?,
    ): RootShellDeviceController.Observation {
        val image = observation.image ?: return observation
        if (region == null) return observation
        val cropped = runCatching { cropImage(image, region) }.getOrNull() ?: return observation
        val content = runCatching { JSONObject(observation.content) }.getOrNull() ?: return observation
        content.put(
            "region",
            JSONObject()
                .put("x", region.x)
                .put("y", region.y)
                .put("width", region.width)
                .put("height", region.height),
        )
        // 裁剪图可能被放大过（<540x960 时 x2），而 region 只给 normalized 值，
        // 模型很容易把裁剪图像素当成整屏像素用。这里给出完整映射：
        // 整屏像素 = source_left + 裁剪图像素 / scale（scale = image_width / source_width）。
        content.put(
            "crop_map",
            JSONObject()
                .put("source_left", cropped.sourceLeft)
                .put("source_top", cropped.sourceTop)
                .put("source_width", cropped.sourceWidth)
                .put("source_height", cropped.sourceHeight)
                .put("image_width", cropped.imageWidth)
                .put("image_height", cropped.imageHeight)
                .put("scale", cropped.imageWidth.toDouble() / cropped.sourceWidth.coerceAtLeast(1)),
        )
        content.put(
            "note",
            "截图已裁剪放大；坐标请用 crop_map 换算：整屏像素 = source_left + 图像像素 / scale，" +
                "再按 screen 宽高换成 normalized（0–999）。region 仍以整屏 normalized 表示。",
        )
        // 附图已从「整屏」变成「裁剪放大」：原来那份 CoordinateSpace（整屏宽高 + 整屏截图宽高）
        // 对这张图不再成立 —— fromScreenshot 只按宽高比缩放，不知道裁剪偏移。
        // 继续挂着它，coordinate_contract 会报出一个与附图不符的截图尺寸，
        // 模型照着换算就是静默点偏（fix162）。所以这里把坐标系置空，并在响应里说清楚原因。
        content.put(
            "coordinate_contract",
            JSONObject()
                .put("recommended_coordinate_space", "normalized")
                .put("screen", content.optJSONObject("coordinate_contract")?.optJSONObject("screen") ?: JSONObject())
                .put(
                    "note",
                    "本次附图是裁剪放大图（见 crop_map）：没有整屏截图坐标系，coordinate_space=screenshot 已不可用；" +
                        "坐标请按 crop_map 换算成整屏像素，再按 screen 宽高换成 normalized（0–999）",
                ),
        )
        return observation.copy(
            content = content.toString(),
            image = cropped.image,
            coordinateSpace = null,
        )
    }

    /** 裁剪结果连同它在原图中的位置，供模型把裁剪图坐标换算回整屏。 */
    private data class CroppedImage(
        val image: AgentModelClient.ModelImage,
        val sourceLeft: Int,
        val sourceTop: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val imageWidth: Int,
        val imageHeight: Int,
    )

    private fun cropImage(
        image: AgentModelClient.ModelImage,
        region: AgentScreenObservationContract.Region,
    ): CroppedImage? {
        val base64 = image.reference.substringAfter("base64,", "")
        if (base64.isEmpty()) return null
        val bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val left = (region.x / 1000.0 * width).toInt().coerceIn(0, width - 1)
        val top = (region.y / 1000.0 * height).toInt().coerceIn(0, height - 1)
        val right = ((region.x + region.width) / 1000.0 * width).toInt().coerceIn(left + 1, width)
        val bottom = ((region.y + region.height) / 1000.0 * height).toInt().coerceIn(top + 1, height)
        val cropped = android.graphics.Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        val scaled = if (cropped.width < 540 && cropped.height < 960) {
            android.graphics.Bitmap.createScaledBitmap(cropped, cropped.width * 2, cropped.height * 2, true)
        } else {
            cropped
        }
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        val png = out.toByteArray()
        val outputWidth = scaled.width
        val outputHeight = scaled.height
        if (!scaled.isRecycled) scaled.recycle()
        if (scaled !== cropped && !cropped.isRecycled) cropped.recycle()
        if (!bitmap.isRecycled) bitmap.recycle()
        val encoded = io.github.mangi.eta.agent.media.AgentImageCodec
            .fromScreenBytes(png, source = "virtual")
        return CroppedImage(
            image = encoded,
            sourceLeft = left,
            sourceTop = top,
            sourceWidth = right - left,
            sourceHeight = bottom - top,
            imageWidth = outputWidth,
            imageHeight = outputHeight,
        )
    }

    /**
     * 成功的 GUI 动作附带一次轻量观察：只读 UI 树、不截图，节点数减半。
     * 模型据此确认动作生效并直接用新的 observation_id 继续操作，不必再单独 observe_screen；
     * 失败或结果未知的动作不附带，保持"先重新观察"的既有约束。
     */
    /**
     * 动作后观察 + 差异摘要。
     *
     * @param displayId 本次动作**实际作用的屏**（主屏为 null）；省略时沿用快照屏。
     *   坐标动作允许跨屏（先看主屏、再点虚拟屏），拿「快照屏」去观察会把反馈换到
     *   另一块屏上（fix165 真机取证：动作写主屏、after 却是虚拟屏的账号页）。
     */
    private fun afterAction(
        raw: String,
        displayId: Int? = publishedObservation.get().displayId,
    ): AgentModelClient.ToolResult {
        val result = runCatching { JSONObject(raw) }.getOrNull()
        if (result == null || !result.optBoolean("ok")) return textResult(raw)
        val snapshot = publishedObservation.get()
        val plan = AfterActionScopePolicy.plan(displayId, snapshot.displayId)
        // 跨屏时没有可比性：旧快照属于另一块屏，拿来 diff 会把「换了一块屏」
        // 渲染成「屏上所有东西都变了」。
        val before = if (plan.compareWithPrevious) snapshot.elements else null
        val after = runCatching {
            if (plan.observeDisplayId == null && plan.compareWithPrevious) {
                screenObservationProvider?.invoke(AFTER_ACTION_OPTIONS)
                    ?: deviceController.observe(
                        includeScreenshot = false,
                        includeUiTree = true,
                        maxNodes = AFTER_ACTION_OPTIONS.maxNodes,
                        displayId = null,
                    )
            } else {
                // 显式指定屏（含跨屏回主屏）：不能走只观察默认屏的 provider。
                deviceController.observe(
                    includeScreenshot = false,
                    includeUiTree = true,
                    maxNodes = AFTER_ACTION_OPTIONS.maxNodes,
                    displayId = plan.observeDisplayId,
                )
            }
        }.getOrElse { throwable ->
            logger.debug { "Agent local tool after-action observation failed: type=${throwable.javaClass.simpleName}" }
            return textResult(raw)
        }
        val elements = after.elementObservation ?: return textResult(raw)
        // 新快照取代动作前的快照：旧 index 在界面变化后本就不可靠，继续保留只会让模型误用。
        // 跨屏时必须连 displayId 一起换掉，否则快照会「属于 A 屏、节点来自 B 屏」——
        // 那样的快照会让 fix163 的同屏校验误通过。
        publishedObservation.set(
            PublishedObservation(
                elements = elements,
                coordinateSpace = if (plan.keepScreenshotSpace) snapshot.coordinateSpace else null,
                displayId = plan.snapshotDisplayId,
                // 动作后的刷新仍在同一个 GUI 代号里，否则下一步动作会误判「操作权已变化」。
                generation = snapshot.generation,
            ),
        )
        result.put(
            "after",
            AgentAfterActionSummary.build(
                before = before,
                after = elements,
                displayId = plan.observeDisplayId,
                crossDisplay = !plan.compareWithPrevious,
            ),
        )
        return textResult(result.toString())
    }

    private fun tap(args: JSONObject, displayId: Int?): String {
        // 屏先由调用方定好（它还要把同一个值传给 afterAction），再按那块屏的尺寸校验坐标：
        // 顺序反了就会拿主屏尺寸校验虚拟屏坐标（fix161）。
        val point = convertPoint(
            x = args.getInt("x"),
            y = args.getInt("y"),
            coordinateSpace = args.optString("coordinate_space"),
            displayId = displayId,
        )
        // 虚拟屏上不弹悬屏指示（那会打扰用户），也不做触感反馈。
        if (displayId != null) return deviceController.tap(point.x, point.y, displayId)
        return clearOverlay(Display.DEFAULT_DISPLAY, point) {
            AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.TAP)
            showTap(point.x, point.y)
            deviceController.tap(point.x, point.y)
        }
    }

    private fun tapArea(args: JSONObject, displayId: Int?): String {
        val x1 = args.getInt("x1")
        val y1 = args.getInt("y1")
        val x2 = args.getInt("x2")
        val y2 = args.getInt("y2")
        val coordinateSpace = args.optString("coordinate_space")
        val first = convertPoint(x1, y1, coordinateSpace, displayId)
        val second = convertPoint(x2, y2, coordinateSpace, displayId)
        val point = ScreenPoint(
            x = ((first.x.toLong() + second.x.toLong()) / 2L).toInt(),
            y = ((first.y.toLong() + second.y.toLong()) / 2L).toInt(),
        )
        if (displayId != null) return deviceController.tap(point.x, point.y, displayId)
        return clearOverlay(Display.DEFAULT_DISPLAY, point) {
            AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.TAP)
            showTap(point.x, point.y)
            deviceController.tap(point.x, point.y)
        }
    }

    /** 画布当前生效的屏：只有画布真的还活着且就是这块屏才沿用，否则回落主屏。 */
    private fun currentActiveDisplayId(): Int {
        val id = activeDisplayId
        if (!CanvasRoutingPolicy.isVirtualDisplay(id)) return Display.DEFAULT_DISPLAY
        val vd = VirtualDisplayController.snapshots.value
        return if (vd.active && vd.displayId == id) {
            id
        } else {
            activeDisplayId = Display.DEFAULT_DISPLAY
            Display.DEFAULT_DISPLAY
        }
    }

    private fun tapElement(args: JSONObject): String {
        val index = args.optInt("index", -1)
        val observation = requireElementObservation(args) ?: return observationError(args)
        val node = observation.nodes.firstOrNull { it.index == index }
        if (node == null) {
            return errorResult("INVALID_NODE_INDEX", "观察快照中不存在节点 index=$index")
        }
        val displayId = resolveDisplayId(args)
        requireSameObservationScope(displayId, "tap_element")
        val result = performElementTap(observation, node, displayId)
        if (result.isOkJson()) return result
        return retryStaleNodeAction(result, observation, node) { fresh, target ->
            performElementTap(fresh, target, displayId)
        }
    }

    /**
     * 节点点击只在这里落地：虚拟屏不画触点、不做触感反馈，
     * 以免把主屏的用户界面弄脏。
     */
    private fun performElementTap(
        observation: RootShellDeviceController.ElementObservation,
        node: RootShellDeviceController.UiNode,
        displayId: Int?,
    ): String {
        val result = if (displayId == null) {
            clearOverlay(Display.DEFAULT_DISPLAY, ScreenPoint(node.centerX, node.centerY)) {
                deviceController.tapElement(observation, node.index)
            }
        } else {
            // 虚拟屏：动作不打扰主屏，点击指示也不能画到用户屏幕上。
            deviceController.tapElement(observation, node.index)
        }
        if (result.isOkJson() && displayId == null) {
            AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.TAP)
            showTap(node.centerX, node.centerY)
        }
        return result
    }

    private fun longPressElement(args: JSONObject): String {
        val index = args.optInt("index", -1)
        val observation = requireElementObservation(args) ?: return observationError(args)
        val node = observation.nodes.firstOrNull { it.index == index }
        val durationMs = args.optInt("duration_ms", 800)
        if (node == null) {
            return errorResult("INVALID_NODE_INDEX", "观察快照中不存在节点 index=$index")
        }
        val displayId = resolveDisplayId(args)
        requireSameObservationScope(displayId, "long_press_element")
        val result = performElementLongPress(observation, node, displayId, durationMs)
        if (result.isOkJson()) return result
        return retryStaleNodeAction(result, observation, node) { fresh, target ->
            performElementLongPress(fresh, target, displayId, durationMs)
        }
    }

    private fun performElementLongPress(
        observation: RootShellDeviceController.ElementObservation,
        node: RootShellDeviceController.UiNode,
        displayId: Int?,
        durationMs: Int,
    ): String {
        val result = if (displayId == null) {
            clearOverlay(Display.DEFAULT_DISPLAY, ScreenPoint(node.centerX, node.centerY)) {
                deviceController.longPressElement(observation, node.index, durationMs)
            }
        } else {
            // 同 tapElement：虚拟屏动作不能触发主屏浮层或触感反馈。
            deviceController.longPressElement(observation, node.index, durationMs)
        }
        if (result.isOkJson() && displayId == null) {
            AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.LONG_PRESS)
            showLongPress(node.centerX, node.centerY, durationMs)
        }
        return result
    }

    private fun longPress(args: JSONObject, displayId: Int?): String {
        val point = convertPoint(
            x = args.getInt("x"),
            y = args.getInt("y"),
            coordinateSpace = args.optString("coordinate_space"),
            displayId = displayId,
        )
        val durationMs = args.optInt("duration_ms", 800)
        if (displayId != null) return deviceController.longPress(point.x, point.y, durationMs, displayId)
        return clearOverlay(Display.DEFAULT_DISPLAY, point) {
            AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.LONG_PRESS)
            showLongPress(point.x, point.y, durationMs)
            deviceController.longPress(point.x, point.y, durationMs)
        }
    }

    private fun swipe(args: JSONObject, displayId: Int?): String {
        val start = convertPoint(
            x = args.getInt("x1"),
            y = args.getInt("y1"),
            coordinateSpace = args.optString("coordinate_space"),
            displayId = displayId,
        )
        val end = convertPoint(
            x = args.getInt("x2"),
            y = args.getInt("y2"),
            coordinateSpace = args.optString("coordinate_space"),
            displayId = displayId,
        )
        val durationMs = args.optInt("duration_ms", 500)
        if (displayId != null) {
            return deviceController.swipe(start.x, start.y, end.x, end.y, durationMs, displayId)
        }
        return clearOverlay(Display.DEFAULT_DISPLAY, start, end) {
            AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.SWIPE)
            showSwipe(start.x, start.y, end.x, end.y, durationMs)
            deviceController.swipe(
                start.x,
                start.y,
                end.x,
                end.y,
                durationMs,
                displayId,
            )
        }
    }

    private fun scrollElement(args: JSONObject): String {
        val index = args.optInt("index", -1)
        val observation = requireElementObservation(args) ?: return observationError(args)
        val direction = args.optString("direction")
        val amount = args.optString("amount")
        // fix167：和 tap_element / long_press_element 对齐。
        // 以前这里既不解析 display_id、也不查那块屏还在不在：
        // 模型传 display_id=0（以为在主屏滚动）时参数被默默丢掉，动作仍在快照所属的虚拟屏上执行，
        // 结果照样 ok —— 模型看不出自己搞错了屏（与 fix163 修的是同一个病，只是漏了这个工具）。
        val displayId = resolveDisplayId(args)
        requireSameObservationScope(displayId, "scroll_element")
        val result = deviceController.scrollElement(
            observation = observation,
            index = index,
            direction = direction,
            amount = amount,
        )
        val node = observation.nodes.firstOrNull { it.index == index } ?: return result
        return retryStaleNodeAction(result, observation, node) { fresh, target ->
            deviceController.scrollElement(
                observation = fresh,
                index = target.index,
                direction = direction,
                amount = amount,
            )
        }
    }

    /**
     * 统一的文本输入：replace（默认）整体替换为 text，text 为空即清空；append 在光标处插入。
     * 指定 index 时先确认节点来自最近一次观察；不指定时作用于当前输入焦点。
     * submit=true 在写入成功后按输入法回车，用于搜索、发送等提交动作。
     */
    private fun typeText(args: JSONObject): String {
        val text = args.requiredText("text")
        val mode = args.optString("mode", "replace").trim().lowercase(Locale.ROOT).ifBlank { "replace" }
        // 虚拟屏仍统一走 input -d：节点路径/主屏焦点不适合跨屏输入，
        // 漏传 display_id 时还可能把文本打到主屏焦点上。
        val displayIdArg = resolveDisplayId(args)
        if (displayIdArg != null) {
            rejectIndexOnVirtualDisplay(args, displayIdArg, "type_text")
            if (mode != "replace" && mode != "append") {
                return errorResult("INVALID_ARGUMENT", "mode 只能是 replace 或 append")
            }
            if (mode == "append" && text.isEmpty()) {
                return errorResult("INVALID_ARGUMENT", "append 模式的 text 不能为空")
            }
            return deviceController.textOnDisplay(
                context = requireContext(),
                displayId = displayIdArg,
                text = text,
                clearFirst = mode == "replace",
                submit = args.optBoolean("submit"),
            )
        }
        val written = when (mode) {
            "replace" -> replaceText(args)
            "append" -> {
                if (text.isEmpty()) return errorResult("INVALID_ARGUMENT", "append 模式的 text 不能为空")
                if (args.optNullableInt("index") != null) {
                    return errorResult("INVALID_ARGUMENT", "append 只作用于当前输入焦点；要写入指定输入框请用 mode=replace")
                }
                // 长文本走选区插入加粘贴回退，比逐字键入更稳定。
                if (text.length > TYPE_TEXT_INCREMENTAL_CHARS) pasteText(args) else deviceController.inputText(text)
            }
            else -> return errorResult("INVALID_ARGUMENT", "mode 只能是 replace 或 append")
        }
        val json = runCatching { JSONObject(written) }.getOrNull() ?: return written
        json.put("tool", "type_text").put("mode", mode)
        if (!json.optBoolean("ok") || !args.optBoolean("submit")) return json.toString()
        val submitted = runCatching {
            JSONObject(deviceController.pressKey("ENTER", resolveDisplayId(args)))
        }.getOrNull()
        json.put("submitted", submitted?.optBoolean("ok") == true)
        if (submitted?.optBoolean("ok") != true) {
            // 文本已写入但提交失败：整体仍算成功，避免模型重复输入；由 submitted=false 提示单独补按回车或点按钮。
            json.put("submit_error", submitted?.optString("code").orEmpty().ifBlank { "SUBMIT_FAILED" })
        }
        return json.toString()
    }

    private fun inputText(args: JSONObject): String {
        val text = args.requiredText("text")
        if (text.length > 1_000) {
            return errorResult("TEXT_TOO_LONG", "input_text 最多支持 1000 个字符")
        }
        val displayId = resolveDisplayId(args)
        if (displayId != null) {
            return deviceController.textOnDisplay(
                context = requireContext(),
                displayId = displayId,
                text = text,
                clearFirst = args.optString("mode", "append").lowercase(Locale.ROOT) == "replace",
                submit = false,
            )
        }
        return when (args.optString("mode", "append").lowercase(Locale.ROOT)) {
            "replace" -> replaceText(args)
            "paste" -> pasteText(args)
            else -> deviceController.inputText(text, currentActiveDisplayId())
        }
    }

    private fun replaceText(args: JSONObject): String {
        // 带 display_id 时不能走无障碍（那只覆盖默认屏），改走 input -d。
        val displayId = resolveDisplayId(args)
        if (displayId != null) {
            return deviceController.textOnDisplay(
                context = requireContext(),
                displayId = displayId,
                text = args.requiredText("text"),
                clearFirst = true,
                submit = false,
            )
        }
        val index = args.optNullableInt("index")
        val text = args.requiredText("text")
        if (index == null) {
            return deviceController.replaceText(text = text, index = null, observation = null)
        }
        val observation = requireElementObservation(args) ?: return observationError(args)
        requireSameObservationScope(null, "type_text")
        val node = observation.nodes.firstOrNull { it.index == index }
            ?: return errorResult("INVALID_NODE_INDEX", "观察快照中不存在节点 index=$index")
        // fix171：节点动作早就会「过期就重观察再试」，文本写入一直不会 ——
        // 真机探针（TEXT1）每次拿到 STALE_CONTENT，模型只能重看一次屏幕、重找一个 index。
        // replace 本身幂等，重试不会重复输入。
        val result = deviceController.replaceText(text = text, index = index, observation = observation)
        if (result.isOkJson()) return result
        return retryStaleNodeAction(result, observation, node) { fresh, target ->
            deviceController.replaceText(text = text, index = target.index, observation = fresh)
        }

    }

    private fun clearText(args: JSONObject): String {
        val displayId = resolveDisplayId(args)
        if (displayId != null) {
            rejectIndexOnVirtualDisplay(args, displayId, "clear_text")
            return deviceController.textOnDisplay(
                context = requireContext(),
                displayId = displayId,
                text = "",
                clearFirst = true,
                submit = false,
            )
        }
        val index = args.optNullableInt("index")
        if (index == null) {
            return deviceController.clearText(index = null, observation = null)
        }
        val observation = requireElementObservation(args) ?: return observationError(args)
        requireSameObservationScope(null, "clear_text")
        val node = observation.nodes.firstOrNull { it.index == index }
            ?: return errorResult("INVALID_NODE_INDEX", "观察快照中不存在节点 index=$index")
        // fix171：同 replaceText —— 清空幂等，快照过期就该自动重观察再试一次。
        val result = deviceController.clearText(index = index, observation = observation)
        if (result.isOkJson()) return result
        return retryStaleNodeAction(result, observation, node) { fresh, target ->
            deviceController.clearText(index = target.index, observation = fresh)
        }

    }

    private fun setClipboard(args: JSONObject): String =
        deviceController.clipboardSet(requireContext(), args.requiredText("text"))

    private fun getClipboard(): String =
        deviceController.clipboardGet(requireContext())

    private fun pasteText(args: JSONObject): String {
        val displayId = resolveDisplayId(args)
        if (displayId != null) {
            // 粘贴本身就是剪贴板通路：非 ASCII 也能过去。
            return deviceController.textOnDisplay(
                context = requireContext(),
                displayId = displayId,
                text = args.requiredText("text"),
                clearFirst = false,
                submit = false,
            )
        }
        return deviceController.pasteText(args.requiredText("text"))
    }

    private fun waitForText(args: JSONObject): String {
        // 等待的是"即将出现"的内容：虚拟屏此刻可能还没有窗口，不能要求已有任务。
        val displayId = resolveDisplayId(args, requireTask = false)
        return deviceController.waitForText(
            text = args.requiredText("text"),
            timeoutMs = args.optInt("timeout_ms", 10_000),
            includeDesc = args.optBoolean("include_desc", true),
            matchMode = args.optString("match", "contains"),
            displayId = displayId,
        )
    }

    private fun waitForPackage(args: JSONObject): String {
        // 同上：等待应用出现在虚拟屏时，屏上可能还没有任务。
        val displayId = resolveDisplayId(args, requireTask = false)
        return deviceController.waitForPackage(
            packageName = args.optString("package_name"),
            timeoutMs = args.optInt("timeout_ms", 10_000),
            displayId = displayId,
        )
    }

    /**
     * 「这次点哪块屏」的人话描述：错误消息里写 `display 116` 比写 `null` 有用得多。
     */
    private fun describeDisplayForAgent(displayId: Int?): String =
        if (displayId == null || displayId == 0) "主屏" else "display $displayId"

    /**
     * 节点动作必须落在「快照所属那块屏」（fix163）。
     *
     * 快照来自另一块屏时，index 的含义完全不同：按快照自己的窗口执行，
     * 得到的是「说点了主屏、实际点了虚拟屏」的假成功（真机 STEP13 返回 ok=true，
     * after.package 却是虚拟屏上的 com.android.settings）；反方向则会真的动用户的主屏。
     */
    private fun requireSameObservationScope(requestedDisplayId: Int?, toolName: String) {
        val observed = publishedObservation.get().displayId
        if (ObservationScopePolicy.check(observed, requestedDisplayId) ==
            ObservationScopePolicy.Scope.MATCH
        ) {
            return
        }
        throw InvalidToolArgumentException(
            message = "$toolName 的节点快照来自${describeDisplayForAgent(observed)}，" +
                "不能用来操作${describeDisplayForAgent(requestedDisplayId)}：" +
                "节点 index 只在它被观察的那块屏上有意义。请先对目标屏 observe_screen 一次，" +
                "或用坐标工具（tap / tap_area）",
            code = ObservationScopeErrors.OTHER_DISPLAY,
            retryable = false,
        )
    }

    /**
     * 虚拟屏写入不接受节点 index（fix164）。
     *
     * 这条通路是进程内注入，只写该屏当前焦点输入框；以前 index 被默默丢掉，
     * 文本会落到另一个框里而结果仍报 ok —— 模型以为写到了 index 那个框。
     */
    private fun rejectIndexOnVirtualDisplay(args: JSONObject, displayId: Int, toolName: String) {
        val hasIndex = args.optNullableInt("index") != null
        if (DisplayTextTargetPolicy.resolve(hasIndex, displayId) !=
            DisplayTextTargetPolicy.Target.REJECTED_INDEX
        ) {
            return
        }
        throw InvalidToolArgumentException(
            message = "$toolName 不能同时带 index 与 display_id=$displayId：" +
                "虚拟屏写入是进程内注入，只作用于该屏**当前焦点输入框**，index 会被忽略" +
                "（以前还会静默写进别的输入框）。要写指定输入框，先 tap_element 把它点成焦点再不带 index 调用；" +
                "要写主屏的那个 index，请不要传 display_id",
            code = DisplayTextErrors.INDEX_ON_VIRTUAL_DISPLAY,
            retryable = false,
        )
    }

    /**
     * 坐标系必须显式声明。旧版按"上一次观察是否带截图"隐式切换默认值，模型无从得知，
     * 经常把截图像素当成屏幕像素；缺省时直接报参数错误，让模型在下一步改正。
     */
    private fun convertPoint(
        x: Int,
        y: Int,
        coordinateSpace: String,
        displayId: Int?,
    ): ScreenPoint {
        val published = publishedObservation.get()
        val space = published.coordinateSpace
        // 尺寸口径必须跟着「这次点哪块屏」：最近一次观察可能根本不在这块屏上。
        // 真机缺陷：先看主屏、再点虚拟屏，会拿 1216x2640 去校验 720x1280 的坐标（fix161）。
        val dimensions = CoordinateDimensionPolicy.pick(
            requested = deviceController.displayDimensions(displayId),
            published = space?.let { it.screenWidth to it.screenHeight },
            publishedDisplayId = published.displayId,
            requestedDisplayId = displayId,
        )
        val requestedSpace = coordinateSpace.trim().lowercase(Locale.ROOT)
        if (requestedSpace.isBlank()) {
            throw InvalidToolArgumentException(
                "缺少 coordinate_space：看截图定位用 normalized（0–999），坐标来自 ui_nodes 用 screen",
            )
        }
        if (requestedSpace == "normalized") {
            if (x !in 0..NORMALIZED_MAX || y !in 0..NORMALIZED_MAX) {
                throw InvalidToolArgumentException("normalized 坐标必须在 0–$NORMALIZED_MAX 之间：($x,$y)")
            }
            val (width, height) = dimensions ?: deviceController.screenDimensions()
            return ScreenPoint(
                x = (x.toLong() * (width - 1) / NORMALIZED_MAX).toInt(),
                y = (y.toLong() * (height - 1) / NORMALIZED_MAX).toInt(),
            )
        }
        if (requestedSpace == "screen") {
            val (width, height) = dimensions ?: deviceController.screenDimensions()
            if (x !in 0 until width || y !in 0 until height) {
                throw InvalidToolArgumentException(
                    "屏幕坐标超出范围：($x,$y) not in ${width}x$height",
                )
            }
            return ScreenPoint(x, y)
        }
        if (requestedSpace != "screenshot") {
            throw InvalidToolArgumentException("coordinate_space 只能是 normalized、screen 或 screenshot")
        }
        // 截图坐标系同样得是「这块屏的整屏截图」：另一块屏的截图只按宽高比换算，
        // 会算出越界点（被控制器报成 TOOL_ERROR，看着像内部错误）或屏内的错位置（静默点偏）。
        when (
            CoordinateDimensionPolicy.screenshotUse(
                hasSpace = space != null,
                publishedDisplayId = published.displayId,
                requestedDisplayId = displayId,
            )
        ) {
            CoordinateDimensionPolicy.ScreenshotSpaceUse.NO_SCREENSHOT -> throw InvalidToolArgumentException(
                message = "最近一次观察没有可用的截图坐标系（未附图，或附图已按 region 裁剪放大）；" +
                    "看截图定位请用 normalized（0–999），或先带截图整屏重新观察一次",
                code = CoordinateSpaceErrors.SCREENSHOT_UNAVAILABLE,
                retryable = false,
            )

            CoordinateDimensionPolicy.ScreenshotSpaceUse.OTHER_DISPLAY -> throw InvalidToolArgumentException(
                message = "最近一次观察附的是${describeDisplayForAgent(published.displayId)}的截图，" +
                    "不能用来换算${describeDisplayForAgent(displayId)}的截图坐标：请先对这块屏带截图观察一次" +
                    "（observe_screen 传 display_id 与 include_screenshot），或用 normalized（0–999）",
                code = CoordinateSpaceErrors.SCREENSHOT_OTHER_DISPLAY,
                retryable = false,
            )

            CoordinateDimensionPolicy.ScreenshotSpaceUse.USABLE -> Unit
        }
        val screenshotSpace = space ?: throw InvalidToolArgumentException(
            message = "最近一次观察没有附图，没有 screenshot 坐标系；看截图定位请用 normalized，或先带截图重新观察",
            code = CoordinateSpaceErrors.SCREENSHOT_UNAVAILABLE,
            retryable = false,
        )
        val point = runCatching { screenshotSpace.fromScreenshot(x, y) }
            .getOrElse { throwable ->
                throw InvalidToolArgumentException(
                    throwable.message ?: "截图坐标超出范围",
                )
            }
        return ScreenPoint(point.x, point.y)
    }

    private fun searchApps(args: JSONObject): String {
        val query = args.optString("query").trim()
        if (query.isBlank()) {
            return errorResult("INVALID_ARGUMENT", "query 不能为空")
        }
        val includeSystem = args.optBoolean("include_system", false)
        val limit = args.optInt("limit", 10).coerceIn(1, 20)
        val apps = findAppsByName(query, includeSystem).take(limit)
        return JSONObject()
            .put("ok", true)
            .put("tool", "search_apps")
            .put("query", query)
            .put("apps", apps.toJsonArray())
            .toString()
    }

    private fun launchApp(args: JSONObject): String {
        val packageName = args.optString("package_name").trim().ifBlank { null }
        val appName = args.optString("app_name").trim().ifBlank { null }
        val requestedDisplay = CanvasRoutingPolicy.RequestedDisplay.parse(
            args.optString("display", "auto"),
        ) ?: return errorResult("INVALID_ARGUMENT", "display 仅支持 auto、virtual 或 main")

        val app = if (packageName != null) {
            findAppByPackage(packageName) ?: AppInfo(packageName = packageName, appName = appName ?: packageName)
        } else {
            if (appName == null) {
                return errorResult("INVALID_ARGUMENT", "package_name 和 app_name 至少提供一个")
            }
            val matches = findAppsByName(appName, includeSystem = false)
            val exactMatches = matches.filter { it.appName.equals(appName, ignoreCase = true) }
            when {
                exactMatches.size == 1 -> exactMatches.single()
                matches.size == 1 -> matches.single()
                matches.isEmpty() -> return errorResult(
                    code = "APP_NOT_FOUND",
                    message = "未找到应用：$appName"
                )
                else -> return JSONObject()
                    .put("ok", false)
                    .put("code", "AMBIGUOUS_APP")
                    .put("message", "匹配到多个应用，请指定 package_name")
                    .put("candidates", matches.take(10).toJsonArray())
                    .toString()
            }
        }

        val displayId = resolveDisplayId(args, requireTask = false)
        if (displayId != null) {
            val session = virtualDisplays.findByDisplayId(displayId)
            if (session == null && displayId != VirtualDisplayController.currentDisplayId()) {
                return errorResult(
                    "VIRTUAL_DISPLAY_NOT_FOUND",
                    "未找到虚拟屏 $displayId；请先 virtual_display_list",
                )
            }
            if (session != null) {
                // launch_app 在虚拟屏上必须走 am start --display；普通 startActivity 会落在主屏。
                val request = AgentVirtualDisplayTools.LaunchRequest(
                    id = session.id,
                    packageName = app.packageName,
                    activity = "",
                    action = "",
                    data = "",
                    flags = null,
                    extras = emptyMap(),
                )
                return launchOnVirtualDisplay(session, request)
                    .put("tool", "launch_app")
                    .put("app_name", app.appName)
                    .put("package_name", app.packageName)
                    .put("display", displayId)
                    .put("display_id", displayId)
                    .toString()
            }
            // 这块屏是画布（上游 3.4.0）建的：不归 virtual_display_* 管，交给画布自己启动。
        }
        val context = requireContext()
        val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent == null) {
            return errorResult(
                code = "APP_NOT_LAUNCHABLE",
                message = "应用不可启动或未安装：${app.packageName}"
            )
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        // 没指定屏时按画布策略分流：auto/virtual → 后台虚拟屏，main / 不具备后端 → 物理主屏。
        // 这条分支是上游 3.4.0 的「启动到后台，不打断用户主屏」；以前只解析 display 参数却没用，
        // 模型写 display=virtual 也被当成主屏，属于合并时丢掉的行为。
        val mainForegroundPkg =
            AgentAccessibilityService.current()?.currentPackageName(Display.DEFAULT_DISPLAY)
        val targetCanvas = CanvasRoutingPolicy.decideLaunchTarget(
            virtualDisplayEnabled = virtualDisplayEnabled(),
            backendAvailable = VirtualDisplayController.isBackendAvailable(rootAvailable(), lsposedAvailable()),
            requested = requestedDisplay,
            targetPackage = app.packageName,
            foregroundPackageOnMainDisplay = mainForegroundPkg,
            ownPackage = context.packageName,
        )
        val launchedVirtualId = if (targetCanvas == CanvasRoutingPolicy.CanvasTarget.VIRTUAL_DISPLAY) {
            VirtualDisplayController.launchAppOnVirtualDisplay(
                context = context,
                packageName = app.packageName,
                appName = app.appName,
                launchIntent = launchIntent,
                rootAvailable = rootAvailable,
            )
        } else {
            null
        }
        val finalDisplayId = if (launchedVirtualId != null && CanvasRoutingPolicy.isVirtualDisplay(launchedVirtualId)) {
            activeDisplayId = launchedVirtualId
            launchedVirtualId
        } else {
            activeDisplayId = Display.DEFAULT_DISPLAY
            context.startActivity(launchIntent)
            Display.DEFAULT_DISPLAY
        }
        val finalCanvas = if (CanvasRoutingPolicy.isVirtualDisplay(finalDisplayId)) {
            CanvasRoutingPolicy.CanvasTarget.VIRTUAL_DISPLAY.wireName
        } else {
            CanvasRoutingPolicy.CanvasTarget.MAIN_DISPLAY.wireName
        }
        logger.info("Agent local tool action=launch_app outcome=started canvas=$finalCanvas display=$finalDisplayId")
        return JSONObject()
            .put("ok", true)
            .put("tool", "launch_app")
            .put("app_name", app.appName)
            .put("package_name", app.packageName)
            .put("canvas", finalCanvas)
            .put("display", finalDisplayId)
            .put("display_id", finalDisplayId)
            .toString()
    }

    /** 已启用插件声明的 prompt 扩展；由运行时注入系统提示。 */
    fun pluginPrompt(): String = pluginRuntime.promptSection()

    private fun pluginTool(name: String, args: JSONObject): AgentModelClient.ToolResult =
        when (name) {
            "plugin_list" -> {
                val installed = pluginRuntime.allInstalled()
                val enabledIds = installed.filter { pluginRuntime.isEnabled(it.id) }.map { it.id }.toSet()
                // MCP 注册状态要能看见：用户最常问的就是「插件装了，为什么工具没出现」。
                // 只读仓库、不触发连接，避免 list 变成一次网络请求。
                val registeredMcp = runCatching {
                    runBlocking { McpServerRepository.servers() }
                }.getOrDefault(emptyList()).associateBy { it.id }
                textResult(
                    JSONObject()
                        .put("ok", true)
                        .put("tool", "plugin_list")
                        .put("count", installed.size)
                        .put("enabled_count", enabledIds.size)
                        .put(
                            "plugins",
                            JSONArray().also { array ->
                                installed.forEach { plugin ->
                                    array.put(
                                        JSONObject()
                                            .put("id", plugin.id)
                                            .put("name", plugin.name)
                                            .put("version", plugin.version)
                                            .put("description", plugin.description)
                                            .put("author", plugin.author)
                                            .put("enabled", plugin.id in enabledIds)
                                            .put("install_dir", pluginRuntime.installer.pluginPath(plugin.id).orEmpty())
                                            .put("tools", pluginRuntime.installer.toolsJson(plugin))
                                            .put(
                                                "tools_note",
                                                if (plugin.tools.isEmpty()) {
                                                    ""
                                                } else {
                                                    "这些是能力声明，不是可调用工具；请通过该插件的 mcp 工具或 acp 代理使用"
                                                },
                                            )
                                            .put("mcp_count", plugin.mcp.size)
                                            .put(
                                                "mcp",
                                                JSONArray().also { servers ->
                                                    plugin.mcp.forEach { server ->
                                                        val serverId = PluginManifest.mcpServerId(plugin.id, server.name)
                                                        val record = registeredMcp[serverId]
                                                        servers.put(
                                                            JSONObject()
                                                                .put("name", server.name)
                                                                .put("url", server.url)
                                                                .put("server_id", serverId)
                                                                .put("registered", record != null)
                                                                .put("enabled", record?.enabled ?: false)
                                                                .put(
                                                                    "note",
                                                                    when {
                                                                        record == null && plugin.id !in enabledIds ->
                                                                            "插件已停用，未注册"

                                                                        record == null ->
                                                                            "未注册：插件是启用状态却查不到服务器记录，" +
                                                                                "多半注册失败；可调用 plugin_reload 重试"

                                                                        !record.enabled ->
                                                                            "已注册但被单独停用（在 MCP 服务器页恢复）"

                                                                        else -> "可用"
                                                                    },
                                                                ),
                                                        )
                                                    }
                                                },
                                            )
                                            .put("acp_count", plugin.acp.size)
                                            .put("has_prompt", plugin.prompt.isNotBlank()),
                                    )
                                }
                            },
                        )
                        .toString(),
                )
            }

            "plugin_reload" -> {
                // 手改 eta-plugin.json 后不会自动生效：这里重新扫描磁盘，
                // 并按「已启用」重新注册 ACP 代理与 MCP 服务器。
                val installed = pluginRuntime.allInstalled()
                val enabled = installed.filter { pluginRuntime.isEnabled(it.id) }
                syncExternalAcpAgents()
                val problems = enabled.flatMap { plugin ->
                    syncPluginMcpServers(plugin, enabled = true).map { "${plugin.id}: $it" }
                }
                textResult(
                    JSONObject()
                        .put("ok", true)
                        .put("tool", "plugin_reload")
                        .put("count", installed.size)
                        .put("enabled_count", enabled.size)
                        .put("mcp_problems", JSONArray(problems))
                        .toString(),
                )
            }

            "plugin_install" -> {
                val request = AgentPluginTools.parseInstall(args)
                    ?: return textResult(
                        errorResult("INVALID_ARGUMENT", "path 必须是绝对路径；覆盖安装时 id/sha256 格式必须正确")
                    )
                val file = java.io.File(request.path)
                if (!file.isFile && !file.isDirectory) {
                    return textResult(errorResult("PLUGIN_NOT_FOUND", "路径不存在：${request.path}"))
                }
                when (
                    val result = pluginRuntime.installPath(
                        path = file,
                        replaceExisting = request.replaceExisting,
                        expectedReplacementId = request.expectedReplacementId,
                        expectedArchiveSha256 = request.expectedArchiveSha256,
                    )
                ) {
                    is PluginPackageInstaller.Result.Installed -> {
                        // 安装成功就把插件声明的 ACP 代理与 HTTP MCP 服务器注册进去：装完即可用。
                        syncExternalAcpAgents()
                        val mcpProblems = syncPluginMcpServers(result.plugin, enabled = true)
                        textResult(
                            JSONObject()
                                .put("ok", true)
                                .put("tool", "plugin_install")
                                .put("id", result.plugin.id)
                                .put("name", result.plugin.name)
                                .put("version", result.plugin.version)
                                .put("already_installed", result.alreadyInstalled)
                                .put("sha256", result.sha256)
                                .put("mcp_problems", JSONArray(mcpProblems))
                                .put("summary", PluginManifest.summarize(result.plugin))
                                .apply {
                                    val agents = result.plugin.acp
                                    if (agents.isNotEmpty()) {
                                        put(
                                            "acp_agents",
                                            JSONArray().also { array ->
                                                agents.forEach { agent ->
                                                    array.put("${result.plugin.id}:${agent.id}")
                                                }
                                            },
                                        )
                                    }
                                }
                                .toString(),
                        )
                    }

                    is PluginPackageInstaller.Result.Failed -> textResult(
                        JSONObject()
                            .put("ok", false)
                            .put("tool", "plugin_install")
                            .put("code", result.code)
                            .put("message", result.message)
                            .toString(),
                    )
                }
            }

            "plugin_uninstall" -> {
                val request = AgentPluginTools.parseId(args)
                    ?: return textResult(errorResult("INVALID_ARGUMENT", "id 不合法"))
                val removing = pluginRuntime.allInstalled().firstOrNull { it.id == request.id }
                val removed = pluginRuntime.uninstall(request.id)
                if (removed) {
                    syncExternalAcpAgents()
                    removing?.let { syncPluginMcpServers(it, enabled = false) }
                }
                textResult(
                    JSONObject()
                        .put("ok", removed)
                        .put("tool", "plugin_uninstall")
                        .put("id", request.id)
                        .apply {
                            if (!removed) {
                                put("code", "PLUGIN_NOT_FOUND")
                                put("message", "插件不存在：${request.id}")
                            }
                        }
                        .toString(),
                )
            }

            "plugin_enable" -> {
                val request = AgentPluginTools.parseEnable(args)
                    ?: return textResult(errorResult("INVALID_ARGUMENT", "id 不合法或缺少 enabled"))
                val installed = pluginRuntime.allInstalled().firstOrNull { it.id == request.id }
                val ok = pluginRuntime.setEnabled(request.id, request.enabled)
                if (ok) {
                    syncExternalAcpAgents()
                    installed?.let { syncPluginMcpServers(it, enabled = request.enabled) }
                }
                textResult(
                    JSONObject()
                        .put("ok", ok)
                        .put("tool", "plugin_enable")
                        .put("id", request.id)
                        .put("enabled", request.enabled)
                        .apply {
                            if (!ok) {
                                put("code", "PLUGIN_NOT_FOUND")
                                put("message", "插件不存在：${request.id}")
                            }
                        }
                        .toString(),
                )
            }

            else -> textResult(errorResult("UNKNOWN_TOOL", "未知插件工具：$name"))
        }

    /**
     * 插件声明的 HTTP MCP 服务器注册进内置 MCP 管理器，装完即可被模型看到。
     * 失败只记录：MCP 注册不成功不应让插件安装整体失败。
     *
     * 实际同步逻辑在 [PluginMcpRegistry]，因为界面上的开关/卸载也要用同一份 ——
     * 只在这里处理的话，从界面停用插件会留下仍然在连的 MCP 服务器。
     */
    private fun syncPluginMcpServers(plugin: PluginManifest.Plugin, enabled: Boolean): List<String> {
        if (plugin.mcp.isEmpty()) return emptyList()
        return runCatching {
            runBlocking { PluginMcpRegistry.sync(plugin, enabled) }
        }.getOrElse { failure ->
            val reason = "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}".take(200)
            logger.info("plugin mcp registration failed: $reason")
            listOf(reason)
        }
    }

    private sealed interface AcpProviderResolution {
        data class Use(val config: AcpProvider.Config, val model: String) : AcpProviderResolution
        data class Error(val code: String, val message: String) : AcpProviderResolution
    }

    private fun codexProviders(): List<ProviderSetting> = runCatching {
        runBlocking { ProviderRepository.allProviders() }
    }.getOrDefault(emptyList())

    private fun ProviderSetting.isCodexCompatible(): Boolean =
        (this is OpenAiCompatibleProviderSetting || this is CustomProviderSetting) &&
            isEnabled && baseUrl.isNotBlank()

    private fun ProviderSetting.codexReady(): Boolean =
        isCodexCompatible() && apiKey.isNotBlank() &&
            models.any { model -> model.isEnabled && model.modelId.isNotBlank() }

    private fun ProviderSetting.codexModelIds(): List<String> =
        models.filter { model -> model.isEnabled && model.modelId.isNotBlank() }
            .map { model -> model.modelId }

    /**
     * 把用户说的 provider 名 / id / 模型名解析成 Eta 里真实配置的提供商。
     * 只有显式指定 provider 时才报错；未指定且没有可用提供商时返回 null，
     * 保留「用 codex 自己的 ~/.codex/config.toml」这条旧路径。
     */
    private fun resolveAcpProvider(requested: String?, model: String?): AcpProviderResolution? {
        val compatible = codexProviders().filter { provider -> provider.isCodexCompatible() }
        val wanted = requested?.trim()?.takeIf { it.isNotEmpty() }
        if (wanted == null) {
            val settings = runCatching { runBlocking { ProviderRepository.settings() } }.getOrNull()
            val chosen = compatible.firstOrNull { provider ->
                provider.id == settings?.selectedProviderId && provider.codexReady()
            } ?: compatible.firstOrNull { provider -> provider.codexReady() }
                ?: return null
            val chosenModel = model?.trim()?.takeIf { it.isNotEmpty() }
                ?: settings?.selectedModelId?.takeIf { id ->
                    settings.selectedProviderId == chosen.id && chosen.models.any { it.modelId == id }
                }
                ?: chosen.codexModelIds().firstOrNull()
                ?: return null
            return AcpProviderResolution.Use(
                config = AcpProvider.Config(
                    id = chosen.id,
                    name = chosen.name,
                    baseUrl = chosen.baseUrl,
                    apiKey = chosen.apiKey,
                    wireApi = AcpProvider.CODEX_WIRE_API,
                    defaultModel = chosenModel,
                    // 中转站的自定义头在 Eta 自己请求里有效，委派 codex 时也必须带上。
                    customHeaders = chosen.customHeaders,
                ),
                model = chosenModel,
            )
        }

        val chosen = compatible.firstOrNull { provider -> provider.id.equals(wanted, ignoreCase = true) }
            ?: compatible.firstOrNull { provider -> provider.name.equals(wanted, ignoreCase = true) }
            ?: compatible.firstOrNull { provider ->
                provider.models.any { m ->
                    m.modelId.equals(wanted, ignoreCase = true) ||
                        m.displayName.equals(wanted, ignoreCase = true)
                }
            }
            ?: return AcpProviderResolution.Error(
                "ACP_PROVIDER_UNKNOWN",
                "Eta 中没有提供商「$wanted」。可用：" +
                    compatible.joinToString("、") { provider -> provider.name }.ifEmpty { "无" },
            )
        if (!chosen.codexReady()) {
            return AcpProviderResolution.Error(
                "ACP_PROVIDER_NOT_READY",
                "提供商「${chosen.name}」还不能用于 Codex：需要启用、填写 API key，并至少有一个模型",
            )
        }
        val chosenModel = model?.trim()?.takeIf { it.isNotEmpty() }
            ?: chosen.codexModelIds().firstOrNull()
            ?: return AcpProviderResolution.Error(
                "ACP_PROVIDER_NO_MODEL",
                "提供商「${chosen.name}」没有可用模型",
            )
        return AcpProviderResolution.Use(
            config = AcpProvider.Config(
                id = chosen.id,
                name = chosen.name,
                baseUrl = chosen.baseUrl,
                apiKey = chosen.apiKey,
                wireApi = AcpProvider.CODEX_WIRE_API,
                defaultModel = chosenModel,
                customHeaders = chosen.customHeaders,
            ),
            model = chosenModel,
        )
    }

    private fun acpProvidersJson(providers: List<ProviderSetting>, selectedId: String?): JSONArray =
        JSONArray().also { array ->
            providers.filter { provider -> provider.isCodexCompatible() }.forEach { provider ->
                val models = provider.codexModelIds()
                val modelsJson = JSONArray()
                models.take(AcpModels.MAX_LISTED_MODELS).forEach(modelsJson::put)
                array.put(
                    JSONObject()
                        .put("id", provider.id)
                        .put("name", provider.name)
                        .put("base_url", provider.baseUrl)
                        .put("codex_wire_api", AcpProvider.CODEX_WIRE_API)
                        .put("model_count", models.size)
                        .put("models", modelsJson)
                        .put("ready", provider.codexReady())
                        .put("selected", provider.id == selectedId),
                )
            }
        }

    /**
     * @param toolCallId 调用方（AgentLoop）给出的本次调用 id，进度事件靠它挂回同一张工具卡片。
     */
    private fun acpTool(
        name: String,
        args: JSONObject,
        toolCallId: String,
    ): AgentModelClient.ToolResult =
        when (name) {
            "acp_agent_list" -> {
                val agents = acpClient.agents()
                val providers = codexProviders()
                val settings = runCatching { runBlocking { ProviderRepository.settings() } }.getOrNull()
                val providerReady = providers.any { provider -> provider.codexReady() }
                textResult(
                    JSONObject()
                        .put("ok", true)
                        .put("tool", "acp_agent_list")
                        .put("default_agent", acpClient.defaultAgentId())
                        .put("providers", acpProvidersJson(providers, settings?.selectedProviderId))
                        .put(
                            "agents",
                            JSONArray().also { array ->
                                agents.forEach { agent ->
                                    array.put(
                                        JSONObject()
                                            .put("id", agent.id)
                                            .put("title", agent.title)
                                            .put("executable", agent.executable)
                                            .put("resolved_path", agent.resolvedPath)
                                            .put("source", agent.source)
                                            .put("installed", agent.installed)
                                            .put(
                                                "credential_present",
                                                agent.credentialPresent || (agent.id == "codex" && providerReady),
                                            )
                                            .put("credential_env_keys", JSONArray(agent.credentialEnvKeys))
                                            .put(
                                                "note",
                                                when {
                                                    !agent.installed ->
                                                        "在 Linux 工具环境内没找到可执行文件（已按显式路径、" +
                                                            "通配目录与 PATH 依次查找），请先在该环境安装"
                                                    agent.id == "codex" && providerReady ->
                                                        "可用（使用 Eta 模型提供商）"
                                                    !agent.credentialPresent -> "缺少供应商凭证，需要 " + agent.credentialEnvKeys.joinToString(" 或 ")
                                                    else -> "可用"
                                                },
                                            )
                                    )
                                }
                            },
                        )
                        .put(
                            "sessions",
                            JSONArray().also { array ->
                                acpClient.sessions().forEach { session ->
                                    array.put(
                                        JSONObject()
                                            .put("session_id", session.id)
                                            .put("agent", session.agentId)
                                            .put("cwd", session.cwd)
                                            .put("age_seconds", session.ageSeconds)
                                            .put("task", session.taskPreview),
                                    )
                                }
                            },
                        )
                        .toString(),
                )
            }

            "acp_delegate" -> {
                val request = AgentAcpTools.parseDelegate(args)
                    ?: return textResult(
                        errorResult(
                            "INVALID_ARGUMENT",
                            "task 必填（≤8000 字）；cwd 必须是 Linux 环境内绝对路径；timeout_seconds 范围 30..1800",
                        )
                    )
                val agent = request.agent.ifBlank { acpClient.defaultAgentId() }
                if (request.provider != null && agent != "codex") {
                    return textResult(errorResult("ACP_PROVIDER_UNSUPPORTED", "provider 参数目前只支持 codex 代理"))
                }
                val providerResolution = if (agent == "codex") {
                    resolveAcpProvider(request.provider, request.model)
                } else {
                    null
                }
                if (providerResolution is AcpProviderResolution.Error) {
                    return textResult(errorResult(providerResolution.code, providerResolution.message))
                }
                val providerConfig = (providerResolution as? AcpProviderResolution.Use)?.config
                val effectiveModel = request.model
                    ?: (providerResolution as? AcpProviderResolution.Use)?.model
                // 进度桥：ACP 读线程 → AgentEvent.ToolProgress → 既有事件流 → 工具卡片副标题。
                // 节流放在这里而不是 AcpClient：那里不知道"多少频率人才看得清"，
                // 而 text 类增量在代理写文件时可以每几十毫秒来一次。
                val progressSink = onToolProgress
                val lastEmit = java.util.concurrent.atomic.AtomicLong(0L)
                val (ok, payload) = acpClient.delegate(
                    agentId = agent,
                    task = request.task,
                    cwd = request.cwd,
                    timeoutSeconds = request.timeoutSeconds,
                    model = effectiveModel,
                    provider = providerConfig,
                    onProgress = progressSink?.let { sink ->
                        AcpClient.ProgressObserver { kind, detail ->
                            val now = System.currentTimeMillis()
                            if (now - lastEmit.get() < ACP_PROGRESS_THROTTLE_MS) return@ProgressObserver
                            lastEmit.set(now)
                            sink(
                                AgentEvent.ToolProgress(
                                    // 轮次由 AgentLoop 在转发时覆盖（它才知道 round）；
                                    // 这里填 0 只是为了让事件在独立测试里可构造。
                                    round = 0,
                                    toolCallId = toolCallId,
                                    name = "acp_delegate",
                                    kind = kind,
                                    detail = detail,
                                ),
                            )
                        }
                    },
                )
                textResult(payload).also { if (!ok) logger.warn("ACP delegate failed: ${payload.take(200)}") }
            }

            "acp_session_status" -> {
                val request = AgentAcpTools.parseSession(args)
                    ?: return textResult(errorResult("INVALID_ARGUMENT", "session_id 不合法"))
                val (_, payload) = acpClient.status(request.sessionId)
                textResult(payload)
            }

            "acp_session_close" -> {
                val request = AgentAcpTools.parseSession(args)
                    ?: return textResult(errorResult("INVALID_ARGUMENT", "session_id 不合法"))
                val (_, payload) = acpClient.closeSession(request.sessionId)
                textResult(payload)
            }

            else -> textResult(errorResult("UNKNOWN_TOOL", "未知 ACP 工具：$name"))
        }

    /** 这块屏相对当前对话的归属（会话优先、运行级兜底）。 */
    private fun ownershipOf(info: VirtualDisplayHub.SessionInfo): VirtualDisplayOwnership.Match =
        VirtualDisplayOwnership.match(
            ownerConversationId = info.ownerConversationId,
            ownerRunId = info.ownerRunId,
            currentConversationId = virtualDisplayOwnerId,
            currentRunId = virtualDisplayRunId,
        )

    /** 归属说明：建了多久、用途 —— 模型据此判断「该不该 force 释放别人的屏」。 */
    private fun describeOwner(info: VirtualDisplayHub.SessionInfo): String {
        val ageSeconds = ((System.currentTimeMillis() - info.createdAtMs) / 1000).coerceAtLeast(0)
        val purpose = info.purpose.ifBlank { "未写用途" }
        return "创建于 $ageSeconds 秒前，用途：$purpose"
    }

    /**
     * 虚拟屏工具执行入口。
     * create/release/list 由 VirtualDisplayController 负责；
     * launch 赬倒到 root am start --display，因为主进程的 startActivity
     * 无法把 activity 送到非默认屏（setActivityRecordInFullscreen 等阻抑）。
     */
    private fun virtualDisplayTool(name: String, args: JSONObject, generation: Long? = null): AgentModelClient.ToolResult {
        val result = when (name) {
            "virtual_display_create" -> {
                val mainMetrics = context.resources.displayMetrics
                val request = AgentVirtualDisplayTools.parseCreate(
                    args,
                    mainWidth = mainMetrics.widthPixels,
                    mainHeight = mainMetrics.heightPixels,
                )
                    ?: return textResult(errorResult("INVALID_ARGUMENT", "width/height/density_dpi 范围不合法"))
                val session = runCatching {
                    virtualDisplays.create(
                        width = request.width,
                        height = request.height,
                        densityDpi = request.densityDpi,
                        purpose = request.purpose,
                        // 带上归属：屏会跨运行存活，不记下来新运行就认不出它，
                        // 既不敢释放（假成功），也无法判断是不是别的对话的。
                        ownerConversationId = virtualDisplayOwnerId,
                        ownerRunId = virtualDisplayRunId,
                    )
                }.getOrElse { throwable ->
                    // 上限/缓冲不足有自己的错误码：模型据此知道该释放还是该换小尺寸，
                    // 笼统回「创建失败」只会让它重试同一件事。
                    val code = (throwable as? ManagedVirtualDisplayController.RejectedException)?.code
                        ?: "VIRTUAL_DISPLAY_FAILED"
                    return textResult(errorResult(code, throwable.message ?: "无法创建虚拟屏"))
                }
                val created = JSONObject()
                    .put("ok", true)
                    .put("tool", "virtual_display_create")
                    .put("id", session.id)
                    .put("display_id", session.displayId)
                    .put("width", session.width)
                    .put("height", session.height)
                    .put("density_dpi", session.densityDpi)
                if (request.packageName.isEmpty()) {
                    created
                        .put(
                            "next",
                            "先 virtual_display_launch 启动目标应用（它现在会自动观察首屏），" +
                                "再按 first_observation 里的节点直接操作；" +
                                "拿不到节点时再回退到截图 + 坐标动作（tap_area/swipe 等）。" +
                                "任务结束后调用 virtual_display_release；也可以下次直接传 package_name，建屏与启动一次完成。",
                        )
                        .toString()
                } else {
                    // 建屏 + 启动 + 首屏观察合成一次调用：同一件事原本要 create / launch / (wait+observe) 三次。
                    val launched = launchOnVirtualDisplay(
                        session,
                        AgentVirtualDisplayTools.LaunchRequest(
                            id = session.id,
                            packageName = request.packageName,
                            activity = request.activity,
                            force = request.force,
                            action = "",
                            data = "",
                            flags = null,
                            extras = emptyMap(),
                        ),
                    )
                    created.put("launched", launched.optBoolean("ok"))
                    if (launched.optBoolean("ok")) {
                        created
                            .put("package_name", request.packageName)
                            .put("component", launched.optString("component"))
                        observeAfterLaunch(session.displayId, generation)?.let { observation ->
                            created.put("first_observation", summarizeLaunchObservation(observation))
                        }
                    } else {
                        // 屏建好了但应用没起来：不能把整次调用判成失败 —— 模型会以为屏也没建，
                        // 反复 create 直到撞上会话上限。把真实原因作为子字段报回去。
                        created.put("launch_error", launched)
                        created.put(
                            "next",
                            "屏已建好，但应用没启动成功：按 launch_error 处理" +
                                "（常见原因是它已在别处运行，确实接受后果时传 force=true）。",
                        )
                    }
                    created.toString()
                }
            }

            "virtual_display_list" -> {
                val includeOthers = args.optBoolean("include_others", false)
                // 以 Hub 为准而不是本 controller：屏会跨运行存活，只读 controller 自己的 map
                // 会把上一次运行建的屏当成「不存在」——用户切回对话就再也管不到它。
                val all = VirtualDisplayHub.list()
                // 登记残留（controller 已无此会话）不列出，避免报出点不开的「幽灵屏」。
                val live = all.mapNotNull { info ->
                    VirtualDisplayHub.liveSession(info.id)?.let { session -> info to session }
                }
                val mine = live.filter { ownershipOf(it.first) == VirtualDisplayOwnership.Match.SELF }
                val visible = if (includeOthers) live else mine
                val others = live.size - mine.size
                // 一次任务栈查询复用给所有会话：用来回答"这块屏上到底有没有东西"。
                // 系统会把任务迁回主屏（实测），光看会话存活会以为一切正常。
                val tasks = if (visible.isEmpty()) null else deviceController.tasksOrNull()
                JSONObject()
                    .put("ok", true)
                    .put("tool", "virtual_display_list")
                    // count 与 displays 数组等长（include_others=true 时含别人的屏）；
                    // 自己的块数单独报，免得模型把「列表里有 2 块」读成「我有 2 块」。
                    .put("count", visible.size)
                    .put("mine_count", mine.size)
                    .put("total_count", live.size)
                    .put("others_count", others)
                    .put("include_others", includeOthers)
                    .put(
                        "limits",
                        JSONObject()
                            .put("max_sessions", VirtualDisplayBudget.MAX_SESSIONS)
                            .put("max_total_mb", VirtualDisplayBudget.MAX_TOTAL_BYTES / (1024 * 1024))
                            .put("idle_timeout_seconds", VirtualDisplayBudget.IDLE_TIMEOUT_MS / 1000),
                    )
                    .put(
                        "total_mb",
                        // 额度是整机的，不是本对话的：别人的屏也占内存，必须算进去。
                        live.sumOf { it.second.estimatedBytes } * 10 / (1024 * 1024) / 10.0,
                    )
                    .put(
                        "displays",
                        JSONArray().also { array ->
                            visible.forEach { entry ->
                                val info = entry.first
                                val session = entry.second
                                // 任务栈只说明「有任务」；进程是否还活着要单独查 ——
                                // 应用崩溃后任务记录仍留在栈里，只报包名等于说假话。
                                val foreground = VirtualDisplayTasks.foregroundOn(tasks.orEmpty(), session.displayId)
                                val foregroundAlive = if (foreground.isEmpty()) {
                                    null
                                } else {
                                    deviceController.packageHasProcess(foreground)
                                }
                                array.put(
                                    JSONObject()
                                        .put("id", session.id)
                                        .put("display_id", session.displayId)
                                        .put("width", session.width)
                                        .put("height", session.height)
                                        .put("density_dpi", session.densityDpi)
                                        .put("age_seconds", session.ageSeconds)
                                        .put("idle_seconds", session.idleSeconds)
                                        .put("estimated_mb", session.estimatedBytes / (1024 * 1024))
                                        .put("purpose", info.purpose)
                                        // 归属：self 是本对话的；other 是别的对话的（只有
                                        // include_others=true 才会出现）；unknown 是旧版本没记归属。
                                        .put("owner", VirtualDisplayOwnership.label(ownershipOf(info)))
                                        // 这块屏上此刻有几个任务、哪个在前台。
                                        // 0 个通常意味着应用被系统迁回了主屏（见 observe 的提示）。
                                        .put(
                                            "task_count",
                                            VirtualDisplayTasks.forDisplay(tasks.orEmpty(), session.displayId).size,
                                        )
                                        .put("foreground_package", foreground)
                                        .put("foreground_alive", foregroundAlive ?: JSONObject.NULL)
                                        .apply {
                                            // 没有前台包时也要说清楚：空屏与「任务栈压根没查到」是两件不同的事，
                                            // 两者原本都表现为 task_count=0 + 空包名，模型只能自己猜。
                                            val note = if (foreground.isNotEmpty()) {
                                                virtualDisplayForegroundNote(foreground, foregroundAlive)
                                            } else {
                                                virtualDisplayEmptyNote(tasksKnown = tasks != null)
                                            }
                                            note?.let { put("foreground_note", it) }
                                        }
                                )
                            }
                        },
                    )
                    .apply {
                        if (others > 0 && !includeOthers) {
                            put(
                                "others_note",
                                "另有 $others 块虚拟屏属于其他对话：默认不列出，也不会被 id=all 释放；" +
                                    "要看时传 include_others=true，确认无人使用后才能用 force 释放",
                            )
                        }
                    }
                    .toString()
            }

            "virtual_display_release" -> {
                val request = AgentVirtualDisplayTools.parseRelease(args)
                    ?: return textResult(errorResult("INVALID_ARGUMENT", "id 不能为空"))
                val releaseAll = request.id.equals("all", ignoreCase = true)
                // 以 Hub 为准：屏会跨运行存活，只查本 controller 会把上一次运行建的屏当成
                // 「不存在」，于是返回「释放成功」而屏还在 —— 假成功比报错更难查。
                val all = VirtualDisplayHub.list()
                val targets = if (releaseAll) {
                    all.filter { info -> ownershipOf(info) == VirtualDisplayOwnership.Match.SELF }
                } else {
                    listOfNotNull(all.firstOrNull { it.id == request.id })
                }
                if (!releaseAll && targets.isEmpty()) {
                    // 幂等：重复释放、已被空闲回收都不该报错；但也不能说「释放了 1 块」。
                    return textResult(
                        JSONObject()
                            .put("ok", true)
                            .put("tool", "virtual_display_release")
                            .put("released", 0)
                            .put("id", request.id)
                            .put("closed_tasks", 0)
                            .put("note", "这块虚拟屏已经不存在（可能已释放，或被空闲超时回收），无需再处理")
                            .toString(),
                    )
                }
                val foreign = targets.filter { info ->
                    ownershipOf(info) != VirtualDisplayOwnership.Match.SELF
                }
                if (foreign.isNotEmpty() && !request.force) {
                    val first = foreign.first()
                    return textResult(
                        errorResult(
                            "VIRTUAL_DISPLAY_OTHER_SESSION",
                            "虚拟屏 ${first.id} 属于另一个对话（${describeOwner(first)}），本次未释放。" +
                                "确认那个对话不再使用它之后，再传 force=true 释放",
                        ),
                    )
                }
                // 释放前先移除任务，避免系统把任务迁回主屏；与 controller 内的
                // taskCleaner 共用同一条路径，这里额外把数量报给模型。
                var closedTasks = 0
                targets.forEach { info ->
                    closedTasks += deviceController.closeTasksOnDisplay(info.displayId).closedTasks
                }
                val released = targets.count { info -> VirtualDisplayHub.release(info.id) }
                val releasedAll = released == targets.size
                // id=all 只清本对话的：以前它会连别的对话的屏一起拆，等于替用户做了决定。
                val skippedOthers = if (releaseAll) all.size - targets.size else 0
                JSONObject()
                    .put("ok", releasedAll)
                    .put("tool", "virtual_display_release")
                    .put("released", released)
                    .put("id", request.id)
                    .put("closed_tasks", closedTasks)
                    .apply {
                        if (!releasedAll) {
                            put("error_code", "VIRTUAL_DISPLAY_TASKS_REMAIN")
                            put(
                                "error",
                                "虚拟屏上仍有任务未能移除，本次未完全释放；请先关闭这些应用或重试",
                            )
                        }
                        if (closedTasks > 0) {
                            put("note", "释放前已移除屏上的 $closedTasks 个任务，避免它们被系统迁回主屏")
                        }
                        if (skippedOthers > 0) {
                            put("skipped_others", skippedOthers)
                            put(
                                "others_note",
                                "另有 $skippedOthers 块虚拟屏属于其他对话，id=all 不会释放它们",
                            )
                        }
                    }
                    .toString()
            }

            "virtual_display_launch" -> {
                val request = AgentVirtualDisplayTools.parseLaunch(args)
                    ?: return textResult(errorResult("INVALID_ARGUMENT", "id 与 package_name 必填，且必须是合法包名"))
                val session = virtualDisplays.find(request.id)
                    ?: return textResult(
                        errorResult("VIRTUAL_DISPLAY_NOT_FOUND", "未找到虚拟屏 ${request.id}；请先 virtual_display_list")
                    )
                val outcome = launchOnVirtualDisplay(session, request)
                if (!outcome.optBoolean("ok")) return textResult(outcome.toString())
                // 启动后顺手观察首次屏：以前这一步要 launch → wait N 秒 → observe_screen 三次调用，
                // 而且"等多久"只能猜。现在一次调用就把首屏节点和 observation_id 一起拿到。
                val firstObservation = observeAfterLaunch(session.displayId, generation)
                outcome
                    .put("tool", "virtual_display_launch")
                    .put("display_id", session.displayId)
                    .put("package_name", request.packageName)
                    .put("action", request.action.ifEmpty { "android.intent.action.MAIN" })
                    .put("data", request.data)
                    .put("extras_count", request.extras.size)
                if (firstObservation != null) {
                    outcome.put("first_observation", summarizeLaunchObservation(firstObservation))
                }
                outcome.toString()
            }

            else -> return textResult(errorResult("UNKNOWN_TOOL", "未知虚拟屏工具：$name"))
        }
        return textResult(result)
    }

    /**
     * 在虚拟屏上启动应用，并且**核对它真的落在虚拟屏上**。
     *
     * 三次实测得来的三道关（缺一条就会"动用户的屏"）：
     * 1. **解析组件**：`am start -p <pkg> -c LAUNCHER` 在本机解析不出活动，直接失败；
     *    先 `cmd package resolve-activity` 拿到 `包名/类名` 再用 `-n` 启动。
     * 2. **预检已有任务**：对已经运行的应用，`am start --display N` 不会新建任务，
     *    而是把那个任务搬过来或把主屏上的实例顶到前台 —— 执行完命令的成功码是骗人的。
     * 3. **启动后核验**：`am start` 返回成功不等于它在目标屏；必须在任务栈里确认。
     */
    private fun launchOnVirtualDisplay(
        session: ManagedVirtualDisplayController.Session,
        request: AgentVirtualDisplayTools.LaunchRequest,
    ): JSONObject {
        var component = request.component
        // 前两关（解析组件、预检已有任务）必须在同一个 Root 会话里完成：
        // 预检要在启动之前，而解析又要先执行一次命令。
        val earlyError = runCatching {
            val executor = BoundedRootCommandExecutor(logger, rootAvailable = rootAvailable)
            executor.use { exec ->
                if (component.isEmpty() && request.activity.isEmpty()) {
                    val resolved = exec.execute(
                        "cmd package resolve-activity --brief -a android.intent.action.MAIN " +
                            "-c android.intent.category.LAUNCHER ${request.packageName}",
                        timeoutMillis = 8_000L,
                        maxOutputBytes = 4_096,
                    )
                    val parsed = AgentVirtualDisplayTools.parseLauncherComponent(
                        resolved.stdout,
                        request.packageName,
                    )
                    if (parsed == null) {
                        return@use errorResult(
                            "LAUNCH_ENTRY_NOT_FOUND",
                            "解析不到「${request.packageName}」的启动入口；" +
                                "请显式传 activity=包名/类名。解析输出：" + resolved.stdout.take(200),
                        )
                    }
                    component = parsed
                }
                // 预检必须在启动**之前**：命令一执行，用户的主屏就已经被改动了。
                val hosting = VirtualDisplayTasks.displaysHosting(
                    deviceController.tasksNow(),
                    request.packageName,
                )
                if (hosting.isNotEmpty() && !request.force) {
                    return@use errorResult(
                        "APP_ALREADY_RUNNING",
                        "「${request.packageName}」已有任务在 display " +
                            hosting.sorted().joinToString("、") +
                            "。在虚拟屏上启动会把它从那里搬走（主屏上的实例会被挪走或顶到前台），" +
                            "等于动了用户的屏，所以本次未启动。请先让该应用退出；" +
                            "确实接受这个后果时传 force=true。",
                    )
                }
                null
            }
        }.getOrElse { throwable ->
            errorResult("LAUNCH_FAILED", "启动失败：" + (throwable.message ?: throwable.javaClass.simpleName))
        }
        if (earlyError != null) return JSONObject(earlyError)

        val effective = request.copy(component = component)
        val runResult = runCatching {
            val executor = BoundedRootCommandExecutor(logger, rootAvailable = rootAvailable)
            executor.use {
                it.execute(
                    AgentVirtualDisplayTools.buildAmStartCommand(session.displayId, effective),
                    timeoutMillis = 20_000L,
                    maxOutputBytes = 8_192,
                )
            }
        }.getOrNull()
        val output = runResult?.stdout.orEmpty()
        val failed = runResult == null || !runResult.ok ||
            output.contains("Error", ignoreCase = true) ||
            output.contains("Exception", ignoreCase = true)
        if (failed) {
            return JSONObject().put("ok", false)
                .put("code", "LAUNCH_FAILED")
                .put(
                    "message",
                    "在虚拟屏上启动失败：" + output.ifBlank { runResult?.errorCode ?: "未知错误" },
                )
        }
        // 给窗口一点时间挂到任务栈上，再核对"到底落在哪"。
        Thread.sleep(600L)
        val tasks = deviceController.tasksNow()
        val landed = VirtualDisplayTasks.forDisplay(tasks, session.displayId)
            .any { it.packageName == effective.packageName || it.topPackage == effective.packageName }
        if (!landed) {
            val actual = VirtualDisplayTasks.displaysHosting(tasks, effective.packageName)
            return JSONObject().put("ok", false)
                .put("code", "LAUNCH_NOT_ON_DISPLAY")
                .put(
                    "message",
                    "命令返回成功，但「${effective.packageName}」没有落在 display ${session.displayId}" +
                        (if (actual.isEmpty()) {
                            "（任务栈里也找不到它，可能启动即退出）"
                        } else {
                            "（实际在 display ${actual.sorted().joinToString("、")}）"
                        }) +
                        "。这通常是系统把任务迁回了主屏，请改用其他应用或先释放该虚拟屏。",
                )
                .put("actual_displays", JSONArray(actual.sorted()))
        }
        virtualDisplays.noteForegroundPackage(session.id, effective.packageName)
        // 启动命令的完整输出里常有 "Warning: ..."，对模型是噪声，只留尾部。
        return JSONObject()
            .put("ok", true)
            .put("component", effective.component)
            .put("verified_on_display", session.displayId)
            .put("output", output.take(240))
    }

    /**
     * 启动后轮询该屏的首屏节点，拿到就返回（最多 [LAUNCH_OBSERVE_TIMEOUT_MS]）。
     *
     * 为什么不让模型自己 `wait` 一下再 `observe_screen`：等多久只能猜（猜短了拿到空节点，
     * 猜长了白等），而且那是两次额外调用。这里按 [LAUNCH_OBSERVE_POLL_MS] 轮询，
     * 一旦有节点就停；顺便把这次观察登记为「当前观察」，紧接着的 tap_element
     * 无需再自己重现观察一次。
     */
    private fun observeAfterLaunch(displayId: Int, generation: Long? = null): JSONObject? {
        val deadline = SystemClock.elapsedRealtime() + LAUNCH_OBSERVE_TIMEOUT_MS
        var last: JSONObject? = null
        var lastObservation: Observation? = null
        while (true) {
            val observation = runCatching {
                deviceController.observe(
                    includeScreenshot = false,
                    includeUiTree = true,
                    maxNodes = LAUNCH_OBSERVE_MAX_NODES,
                    displayId = displayId,
                )
            }.getOrNull()
            val content = observation?.let { runCatching { JSONObject(it.content) }.getOrNull() }
            if (content != null) {
                last = content
                lastObservation = observation
                if (launchObservationReady(content)) {
                    content.put("tree_ready", true)
                    publishObservation(observation, displayId, generation)
                    return content
                }
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                // 超时也要登记最后一次：它可能已经能用，只是没达到「就绪」判据。
                // 但必须标明「这棵树可能还没画完」：否则模型会把「没看到」当成「那里没东西」——
                // 真机上就出现过「只有 2 个节点时盲点坐标，点到不该点的地方」。
                last?.put("tree_ready", false)
                lastObservation?.let { publishObservation(it, displayId, generation) }
                return last
            }
            Thread.sleep(LAUNCH_OBSERVE_POLL_MS)
        }
    }

    /**
     * 首屏「够用了」的判据：有可点／可滚节点，或节点数达到 [LAUNCH_OBSERVE_READY_NODES]。
     *
     * 只用「ui_nodes 非空」作判据会踩坑：启动动画期常只回 1~2 个纯容器节点（真机实测 2 个），
     * 里面既没有可点项也没有滚动容器，模型还得再观察一次 —— 恰好是要省掉的那一步。
     */
    private fun launchObservationReady(content: JSONObject): Boolean {
        val nodes = content.optJSONArray("ui_nodes") ?: return false
        if (nodes.length() >= LAUNCH_OBSERVE_READY_NODES) return true
        for (index in 0 until nodes.length()) {
            val node = nodes.optJSONObject(index) ?: continue
            if (node.optBoolean("clickable") || node.optBoolean("scrollable")) return true
        }
        return false
    }

    private fun publishObservation(
        observation: Observation,
        displayId: Int,
        generation: Long? = null,
    ) {
        val elements = observation.elementObservation ?: return
        publishedObservation.set(
            PublishedObservation(
                elements = elements,
                coordinateSpace = observation.coordinateSpace,
                displayId = displayId,
                // 与 observeScreen 保持一致：必须登记当时的 GUI 代号。
                // 漏了它，紧接着的 tap_element / type_text 会因「代号不匹配」被判成
                // NO_OBSERVATION —— 建屏后第一次操作必然失败一次（真机连踩两次才发现）。
                generation = generation,
            ),
        )
    }

    /**
     * 把一次完整观察缩成「首屏摘要」：只留节点与可用性字段，不带截图与坐标契约。
     *
     * 全量 content 约 8~9KB，直接塞进 launch 结果会把一次轻量调用变成大块上下文；
     * 这里只留能直接着手的那几项。
     */
    private fun summarizeLaunchObservation(content: JSONObject): JSONObject =
        JSONObject()
            .put("observation_id", content.optString("observation_id"))
            .put("ui_nodes_shown", content.optInt("ui_nodes_shown", 0))
            .put("ui_tree_truncated", content.optBoolean("ui_tree_truncated"))
            .put("ui_nodes_truncated", content.optBoolean("ui_nodes_truncated"))
            .put("node_limit", content.optInt("node_limit", 0))
            .put("tree_ready", content.optBoolean("tree_ready", true))
            .put("ui_nodes", content.optJSONArray("ui_nodes") ?: JSONArray())
            .put(
                "note",
                "启动后已自动观察首屏：界面已就绪可直接 tap_element；若仍是加载中的画面，再 observe_screen 刷新一次。",
            )
            .also { summary ->
                content.optJSONObject("ui_diagnostics")?.let { diagnostics ->
                    summary.put("ui_diagnostics", diagnostics)
                }
            }

    /**
     * 把后台虚拟屏里已经准备好的画面移交给物理主屏（上游 3.4.0 的 `present_to_user`）。
     *
     * 刻意不在这里做「迁移后要不要关卡片」这类界面决定：Runtime 侧的结果卡片自己带「打开画面」。
     * 没有虚拟屏时返回 ok=false 的 `NO_VIRTUAL_DISPLAY`，而不是假装成功 —— 模型据此知道该走别的手段。
     */
    private fun presentToUser(args: JSONObject): String {
        val context = requireContext()
        val reason = args.optString("reason").trim()
        val snapshot = VirtualDisplayController.snapshots.value
        if (!snapshot.active) {
            return errorResult(
                "NO_VIRTUAL_DISPLAY",
                "当前没有后台虚拟屏画面可移交；如果目标应用在主屏，直接用 observe_screen 继续",
            )
        }
        val presented = VirtualDisplayController.handoffToMainDisplay(context, rootAvailable)
        activeDisplayId = Display.DEFAULT_DISPLAY
        return JSONObject()
            .put("ok", presented)
            .put("tool", "present_to_user")
            .put("canvas", CanvasRoutingPolicy.CanvasTarget.MAIN_DISPLAY.wireName)
            .put("display_id", Display.DEFAULT_DISPLAY)
            .put("presented", presented)
            .apply {
                if (snapshot.appName.isNotBlank()) put("app_name", snapshot.appName)
                if (snapshot.packageName.isNotBlank()) put("package_name", snapshot.packageName)
                if (reason.isNotBlank()) put("reason", reason)
                if (!presented) put("code", "HANDOFF_FAILED")
            }
            .toString()
    }

    private fun openUri(args: JSONObject): String {
        val uriText = args.optString("uri").trim()
        if (uriText.isBlank()) {
            return errorResult("INVALID_ARGUMENT", "uri 不能为空")
        }
        val uri = Uri.parse(uriText)
        if (uri.scheme.isNullOrBlank()) {
            return errorResult("INVALID_ARGUMENT", "uri 缺少 scheme")
        }
        val displayId = resolveDisplayId(args, requireTask = false)
        if (displayId != null) {
            return errorResult(
                "UNSUPPORTED_ON_VIRTUAL_DISPLAY",
                "open_uri 仍只支持主屏；虚拟屏请用 launch_app(display_id=$displayId) 或 virtual_display_launch",
            )
        }
        val context = requireContext()
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!HookSupport.resolvesActivity(context, intent)) {
            return errorResult("NO_ACTIVITY", "没有应用可以处理该 URI")
        }
        val resolvedPkg = context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
        val mainForegroundPkg = AgentAccessibilityService.current()?.currentPackageName(Display.DEFAULT_DISPLAY)
        val targetCanvas = CanvasRoutingPolicy.decideLaunchTarget(
            virtualDisplayEnabled = virtualDisplayEnabled(),
            backendAvailable = VirtualDisplayController.isBackendAvailable(rootAvailable(), lsposedAvailable()),
            requested = CanvasRoutingPolicy.RequestedDisplay.AUTO,
            targetPackage = resolvedPkg,
            foregroundPackageOnMainDisplay = mainForegroundPkg,
            ownPackage = context.packageName,
        )
        val virtualId = if (targetCanvas == CanvasRoutingPolicy.CanvasTarget.VIRTUAL_DISPLAY) {
            VirtualDisplayController.openUriOnVirtualDisplay(
                context = context,
                intent = intent,
                targetLabel = uri.host ?: uri.scheme.orEmpty(),
                rootAvailable = rootAvailable,
            )
        } else {
            null
        }
        val finalDisplayId = if (virtualId != null && CanvasRoutingPolicy.isVirtualDisplay(virtualId)) {
            activeDisplayId = virtualId
            virtualId
        } else {
            activeDisplayId = Display.DEFAULT_DISPLAY
            context.startActivity(intent)
            Display.DEFAULT_DISPLAY
        }
        val finalCanvas = if (CanvasRoutingPolicy.isVirtualDisplay(finalDisplayId)) {
            CanvasRoutingPolicy.CanvasTarget.VIRTUAL_DISPLAY.wireName
        } else {
            CanvasRoutingPolicy.CanvasTarget.MAIN_DISPLAY.wireName
        }
        logger.info("Agent local tool action=open_uri outcome=started canvas=$finalCanvas")
        return JSONObject()
            .put("ok", true)
            .put("tool", "open_uri")
            .put("scheme", uri.scheme?.lowercase(Locale.ROOT))
            .put("canvas", finalCanvas)
            .put("display_id", finalDisplayId)
            .also { result ->
                if (uri.scheme.equals("https", true)) {
                    result.put("display_uri", uriText)
                }
            }
            .toString()
    }

    /** 查看或切换当前会话工作区；参考 dsh 的 working_directory(cd=...)。 */
    private fun workingDirectory(args: JSONObject): String {
        val environment = args.optString("environment", "android").trim().lowercase(Locale.ROOT)
        return when (environment.ifBlank { "android" }) {
            "android" -> androidWorkingDirectory(args)
            "linux" -> linuxWorkingDirectory(args)
            else -> errorResult("INVALID_ENVIRONMENT", "environment 仅支持 android/linux")
        }
    }

    private fun androidWorkingDirectory(args: JSONObject): String {
        val current = terminalExecutionContext.userWorkspacePath
        val raw = args.optString("cwd").trim()
        if (raw.isEmpty()) {
            return JSONObject()
                .put("ok", true)
                .put("tool", "working_directory")
                .put("environment", "android")
                .put("cwd", current)
                .put("persisted", workspaceScopeId.isNotBlank() && io.github.mangi.eta.agent.terminal.SessionWorkspaceStore.get(context, workspaceScopeId) != null)
                .toString()
        }
        val candidate = runCatching { resolveWorkspacePath(raw, current) }.getOrElse { error ->
            return errorResult("INVALID_WORKSPACE", error.message ?: "工作区路径无效")
        }
        terminalExecutionContext.userWorkspacePath = candidate
        val persisted = workspaceScopeId.isNotBlank() && io.github.mangi.eta.agent.terminal.SessionWorkspaceStore.set(context, workspaceScopeId, candidate)
        return JSONObject()
            .put("ok", true)
            .put("tool", "working_directory")
            .put("environment", "android")
            .put("cwd", candidate)
            .put("previous", current)
            .put("persisted", persisted)
            .put("note", "后续文件与 Android 终端命令默认使用新工作区；已打开的 terminal session 保持原 cwd。")
            .toString()
    }

    /**
     * 切换本会话的 Linux 默认目录（guest 视角）。
     *
     * `/workspace` 是宿主共享挂载，不按会话重新绑定：用户的项目（如 `/workspace/eta-v2`）
     * 必须对所有会话可见。隔离落在「每个会话记住自己的 cwd」上；`/root` 也在允许范围内。
     */
    private fun linuxWorkingDirectory(args: JSONObject): String {
        val current = terminalExecutionContext.linuxCwd
        val raw = args.optString("cwd").trim()
        val store = io.github.mangi.eta.agent.terminal.SessionWorkspaceStore
        if (raw.isEmpty()) {
            return JSONObject()
                .put("ok", true)
                .put("tool", "working_directory")
                .put("environment", "linux")
                .put("cwd", current)
                .put("persisted", workspaceScopeId.isNotBlank() && store.getLinuxCwd(context, workspaceScopeId) != null)
                .toString()
        }
        val candidate = io.github.mangi.eta.agent.terminal.LinuxGuestPaths.normalize(current, raw)
            ?: return errorResult("INVALID_WORKSPACE", "Linux 工作目录必须是 /workspace 或 /root 下的路径")
        val host = io.github.mangi.eta.agent.terminal.LinuxGuestPaths.hostPath(
            candidate,
            terminalExecutionContext.linuxHostWorkspacePath,
            terminalExecutionContext.linuxRootfsPath,
        ) ?: return errorResult("LINUX_ENVIRONMENT_NOT_READY", "Linux 环境尚未安装，无法校验该目录")
        if (!File(host).isDirectory) {
            return errorResult("WORKSPACE_NOT_FOUND", "Linux 目录不存在：$candidate（请先在 Linux 环境内创建）")
        }
        terminalExecutionContext.linuxCwd = candidate
        val persisted = workspaceScopeId.isNotBlank() && store.setLinuxCwd(context, workspaceScopeId, candidate)
        return JSONObject()
            .put("ok", true)
            .put("tool", "working_directory")
            .put("environment", "linux")
            .put("cwd", candidate)
            .put("previous", current)
            .put("persisted", persisted)
            .put("note", "后续 Linux 命令与后台任务默认使用该目录；/workspace 仍是共享挂载，已打开的 terminal session 保持原 cwd。")
            .toString()
    }

    private fun resolveWorkspacePath(raw: String, current: String): String {
        require(raw.length <= 4096 && '\u0000' !in raw) { "工作区路径无效或过长" }
        val expanded = when {
            raw == "~" -> current
            raw.startsWith("~/") -> "$current/${raw.removePrefix("~/")}"
            raw.startsWith("/") -> raw
            else -> "$current/$raw"
        }
        val file = File(expanded).canonicalFile
        if (!file.exists() && !file.mkdirs()) error("目录不存在，也无法创建")
        require(file.isDirectory) { "工作区必须是目录" }
        require(file.canWrite()) { "工作区不可写" }
        require(file.absolutePath !in io.github.mangi.eta.agent.terminal.WorkspacePathRules.blockedRoots) { "不能把系统目录设为工作区" }
        return file.absolutePath
    }

    private fun checkRoot(): String {
        val probe = RootAccess.request(requireContext())
        kotlinx.coroutines.runBlocking {
            while (probe.isActive && !closed.get()) kotlinx.coroutines.delay(100)
        }
        if (closed.get()) return errorResult("CANCELLED", "已取消等待 Root 检测")
        val state = RootAccess.state.value
        return JSONObject().put("ok", true).put("tool", "terminal").put("action", "check_root")
            .put("root_available", state.isGranted).put("status", state.status.name.lowercase(Locale.ROOT))
            .put("checking", state.isChecking).put("su_present", state.suPresent)
            .put("uid", if (state.isGranted) 0 else JSONObject.NULL)
            .put("message", if (state.isGranted) "已确认 Root 授权；后续新命令默认使用 Root" else
                "尚未确认 Root 授权；请根据 status 检查授权或检测结果，默认 user 不表示终端不支持 Root")
            .toString()
    }

    private fun findAppByPackage(packageName: String): AppInfo? =
        installedLauncherApps().firstOrNull { it.packageName == packageName }

    private fun findAppsByName(query: String, includeSystem: Boolean): List<AppInfo> {
        val normalizedQuery = query.normalized()
        return installedLauncherApps()
            .asSequence()
            .filter { includeSystem || !it.isSystemApp }
            .mapNotNull { app ->
                val score = app.matchScore(query, normalizedQuery)
                if (score == Int.MAX_VALUE) null else score to app
            }
            .sortedWith(compareBy<Pair<Int, AppInfo>> { it.first }.thenBy { it.second.appName })
            .map { it.second }
            .toList()
    }

    private fun AppInfo.matchScore(rawQuery: String, normalizedQuery: String): Int {
        val normalizedName = appName.normalized()
        val normalizedPackage = packageName.normalized()
        return when {
            packageName.equals(rawQuery, ignoreCase = true) -> 0
            appName.equals(rawQuery, ignoreCase = true) -> 1
            normalizedName == normalizedQuery -> 2
            normalizedPackage.contains(normalizedQuery) -> 3
            normalizedName.contains(normalizedQuery) -> 4
            else -> Int.MAX_VALUE
        }
    }

    private fun installedLauncherApps(): List<AppInfo> {
        val context = requireContext()
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = packageManager.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(0L)
        )
        val apps = linkedMapOf<String, AppInfo>()
        resolveInfos.forEach { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@forEach
            val applicationInfo = activityInfo.applicationInfo ?: return@forEach
            val packageName = applicationInfo.packageName ?: return@forEach
            val appName = resolveInfo.loadLabel(packageManager).toString().trim()
                .takeIf { it.isNotBlank() }
                ?: packageName
            apps.putIfAbsent(
                packageName,
                AppInfo(
                    packageName = packageName,
                    appName = appName,
                    isSystemApp = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                )
            )
        }
        return apps.values.toList()
    }

    private fun requireContext(): Context =
        AgentAppContext.resolve()
            ?: error("无法获取 Android 进程 Context")

    private fun List<AppInfo>.toJsonArray(): JSONArray =
        JSONArray().also { array ->
            forEach { app ->
                array.put(
                    JSONObject()
                        .put("app_name", app.appName)
                        .put("package_name", app.packageName)
                        .put("is_system_app", app.isSystemApp)
                )
            }
        }

    private fun String.normalized(): String =
        trim().lowercase(Locale.ROOT)

    private fun String.isOkJson(): Boolean =
        runCatching { JSONObject(this).optBoolean("ok", false) }.getOrDefault(false)

    private fun JSONObject.optNullableInt(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null

    private fun requireElementObservation(
        args: JSONObject,
    ): RootShellDeviceController.ElementObservation? {
        val current = publishedObservation.get().elements ?: return null
        val requestedId = args.optString("observation_id").trim()
        return current.takeIf {
            ObservationReferencePolicy.validate(current.id, requestedId) ==
                ObservationReferencePolicy.Status.MATCH
        }
    }

    private fun observationError(args: JSONObject): String {
        val current = publishedObservation.get().elements
        val requestedId = args.optString("observation_id").trim()
        return when (ObservationReferencePolicy.validate(current?.id, requestedId)) {
            ObservationReferencePolicy.Status.NO_OBSERVATION ->
                errorResult("NO_OBSERVATION", "请先调用 observe_screen 获取 UI 节点")
            ObservationReferencePolicy.Status.ID_REQUIRED -> errorResult(
                "OBSERVATION_ID_REQUIRED",
                "节点动作必须携带同一次 observe_screen 返回的 observation_id",
            )
            ObservationReferencePolicy.Status.STALE -> {
                // 过期回执里顺带说「这个 index 现在指向什么」：实测模型很容易以为 index 能沿用，
                // 而新快照的同 index 早已是别的控件（o1 的 index=20 是「设备互联」容器，新快照同 index 不是）。
                val node = current?.nodes?.firstOrNull { it.index == args.optInt("index", -1) }
                val now = node?.let {
                    val label = it.text.take(40).takeIf { text -> text.isNotBlank() }
                        ?.let { text -> "「$text」" }
                        .orEmpty()
                    "；当前 index=${it.index} 是 ${it.className.substringAfterLast('.')}$label，不能默认还是原来那个控件"
                }.orEmpty()
                JSONObject()
                    .put("ok", false)
                    .put("code", "STALE_OBSERVATION")
                    .put(
                        "message",
                        "observation_id=$requestedId 已过期；当前为 ${current?.id}，请重新观察屏幕$now",
                    )
                    .put("observation_id", current?.id ?: JSONObject.NULL)
                    .toString()
            }
            ObservationReferencePolicy.Status.MATCH -> errorResult(
                "OBSERVATION_ERROR",
                "观察快照状态异常，请重新观察屏幕",
            )
        }
    }

    /**
     * 节点动作因快照过期失败时，自动重新观察并按同一节点重试一次。
     *
     * 现场：模型拿到观察快照后往往要隔几步才点，界面早已刷新，动作被 `validateNode` 判成
     * STALE_CONTENT；模型只能再 observe_screen、重新找 index、再点一次 —— 两次往返只为一次点击。
     *
     * 三条安全边界（判定规则在 [NodeActionRetryPolicy]，有单测守着）：
     * 1. 只重试「重新观察就能解决」的失败码；
     * 2. 重试前必须在新快照里找到**唯一**匹配节点，找不到或不唯一就放弃 —— 宁可不点，不能点错；
     * 3. 第一次失败意味着动作未执行（校验在动作之前），重试不会造成重复点击。
     */
    private fun retryStaleNodeAction(
        result: String,
        observation: RootShellDeviceController.ElementObservation,
        node: RootShellDeviceController.UiNode,
        action: (
            RootShellDeviceController.ElementObservation,
            RootShellDeviceController.UiNode,
        ) -> String,
    ): String {
        NodeActionRetryPolicy.staleFailureCode(result) ?: return result
        val fresh = refreshObservationForRetry(observation.maxNodes) ?: return result
        val target = NodeActionRetryPolicy.matchUniqueNode(fresh.nodes, node)
            ?: return retryHint(result, fresh)
        val retried = runCatching { action(fresh, target) }.getOrElse { return retryHint(result, fresh) }
        return runCatching {
            JSONObject(retried)
                .put("auto_retried", true)
                .put("observation_id", fresh.id)
                .put("index", target.index)
        }.getOrElse { retried }.toString()
    }

    /**
     * 为自动重试重新观察一次。拿不到节点树就恢复原快照：
     * 否则模型会从「快照过期」变成「没有观察」，连原失败原因都丢了。
     */
    private fun refreshObservationForRetry(
        maxNodes: Int,
    ): RootShellDeviceController.ElementObservation? {
        val previous = publishedObservation.get()
        val args = JSONObject()
            .put("display_id", previous.displayId ?: 0)
            .put("include_ui_tree", true)
            .put("include_screenshot", false)
            .put("max_nodes", maxNodes.coerceIn(1, 120))
        runCatching { observeScreen(args, publishedObservation.get().generation) }
        val refreshed = publishedObservation.get().elements
        if (refreshed == null) publishedObservation.set(previous)
        return refreshed
    }

    /**
     * 自动重试放弃时的回执：把刚拿到的 observation_id 一并给出，
     * 模型可以立刻用新 id 重新选节点，不必再多花一轮 observe_screen。
     */
    private fun retryHint(
        original: String,
        fresh: RootShellDeviceController.ElementObservation,
    ): String = runCatching {
        JSONObject(original)
            .put("observation_id", fresh.id)
            .put("auto_retried", false)
            .put(
                "retry_note",
                "已重新观察，但新快照里没有唯一对应的节点，动作未执行；" +
                    "请直接用 observation_id=${fresh.id} 重新选择节点",
            )
    }.getOrElse { original }.toString()

    // ==================== Skills tools ====================

    private fun skillsList(args: JSONObject): String {
        if (skillTreeMutationUncertain.get()) return nextTurnRequired("Skill 树")
        val indexService = skillIndexService
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能服务未初始化")
        val query = args.optString("query").trim().lowercase()
        val limit = args.optInt("limit", 50).coerceIn(1, 200)
        // refresh 用于「刚写完技能目录，现在要确认它被索引了」：默认走缓存，
        // 缓存对每次全量重扫没意义，但对一次工具调用里的重复查询有意义。
        val refresh = args.optBoolean("refresh", false)
        val entries = (if (refresh) {
            indexService.listSkillsForManagement(forceRefresh = true)
        } else {
            indexService.listInstalledSkills()
        })
            .filter { entry -> entry.installed && entry.enabled }
            .filter { entry -> SkillCompatibilityChecker.evaluate(entry).available }
            .filter { entry -> isVisibleInCurrentRun(entry.id) }
            .filter { entry ->
                if (query.isBlank()) true
                else listOf(entry.id, entry.name, entry.description, entry.skillFilePath, entry.rootPath)
                    .any { it.lowercase().contains(query) }
            }
            .take(limit)
        val items = JSONArray()
        entries.forEach { entry ->
            val capabilities = JSONArray()
            if (entry.hasScripts) capabilities.put("scripts")
            if (entry.hasReferences) capabilities.put("references")
            if (entry.hasAssets) capabilities.put("assets")
            if (entry.hasEvals) capabilities.put("evals")
            items.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("name", entry.name)
                    .put("description", entry.description)
                    .put("enabled", entry.enabled)
                    .put("source", entry.source)
                    .put("rootPath", entry.rootPath)
                    .put("skillFilePath", entry.skillFilePath)
                    .put("capabilities", capabilities)
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("query", query)
            .put("count", entries.size)
            // 技能根目录：创造模式据此新建 <id>/SKILL.md；未索引的技能无法从条目推断。
            .put("skillsRoot", indexService.rootPath)
            .put("refreshed", refresh)
            .put("items", items)
            .toString()
    }

    private fun skillsRead(args: JSONObject): String {
        if (skillTreeMutationUncertain.get()) return nextTurnRequired("Skill 树")
        val indexService = skillIndexService
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能服务未初始化")
        val loader = skillLoader
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能加载器未初始化")
        val skillId = args.optString("skillId").trim()
        if (skillId.isBlank()) return errorResult("MISSING_PARAM", "缺少 skillId")
        val maxChars = args.optInt("maxChars", 16_000).coerceIn(512, 64_000)
        val entry = indexService.findInstalledSkill(skillId)
            ?: return errorResult("NOT_FOUND", "未找到 skill：$skillId")
        if (!isVisibleInCurrentRun(entry.id)) return nextTurnRequired(entry.id)
        val compat = SkillCompatibilityChecker.evaluate(entry)
        if (!compat.available) return errorResult("INCOMPATIBLE", compat.reason ?: "当前环境不可用")
        val resolved = loader.load(entry, "agent 主动读取 skill")
            ?: return errorResult("READ_FAILED", "读取 SKILL.md 失败：${entry.skillFilePath}")
        val body = if (resolved.bodyMarkdown.length <= maxChars) {
            resolved.bodyMarkdown
        } else {
            resolved.bodyMarkdown.take(maxChars) + "\n..."
        }
        val references = JSONArray()
        resolved.loadedReferences.forEach { references.put(it) }
        val frontmatter = JSONObject()
        resolved.frontmatter.forEach { (k, v) -> frontmatter.put(k, v) }
        return JSONObject()
            .put("ok", true)
            .put("id", entry.id)
            .put("name", entry.name)
            .put("description", entry.description)
            .put("rootPath", entry.rootPath)
            .put("skillFilePath", entry.skillFilePath)
            .put("scriptsDir", resolved.scriptsDir ?: JSONObject.NULL)
            .put("assetsDir", resolved.assetsDir ?: JSONObject.NULL)
            .put("references", references)
            .put("frontmatter", frontmatter)
            .put("bodyMarkdown", body)
            .toString()
    }

    private fun skillsReadResource(args: JSONObject): String {
        if (skillTreeMutationUncertain.get()) return nextTurnRequired("Skill 树")
        val indexService = skillIndexService
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能服务未初始化")
        val reader = skillResourceReader
            ?: return errorResult("SKILLS_UNAVAILABLE", "Skill 资源读取器未初始化")
        val skillId = args.getString("skillId").trim()
        val relativePath = args.getString("relativePath").trim()
        val maxChars = args.optInt("maxChars", 16_000).coerceIn(512, 64_000)
        val entry = indexService.findInstalledSkill(skillId)
            ?: return errorResult("NOT_FOUND", "未找到已启用 Skill：$skillId")
        if (!isVisibleInCurrentRun(entry.id)) return nextTurnRequired(entry.id)
        val compatibility = SkillCompatibilityChecker.evaluate(entry)
        if (!compatibility.available) {
            return errorResult(
                "INCOMPATIBLE",
                compatibility.reason ?: "当前环境不可用",
            )
        }
        return when (val result = reader.readText(entry, relativePath)) {
            is SkillResourceReadResult.Success -> {
                val truncated = result.text.length > maxChars
                val visibleText = if (truncated) {
                    result.text.take(maxChars).let { prefix ->
                        if (prefix.lastOrNull()?.isHighSurrogate() == true) {
                            prefix.dropLast(1)
                        } else {
                            prefix
                        }
                    }
                } else {
                    result.text
                }
                JSONObject()
                    .put("ok", true)
                    .put("skillId", entry.id)
                    .put("relativePath", result.relativePath)
                    .put("text", visibleText)
                    .put("truncated", truncated)
                    .put("totalChars", result.text.length)
                    .toString()
            }
            is SkillResourceReadResult.Failure -> errorResult(
                code = result.error.code.name,
                message = result.error.message,
            )
        }
    }

    private fun skillsListCurated(): String {
        val source = githubSkillSource
            ?: return errorResult("SKILL_INSTALLER_UNAVAILABLE", "GitHub Skill 服务未初始化")
        return skillSourceResult {
            val inspection = source.listCurated()
            rememberInspection(
                repository = GitHubSkillRepositoryParser.parse(inspection.repository),
                inspection = inspection,
                rememberDefault = true,
            )
            inspectionResult(inspection)
        }
    }

    private fun skillsInspectGitHub(args: JSONObject): String {
        val source = githubSkillSource
            ?: return errorResult("SKILL_INSTALLER_UNAVAILABLE", "GitHub Skill 服务未初始化")
        return skillSourceResult {
            val repository = GitHubSkillRepositoryParser.resolve(
                repository = args.getString("repository"),
                explicitRef = args.optString("ref").takeIf { args.has("ref") },
                explicitPath = args.optString("path").takeIf { args.has("path") },
            )
            val inspection = source.inspect(repository)
            rememberInspection(
                repository = repository,
                inspection = inspection,
                rememberDefault = repository.ref == null,
            )
            inspectionResult(inspection)
        }
    }

    private fun skillsInstallFromGitHub(args: JSONObject): String {
        val replaceExisting = args.optBoolean("replaceExisting", false)
        return skillSourceResult {
            val requestedRepository = GitHubSkillRepositoryParser.resolve(
                repository = args.getString("repository"),
                explicitRef = args.optString("ref").takeIf { args.has("ref") },
                explicitPath = null,
            )
            val pathsJson = args.getJSONArray("paths")
            val selectedPaths = (0 until pathsJson.length()).map { index ->
                GitHubSkillRepositoryParser.normalizeRelativePath(pathsJson.getString(index))
            }
            if (replaceExisting && selectedPaths.size != 1) {
                return@skillSourceResult errorResult(
                    "SKILL_REPLACE_SCOPE_TOO_BROAD",
                    "一次只能替换一个 Skill 路径；请逐个重试",
                )
            }
            val expectedReplacementId = args.optString("expectedReplacementId").trim()
            val repository = if (replaceExisting) {
                validateReplacementReplay(
                    requestedRepository = requestedRepository,
                    selectedPaths = selectedPaths,
                    expectedReplacementId = expectedReplacementId,
                )?.let { return@skillSourceResult it }
                requestedRepository.copy(ref = pendingSkillConflict.get()!!.commitSha)
            } else {
                val snapshot = inspectedGitHubSnapshots[
                    inspectionKey(requestedRepository.slug, requestedRepository.ref)
                ] ?: return@skillSourceResult errorResult(
                    "SKILL_INSPECTION_REQUIRED",
                    "安装前必须在本轮先检查同一仓库与 ref 的 Skill 候选",
                )
                val invalidSelection = selectedPaths.firstOrNull {
                    it !in snapshot.candidatesByPath
                }
                if (invalidSelection != null) {
                    return@skillSourceResult errorResult(
                        "INVALID_SKILL_SELECTION",
                        "所选路径不在本轮检查返回的候选中：$invalidSelection",
                    )
                }
                val snapshotPrefix = snapshot.prefix
                if (
                    snapshotPrefix != null &&
                    selectedPaths.any {
                        it != snapshotPrefix && !it.startsWith("$snapshotPrefix/")
                    }
                ) {
                    return@skillSourceResult errorResult(
                        "INVALID_SKILL_SELECTION",
                        "所选路径不在本轮检查的目录范围内",
                    )
                }
                requestedRepository.copy(ref = snapshot.commitSha)
            }
            val prefix = requestedRepository.path?.takeUnless { it == "." }
            if (
                prefix != null &&
                selectedPaths.any { it != prefix && !it.startsWith("$prefix/") }
            ) {
                return@skillSourceResult errorResult(
                    "INVALID_SKILL_SELECTION",
                    "所选路径不在 GitHub URL 指定目录内",
                )
            }
            val source = githubSkillSource
                ?: return@skillSourceResult errorResult(
                    "SKILL_INSTALLER_UNAVAILABLE",
                    "GitHub Skill 服务未初始化",
                )
            val installer = skillPackageInstaller
                ?: return@skillSourceResult errorResult(
                    "SKILL_INSTALLER_UNAVAILABLE",
                    "Skill 安装器未初始化",
                )
            source.downloadArchive(repository).use { archive ->
                if (closed.get()) {
                    return@skillSourceResult errorResult(
                        "SKILL_INSTALL_CANCELLED",
                        "Skill 安装已取消，未提交文件",
                    )
                }
                val result = installer.installRepositoryZip(
                    openStream = { archive.file.inputStream() },
                    selectedPaths = selectedPaths,
                    replaceUserSkills = replaceExisting,
                    expectedReplacementIds = if (replaceExisting) {
                        setOf(expectedReplacementId)
                    } else {
                        emptySet()
                    },
                    isCancelled = closed::get,
                )
                installResult(
                    result = result,
                    repository = archive.repository,
                    ref = archive.ref,
                    commitSha = archive.commitSha,
                    selectedPaths = selectedPaths,
                )
            }
        }
    }

    private fun inspectionResult(
        inspection: io.github.mangi.eta.agent.skill.GitHubSkillInspection,
    ): String {
        val installedIds = skillIndexService
            ?.listSkillsForManagement()
            .orEmpty()
            .filter { it.installed }
            .mapTo(mutableSetOf()) { SkillParser.normalizeSkillLookup(it.id) }
        val items = JSONArray()
        inspection.candidates.forEach { candidate ->
            items.put(
                JSONObject()
                    .put("name", candidate.name)
                    .put("path", candidate.path)
                    .put(
                        "installed",
                        SkillParser.normalizeSkillLookup(candidate.name) in installedIds,
                    ),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("repository", inspection.repository)
            .put("ref", inspection.ref)
            .put("commitSha", inspection.commitSha)
            .put("prefix", inspection.prefix ?: JSONObject.NULL)
            .put("count", inspection.candidates.size)
            .put("items", items)
            .toString()
    }

    private fun rememberInspection(
        repository: GitHubSkillRepository,
        inspection: GitHubSkillInspection,
        rememberDefault: Boolean,
    ) {
        val snapshot = GitHubInspectionSnapshot(
            commitSha = inspection.commitSha,
            prefix = inspection.prefix,
            candidatesByPath = inspection.candidates.associate { it.path to it.name },
        )
        inspectedGitHubSnapshots[inspectionKey(repository.slug, repository.ref)] = snapshot
        inspectedGitHubSnapshots[inspectionKey(repository.slug, inspection.ref)] = snapshot
        inspectedGitHubSnapshots[inspectionKey(repository.slug, inspection.commitSha)] = snapshot
        if (rememberDefault) {
            inspectedGitHubSnapshots[inspectionKey(repository.slug, null)] = snapshot
        }
    }

    private fun inspectionKey(repository: String, ref: String?): String =
        "${repository.lowercase(Locale.ROOT)}@${ref.orEmpty()}"

    private fun validateReplacementReplay(
        requestedRepository: GitHubSkillRepository,
        selectedPaths: List<String>,
        expectedReplacementId: String,
    ): String? {
        val pending = pendingSkillConflict.get() ?: return errorResult(
            "SKILL_REPLACE_CAPABILITY_REQUIRED",
            "没有可供精确重放的 Skill 冲突",
        )
        if (
            !requestedRepository.slug.equals(pending.repository, ignoreCase = true) ||
            requestedRepository.ref != pending.commitSha ||
            selectedPaths.singleOrNull() != pending.selectedPath ||
            expectedReplacementId != pending.expectedReplacementId
        ) {
            return errorResult(
                "SKILL_REPLACE_CAPABILITY_MISMATCH",
                "覆盖参数必须精确重放冲突结果中的仓库、commitSha、路径与 Skill ID",
            )
        }
        return null
    }

    private fun isVisibleInCurrentRun(skillId: String): Boolean {
        val normalized = SkillParser.normalizeSkillLookup(skillId)
        return normalized in runAvailableSkillIds && normalized !in mutatedSkillIds
    }

    private fun nextTurnRequired(skillId: String): String = errorResult(
        "NEXT_TURN_REQUIRED",
        "Skill $skillId 在本轮已安装或变更，将从下一轮对话开始可用",
    )

    private fun installResult(
        result: SkillInstallResult,
        repository: String,
        ref: String,
        commitSha: String,
        selectedPaths: List<String>,
    ): String = when (result) {
        is SkillInstallResult.Success -> {
            pendingSkillConflict.set(null)
            val installed = JSONArray()
            result.installed.forEach { skill ->
                mutatedSkillIds += SkillParser.normalizeSkillLookup(skill.id)
                installed.put(
                    JSONObject()
                        .put("id", skill.id)
                        .put("name", skill.name),
                )
            }
            JSONObject()
                .put("ok", true)
                .put("repository", repository)
                .put("ref", ref)
                .put("commitSha", commitSha)
                .put("selectedPaths", JSONArray(selectedPaths))
                .put("installed", installed)
                .put("available", "next_turn")
                .put("scriptsExecuted", false)
                .put("message", "Skill 已安装并启用，将从下一轮对话开始可用；安装过程未执行脚本")
                .toString()
        }
        is SkillInstallResult.Conflict -> {
            val conflicts = JSONArray()
            result.conflicts.forEach { conflict ->
                conflicts.put(
                    JSONObject()
                        .put("id", conflict.id)
                        .put("name", conflict.name)
                        .put("replaceAllowed", conflict.replaceAllowed),
                )
            }
            pendingSkillConflict.set(
                result.conflicts.singleOrNull()
                    ?.takeIf { it.replaceAllowed && selectedPaths.size == 1 }
                    ?.let { conflict ->
                        PendingSkillConflictCapability(
                            repository = repository,
                            commitSha = commitSha,
                            selectedPath = selectedPaths.single(),
                            expectedReplacementId = conflict.id,
                            expectedReplacementName = conflict.name,
                        )
                    },
            )
            JSONObject()
                .put("ok", false)
                .put("code", "SKILL_CONFLICT")
                .put("message", "Skill 已存在；可替换的单个用户 Skill 可按返回参数直接重试，内置 Skill 不可覆盖")
                .put("repository", repository)
                .put("ref", ref)
                .put("commitSha", commitSha)
                .put("selectedPaths", JSONArray(selectedPaths))
                .put("conflicts", conflicts)
                .toString()
        }
        is SkillInstallResult.Failure -> {
            if (result.error.code == SkillInstallErrorCode.COMMIT_FAILED) {
                skillTreeMutationUncertain.set(true)
            }
            errorResult(
                code = result.error.code.name,
                message = result.error.message,
            )
        }
    }

    private inline fun skillSourceResult(block: () -> String): String = try {
        block()
    } catch (failure: GitHubSkillSourceException) {
        errorResult(failure.code, failure.message ?: "GitHub Skill 请求失败")
    }

    private fun errorResult(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .toString()

    /**
     * fix168：模型传了本工具不认识的参数时，不要静默丢掉。
     *
     * `JSONObject.optInt("display_id")` 拿不到时只会返回缺省值，工具照常执行、
     * 结果里也看不出任何异常 —— 模型于是就相信「我已经指定了」。
     * 这里在**成功结果**上附一条 `arg_hints`（不改变 `ok`、不改变任何动作）：
     * 归一化后能对上声明参数的（`displayId` → `display_id`）直接给出正确名字，
     * 完全对不上的列出这个工具支持哪些参数。
     */
    private fun annotateArgumentHints(
        toolName: String,
        args: JSONObject,
        result: AgentModelClient.ToolResult,
    ): AgentModelClient.ToolResult {
        if (args.length() == 0) return result
        val declared = ToolArgumentSchema.declaredKeys(toolName) ?: return result
        val hints = ToolArgumentHintPolicy.review(declared, args.keys().asSequence().toList())
        if (hints.isEmpty()) return result
        val json = runCatching { JSONObject(result.content) }.getOrNull() ?: return result
        // 只挂在成功结果上：失败时模型已经在看错误码，再叠一条参数提示只会把注意力拉偏。
        if (!json.optBoolean("ok")) return result
        val messages = JSONArray()
        hints.forEach { hint -> messages.put(ToolArgumentHintPolicy.message(hint, declared)) }
        json.put("arg_hints", messages)
        return result.copy(content = json.toString())
    }

    private fun textResult(content: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(content)

    private data class ScreenPoint(val x: Int, val y: Int)

    private class InvalidToolArgumentException(
        message: String,
        /** 回给模型的错误码；与「参数非法」区分开，避免模型把设备状态问题当成参数问题。 */
        val code: String = "INVALID_ARGUMENT",
        /** 同样的调用再来一次会不会有不同的结果；屏不对这类情况恒为 false。 */
        val retryable: Boolean = true,
    ) : IllegalArgumentException(message)

    private data class PublishedObservation(
        val elements: RootShellDeviceController.ElementObservation? = null,
        val coordinateSpace: RootShellDeviceController.CoordinateSpace? = null,
        val displayId: Int? = null,
        /** 观察时的 GUI 代号；用户暂停/恢复会推进它，旧快照随之作废。 */
        val generation: Long? = null,
    )

    private data class GitHubInspectionSnapshot(
        val commitSha: String,
        val prefix: String?,
        val candidatesByPath: Map<String, String>,
    )

    /** 手势经过状态胶囊时让它暂时穿透并淡出；在虚拟屏内操作时不干扰物理屏胶囊。 */
    private fun <T> clearOverlay(displayId: Int, vararg points: ScreenPoint, gesture: () -> T): T {
        if (CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
            return gesture()
        }
        val area = Rect(
            points.minOf { it.x },
            points.minOf { it.y },
            points.maxOf { it.x } + 1,
            points.maxOf { it.y } + 1,
        )
        return AgentOverlayClearance.around(area, gesture)
    }

    private fun showTap(x: Int, y: Int, displayId: Int = Display.DEFAULT_DISPLAY) {
        if (CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
            VirtualDisplayController.emitTapEffect(x, y, longPress = false)
        } else {
            GestureIndicator.showTap(context, x, y)
        }
    }

    private fun showLongPress(
        x: Int,
        y: Int,
        durationMs: Int,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ) {
        if (CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
            VirtualDisplayController.emitTapEffect(x, y, longPress = true)
        } else {
            GestureIndicator.showLongPress(context, x, y, durationMs)
        }
    }

    private fun showSwipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ) {
        if (CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
            VirtualDisplayController.emitSwipeEffect(x1, y1, x2, y2, durationMs)
        } else {
            GestureIndicator.showSwipe(context, x1, y1, x2, y2, durationMs)
        }
    }

    private data class AppInfo(
        val packageName: String,
        val appName: String,
        val isSystemApp: Boolean = false
    )

    private companion object {
        /**
         * ACP 进度事件的最小间隔。
         *
         * 代理写文件时 text 增量可以每几十毫秒来一次；每次事件都要跨进程打包成 Bundle
         * 并触发一次 Compose 重组。250ms 足够让人看清"它在动"，又不至于把界面刷爆。
         */
        const val ACP_PROGRESS_THROTTLE_MS = 250L
        val DEVICE_DIRECT_TOOL_NAMES = io.github.mangi.eta.agent.model.AgentPhoneToolCatalog.direct + setOf(
            "set_alarm",
            "set_timer",
            "device_status",
            "inspect_app",
            "network_info",
            "top_memory_apps",
            "top_storage_apps",
            "media_control",
            "set_volume",
        )
        val DEVICE_SENSITIVE_READ_TOOL_NAMES = io.github.mangi.eta.agent.context.PersonalSearchTools.names + io.github.mangi.eta.agent.model.AgentPhoneToolCatalog.reads + setOf(
            "search_notes", "search_system_memories",
            "get_setting",
            "wifi_credentials",
            "recent_notifications",
            "search_notification_history",
            "recent_app_activity",
            "app_usage_summary",
            "get_current_location",
            "get_device_environment",
            "list_alarms",
            "list_active_timers",
            "search_clipboard_history",
            "get_health_summary",
            "read_sms_code",
            "get_logcat",
            "search_media",
            "search_audio",
            "search_recordings",
            "search_files",
            "search_calendar_events",
            "search_contacts",
            "search_call_history",
            "search_messages",
            "search_downloads",
            "search_coloros_notes",
            "search_coloros_recordings",
            "search_recording_summaries",
            "search_coloros_memories",
            "search_saved_places",
            "search_personal_orders",
            "search_qq_chat_images",
            "search_wechat_chat_images",
        )
        val DEVICE_SENSITIVE_ACTION_TOOL_NAMES = io.github.mangi.eta.agent.model.AgentPhoneToolCatalog.writes + setOf(
            "set_setting",
            "set_device_state",
            "app_state_control",
        )
        val DEVICE_TOOL_NAMES =
            DEVICE_DIRECT_TOOL_NAMES + DEVICE_SENSITIVE_READ_TOOL_NAMES +
                DEVICE_SENSITIVE_ACTION_TOOL_NAMES
        val MEMORY_TOOL_NAMES = setOf("memory_get", "memory_write")
    }
}

private val AFTER_ACTION_OPTIONS = AgentScreenObservationContract.Options(
    includeScreenshot = false,
    includeUiTree = true,
    maxNodes = 30,
)

/** append 超过该长度改走粘贴路径；逐字键入的增量重建对长文本既慢又容易被输入法打断。 */
private const val TYPE_TEXT_INCREMENTAL_CHARS = 200

/**
 * 虚拟屏启动后自动观察首屏的时间预算：够冷启动应用画完首屏，又不会把 launch 拖成「卡住」。
 *
 * 从 5s 调到 8s：设备忙（比如本机正在编译）时，设置首页 5 秒内只画出 2 个容器节点，
 * 旧预算会把这种半成品树当成首屏交出去（真机上随即拿它做过一次盲点）。
 * 就绪时照样提前返回，常态没有额外成本。
 */
private const val LAUNCH_OBSERVE_TIMEOUT_MS = 8_000L

/** 自动观察的轮询间隔；比无障碍整树 dump 一次（约 300ms）略短，避免白等。 */
private const val LAUNCH_OBSERVE_POLL_MS = 250L

/** 首屏观察的节点上限：与固定套路里的默认值一致，约 8.6KB，不触发落盘。 */
private const val LAUNCH_OBSERVE_MAX_NODES = 60

/** 首屏节点数达到这个量就当「已经画出来了」，不必再等轮询到超时。 */
private const val LAUNCH_OBSERVE_READY_NODES = 5

/** 归一化坐标上界：0–999，与主流 GUI 模型的输出习惯一致。 */
private const val NORMALIZED_MAX = 999

/**
 * 这次动作要动哪块屏的 scope —— 纯函数，便于单测。
 *
 * 规则：显式 `display_id` 优先；没传（0）时沿用最近一次观察所在的屏；都没有就是主屏。
 * 两块不同的虚拟屏会得到两个不同的 scope —— 这正是「两个会话各用一块屏时不再互相等待」的前提。
 * 如果这里退化成永远返回主屏，协调器再怎么按屏分发也没有意义，所以它值得单独有测试。
 */
internal fun resolveGuiScope(explicitDisplayId: Int, publishedDisplayId: Int?): String {
    val displayId = explicitDisplayId.takeIf { it != 0 }
        ?: ImplicitDisplayPolicy.inherit(publishedDisplayId)
    return if (displayId != null) {
        GuiControlCoordinator.displayScope(displayId)
    } else {
        GuiControlCoordinator.MainScope
    }
}
