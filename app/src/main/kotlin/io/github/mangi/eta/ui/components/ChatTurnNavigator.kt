package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
 * 贴在消息区右侧的回合导航条。
 *
 * 位置刻意避开右下角的「回到底部」：那个按钮属于「我在跟最新内容」，这个是「我要去别处」，
 * 两者语义相反，挤在一起容易点错。
 */
@Composable
internal fun ChatTurnNavigator(
    /** 当前视口所属的回合（0 基）；null 表示还停在第一个提问之前。 */
    currentTurn: Int?,
    total: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (total <= 1) return
    val current = currentTurn?.coerceIn(0, total - 1)
    Column(
        modifier = modifier
            .widthIn(min = 40.dp)
            .background(
                color = MiuixTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(20.dp),
            )
            .padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TurnStepButton(
            icon = Icons.Rounded.KeyboardArrowUp,
            contentDescription = stringResource(R.string.chat_turn_previous),
            enabled = current != null && current > 0,
            onClick = onPrevious,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                // 还没进入任何回合时显示「—/N」而不是「1/N」：后者会让用户以为自己正看着
                // 第 1 回合，实际那一屏还在开场卡片上。
                text = if (current == null) "—/$total" else "${current + 1}/$total",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                maxLines = 1,
            )
        }
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
            .size(32.dp)
            .clip(RoundedCornerShape(16.dp))
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
