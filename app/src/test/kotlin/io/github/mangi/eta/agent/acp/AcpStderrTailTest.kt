package io.github.mangi.eta.agent.acp

import org.junit.Assert.assertEquals
import org.junit.Test

class AcpStderrTailTest {

    private fun tail(raw: String, ok: Boolean) = AcpStderrTail.tail(raw, finishedOk = ok, maxChars = 1_500)

    @Test
    fun `successful run drops the terminator line`() {
        assertEquals("", tail("Terminated\n", ok = true))
    }

    @Test
    fun `successful run keeps real warnings but drops termination noise`() {
        val raw = "warning: deprecated flag\nTerminated\n"
        assertEquals("warning: deprecated flag", tail(raw, ok = true))
    }

    @Test
    fun `failed run keeps everything including termination`() {
        val raw = "panic: boom\nTerminated\n"
        assertEquals("panic: boom\nTerminated", tail(raw, ok = false))
    }

    @Test
    fun `tail keeps only the last max chars`() {
        val raw = "x".repeat(2_000) + "END"
        val out = AcpStderrTail.tail(raw, finishedOk = false, maxChars = 10)
        assertEquals("xxxxxxxEND", out)
    }

    @Test
    fun `blank stderr yields empty string`() {
        assertEquals("", tail("", ok = false))
        assertEquals("", tail("\n\n", ok = true))
    }
}
