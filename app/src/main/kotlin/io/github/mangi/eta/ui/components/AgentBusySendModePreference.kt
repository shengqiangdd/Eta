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
import io.github.mangi.eta.agent.model.AgentBusySendMode
import io.github.mangi.eta.config.Prefs

/**
 * 繁忙时（运行中）发送行为：排队发送 / 插话发送。
 *
 * 对齐 dsh 的「繁忙时的发送行为」设置：dsh 用 `BusyEnterBehavior` 决定运行时
 * Enter 与发送按钮走哪条路，Eta 没有键盘提交，只作用于运行中的发送按钮。
 *
 * 默认排队：用户点发送的直觉是「先记下」，打断当前回合必须是显式选择。
 */
@Composable
internal fun AgentBusySendModePreference(
    prefs: SharedPreferences?,
    title: String,
    icon: ImageVector,
    iconTint: Color,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val enabled = prefs != null
    var current by remember(prefs) { mutableStateOf(readBusySendMode(prefs)) }
    var showDialog by remember { mutableStateOf(false) }
    DisposableEffect(prefs) {
        val target = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == Prefs.Keys.AGENT_BUSY_SEND_MODE) {
                current = readBusySendMode(changed)
            }
        }
        target.registerOnSharedPreferenceChangeListener(listener)
        onDispose { target.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    EtaPreference(
        title = title,
        summary = stringResource(
            R.string.preference_value_separator_format,
            stringResource(busySendModeTitleRes(current)),
            stringResource(busySendModeSummaryRes(current)),
        ),
        startAction = { EtaPreferenceIcon(icon = icon, enabled = enabled, tint = iconTint) },
        enabled = enabled,
        onClick = if (enabled) ({ showDialog = true }) else null,
    )
    if (showDialog) {
        EtaWindowDialog(
            show = true,
            title = title,
            summary = stringResource(R.string.settings_busy_send_summary),
            onDismissRequest = { showDialog = false },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                AgentBusySendMode.values().forEach { mode ->
                    EtaRadioButtonPreference(
                        title = stringResource(busySendModeTitleRes(mode)),
                        summary = stringResource(busySendModeSummaryRes(mode)),
                        selected = current == mode,
                        onClick = {
                            showDialog = false
                            val target = prefs ?: return@EtaRadioButtonPreference
                            val written = writeBusySendMode(target, mode)
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

/** 写设置并返回是否成功。 */
// lint 建议用 KTX 的 edit {}，但它不返回 commit 结果；
// 这里必须知道写失败才能提示用户（同 AgentModePreference）。
@SuppressLint("UseKtx")
private fun writeBusySendMode(prefs: SharedPreferences, mode: AgentBusySendMode): Boolean =
    runCatching {
        prefs.edit().putString(Prefs.Keys.AGENT_BUSY_SEND_MODE, mode.id).commit()
    }.getOrDefault(false)

/** 读取当前设置；没有值或未知值都回退默认（队列），与 `AgentBusySendMode.fromId` 同一规则。 */
private fun readBusySendMode(prefs: SharedPreferences?): AgentBusySendMode {
    val stored = runCatching { prefs?.getString(Prefs.Keys.AGENT_BUSY_SEND_MODE, null) }.getOrNull()
    return AgentBusySendMode.fromId(stored)
}

private fun busySendModeTitleRes(mode: AgentBusySendMode): Int = when (mode) {
    AgentBusySendMode.QUEUE -> R.string.agent_busy_send_queue_title
    AgentBusySendMode.STEER -> R.string.agent_busy_send_steer_title
}

private fun busySendModeSummaryRes(mode: AgentBusySendMode): Int = when (mode) {
    AgentBusySendMode.QUEUE -> R.string.agent_busy_send_queue_summary
    AgentBusySendMode.STEER -> R.string.agent_busy_send_steer_summary
}
