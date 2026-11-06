package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 长会话里的「回合」导航。
 *
 * 为什么需要：真机上一个会话能有八千多条消息、几次到几十次提问，中间隔着成百上千条思考与工具调用。
 * 「只看对话」能把过程收起来，但找回「上周问的那件事」仍要滑几十屏；会话内搜索要用户先想起关键词，
 * 而人常常只记得「大概是在中间某次」。
 *
 * 回合的唯一定义是一次**用户提问**：它之后的思考、工具、回答都属于这一回合，直到下一次提问。
 * 所以锚点只取 [UserMessageUi] —— 系统通知、助手正文都不是回合起点，拿它们当锚点会跳到半途。
 */

/** 时间线里每次提问的消息 id，顺序与界面一致。 */
internal fun chatTurnAnchorIds(messages: List<AgentChatMessageUi>): List<String> =
    messages.filterIsInstance<UserMessageUi>().map { it.id }

/**
 * 每个时间线条目属于第几个回合（0 基）；第一个提问之前的条目记为 -1。
 *
 * [starts] 与时间线条目一一对应，`true` 表示该条目是回合起点（用户提问）。
 * 之所以按「条目」而不是按「消息」算：过程消息会被折叠进同一个工作过程块，块内多条消息
 * 在列表里只占一个 index，按消息算会让 index 与滚动位置对不上。
 */
internal fun turnIndexByEntryStarts(starts: List<Boolean>): List<Int> {
    var turn = -1
    return starts.map { isStart ->
        if (isStart) turn += 1
        turn
    }
}

/**
 * 当前视口顶部那一项所属的回合。
 *
 * 顶部项可能是某个回合中间的工作过程块，也可能落在第一个提问之前（开场条目）；
 * 后者往前找不到回合，返回 null，由调用方决定显示什么。
 *
 * [atEnd] 表示列表已经滚到最后（不能再往下）：此时视口**下半部分**才是最新一回合，
 * 只看顶部会把「已经在最新消息上」报成上一回合 —— 用户点「回到底部」后看到 19/20
 * 会以为自己没到最新。
 */
internal fun currentTurnIndex(
    entryTurns: List<Int>,
    firstVisibleItemIndex: Int,
    atEnd: Boolean = false,
): Int? {
    if (entryTurns.isEmpty()) return null
    if (atEnd) {
        for (index in entryTurns.lastIndex downTo 0) {
            if (entryTurns[index] >= 0) return entryTurns[index]
        }
        return null
    }
    val start = firstVisibleItemIndex.coerceIn(0, entryTurns.lastIndex)
    for (index in start downTo 0) {
        if (entryTurns[index] >= 0) return entryTurns[index]
    }
    return null
}

/**
 * 上一个 / 下一个回合的目标下标；越界返回 null。
 *
 * [currentIndex] 为 null 表示视口还停在第一个提问之前（开场卡片、系统通知那一屏）：
 * 此时「上一个」没有目标，而「下一个」应当正好落到第 1 回合 —— 不能拿「最新回合」当基准，
 * 否则会从开场直接跳到第 2 回合，第一回合被跳过且用户看不出来。
 *
 * 这里**不循环**：搜索命中很多，循环能让人一直往下看；回合只有几十个，从第一个「上一个」
 * 跳到最后会让人以为自己跳成功了，实际上跨过了整段对话。
 */
internal fun moveTurnIndex(currentIndex: Int?, total: Int, step: Int): Int? {
    if (total <= 0) return null
    if (currentIndex == null) return if (step > 0) 0 else null
    return (currentIndex + step).takeIf { it in 0 until total }
}


/**
 * 回合导航条的尺寸。
 *
 * 这条导航原先贴在消息区右侧，列表必须为它让出 44dp 宽的槽位：
 * 本机 347dp 宽的屏幕上，每一行正文因此少 13%，用户直接指出「多轮消息时消息内容被挤掉一块」。
 * 现在它改到输入框上方，只占高度、不占宽度 —— 尺寸仍只在这里定义一次。
 */
internal object ChatTurnStripMetrics {
    /** 单个方向按钮的点击区（也是胶囊高度的组成部分）。 */
    val StepButtonSize = 30.dp

    /** 胶囊上下内边距。 */
    val VerticalPadding = 2.dp

    /** 胶囊总高度；bottomBar 会按它给列表留出可滚动的余量。 */
    val Height = StepButtonSize + VerticalPadding * 2

    /** 计数器左右留白：数字与箭头贴在一起会被读成「3/12↑」。 */
    val LabelHorizontalPadding = 4.dp
}

/**
 * 是否显示回合导航条。
 *
 * 判据只剩这一处：上一版的「画不画」和「列表让多少」是两个地方各写一遍，
 * 于是同时出现过「没画导航但每行都窄一截」和「画了却压住正文」。
 * 现在它只影响输入框上方多一行，列表宽度完全与之无关。
 */
internal fun chatTurnStripVisible(turnNavigationEnabled: Boolean, total: Int): Boolean =
    turnNavigationEnabled && total > 1

/**
 * 计数器文案。
 *
 * [currentTurn] 为 null 表示视口还停在第一个提问之前（开场卡片、系统通知那一屏）：
 * 显示「—/N」而不是「1/N」，后者会让用户以为自己正看着第 1 回合。
 */
internal fun chatTurnStripLabel(currentTurn: Int?, total: Int): String {
    if (total <= 0) return "—/0"
    val current = currentTurn?.coerceIn(0, total - 1)
    return if (current == null) "—/$total" else "${current + 1}/$total"
}

/**
 * 输入框上方的回合导航条。
 *
 * 为什么不再浮在消息区右侧：那个位置要么压住正文，要么让列表让出 44dp 宽的槽位，
 * 而「让出宽度」在手机上等于每一行都变短。挪到输入框上方后：
 * - 正文拿到整屏宽度；
 * - 只占一行高度，且由 Scaffold 的 bottomBar 统一计入 inset，列表能滚到它下面，
 *   不会出现「最后一行被盖住」；
 * - 位置靠近拇指，翻回合不用把手伸到屏幕中部。
 */
@Composable
internal fun ChatTurnStrip(
    /** 当前视口所属的回合（0 基）；null 表示还停在第一个提问之前。 */
    currentTurn: Int?,
    total: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (total <= 1) return
    val current = currentTurn?.coerceIn(0, total - 1)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(ChatTurnStripMetrics.Height / 2))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .padding(vertical = ChatTurnStripMetrics.VerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TurnStepButton(
            icon = Icons.Rounded.KeyboardArrowUp,
            contentDescription = stringResource(R.string.chat_turn_previous),
            enabled = current != null && current > 0,
            onClick = onPrevious,
        )
        Text(
            text = chatTurnStripLabel(current, total),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = ChatTurnStripMetrics.LabelHorizontalPadding),
        )
        TurnStepButton(
            icon = Icons.Rounded.KeyboardArrowDown,
            contentDescription = stringResource(R.string.chat_turn_next),
            enabled = current == null || current < total - 1,
            onClick = onNext,
        )
    }
}

@Composable
private fun TurnStepButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (enabled) {
        MiuixTheme.colorScheme.onSurface
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantActions.copy(alpha = 0.38f)
    }
    Box(
        modifier = Modifier
            .size(ChatTurnStripMetrics.StepButtonSize)
            .clip(RoundedCornerShape(ChatTurnStripMetrics.StepButtonSize / 2))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
            tint = tint,
        )
    }
}
