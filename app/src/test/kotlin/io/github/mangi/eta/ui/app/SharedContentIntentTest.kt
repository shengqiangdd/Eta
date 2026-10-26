package io.github.mangi.eta.ui.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统分享 / 选中文本的解析规则。
 *
 * 这些规则决定「用户分享进来的东西会不会丢」：分享面板一旦点下来，第三方应用那边已经
 * 关掉或跳走了，Eta 这边漏掉一段文本或一张图，用户很难察觉。所以按来源、按类型、
 * 按超限逐条钉住，而不是只测一条「纯文本能过」。
 */
class SharedContentIntentTest {

    private fun parse(input: SharedContentInput): SharedContentPlan? =
        SharedContentIntentParser.parse(input)

    @Test
    fun `plain shared text becomes draft text`() {
        val plan = parse(
            SharedContentInput(
                action = SharedContentIntentParser.ACTION_SEND,
                type = "text/plain",
                text = "  帮我把这段话整理一下  ",
            ),
        )
        assertEquals("帮我把这段话整理一下", plan?.text)
        assertTrue(plan!!.imageUris.isEmpty())
        assertTrue(plan.fileUris.isEmpty())
        assertEquals(SharedContentOrigin.SHARE, plan.origin)
    }

    @Test
    fun `subject is kept together with body`() {
        val plan = parse(
            SharedContentInput(
                action = SharedContentIntentParser.ACTION_SEND,
                type = "text/plain",
                subject = "一篇文章",
                text = "https://example.com/post",
            ),
        )
        assertEquals("一篇文章\n\nhttps://example.com/post", plan?.text)
    }

    @Test
    fun `subject already inside text is not duplicated`() {
        val plan = parse(
            SharedContentInput(
                action = SharedContentIntentParser.ACTION_SEND,
                text = "一篇文章：https://example.com/post",
                subject = "一篇文章",
            ),
        )
        assertEquals("一篇文章：https://example.com/post", plan?.text)
    }

    @Test
    fun `process text is accepted even when read only`() {
        val plan = parse(
            SharedContentInput(
                action = SharedContentIntentParser.ACTION_PROCESS_TEXT,
                type = "text/plain",
                processText = "选中的句子",
                processTextReadOnly = true,
            ),
        )
        assertEquals("选中的句子", plan?.text)
        assertEquals(SharedContentOrigin.PROCESS_TEXT, plan?.origin)
    }

    @Test
    fun `blank process text is ignored`() {
        assertNull(
            parse(
                SharedContentInput(
                    action = SharedContentIntentParser.ACTION_PROCESS_TEXT,
                    processText = "   \n ",
                ),
            ),
        )
    }

    @Test
    fun `empty share is ignored instead of creating an empty chat`() {
        assertNull(parse(SharedContentInput(action = SharedContentIntentParser.ACTION_SEND, type = "text/plain")))
    }

    @Test
    fun `unknown action is ignored`() {
        assertNull(parse(SharedContentInput(action = "android.intent.action.VIEW", text = "x")))
        assertNull(parse(SharedContentInput(action = null, text = "x")))
    }

    @Test
    fun `image uri is classified by its own mime type`() {
        val plan = parse(
            SharedContentInput(
                action = SharedContentIntentParser.ACTION_SEND,
                type = "image/png",
                uris = listOf(
                    SharedUriInput("content://media/picker/1", mimeType = "image/png"),
                ),
            ),
        )
        assertEquals(listOf("content://media/picker/1"), plan?.imageUris)
        assertTrue(plan!!.fileUris.isEmpty())
    }

    @Test
    fun `image uri is classified by top level type when per uri mime is missing`() {
        val plan = parse(
            SharedContentInput(
                action = SharedContentIntentParser.ACTION_SEND_MULTIPLE,
                type = "image/*",
                uris = listOf(
                    SharedUriInput("content://media/picker/2"),
                    SharedUriInput("content://media/picker/3"),
                ),
            ),
        )
        assertEquals(2, plan?.imageUris?.size)
        assertEquals(SharedContentOrigin.SHARE_MULTIPLE, plan?.origin)
    }

