package io.github.mangi.eta.ui.app

import io.github.mangi.eta.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CharacterCardImportMessagesTest {
    /**
     * 只验证「code → 受控分类」的映射，不依赖具体语言的文案：
     * 文案本身由三套 strings.xml 与本包外的本地化测试守着。
     */
    private fun resolveId(code: String?): Int? {
        var hit: Int? = null
        val message = characterCardImportMessage(code) { id ->
            hit = id
            "text:$id"
        }
        return if (message == null) null else hit
    }

    @Test
    fun damagedPriorityV3ExplainsThatV2WasNotUsed() {
        assertEquals(R.string.character_import_error_v3, resolveId("CARD_V3_INVALID") ?: -1)
    }

    @Test
    fun unrecognizedErrorCannotExposeFileOrExceptionText() {
        assertNull(resolveId("角色卡原文：private example"))
        assertNull(resolveId(null))
        assertEquals(R.string.character_import_error_metadata, resolveId("CARD_METADATA_MISSING") ?: -1)
        assertEquals(R.string.character_import_error_too_large, resolveId("CARD_TOO_LARGE") ?: -1)
    }
}
