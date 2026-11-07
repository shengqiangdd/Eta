package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `terminal` 会话复用判定。
 *
 * 真机缺陷：连着两次 `echo` 都返回了完整的 384 字符 `runtime` 块 ——
 * 每次调用都用 `UUID.randomUUID()` 开新会话，`runtimeSignature` 永远是空。
 * 顺带两个后果：每次重启 shell 进程；`cd`/`export` 等会话状态全部丢失。
 */
class TerminalSessionReusePolicyTest {

    @Test
    fun sameRouteReusesTheSession() {
        assertTrue(
            "同环境/身份/cwd 应复用：这是 runtime 去重生效的前提",
            TerminalSessionReusePolicy.reusable(
                existingEnvironment = "debian",
                existingIdentity = "root",
                existingCwd = "/workspace",
                closed = false,
                environment = "debian",
                identity = "root",
                cwd = "/workspace",
            ),
        )
    }

    @Test
    fun differentEnvironmentDoesNotReuse() {
        assertFalse(
            "debian 与 android 是两个执行环境，复用会把命令送错地方",
            TerminalSessionReusePolicy.reusable(
                existingEnvironment = "debian",
                existingIdentity = "root",
                existingCwd = "/workspace",
                closed = false,
                environment = "android",
                identity = "root",
                cwd = "/workspace",
            ),
        )
    }

    @Test
    fun differentIdentityDoesNotReuse() {
        assertFalse(
            "root 与普通用户不能共用一个 shell",
            TerminalSessionReusePolicy.reusable(
                existingEnvironment = "android",
                existingIdentity = "root",
                existingCwd = "/sdcard",
                closed = false,
                environment = "android",
                identity = "user",
                cwd = "/sdcard",
            ),
        )
    }

    @Test
    fun differentCwdDoesNotReuse() {
        assertFalse(
            "cwd 不同会让命令落在错的目录里",
            TerminalSessionReusePolicy.reusable(
                existingEnvironment = "android",
                existingIdentity = "root",
                existingCwd = "/sdcard",
                closed = false,
                environment = "android",
                identity = "root",
                cwd = "/data/local/tmp",
            ),
        )
    }

    @Test
    fun closedSessionIsNeverReused() {
        assertFalse(
            "已关闭的会话不能复用",
            TerminalSessionReusePolicy.reusable(
                existingEnvironment = "android",
                existingIdentity = "root",
                existingCwd = "/sdcard",
                closed = true,
                environment = "android",
                identity = "root",
                cwd = "/sdcard",
            ),
        )
    }
}
