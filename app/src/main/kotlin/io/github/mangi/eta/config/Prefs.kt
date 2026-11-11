package io.github.mangi.eta.config

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.mangi.eta.agent.context.PersonalSearchTools
import io.github.mangi.eta.agent.model.AgentFileToolCatalog
import io.github.mangi.eta.agent.model.AgentPhoneToolCatalog
import io.github.mangi.eta.agent.model.AgentTerminalToolCatalog
import org.json.JSONArray

/**
 * 模块配置中枢。
 *
 * - Hook 进程（system_server / SystemUI / Google / 系统助手等）在模块加载时调用
 *   [attachRemote]，缓存框架提供的只读 [SharedPreferences]；之后所有拦截回调用 [isEnabled]
 *   读取当前进程持有的 remote preferences。
 * - Eta Runtime 自己消费的开关保存在 App 私有配置中，不依赖 Xposed Service。
 * - Hook 消费的开关通过 [remotePreferencesForUi] 写入 RemotePreferences；
 *   XposedService 未就绪时不提供本地假 fallback。
 *
 * 基于 libxposed API 102 的 [io.github.libxposed.api.XposedInterface.getRemotePreferences]
 * 与 service 102 的 [XposedService.getRemotePreferences]，两端共用同一 group。
 */
internal object Prefs {

    /** 远程配置组名，UI 写入与 Hook 读取必须一致。 */
    const val GROUP = "eta_prefs"

    private const val LOCAL_AGENT_GROUP = "eta_agent_preferences"

    /** 所有功能开关 key。默认值按功能风险独立定义。 */
    object Keys {
        const val POWER_KEY_ASSISTANT_TARGET = "power_key_assistant_target"
        // 兼容旧版布尔协议；新 UI 不再写入，缺少三态配置时 true 仍表示 Gemini。
        const val POWER_KEY_TAKEOVER = "power_key_takeover"
        const val ASSISTANT_AUTO_CONFIG = "assistant_auto_config"
        const val HOTWORD_SELF_HEAL = "hotword_self_heal"
        const val GESTURE_BAR_CIRCLE_TO_SEARCH = "gesture_bar_circle_to_search"
        const val DOUBLE_FINGER_CIRCLE_TO_SEARCH = "double_finger_circle_to_search"
        const val LOCKSCREEN_VOICE_COMMAND = "lockscreen_voice_command"
        const val SCREEN_ON_VOICE_COMMAND = "screen_on_voice_command"
        const val AGENT_CUSTOM_MODEL = "agent_custom_model"
        const val AGENT_REQUIRE_PREFIX = "agent_require_prefix"
        const val AGENT_TERMINAL_TOOLS = "agent_terminal_tools"
        const val AGENT_BROWSER_TOOLS = "agent_browser_tools"
        const val AGENT_DEVICE_DIRECT_TOOLS = "agent_device_direct_tools"
        const val AGENT_DEVICE_SENSITIVE_READ_TOOLS = "agent_device_sensitive_read_tools"
        const val AGENT_DEVICE_SENSITIVE_ACTION_TOOLS = "agent_device_sensitive_action_tools"
        /**
         * 用户逐项禁用的工具名，逗号分隔。
         *
         * 与上面几个「按能力分类」的开关互补：分类开关只能整组开关，这里允许只关掉组里的
         * 个别工具（例如保留 `read_file` 而禁掉 `terminal`）。空串表示没有逐项禁用。
         */
        const val AGENT_DISABLED_TOOLS = "agent_disabled_tools"
        const val AGENT_THINKING_ENABLED = "agent_thinking_enabled"
        const val AGENT_AUTO_COMPACTION_ENABLED = "agent_auto_compaction_enabled"
        /** 会话运行期间保持屏幕唤醒；只在前台查看运行中的会话时生效。 */
        const val AGENT_KEEP_SCREEN_ON = "agent_keep_screen_on"
        /** 运行中是否显示状态栏下方的思考胶囊；关掉只是不画胶囊，任务照常执行，边缘流光仍可提示正在接管。 */
        const val AGENT_OVERLAY_CAPSULE_ENABLED = "agent_overlay_capsule_enabled"
        /** 运行中是否显示屏幕边缘流光；它不遮挡内容，只提示 Agent 正在操作前台界面。 */
        const val AGENT_OVERLAY_GLOW_ENABLED = "agent_overlay_glow_enabled"
        /**
         * 计划模式：只暴露只读工具并要求先出计划再等确认。
         * 它是临时模式——用户忘了关会一直只能计划，所以设置页文案要提醒。
         *
         * 2026-10-21 起由 [AGENT_MODE] 取代；此键保留为**兼容回退**：
         * 升级用户没有 AGENT_MODE 时仍按它工作，设置页切换模式时也会同步写它，
         * 这样回退到旧版本 App 时行为一致。
         */
        const val AGENT_PLAN_MODE = "agent_plan_mode"

