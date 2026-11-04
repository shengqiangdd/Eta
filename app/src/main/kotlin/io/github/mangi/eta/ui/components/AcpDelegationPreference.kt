package io.github.mangi.eta.ui.components

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
import io.github.mangi.eta.agent.acp.AcpDelegationMode
import io.github.mangi.eta.config.Prefs

/**
 * 「复杂编码任务怎么处理」的选择器。
 *
 * 三档是互斥策略，不是三个开关：
 * - 关闭：一律自己改
 * - 先问再做（默认）：够复杂就问一句
 * - 直接委派：够复杂直接派，不打断
 *
 * 用单选而不是开关，因为「关闭 + 自动」这种组合没有意义，
 * 而且两个开关要用户自己推出优先级，比直接三选一更难理解。
 *
 * 写入 [Prefs.Keys.AGENT_ACP_DELEGATION_MODE]；只写本地 Agent 配置，
 * 这个键不进 RemotePreferences（它只影响请求时的提示词，与 Hook 无关）。
 */
@Composable
internal fun AcpDelegationPreference(
    prefs: SharedPreferences?,
    title: String,
    icon: ImageVector,
    iconTint: Color,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val enabled = prefs != null
    var current by remember(prefs) { mutableStateOf(readMode(prefs)) }
    var showDialog by remember { mutableStateOf(false) }
    DisposableEffect(prefs) {
        val target = prefs ?: return@DisposableEffect onDispose { }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == Prefs.Keys.AGENT_ACP_DELEGATION_MODE) current = readMode(changed)
        }
        target.registerOnSharedPreferenceChangeListener(listener)
        onDispose { target.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    EtaPreference(
        title = title,
        summary = stringResource(modeSummaryRes(current)),
        startAction = { EtaPreferenceIcon(icon = icon, enabled = enabled, tint = iconTint) },
        enabled = enabled,
        onClick = if (enabled) ({ showDialog = true }) else null,
    )
    if (showDialog) {
        EtaWindowDialog(
            show = true,
            title = title,
            summary = stringResource(R.string.settings_acp_delegation_summary),
            onDismissRequest = { showDialog = false },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                AcpDelegationMode.entries.forEach { mode ->
                    EtaRadioButtonPreference(
                        title = stringResource(modeTitleRes(mode)),
                        summary = stringResource(modeDetailRes(mode)),
                        selected = current == mode,
                        onClick = {
                            showDialog = false
                            val target = prefs ?: return@EtaRadioButtonPreference
                            // 同步写并检查结果：KTX 的 edit { } 不返回成败，
                            // 写失败时会静默当成功（用户选完档位却什么都没变）。
                            @Suppress("UseKtx")
                            val ok = runCatching {
                                target.edit()
                                    .putString(Prefs.Keys.AGENT_ACP_DELEGATION_MODE, mode.wire)
                                    .commit()
                            }.getOrDefault(false)
                            if (ok) {
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

private fun readMode(prefs: SharedPreferences?): AcpDelegationMode {
    if (prefs == null) return AcpDelegationMode.ASK
    val stored = runCatching {
        prefs.getString(Prefs.Keys.AGENT_ACP_DELEGATION_MODE, null)
    }.getOrNull()
    return AcpDelegationMode.fromWire(stored)
}

private fun modeTitleRes(mode: AcpDelegationMode): Int = when (mode) {
    AcpDelegationMode.OFF -> R.string.acp_delegation_off_title
    AcpDelegationMode.ASK -> R.string.acp_delegation_ask_title
    AcpDelegationMode.AUTO -> R.string.acp_delegation_auto_title
}

/** 收起行的简短说明：说清「够复杂时会发生什么」。 */
private fun modeSummaryRes(mode: AcpDelegationMode): Int = when (mode) {
    AcpDelegationMode.OFF -> R.string.acp_delegation_off_summary
    AcpDelegationMode.ASK -> R.string.acp_delegation_ask_summary
    AcpDelegationMode.AUTO -> R.string.acp_delegation_auto_summary
}

/** 弹窗里的详细说明：给出具体判据，避免用户选完仍然不知道边界在哪。 */
private fun modeDetailRes(mode: AcpDelegationMode): Int = when (mode) {
    AcpDelegationMode.OFF -> R.string.acp_delegation_off_detail
    AcpDelegationMode.ASK -> R.string.acp_delegation_ask_detail
    AcpDelegationMode.AUTO -> R.string.acp_delegation_auto_detail
}
