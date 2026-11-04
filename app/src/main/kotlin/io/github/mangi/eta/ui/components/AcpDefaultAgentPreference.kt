package io.github.mangi.eta.ui.components

import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.github.mangi.eta.agent.acp.AcpClient
import io.github.mangi.eta.config.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 默认把复杂编码任务派给哪个代理。
 *
 * 选项来自 [AcpDelegationAgents]（与 `BUILTIN_AGENTS` 同一份清单），
 * 不在这里另写一份字符串 —— 加代理时漏改这里，用户就会看到两个名字指同一个东西。
 *
 * 允许留空（「自动」）：装了哪个就用哪个。给一个明确的「自动」选项而不是
 * 「不选就等于第一个可用」，是因为后者在装了第二个代理之后会**悄悄换掉**用户一直用的那个。
 */
@Composable
internal fun AcpDefaultAgentPreference(
    prefs: SharedPreferences?,
    title: String,
    icon: ImageVector,
    iconTint: Color,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val enabled = prefs != null
    var current by remember(prefs) { mutableStateOf(readAgent(prefs)) }
    var showDialog by remember { mutableStateOf(false) }
    DisposableEffect(prefs) {
        val target = prefs ?: return@DisposableEffect onDispose { }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == Prefs.Keys.AGENT_ACP_DEFAULT_AGENT) current = readAgent(changed)
        }
        target.registerOnSharedPreferenceChangeListener(listener)
        onDispose { target.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val currentLabel = AcpDelegationAgents.entries
        .firstOrNull { it.id == current }
        ?.let { stringResource(it.titleRes) }
        ?: stringResource(R.string.acp_agent_auto_title)

    EtaPreference(
        title = title,
        summary = currentLabel,
        startAction = { EtaPreferenceIcon(icon = icon, enabled = enabled, tint = iconTint) },
        enabled = enabled,
        onClick = if (enabled) ({ showDialog = true }) else null,
    )
    if (showDialog) {
        EtaWindowDialog(
            show = true,
            title = title,
            summary = stringResource(R.string.settings_acp_default_agent_summary),
            onDismissRequest = { showDialog = false },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // 先给「自动」，再给具体代理：默认项放最上面，符合从上往下读的习惯。
                EtaRadioButtonPreference(
                    title = stringResource(R.string.acp_agent_auto_title),
                    summary = stringResource(R.string.acp_agent_auto_summary),
                    selected = current.isEmpty(),
                    onClick = {
                        showDialog = false
                        write(prefs)?.let { ok ->
                            if (ok) current = "" else toast(context, resources)
                        }
                    },
                )
                AcpDelegationAgents.entries.forEach { agent ->
                    EtaRadioButtonPreference(
                        title = stringResource(agent.titleRes),
                        summary = stringResource(agent.summaryRes),
                        selected = current == agent.id,
                        onClick = {
                            showDialog = false
                            write(prefs, agent.id)?.let { ok ->
                                if (ok) current = agent.id else toast(context, resources)
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * 同步写入并汇报结果。
 *
 * 用 `commit()` 而不是 KTX 的 `edit { }`（默认异步 apply）：KTX 版本不返回成功与否，
 * 而这里恰恰要判断「值有没有真的写进去」，写失败要提示用户，不能默默当成功。
 * 因此抑制 UseKtx，而不是换成异步写法。
 */
@Suppress("UseKtx")
private fun write(prefs: SharedPreferences?, value: String = ""): Boolean? {
    val target = prefs ?: return null
    return runCatching {
        target.edit().putString(Prefs.Keys.AGENT_ACP_DEFAULT_AGENT, value).commit()
    }.getOrDefault(false)
}

private fun toast(context: android.content.Context, resources: android.content.res.Resources) {
    Toast.makeText(
        context.applicationContext,
        resources.getString(R.string.settings_write_failed),
        Toast.LENGTH_SHORT,
    ).show()
}

private fun readAgent(prefs: SharedPreferences?): String {
    if (prefs == null) return ""
    return runCatching {
        prefs.getString(Prefs.Keys.AGENT_ACP_DEFAULT_AGENT, null)
    }.getOrNull().orEmpty()
}
