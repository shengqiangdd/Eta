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
    /** 用户共享目录在 guest 内的挂载点（上游 3.4.0 新增，供提示与工具共用）。 */
    val sharedMounts: List<SharedFolderMount> = emptyList(),
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

    // 下面整段是「给模型看的执行上下文说明」，里面写 /sdcard 是在描述宿主路径映射，
    // 不是拿它做文件读写（真实访问一律走 workspacePath / bind mount）。lint 的 SdCardPath
    // 只看字面量，所以在这里显式豁免，避免以后有人把它当成真的路径拼接。
    @android.annotation.SuppressLint("SdCardPath")
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
        append("（bind mount 同一个物理文件夹，双向实时同步）；")
        if (linuxHostWorkspacePath == androidWorkspacePath) {
            append("与默认 Android 工作区完全同目录，两边修改直接互通。")
        } else {
            append("与默认 Android 工作区不同目录，Android 侧可直接通过 ")
            append(JSONObject.quote(linuxHostWorkspacePath))
            append(" 访问同一文件夹。")
        }
        if (sharedMounts.isEmpty()) {
            append("当前未配置额外共享目录（/workspace/mounts 仅在配置挂载项后挂载）；")
        } else {
            append("已配置的额外共享目录（Linux 挂载点 <-> Android 源目录，底层为同一物理文件夹，双向实时同步）：")
            append(
                sharedMounts.joinToString("，") { mount ->
                    "${JSONObject.quote("${SharedFolderMounts.LINUX_MOUNTS_ROOT}/${mount.name}")} <-> ${JSONObject.quote(mount.sourcePath)}"
                },
            )
            append("；")
        }
        if (linuxBackend == LinuxExecutionBackend.CHROOT) {
            append("chroot 下宿主 \"/storage/emulated/0\"（及符号链接 \"/sdcard\"）与 \"/data/local/tmp\" 已默认挂载至 Linux 同路径。")
        } else {
            append("PRoot 下若已授予所有文件访问权限，宿主 \"/storage/emulated/0\" 也会默认挂载至 Linux 同路径。")
        }
        append("Linux 路径按 Linux 环境解释。")
    }
}
