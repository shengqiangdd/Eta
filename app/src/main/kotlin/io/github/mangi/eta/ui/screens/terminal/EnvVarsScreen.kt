package io.github.mangi.eta.ui.screens.terminal

import android.content.Context
import android.widget.Toast
import java.util.Locale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.TerminalEnvEntry
import io.github.mangi.eta.agent.terminal.TerminalEnvFile
import io.github.mangi.eta.agent.terminal.TerminalEnvValidation
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 环境变量管理：编辑应用私有 `files/terminal-user/env.conf`；旧版 `files/terminal/env.conf` 只读兼容。
 *
 * 设置页只改应用私有文件；`/sdcard/Download/eta-env.conf` 作为只读覆盖源显示提示，
 * 避免「界面改了却不生效」的误解。保存后 Agent 的下一条终端命令会重新读取。
 */
@Composable
internal fun EnvVarsScreen(context: Context, onBack: () -> Unit) {
    val envFile = remember(context.applicationContext) { TerminalEnvFile(context.applicationContext.filesDir) }
    var entries by remember { mutableStateOf(envFile.privateEntries()) }
    var downloadEntries by remember { mutableStateOf(envFile.downloadEntries()) }
    var downloadExists by remember { mutableStateOf(envFile.downloadFileExists()) }
    var downloadReadable by remember { mutableStateOf(envFile.downloadFileReadable()) }
    var editing by remember { mutableStateOf<TerminalEnvEntry?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var editorKey by remember { mutableStateOf("") }
    var editorValue by remember { mutableStateOf("") }
    var editorError by remember { mutableStateOf<TerminalEnvValidation?>(null) }
    var editorNotice by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<TerminalEnvEntry?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun openAdd() {
        editing = null
        editorKey = ""
        editorValue = ""
        editorError = null
        editorNotice = null
        showEditor = true
    }

    fun openEdit(entry: TerminalEnvEntry) {
        editing = entry
        editorKey = entry.key
        editorValue = entry.value
        editorError = null
        editorNotice = null
        showEditor = true
    }

    fun save() {
        val validation = envFile.validateEntry(editorKey.trim(), editorValue)
        if (validation != TerminalEnvValidation.OK) {
            editorError = validation
            editorNotice = null
            return
        }
        val key = editorKey.trim()
        val updated = entries.toMutableList()
        val index = updated.indexOfFirst { it.key == key }
        val entry = TerminalEnvEntry(key, editorValue)
        if (index >= 0) updated[index] = entry else updated.add(entry)
        editing?.takeIf { it.key != key }?.let { old -> updated.removeAll { it.key == old.key } }
        saving = true
        scope.launch {
            val saved = withContext(Dispatchers.IO) { envFile.savePrivateEntries(updated) }
            saving = false
            if (saved) {
                entries = envFile.privateEntries()
                downloadEntries = envFile.downloadEntries()
                downloadExists = envFile.downloadFileExists()
                downloadReadable = envFile.downloadFileReadable()
                showEditor = false
            } else {
                editorError = null
                editorNotice = context.getString(R.string.env_vars_save_failed)
            }
        }
    }

    fun delete(entry: TerminalEnvEntry) {
        val updated = entries.filterNot { it.key == entry.key }
        scope.launch {
            val saved = withContext(Dispatchers.IO) { envFile.savePrivateEntries(updated) }
            deleteTarget = null
            if (saved) {
                entries = envFile.privateEntries()
            } else {
                Toast.makeText(context, R.string.env_vars_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.env_vars_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = ::openAdd) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.env_vars_add))
            }
        },
    ) {
        item(key = "intro") {
            EtaPreferenceGroup {
                EtaPreference(
                    title = stringResource(R.string.env_vars_title),
                    summary = stringResource(R.string.env_vars_summary),
                )
            }
        }
        item(key = "entries") {
            EtaPreferenceGroup {
                if (entries.isEmpty()) {
                    EtaPreference(title = stringResource(R.string.env_vars_empty))
                }
                val downloadByKey = downloadEntries.associateBy { it.key }
                entries.forEachIndexed { index, entry ->
                    if (index > 0) EtaPreferenceDivider(hasLeading = true)
                    val overridden = downloadByKey[entry.key]
                    EtaPreference(
                        title = entry.key,
                        summary = when {
                            overridden != null -> stringResource(
                                R.string.env_vars_overridden_summary,
                                displayValue(overridden),
                            )
                            entry.value.isEmpty() -> stringResource(R.string.env_vars_value_empty)
                            else -> displayValue(entry)
                        },
                        startAction = {
                            EtaPreferenceIcon(icon = Icons.Rounded.Key, tint = EtaPreferenceColors.Blue)
                        },
                        endActions = {
                            IconButton(onClick = { deleteTarget = entry }) {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = stringResource(R.string.env_vars_delete_title),
                                )
                            }
                        },
                        onClick = { openEdit(entry) },
                    )
                }
            }
        }
        if (downloadEntries.isNotEmpty()) {
            item(key = "download-override") {
                EtaPreferenceGroup {
                    EtaPreference(
                        title = stringResource(R.string.env_vars_download_title),
                        summary = pluralStringResource(
                            R.plurals.env_vars_download_summary,
                            downloadEntries.size,
                            downloadEntries.size,
                            downloadEntries.take(3).joinToString(stringResource(R.string.list_separator)) { it.key },
                        ),
                    )
                }
            }
        } else if (downloadExists && !downloadReadable) {
            item(key = "download-unreadable") {
                EtaPreferenceGroup {
                    EtaPreference(
                        title = stringResource(R.string.env_vars_download_unreadable_title),
                        summary = stringResource(R.string.env_vars_download_unreadable_summary),
                    )
                }
            }
        }
        item(key = "agent-hint") {
            EtaPreferenceGroup {
                EtaPreference(
                    title = stringResource(R.string.env_vars_agent_hint_title),
                    summary = stringResource(R.string.env_vars_agent_hint_summary),
                )
            }
        }
    }

    EtaWindowDialog(
        show = showEditor,
        title = stringResource(if (editing == null) R.string.env_vars_add_title else R.string.env_vars_edit_title),
        onDismissRequest = { if (!saving) showEditor = false },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                value = editorKey,
                onValueChange = { editorKey = it; editorError = null; editorNotice = null },
                label = stringResource(R.string.env_vars_key),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = editorValue,
                onValueChange = { editorValue = it; editorError = null; editorNotice = null },
                label = stringResource(R.string.env_vars_value),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            editorError?.let { error ->
                Text(text = validationMessage(error), color = MiuixTheme.colorScheme.error)
            }
            editorNotice?.let { notice ->
                Text(text = notice, color = MiuixTheme.colorScheme.error)
            }
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_save),
                onCancel = { if (!saving) showEditor = false },
                onConfirm = ::save,
                confirmEnabled = !saving,
            )
        }
    }

    deleteTarget?.let { target ->
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.env_vars_delete_title),
            summary = stringResource(R.string.env_vars_delete_summary, target.key),
            onDismissRequest = { deleteTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.ui_delete_3755f5),
                destructive = true,
                onCancel = { deleteTarget = null },
                onConfirm = { delete(target) },
            )
        }
    }
}

@Composable
private fun validationMessage(error: TerminalEnvValidation): String = when (error) {
    TerminalEnvValidation.EMPTY_KEY -> stringResource(R.string.env_vars_error_empty_key)
    TerminalEnvValidation.INVALID_KEY -> stringResource(R.string.env_vars_error_invalid_key)
    TerminalEnvValidation.BLOCKED_KEY -> stringResource(R.string.env_vars_error_blocked_key)
    TerminalEnvValidation.INVALID_VALUE -> stringResource(R.string.env_vars_error_invalid_value)
    TerminalEnvValidation.TOO_LONG -> stringResource(R.string.env_vars_error_too_long)
    TerminalEnvValidation.OK -> ""
}

private fun displayValue(entry: TerminalEnvEntry): String {
    val sensitive = listOf("KEY", "TOKEN", "SECRET", "PASSWORD", "PASS", "AUTH")
        .any { entry.key.uppercase(Locale.ROOT).contains(it) }
    return if (sensitive) "••••••" else entry.value
}
