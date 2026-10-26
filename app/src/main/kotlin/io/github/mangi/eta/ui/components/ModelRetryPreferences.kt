package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.ModelRetrySettings
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text

/**
 * 模型请求重试设置：开关 + 次数。
 *
 * 设置存在 DataStore（而不是 Prefs/Xposed 远程配置）：它只影响入口进程组装请求时的行为，
 * 不需要 Hook 到目标应用进程；每次请求都会读取最新值，改完立即生效、不用重启 Runtime。
 */
@Composable
internal fun ModelRetryPreferences() {
    val scope = rememberCoroutineScope()
    val settings by produceState<ModelRetrySettings?>(initialValue = null, Unit) {
        value = SettingsDataStore.modelRetrySettings()
    }
    var showCountDialog by remember { mutableStateOf(false) }
    val current = settings ?: ModelRetrySettings()
    Column(modifier = Modifier.fillMaxWidth()) {
        EtaSwitchPreference(
            title = stringResource(R.string.settings_model_retry_title),
            summary = if (current.enabled) {
                stringResource(R.string.settings_model_retry_summary_on)
            } else {
                stringResource(R.string.settings_model_retry_summary_off)
            },
            checked = current.enabled,
            enabled = settings != null,
            onCheckedChange = { enabled ->
                scope.launch {
                    SettingsDataStore.setModelRetrySettings(current.copy(enabled = enabled))
                }
            },
        )
        EtaPreferenceDivider(hasLeading = false)
        EtaPreference(
            title = stringResource(R.string.settings_model_retry_count_title),
            summary = if (current.enabled) {
                stringResource(R.string.settings_model_retry_count_summary, current.maxRetries)
            } else {
                stringResource(R.string.settings_model_retry_count_off)
            },
            enabled = settings != null && current.enabled,
            onClick = if (current.enabled) ({ showCountDialog = true }) else null,
        )
    }
    if (showCountDialog) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.settings_model_retry_count_title),
            summary = stringResource(R.string.settings_model_retry_dialog_summary),
            onDismissRequest = { showCountDialog = false },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                (0..ModelRetrySettings.MAX_RETRIES).forEach { option ->
                    EtaRadioButtonPreference(
                        title = if (option == 0) {
                            stringResource(R.string.settings_model_retry_option_zero)
                        } else {
                            stringResource(R.string.settings_model_retry_option_count, option)
                        },
                        selected = current.maxRetries == option,
                        onClick = {
                            showCountDialog = false
                            scope.launch {
                                SettingsDataStore.setModelRetrySettings(current.copy(maxRetries = option))
                            }
                        },
                    )
                }
                Text(
                    text = stringResource(R.string.settings_model_retry_effective, current.effectiveMaxRetries),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}
