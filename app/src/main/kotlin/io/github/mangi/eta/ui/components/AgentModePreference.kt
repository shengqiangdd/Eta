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
import io.github.mangi.eta.agent.model.AgentMode
import io.github.mangi.eta.config.Prefs

/**
 * Agent 模式选择（标准 / 计划 / 创造）。
 *
 * 为什么是单选而不是三个开关：模式之间互斥，而且「计划 + 创造」这种组合没有意义
 * （一个要求只读、一个要求写文件）。单选把不可能的组合直接排除掉。
 *
 * 写入两个键：新键 [Prefs.Keys.AGENT_MODE] 是事实源，
 * 旧键 [Prefs.Keys.AGENT_PLAN_MODE] 同步写，保证回退到旧版本 App 时行为一致。
 */
@Composable
internal fun AgentModePreference(
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
        val target = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == Prefs.Keys.AGENT_MODE || key == Prefs.Keys.AGENT_PLAN_MODE) {
                current = readMode(changed)
            }
        }
        target.registerOnSharedPreferenceChangeListener(listener)
        onDispose { target.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    EtaPreference(
        title = title,
        summary = stringResource(
            R.string.preference_value_separator_format,
            stringResource(modeTitleRes(current)),
            stringResource(modeSummaryRes(current)),
        ),
        startAction = { EtaPreferenceIcon(icon = icon, enabled = enabled, tint = iconTint) },
        enabled = enabled,
        onClick = if (enabled) ({ showDialog = true }) else null,
    )
    if (showDialog) {
        EtaWindowDialog(
            show = true,
            title = title,
            summary = stringResource(R.string.settings_agent_mode_summary),
            onDismissRequest = { showDialog = false },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                AgentMode.values().forEach { mode ->
                    EtaRadioButtonPreference(
                        title = stringResource(modeTitleRes(mode)),
                        summary = stringResource(modeSummaryRes(mode)),
                        selected = current == mode,
                        onClick = {
                            showDialog = false
                            val target = prefs ?: return@EtaRadioButtonPreference
                            val written = writeString(target, Prefs.Keys.AGENT_MODE, mode.id) &&
                                writeBoolean(target, Prefs.Keys.AGENT_PLAN_MODE, mode == AgentMode.PLAN)
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

/** 读取当前模式：优先新键，其次旧计划开关（升级用户没有新键）。 */
private fun readMode(prefs: SharedPreferences?): AgentMode {
    if (prefs == null) return AgentMode.DEFAULT
    val stored = runCatching { prefs.getString(Prefs.Keys.AGENT_MODE, null) }.getOrNull()
    if (!stored.isNullOrBlank()) return AgentMode.fromId(stored)
    val legacyPlan = runCatching {
        prefs.getBoolean(Prefs.Keys.AGENT_PLAN_MODE, false)
    }.getOrDefault(false)
    return if (legacyPlan) AgentMode.PLAN else AgentMode.DEFAULT
}

private fun modeTitleRes(mode: AgentMode): Int = when (mode) {
    AgentMode.STANDARD -> R.string.agent_mode_standard_title
    AgentMode.PLAN -> R.string.agent_mode_plan_title
    AgentMode.CREATOR -> R.string.agent_mode_creator_title
}

private fun modeSummaryRes(mode: AgentMode): Int = when (mode) {
    AgentMode.STANDARD -> R.string.agent_mode_standard_summary
    AgentMode.PLAN -> R.string.agent_mode_plan_summary
    AgentMode.CREATOR -> R.string.agent_mode_creator_summary
}

private fun writeString(prefs: SharedPreferences, key: String, value: String): Boolean =
    runCatching { prefs.edit().putString(key, value).commit() }.getOrDefault(false)

private fun writeBoolean(prefs: SharedPreferences, key: String, value: Boolean): Boolean =
    runCatching { prefs.edit().putBoolean(key, value).commit() }.getOrDefault(false)