    @Test
    fun `image uri is classified by file name when no mime is available`() {
        val plan = parse(
            SharedContentInput(
                action = SharedContentIntentParser.ACTION_SEND,
                type = "*/*",
                uris = listOf(
                    SharedUriInput("content://downloads/photo.JPG?x=1", displayName = "photo.JPG"),
                    SharedUriInput("content://downloads/report", displayName = "report.pdf"),
                ),
            ),
        )
        assertEquals(listOf("content://downloads/photo.JPG?x=1"), plan?.imageUris)
        assertEquals(listOf("content://downloads/report"), plan?.fileUris)
    }

    @Test
    fun `http uri is not treated as an attachment`() {
        // 网页链接应该作为文本交给模型；当成附件去复制会得到一个打不开的“文件”。
        val plan = parse(
            SharedContentInput(
                action = SharedContentIntentParser.ACTION_SEND,
                type = "text/plain",
                text = "https://example.com/photo.jpg",
                uris = listOf(SharedUriInput("https://example.com/photo.jpg", mimeType = "image/jpeg")),
            ),
        )
        assertTrue(plan!!.imageUris.isEmpty())
        assertTrue(plan.fileUris.isEmpty())
        assertEquals("https://example.com/photo.jpg", plan.text)
    }

    @Test
    fun `duplicate uris are attached once`() {
        val plan = parse(
            SharedContentInput(
                action = SharedContentIntentParser.ACTION_SEND_MULTIPLE,
                type = "image/*",
                uris = listOf(
                    SharedUriInput("content://media/picker/9", mimeType = "image/jpeg"),
                    SharedUriInput(" content://media/picker/9 ", mimeType = "image/jpeg"),
                ),
            ),
        )
        assertEquals(1, plan?.imageUris?.size)
    }

    @Test
    fun `image count is capped with an explicit note`() {
        val uris = (1..10).map { index ->
            SharedUriInput("content://media/picker/$index", mimeType = "image/png")
        }
        val plan = parse(
            SharedContentInput(
                action = SharedContentIntentParser.ACTION_SEND_MULTIPLE,
                type = "image/*",
                uris = uris,
            ),
        )!!
        assertEquals(SharedContentIntentParser.MAX_IMAGES, plan.imageUris.size)
        val note = plan.notes.filterIsInstance<SharedContentNote.ImagesLimited>().single()
        assertEquals(10, note.offered)
        assertEquals(SharedContentIntentParser.MAX_IMAGES, note.accepted)
    }

    @Test
    fun `file count is capped with an explicit note`() {
        val uris = (1..25).map { index ->
            SharedUriInput("content://downloads/file$index.pdf", mimeType = "application/pdf")
        }
        val plan = parse(
            SharedContentInput(
                action = SharedContentIntentParser.ACTION_SEND_MULTIPLE,
                type = "application/pdf",
                uris = uris,
            ),
        )!!
        assertEquals(SharedContentIntentParser.MAX_FILES, plan.fileUris.size)
        val note = plan.notes.filterIsInstance<SharedContentNote.FilesLimited>().single()
        assertEquals(25, note.offered)
    }

    @Test
    fun `overlong text is truncated and reported`() {
        val plan = parse(
            SharedContentInput(
                action = SharedContentIntentParser.ACTION_SEND,
                text = "a".repeat(SharedContentIntentParser.MAX_TEXT_CHARS + 32),
            ),
        )!!
        assertEquals(SharedContentIntentParser.MAX_TEXT_CHARS, plan.text.length)
        val note = plan.notes.filterIsInstance<SharedContentNote.TextTruncated>().single()
        assertEquals(SharedContentIntentParser.MAX_TEXT_CHARS + 32, note.offeredChars)
        assertEquals(SharedContentIntentParser.MAX_TEXT_CHARS, note.keptChars)
    }

    @Test
    fun `image only share is still a valid plan`() {
        val plan = parse(
            SharedContentInput(
                action = SharedContentIntentParser.ACTION_SEND,
                type = "image/jpeg",
                uris = listOf(SharedUriInput("content://media/picker/4", mimeType = "image/jpeg")),
            ),
        )!!
        assertTrue(plan.text.isEmpty())
        assertEquals(1, plan.attachmentCount)
        assertTrue(!plan.isEmpty)
    }
}
