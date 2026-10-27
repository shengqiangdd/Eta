package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.components.RoleplayChatUi

@Immutable
internal data class AgentChatUiState(
    val messages: List<AgentChatMessageUi>,
    val history: List<AgentModelClient.ConversationMessage> = emptyList(),
    /**
     * `history`（模型上下文投影）没有载入内存；此时是空表，**不代表会话没有上下文**。
     *
     * 启动时只还原当前会话的 history：其它会话的 history 合计可能很大（实测 13 个会话
     * 约 900 万字符），而它们不参与任何模型请求。切到该会话/发送前再按需读取，
     * 未载入期间落盘侧不能把空表写回库。
     */
    val historyDeferred: Boolean = false,
    // 完整脱敏历史独立于模型投影；摘要替换 history 时不覆盖 journal。
    val journal: List<AgentModelClient.ConversationMessage> = emptyList(),
    /**
     * `journal` 因体积过大而没有载入内存（此时它就是空表，**不代表该会话没有历史**）。
     *
     * 为什么必须显式标记：`journal` 原本有个约定「空了就退回 history」
     * （见 AgentConversationPersistence 与 AgentConversationRevisionReducer）。
     * 如果只是把它清空，这个约定会把 history 当成 journal 写回数据库 / 用来切分上下文，
     * 等于用「被摘要压缩过的模型投影」覆盖「完整脱敏历史」——那是数据损坏，不是省内存。
     * 所以凡是把 journal 当作「可为空、空则回退」的地方，都必须先看这个标记。
     */
    val journalDeferred: Boolean = false,
    /**
     * 消息列表没有载入内存（启动时只载入当前会话）。
     *
     * 此时 [messages] 是空表，**不代表会话没有消息**。落盘侧看到这个标记就整段跳过消息写入，
     * 否则空表会把库里那段历史删掉 —— 与 [journalDeferred] 同一类风险。
     */
    val messagesDeferred: Boolean = false,
    /**
     * 内存里的 [messages] 从数据库 sort_index 的哪个位置开始；>0 表示更早的消息还在库里。
     * 只影响 UI 窗口，发送/持久化会按这个偏移只写尾部。
     */
    val messagesWindowStart: Int = 0,
    /** [messagesDeferred] 时为会话列表预览保留的最后一条消息；不参与会话内任何改写。 */
    val previewMessage: AgentChatMessageUi? = null,
    val input: String,
    val isStreaming: Boolean,
    /** run 已被用户暂停（当前步骤跑完后在检查点挂起）；仅运行中时才有意义。 */
    val isPaused: Boolean = false,
    val isCompacting: Boolean = false,
    val thinkingEnabled: Boolean,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.fromLegacy(thinkingEnabled),
    val availableReasoningEfforts: List<ReasoningEffort> = emptyList(),
    val pendingImages: List<PendingImageUi> = emptyList(),
    val pendingFileReferences: List<PendingFileReferenceUi> = emptyList(),
    val appliedRuntimeRunIds: List<String> = emptyList(),
    val messageEdit: MessageEditUiState? = null,
    val roleplay: RoleplayBinding? = null,
    val roleplayMessages: RoleplayMessageState = RoleplayMessageState(),
    /** 会话最近一次发送使用的本地 Model.id；null 表示尚未绑定，跟随默认模型。 */
    val modelId: String? = null,
    /** 是否置顶：列表排序优先，并单独分组。持久化在 `conversations.pinned`。 */
    val isPinned: Boolean = false,
) {
    val canCompactContext: Boolean get() = !isStreaming && messageEdit == null && history.any {
        !it.contextSummary && (it.role == "assistant" || it.role == "tool")
    }
}

@Immutable
sealed interface AgentChatMessageUi {
    val id: String
}

@Immutable
data class UserMessageUi(
    override val id: String,
    val content: String,
    val images: List<String> = emptyList(),
    val isEdited: Boolean = false,
) : AgentChatMessageUi

@Immutable
data class AgentMessageUi(
    override val id: String,
    val content: String,
    val isStreaming: Boolean = false,
    val renderMarkdown: Boolean = true,
    val usage: TokenUsageUi? = null,
    val characterEditable: Boolean = false,
    val candidateCount: Int = 1,
    val selectedCandidate: Int = 0,
) : AgentChatMessageUi

enum class SystemNoticeCode(val wireValue: String) {
    Stopped("stopped"),
    EmptyResult("empty_result"),
    RuntimeFailed("runtime_failed"),
    ModelRetry("model_retry"),
    ContextCompaction("context_compaction"),
    Interrupted("interrupted");

