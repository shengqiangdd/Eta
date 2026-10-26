package io.github.mangi.eta.ui.app

import java.util.Locale

/**
 * 系统分享 / 选中文本进入 Eta 的解析层。
 *
 * 这里只放纯 JVM 逻辑：Activity 把 `Intent` 摊平成 [SharedContentInput]，
 * 解析规则在这里决定「哪些算文本、哪些算图片、哪些算文件、超限怎么处理」。
 * 这样规则可以在普通单测里逐条验证（Android 的 Intent 在 JVM 单测里只是空壳，
 * 拿它当被测对象等于什么都没测）。
 *
 * 上限不是装饰：分享方可以一次塞进几百张图，全部接住会把主线程和内存一起拖垮。
 * 但静默丢弃会变成「用户以为发出去了」，所以每次截断都产出 [SharedContentNote]，
 * 由界面如实说明，而不是假装全部收下。
 */
data class SharedUriInput(
    val uri: String,
    val mimeType: String? = null,
    val displayName: String? = null,
)

enum class SharedContentOrigin { SHARE, SHARE_MULTIPLE, PROCESS_TEXT }

data class SharedContentInput(
    val action: String?,
    val type: String? = null,
    val text: String? = null,
    val subject: String? = null,
    val uris: List<SharedUriInput> = emptyList(),
    val processText: String? = null,
    val processTextReadOnly: Boolean = false,
)

/** 被截断 / 被忽略的部分；文案由界面按语言资源渲染。 */
sealed interface SharedContentNote {
    data class TextTruncated(val offeredChars: Int, val keptChars: Int) : SharedContentNote

    data class ImagesLimited(val offered: Int, val accepted: Int) : SharedContentNote

    data class FilesLimited(val offered: Int, val accepted: Int) : SharedContentNote
}

data class SharedContentPlan(
    val text: String,
    val imageUris: List<String>,
    val fileUris: List<String>,
    val origin: SharedContentOrigin,
    val notes: List<SharedContentNote> = emptyList(),
) {
    val isEmpty: Boolean get() = text.isBlank() && imageUris.isEmpty() && fileUris.isEmpty()

    val attachmentCount: Int get() = imageUris.size + fileUris.size
}

object SharedContentIntentParser {
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
    const val ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT"

    /** 单次接收的文本上限；超出部分截断并提示，不做静默丢弃。 */
    const val MAX_TEXT_CHARS = 100_000
    const val MAX_IMAGES = 8
    const val MAX_FILES = 20

    private val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif",
    )

    fun parse(input: SharedContentInput): SharedContentPlan? {
        val action = input.action ?: return null
        return when (action) {
            ACTION_PROCESS_TEXT -> parseProcessText(input)
            ACTION_SEND, ACTION_SEND_MULTIPLE -> parseShare(
                input = input,
                multiple = action == ACTION_SEND_MULTIPLE,
            )
            else -> null
        }
    }

    private fun parseProcessText(input: SharedContentInput): SharedContentPlan? {
        val raw = input.processText?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val notes = mutableListOf<SharedContentNote>()
        val text = limitText(raw, notes)
        return SharedContentPlan(
            text = text,
            imageUris = emptyList(),
            fileUris = emptyList(),
            origin = SharedContentOrigin.PROCESS_TEXT,
            notes = notes,
        )
    }

    private fun parseShare(
        input: SharedContentInput,
        multiple: Boolean,
    ): SharedContentPlan? {
        val notes = mutableListOf<SharedContentNote>()
        val text = limitText(mergeShareText(input.text, input.subject), notes)
        // 只接受本机能打开的本机 uri：http(s) 之类应该作为文本交给模型，而不是当成附件去复制。
        val candidates = input.uris.asSequence()
            .map { candidate ->
                candidate.copy(
                    uri = candidate.uri.trim(),
                    mimeType = candidate.mimeType?.trim()?.lowercase(Locale.ROOT),
                )
            }
            .filter { candidate -> candidate.uri.isLocalUri() }
            .distinctBy { candidate -> candidate.uri }
            .toList()
        // 顶层 type 是 image/* 时，把没有逐个 mime 的 uri 也按图片处理：
        // 不少分享方只给 Intent type，不给每个 uri 的 type。
        val defaultIsImage = input.type?.startsWith("image/", ignoreCase = true) == true
        val images = candidates.filter { candidate -> candidate.isImage(defaultIsImage) }
        val files = candidates.filterNot { candidate -> candidate.isImage(defaultIsImage) }
        if (images.size > MAX_IMAGES) {
            notes += SharedContentNote.ImagesLimited(offered = images.size, accepted = MAX_IMAGES)
        }
        if (files.size > MAX_FILES) {
            notes += SharedContentNote.FilesLimited(offered = files.size, accepted = MAX_FILES)
        }
        val plan = SharedContentPlan(
            text = text,
            imageUris = images.take(MAX_IMAGES).map { candidate -> candidate.uri },
            fileUris = files.take(MAX_FILES).map { candidate -> candidate.uri },
            origin = if (multiple) SharedContentOrigin.SHARE_MULTIPLE else SharedContentOrigin.SHARE,
            notes = notes,
        )
        return plan.takeUnless { it.isEmpty }
    }

    private fun limitText(
        text: String,
        notes: MutableList<SharedContentNote>,
    ): String {
        val trimmed = text.trim()
        if (trimmed.length <= MAX_TEXT_CHARS) return trimmed
        notes += SharedContentNote.TextTruncated(
            offeredChars = trimmed.length,
            keptChars = MAX_TEXT_CHARS,
        )
        return trimmed.take(MAX_TEXT_CHARS)
    }

    /**
     * 分享方常见的两种形态：网页分享是「标题 + 链接」，纯文本分享只有正文。
     * 标题已经在正文里时不再重复插入，避免把一段话拼两遍。
     */
    private fun mergeShareText(text: String?, subject: String?): String {
        val body = text?.trim().orEmpty()
        val title = subject?.trim().orEmpty()
        return when {
            title.isEmpty() -> body
            body.isEmpty() -> title
            body.contains(title) -> body
            else -> "$title\n\n$body"
        }
    }

    private fun String.isLocalUri(): Boolean =
        startsWith("content://", ignoreCase = true) || startsWith("file://", ignoreCase = true)

    private fun SharedUriInput.isImage(defaultIsImage: Boolean): Boolean {
        val mime = mimeType
        if (!mime.isNullOrBlank()) return mime.startsWith("image/")
        if (defaultIsImage) return true
        val name = (displayName ?: uri.substringAfterLast('/'))
            .substringBefore('?')
            .substringAfterLast('/')
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in IMAGE_EXTENSIONS
    }
}
