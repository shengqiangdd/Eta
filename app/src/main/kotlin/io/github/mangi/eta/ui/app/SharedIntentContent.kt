package io.github.mangi.eta.ui.app

/**
 * 分享 intent 的**最小只读视图**，用来把「Android 的 Intent 长什么样」挡在适配器里。
 *
 * 为什么要有这一层：[SharedContentIntentReader] 原本直接吃 `Intent` 与 `ContentResolver`，
 * 于是它的分支（单值 / 列表 / 只有 ClipData / 去重顺序）只能在 Robolectric 里跑，
 * 而那套测试在本机（Linux aarch64）根本起不来 —— 等于这些分支没有任何自动化守着。
 * 分享一旦漏读，用户看到的是「分享进来少了几张图」，还很难复现。
 *
 * 这里只保留纯数据形状，读取形态差异（`EXTRA_STREAM` 单值或 ArrayList、`ClipData` 遍历、
 * `ContentResolver.getType` / `OpenableColumns`）由 `SharedContentIntentReader` 负责。
 */
internal interface SharedIntentView {
    val action: String?
    val type: String?
    val text: CharSequence?
    val subject: CharSequence?

    /** 选中文本入口（`ACTION_PROCESS_TEXT`）带来的选区。 */
    val processText: CharSequence?
    val processTextReadOnly: Boolean

    /** `EXTRA_STREAM` 里的 uri 文本，适配器已按单值 / 列表两种形态容错。 */
    val streamUriTexts: List<String>

    /** `ClipData` 里的 uri 文本；相册多选常常只给这一份，`EXTRA_STREAM` 是空的。 */
    val clipUriTexts: List<String>
}

/** 单个 uri 的元数据（mime / 显示名）查询；Android 版走 ContentResolver，测试给假实现。 */
internal fun interface SharedUriMetadata {
    fun describe(uriText: String): SharedUriInput
}

/**
 * intent → [SharedContentInput] 的纯逻辑部分。
 *
 * 取舍规则不在这里（那是 [SharedContentIntentParser] 的职责），这里只负责
 * 「按 action 分发」与「把两处 uri 合成一份有序、去重的附件列表」。
 */
internal object SharedIntentContent {

    fun read(view: SharedIntentView?, metadata: SharedUriMetadata): SharedContentInput? {
        val action = view?.action ?: return null
        return when (action) {
            SharedContentIntentParser.ACTION_PROCESS_TEXT -> SharedContentInput(
                action = action,
                type = view.type,
                processText = view.processText?.toString(),
                processTextReadOnly = view.processTextReadOnly,
            )

            SharedContentIntentParser.ACTION_SEND,
            SharedContentIntentParser.ACTION_SEND_MULTIPLE,
            -> {
                // 顺序按「先 EXTRA_STREAM，后 ClipData」；重复的只留第一次出现的位置。
                // 顺序有意义：用户在多选相册里先点的那张应当排在最前面。
                val uris = LinkedHashSet<String>()
                view.streamUriTexts.forEach { if (it.isNotBlank()) uris += it }
                view.clipUriTexts.forEach { if (it.isNotBlank()) uris += it }
                SharedContentInput(
                    action = action,
                    type = view.type,
                    text = view.text?.toString(),
                    subject = view.subject?.toString(),
                    uris = uris.map(metadata::describe),
                )
            }

            else -> null
        }
    }
}
