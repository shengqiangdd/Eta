package io.github.mangi.eta.ui.app

import android.content.ContentResolver
import androidx.core.net.toUri
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

/**
 * 把 Android 的分享 intent 摊平成纯数据。
 *
 * 这一层**只做 Android 形态的读取**：`EXTRA_STREAM` 的单值 / 列表两种历史形态、
 * `ClipData` 遍历、`ContentResolver.getType` 与 `OpenableColumns` 查询、以及
 * `Uri.lastPathSegment` 兜底。所有「怎么合并、怎么去重、按 action 分发」的判断都在
 * [SharedIntentContent] 里，那里能在普通 JVM 单测里跑（见 `SharedIntentContentTest`）。
 *
 * 这样切分的原因：Robolectric 在本机（Linux aarch64）起不来，而原先把判断和 Android
 * 读取混在一起，导致这些分支一个自动化用例都没有。
 */
internal object SharedContentIntentReader {

    fun read(intent: Intent?, resolver: ContentResolver): SharedContentInput? =
        SharedIntentContent.read(
            view = intent?.let(::AndroidSharedIntentView),
            metadata = AndroidSharedUriMetadata(resolver),
        )

    private class AndroidSharedIntentView(private val intent: Intent) : SharedIntentView {
        override val action: String? get() = intent.action
        override val type: String? get() = intent.type
        override val text: CharSequence? get() = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        override val subject: CharSequence? get() = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)
        override val processText: CharSequence?
            get() = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
        override val processTextReadOnly: Boolean
            get() = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)

        override val streamUriTexts: List<String> get() = intent.streamUriTexts()

        override val clipUriTexts: List<String>
            get() {
                val clip = intent.clipData ?: return emptyList()
                return buildList {
                    for (index in 0 until clip.itemCount) {
                        clip.getItemAt(index).uri?.let { add(it.toString()) }
                    }
                }
            }
    }

    private class AndroidSharedUriMetadata(
        private val resolver: ContentResolver,
    ) : SharedUriMetadata {
        override fun describe(uriText: String): SharedUriInput {
            val uri = runCatching { uriText.toUri() }.getOrNull()
                ?: return SharedUriInput(uri = uriText, displayName = null)
            return SharedUriInput(
                uri = uriText,
                mimeType = runCatching { resolver.getType(uri) }.getOrNull(),
                displayName = queryDisplayName(resolver, uri),
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamUriTexts(): List<String> {
        // EXTRA_STREAM 的类型随发送方而变：ACTION_SEND 是单个 Uri，SEND_MULTIPLE 是 ArrayList。
        // 直接按 ArrayList 读单个 Uri 会抛 ClassCastException（Bundle 会把它打成 warning
        // 并返回 null —— 既有日志噪音，也会掩盖真正的读取错误），所以两种形态各自容错。
        val single = runCatching { getParcelableExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull()
        val multiple = runCatching {
            getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        }.getOrNull()
        return buildList {
            single?.let { add(it.toString()) }
            multiple?.forEach { add(it.toString()) }
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return uri.lastPathSegment
        // 显示名只用于「按扩展名猜图片」，查不到就退回路径末段：不能因为某个 provider
        // 不支持 OpenableColumns 就丢掉这个附件。
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment
    }
}
