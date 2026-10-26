package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.AppearanceSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 启动期外观快照的硬约束：**没初始化也必须能读**。
 *
 * 它跑在 Application.onCreate 与首帧之间，任何异常或阻塞都是「启动即崩 / 启动白屏」。
 * 所以没 init（例如进程刚起、缓存还没写入）时直接给默认值，不碰 DataStore。
 */
class AppearanceSnapshotTest {

    @Test
    fun snapshotWithoutInitFallsBackToDefaultsInsteadOfCrashing() {
        assertEquals(AppearanceSettings(), AppearanceSettingsRepository.snapshot())
    }

    @Test
    fun snapshotIsStableAcrossRepeatedCalls() {
        val first = AppearanceSettingsRepository.snapshot()
        val second = AppearanceSettingsRepository.snapshot()
        assertEquals(first, second)
    }
}
