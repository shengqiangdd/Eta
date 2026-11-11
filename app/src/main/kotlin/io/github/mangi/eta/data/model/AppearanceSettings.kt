package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

const val MIN_INTERFACE_SCALE = 0.8f
const val MAX_INTERFACE_SCALE = 1.1f
const val DEFAULT_INTERFACE_SCALE = 1f

@Serializable
data class AppearanceSettings(
    val uiStyle: AppearanceUiStyle = AppearanceUiStyle.COUI,
    val themeMode: AppearanceThemeMode = AppearanceThemeMode.SYSTEM,
    val monetEnabled: Boolean = false,
    val paletteStyle: AppearancePaletteStyle = AppearancePaletteStyle.TONAL_SPOT,
    val accentColor: AppearanceAccentColor = AppearanceAccentColor.SYSTEM,
    val pureBlackEnabled: Boolean = false,
    val blurEnabled: Boolean = true,
    val topBarBlurStyle: AppearanceTopBarBlurStyle = AppearanceTopBarBlurStyle.GAUSSIAN,
    val swipeDismissEnabled: Boolean = true,
    val predictiveBackEnabled: Boolean = true,
    val interfaceScale: Float = DEFAULT_INTERFACE_SCALE,
    /**
     * 聊天页视图模式：全部 / 只看对话 / 只看轨迹。
     *
     * 它不是主题配色，但和 swipeDismiss / predictiveBack 一样属于界面偏好 —— 放在同一个
     * 通道里，聊天页才能直接经 LocalAppearanceSettings 读到它并即时重组，不必再拉一条
     * CompositionLocal 或额外参数链。
     */
    val chatViewMode: ChatViewMode = ChatViewMode.ALL,
) {
    fun normalized(): AppearanceSettings = copy(
        interfaceScale = normalizeInterfaceScale(interfaceScale),
    )
}

/**
 * 聊天页的视图模式。
 *
 * 三态而不是一个「只看对话」开关：长会话里有两类相反的需求 —— 读「我问了什么、它答了
 * 什么」时要收起过程，查「这一步做了什么、为什么慢或失败」时要收起大段正文。布尔表达
 * 不了第二种，两个布尔又能同时为真、互相矛盾，所以用互斥的枚举。
 */
@Serializable
enum class ChatViewMode(val persistedValue: String) {
    /** 原样显示全部消息。 */
    ALL("all"),

    /** 只看对话：收起思考、工具调用、运行轨迹这类过程消息。 */
    DIALOG("dialog"),

    /** 只看轨迹：收起助手正文，只留用户提问与执行过程。 */
    TRACE("trace");

    companion object {
        /**
         * 界面上呈现的顺序。
         *
         * 下拉索引一律走这里，不用 `entries` / `ordinal` —— 否则以后重排枚举声明，界面上的
         * 选中项会静默指向另一个模式，看着像「设置没保存」。
         */
        val uiOrder: List<ChatViewMode> = listOf(ALL, DIALOG, TRACE)

        fun fromPersistedValue(value: String?): ChatViewMode =
            entries.firstOrNull { it.persistedValue == value } ?: ALL
    }
}

@Serializable
enum class AppearanceUiStyle(val persistedValue: String) {
    COUI("coui"),
    MIUIX("miuix");

    val isCoui: Boolean
        get() = this == COUI

    companion object {
        fun fromPersistedValue(value: String?): AppearanceUiStyle =
            entries.firstOrNull { it.persistedValue == value } ?: COUI
    }
}

@Serializable
enum class AppearanceThemeMode(val persistedValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromPersistedValue(value: String?): AppearanceThemeMode =
            entries.firstOrNull { it.persistedValue == value } ?: SYSTEM
    }
}

@Serializable
enum class AppearancePaletteStyle(val persistedValue: String) {
    TONAL_SPOT("tonal_spot"),
    NEUTRAL("neutral"),
    VIBRANT("vibrant"),
    EXPRESSIVE("expressive"),
    RAINBOW("rainbow"),
    FRUIT_SALAD("fruit_salad"),
    MONOCHROME("monochrome"),
    FIDELITY("fidelity"),
    CONTENT("content");

    companion object {
        fun fromPersistedValue(value: String?): AppearancePaletteStyle =
            entries.firstOrNull { it.persistedValue == value } ?: TONAL_SPOT
    }
}

@Serializable
enum class AppearanceAccentColor(val persistedValue: String) {
    SYSTEM("system"),
    BLUE("blue"),
    PURPLE("purple"),
    PINK("pink"),
    RED("red"),
    ORANGE("orange"),
    YELLOW("yellow"),
    GREEN("green"),
    TEAL("teal");

    companion object {
        fun fromPersistedValue(value: String?): AppearanceAccentColor =
            entries.firstOrNull { it.persistedValue == value } ?: SYSTEM
    }
}

@Serializable
enum class AppearanceTopBarBlurStyle(val persistedValue: String) {
    GAUSSIAN("gaussian"),
    PROGRESSIVE("progressive");

    companion object {
        fun fromPersistedValue(value: String?): AppearanceTopBarBlurStyle =
            entries.firstOrNull { it.persistedValue == value } ?: GAUSSIAN
    }
}

/**
 * 由持久化值解析聊天视图模式，并兼容旧的布尔键「只看对话」。
 *
 * [legacyDialogOnly] 只在 [persistedValue] 缺失时才参与判断：一旦新版本写过新键（哪怕是
 * 「全部」），旧键就不能再把它改回去。抽成纯函数是为了给这条迁移规则本身留测试 ——
 * 迁移错了，用户升级后会看到一段本不该被收起/展开的会话。
 */
fun resolveChatViewMode(persistedValue: String?, legacyDialogOnly: Boolean?): ChatViewMode =
    when {
        persistedValue != null -> ChatViewMode.fromPersistedValue(persistedValue)
        legacyDialogOnly == true -> ChatViewMode.DIALOG
        else -> ChatViewMode.ALL
    }

fun normalizeInterfaceScale(value: Float): Float =
    if (value.isFinite()) {
        value.coerceIn(MIN_INTERFACE_SCALE, MAX_INTERFACE_SCALE)
    } else {
        DEFAULT_INTERFACE_SCALE
    }
