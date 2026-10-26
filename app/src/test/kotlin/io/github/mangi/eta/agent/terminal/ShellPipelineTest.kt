package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 管道判定直接决定后台命令是否走 PTY 捕获，判错会改变日志行为：
 * 多给终端只是多一层包装，漏给终端则 `read` 会长时间读到空日志。
 */
class ShellPipelineTest {
    @Test
    fun plainCommandHasNoPipe() {
        assertFalse(ShellPipeline.hasStreamingPipe("echo hello"))
        assertFalse(ShellPipeline.hasStreamingPipe("sleep 30"))
        assertFalse(ShellPipeline.hasStreamingPipe(""))
    }

    @Test
    fun singlePipeCountsAsStreaming() {
        assertTrue(ShellPipeline.hasStreamingPipe("grep foo file.txt | wc -l"))
        assertTrue(ShellPipeline.hasStreamingPipe("cmd |grep x"))
        assertTrue(ShellPipeline.hasStreamingPipe("cmd|grep x"))
        assertTrue(ShellPipeline.hasStreamingPipe("a | b | c"))
        assertTrue(ShellPipeline.hasStreamingPipe("cmd |& grep x"))
    }

    @Test
    fun logicalOrIsNotAPipe() {
        assertFalse(ShellPipeline.hasStreamingPipe("test -f a || echo missing"))
        assertFalse(ShellPipeline.hasStreamingPipe("a || b || c"))
    }

    @Test
    fun pipeInsideQuotesIsLiteral() {
        assertFalse(ShellPipeline.hasStreamingPipe("echo \"a|b\""))
        assertFalse(ShellPipeline.hasStreamingPipe("echo 'a|b'"))
        assertFalse(ShellPipeline.hasStreamingPipe("grep 'a|b' file"))
        assertTrue(ShellPipeline.hasStreamingPipe("echo \"a|b\" | cat"))
    }

    @Test
    fun escapedPipeIsLiteral() {
        assertFalse(ShellPipeline.hasStreamingPipe("echo a \\| b"))
        assertTrue(ShellPipeline.hasStreamingPipe("echo a \\| b | cat"))
    }

    @Test
    fun quotedPipeDoesNotHideLaterPipe() {
        assertTrue(ShellPipeline.hasStreamingPipe("echo 'x|y' | sed s/x/z/"))
    }
}
