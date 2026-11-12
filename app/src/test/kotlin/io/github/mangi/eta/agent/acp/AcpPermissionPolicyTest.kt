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

    /**
     * 误判回归（2026-11-12）：旧写法把 `"rm -rf /"` 当纯子串匹配，
     * 而删构建产物正是以它开头，于是日常清理被当成「删根目录」拦掉。
     * 同理 `dd if=/dev/zero of=./tmp.img`（造个测试用空文件）也被当成抹盘。
     *
     * 修法：`/` 后面必须是行尾/空白/通配才算根目录，不能是 `/workspace` 这种具体路径。
     */
    @Test
    fun deletingInsideProjectIsAllowed() {
        assertFalse(shouldDenyAcpCommand("rm -rf /workspace/app/build"))
        assertFalse(shouldDenyAcpCommand("rm -rf /tmp/scratch"))
        assertFalse(shouldDenyAcpCommand("rm -rf ./build"))
        assertFalse(shouldDenyAcpCommand("rm -rf node_modules"))
    }

    @Test
    fun creatingImageFromDevZeroIsAllowed() {
        // 写向普通文件而不是块设备，安全。
        assertFalse(shouldDenyAcpCommand("dd if=/dev/zero of=./tmp.img bs=1M count=1"))
    }

    @Test
    fun mentioningDangerousWordsIsAllowed() {
        // 只是提到，不是执行。旧的「危险词」表会把它们全拒。
        assertFalse(shouldDenyAcpCommand("git log --grep reboot"))
        assertFalse(shouldDenyAcpCommand("echo 'remember to mkfs later'"))
    }

    @Test
    fun writingBlockDeviceIsStillDenied() {
        // 上面放宽了路径边界，这里守住真正的块设备写入写法不被一起放过。
        assertTrue(shouldDenyAcpCommand("echo x > /dev/block/sda"))
        assertTrue(shouldDenyAcpCommand("cat foo > /dev/sda"))
    }
}