        /** 工作模式 id（standard / plan / creator）；见 AgentMode。 */
        const val AGENT_MODE = "agent_mode"

        /**
         * 繁忙时发送行为（对齐 dsh 的 `BusyEnterBehavior`）：`queue` 排队（默认）/ `steer` 插话。
         *
         * 字符串键，不走 [BOOLEAN_DEFAULTS]，读取用 [getLocalAgentString] 再交给
         * `AgentBusySendMode.fromId`（未知值回退 queue）。
         */
        const val AGENT_BUSY_SEND_MODE = "agent_busy_send_mode"

        /**
         * 回合结束后工作过程什么时候收起（对齐 dsh 的 `collapseTiming`）：
         * `completion` 结束即收（默认）／ `next-input` 保持展开到下一条消息。
         *
         * 字符串键，不走 [BOOLEAN_DEFAULTS]，读取用 [getLocalAgentString] 再交给
         * `AgentWorkCollapseTiming.fromId`（未知值回退 completion）。
         */
        const val AGENT_WORK_COLLAPSE_TIMING = "agent_work_collapse_timing"

        /**
         * 工作细节档位（对齐 dsh 的 `transcriptView`）：
         * `compact` / `standard` / `detailed`（默认）/ `verbose`。
         *
         * 字符串键，不走 [BOOLEAN_DEFAULTS]，读取用 [getLocalAgentString] 再交给
         * `AgentTranscriptViewMode.fromId`（`normal`/`expanded` 等旧值读作 detailed）。
         */
        const val AGENT_TRANSCRIPT_VIEW = "agent_transcript_view"

        /**
         * 复杂编码任务是否交给 ACP 编码代理（codex / claude）：`off` / `ask`（默认）/ `auto`。
         *
         * 字符串键，不走 [BOOLEAN_DEFAULTS]，读取用 [getLocalAgentString] 再交给
         * `AcpDelegationMode.fromWire`（未知值回退 ask）。
         */
        const val AGENT_ACP_DELEGATION_MODE = "agent_acp_delegation_mode"

        /**
         * 默认委派给哪个代理（`codex` / `claude`）；空表示用第一个可用的。
         *
         * 存 id 而不是标题：标题是可翻译的展示文案，改了就对不上。
         */
        const val AGENT_ACP_DEFAULT_AGENT = "agent_acp_default_agent"

        /**
         * 委派专用的模型提供商 id；空 = 跟随主模型（历史行为）。
         *
         * 为什么要单开一个而不是“就用主界面选的那个”：
         * 聊天可以在便宜模型上跑，而委派出去的编码任务需要能真正顶住长上下文的 API。
         * 先前只有一个选择，换主模型就把委派目标一起换了；且换后不报错，只是行为变了。
         */
        const val AGENT_ACP_DEFAULT_PROVIDER = "agent_acp_default_provider"

        /**
         * 委派专用的模型 id；空 = 用该提供商第一个启用模型。
         *
         * 必须与 [AGENT_ACP_DEFAULT_PROVIDER] 搭配才生效：模型名跨提供商不通用，
         * 拿一个不属于当前提供商的模型名去跑只会得到上游 404。
         */
        const val AGENT_ACP_DEFAULT_MODEL = "agent_acp_default_model"

