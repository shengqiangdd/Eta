package io.github.mangi.eta.ui.components

import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tool.acpProviderModelIds
import io.github.mangi.eta.agent.tool.acpProviderReady
import io.github.mangi.eta.agent.tool.acpProviderSupportsAgent
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.repository.ProviderCredentialRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text

/**
 * 「委派用它」的提供商与模型 —— **一个**选择器，而不是两项。
 *
 * 拆成「先选提供商、再选模型」两项时，用户要点开两个对话框、跨两个界面记着
 * 上一步选了什么；而这两件事本来就是一体（模型名跨提供商不通用，选提供商
 * 的唯一目的就是缩小模型范围）。合并后一次点开、按提供商分组、点哪个用哪个。
 *
 * 分组而不是两级下钻：模型总量可能很大（本机有个提供商 96 个模型），
 * 所以配了搜索框；但默认全部展开，常见情况下滚两下就到底，不必再点一次。
 *
 * 底层仍存两个键（[Prefs.Keys.AGENT_ACP_DEFAULT_PROVIDER] 与
 * [Prefs.Keys.AGENT_ACP_DEFAULT_MODEL]）：委派侧要在 hook 进程里读，
 * 合成一个 JSON 还得在那儿解析一遍。界面合并是界面的事。
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
    val enabled = prefs != null
    var currentProvider by remember(prefs) {
        mutableStateOf(readString(prefs, Prefs.Keys.AGENT_ACP_DEFAULT_PROVIDER))
    }
    var currentModel by remember(prefs) {
        mutableStateOf(readString(prefs, Prefs.Keys.AGENT_ACP_DEFAULT_MODEL))
    }
    var agentId by remember(prefs) {
        mutableStateOf(readString(prefs, Prefs.Keys.AGENT_ACP_DEFAULT_AGENT))
    }
    var showDialog by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // 两级下钻的当前位置：null = 提供商列表，非 null = 该提供商的模型列表。
    // 不把两级平铺在一页：模型总量可能是几百（本机有个提供商 96 个），
    // 平铺后用户为了看第二个提供商也得滑过前一个的全部模型。
    var pickedProviderId by remember { mutableStateOf<String?>(null) }
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())

    DisposableEffect(prefs) {
        val target = prefs ?: return@DisposableEffect onDispose { }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            when (key) {
                Prefs.Keys.AGENT_ACP_DEFAULT_PROVIDER ->
                    currentProvider = readString(changed, Prefs.Keys.AGENT_ACP_DEFAULT_PROVIDER)
                Prefs.Keys.AGENT_ACP_DEFAULT_MODEL ->
                    currentModel = readString(changed, Prefs.Keys.AGENT_ACP_DEFAULT_MODEL)
                Prefs.Keys.AGENT_ACP_DEFAULT_AGENT ->
                    agentId = readString(changed, Prefs.Keys.AGENT_ACP_DEFAULT_AGENT)
            }
        }
        target.registerOnSharedPreferenceChangeListener(listener)
        onDispose { target.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val groups = remember(providers, agentId) { acpModelGroups(providers, agentId) }
    val visible = remember(groups, query) { groups.filterByQuery(query) }
    // 登录态只在界面刷新时算一次；每行都去查一遍仓库会把整张列表拖成同步 I/O。
    val oauthConnected: (ProviderSetting) -> Boolean = remember(providers, agentId) {
        val loggedIn = providers.associate { provider ->
            provider.id to runCatching {
                ProviderCredentialRepository.account(provider.id, provider.authMode)
                    ?.let { summary -> !summary.needsReauthentication } ?: false
            }.getOrDefault(false)
        }
        // 先赋给局部变量：内层 lambda 直接当尾随参数会被当成 remember 自己的 trailing lambda。
        val lookup: (ProviderSetting) -> Boolean = { provider -> loggedIn[provider.id] == true }
        lookup
    }
    val currentLabel = groups.firstOrNull { it.provider.id == currentProvider }
        ?.takeIf { group -> group.models.any { it == currentModel } }
        ?.let { group -> "${group.provider.name} / $currentModel" }
        ?: stringResource(R.string.acp_follow_main_title)

    EtaPreference(
        title = title,
        summary = currentLabel,
        startAction = { EtaPreferenceIcon(icon = icon, enabled = enabled, tint = iconTint) },
        enabled = enabled,
        onClick = if (enabled) ({
            // 每次打开都从提供商列表开始。记住上次停在哪一层，下次打开
            // 会先看到一堆自己没打算看的模型。
            pickedProviderId = null
            query = ""
            showDialog = true
        }) else null,
    )
    if (showDialog) {
        // 取到 LazyColumn 外面：LazyListScope 不是 @Composable，里面不能直接调 stringResource。
        val notReady = stringResource(R.string.acp_provider_not_ready)
        val picked = pickedProviderId?.let { id -> groups.firstOrNull { it.provider.id == id } }
        // 停在模型页、但那个提供商已经不在候选里了（被停用或删掉）：
        // 退回提供商列表，而不是留一个空白对话框。
        LaunchedEffect(pickedProviderId, groups) {
            if (pickedProviderId != null && picked == null) pickedProviderId = null
        }
        EtaWindowDialog(
            show = true,
            title = title,
            summary = stringResource(R.string.settings_acp_model_summary),
            onDismissRequest = { showDialog = false },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                InputField(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = {},
                    expanded = false,
                    onExpandedChange = {},
                    label = stringResource(
                        if (picked == null) R.string.acp_picker_search_hint else R.string.acp_picker_search_model,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                // 必须是 LazyColumn，不能是普通 Column。
                // miuix 的 WindowDialog 只是把 content 直接调出来，不自带任何滚动容器
                // （已反编译 miuix-ui 0.9.4 的 WindowDialogKt 核实：content 直接 invoke），
                // 而模型可能上百条。heightIn 给一个上界，否则 LazyColumn 在无限高度约束下无法滚动。
                if (picked == null) {
                    // 第一级：只列提供商。每个提供商有多少模型写在摘要里，
                    // 进去才看得到具体模型名 —— 这样不管总模型有多少，这一页都是可数的。
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = ACP_PICKER_MAX_HEIGHT),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        item(key = "follow_main") {
                            EtaRadioButtonPreference(
                                title = stringResource(R.string.acp_follow_main_title),
                                summary = stringResource(R.string.acp_follow_main_summary),
                                selected = currentProvider.isEmpty() && currentModel.isEmpty(),
                                onClick = {
                                    showDialog = false
                                    // 两个键一起清：只清一个会留下「有模型、没提供商」的
                                    // 半截状态，而模型名跨提供商不通用，那个值永远不会是对的。
                                    val ok = writeString(prefs, Prefs.Keys.AGENT_ACP_DEFAULT_PROVIDER, "")
                                    if (ok == true) {
                                        writeString(prefs, Prefs.Keys.AGENT_ACP_DEFAULT_MODEL, "")
                                        currentProvider = ""
                                        currentModel = ""
                                    } else if (ok == false) {
                                        toast(context, resources)
                                    }
                                },
                            )
                        }
                        if (visible.isEmpty()) {
                            // 空列表要说清是「没有」而不是「加载中」，否则用户会以为界面坏了。
                            item(key = "none") {
                                EtaRadioButtonPreference(
                                    title = stringResource(R.string.acp_provider_none_title),
                                    summary = stringResource(R.string.acp_provider_none_summary),
                                    selected = false,
                                    enabled = false,
                                    onClick = { },
                                )
                            }
                        }
                        items(visible, key = { group -> "provider:${group.provider.id}" }) { group ->
                            // 订阅登录的提供商 apiKey 本来就是空的，拿它当“没凭据”会把
                            // 「已登录的 ChatGPT 订阅」误标成不可用 —— 那正是这一轮要修的东西。
                            val ready = acpProviderReady(group.provider, agentId, oauthConnected)
                            EtaRadioButtonPreference(
                                title = group.provider.name,
                                summary = pluralStringResource(
                                    R.plurals.provider_models_count,
                                    group.models.size,
                                    group.models.size,
                                ) + if (ready) "" else " · $notReady",
                                selected = currentProvider == group.provider.id,
                                onClick = {
                                    pickedProviderId = group.provider.id
                                    // 换层就清搜索词：上一层的过滤条件带到下一层，
                                    // 会出现「点进去是空的」这种莫名其妙的页面。
                                    query = ""
                                },
                            )
                        }
                    }
                } else {
                    val models = remember(picked, query) { picked.filterModelsByQuery(query) }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = ACP_PICKER_MAX_HEIGHT),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        item(key = "back") {
                            EtaRadioButtonPreference(
                                title = stringResource(R.string.acp_picker_back),
                                summary = picked.provider.name,
                                selected = false,
                                onClick = {
                                    pickedProviderId = null
                                    query = ""
                                },
                            )
                        }
                        if (models.isEmpty()) {
                            item(key = "no_model") {
                                EtaRadioButtonPreference(
                                    title = stringResource(R.string.acp_picker_no_match),
                                    summary = null,
                                    selected = false,
                                    enabled = false,
                                    onClick = { },
                                )
                            }
                        }
                        items(models, key = { model -> "model:${picked.provider.id}:$model" }) { model ->
                            val ready = acpProviderReady(picked.provider, agentId, oauthConnected)
                            EtaRadioButtonPreference(
                                title = model,
                                summary = if (ready) null else notReady,
                                selected = currentProvider == picked.provider.id && currentModel == model,
                                onClick = {
                                    showDialog = false
                                    // 提供商和模型必须成对写入：这正是合并成一个选择器
                                    // 换来的保证 —— 旧的「先选提供商」路径里，
                                    // 换提供商必须顺手清掉模型，忘一次就会拿旧模型名去请求新上游。
                                    val ok = writeString(
                                        prefs,
                                        Prefs.Keys.AGENT_ACP_DEFAULT_PROVIDER,
                                        picked.provider.id,
                                    )
                                    if (ok == true) {
                                        writeString(prefs, Prefs.Keys.AGENT_ACP_DEFAULT_MODEL, model)
                                        currentProvider = picked.provider.id
                                        currentModel = model
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
    }
}

/** 选择器里的一组：一个提供商 + 它可用于委派的模型。 */
internal data class AcpModelGroup(val provider: ProviderSetting, val models: List<String>)

