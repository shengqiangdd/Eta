package io.github.mangi.eta.agent.acp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * stderr 致命行判据。
 *
 * 真机实测（PKX110，2026-10-27 21:37）：委派 codex 后它把
 * `ERROR rmcp::transport::worker: worker quit with fatal: Client error: HTTP request failed ...
 *  error sending request for url (https://chatgpt.com/backend-api/wham/apps)`
 * 写到 stderr，进程却没退出，Eta 一直等到 90 秒超时 —— 用户看到的就是「卡在 ACP 委派」。
 */
class AcpFatalStderrTest {

    @Test
    fun `real device fatal line is detected`() {
        val line = "2026-10-27T13:37:26.179099Z ERROR rmcp::transport::worker: worker quit with fatal: " +
            "Client error: HTTP request failed: http/request failed: error sending request for url " +
            "(https://chatgpt.com/backend-api/wham/apps), when send initialized notification"
        assertTrue(isFatalStderrLine(line))
    }

    @Test
    fun `ordinary error line is not fatal`() {
        assertFalse(isFatalStderrLine("ERROR something failed, retrying in 2s"))
        assertFalse(isFatalStderrLine("WARN deprecated flag --foo"))
    }

    @Test
    fun `explicit fatal phrasings are detected`() {
        assertTrue(isFatalStderrLine("fatal error: cannot open config"))
        assertTrue(isFatalStderrLine("panic: runtime error: invalid memory address"))
        assertTrue(isFatalStderrLine("Failed to initialize transport"))
    }

    /**
     * 真机实测（PKX110，2026-10-27 21:44 新包）：委派在 180 秒上限才返回、正文为空，
     * stderr 只有这行模型刷新报错 —— 进程活着、不回话、不退出。
     * 要求同时出现 timeout：单是一句 "failed to refresh available models" 可能可重试。
     */
    @Test
    fun `models refresh hang is fatal but plain refresh failure is not`() {
        val hang = "ERROR codex_models_manager::manager: failed to refresh available models: " +
            "timeout waiting for child process to exit"
        assertTrue(isFatalStderrLine(hang))
        assertTrue(isFatalStderrLine("timeout waiting for child process to exit"))
        assertFalse(isFatalStderrLine("WARN failed to refresh available models: connection reset"))
    }

    @Test
    fun `blank line is not fatal`() {
        assertFalse(isFatalStderrLine(""))
        assertFalse(isFatalStderrLine("   "))
    }

    /**
     * 超时收尾自己关流引发的读异常，不能被当成代理错误。
     * 真机证据（2026-10-27）：一条 80 秒后失败的委派，结果写着
     * 「read interrupted by close() on another thread（代理侧：…）」——真原因（超时）被盖住了。
     */
    @Test
    fun `stream closed by teardown is not a protocol error`() {
        assertTrue(isSelfClosedStreamRead("read interrupted by close() on another thread"))
        assertTrue(isSelfClosedStreamRead("Stream closed"))
        assertFalse(isSelfClosedStreamRead("代理初始化失败：worker quit with fatal"))
        assertFalse(isSelfClosedStreamRead(""))
    }
}
