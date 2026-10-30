package io.github.mangi.eta.ui.components

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentTranscriptViewMode
import io.github.mangi.eta.config.Prefs

/**
 * 工作细节档位：紧凑／标准／详细（默认）／完整。
 *
 * 对齐 dsh 的 `transcriptView`（四档组合见 AgentTranscriptViewMode 的注释）。与「折叠时机」
 * 是两个独立开关：这一项决定「看多少」，那一项决定「什么时候收」—— dsh 也是分开存的。
 */
@Composable
internal fun AgentTranscriptViewPreference(
    prefs: SharedPreferences?,
    title: String,
    icon: ImageVector,
    iconTint: Color,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val enabled = prefs != null
    var current by remember(prefs) { mutableStateOf(readTranscriptViewMode(prefs)) }
    var showDialog by remember { mutableStateOf(false) }
    DisposableEffect(prefs) {
        val target = prefs ?: return@DisposableEffect onDispose { }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == Prefs.Keys.AGENT_TRANSCRIPT_VIEW) {
                current = readTranscriptViewMode(changed)
            }
        }
        target.registerOnSharedPreferenceChangeListener(listener)
        onDispose { target.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    EtaPreference(
        title = title,
        summary = stringResource(
            R.string.preference_value_separator_format,
            stringResource(transcriptViewTitleRes(current)),
            stringResource(transcriptViewSummaryRes(current)),
        ),
        startAction = { EtaPreferenceIcon(icon = icon, enabled = enabled, tint = iconTint) },
        enabled = enabled,
        onClick = if (enabled) ({ showDialog = true }) else null,
    )
    if (showDialog) {
        EtaWindowDialog(
            show = true,
            title = title,
            summary = stringResource(R.string.settings_transcript_view_dialog_summary),
            onDismissRequest = { showDialog = false },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                AgentTranscriptViewMode.values().forEach { mode ->
                    EtaRadioButtonPreference(
                        title = stringResource(transcriptViewTitleRes(mode)),
                        summary = stringResource(transcriptViewSummaryRes(mode)),
                        selected = current == mode,
                        onClick = {
                            showDialog = false
                            val target = prefs ?: return@EtaRadioButtonPreference
                            val written = writeTranscriptViewMode(target, mode)
                            if (written) {
                                current = mode
                            } else {
                                Toast.makeText(
                                    context.applicationContext,
                                    resources.getString(R.string.settings_write_failed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * 聊天页读当前档位，并在设置改动后即时生效。
 *
 * 与 [rememberAgentWorkCollapseTiming] 同一条路子：监听 prefs 而不是依赖重组时机 ——
 * 设置页与聊天页是两个路由，返回聊天页时组件不会自动重读设置。
 */
@Composable
internal fun rememberAgentTranscriptViewMode(): AgentTranscriptViewMode {
    val context = LocalContext.current
    val prefs = remember(context) { Prefs.localAgentPreferences(context) }
    var current by remember(prefs) {
        mutableStateOf(AgentTranscriptViewMode.fromId(Prefs.getLocalAgentString(Prefs.Keys.AGENT_TRANSCRIPT_VIEW)))
    }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Prefs.Keys.AGENT_TRANSCRIPT_VIEW) {
                current = AgentTranscriptViewMode.fromId(
                    prefs.getString(Prefs.Keys.AGENT_TRANSCRIPT_VIEW, null),
                )
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return current
}

/** 写设置并返回是否成功。 */
// lint 建议用 KTX 的 edit {}，但它不返回 commit 结果；
// 这里必须知道写失败才能提示用户（同 AgentWorkCollapseTimingPreference）。
@SuppressLint("UseKtx")
private fun writeTranscriptViewMode(prefs: SharedPreferences, mode: AgentTranscriptViewMode): Boolean =
    runCatching {
        prefs.edit().putString(Prefs.Keys.AGENT_TRANSCRIPT_VIEW, mode.id).commit()
    }.getOrDefault(false)

/** 读取当前设置；没有值或未知值都回退默认（detailed），与 `fromId` 同一规则。 */
private fun readTranscriptViewMode(prefs: SharedPreferences?): AgentTranscriptViewMode {
    val stored = runCatching { prefs?.getString(Prefs.Keys.AGENT_TRANSCRIPT_VIEW, null) }.getOrNull()
    return AgentTranscriptViewMode.fromId(stored)
}

private fun transcriptViewTitleRes(mode: AgentTranscriptViewMode): Int = when (mode) {
    AgentTranscriptViewMode.COMPACT -> R.string.agent_transcript_view_compact_title
    AgentTranscriptViewMode.STANDARD -> R.string.agent_transcript_view_standard_title
    AgentTranscriptViewMode.DETAILED -> R.string.agent_transcript_view_detailed_title
    AgentTranscriptViewMode.VERBOSE -> R.string.agent_transcript_view_verbose_title
}

private fun transcriptViewSummaryRes(mode: AgentTranscriptViewMode): Int = when (mode) {
    AgentTranscriptViewMode.COMPACT -> R.string.agent_transcript_view_compact_summary
    AgentTranscriptViewMode.STANDARD -> R.string.agent_transcript_view_standard_summary
    AgentTranscriptViewMode.DETAILED -> R.string.agent_transcript_view_detailed_summary
    AgentTranscriptViewMode.VERBOSE -> R.string.agent_transcript_view_verbose_summary
}
