package io.github.mangi.eta.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 真实数据里 `MEMORY.md` 出现过两个 `## 用户偏好`。
 *
 * 以前 `find` 对精确匹配直接 `firstOrNull`，读写都会静默落在第一节上：
 * 用户以为改的是新的那节，实际改的是旧的（或反过来）。现在必须报歧义。
 */
class AgentMemoryDuplicateSectionTest {

    private val duplicated = """
        # 核心记忆
        ## 用户偏好
        - 旧
        ## 用户偏好
        - 新
    """.trimIndent()

    @Test
    fun `duplicate exact titles are ambiguous instead of silently picking the first`() {
        val sections = AgentMemoryDocument.sections(duplicated)
        assertNull("重名必须返回 null，交给调用方报歧义", AgentMemoryDocument.find(sections, "用户偏好"))
        val candidates = AgentMemoryDocument.candidates(sections, "用户偏好")
        assertEquals(2, candidates.size)
        assertEquals("两个候选都要带上行号，才能让用户挑一个", listOf(2, 4), candidates.map { it.startLine })
    }

    @Test
    fun `unique title still resolves`() {
        val sections = AgentMemoryDocument.sections(duplicated)
        assertEquals("核心记忆", AgentMemoryDocument.find(sections, "核心记忆")?.title)
    }
}