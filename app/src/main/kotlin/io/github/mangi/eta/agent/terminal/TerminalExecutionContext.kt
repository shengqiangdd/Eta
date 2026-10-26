package io.github.mangi.eta.agent.terminal

import org.json.JSONObject

/** 同一次运行的 Linux 环境；工作区/默认 cwd 可由 `working_directory` 在同一 run 内切换。 */
internal data class TerminalExecutionContext(
    val linuxEnvironment: TerminalEnvironment,
    val linuxRootfsPath: String?,
    /** 当前会话工作区；可由 `working_directory` 工具在同一 run 内切换。 */
    var userWorkspacePath: String = TerminalRuntime.userWorkspacePath,
    /**
     * 当前会话的 Linux 默认目录（guest 视角）。
     *
     * `/workspace` 仍是宿主共享目录的 bind 视图，不按会话重新绑定；会话隔离落在
     * 这个默认 cwd 上，避免把用户放在 `/workspace/...` 的项目对其它会话隐藏。
     */
    var linuxCwd: String = TerminalRuntime.LINUX_WORKSPACE_PATH,
    /** `/workspace` 这个 bind mount 的实际宿主来源；默认由后端推导，测试可显式传入。 */
    val linuxHostWorkspacePath: String = TerminalRuntime.linuxWorkspacePathFor(linuxRootfsPath),
) {
    init {
        require(linuxEnvironment.isLinux)
    }

    val androidWorkspacePath: String
        get() = userWorkspacePath

    val linuxBackend: LinuxExecutionBackend
        get() = LinuxEnvironmentPaths.backendOf(linuxRootfsPath)

    val linuxIdentity: String
        get() = if (linuxBackend == LinuxExecutionBackend.PROOT) "user" else "root"

    val linuxWorkspacePath: String
        get() = TerminalRuntime.LINUX_WORKSPACE_PATH

    val linuxReady: Boolean
        get() = LinuxEnvironmentPaths.rootfsReady(linuxRootfsPath)

    fun identityFor(environment: TerminalEnvironment, rootAvailable: Boolean): String =
        if (environment.isLinux) linuxIdentity else if (rootAvailable) "root" else "user"

    fun description(rootAvailable: Boolean): String = buildString {
        append("本次运行的命令和文件工具共用默认执行上下文：environment=android，identity=")
        append(identityFor(TerminalEnvironment.ANDROID, rootAvailable))
        append("，工作区=")
        append(JSONObject.quote(androidWorkspacePath))
        append("。已授权 Root 时直接使用 Root，未确认授权时使用 App 身份。identity=user 可显式选择普通身份；工作目录不因身份变化而移动。\n")
        append("environment=linux 使用已选择的 ")
        append(linuxEnvironment.wireName)
        append("，backend=")
        append(linuxBackend.wireName)
        append("，identity=")
        append(linuxIdentity)
        append("，ready=")
        append(linuxReady)
        append("，Linux 默认目录=")
        append(JSONObject.quote(linuxCwd))
        append("。Linux 工作区 ")
        append(JSONObject.quote(linuxWorkspacePath))
        append(" 映射宿主目录 ")
        append(JSONObject.quote(linuxHostWorkspacePath))
        append("；只有宿主目录相同时才与默认 Android 工作区共享文件。Linux 路径按 Linux 环境解释。")
    }
}
