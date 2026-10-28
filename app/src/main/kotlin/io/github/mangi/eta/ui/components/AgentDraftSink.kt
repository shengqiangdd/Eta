package io.github.mangi.eta.ui.components

import androidx.compose.runtime.compositionLocalOf

/**
 * 输入草稿出口。
 *
 * 从输入框一路透传到 AgentChatBody 会穿过四层签名（含两个近似重载），成本高且容易漏；
 * 这里用 CompositionLocal 让输入框直接写回会话状态。默认 null：预览与测试里不产生副作用。
 */
internal val LocalAgentDraftSink = compositionLocalOf<((String) -> Unit)?> { null }