    companion object {
        fun fromWireValue(value: String): SystemNoticeCode? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

/** Eta 自己生成的消息只保存稳定状态码，展示时再按当前语言解析。 */
@Immutable
data class SystemNoticeMessageUi(
    override val id: String,
    val code: SystemNoticeCode,
    val detail: String? = null,
    val contextTokens: Int? = null,
    /** 仅运行期存在的进行中标记，不随消息持久化；恢复的历史通知始终视为已结束。 */
    val running: Boolean = false,
) : AgentChatMessageUi

@Immutable
data class TokenUsageUi(
    val contextTokens: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedTokens: Int? = null,
) {
    val isEmpty: Boolean
        get() = contextTokens == null &&
            inputTokens == null &&
            outputTokens == null &&
            reasoningTokens == null &&
            cachedTokens == null
}

@Immutable
data class ThinkingMessageUi(
    override val id: String,
    val content: String,
    val isStreaming: Boolean,
    val elapsedSeconds: Int? = null,
    val collapsed: Boolean = false,
) : AgentChatMessageUi

/**
 * 首页的 Run trace 入口卡片：展示 Agent 当前可调用的能力分组。
 */
@Immutable
data class RunTraceMessageUi(
    override val id: String,
    val capabilities: List<CapabilityUi>,
) : AgentChatMessageUi

@Immutable
data class CapabilityUi(
    val title: String,
    val items: List<String>,
)

/**
 * 工具调用摘要：出现在消息流中，显示当前/最近一步调用了哪些工具。
 */
@Immutable
data class ToolSummaryMessageUi(
    override val id: String,
    val tools: List<String>,
) : AgentChatMessageUi

@Immutable
data class ToolActivityMessageUi(
    override val id: String,
    val toolName: String,
    val status: ToolActivityStatusUi,
    val argumentsSummary: String,
    val command: String? = null,
    val resultSummary: String? = null,
    /** 可展开细节：编辑类工具的行号 + diff；其他工具为 null。 */
    val resultDetail: String? = null,
    /** 文件编辑类工具改的完整路径；用于直接打开只读预览。 */
    val filePath: String? = null,
    val elapsedMs: Long? = null,
    /**
     * 这条工具开始执行的**墙上时间**（毫秒）。
     *
     * 为什么不能只靠界面里的 remember：切换会话、进程重建都会让 Compose 的
     * rememberSaveable 丢掉起点，于是同一张卡片每次都从 0 开始数秒 ——
     * 用户看到的是「重新进入会话秒数又归零」，会以为任务重跑了。
     * 落盘后重启也能算出真实已运行时长。
     *
     * null = 旧数据（落盘前没有这一列），此时退回界面侧近似值。
     */
    val startedAtMs: Long? = null,
    val imageCount: Int = 0,
    /** 仅 ask_user：问题原文与候选答案；用于渲染「等你回答」卡片。 */
    val askQuestion: String? = null,
    val askOptions: List<String> = emptyList(),
    /**
     * 长时工具的执行中进度行（当前是 ACP 委派：外部代理正在做什么）。
     * 与 [resultSummary] 的分工见 [AgentRunMessageProjector] 的 updateToolProgress。
     */
    val progressLine: String? = null,
    /** 进度行的类别：tool_call / text / thought / failed；用于区分图标与语气。 */
    val progressKind: String? = null,
) : AgentChatMessageUi

enum class ToolActivityStatusUi {
    Running,
    Success,
    Failed,
    Unknown,
}

/**
 * 建议语 chip 行。
 */
@Immutable
data class SuggestionChipsMessageUi(
    override val id: String,
    val prompts: List<String>,
) : AgentChatMessageUi

@Immutable
data class PendingImageUi(
    val id: String,
    val uri: String,
    val dataUrl: String,
    val mimeType: String,
)

@Immutable
data class PendingFileReferenceUi(
    val id: String,
    val reference: AgentFileReference,
)

@Immutable
data class MessageEditUiState(
    val targetMessageId: String,
    val previousInput: String,
    val previousImages: List<PendingImageUi>,
    val previousFileReferences: List<PendingFileReferenceUi>,
    val hasLaterTurns: Boolean,
    val preserveFollowingMessages: Boolean = false,
)

/** 角色对话在界面上的身份投影；普通对话返回 null。 */
internal fun AgentChatHomeUiState.roleplayChatUi(): RoleplayChatUi? = roleplay?.let {
    RoleplayChatUi(characterName = it.characterName, avatarPath = it.avatarPath, userName = it.userName)
}