        /**
         * 聊天页底部常驻卡「没有在跑的就收起」（默认开）。
         *
         * 关掉 = 永远显示台账与后台任务卡，包含早就结束的那些。
         * 默认开是因为原来的行为会让输入框上方长期堆着已完成的任务，
         * 读起来像「还有事没完」。
         */
        const val AGENT_CHAT_CARDS_AUTO_COLLAPSE = "agent_chat_cards_auto_collapse"

        /** 允许主 Agent 派发 Subagent 并发执行（上游 3.3.5）。 */
        const val AGENT_SUBAGENT_ENABLED = "agent_subagent_enabled"
        /** 后台任务结束后，在聊天页的任务卡上给出「继续」（默认开；点一下才会发出）。 */
        const val AGENT_BACKGROUND_RESUME_BUTTON = "agent_background_resume_button"
        /**
         * 「继续」入口保留更久（10 分钟而非默认 2 分钟，默认关）。
         *
         * 卡片只在任务结束后的短窗口内出现，窗口一过整卡消失。这个开关只改窗口长度，
         * 不把卡片变回常驻 —— 「结束了不占位」的前提不变。
         */
        const val AGENT_BACKGROUND_RESUME_WINDOW_LONG = "agent_background_resume_window_long"
        /**
         * 后台任务结束后**自动**发起下一轮（默认关）。
         *
         * 它会在没人看着的时候花掉额度，所以默认关，且另有硬上限
         * （见 `BackgroundResume` 的 `AutoResumeMaxPerChain`）：
         * 一条链里最多自动续 2 次，用户发一条消息才开新链。链长落盘，
         * 进程重启不会把上限清零。
         */
        const val AGENT_BACKGROUND_RESUME_AUTO = "agent_background_resume_auto"

        const val AGENT_VIRTUAL_DISPLAY_ENABLED = "agent_virtual_display_enabled"
        const val AGENT_RUNTIME_CONFIG_JSON = "agent_runtime_config_json"
        const val AGENT_DISABLED_TOOLS_JSON = "agent_disabled_tools_json"

        /** 全部布尔开关及其默认值。 */
        val BOOLEAN_DEFAULTS: Map<String, Boolean> = mapOf(
            POWER_KEY_TAKEOVER to false,
            ASSISTANT_AUTO_CONFIG to false,
            HOTWORD_SELF_HEAL to false,
            GESTURE_BAR_CIRCLE_TO_SEARCH to true,
            DOUBLE_FINGER_CIRCLE_TO_SEARCH to false,
            LOCKSCREEN_VOICE_COMMAND to false,
            SCREEN_ON_VOICE_COMMAND to false,
            AGENT_CUSTOM_MODEL to true,
            AGENT_REQUIRE_PREFIX to false,
            AGENT_TERMINAL_TOOLS to true,
            AGENT_BROWSER_TOOLS to true,
            AGENT_DEVICE_DIRECT_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS to true,
            AGENT_THINKING_ENABLED to true,
            AGENT_AUTO_COMPACTION_ENABLED to true,
            AGENT_KEEP_SCREEN_ON to false,
            AGENT_OVERLAY_CAPSULE_ENABLED to true,
            AGENT_OVERLAY_GLOW_ENABLED to true,
            AGENT_PLAN_MODE to false,
            AGENT_SUBAGENT_ENABLED to true,
            AGENT_CHAT_CARDS_AUTO_COLLAPSE to true,
            AGENT_BACKGROUND_RESUME_BUTTON to true,
            AGENT_BACKGROUND_RESUME_WINDOW_LONG to false,
            AGENT_BACKGROUND_RESUME_AUTO to false,
            AGENT_VIRTUAL_DISPLAY_ENABLED to true,
        )

