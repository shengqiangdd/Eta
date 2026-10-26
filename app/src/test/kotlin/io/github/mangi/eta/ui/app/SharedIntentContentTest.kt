package io.github.mangi.eta.ui.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分享 intent → 纯数据 的**合并层**（[SharedIntentContent]）。
 *
 * 为什么要有这一层测试：解析规则（[SharedContentIntentParser]）已经有单测，但
 * 「URI 到底以什么形态出现、怎么合并」并没有被覆盖过 —— 常见的有三种：
 *
 * 1. `EXTRA_STREAM` 是 `ArrayList<Uri>`（标准 SEND_MULTIPLE）；
 * 2. 只有 `ClipData`、`EXTRA_STREAM` 是空的（相册多选就是这样）；
 * 3. 同一批图同时出现在两处（有些分享方两样都填）。
 *
 * 漏一种，用户看到的就是「分享进来少了几张图」，还极难复现。这些判断现在全部落在
 * [SharedIntentContent] 里，所以能在普通 JVM 单测（无 Robolectric）里真跑。
 *
 * 覆盖边界（如实记录）：`Intent` → 视图的读取本身（`getParcelableExtra` 与
 * `getParcelableArrayListExtra` 的 ClassCastException 容错、`ClipData` 遍历、
 * `ContentResolver` 查询）仍在 `SharedContentIntentReader` 里，需要 Robolectric
 * 或真机；本机 aarch64 上 Robolectric 运行时不可用，那部分只能靠真机分享验证。
 */
class SharedIntentContentTest {

    private class FakeView(
        override val action: String?,
        override val type: String? = null,
        override val text: CharSequence? = null,
        override val subject: CharSequence? = null,
        override val processText: CharSequence? = null,
        override val processTextReadOnly: Boolean = false,
        override val streamUriTexts: List<String> = emptyList(),
        override val clipUriTexts: List<String> = emptyList(),
    ) : SharedIntentView

    /** 记录被查询过哪些 uri：重复 query 在真机上就是重复 IPC。 */
    private val described = mutableListOf<String>()

    private val metadata = SharedUriMetadata { uriText ->
        described += uriText
        SharedUriInput(
            uri = uriText,
            mimeType = if (uriText.endsWith(".png")) "image/png" else null,
            displayName = uriText.substringAfterLast('/'),
        )
    }

    private fun mediaUri(id: Int): String = "content://media/external/images/media/$id"

    private fun read(view: SharedIntentView?): SharedContentInput? =
        SharedIntentContent.read(view, metadata)

    @Test
    fun `send multiple reads every uri from extra stream`() {
        val input = requireNotNull(
            read(
                FakeView(
                    action = SharedContentIntentParser.ACTION_SEND_MULTIPLE,
                    type = "image/*",
                    streamUriTexts = listOf(mediaUri(1), mediaUri(2), mediaUri(3)),
                ),
            ),
        )

        assertEquals(3, input.uris.size)
        assertEquals(
            listOf(mediaUri(1), mediaUri(2), mediaUri(3)),
            input.uris.map { it.uri },
        )

        val plan = requireNotNull(SharedContentIntentParser.parse(input))
        assertEquals(SharedContentOrigin.SHARE_MULTIPLE, plan.origin)
        assertEquals(3, plan.imageUris.size)
        assertEquals(0, plan.notes.size)
    }

    @Test
    fun `clip data only payload is read like a gallery multi selection`() {
        val input = requireNotNull(
            read(
                FakeView(
                    action = SharedContentIntentParser.ACTION_SEND_MULTIPLE,
                    type = "image/*",
                    clipUriTexts = listOf(mediaUri(7), mediaUri(8), mediaUri(9)),
                ),
            ),
        )

        assertEquals(3, input.uris.size)
        val plan = requireNotNull(SharedContentIntentParser.parse(input))
        assertEquals(3, plan.imageUris.size)
    }

