package io.github.mangi.eta.agent.acp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 孤儿 ACP 进程的判据。
 *
 * 真实事故（PKX110，2026-10-27）：App 重启后 `codex-acp` 被 init 收养继续跑；
 * 更糟的是「清理代码」本身是静默失效的 —— App uid 读不到 root 进程的 /proc，也发不了信号，
 * 真机上 8 个残留一个都没收掉。所以判据改为以 **uid** 认「是不是 Eta 的祖先」。
 *
 * 清理必须只动「Eta 运行时目录里、且祖先链上已经没有 Eta」的进程，
 * 否则会把正在跑的委派、或用户自己在终端里跑的 codex 一起杀掉。
 */
class AcpOrphanReaperTest {

    private val root = AcpOrphanReaper.ETA_RUNTIME_ROOT
    private val etaUid = 10_629
    private val rootUid = 0

    @Test
    fun `agent whose parent is gone is an orphan`() {
        val procs = listOf(
            AcpOrphanReaper.Proc(
                pid = 4242, ppid = 1, uid = rootUid,
                cmdline = "$root/node/26.8.1/bin/codex-acp.real",
            ),
        )
        assertEquals(listOf(4242), AcpOrphanReaper.selectOrphans(procs, etaUid))
    }

    @Test
    fun `agent under the current app process is not an orphan`() {
        val procs = listOf(
            AcpOrphanReaper.Proc(pid = 300, ppid = 1, uid = etaUid, cmdline = "io.github.mangi.eta"),
            AcpOrphanReaper.Proc(pid = 400, ppid = 300, uid = rootUid, cmdline = "su -c sh -c ${root}node/26.8.1/bin/codex-acp"),
            AcpOrphanReaper.Proc(pid = 500, ppid = 400, uid = rootUid, cmdline = "$root/node/26.8.1/bin/codex-acp"),
        )
        assertTrue(AcpOrphanReaper.selectOrphans(procs, etaUid).isEmpty())
    }

    @Test
    fun `orphan chain left by a previous app process is cleaned up`() {
        // 上一代 App 已死，中间层 su/sh 也没了，代理被 init 收养。
        val procs = listOf(
            AcpOrphanReaper.Proc(pid = 28653, ppid = 1, uid = rootUid, cmdline = "node $root/node/26.8.1/bin/codex-acp.real"),
            AcpOrphanReaper.Proc(
                pid = 28680, ppid = 28653, uid = rootUid,
                cmdline = "$root/lib/node_modules/@zed-industries/codex-acp/bin/codex-acp",
            ),
        )
        assertEquals(listOf(28653, 28680), AcpOrphanReaper.selectOrphans(procs, etaUid))
    }

    @Test
    fun `process outside the eta runtime is never touched`() {
        val procs = listOf(
            AcpOrphanReaper.Proc(pid = 700, ppid = 1, uid = rootUid, cmdline = "/usr/local/bin/codex-acp"),
            AcpOrphanReaper.Proc(pid = 701, ppid = 1, uid = rootUid, cmdline = "/bin/bash"),
        )
        assertTrue(AcpOrphanReaper.selectOrphans(procs, etaUid).isEmpty())
    }

    @Test
    fun `process running as the app uid is never selected`() {
        // 同一个 uid 里跑的进程属于 Eta 自己（例如它直接拉起的子进程），不能杀。
        val procs = listOf(
            AcpOrphanReaper.Proc(pid = 300, ppid = 1, uid = etaUid, cmdline = "$root/node/26.8.1/bin/codex-acp"),
        )
        assertTrue(AcpOrphanReaper.selectOrphans(procs, etaUid).isEmpty())
    }

    @Test
    fun `eta ancestor is found through a long chain`() {
        val procs = listOf(
            AcpOrphanReaper.Proc(pid = 100, ppid = 1, uid = etaUid, cmdline = "io.github.mangi.eta"),
            AcpOrphanReaper.Proc(pid = 101, ppid = 100, uid = rootUid, cmdline = "su"),
            AcpOrphanReaper.Proc(pid = 102, ppid = 101, uid = rootUid, cmdline = "sh"),
            AcpOrphanReaper.Proc(pid = 103, ppid = 102, uid = rootUid, cmdline = "busybox chroot"),
            AcpOrphanReaper.Proc(pid = 104, ppid = 103, uid = rootUid, cmdline = "sh -lc"),
            AcpOrphanReaper.Proc(pid = 105, ppid = 104, uid = rootUid, cmdline = "$root/node/26.8.1/bin/codex-acp"),
        )
        assertTrue(AcpOrphanReaper.selectOrphans(procs, etaUid).isEmpty())
    }
}
