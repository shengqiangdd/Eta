package io.github.mangi.eta.data.repository

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「只读文件开头」拿备份规模。
 *
 * 为什么必须是纯 JVM 测试：[EtaBackupJsonStreams.peekOrReplay] 刻意不用 `android.util.JsonReader`
 * （那在 JVM 单测里会抛 Stub!），所以在门禁里能真的跑起来——带 Room/Context 的
 * `EtaBackupRepositoryTest` 在本机是被整类跳过的。
 */
class EtaBackupStatsPeekTest {
    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { buffer ->
        GZIPOutputStream(buffer).use { it.write(text.toByteArray()) }
    }.toByteArray()

    private fun document(stats: String, tail: String = ""): String =
        """{"format":"eta-backup","schemaVersion":5,"exportedAt":1,"stats":$stats$tail"""

    private fun fullStats() = """{"conversationCount":25,"messageCount":153678,"providerCount":22,""" +
        """"modelCount":58,"memoryBytes":144,"characterCount":3}"""

    private fun body(): String = document(fullStats(), ""","providers":[],"messages":[]}""")

    @Test
    fun readsStatsFromTheHeadOfAPlainJsonBackup() {
        val stats = EtaBackupJsonStreams.statsFromPrefix(body())
        assertEquals(25, stats?.conversationCount)
        assertEquals(153678, stats?.messageCount)
        assertEquals(22, stats?.providerCount)
        assertEquals(58, stats?.modelCount)
        assertEquals(144, stats?.memoryBytes)
        assertEquals(3, stats?.characterCount)
    }

    @Test
    fun readsStatsThroughGzipWithoutDecompressingTheWholeFile() {
        val (stats, _) = EtaBackupJsonStreams.peekOrReplay(ByteArrayInputStream(gzip(body())))
        assertEquals(153678, stats?.messageCount)
        assertEquals(22, stats?.providerCount)
    }

    @Test
    fun peekLeavesAPlainStreamReadableFromTheStart() {
        val text = body()
        val (_, replay) = EtaBackupJsonStreams.peekOrReplay(ByteArrayInputStream(text.toByteArray()))
        assertEquals(text, replay.readBytes().toString(Charsets.UTF_8))
    }

    @Test
    fun peekLeavesTheGzipStreamReadableFromTheStart() {
        val text = body()
        val (stats, replay) = EtaBackupJsonStreams.peekOrReplay(ByteArrayInputStream(gzip(text)))
        assertEquals(25, stats?.conversationCount)
        // 关键：peek 消耗过压缩流的一段，回退后仍要能从字节 0 解压出完整原文。
        assertEquals(text, GZIPInputStream(replay).readBytes().toString(Charsets.UTF_8))
    }

    @Test
    fun legacyBackupWithStatsAtTheEndStaysReadableAfterPeek() {
        // 真机抓到的回归：旧备份 peek 拿不到统计 → 完整路径从被读过的中间开始解析
        // → MalformedJsonException，预览直接坏掉。这里钉住「peek 之后还能从头读」。
        val text = """{"format":"eta-backup","schemaVersion":4,"exportedAt":1,"providers":[],""" +
            """"conversations":[],"messages":[],"roleplay":null,"stats":{""" +
            """"conversationCount":25,"messageCount":153678}}"""
        val (stats, replay) = EtaBackupJsonStreams.peekOrReplay(ByteArrayInputStream(gzip(text)))
        assertNull("旧格式的统计在末尾，只读开头拿不到", stats)
        assertEquals(text, GZIPInputStream(replay).readBytes().toString(Charsets.UTF_8))
    }

    @Test
    fun backupWithoutStatsFallsBackToFullValidation() {
        val legacy = """{"format":"eta-backup","schemaVersion":1,"exportedAt":1}"""
        assertNull(EtaBackupJsonStreams.statsFromPrefix(legacy))
    }

    @Test
    fun statsWrittenByThePreviousVersionIsNotEnough() {
        // fix130 只写了对话数与消息数；缺的项不能当成 0 显示成「0 个提供商」。
        val partial = """{"conversationCount":25,"messageCount":153678}"""
        assertNull(EtaBackupJsonStreams.statsFromPrefix(document(partial, "}")))
    }

    @Test
    fun aStatsKeyInsideAStringIsNotMistakenForTheRealThing() {
        val trap = """{"format":"eta-backup","memoryMd":"note: \"stats\":{\"providerCount\":9}"}"""
        assertNull(EtaBackupJsonStreams.statsFromPrefix(trap))
    }

    @Test
    fun truncatedGzipReturnsNullInsteadOfThrowing() {
        val whole = gzip(body())
        val truncated = whole.copyOf(whole.size / 2)
        val (stats, _) = EtaBackupJsonStreams.peekOrReplay(ByteArrayInputStream(truncated))
        assertNull(stats)
    }
}
