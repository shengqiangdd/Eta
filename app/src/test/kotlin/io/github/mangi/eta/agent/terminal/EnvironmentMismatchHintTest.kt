package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentMismatchHintTest {

    @Test
    fun `android failure on workspace path explains the environment`() {
        val note = EnvironmentMismatchHint.note(
            environment = "android",
            command = "cd /workspace/eta-v2 && git diff --stat",
            exitCode = 1,
            stderr = "sh: cd: can't cd to /workspace/eta-v2: No such file or directory",
        )
        assertTrue(note != null)
        assertTrue(note!!.contains("/workspace"))
        assertTrue(note.contains("environment=android"))
        assertTrue(note.contains("设为 linux"))
    }

    @Test
    fun `android success stays silent`() {
        // 命令自己拿这个路径做判断（[ -d /workspace ]）不该被当成错误
        assertNull(
            EnvironmentMismatchHint.note(
                environment = "android",
                command = "[ -d /workspace ] && echo yes || echo no",
                exitCode = 0,
                stderr = "",
            ),
        )
    }

    @Test
    fun `linux environment never hints`() {
        assertNull(
            EnvironmentMismatchHint.note(
                environment = "linux",
                command = "cd /workspace/eta-v2 && git diff --stat",
                exitCode = 128,
                stderr = "fatal: not a git repository",
            ),
        )
    }

    @Test
    fun `missing path in stderr is reported even with zero exit`() {
        val note = EnvironmentMismatchHint.note(
            environment = "android",
            command = "ls /root/notes 2>&1 | head -3; true",
            exitCode = 0,
            stderr = "ls: /root/notes: No such file or directory",
        )
        assertTrue(note != null)
        assertTrue(note!!.contains("/root/"))
    }

    @Test
    fun `silent when nothing indicates the path is missing`() {
        // 既没失败、stderr 也没报缺失：不能凭空断定环境用错了
        assertNull(
            EnvironmentMismatchHint.note(
                environment = "android",
                command = "ls /workspace 2>/dev/null; true",
                exitCode = 0,
                stderr = "",
            ),
        )
    }

    @Test
    fun `unrelated command stays silent`() {
        assertNull(
            EnvironmentMismatchHint.note(
                environment = "android",
                command = "ls /sdcard/Download",
                exitCode = 1,
                stderr = "ls: /sdcard/Download: No such file or directory",
            ),
        )
    }

    // ---- 文件工具：FILE_NOT_FOUND 也要说清楚是不是环境选错了 ----

    @Test
    fun `file tool on missing linux path points at the environment`() {
        val note = EnvironmentMismatchHint.noteForPath("android", "/workspace/eta-v2/README.md", "FILE_NOT_FOUND")
        assertTrue(note != null)
        assertTrue(note!!.contains("/workspace"))
        assertTrue(note.contains("environment=android"))
        assertTrue(note.contains("设为 linux"))
        assertTrue(note.contains("不代表文件真的不存在"))
    }

    @Test
    fun `file tool hints on exact roots and home paths`() {
        assertTrue(EnvironmentMismatchHint.noteForPath("android", "/workspace", "FILE_NOT_FOUND") != null)
        assertTrue(EnvironmentMismatchHint.noteForPath("android", "/workspace/", "FILE_NOT_FOUND") != null)
        assertTrue(EnvironmentMismatchHint.noteForPath("android", "/root/notes.md", "FILE_NOT_FOUND") != null)
    }

    @Test
    fun `file tool stays silent when the failure is not a missing path`() {
        // 权限不足和命名空间无关，提示「换环境」只会把人带偏。
        assertNull(EnvironmentMismatchHint.noteForPath("android", "/workspace/x", "FILE_ACCESS_DENIED"))
        assertNull(EnvironmentMismatchHint.noteForPath("android", "/workspace/x", "NOT_DIRECTORY"))
    }

    @Test
    fun `file tool stays silent outside linux prefixes and matches whole segments`() {
        assertNull(EnvironmentMismatchHint.noteForPath("android", "/sdcard/Download/a.txt", "FILE_NOT_FOUND"))
        assertNull(EnvironmentMismatchHint.noteForPath("android", "relative/path.txt", "FILE_NOT_FOUND"))
        // 前缀必须按目录边界匹配：/workspace2 不是 /workspace
        assertNull(EnvironmentMismatchHint.noteForPath("android", "/workspace2/a.txt", "FILE_NOT_FOUND"))
    }

    @Test
    fun `file tool with linux environment never hints`() {
        assertNull(EnvironmentMismatchHint.noteForPath("linux", "/workspace/eta-v2", "FILE_NOT_FOUND"))
    }
}