        /**
         * 由 Eta Runtime 最终裁决、不要求 Xposed 框架在线的开关。
         *
         * **跨进程可见性**（改键之前请读完，这里踩过坑）：
         * - 这些键的事实源是本地 prefs（App 私有目录），**只有 App 进程能读**。
         * - hook 进程（LSPosed 注入到别的应用里）没有 Eta 的 uid，`localAgent` 永远是 null，
         *   所以 [isEnabled] 里会回退到 remote（见 `localAgent ?: remote`）。
         * - 因此**被 hook 消费的键不能只留在本地**：必须由 `reconcileAgentPreferences`
         *   把值回写 remote，否则 hook 侧读到的是默认值 —— 界面开关打开了，系统层毫无反应。
         * - 当前唯一同时被 hook 读取的本地键是 [AGENT_VIRTUAL_DISPLAY_ENABLED]
         *   （`VirtualDisplaySystemHooks`）。它能成立，是因为设置页改它时会调用
         *   `reconcileAgentPreferences`，且 App 连上 XposedService 时还会再同步一次。
         *   要加这类键，先确认这条同步链真的覆盖它，或者干脆别登记进来。
         * - 另外 6 个 hook 读取的键（AGENT_CUSTOM_MODEL / AGENT_REQUIRE_PREFIX /
         *   ASSISTANT_AUTO_CONFIG / GESTURE_BAR_CIRCLE_TO_SEARCH /
         *   DOUBLE_FINGER_CIRCLE_TO_SEARCH / HOTWORD_SELF_HEAL）**故意不**登记：
         *   设置页用 `prefs`（remote）写入，hook 直接读 remote，两造同一个事实源。
         */
        val LOCAL_AGENT_KEYS: Set<String> = setOf(
            AGENT_TERMINAL_TOOLS,
            AGENT_BROWSER_TOOLS,
            AGENT_DEVICE_DIRECT_TOOLS,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
            AGENT_THINKING_ENABLED,
            AGENT_AUTO_COMPACTION_ENABLED,
            AGENT_KEEP_SCREEN_ON,
            AGENT_OVERLAY_CAPSULE_ENABLED,
            AGENT_OVERLAY_GLOW_ENABLED,
            AGENT_PLAN_MODE,
            AGENT_SUBAGENT_ENABLED,
            AGENT_CHAT_CARDS_AUTO_COLLAPSE,
            AGENT_BACKGROUND_RESUME_BUTTON,
            AGENT_BACKGROUND_RESUME_AUTO,
            AGENT_BACKGROUND_RESUME_WINDOW_LONG,
            AGENT_VIRTUAL_DISPLAY_ENABLED,
        )

        /**
         * 允许被 hook 进程读取的本地键（[LOCAL_AGENT_KEYS] 的子集）。
         *
         * hook 读不到 App 私有 prefs，这些键能成立**完全依赖** `reconcileAgentPreferences`
         * 把值回写 remote。所以这份清单越短越好：每加一个都要先确认同步链真的覆盖它，
         * 说明写进 [LOCAL_AGENT_KEYS] 的注释里。`PrefsCrossProcessGuardTest` 守着这条边界 ——
         * hook 里读了本地键却没登记，测试就红。
         */
        val HOOK_VISIBLE_LOCAL_KEYS: Set<String> = setOf(
            AGENT_VIRTUAL_DISPLAY_ENABLED,
        )

        /**
         * 与 [LOCAL_AGENT_KEYS] 同属「本地为事实源」的**字符串**键。
         *
         * 必须单独一张表：`reconcileAgentPreferences` 用
         * `BOOLEAN_DEFAULTS.getValue(key)` 取默认值，把字符串键塞进
         * `LOCAL_AGENT_KEYS` 会在打设置页时抛
         * `NoSuchElementException: Key ... is missing in the map` ——
         * 一次真实的闪退（2026-11-04，我自己引入的）。
         *
         * 值的语义是「空串 = 未设置 / 自动」，迁移时按原样搬运，
         * 不做布尔转换。
         */
        val LOCAL_AGENT_STRING_KEYS: Set<String> = setOf(
            AGENT_ACP_DELEGATION_MODE,
            AGENT_ACP_DEFAULT_AGENT,
            AGENT_ACP_DEFAULT_PROVIDER,
            AGENT_ACP_DEFAULT_MODEL,
            AGENT_DISABLED_TOOLS,
        )
    }

    /** Hook 进程缓存的只读 remote preferences，由 ModuleMain 在 onModuleLoaded 注入。 */
    @Volatile
    private var remote: SharedPreferences? = null

    @Volatile
    private var localAgent: SharedPreferences? = null

    /** App 进程调用：初始化不依赖 Xposed Service 的 Agent 配置。 */
    /** 供 UI 监听本地 Agent 开关变化；内部初始化与 [isEnabled] 使用同一份配置。 */
    internal fun localAgentPreferences(context: Context): SharedPreferences {
        initLocal(context)
        return checkNotNull(localAgent)
    }

