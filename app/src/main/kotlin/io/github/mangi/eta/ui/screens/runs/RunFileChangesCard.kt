package io.github.mangi.eta.ui.screens.runs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.AgentRunFileChanges
import io.github.mangi.eta.agent.runtime.RunFileChange
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.FilePreviewDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 运行详情里的「文件变更」：这次运行由工具改过哪些文件、各增删多少行。
 *
 * 参考 dsh 的 workspace-changes，但边界更窄（见 [AgentRunFileChanges] 的注释）：
 * 只统计 `write_file` / `edit_file`，shell 重定向与其他应用写的不在账本里，
 * 所以卡片底部把这条边界直接写给用户看，避免把「账本为空」误读成「没改过任何文件」。
 */
@Composable
internal fun RunFileChangesCard(runId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) { AgentRunFileChanges.forApp(context) }
    var refresh by remember(runId) { mutableIntStateOf(0) }
    val changes by produceState(emptyList<RunFileChange>(), runId, refresh) {
        value = withContext(Dispatchers.IO) { store.load(runId) }
    }
    if (changes.isEmpty()) return
    val clipboard = LocalClipboardManager.current
    var copied by remember(runId) { mutableStateOf(false) }
    var expandedPath by rememberSaveable(runId) { mutableStateOf<String?>(null) }
    var previewPath by remember(runId) { mutableStateOf<String?>(null) }
    val added = changes.sumOf { it.added }
    val removed = changes.sumOf { it.removed }
    EtaCard(
        modifier = modifier,
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = stringResource(R.string.run_files_title), fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            Text(
                text = pluralStringResource(R.plurals.run_files_count, changes.size, changes.size, added, removed),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.weight(1f))
            EtaTextButton(
                text = if (copied) stringResource(R.string.run_files_copied) else stringResource(R.string.run_files_copy_digest),
                onClick = {
                    clipboard.setText(AnnotatedString(AgentRunFileChanges.digest(changes)))
                    copied = true
                },
            )
        }
        changes.forEach { change ->
            val expanded = expandedPath == change.path
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clickable { expandedPath = if (expanded) null else change.path },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = change.path.substringAfterLast('/'),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "+${change.added}/-${change.removed}" +
                            if (change.count > 1) {
                                pluralStringResource(R.plurals.run_files_repeat, change.count, change.count)
                            } else {
                                ""
                            },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.width(6.dp))
                    // 只给行数不够：用户经常要确认「现在文件里到底是什么」。
                    EtaTextButton(text = stringResource(R.string.run_files_view), onClick = { previewPath = change.path })
                }
                Text(
                    text = change.path,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
                if (expanded && change.diff.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text = change.diff,
                            style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                        )
                    }
                }
                if (expanded && change.diff.isBlank()) {
                    Text(
                        text = stringResource(R.string.run_files_lines_only),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.run_files_scope_hint),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
    previewPath?.let { path ->
        FilePreviewDialog(path = path, onDismiss = { previewPath = null })
    }
}
