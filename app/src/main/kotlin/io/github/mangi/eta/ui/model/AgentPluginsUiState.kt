package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

/**
 * 插件管理页状态。
 *
 * 为什么要专门做一页：插件能改的东西（MCP 服务器、ACP 代理、提示词）都是"注入到 Agent 里"的，
 * 装完之后在界面上**完全看不见**——用户既不知道自己装了什么，也没法停用出问题的插件，
 * 只能去翻 `files/plugins` 目录。这一页把"装了什么 / 开了没 / 它给我加了什么"摊开。
 */
@Immutable
data class AgentPluginsUiState(
    val plugins: List<PluginItemUi> = emptyList(),
    val isLoading: Boolean = false,
    val isImporting: Boolean = false,
    /** 链接下载/解析进行中（与「本地导入」分开：一个在等文件选择，一个在等网络）。 */
    val isFetchingLink: Boolean = false,
    /** 下载完成、等用户确认的预览；确认后才会真的写进插件目录。 */
    val linkPreview: PluginLinkPreviewUi? = null,
    val busyPluginId: String? = null,
    /** 正在对照来源链接检查更新（每个插件都要下载一次整包，所以是显式动作，不自动跑）。 */
    val isCheckingUpdates: Boolean = false,
    /** 上一次检查更新的结果，按插件 id 索引；行内「可更新 vX」从这里来。 */
    val updateStatuses: Map<String, PluginUpdateStatusUi> = emptyMap(),
    val notice: PluginNoticeUi? = null,
) {
    val isBusy: Boolean
        get() = isImporting || isFetchingLink || busyPluginId != null || linkPreview != null ||
            isCheckingUpdates
}

/**
 * 从链接安装的预览。
 *
 * 之所以不"下载完直接装"：链接是外部内容，装进去的插件会往 Agent 里注入
 * MCP 服务器与提示词。把名称、版本、渠道、来源 host、SHA-256 摊开让用户确认一次，
 * 才有机会发现"这不是我要的那个插件"。
 */
@Immutable
data class PluginLinkPreviewUi(
    val token: String,
    val url: String,
    val host: String,
    val secure: Boolean,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val toolCount: Int,
    val mcpCount: Int,
    val acpCount: Int,
    val hasPrompt: Boolean,
    val bytes: Long,
    val sha256: String,
    /** 非空表示这是「从来源更新」：确认后覆盖该插件，而不是新装一个。 */
    val updateTargetId: String? = null,
    val currentVersion: String = "",
) {
    val executableChannelCount: Int
        get() = mcpCount + acpCount
    val isUpdate: Boolean get() = updateTargetId != null
}

/** 一条可回滚的历史留底：详情里既要显示版本号，也要显示「备份于何时」。 */
@Immutable
data class PluginRollbackUi(
    val version: String,
    val backedUpAtMillis: Long,
)

@Immutable
data class PluginItemUi(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val enabled: Boolean,
    val toolCount: Int,
    val mcpServers: List<String>,
    val acpAgents: List<String>,
    val hasPrompt: Boolean,
    /** 「从哪来」的人话描述（链接主机 / 本地文件），没有记录时为 null。 */
    val sourceLabel: String? = null,
    /** 来源是链接时才能「从来源更新」。 */
    val canUpdateFromSource: Boolean = false,
    /** 来源记录里的安装时间（毫秒）；没有来源记录时为 0。 */
    val installedAtMillis: Long = 0L,
    /** 来源记录里的包摘要；本地导入或旧记录可能为空。 */
    val sha256: String = "",
    /** 来源记录的完整地址（链接是 URL，本地导入是路径）；没有记录时为空。 */
    val sourceLocation: String = "",
    /** 链接安装的来源主机；详情里用它，避免和「来源」标签叠成「来源：来源：…」。 */
    val sourceHost: String = "",
    /** 可回滚的历史版本（新的在前）；没有留底、或留底就是当前版本时为空。 */
    val rollbackVersions: List<PluginRollbackUi> = emptyList(),
    /** 声明性工具名。行摘要只给数量，详情里要能看出具体是哪几个。 */
    val toolNames: List<String> = emptyList(),
) {
    /**
     * 这个插件实际能给 Agent 增加的通道数。
     *
     * 只算 MCP 与 ACP：manifest 里的 `tools` 只是**能力声明**，没有执行器
     * （真正能跑的是同一插件声明的 MCP 工具与 ACP 代理）。把它算进来会让
     * "装了插件但什么也没多出来"看起来像装成功了。
     */
    val executableChannelCount: Int
        get() = mcpServers.size + acpAgents.size
}

@Immutable
data class PluginUpdateStatusUi(
    val pluginId: String,
    val name: String,
    val currentVersion: String,
    /** 远端解析出来的版本；检查失败时为空。 */
    val remoteVersion: String,
    val state: State,
    /** 补充说明：失败原因、来源指向别的插件、版本格式无法比较等。 */
    val detail: String? = null,
) {
    /**
     * 为什么四种结论要分开：把「来源被回退」和「检查失败」都说成「已是最新」，
     * 用户会以为插件作者没更新，实际上一个该换来源、一个该重试。
     */
    enum class State {
        /** 远端更新（或格式不同到需人工判断）：可以点「从来源更新」。 */
        UPDATE_AVAILABLE,
        UP_TO_DATE,

        /** 远端比已装旧：来源被回退了，点更新只会降级。 */
        OLDER,
        FAILED,
    }
}

@Immutable
data class PluginNoticeUi(
    val id: Long,
    val title: String,
    val message: String,
    val isError: Boolean,
)
