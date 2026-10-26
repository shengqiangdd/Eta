package io.github.mangi.eta.agent.terminal

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import java.io.File

internal enum class LinuxExecutionBackend(val wireName: String) {
    CHROOT("chroot"),
    PROOT("proot"),
}

/** App 进程的终端运行条件；读取能力不会触发 su 授权。 */
internal object TerminalRuntime {
    const val ROOT_WORKSPACE_PATH = "/data/local/tmp/eta"
    const val LINUX_WORKSPACE_PATH = "/workspace"

    @Volatile private var appContext: Context? = null

    /** 用户自定义的终端工作区；null 表示用 App 私有目录。 */
    @Volatile private var customWorkspace: String? = null
    private var backgroundJobs: BackgroundTerminalJobs? = null

    @Synchronized
    fun backgroundJobs(context: Context): BackgroundTerminalJobs {
        val app = context.applicationContext
        return backgroundJobs ?: BackgroundTerminalJobs(
            supervisor = DetachedTaskSupervisor(AndroidAgentLogger, DetachedTaskSupervisor.defaultRecordsFile(app)),
            logger = AndroidAgentLogger,
            acquireLease = { onStop -> AgentExecutionService.acquire(app, "terminal-background-commands", onStop = onStop) },
            releaseLease = { AgentExecutionService.release("terminal-background-commands") },
            onCompleted = { BackgroundCommandNotification.show(app, it) },
            completionNotificationsEnabled = { app.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled() },
        ).also { backgroundJobs = it }
    }

    fun acquireUserTask(id: String, onStop: () -> Unit): Boolean =
        appContext?.let { AgentExecutionService.acquire(it, id, onStop = onStop) } ?: true

    fun releaseUserTask(id: String) {
        if (appContext != null) AgentExecutionService.release(id)
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        customWorkspace = TerminalWorkspaceSettings.get(context)
        File(userWorkspacePath).mkdirs()
    }

    /**
     * 设置 / 清除自定义工作区；返回 null 表示成功，否则是给用户看的原因。
     *
     * 只作用于 user 身份（以及 Linux 的 `/workspace`）：root 身份仍用
     * [ROOT_WORKSPACE_PATH]，那里承载 busybox applet，不能被用户路径替换。
     */
    fun applyCustomWorkspace(context: Context, path: String?): String? {
        val error = TerminalWorkspaceSettings.set(context, path)
        if (error != null) return error
        customWorkspace = TerminalWorkspaceSettings.get(context)
        customWorkspace?.let { runCatching { File(it).mkdirs() } }
        return null
    }

    /** 当前生效的自定义工作区（供设置页展示）；null 表示默认。 */
    val customWorkspacePath: String? get() = customWorkspace

    val rootAvailable: Boolean get() = RootAccess.isGranted
    val publicStorageGranted: Boolean get() = appContext != null && android.os.Environment.isExternalStorageManager()
    val nativeLibraryDir: File? get() = appContext?.applicationInfo?.nativeLibraryDir?.let(::File)
    val userWorkspacePath: String get() {
        // 自定义目录可能在设置之后被删除：每次取用时确认它还在，否则回退默认，
        // 避免终端一打开就落在一个不存在的路径上。
        customWorkspace?.takeIf { File(it).isDirectory }?.let { return it }
        return appContext?.let {
            TerminalPrivateStorage.workspace(it.filesDir).absolutePath
        } ?: File(System.getProperty("java.io.tmpdir"), "eta-terminal-workspace").absolutePath
    }

    /**
     * 会话工作区：非空 scopeId 时每个会话独立目录；空 scopeId（语音/一次性入口）沿用全局工作区。
     *
     * 自定义工作区是用户显式选择的项目目录，仍优先于会话目录 —— 这是有意的覆盖，
     * 避免升级后把用户已经指向的共享存储项目悄悄换掉。
     */
    fun sessionWorkspacePath(context: Context, scopeId: String): String {
        if (scopeId.isBlank()) return userWorkspacePath
        customWorkspace?.takeIf { File(it).isDirectory }?.let { return it }
        return TerminalPrivateStorage.sessionWorkspace(context.filesDir, scopeId).absolutePath
    }
    val temporaryDirectory: File get() = appContext?.let { File(it.cacheDir, "terminal/proot") }
        ?: File(System.getProperty("java.io.tmpdir"), "eta-proot")

    fun defaultIdentity(environment: TerminalEnvironment, rootfsPath: String? = null): String = when {
        environment.isLinux -> if (LinuxEnvironmentPaths.backendOf(rootfsPath) == LinuxExecutionBackend.PROOT) "user" else "root"
        rootAvailable -> "root"
        else -> "user"
    }

    fun workspace(identity: String): String {
        // 自定义工作区对两种身份都生效：用户要的是「终端落在我的项目里」，
        // 不该因为默认身份是 root 就失效。busybox applet 用的是 ROOT_WORKSPACE_PATH
        // 常量（绝对路径），不依赖 cwd，所以换工作区不会破坏终端内置命令。
        customWorkspace?.takeIf { File(it).isDirectory }?.let { return it }
        return if (identity == "root") ROOT_WORKSPACE_PATH else userWorkspacePath
    }

    /**
     * Linux `/workspace` 的实际宿主来源。
     *
     * chroot 与 proot 都接受设置里的自定义工作区：chroot 用 root 身份的工作区
     * （自定义目录或 `/data/local/tmp/eta`），proot 用普通身份的工作区。
     * 描述与路径校验都按实际 bind 来源走，不能假设两者相同。
     */
    fun linuxWorkspacePathFor(rootfsPath: String?): String =
        if (LinuxEnvironmentPaths.backendOf(rootfsPath) == LinuxExecutionBackend.PROOT) {
            workspace("user")
        } else {
            workspace("root")
        }

    fun nativeExecutable(name: String): File? = nativeLibraryDir?.let { File(it, name) }
        ?.takeIf { it.isFile && it.canExecute() }
}
