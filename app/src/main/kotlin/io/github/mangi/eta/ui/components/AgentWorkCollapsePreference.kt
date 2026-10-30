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
import io.github.mangi.eta.agent.model.AgentWorkCollapseTiming
import io.github.mangi.eta.config.Prefs

/**
 * 回合结束后工作过程什么时候收起：回合结束（默认）／下一条消息发出时。
 *
 * 对齐 dsh 的 `collapseTiming`：dsh 把它做成独立于「工作细节档位」的第二个开关，
 * 因为「看多少细节」与「什么时候收」是两件事 —— 想看细节但不想手动点开，
 * 就该让结束的那一轮保持展开到下一句话。
 */
@Composable
internal fun AgentWorkCollapseTimingPreference(
    prefs: SharedPreferences?,
    title: String,
    icon: ImageVector,
    iconTint: Color,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val enabled = prefs != null
    var current by remember(prefs) { mutableStateOf(readCollapseTiming(prefs)) }
    var showDialog by remember { mutableStateOf(false) }
    DisposableEffect(prefs) {
        val target = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == Prefs.Keys.AGENT_WORK_COLLAPSE_TIMING) {
                current = readCollapseTiming(changed)
            }
        }
        target.registerOnSharedPreferenceChangeListener(listener)
        onDispose { target.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    EtaPreference(
        title = title,
        summary = stringResource(
            R.string.preference_value_separator_format,
            stringResource(collapseTimingTitleRes(current)),
            stringResource(collapseTimingSummaryRes(current)),
        ),
        startAction = { EtaPreferenceIcon(icon = icon, enabled = enabled, tint = iconTint) },
        enabled = enabled,
        onClick = if (enabled) ({ showDialog = true }) else null,
    )
    if (showDialog) {
        EtaWindowDialog(
            show = true,
            title = title,
            summary = stringResource(R.string.settings_work_collapse_summary),
            onDismissRequest = { showDialog = false },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                AgentWorkCollapseTiming.values().forEach { timing ->
                    EtaRadioButtonPreference(
                        title = stringResource(collapseTimingTitleRes(timing)),
                        summary = stringResource(collapseTimingSummaryRes(timing)),
                        selected = current == timing,
                        onClick = {
                            showDialog = false
                            val target = prefs ?: return@EtaRadioButtonPreference
                            val written = writeCollapseTiming(target, timing)
                            if (written) {
                                current = timing
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
 * 聊天页读当前折叠时机，并在设置改动后即时生效。
 *
 * 单独抽出来是因为它只影响「收不收」，与消息投影无关；放进 CompositionLocal 或层层
 * 加参数都会把一次设置读取扩散到与显示无关的签名里。
 */
@Composable
internal fun rememberAgentWorkCollapseTiming(): AgentWorkCollapseTiming {
    val context = LocalContext.current
    val prefs = remember(context) { Prefs.localAgentPreferences(context) }
    var current by remember(prefs) {
        mutableStateOf(
            AgentWorkCollapseTiming.fromId(Prefs.getLocalAgentString(Prefs.Keys.AGENT_WORK_COLLAPSE_TIMING)),
        )
    }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Prefs.Keys.AGENT_WORK_COLLAPSE_TIMING) {
                current = AgentWorkCollapseTiming.fromId(
                    prefs.getString(Prefs.Keys.AGENT_WORK_COLLAPSE_TIMING, null),
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
// 这里必须知道写失败才能提示用户（同 AgentBusySendModePreference）。
@SuppressLint("UseKtx")
private fun writeCollapseTiming(prefs: SharedPreferences, timing: AgentWorkCollapseTiming): Boolean =
    runCatching {
        prefs.edit().putString(Prefs.Keys.AGENT_WORK_COLLAPSE_TIMING, timing.id).commit()
    }.getOrDefault(false)

/** 读取当前设置；没有值或未知值都回退默认（结束即收），与 `AgentWorkCollapseTiming.fromId` 同一规则。 */
private fun readCollapseTiming(prefs: SharedPreferences?): AgentWorkCollapseTiming {
    val stored = runCatching { prefs?.getString(Prefs.Keys.AGENT_WORK_COLLAPSE_TIMING, null) }.getOrNull()
    return AgentWorkCollapseTiming.fromId(stored)
}

private fun collapseTimingTitleRes(timing: AgentWorkCollapseTiming): Int = when (timing) {
    AgentWorkCollapseTiming.COMPLETION -> R.string.agent_work_collapse_completion_title
    AgentWorkCollapseTiming.NEXT_INPUT -> R.string.agent_work_collapse_next_input_title
}

private fun collapseTimingSummaryRes(timing: AgentWorkCollapseTiming): Int = when (timing) {
    AgentWorkCollapseTiming.COMPLETION -> R.string.agent_work_collapse_completion_summary
    AgentWorkCollapseTiming.NEXT_INPUT -> R.string.agent_work_collapse_next_input_summary
}
