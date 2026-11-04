package io.github.mangi.eta.agent.terminal

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal enum class PackageProfileInstallStage {
    CHECKING,
    DOWNLOADING,
    INSTALLING,
    COMPLETE,
}

internal data class PackageProfileInstallProgress(
    val stage: PackageProfileInstallStage,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
)

internal sealed interface PackageProfileInstallResult {
    data object AlreadyReady : PackageProfileInstallResult
    data object EnvironmentNotReady : PackageProfileInstallResult

    /** 依赖的 profile 尚未安装，按依赖链先装它。 */
    data class DependencyMissing(val profileId: String) : PackageProfileInstallResult
    data object Installed : PackageProfileInstallResult
    data class Failed(val stage: PackageProfileInstallStage) : PackageProfileInstallResult
}

internal data class LinuxPackageSpec(
    val packages: List<String> = emptyList(),
    val managedTool: ManagedLinuxTool? = null,
    val setupScript: String? = null,
)

internal data class LinuxPackageProfile(
    val id: String,
    val markerName: String,
    val revision: Int,
    val specs: Map<LinuxDistribution, LinuxPackageSpec>,
    /** 安装前必须就绪的前置 profile。 */
    val dependsOn: LinuxPackageProfile? = null,
) {
    fun spec(distribution: LinuxDistribution): LinuxPackageSpec = requireNotNull(specs[distribution])
}

