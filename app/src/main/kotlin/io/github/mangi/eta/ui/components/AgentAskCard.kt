package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.AgentAskUi
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 待回答卡片：问题原文 + 可点选的候选答案。
 *
 * 为什么把选项画成按钮：模型提问时给出的候选项，如果只能靠用户手打一遍，等于把结构化信息又退化成了文本；
 * 点一下就把该选项作为下一条消息发出，和手打完全同一条路径（运行中会走补充指令）。
 *
 * 点过一次后本卡片锁定，避免网络慢时连点发出两条同样的回答。
 */
@Composable
internal fun AgentAskCard(
    ask: AgentAskUi,
    onAnswer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var answered by remember(ask.messageId) { mutableStateOf<String?>(null) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            text = stringResource(R.string.ask_card_title),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.primary,
        )
        Text(
            text = ask.question,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
        val chosen = answered
        if (chosen != null) {
            Text(
                text = stringResource(R.string.ask_card_option_sent, chosen),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else if (ask.options.isEmpty()) {
            Text(
                text = stringResource(R.string.ask_card_reply_hint),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            ask.options.forEach { option ->
                AskOptionRow(
                    option = option,
                    onClick = {
                        answered = option
                        onAnswer(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun AskOptionRow(option: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surface)
            .border(1.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = option,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