    fun initLocal(context: Context) {
        if (localAgent == null) {
            synchronized(this) {
                if (localAgent == null) {
                    localAgent = context.applicationContext.getSharedPreferences(
                        LOCAL_AGENT_GROUP,
                        Context.MODE_PRIVATE,
                    ).also(::migrateLegacyGroupSwitchesIfNeeded)
                }
            }
        }
    }

    /** Hook 进程调用：缓存框架提供的只读 SharedPreferences。 */
    fun attachRemote(prefs: SharedPreferences?) {
        remote = prefs
    }

    /** Hook 进程监听框架下发的配置变化；listener 必须由调用方在进程生命周期内强引用。 */
    fun registerRemoteListener(listener: SharedPreferences.OnSharedPreferenceChangeListener): Boolean {
        val preferences = remote ?: return false
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return true
    }

    /**
     * 读取布尔开关。remote 不可用（框架未注入或调用失败）时回退各功能自己的默认值；
     * 默认值与设置页展示保持一致。
     */
    fun isEnabled(key: String): Boolean {
        val default = Keys.BOOLEAN_DEFAULTS[key] ?: true
        // 注意两个进程的差别：App 进程里 localAgent 已初始化，LOCAL 键读本地；
        // hook 进程里 localAgent 为 null，同一个键会回退 remote —— 所以 hook 消费的键
        // 必须在 remote 里有值，详见 [Keys.LOCAL_AGENT_KEYS] 的「跨进程可见性」。
        val preferences = if (key in Keys.LOCAL_AGENT_KEYS) localAgent ?: remote else remote
        return preferences?.getBoolean(key, default) ?: default
    }

    fun getString(key: String): String {
        return remote?.getString(key, "") ?: ""
    }

    /**
     * 读取本地 Agent 字符串配置（模式等非布尔值）。
     *
     * 与 [getString] 的区别：Agent 配置的事实源是本地 prefs（[LOCAL_AGENT_KEYS] 那套），
     * 远端只用于回写；没有值返回 null，由调用方决定默认值。
     */
    fun getLocalAgentString(key: String): String? =
        runCatching { (localAgent ?: remote)?.getString(key, null) }.getOrNull()

    fun powerAssistantTarget(): PowerAssistantTarget = powerAssistantTarget(remote)

    fun powerAssistantTarget(preferences: SharedPreferences?): PowerAssistantTarget {
        val persistedValue = runCatching {
            preferences?.getString(Keys.POWER_KEY_ASSISTANT_TARGET, null)
        }.getOrNull()
        val legacyDefault = Keys.BOOLEAN_DEFAULTS.getValue(Keys.POWER_KEY_TAKEOVER)
        val legacyTakeover = runCatching {
            preferences?.getBoolean(Keys.POWER_KEY_TAKEOVER, legacyDefault)
        }.getOrNull() ?: legacyDefault
        return PowerAssistantTarget.resolve(persistedValue, legacyTakeover)
    }

    /**
     * UI 进程获取可写的 RemotePreferences。
     *
     * [XposedService.getRemotePreferences] 的 commit 会同步等待 binder 提交到 LSPosed
     * 数据库，失败返回 false；service 未就绪时返回 null，让 UI 保持不可写。
     */
    fun remotePreferencesForUi(service: XposedService?): SharedPreferences? =
        runCatching { service?.getRemotePreferences(GROUP) }.getOrNull()

    /** Eta 设置页与 Runtime 使用的本地 Agent 配置，不依赖 LSPosed。 */
    fun localAgentPreferences(): SharedPreferences? = localAgent

