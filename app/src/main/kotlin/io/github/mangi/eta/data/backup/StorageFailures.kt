package io.github.mangi.eta.data.backup

/**
 * 存储类失败的识别。
 *
 * 为什么需要这一层：Android 的 IOException ／ SQLiteException 只会给出英文 errno 串，
 * 例如 `write failed: ENOSPC (No space left on device)` 或
 * `database or disk is full (code 13 SQLITE_FULL)`。把它原样塞进 Toast，
 * 用户看到的是「备份操作失败」级别的一团雾；而导入/备份这条链路上，
 * 空间不足是最常见的失败之一（导入要把整份备份解压到应用缓存，406 MB 的库就要同量级临时空间），
 * 该做的是去清存储，不是换一个备份文件重试。
 */
internal object StorageFailures {
    private val OUT_OF_SPACE = listOf(
        "ENOSPC",
        "No space left on device",
        "Disk quota exceeded",
        "SQLITE_FULL",
        "disk is full",
    )

    private val NOT_WRITABLE = listOf(
        "EACCES",
        "EPERM",
        "EROFS",
        "Read-only file system",
        "Permission denied",
        "Unable to create backup staging directory",
    )

    fun isOutOfSpace(failure: Throwable?): Boolean = matches(failure, OUT_OF_SPACE)

    fun isNotWritable(failure: Throwable?): Boolean = matches(failure, NOT_WRITABLE)

    /** 沿 cause 链最多看 8 层：真正的原因常被包装在「格式无效」之类的外层后面。 */
    private fun matches(failure: Throwable?, needles: List<String>): Boolean {
        var current = failure
        var depth = 0
        while (current != null && depth < 8) {
            val message = current.message
            if (message != null && needles.any { message.contains(it, ignoreCase = true) }) return true
            current = current.cause
            depth++
        }
        return false
    }
}