internal object LinuxPackageProfiles {
    val PYTHON = LinuxPackageProfile(
        id = "python",
        markerName = AlpineEnvironmentPaths.PYTHON_TOOLS_MARKER,
        revision = AlpineEnvironmentPaths.PYTHON_TOOLS_REVISION,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(
                managedTool = ManagedLinuxTool.UV,
                setupScript = """
                    UV_PYTHON_INSTALL_DIR=/opt/eta/python UV_PYTHON_BIN_DIR=/usr/local/bin UV_PYTHON_INSTALL_BIN=1 uv python install --default --force
                """.trimIndent(),
            ),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(
                managedTool = ManagedLinuxTool.UV,
                setupScript = """
                    UV_PYTHON_INSTALL_DIR=/opt/eta/python UV_PYTHON_BIN_DIR=/usr/local/bin UV_PYTHON_INSTALL_BIN=1 uv python install --default --force
                """.trimIndent(),
            ),
        ),
    )
    val NODE = LinuxPackageProfile(
        id = "node",
        markerName = AlpineEnvironmentPaths.NODE_TOOLS_MARKER,
        revision = AlpineEnvironmentPaths.NODE_TOOLS_REVISION,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(
                packages = listOf("nodejs-current", "npm"),
            ),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(
                // Node 官方 arm64 二进制链接 libatomic.so.1，归档安装不含系统依赖，需补装。
                packages = listOf("libatomic1"),
                managedTool = ManagedLinuxTool.NODE,
            ),
        ),
    )
    val SSH = LinuxPackageProfile(
        id = "ssh",
        markerName = AlpineEnvironmentPaths.SSH_TOOLS_MARKER,
        revision = AlpineEnvironmentPaths.SSH_TOOLS_REVISION,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(
                packages = listOf("openssh"),
                setupScript = "ssh-keygen -A >/dev/null 2>&1 || true",
            ),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(
                packages = listOf("openssh-client", "openssh-server"),
                setupScript = "ssh-keygen -A >/dev/null 2>&1 || true",
            ),
        ),
    )

    /**
     * Kimi Code 使用 npm 分发，运行在 Node profile 之上；可选原生扩展由 npm 按平台安装。
     * 始终安装最新正式版（升级重装即可）；--prefix /usr/local 让 kimi 进入 PATH 首位，
     * 与 Node 归档自身的 prefix 无关。国内镜像优先，官方 registry 兜底。
     */
    private const val KIMI_INSTALL_SCRIPT =
        "npm install -g --prefix /usr/local --registry=https://registry.npmmirror.com " +
            "@moonshot-ai/kimi-code@latest || " +
            "npm install -g --prefix /usr/local @moonshot-ai/kimi-code@latest"

    val KIMI = LinuxPackageProfile(
        id = "kimi",
        markerName = AlpineEnvironmentPaths.KIMI_TOOLS_MARKER,
        revision = AlpineEnvironmentPaths.KIMI_TOOLS_REVISION,
        dependsOn = NODE,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(setupScript = KIMI_INSTALL_SCRIPT),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(setupScript = KIMI_INSTALL_SCRIPT),
        ),
    )
    /**
     * ACP 编码代理：`acp_delegate` 真正启动的就是这两个可执行文件。
     *
     * 为什么需要这个 profile：它们以前**从来没有安装路径** —— 只是有人手工 `npm i -g` 到了
     * `/opt/eta/node/<ver>`。开发机上看着能用，新设备装完 Linux 环境却一个都找不到，
     * 而 `acp_agent_list` 会老实报 installed=false（错的是「没有人给用户装的办法」）。
     *
     * 装到哪里：`--prefix /opt/eta`，故意**不**用 `/usr/local`。
     * `AcpClient.BUILTIN_AGENTS` 的候选里有 `/opt/eta/node/<版本>/bin/codex-acp`，
     * 而 `/opt/eta/node/<版本>/bin` 是 Node 归档自带的 prefix；把包装到这里，
     * 升级 Node（换版本目录）就会把它们一并弄丢。装在 `/opt/eta` 下则与 Node 版本解耦，
     * 再软链到 `/usr/local/bin`，探测能直接命中。
     *
     * **为什么拆成两个 profile（Codex / Claude 各一个）**：
     * 两个包实测共 526MB（`@agentclientprotocol` 403M 含 `@openai/codex` 本体 +
     * `@zed-industries` 124M）。合在一起装 = 用户想用其中一个也必须付出全部体积；
     * 而且任一包网络失败会让整次安装失败，连已经装好的那个也要重来。
     * 拆开后：体积如实可见、失败互不牵连、用户能只装自己要用的那个。
     */
    private const val ACP_MKDIR = "mkdir -p /opt/eta/lib "

    /**
     * 注意 `mkdir -p /opt/eta/lib`：`npm install --prefix` **不会自己建目录**，
     * 它直接 lstat `<prefix>/lib` 就报 ENOENT。实测两种都不行：不建 prefix、
     * 只建 prefix 而没建 `lib`。必须两个都建，否则安装第一步就挂。
     */
    private fun acpInstallScript(packages: String): String =
        ACP_MKDIR +
            "&& (npm install -g --prefix /opt/eta --registry=https://registry.npmmirror.com " +
            "$packages || " +
            "npm install -g --prefix /opt/eta $packages)"

    /**
     * npm 装完后的收尾：把 `binName` 从 `/opt/eta/bin` 链到 `/usr/local/bin`。
     *
     * 为什么用软链而不是写 profile.d：这跟 [PinnedLinuxToolInstaller] 装 node 的做法一致
     * ——环境的 PATH 里本来就有 `/usr/local/bin`，不需要新变量、不需要 source，
     * 也不会因为用户拿到一个非登录 shell 而失效。
     *
     * **最后一行的验证不能省**：`npm` 返回 0 不等于产物真在。原样照搬 Kimi 的做法
     * （`kimi --version` 自检）——若 bin 缺失就非 0 退出，marker 不写，
     * 界面就不会把「装坏了」报成「已安装」。
     */
    private fun acpLinkCommand(binName: String): String =
        "ln -sfn /opt/eta/bin/$binName /usr/local/bin/$binName " +
            "&& test -x /usr/local/bin/$binName"

    val ACP_CODEX = LinuxPackageProfile(
        id = "acp-codex",
        markerName = AlpineEnvironmentPaths.ACP_CODEX_MARKER,
        revision = AlpineEnvironmentPaths.ACP_AGENTS_REVISION,
        dependsOn = NODE,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(
                setupScript = acpInstallScript("@agentclientprotocol/codex-acp@latest") + "\n" +
                    acpLinkCommand("codex-acp"),
            ),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(
                setupScript = acpInstallScript("@agentclientprotocol/codex-acp@latest") + "\n" +
                    acpLinkCommand("codex-acp"),
            ),
        ),
    )

    val ACP_CLAUDE = LinuxPackageProfile(
        id = "acp-claude",
        markerName = AlpineEnvironmentPaths.ACP_CLAUDE_MARKER,
        revision = AlpineEnvironmentPaths.ACP_AGENTS_REVISION,
        dependsOn = NODE,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(
                setupScript = acpInstallScript("@zed-industries/claude-code-acp@latest") + "\n" +
                    acpLinkCommand("claude-code-acp"),
            ),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(
                setupScript = acpInstallScript("@zed-industries/claude-code-acp@latest") + "\n" +
                    acpLinkCommand("claude-code-acp"),
            ),
        ),
    )

    val ALL = listOf(PYTHON, NODE, SSH, KIMI, ACP_CODEX, ACP_CLAUDE)
}

