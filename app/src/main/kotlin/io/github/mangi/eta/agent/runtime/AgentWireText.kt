package io.github.mangi.eta.agent.runtime

import android.os.Binder
import android.os.Message
import android.os.Messenger
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/** 大文本通过已取消目录链接的只读文件传输；Binder 只携带描述符与长度。 */
internal object AgentWireText {
    private const val INLINE_CHARS = 16_384
    private const val FD_SUFFIX = "_eta_text_fd"
    private const val SIZE_SUFFIX = "_eta_text_bytes"
    // 单次物化的内存保护，超过时拒绝交付，不截断已落盘的历史。
    private const val MAX_TRANSFER_BYTES = 64L * 1024 * 1024

    fun put(bundle: Bundle, key: String, text: String, directory: File?) {
        if (directory == null || text.length <= INLINE_CHARS) {
            bundle.putString(key, text)
            return
        }
        require(text.length <= MAX_TRANSFER_BYTES) { "历史传输超过单次内存预算，原始记录仍保留" }
        val file = File.createTempFile("eta-context-", ".json", directory)
        try {
            // 流式写：不再先 toByteArray() 拷一份整块字节（30 MB 历史时就是 30 MB 堆内存 + 一次全量拷贝）。
            file.outputStream().bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(text) }
            val size = file.length()
            require(size in 0..MAX_TRANSFER_BYTES) { "历史传输超过单次内存预算，原始记录仍保留" }
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            bundle.putParcelable(key + FD_SUFFIX, descriptor)
            bundle.putLong(key + SIZE_SUFFIX, size)
        } finally {
            check(file.delete()) { "Cannot unlink history transfer file" }
        }
    }

    fun hasDescriptor(bundle: Bundle, key: String): Boolean = bundle.containsKey(key + FD_SUFFIX)

    fun read(bundle: Bundle, key: String): String? {
        val descriptor = bundle.getParcelable(key + FD_SUFFIX, ParcelFileDescriptor::class.java)
            ?: return bundle.getString(key)
        bundle.remove(key + FD_SUFFIX)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val size = bundle.getLong(key + SIZE_SUFFIX, -1)
            require(size in 0..MAX_TRANSFER_BYTES && descriptor.statSize == size) {
                "Invalid history transfer descriptor"
            }
            // 同样流式解码：不再一次性 ByteArray(size) 再转 String。
            val counting = CountingInputStream(input)
            val text = counting.reader(Charsets.UTF_8).readText()
            check(counting.count == size) { "History transfer size mismatch" }
            check(input.read() == -1) { "History transfer size mismatch" }
            text
        }
    }

    /** 同进程 Messenger 不经过 Parcel；必须显式复制接收端的描述符所有权。 */
    fun send(target: Messenger?, message: Message) {
        val original = message.data
        var localCopy: Bundle? = null
        try {
            if (target == null) return
            if (target.binder is Binder) {
                val copy = Bundle(original)
                localCopy = copy
                original.keySet().filter { it.endsWith(FD_SUFFIX) }.forEach { key ->
                    copy.putParcelable(key, original.getParcelable(key, ParcelFileDescriptor::class.java)!!.dup())
                }
                message.data = copy
            }
            target.send(message)
            localCopy = null
        } finally {
            localCopy?.let(::close)
            close(original)
        }
    }

    fun close(bundle: Bundle) {
        bundle.keySet().filter { it.endsWith(FD_SUFFIX) }.forEach { key ->
            bundle.getParcelable(key, ParcelFileDescriptor::class.java)?.close()
            bundle.remove(key)
        }
    }
}

/** 只用来核对「读到的字节数 == 声明的字节数」，不参与内容解析。 */
private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) count += it }
}