    /**
     * 首次升级优先把已有 RemotePreferences 值迁入本地；之后本地值是事实源，并在框架
     * 可用时回写远端，让仍在目标进程中组装请求的 Hook 入口拿到一致的初始配置。
     */
    fun reconcileAgentPreferences(service: XposedService?) {
        val local = localAgent ?: return
        val remotePreferences = remotePreferencesForUi(service) ?: return
        val localEditor = local.edit()
        val remoteEditor = remotePreferences.edit()
        var updateLocal = false
        var updateRemote = false

        Keys.LOCAL_AGENT_KEYS.forEach { key ->
            // 用下标取值 + 跳过，而不是 getValue：这个集合里缺默认值的键已经导致过两次
            // 「打开设置即闪退」。注释警告挡不住第三次，所以让迁移本身容错 ——
            // 缺默认值的键只是不参与布尔迁移（它本来也不该在这张表里），
            // 记一条日志远好过让用户打不开设置。
            val default = Keys.BOOLEAN_DEFAULTS[key]
            if (default == null) {
                android.util.Log.w("EtaPrefs", "reconcileAgentPreferences: 键 $key 缺少布尔默认值，已跳过")
                return@forEach
            }
            when {
                local.contains(key) -> {
                    remoteEditor.putBoolean(key, local.getBoolean(key, default))
                    updateRemote = true
                }
                remotePreferences.contains(key) -> {
                    localEditor.putBoolean(key, remotePreferences.getBoolean(key, default))
                    updateLocal = true
                }
            }
        }
        // 字符串键单独一轮：它们没有布尔默认值，且值可能是空串（= 未设置），
        // 所以判据只能是 contains，不能按「值非空」判断有没有设置过。
        Keys.LOCAL_AGENT_STRING_KEYS.forEach { key ->
            when {
                local.contains(key) -> {
                    remoteEditor.putString(key, local.getString(key, null))
                    updateRemote = true
                }
                remotePreferences.contains(key) -> {
                    localEditor.putString(key, remotePreferences.getString(key, null))
                    updateLocal = true
                }
            }
        }
        // 工具开关落盘（上游）：JSON 数组，缺省值是 "[]" 而不是 null。
        when {
            local.contains(Keys.AGENT_DISABLED_TOOLS_JSON) -> {
                remoteEditor.putString(
                    Keys.AGENT_DISABLED_TOOLS_JSON,
                    local.getString(Keys.AGENT_DISABLED_TOOLS_JSON, "[]"),
                )
                updateRemote = true
            }
            remotePreferences.contains(Keys.AGENT_DISABLED_TOOLS_JSON) -> {
                localEditor.putString(
                    Keys.AGENT_DISABLED_TOOLS_JSON,
                    remotePreferences.getString(Keys.AGENT_DISABLED_TOOLS_JSON, "[]"),
                )
                updateLocal = true
            }
        }
        if (updateLocal) localEditor.commit()
        if (updateRemote) runCatching { remoteEditor.commit() }
    }

    /** 将运行时工具名映射为工具列表中的主开关标识，确保同一能力的不同动作变体受同一开关约束。 */
    fun canonicalToolSwitchId(toolName: String): String = when (toolName) {
        "tap" -> "tap_area"
        "long_press_element" -> "long_press"
        "scroll_element" -> "scroll"
        "input_text", "replace_text", "clear_text", "paste_text" -> "type_text"
        "wait", "wait_for_package" -> "wait_for_text"
        "browser_read", "browser_interact", "browser_screenshot" -> "browser_use"
        "search_coloros_notes" -> "search_notes"
        "search_coloros_memories" -> "search_system_memories"
        else -> PersonalSearchTools.canonical(toolName)
    }

    fun disabledTools(preferences: SharedPreferences? = localAgent ?: remote): Set<String> {
        val raw = runCatching {
            preferences?.getString(Keys.AGENT_DISABLED_TOOLS_JSON, null)
        }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptySet()
        return runCatching {
            val array = JSONArray(raw)
            buildSet(array.length()) {
                for (index in 0 until array.length()) {
                    val item = array.optString(index).trim()
                    if (item.isNotEmpty()) add(item)
                }
            }
        }.getOrDefault(emptySet())
    }

    fun isToolEnabledInSet(toolName: String, disabled: Set<String>): Boolean {
        if (disabled.isEmpty()) return true
        val canonical = canonicalToolSwitchId(toolName)
        return toolName !in disabled && canonical !in disabled
    }

    fun isToolEnabled(
        toolName: String,
        preferences: SharedPreferences? = localAgent ?: remote,
    ): Boolean = isToolEnabledInSet(toolName, disabledTools(preferences))