/**
 * 按提供商分组的候选模型。
 *
 * 没有启用模型的提供商会整组略去 —— 委派必须带一个模型名，
 * 列出「选不了的提供商」只会制造一次注定失败的点击。
 */
internal fun acpModelGroups(
    providers: List<ProviderSetting>,
    agentId: String,
): List<AcpModelGroup> = acpSelectableProviders(providers, agentId).mapNotNull { provider ->
    val models = acpProviderModelIds(provider)
    if (models.isEmpty()) null else AcpModelGroup(provider, models)
}

/**
 * 模型页的搜索：只在当前这一组的模型名里过滤。
 *
 * 和 [filterByQuery] 分开：那一层要判断「哪些提供商还值得列出来」，所以模型名命中
 * 也要保留整组；这一层已经确定只有一个提供商，只剩模型名这一个维度。
 */
internal fun AcpModelGroup.filterModelsByQuery(query: String): List<String> {
    val keyword = query.trim()
    if (keyword.isEmpty()) return models
    return models.filter { it.contains(keyword, ignoreCase = true) }
}

/** 提供商页的搜索：提供商名或模型名命中即可；整组都不命中就整组消失。 */
internal fun List<AcpModelGroup>.filterByQuery(query: String): List<AcpModelGroup> {
    val keyword = query.trim()
    if (keyword.isEmpty()) return this
    return mapNotNull { group ->
        if (group.provider.name.contains(keyword, ignoreCase = true)) {
            group
        } else {
            val hit = group.models.filter { it.contains(keyword, ignoreCase = true) }
            if (hit.isEmpty()) null else group.copy(models = hit)
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

/**
 * 选择器对话框的最大高度。
 *
 * 不直接写进调用处复用：没有上界的话 LazyColumn 在无限高约束下不会滚动
 * （它会把所有项都测一遍，然后被外层裁掉）—— 症状和用 Column 时一模一样。
 */
private val ACP_PICKER_MAX_HEIGHT = 420.dp

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
