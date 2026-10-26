package io.github.mangi.eta.core

import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 启动 / 恢复诊断的落盘通道。
 *
 * 为什么需要：这类「账目行」（`startup …`、`journal boot …`、`run replay …`）原本只发 logcat，
 * 而一次 agent run 的 DEBUG 事件几分钟就能把环形缓冲顶掉 —— 实测安装后 3 分钟再去 `logcat -d`
 * 已经查不到 `journal boot`，而「启动为什么慢」恰恰总是事后才被追问。所以这里把极少量关键行
 * 另存一份到 `files/diagnostics/boot.log`，写满后只保留后一半。
 *
 * 两条约束：
 * - 调用方可能在主线程（启动打点就是），所以**只入队、不落盘**，实际写在单独线程做。
 * - 纯诊断通道，失败必须静默降级（记一次 warn 后本进程不再尝试），绝不允许影响启动。
 */
internal object BootLog {
    private const val MAX_BYTES = 128 * 1024L
    private const val KEEP_DIVISOR = 2

    @Volatile
    private var directory: File? = null

    @Volatile
    private var disabled = false

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private val pid = Process.myPid()

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "eta-bootlog").apply { isDaemon = true }
    }

    /** 由 [io.github.mangi.eta.EtaApp.onCreate] 调用；未初始化时 [append] 直接丢弃。 */
    fun init(dir: File) {
        directory = dir
    }

    fun append(message: String) {
        val dir = directory ?: return
        if (disabled) return
        // SimpleDateFormat 不是线程安全的：在调用线程格式化，写线程只负责落盘。
        // 带上 pid：主进程与 :voice 等进程写同一个 boot.log，没有 pid 时无法分辨某行是谁写的。
        val line = "${stamp.format(Date())} [$pid] $message"
        try {
            writer.execute { write(dir, line) }
        } catch (t: Throwable) {
            disabled = true
        }
    }

    private fun write(dir: File, line: String) {
        if (disabled) return
        try {
            val file = File(dir, "boot.log")
            if (!dir.exists()) dir.mkdirs()
            file.appendText(line + "\n")
            if (file.length() > MAX_BYTES) trim(file)
        } catch (t: Throwable) {
            disabled = true
            AndroidAgentLogger.warn("boot log disabled: ${t.javaClass.simpleName}")
        }
    }

    private fun trim(file: File) {
        val lines = file.readLines()
        val keep = lines.takeLast(maxOf(1, lines.size / KEEP_DIVISOR))
        file.writeText(keep.joinToString("\n").plus("\n"))
    }
}
