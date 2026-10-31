package io.github.mangi.eta.agent.terminal

/** revision 是后端生成的不透明值，只在同一后端内比较；目录内容变化必须使其失效。 */
internal data class FileToolStat(
    val path: String,
    val name: String,
    val kind: FileToolKind,
    val sizeBytes: Long,
    val revision: String,
)

internal enum class FileToolKind(val wireName: String) {
    FILE("file"), DIRECTORY("directory"), SYMLINK("symlink"), OTHER("other"),
}

internal data class FileToolDirectoryPage(
    val entries: List<FileToolStat>,
    val nextOffset: Int,
    val hasMore: Boolean,
    val revision: String,
)

internal data class FileToolWriteResult(
    val stat: FileToolStat,
    val atomic: Boolean,
)

/**
 * 一次往返里取到的「读前元数据 + 内容 + 读后元数据」。
 * 搜索路径靠它把每个文件的三次后端往返（stat → read → stat）压成一次：
 * 在 Android 上每次往返都是一次 su，Linux 还要进一次 chroot，差距直接决定 5 秒能扫几个文件。
 * 不能写成 data class：里面的 ByteArray 会让 equals/hashCode 按引用比，容易误用。
 */
internal class FileToolCheckedRead(
    val bytes: ByteArray,
    val statBefore: FileToolStat,
    val statAfter: FileToolStat,
)

/**
 * 批量读取里的一条结果：要么读到内容，要么给出逐文件错误码。
 * 单文件失败**不能**中断整批——搜索对单文件失败的处理是「跳过并继续」。
 */
internal class FileToolBatchRead(
    val path: String,
    val checked: FileToolCheckedRead? = null,
    val errorCode: String? = null,
)

/** 预扫描得到的一个条目：相对路径与类型。符号链接不跟随，但仍要出现在列表里，才能照旧计入 skip。 */
internal class FileToolWalkEntry(val relative: String, val kind: FileToolKind)

/**
 * 一次预扫描的结果。
 *
 * [truncated] 为 true 表示列表被上限截断（条目上限或输出预算）。
 * [unreadable] 为 true 表示扫描过程中有目录报错（`find` 的 stderr 非空）：这时列表可能只是
 * 「少了一些文件」而看不出任何标记 —— 调用方必须把它当成不完整，不能报 complete。
 * [problem] 是首条错误摘要，只用于给用户的提示，不代表完整诊断。
 */
internal class FileToolWalk(
    val entries: List<FileToolWalkEntry>,
    val truncated: Boolean,
    val unreadable: Boolean = false,
    val problem: String? = null,
)

/** 这些错误意味着整次操作都不可继续，必须原样抛给调用方，不能按「跳过这个文件」处理。 */
internal fun FileToolException.isGlobalFailure(): Boolean = code in setOf(
    "STALE_CURSOR", "FILE_CHANGED", "ROOT_REQUIRED", "CANCELLED", "TERMINAL_CLOSED",
    "LINUX_ENVIRONMENT_NOT_READY", "LINUX_ENVIRONMENT_REQUIRES_ROOT", "INVALID_IDENTITY",
    "FILE_TIMEOUT", "FILE_PROCESS_FAILED",
)

internal class FileToolException(
    val code: String,
    message: String,
    /** 可选的结构化提示，例如最近似的实际文本；只用于帮助模型自纠，不改变失败语义。 */
    val hint: org.json.JSONObject? = null,
) : Exception(message)

/** 参数中的路径均属于后端自身的命名空间；实现必须在每次操作时重新检查访问边界。 */
internal interface FileToolBackend {
    val environment: String
    val identity: String

    /**
     * 单次读取的字节上限。
     *
     * 为什么要有它：不同后端能力不同（本地后端 16 KiB、shell 后端 512 KiB），而
     * `readBytes` 对超出上限的请求会直接抛参数错误。调用方（比如 code_outline 的批量读）
     * 必须先按它夹取，否则整次调用会变成一句笼统的「参数无效」，在本地后端上必现。
     */
    val maxReadBytes: Int
    fun checkActive() = Unit
    fun resolve(path: String): String
    fun stat(path: String): FileToolStat
    fun readBytes(path: String, offset: Long, limit: Int): ByteArray

    /**
     * 读一段字节，同时给出前后两次元数据（用于判断读取期间文件是否被改动）。
     * 默认实现就是 stat → readBytes → stat；能合成的后端应覆盖它，减少往返次数。
     */
    fun readChecked(path: String, offset: Long, limit: Int): FileToolCheckedRead {
        val before = stat(path)
        val bytes = readBytes(path, offset, limit)
        return FileToolCheckedRead(bytes, before, stat(path))
    }

    /**
     * 一次往返读多个文件。搜索热路径里「每个文件一条命令」是主要开销：
     * Android 上每条命令都是一次 su，Linux 上要进一次 chroot。
     * 默认实现退化为逐个读（语义与 readChecked 完全一致）；能合成的后端应覆盖它。
     */
    fun readCheckedBatch(paths: List<String>, offset: Long, limit: Int): List<FileToolBatchRead> =
        paths.map { path ->
            try {
                FileToolBatchRead(path, readChecked(path, offset, limit))
            } catch (error: FileToolException) {
                if (error.isGlobalFailure()) throw error
                FileToolBatchRead(path, errorCode = error.code)
            }
        }

    /**
     * 一次拿到整棵子树里的条目（普通文件 + 符号链接），按相对路径字典序。
     *
     * 为什么需要它：搜索原本逐目录 list()，而每个目录至少一条后端命令（Android 是 su，
     * Linux 是 chroot）。`app/src` 有 163 个目录、1114 个文件，实测 `find -type f` 只要 8ms，
     * 但旧实现 5 秒只列出 39 个条目 —— 预算全花在往返上。
     *
     * 为什么连符号链接一起要：旧遍历会把符号链接记为 SYMLINK_NOT_FOLLOWED 并计入 skipped，
     * 只列普通文件会让「搜索不完整」这件事静默消失。
     * 目录本身不返回（调用方只需要文件）；不可读目录里的文件会直接缺失，这一点无法从这里恢复，
     * 所以调用方在拿到 truncated 之外，仍要如实标注搜索是否完整。
     *
     * 返回 null 表示后端不支持预扫描，调用方回退到逐目录遍历。
     * truncated=true 表示命中了 maxEntries 或输出预算，列表不完整。
     */
    fun walkFiles(root: String, includeHidden: Boolean, maxEntries: Int): FileToolWalk? = null

    /** offset 为目录原始枚举位置，不能在分页前过滤。顺序由后端保持，变动通过 revision 检测。 */
    fun list(path: String, offset: Int, limit: Int, expectedRevision: String? = null): FileToolDirectoryPage

    /**
     * expectedSha256 用于编辑时校验读到的完整旧内容；不匹配必须返回 FILE_CHANGED 且不写入。
     * expectedRevision 为可选元数据前置条件；atomic 只描述替换可见性，不承诺跨进程互斥。
     */
    fun write(
        path: String,
        bytes: ByteArray,
        append: Boolean,
        expectedRevision: String? = null,
        expectedSha256: String? = null,
    ): FileToolWriteResult
}
