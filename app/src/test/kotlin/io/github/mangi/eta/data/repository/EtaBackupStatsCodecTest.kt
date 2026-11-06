package io.github.mangi.eta.data.repository

import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份统计段的编解码。
 *
 * 为什么单独一个文件：带 Room / Context 的 [EtaBackupRepositoryTest] 是 Robolectric 测试，
 * 在本机 Linux aarch64 上缺 native runtime（`DefaultNativeRuntimeLoader` 直接抛错），
 * 跑不了。这里只碰序列化，能真的在本机执行，至少把「字段名、缺失回退、描述符同步」钉住。
 */
class EtaBackupStatsCodecTest {
    private val json = Json

    @Test
    fun statsSurviveRoundTrip() {
        val document = EtaBackupDocument(exportedAt = 1L, stats = EtaBackupStats(2, 40))
        val text = json.encodeToString(EtaBackupDocument.serializer(), document)
        assertTrue("导出应包含 stats 段", text.contains("\"stats\""))
        val restored = json.decodeFromString(EtaBackupDocument.serializer(), text)
        assertEquals(EtaBackupStats(2, 40), restored.stats)
    }

    @Test
    fun backupWithoutStatsDecodesToNull() {
        val text = """{"format":"eta-backup","schemaVersion":4,"exportedAt":1}"""
        val restored = json.decodeFromString(EtaBackupDocument.serializer(), text)
        assertNull("旧备份没有统计段，必须解成 null 以便回退全量校验", restored.stats)
    }

    @Test
    fun statsIsAKnownHeaderField() {
        // stage() 的 knownFields 直接来自序列化描述符；字段名一旦写错，统计会在暂存时
        // 被当成未知字段丢掉，预览又悄悄退回全量遍历（慢且吃内存），界面上看不出异常。
        val names = EtaBackupDocument.serializer().descriptor.elementNames.toSet()
        assertTrue("stats 必须在 header 字段列表里", "stats" in names)
    }
}
