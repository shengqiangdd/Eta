package io.github.mangi.eta.agent.terminal

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ThreadLocalRandom

/**
 * 终端环境变量的注入方式：值只写进一次性的私有脚本，命令行里只出现文件路径。
 *
 * 旧做法是把 `export KEY='value';` 直接拼进命令串，于是它变成 `sh -c` / `su -c` 的 argv，
 * `ps` 对任何进程都看得见——用户就是这么发现 GitHub token 出现在 `ps` 里的。
 * 现在值只存在于 0600 文件里，shell `source` 完成后立刻删除；命令行、日志里都不再出现值。
 *
 * 两个落点：
 * - Android：App 私有目录 `files/terminal-user/env.d`，root 与 App 身份都能读；
 * - Linux（chroot / proot）：必须写进 rootfs 内部，因为 guest 看不到宿主路径。
 */
internal class TerminalEnvInjection(
    /** 宿主侧真实目录（写文件用）。 */
    private val hostDirectory: File,
    /** shell 里看到的位置；默认与宿主一致，Linux 侧是 guest 路径（如 /tmp）。 */
    private val shellDirectory: String = hostDirectory.absolutePath,
    /**
     * 目录是否归我们独占：是则顺手收紧到 0700。
     *
     * Linux 的落点是 guest 的 `/tmp`，绝不能改它的权限——那里通常是 1777，
     * 里面的其他程序都依赖它可写；改坏的是整个发行版的临时目录。
     */
    private val ownsDirectory: Boolean = true,
) {
    /**
     * 返回可直接拼进 shell 的单行片段；没有可注入的变量时返回 `null`。
     *
     * 写文件失败会抛出异常，由调用方决定降级——绝不回退成「把值拼进命令行」。
     */
    fun script(entries: List<TerminalEnvEntry>): String? {
        val safe = entries.filter { TerminalEnvRules.isInjectable(it) }
        if (safe.isEmpty()) return null
        sweepStale()
        val file = write(safe)
        val path = shellQuote(File(shellDirectory, file.name).path)
        // 不能写成 `. $path 2>/dev/null || echo 提示`：POSIX 里 `.` 找不到文件会直接终止
        // 非交互 shell，`||` 后面的分支根本轮不到执行，整条用户命令就这样静默不跑了。
        // 所以先用 `-r` 探一下，再决定 source 还是提示；`rm -f` 无论如何都执行。
        return "if [ -r $path ]; then . $path; else echo '$ENV_SCRIPT_UNREADABLE_NOTICE' >&2; fi; rm -f $path"
    }

    private fun write(entries: List<TerminalEnvEntry>): File {
        hostDirectory.mkdirs()
        if (ownsDirectory) {
            // 只把文件设成 0600 还不够：目录若是 0755，别的身份仍能列出名字并逐个试读。
            runCatching {
                Files.setPosixFilePermissions(hostDirectory.toPath(), PosixFilePermissions.fromString("rwx------"))
            }
        }
        val name = PREFIX + System.nanoTime().toString(36) + "-" +
            ThreadLocalRandom.current().nextInt(1 shl 24).toString(36) + ".sh"
        val file = File(hostDirectory, name)
        createPrivate(file)
        file.writeBytes(render(entries).toByteArray(Charsets.UTF_8))
        return file
    }

    private fun render(entries: List<TerminalEnvEntry>): String = buildString {
        appendLine("# Eta 终端环境变量（一次性，source 后立即删除）")
        entries.forEach { entry ->
            append("export ").append(entry.key).append('=').append(shellQuote(entry.value)).append('\n')
        }
    }

    /** 建文件的同时就设为 0600；不支持 POSIX 权限的文件系统退化为普通创建（目录本身是私有的）。 */
    private fun createPrivate(file: File) {
        val path = file.toPath()
        val created = runCatching {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
        }.isSuccess
        if (!created && !file.createNewFile()) {
            throw java.io.IOException("无法创建环境变量脚本：${file.name}")
        }
        runCatching { Files.setPosixFilePermissions(path, OWNER_ONLY) }
    }

    /**
     * 命令被强杀时脚本来不及删；这里顺手回收陈旧文件。
     * 只删超过 [STALE_MS] 的，正在被并发命令 source 的新文件不受影响。
     *
     * 按目录限流（每分钟一次）：Linux 侧的落点是 rootfs 的 `/tmp`，用户完全可能把它塞满，
     * 每条命令都遍历一遍会把固定开销抬高。
     */
    private fun sweepStale() {
        val key = hostDirectory.absolutePath
        val now = System.currentTimeMillis()
        val last = lastSweep[key]
        if (last != null && now - last < SWEEP_INTERVAL_MS) return
        lastSweep[key] = now
        val deadline = now - STALE_MS
        runCatching {
            hostDirectory.listFiles()?.forEach { file ->
                if (file.name.startsWith(PREFIX) && file.lastModified() < deadline) file.delete()
            }
        }
    }

    private companion object {
        const val PREFIX = ".eta-env-"
        const val STALE_MS = 10 * 60 * 1000L
        const val SWEEP_INTERVAL_MS = 60 * 1000L
        val OWNER_ONLY: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")
        val lastSweep = java.util.concurrent.ConcurrentHashMap<String, Long>()
    }
}

/**
 * 按环境挑选注入落点。Linux 的 shell 在 chroot/proot 里，宿主私有目录不可见，
 * 所以脚本要写进 rootfs 的 `/tmp`，并对 guest 用 `/tmp/...` 引用。
 */
internal fun terminalEnvScript(
    entries: List<TerminalEnvEntry>,
    environment: TerminalEnvironment,
    filesDir: File,
    rootfsPath: String?,
): String? {
    if (!environment.isLinux) {
        return TerminalEnvInjection(File(filesDir, ANDROID_ENV_DIR)).script(entries)
    }
    val rootfs = rootfsPath?.takeIf { it.isNotBlank() } ?: return null
    // ownsDirectory=false：rootfs 的 /tmp 是 guest 的公共目录，不能按我们的私有权限收紧。
    return TerminalEnvInjection(File(rootfs, "tmp"), "/tmp", ownsDirectory = false).script(entries)
}

/** App 私有目录，跟 env.conf 同一棵树。 */
internal const val ANDROID_ENV_DIR = "terminal-user/env.d"

/**
 * 注入失败时给用户看的一行（只走 stderr，命令本身照常执行）。
 *
 * 以前只写 logcat：用户在终端里只看到「变量是空的」，根本不知道 env.conf 没加载。
 */
internal const val ENV_INJECTION_FAILED_NOTICE =
    "eta: 环境变量注入失败，本命令未加载 env.conf（详见 logcat）"

/**
 * 脚本写出来了、但 shell 没能 source 成功时的提示（被回收、或被权限/SELinux 拦住）。
 *
 * 与 [ENV_INJECTION_FAILED_NOTICE] 分开：那种是「文件根本没写成」，这种是「写成了却读不到」。
 * 文案同样不含任何变量值，可以安全地留在 argv 与脚本片段里。
 */
internal const val ENV_SCRIPT_UNREADABLE_NOTICE =
    "eta: 环境变量脚本读取失败，本命令可能没有加载 env.conf 变量"

/** 失败提示拼成可放在命令前面的一段；文案里没有单引号，可以直接用单引号包裹。 */
internal fun envFailurePrefix(): String = "echo '$ENV_INJECTION_FAILED_NOTICE' >&2; "
