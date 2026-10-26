package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DangerousCommandGuardTest {
    @Test
    fun blocksRootDeletion() {
        assertEquals("删除根目录", DangerousCommandGuard.check("rm -rf /"))
        assertEquals("删除根目录", DangerousCommandGuard.check("rm -rf / ; echo done"))
    }

    @Test
    fun blocksSystemDataAndBlockWrites() {
        assertEquals("删除系统或应用数据目录", DangerousCommandGuard.check("rm -rf /data/data/com.example"))
        assertEquals("直接写块设备", DangerousCommandGuard.check("dd if=/sdcard/a.img of=/dev/block/by-name/boot"))
        assertEquals("格式化分区", DangerousCommandGuard.check("mkfs.ext4 /dev/block/x"))
    }

    @Test
    fun allowsNormalCommands() {
        assertNull(DangerousCommandGuard.check("rm -rf /data/local/tmp/eta-test"))
        assertNull(DangerousCommandGuard.check("ls -la /data/local/tmp"))
        assertNull(DangerousCommandGuard.check("echo hello"))
        assertNull(DangerousCommandGuard.check(""))
    }
}