internal fun linuxPackageProfileReady(rootfs: File, profile: LinuxPackageProfile): Boolean {
    val marker = File(rootfs, profile.markerName)
    if (!marker.isFile) return false
    return marker.useLines { lines ->
        lines.any { line -> line.trim() == "profile=${profile.revision}" }
    }
}

/** 为当前选中的发行版按需安装单个工具 profile；成功后只写对应完成标记。 */
internal class LinuxPackageProfileInstaller(
    private val context: Context,
    private val distribution: LinuxDistribution,
    private val profile: LinuxPackageProfile,
) {
    private val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)
    private val managedToolInstaller = PinnedLinuxToolInstaller(context)

    fun isReady(): Boolean = linuxPackageProfileReady(rootfs, profile)

    suspend fun install(
        onProgress: suspend (PackageProfileInstallProgress) -> Unit = {},
    ): PackageProfileInstallResult {
        installMutex.lock()
        return try {
            installLocked(onProgress)
        } finally {
            installMutex.unlock()
        }
    }

    private suspend fun installLocked(
        onProgress: suspend (PackageProfileInstallProgress) -> Unit,
    ): PackageProfileInstallResult = withContext(Dispatchers.IO) {
        if (isReady()) return@withContext PackageProfileInstallResult.AlreadyReady
        onProgress(PackageProfileInstallProgress(PackageProfileInstallStage.CHECKING))
        if (!LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath) ||
            !File(rootfs, AlpineEnvironmentPaths.COMMON_TOOLS_MARKER).isFile
        ) {
            return@withContext PackageProfileInstallResult.EnvironmentNotReady
        }
        profile.dependsOn?.let { dependency ->
            if (!linuxPackageProfileReady(rootfs, dependency)) {
                return@withContext PackageProfileInstallResult.DependencyMissing(dependency.id)
            }
        }

        val spec = profile.spec(distribution)
        spec.managedTool?.let { tool ->
            val installed = managedToolInstaller.install(
                tool = tool,
                distribution = distribution,
                rootfs = rootfs,
            ) { downloadedBytes, totalBytes ->
                onProgress(
                    PackageProfileInstallProgress(
                        stage = PackageProfileInstallStage.DOWNLOADING,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                    ),
                )
            }
            if (!installed) {
                return@withContext PackageProfileInstallResult.Failed(
                    PackageProfileInstallStage.DOWNLOADING,
                )
            }
        }

        val packageHelper = when (distribution) {
            LinuxDistribution.ALPINE -> "/usr/local/bin/eta-apk"
            LinuxDistribution.DEBIAN -> "/usr/local/bin/eta-apt"
        }
        onProgress(PackageProfileInstallProgress(PackageProfileInstallStage.INSTALLING))
        if (spec.packages.isNotEmpty()) {
            val installResult = InstallerShellRunner.run(
                command = "$packageHelper install ${spec.packages.joinToString(" ")}",
                timeoutSeconds = INSTALL_TIMEOUT_SECONDS,
                environment = distribution.terminalEnvironment,
                linuxRootfsPath = rootfs.absolutePath,
            )
            if (installResult.exitCode != 0) {
                return@withContext PackageProfileInstallResult.Failed(
                    PackageProfileInstallStage.INSTALLING,
                )
            }
        }

        val activateCommand = buildString {
            append("set -e\n")
            spec.setupScript?.let { script -> append(script).append('\n') }
            if (profile == LinuxPackageProfiles.KIMI) append("kimi --version >/dev/null\nkimi web --help >/dev/null\n")
            append("cat > /").append(profile.markerName).append(" <<'ETA_PROFILE_EOF'\n")
            append("profile=").append(profile.revision).append('\n')
            append("ETA_PROFILE_EOF\n")
            append("chmod 0644 /").append(profile.markerName).append(" || exit 71")
        }
        val result = InstallerShellRunner.run(
            command = activateCommand,
            timeoutSeconds = INSTALL_TIMEOUT_SECONDS,
            environment = distribution.terminalEnvironment,
            linuxRootfsPath = rootfs.absolutePath,
        )
        AndroidAgentLogger.info(
            "Package profile action=activate distribution=${distribution.wireName} profile=${profile.id} " +
                "outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${result.exitCode} outputChars=${result.output.length}",
        )
        if (result.exitCode != 0) {
            return@withContext PackageProfileInstallResult.Failed(PackageProfileInstallStage.INSTALLING)
        }

        onProgress(PackageProfileInstallProgress(PackageProfileInstallStage.COMPLETE))
        PackageProfileInstallResult.Installed
    }

    companion object {
        private const val INSTALL_TIMEOUT_SECONDS = 600L
        private val installMutex = Mutex()
    }
}
