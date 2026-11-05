package io.github.mangi.eta.ui.components

import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
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
import io.github.mangi.eta.agent.tool.acpProviderModelIds
import io.github.mangi.eta.agent.tool.acpProviderReady
import io.github.mangi.eta.agent.tool.acpProviderSupportsAgent
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.repository.ProviderRepository

/**
 * 「委派用它」的提供商与模型。
 *
 * 为什么需要这两个开关：委派原先只能跟随主界面选中的提供商/模型。
 * 于是「聊天用便宜模型、委派用正式 API」这种常见诉求无法表达，
 * 而且换个主模型，委派目标会**静默**跟着变 —— 不报错、只是行为变了。
 *
 * 默认仍然是「跟随主模型」：不动设置的用户看不到任何行为变化。
 *
 * 模型选择器要求先选定具体提供商：模型名跨提供商不通用，
 * 拿一个不属于当前提供商的模型名去跑，只会得到一个看不懂的上游 404。
 */
@Composable
internal fun AcpProviderPreference(
    prefs: SharedPreferences?,
    title: String,
    icon: ImageVector,
    iconTint: Color,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val enabled = prefs != null
    var current by remember(prefs) { mutableStateOf(readString(prefs, Prefs.Keys.AGENT_ACP_DEFAULT_PROVIDER)) }
    var agentId by remember(prefs) { mutableStateOf(readString(prefs, Prefs.Keys.AGENT_ACP_DEFAULT_AGENT)) }
    var showDialog by remember { mutableStateOf(false) }
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())

    DisposableEffect(prefs) {
        val target = prefs ?: return@DisposableEffect onDispose { }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            when (key) {
                Prefs.Keys.AGENT_ACP_DEFAULT_PROVIDER ->
                    current = readString(changed, Prefs.Keys.AGENT_ACP_DEFAULT_PROVIDER)
                Prefs.Keys.AGENT_ACP_DEFAULT_AGENT ->
                    agentId = readString(changed, Prefs.Keys.AGENT_ACP_DEFAULT_AGENT)
            }
        }
        target.registerOnSharedPreferenceChangeListener(listener)
        onDispose { target.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val selectable = remember(providers, agentId) { acpSelectableProviders(providers, agentId) }
    val currentLabel = selectable.firstOrNull { it.id == current }?.name
        ?: stringResource(R.string.acp_follow_main_title)

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
            summary = stringResource(R.string.settings_acp_provider_summary),
            onDismissRequest = { showDialog = false },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                EtaRadioButtonPreference(
                    title = stringResource(R.string.acp_follow_main_title),
                    summary = stringResource(R.string.acp_follow_main_summary),
                    selected = current.isEmpty(),
                    onClick = {
                        showDialog = false
                        // 清掉提供商时一并清掉模型：留着旧模型名没有意义，
                        // 一旦以后又选了别的提供商，它就会被当成有效值塞过去。
                        val ok = writeString(prefs, Prefs.Keys.AGENT_ACP_DEFAULT_PROVIDER, "")
                        if (ok == true) {
                            writeString(prefs, Prefs.Keys.AGENT_ACP_DEFAULT_MODEL, "")
                            current = ""
                        } else if (ok == false) {
                            toast(context, resources)
                        }
                    },
                )
                if (selectable.isEmpty()) {
                    // 空列表要说清是「没有」而不是「加载中」，否则用户会以为界面坏了。
                    EtaRadioButtonPreference(
                        title = stringResource(R.string.acp_provider_none_title),
                        summary = stringResource(R.string.acp_provider_none_summary),
                        selected = false,
                        enabled = false,
                        onClick = { },
                    )
                }
                val notReady = stringResource(R.string.acp_provider_not_ready)
                selectable.forEach { provider ->
                    val ready = acpProviderReady(provider, agentId)
                    // 不用 buildString：本段需要调 @Composable 的 stringResource，
                    // 先取出再拼更稳。
                    val summary = if (ready) provider.baseUrl else provider.baseUrl + " · " + notReady
                    EtaRadioButtonPreference(
                        title = provider.name,
                        summary = summary,
                        selected = current == provider.id,
                        onClick = {
                            showDialog = false
                            val ok = writeString(prefs, Prefs.Keys.AGENT_ACP_DEFAULT_PROVIDER, provider.id)
                            if (ok == true) {
                                // 换提供商必须重置模型，见上面「清掉提供商」处的说明。
                                writeString(prefs, Prefs.Keys.AGENT_ACP_DEFAULT_MODEL, "")
                                current = provider.id
                            } else if (ok == false) {
                                toast(context, resources)
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * 委派专用模型。只有选定具体提供商后才可选 —— 见 [AcpProviderPreference] 的说明。
 */
@Composable
internal fun AcpModelPreference(
    prefs: SharedPreferences?,
    title: String,
    icon: ImageVector,
    iconTint: Color,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var current by remember(prefs) { mutableStateOf(readString(prefs, Prefs.Keys.AGENT_ACP_DEFAULT_MODEL)) }
    var providerId by remember(prefs) { mutableStateOf(readString(prefs, Prefs.Keys.AGENT_ACP_DEFAULT_PROVIDER)) }
    var showDialog by remember { mutableStateOf(false) }
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())

    DisposableEffect(prefs) {
        val target = prefs ?: return@DisposableEffect onDispose { }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            when (key) {
                Prefs.Keys.AGENT_ACP_DEFAULT_MODEL ->
                    current = readString(changed, Prefs.Keys.AGENT_ACP_DEFAULT_MODEL)
                Prefs.Keys.AGENT_ACP_DEFAULT_PROVIDER ->
                    providerId = readString(changed, Prefs.Keys.AGENT_ACP_DEFAULT_PROVIDER)
            }
        }
        target.registerOnSharedPreferenceChangeListener(listener)
        onDispose { target.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val owner = remember(providers, providerId) { providers.firstOrNull { it.id == providerId } }
    // 没选提供商时这一项没有意义：模型名跨提供商不通用，先让它明确不可点。
    val selectable = providerId.isNotEmpty() && owner != null
    val enabled = prefs != null && selectable
    val models = remember(owner) { owner?.let { acpProviderModelIds(it) }.orEmpty() }
    val currentLabel = if (!selectable) {
        stringResource(R.string.acp_follow_main_title)
    } else {
        current.takeIf { it.isNotEmpty() } ?: stringResource(R.string.acp_model_auto_title)
    }

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
            summary = stringResource(R.string.settings_acp_model_summary),
            onDismissRequest = { showDialog = false },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                EtaRadioButtonPreference(
                    title = stringResource(R.string.acp_model_auto_title),
                    summary = stringResource(R.string.acp_model_auto_summary),
                    selected = current.isEmpty(),
                    onClick = {
                        showDialog = false
                        writeString(prefs, Prefs.Keys.AGENT_ACP_DEFAULT_MODEL, "")?.let { ok ->
                            if (ok) current = "" else toast(context, resources)
                        }
                    },
                )
                models.forEach { model ->
                    EtaRadioButtonPreference(
                        title = model,
                        selected = current == model,
                        onClick = {
                            showDialog = false
                            writeString(prefs, Prefs.Keys.AGENT_ACP_DEFAULT_MODEL, model)?.let { ok ->
                                if (ok) current = model else toast(context, resources)
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * 当前能选哪些提供商。
 *
 * 默认代理是「自动」时无法预知最终会跑哪个，所以取两个代理的并集 ——
 * 宁可多列一个，也不要强迫用户先定死代理才能选提供商。
 */
internal fun acpSelectableProviders(
    providers: List<ProviderSetting>,
    agentId: String,
): List<ProviderSetting> {
    val agents = if (agentId.isBlank()) {
        AcpDelegationAgents.entries.map { it.id }
    } else {
        listOf(agentId)
    }
    return providers.filter { provider ->
        agents.any { id -> acpProviderSupportsAgent(provider, id) }
    }
}

private fun readString(prefs: SharedPreferences?, key: String): String {
    if (prefs == null) return ""
    return runCatching { prefs.getString(key, null) }.getOrNull().orEmpty()
}

/**
 * 同步写入并汇报结果。
 *
 * 用 `commit()` 而不是 KTX 的 `edit { }`（默认异步 apply）：KTX 版本不返回成功与否，
 * 而这里恰恰要判断「值有没有真的写进去」——写失败还改了界面状态，就是界面在说假话。
 * 因此抑制 UseKtx，而不是换成异步写法。
 */
@Suppress("UseKtx")
private fun writeString(prefs: SharedPreferences?, key: String, value: String): Boolean? {
    val target = prefs ?: return null
    return runCatching { target.edit().putString(key, value).commit() }.getOrDefault(false)
}

private fun toast(context: android.content.Context, resources: android.content.res.Resources) {
    Toast.makeText(
        context.applicationContext,
        resources.getString(R.string.settings_write_failed),
        Toast.LENGTH_SHORT,
    ).show()
}
