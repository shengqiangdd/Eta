package io.github.mangi.eta.agent.acp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权限裁定：默认放行，只挡不可逆操作。
 *
 * 回归点（真机，PKX110，2026-10-27）：旧实现用「危险词」表（含 exec/shell/command/run）
 * 匹配整个 toolCall 文本，导致每一次命令请求都被拒绝，codex 回 `stop_reason=cancelled`，
 * 委派等于不可用。这条测试守着「普通命令必须放行、破坏性命令必须拒绝」。
 */
class AcpPermissionPolicyTest {

    @Test
    fun ordinaryCodingCommandsAreAllowed() {
        assertFalse(shouldDenyAcpCommand("sleep 55"))
        assertFalse(shouldDenyAcpCommand("执行命令：sleep 55"))
        assertFalse(shouldDenyAcpCommand("/bin/bash -lc 'ls -la /workspace'"))
        assertFalse(shouldDenyAcpCommand("运行测试：pytest -q"))
        assertFalse(shouldDenyAcpCommand("EDIT app/src/main/kotlin/Foo.kt"))
    }

    @Test
    fun destructiveCommandsAreDenied() {
        assertTrue(shouldDenyAcpCommand("rm -rf / --no-preserve-root"))
        assertTrue(shouldDenyAcpCommand("mkfs.ext4 /dev/block/sda1"))
        assertTrue(shouldDenyAcpCommand("dd if=/dev/zero of=/dev/block/sda"))
        assertTrue(shouldDenyAcpCommand("fastboot flash boot boot.img"))
        assertTrue(shouldDenyAcpCommand("reboot"))
    }

    @Test
    fun blankTextIsNotDenied() {
        assertFalse(shouldDenyAcpCommand(""))
        assertFalse(shouldDenyAcpCommand("   "))
    }
}
