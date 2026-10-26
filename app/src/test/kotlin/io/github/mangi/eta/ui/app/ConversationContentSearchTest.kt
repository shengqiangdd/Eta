package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 全库内容搜索（SQL 侧）。
 *
 * 界面只消费「会话 id → 命中条数」，所以这里守三件事：
 * 1. 未载入历史的会话也能被搜到（这就是本次改动的目的）；
 * 2. 超长正文落在 agent_text_chunks 里也必须命中（真机库里 203 条消息是这种形态）；
 * 3. LIKE 的 % _ \ 必须转义，否则搜「100%」会退化成通配、把整个库都命中。
 *
 * 注意：这个类依赖 Robolectric 的 SQLite native 库，而它只提供 x86_64 版本 ——
 * 在 arm64 开发机（本机 PKX110）上会挂在 DefaultNativeRuntimeLoader 断言上，属于环境限制而不是用例失败。
 * arm64 机器上请用 LikePatternEscapeTest（纯 JVM）验证转义，SQL 语义用真机 canary 验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class ConversationContentSearchTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test
    fun chunkedBodyIsSearchable() = runBlocking {
        val dao = EtaDatabase.get(context).conversationDao()
        dao.insertConversations(
            listOf(
                conversation("conv-a", "A 会话"),
                conversation("conv-b", "B 会话"),
            ),
        )
        // B 的正文远超分块阈值（16384 字符），入库后 content 只会剩下一个 @eta:chunks 引用。
        val longBody = "开头的废话".repeat(400) + "这里提到工作区目录" + "结尾".repeat(100)
        dao.insertMessages(
            listOf(
                message("m-a1", "conv-a", 0, "帮我看看工作区里有什么"),
                message("m-b1", "conv-b", 0, longBody),
            ),
        )

        // 前提校验：B 确实走了分块路径，否则这个用例证明不了「chunk 里的正文能被搜到」。
        val storedB = dao.messageRowsPage("conv-b", 10, 0).single().content
        assertTrue("超长正文应已落成引用：$storedB", storedB.startsWith("@eta:chunks:v1:"))

        assertEquals(
            mapOf("conv-a" to 1, "conv-b" to 1),
            AgentConversationStore.searchConversationContent(context, "工作区"),
        )
    }

    @Test
    fun percentIsLiteralNotWildcard() = runBlocking {
        val dao = EtaDatabase.get(context).conversationDao()
        dao.insertConversations(listOf(conversation("conv-c", "C 会话")))
        dao.insertMessages(
            listOf(
                message("m-c1", "conv-c", 0, "折扣 50% 起"),
                message("m-c2", "conv-c", 1, "普通的一句话"),
            ),
        )
        // 未转义时 "%" 会匹配全部两条；转义后只命中真的带百分号的那条。
        assertEquals(mapOf("conv-c" to 1), AgentConversationStore.searchConversationContent(context, "50%"))
        assertEquals(mapOf("conv-c" to 1), AgentConversationStore.searchConversationContent(context, "%"))
    }

    @Test
    fun underscoreAndBackslashAreLiteral() = runBlocking {
        val dao = EtaDatabase.get(context).conversationDao()
        dao.insertConversations(listOf(conversation("conv-d", "D 会话")))
        dao.insertMessages(
            listOf(
                message("m-d1", "conv-d", 0, "路径是 /data/local/tmp"),
                message("m-d2", "conv-d", 1, "字段名 foo_bar 要改"),
            ),
        )
        assertEquals(mapOf("conv-d" to 1), AgentConversationStore.searchConversationContent(context, "_"))
        assertEquals(emptyMap<String, Int>(), AgentConversationStore.searchConversationContent(context, "\\"))
        // ASCII 大小写不敏感，与内存里 contains(ignoreCase = true) 的口径一致。
        assertEquals(
            mapOf("conv-d" to 1),
            AgentConversationStore.searchConversationContent(context, "DATA/LOCAL"),
        )
    }

    @Test
    fun chunkReferenceRowItselfIsNotABodyMatch() = runBlocking {
        val dao = EtaDatabase.get(context).conversationDao()
        dao.insertConversations(listOf(conversation("conv-e", "E 会话")))
        // 这条正文一个字都没提 v1；入库后 content 只会剩下 `@eta:chunks:v1:<count>:<length>`。
        val longBody = "闲聊".repeat(9_000)
        dao.insertMessages(
            listOf(
                message("m-e1", "conv-e", 0, longBody),
                message("m-e2", "conv-e", 1, "版本号 v1 的说明"),
            ),
        )
        val stored = dao.messageRowsPage("conv-e", 10, 0).first { it.id == "m-e1" }.content
        assertTrue("超长正文应已落成引用：$stored", stored.startsWith("@eta:chunks:v1:"))

        // 只有 m-e2 的正文真的提到 v1；m-e1 只是引用串里带 v1，按「正文命中」算就是虚报。
        // 真机实测：不排除引用行时搜 v1 得 586 条，其中 203 条是这种引用（含少量正文也真的提到 v1）。
        assertEquals(
            mapOf("conv-e" to 1),
            AgentConversationStore.searchConversationContent(context, "v1"),
        )
        // 整库只有引用串自身带 "eta:chunks"，排除引用行后这一搜必须是空。
        assertEquals(
            emptyMap<String, Int>(),
            AgentConversationStore.searchConversationContent(context, "eta:chunks"),
        )
        // 引用行被排除不等于分块正文被排除：正文里的词仍必须命中。
        assertEquals(
            mapOf("conv-e" to 1),
            AgentConversationStore.searchConversationContent(context, "闲聊"),
        )
    }

    private fun conversation(id: String, title: String) = ConversationEntity(
        id = id,
        title = title,
        thinkingEnabled = false,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun message(id: String, conversationId: String, sortIndex: Int, content: String) =
        ConversationMessageEntity(
            id = id,
            conversationId = conversationId,
            sortIndex = sortIndex,
            type = "user",
            content = content,
        )
}
