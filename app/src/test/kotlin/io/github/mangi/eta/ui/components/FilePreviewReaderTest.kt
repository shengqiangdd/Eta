package io.github.mangi.eta.ui.components

import io.github.mangi.eta.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePreviewReaderTest {
    /** 单测不引入 Android Resources：文案由这里提供，读取与截断逻辑仍然是被测代码。 */
    private val texts = FilePreviewReader.TextResolver { id, _ ->
        when (id) {
            R.string.file_preview_binary -> BINARY_NOTICE
            R.string.file_preview_truncated_suffix -> TRUNCATED_NOTICE
            else -> "text:$id"
        }
    }

    @Test
    fun readsTextFileDirectly() {
        val file = File.createTempFile("eta-preview", ".txt").apply { writeText("hello\nworld") }
        try {
            val preview = FilePreviewReader.read(file.absolutePath, texts)
            assertFalse(preview.failed)
            assertEquals("hello\nworld", preview.text)
        } finally {
            file.delete()
        }
    }

    @Test
    fun binaryFileIsNotDumped() {
        val file = File.createTempFile("eta-preview", ".bin").apply {
            writeBytes(byteArrayOf(0, 1, 2, 3, 0, 5, 0, 7, 0, 9, 0, 11))
        }
        try {
            val preview = FilePreviewReader.read(file.absolutePath, texts)
            assertFalse(preview.failed)
            assertEquals(BINARY_NOTICE, preview.text)
        } finally {
            file.delete()
        }
    }

    @Test
    fun truncatesLongFileWithNotice() {
        val file = File.createTempFile("eta-preview", ".txt").apply {
            writeText((1..600).joinToString("\n") { "line$it" })
        }
        try {
            val preview = FilePreviewReader.read(file.absolutePath, texts)
            assertTrue(preview.text, preview.text.endsWith(TRUNCATED_NOTICE))
            assertTrue(preview.text, preview.text.lineSequence().count() <= 402)
        } finally {
            file.delete()
        }
    }

    private companion object {
        const val BINARY_NOTICE = "[[binary]]"
        const val TRUNCATED_NOTICE = "\n\n[[truncated]]"
    }
}
