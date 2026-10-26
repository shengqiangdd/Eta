package io.github.mangi.eta.agent.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEnvFileTest {
    @Test
    fun exportsKeysFromPrivateFile() {
        val dir = File.createTempFile("eta-env", "").apply { delete(); mkdirs() }
        try {
            File(dir, "terminal").mkdirs()
            File(dir, "terminal/env.conf").writeText(
                """
                # comment
                HTTPS_PROXY=http://127.0.0.1:7890
                PATH=/usr/bin:/bin
                """.trimIndent(),
            )
            val exports = TerminalEnvFile(dir).exportsOrEmpty()
            assertTrue(exports, exports.contains("export HTTPS_PROXY='http://127.0.0.1:7890';"))
            assertTrue(exports, exports.contains("export PATH='/usr/bin:/bin';"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun ignoresUnsafeOrMalformedLines() {
        val dir = File.createTempFile("eta-env", "").apply { delete(); mkdirs() }
        try {
            File(dir, "terminal").mkdirs()
            File(dir, "terminal/env.conf").writeText(
                """
                LD_PRELOAD=/tmp/evil.so
                1BAD=x
                GOOD="quoted value"
                """.trimIndent(),
            )
            val exports = TerminalEnvFile(dir).exportsOrEmpty()
            assertTrue(exports, !exports.contains("LD_PRELOAD"))
            assertTrue(exports, !exports.contains("1BAD"))
            assertTrue(exports, exports.contains("export GOOD='quoted value';"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun emptyWhenNoFile() {
        val dir = File.createTempFile("eta-env", "").apply { delete(); mkdirs() }
        try {
            assertEquals("", TerminalEnvFile(dir).exportsOrEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun editedEntriesRoundTripThroughPrivateFile() {
        val dir = File.createTempFile("eta-env", "").apply { delete(); mkdirs() }
        try {
            val store = TerminalEnvFile(dir)
            assertTrue(store.savePrivateEntries(listOf(
                TerminalEnvEntry("HTTPS_PROXY", "http://127.0.0.1:7890"),
                TerminalEnvEntry("APP_NAME", "Eta 测试"),
            )))
            assertEquals(
                listOf("HTTPS_PROXY", "APP_NAME"),
                store.privateEntries().map { it.key },
            )
            assertEquals("Eta 测试", store.privateEntries().single { it.key == "APP_NAME" }.value)
            val exports = store.exportsOrEmpty()
            assertTrue(exports, exports.contains("export APP_NAME='Eta 测试';"))
            assertTrue(exports, exports.contains("export HTTPS_PROXY='http://127.0.0.1:7890';"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun savesToAppOwnedTerminalUserAndReadsLegacyOnlyAsFallback() {
        val dir = File.createTempFile("eta-env", "").apply { delete(); mkdirs() }
        try {
            File(dir, "terminal").mkdirs()
            File(dir, "terminal/env.conf").writeText("OLD=1\n")
            val store = TerminalEnvFile(dir)
            assertEquals("1", store.privateEntries().single { it.key == "OLD" }.value)

            assertTrue(store.savePrivateEntries(listOf(TerminalEnvEntry("NEW", "2"))))
            assertTrue(File(dir, "terminal-user/env.conf").isFile)
            assertFalse(File(dir, "terminal/env.conf").readText().contains("NEW"))
            assertEquals(listOf("NEW"), store.privateEntries().map { it.key })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun savingReplacesTheWholePrivateConfig() {
        val dir = File.createTempFile("eta-env", "").apply { delete(); mkdirs() }
        try {
            val store = TerminalEnvFile(dir)
            store.savePrivateEntries(listOf(TerminalEnvEntry("OLD", "1")))
            assertTrue(store.savePrivateEntries(listOf(TerminalEnvEntry("NEW", "2"))))
            assertEquals(listOf(TerminalEnvEntry("NEW", "2")), store.privateEntries())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun invalidEntriesAreRejectedWithoutWriting() {
        val dir = File.createTempFile("eta-env", "").apply { delete(); mkdirs() }
        try {
            val store = TerminalEnvFile(dir)
            val invalid = listOf(
                TerminalEnvEntry("", "x"),
                TerminalEnvEntry("1BAD", "x"),
                TerminalEnvEntry("LD_PRELOAD", "/tmp/evil.so"),
                TerminalEnvEntry("GOOD", "line1\nline2"),
            )
            invalid.forEach { entry ->
                assertTrue(entry.key, !store.savePrivateEntries(listOf(entry)))
            }
            assertEquals(emptyList<TerminalEnvEntry>(), store.privateEntries())
            assertEquals(TerminalEnvValidation.BLOCKED_KEY, store.validateEntry("LD_PRELOAD", "x"))
            assertEquals(TerminalEnvValidation.INVALID_VALUE, store.validateEntry("GOOD", "a\nb"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
