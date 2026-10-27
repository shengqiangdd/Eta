package io.github.mangi.eta.agent.acp

import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.concurrent.TimeUnit

/**
 * 清掉上一代 Eta 进程留下的 ACP 代理进程。
 *
 * 委派链路是「Eta 进程 → su/sh → 代理」三层。App 被杀或重启时，中间层收不到通知，
 * 代理会被 init 收养并继续跑 —— 真机（PKX110，2026-10-27）抓到 `codex-acp` 残留 10 分钟仍在。
 *
 * **为什么必须走 root（真机实测，2026-10-27 22:15）**：旧实现直接读 `/proc/<pid>/cmdline`
 * 并发 `Process.sendSignal`，而代理进程是 **root 身份**（`su` 拉起的），App（uid 10629）读别的 uid 的
 * `/proc` 条目是 DENIED、发信号也是 DENIED。于是「清理」全程安静地什么也没做：
 * 真机上 8 个残留进程一个都没被收掉。现在整段扫描与 kill 都通过 `su -c` 执行。
 *
 * 只清理命令行里带 [ETA_RUNTIME_ROOT]（Eta 自己的 Linux 运行时目录）的进程：
 * 用户在自己终端里手动装的 codex 不在这个目录下，不会被误杀。
 */
internal object AcpOrphanReaper {

    /** Eta Linux 运行时的固定路径前缀；我们拉起的代理都在里面。 */
    internal const val ETA_RUNTIME_ROOT = "/opt/eta/"

    /** 沿父链最多看几层（Eta → su → sh → 代理 已经够用）。 */
    internal const val MAX_ANCESTOR_DEPTH = 6

    /** 两次扫描的最小间隔：每次委派都会调用，别把 `su -c` 当免费操作。 */
    private const val MIN_INTERVAL_MS = 60_000L

    internal data class Proc(val pid: Int, val ppid: Int, val uid: Int, val cmdline: String)

    /**
     * 从进程表里挑出「属于 Eta 运行时、但祖先链上已经没有 Eta 进程」的 pid。
     *
     * 判据用 **uid** 而不是 pid：App 重启后 pid 会变，而「Eta 的 uid」在同一次安装里稳定；
     * 代理本身由 root 拉起（uid 0），所以「uid 不是 Eta」不代表能杀，还要看祖先有没有 Eta。
     *
     * 纯函数，便于在没有 /proc 的 JVM 单测里覆盖边界。
     */
    internal fun selectOrphans(
        procs: List<Proc>,
        etaUid: Int,
        runtimeRoot: String = ETA_RUNTIME_ROOT,
    ): List<Int> {
        val byPid = procs.associateBy { it.pid }
        return procs.asSequence()
            .filter { it.pid > 1 }
            .filter { it.uid != etaUid }
            .filter { it.cmdline.contains(runtimeRoot) }
            .filter { proc -> !hasEtaAncestor(proc, byPid, etaUid) }
            .map { it.pid }
            .toList()
    }

    private fun hasEtaAncestor(proc: Proc, byPid: Map<Int, Proc>, etaUid: Int): Boolean {
        var current = proc
        repeat(MAX_ANCESTOR_DEPTH) {
            val parent = byPid[current.ppid] ?: return false
            if (parent.uid == etaUid) return true
            current = parent
        }
        return false
    }

    @Volatile
    private var lastAttemptAt = 0L

    /** 清理失败不影响委派本身；60 秒内最多扫一次。 */
    fun reapOnce() {
        val now = System.currentTimeMillis()
        if (now - lastAttemptAt < MIN_INTERVAL_MS) return
        synchronized(this) {
            if (now - lastAttemptAt < MIN_INTERVAL_MS) return
            lastAttemptAt = now
        }
        val etaUid = android.os.Process.myUid()
        val procs = runCatching { readProcesses() }.getOrNull().orEmpty()
        if (procs.isEmpty()) return
        val orphans = selectOrphans(procs, etaUid)
        if (orphans.isEmpty()) return
        AndroidAgentLogger.warn(
            "ACP 清理孤儿代理：count=${orphans.size} pids=${orphans.joinToString(",")}",
        )
        runCatching { killProcesses(orphans) }
    }

    /**
     * 以 root 扫一遍进程表。输出每行 `pid|ppid|uid|cmdline`。
     *
     * 必须 root：非本 uid 的 `/proc/<pid>/cmdline` 普通 App 读不到（SELinux + uid 限制），
     * 拿不到命令行就永远选不出候选，旧实现正是这样静默失效的。
     */
    private fun readProcesses(): List<Proc> {
        val script = """
            for d in /proc/[0-9]*; do
              p=${'$'}{d#/proc/}
              c=${'$'}(tr '\0' ' ' < ${'$'}d/cmdline 2>/dev/null)
              [ -n "${'$'}c" ] || continue
              st=${'$'}(awk '/^PPid:/{pp=${'$'}2} /^Uid:/{u=${'$'}2} END{print pp"|"u}' ${'$'}d/status 2>/dev/null)
              echo "${'$'}p|${'$'}st|${'$'}c"
            done
        """.trimIndent()
        val output = runRoot(script, timeoutSeconds = 5) ?: return emptyList()
        return output.lineSequence().mapNotNull { line ->
            val parts = line.split('|', limit = 4)
            if (parts.size < 4) return@mapNotNull null
            val pid = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
            val ppid = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
            val uid = parts[2].trim().toIntOrNull() ?: return@mapNotNull null
            Proc(pid = pid, ppid = ppid, uid = uid, cmdline = parts[3])
        }.toList()
    }

    private fun killProcesses(pids: List<Int>) {
        val script = "kill -TERM " + pids.joinToString(" ") + " 2>/dev/null; true"
        runRoot(script, timeoutSeconds = 5)
    }

    /**
     * 杀掉某次 ACP 会话拉起的全部进程（root 身份）。
     *
     * 为什么不用 pid：Android 的 `java.lang.Process` **没有 pid()**（实测 android.jar 里就没有），
     * 而 `destroyForcibly()` 只杀直接子进程（那个 `su`）。代理实际有四层：
     * su → sh → node → 平台二进制，中间层一死，下层就被 init 收养继续跑。
     * 真机（PKX110，2026-10-27）8 个残留里 6 个是这样漏下的。
     *
     * 所以改用**环境变量标签**认人：委派时在脚本里 `export ETA_ACP_SESSION=<id>`，
     * 子进程会继承它，收尾时按标签找进程杀掉。标签不命中就什么也不做，不会误杀。
     */
    internal fun killSessionProcesses(sessionId: String) {
        if (sessionId.isBlank()) return
        val script = """
            marker=ETA_ACP_SESSION=$sessionId
            for d in /proc/[0-9]*; do
              pid=${'$'}{d#/proc/}
              [ "${'$'}pid" = "${'$'}${'$'}" ] && continue
              if tr '\0' '\n' < ${'$'}d/environ 2>/dev/null | grep -qx "${'$'}marker"; then
                echo ${'$'}pid
              fi
            done
        """.trimIndent()
        val output = runRoot(script, timeoutSeconds = 8) ?: return
        val pids = output.lineSequence().mapNotNull { it.trim().toIntOrNull() }.toList()
        if (pids.isEmpty()) return
        val joined = pids.joinToString(" ")
        runRoot("kill -TERM $joined 2>/dev/null; sleep 0.2; kill -KILL $joined 2>/dev/null; true", 8)
    }

    private fun runRoot(script: String, timeoutSeconds: Long): String? = runCatching {
        val process = ProcessBuilder("su", "-c", script)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else {
            output
        }
    }.getOrNull()
}