    @Test
    fun `single send payload is read as one attachment`() {
        val input = requireNotNull(
            read(
                FakeView(
                    action = SharedContentIntentParser.ACTION_SEND,
                    type = "image/png",
                    streamUriTexts = listOf(mediaUri(5)),
                ),
            ),
        )

        assertEquals(1, input.uris.size)
        val plan = requireNotNull(SharedContentIntentParser.parse(input))
        assertEquals(1, plan.imageUris.size)
        assertEquals(SharedContentOrigin.SHARE, plan.origin)
    }

    @Test
    fun `same uri in extra stream and clip data is counted once`() {
        val input = requireNotNull(
            read(
                FakeView(
                    action = SharedContentIntentParser.ACTION_SEND_MULTIPLE,
                    type = "image/*",
                    streamUriTexts = listOf(mediaUri(1)),
                    clipUriTexts = listOf(mediaUri(1), mediaUri(2)),
                ),
            ),
        )

        assertEquals(listOf(mediaUri(1), mediaUri(2)), input.uris.map { it.uri })
        // 去重必须发生在查询元数据之前：重复 uri 不应触发重复的 resolver 查询。
        assertEquals(listOf(mediaUri(1), mediaUri(2)), described)
    }

    @Test
    fun `extra stream uris keep their order ahead of clip data uris`() {
        val input = requireNotNull(
            read(
                FakeView(
                    action = SharedContentIntentParser.ACTION_SEND_MULTIPLE,
                    type = "image/*",
                    streamUriTexts = listOf(mediaUri(3), mediaUri(1)),
                    clipUriTexts = listOf(mediaUri(2)),
                ),
            ),
        )

        // 顺序即「用户在多选里的先后」，不能被 LinkedHashSet 打乱成字典序或倒序。
        assertEquals(
            listOf(mediaUri(3), mediaUri(1), mediaUri(2)),
            input.uris.map { it.uri },
        )
    }

    @Test
    fun `blank uri entries are ignored`() {
        val input = requireNotNull(
            read(
                FakeView(
                    action = SharedContentIntentParser.ACTION_SEND_MULTIPLE,
                    type = "image/*",
                    streamUriTexts = listOf("", "   ", mediaUri(4)),
                    clipUriTexts = listOf(""),
                ),
            ),
        )

        assertEquals(listOf(mediaUri(4)), input.uris.map { it.uri })
    }

    @Test
    fun `share without attachments keeps text only`() {
        val input = requireNotNull(
            read(
                FakeView(
                    action = SharedContentIntentParser.ACTION_SEND,
                    type = "text/plain",
                    text = "  看看这个  ",
                ),
            ),
        )

        assertTrue(input.uris.isEmpty())
        assertEquals("看看这个", requireNotNull(SharedContentIntentParser.parse(input)).text)
        assertTrue(described.isEmpty())
    }

    @Test
    fun `process text reads the selection and the read only flag`() {
        val input = requireNotNull(
            read(
                FakeView(
                    action = SharedContentIntentParser.ACTION_PROCESS_TEXT,
                    type = "text/plain",
                    processText = "选中的一段文字",
                    processTextReadOnly = true,
                ),
            ),
        )

        assertEquals("选中的一段文字", input.processText)
        assertTrue(input.processTextReadOnly)
        // 选中文本入口没有附件，也不该去查任何 uri 元数据。
        assertTrue(input.uris.isEmpty())
        assertTrue(described.isEmpty())

        val plan = requireNotNull(SharedContentIntentParser.parse(input))
        assertEquals("选中的一段文字", plan.text)
        assertEquals(SharedContentOrigin.PROCESS_TEXT, plan.origin)
    }

    @Test
    fun `process text default is not read only`() {
        val input = requireNotNull(
            read(
                FakeView(
                    action = SharedContentIntentParser.ACTION_PROCESS_TEXT,
                    processText = "临时选区",
                ),
            ),
        )

        assertFalse(input.processTextReadOnly)
    }

    @Test
    fun `unrelated action is ignored`() {
        assertNull(read(FakeView(action = "android.intent.action.VIEW")))
        assertNull(read(FakeView(action = null)))
        assertNull(read(null))
    }
}