    fun setToolEnabled(
        toolName: String,
        enabled: Boolean,
        service: XposedService? = null,
    ): Boolean = setToolsEnabled(listOf(toolName), enabled, service)

    fun setToolsEnabled(
        toolNames: Collection<String>,
        enabled: Boolean,
        service: XposedService? = null,
    ): Boolean {
        val local = localAgent ?: return false
        val updated = disabledTools(local).toMutableSet()
        toolNames.forEach { rawName ->
            val canonical = canonicalToolSwitchId(rawName)
            if (enabled) {
                updated -= rawName
                updated -= canonical
            } else {
                updated += canonical
            }
        }
        val encoded = JSONArray(updated.sorted()).toString()
        val editor = local.edit().putString(Keys.AGENT_DISABLED_TOOLS_JSON, encoded)
        LEGACY_TOOL_GROUP_KEYS.forEach { key ->
            if (local.contains(key) && !local.getBoolean(key, true)) {
                editor.putBoolean(key, true)
            }
        }
        val committed = runCatching { editor.commit() }.getOrDefault(false)
        if (committed && service != null) {
            reconcileAgentPreferences(service)
        }
        return committed
    }

    private fun migrateLegacyGroupSwitchesIfNeeded(local: SharedPreferences) {
        if (local.contains(Keys.AGENT_DISABLED_TOOLS_JSON)) return
        val disabledLegacyGroups = LEGACY_TOOL_GROUP_KEYS.filter { key ->
            local.contains(key) && !local.getBoolean(key, true)
        }
        if (disabledLegacyGroups.isEmpty()) return
        val migratedDisabled = linkedSetOf<String>()
        if (Keys.AGENT_BROWSER_TOOLS in disabledLegacyGroups) {
            migratedDisabled += listOf("web_search", "fetch_url", "browser_use")
        }
        if (Keys.AGENT_TERMINAL_TOOLS in disabledLegacyGroups) {
            migratedDisabled += AgentTerminalToolCatalog.names
            migratedDisabled += AgentFileToolCatalog.names
            migratedDisabled += "read_image"
        }
        if (Keys.AGENT_DEVICE_DIRECT_TOOLS in disabledLegacyGroups) {
            migratedDisabled += AgentPhoneToolCatalog.direct
            migratedDisabled += listOf(
                "set_alarm", "set_timer", "device_status", "inspect_app",
                "network_info", "top_memory_apps", "top_storage_apps",
                "media_control", "set_volume",
            )
        }
        if (Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS in disabledLegacyGroups) {
            migratedDisabled += PersonalSearchTools.names
            migratedDisabled += AgentPhoneToolCatalog.reads
            migratedDisabled += listOf(
                "read_personal_item", "summarize_bills", "get_setting", "wifi_credentials",
                "recent_notifications", "search_notification_history", "recent_app_activity",
                "app_usage_summary", "get_current_location", "get_device_environment",
                "list_alarms", "list_active_timers", "search_clipboard_history",
                "get_health_summary", "read_sms_code", "get_logcat", "search_media",
                "search_audio", "search_recordings", "search_files", "search_calendar_events",
                "search_contacts", "search_call_history", "search_messages", "search_downloads",
                "search_notes", "search_coloros_recordings", "search_recording_summaries",
                "search_system_memories", "search_saved_places", "search_personal_orders",
                "search_qq_chat_images", "search_wechat_chat_images",
            )
        }
        if (Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS in disabledLegacyGroups) {
            migratedDisabled += AgentPhoneToolCatalog.writes
            migratedDisabled += listOf("set_setting", "set_device_state", "app_state_control")
        }
        val editor = local.edit()
            .putString(Keys.AGENT_DISABLED_TOOLS_JSON, JSONArray(migratedDisabled.sorted()).toString())
        disabledLegacyGroups.forEach { key -> editor.putBoolean(key, true) }
        runCatching { editor.commit() }
    }

    private val LEGACY_TOOL_GROUP_KEYS = listOf(
        Keys.AGENT_TERMINAL_TOOLS,
        Keys.AGENT_BROWSER_TOOLS,
        Keys.AGENT_DEVICE_DIRECT_TOOLS,
        Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
        Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
    )
}
