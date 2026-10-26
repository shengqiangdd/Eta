package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.data.db.EtaDatabase
import java.io.File
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 用真机数据库副本跑一次真实的启动加载。
 *
 * 目的不是断言某个数字，而是回答一个只有真实数据才能回答的问题：
 * 「这个 4 会话 / 1.6 万条消息 / journal 合计 4800 万字符的库，改完之后能不能一次读进来」。
 * 副本不存在时跳过（CI 里没有它），所以不算门禁，只是本地回归开关：
 *
 *     ETA_REAL_DB=/path/to/eta.db ./gradlew :app:testDebugUnitTest --tests '*RealDatabaseLoad*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class AgentConversationRealDatabaseLoadTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test
    fun realDeviceDatabaseLoadsWithJournalDeferred() {
        val source = File(System.getenv("ETA_REAL_DB") ?: "/workspace/eta-probe/eta.db")
        assumeTrue("缺少真实数据库副本：$source", source.isFile())
        val target = context.getDatabasePath("eta.db")
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
        EtaDatabase.closeForTests()

        val heapBefore = usedHeapBytes()
        val startedAt = System.currentTimeMillis()
        val snapshot = AgentConversationStore.load(context)
        val elapsedMs = System.currentTimeMillis() - startedAt
        val heapDeltaKb = (usedHeapBytes() - heapBefore) / 1024

        val deferred = snapshot.conversationsById.values.filter { it.journalDeferred }
        println(
            String.format(
                Locale.ROOT,
                "真实库加载：%d 个会话，journal 延迟 %d 个，耗时 %d ms，堆增 %d KB",
                snapshot.conversationsById.size, deferred.size, elapsedMs, heapDeltaKb,
            ),
        )
        assertTrue("真实库至少应有一个大会话走延迟路径", deferred.isNotEmpty())
        deferred.forEach { state ->
            assertTrue("延迟会话的 journal 必须是空表", state.journal.isEmpty())
            assertTrue("延迟会话的模型上下文必须仍然完整", state.history.isNotEmpty())
        }
        snapshot.conversationsById.values.forEach { state ->
            assertTrue("消息列表不能为空", state.messages.isNotEmpty())
        }
    }

    private fun usedHeapBytes(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
}
