package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceUiStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 外观设置。
 *
 * 启动路径上有两处要用它（Application 判断预测返回手势、Activity 首帧主题），
 * 而 DataStore 的**首次读取**要建文件、解析整个 Preferences、反序列化 Settings，
 * 冷启动是几百毫秒级。原先两处都得等它：Application 用 runBlocking 堵住主线程，
 * Activity 要等读完才 setContent —— 启动画面就挂在那一动不动。
 *
 * 现在拆成两条路：
 * - [snapshot]：同步读一份 SharedPreferences 里的上次快照（毫秒级），首帧立刻可用；
 * - [settings] / [settingsFlow]：真值仍然来自 DataStore，读到后顺手刷新快照，
 *   主题与手势设置随后由 flow 自动纠正（最多闪一下，不会再卡住启动）。
 */
object AppearanceSettingsRepository {
    private const val CACHE_NAME = "eta_startup_snapshot"
    private const val KEY_APPEARANCE = "appearance_v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var appContext: Context? = null
    private var memory: AppearanceSettings? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 启动期同步快照：不碰 DataStore；没有快照时返回默认值。 */
    fun snapshot(): AppearanceSettings {
        memory?.let { return it }
        val raw = appContext
            ?.getSharedPreferences(CACHE_NAME, Context.MODE_PRIVATE)
            ?.getString(KEY_APPEARANCE, null)
        val value = raw
            ?.let { runCatching { json.decodeFromString<AppearanceSettings>(it) }.getOrNull() }
            ?: AppearanceSettings()
        memory = value
        return value
    }

    private fun remember(settings: AppearanceSettings) {
        memory = settings
        runCatching {
            appContext
                ?.getSharedPreferences(CACHE_NAME, Context.MODE_PRIVATE)
                ?.edit()
                ?.putString(KEY_APPEARANCE, json.encodeToString(settings))
                ?.apply()
        }
    }

    fun settingsFlow(): Flow<AppearanceSettings> =
        SettingsDataStore.appearanceSettingsFlow().onEach(::remember)

    suspend fun settings(): AppearanceSettings =
        SettingsDataStore.settings().appearance.also(::remember)

    suspend fun update(settings: AppearanceSettings) {
        SettingsDataStore.setAppearanceSettings(settings)
        remember(settings.normalized())
    }

    suspend fun update(transform: (AppearanceSettings) -> AppearanceSettings) {
        SettingsDataStore.updateAppearanceSettings(transform)
    }

    suspend fun selectUiStyle(style: AppearanceUiStyle) {
        SettingsDataStore.selectUiStyle(style)
    }
}
