package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自审的判定规则必须能在普通 JVM 上验完 —— 它是纯逻辑，不需要设备，也不该靠真机碰运气。
 *
 * 重点不在「能报出东西」，而在**不该报的别报**：
 * 窗口外的旧崩溃、别的应用的崩溃、有 root 兜底的通知权限，都不算本应用的缺陷。
 */
class AgentSelfAuditTest {

    private val now = 1_800_000_000_000L
    private val pkg = "io.github.mangi.eta"

    private val oomCrash = """
        Process: io.github.mangi.eta
        java.lang.OutOfMemoryError: Failed to allocate a 40 byte allocation
        	at io.github.mangi.eta.ui.components.AgentChatBodyKt.toTimelineEntries(AgentChatBody.kt:1557)
        	at io.github.mangi.eta.ui.components.AgentChatBodyKt.AgentChatBody(AgentChatBody.kt:900)
    """.trimIndent()

    private fun crash(name: String, ageHours: Long, text: String) =
        AgentSelfAudit.CrashFile(name = name, timeMillis = now - ageHours * 3_600_000L, text = text)

    private fun input(
        domains: Set<AgentSelfAudit.Domain> = AgentSelfAudit.Domain.entries.toSet(),
        root: Boolean = true,
        crashFiles: List<AgentSelfAudit.CrashFile> = emptyList(),
        processStartedMillis: Long? = null,
        installUpdatedMillis: Long? = null,
        heapLimitMb: Int? = null,
        javaHeapPssKb: Int? = null,
        capabilities: Map<String, Boolean> = emptyMap(),
        codeClues: AgentSelfAudit.CodeClues? = null,
        windowHours: Int = 48,
        pkg: String = this.pkg,
    ) = AgentSelfAudit.Input(
        packageName = pkg,
        versionName = "3.3.5-debug",
        nowMillis = now,
        windowHours = windowHours,
        domains = domains,
        rootAvailable = root,
        crashFiles = crashFiles,
        processStartedMillis = processStartedMillis,
        installUpdatedMillis = installUpdatedMillis,
        heapLimitMb = heapLimitMb,
        javaHeapPssKb = javaHeapPssKb,
        capabilities = capabilities,
        codeClues = codeClues,
    )

    @Test
    fun crashInsideWindowBecomesP0WithExceptionAndFrame() {
        val report = AgentSelfAudit.audit(
            input(crashFiles = listOf(crash("data_app_crash@1793402235989.txt", 3, oomCrash))),
        )
        val finding = report.findings.single()
        assertEquals(AgentSelfAudit.Severity.P0, finding.severity)
        assertEquals(AgentSelfAudit.Confidence.OBSERVED, finding.confidence)
        assertTrue(finding.title.contains("OutOfMemoryError"))
        assertTrue(finding.evidence.any { it.contains("AgentChatBody.kt:1557") })
        assertTrue("OOM 的建议要给到分配点", finding.fix.contains("大对象分配"))
        assertTrue(finding.verify.isNotBlank())
    }

    private fun stack(vararg frames: String) = buildString {
        appendLine("Process: io.github.mangi.eta")
        appendLine("java.lang.OutOfMemoryError: Failed to allocate a 262160 byte allocation")
        frames.forEach { appendLine("\tat $it") }
    }

    @Test
    fun theOnlyFrameYouCanChangeIsNotCutOffByTheFirstFourSystemFrames() {
        val deep = stack(
            "java.util.HashMap.resize(HashMap.java:709)",
            "java.util.HashMap.putVal(HashMap.java:668)",
            "java.util.HashMap.put(HashMap.java:617)",
            "kotlin.collections.MapsKt__MapsKt.putAll(Maps.kt:597)",
            "io.github.mangi.eta.ui.components.AgentChatBodyKt.toTimelineEntries(AgentChatBody.kt:1557)",
        )
        val evidence = AgentSelfAudit.audit(
            input(crashFiles = listOf(crash("data_app_crash@12.txt", 1, deep))),
        ).findings.single().evidence
        assertTrue(
            "前 4 帧全是系统代码时，必须把自己那帧补进证据（§32.37 那次就丢在这一步）：$evidence",
            evidence.any { it.startsWith("首个可改的帧") && it.contains("AgentChatBody.kt:1557") },
        )
    }

    @Test
    fun theActionableFrameIsNotRepeatedWhenItIsAlreadyInTheFirstFrames() {
        val shallow = stack(
            "io.github.mangi.eta.ui.components.AgentChatBodyKt.toTimelineEntries(AgentChatBody.kt:1557)",
            "androidx.compose.runtime.ComposerKt.invokeComposable(Composer.kt:1)",
        )
        val evidence = AgentSelfAudit.audit(
            input(crashFiles = listOf(crash("data_app_crash@13.txt", 1, shallow))),
        ).findings.single().evidence
        assertTrue("已经在证据里了就不该再补一行：$evidence", evidence.none { it.startsWith("首个可改的帧") })
    }

    @Test
    fun canaryBuildStillFindsTheActionableFrameWhenPackageNameDiffers() {
        // canary 的崩溃文件里 `Process:` 写的是 applicationId（带 .canary），
        // 而类名不带 —— 既有的「按包名认领崩溃」靠前者，取可改帧不能也跟着靠后者。
        val deep = stack(
            "java.util.HashMap.resize(HashMap.java:709)",
            "java.util.HashMap.putVal(HashMap.java:668)",
            "java.util.HashMap.put(HashMap.java:617)",
            "kotlin.collections.MapsKt__MapsKt.putAll(Maps.kt:597)",
            "io.github.mangi.eta.ui.components.AgentChatBodyKt.toTimelineEntries(AgentChatBody.kt:1557)",
        ).replaceFirst("Process: io.github.mangi.eta", "Process: io.github.mangi.eta.canary")
        val evidence = AgentSelfAudit.audit(
            input(
                pkg = "io.github.mangi.eta.canary",
                crashFiles = listOf(crash("data_app_crash@14.txt", 1, deep)),
            ),
        ).findings.single().evidence
        assertTrue(
            "canary 的类名不带 .canary，按包名找不到时必须退回「首个非平台帧」：$evidence",
            evidence.any { it.startsWith("首个可改的帧") && it.contains("AgentChatBody.kt:1557") },
        )
    }

    @Test
    fun crashOutsideWindowIsNotCounted() {
        val report = AgentSelfAudit.audit(
            input(crashFiles = listOf(crash("data_app_crash@1.txt", 96, oomCrash))),
        )
        assertTrue(report.findings.isEmpty())
        assertTrue(report.notes.any { it.contains("早于") })
    }

    @Test
    fun crashFromAnotherPackageIsNotBlamedOnEta() {
        val other = """
            Process: com.example.other
            java.lang.IllegalStateException: boom
            	at com.example.other.A.f(A.kt:1)
        """.trimIndent()
        val report = AgentSelfAudit.audit(input(crashFiles = listOf(crash("data_app_crash@2.txt", 1, other))))
        assertTrue(report.findings.isEmpty())
        assertTrue(report.notes.any { it.contains("其他应用") })
    }

    @Test
    fun withoutRootCrashDomainIsUnavailableInsteadOfClaimingHealth() {
        val report = AgentSelfAudit.audit(
            input(root = false, crashFiles = listOf(crash("data_app_crash@3.txt", 1, oomCrash))),
        )
        assertTrue("没有 root 就不该下任何崩溃结论", report.findings.isEmpty())
        assertTrue(report.unavailable.any { it.contains("dropbox") })
    }

    @Test
    fun heapPressureCrossesSeverityThresholds() {
        val mild = AgentSelfAudit.audit(input(heapLimitMb = 100, javaHeapPssKb = 76 * 1024))
        assertEquals(AgentSelfAudit.Severity.P1, mild.findings.single().severity)

        val severe = AgentSelfAudit.audit(input(heapLimitMb = 100, javaHeapPssKb = 92 * 1024))
        assertEquals(AgentSelfAudit.Severity.P0, severe.findings.single().severity)

        val healthy = AgentSelfAudit.audit(input(heapLimitMb = 100, javaHeapPssKb = 40 * 1024))
        assertTrue(healthy.findings.isEmpty())
        assertTrue("口径必须写进 notes", healthy.notes.any { it.contains("PSS") })
    }

    @Test
    fun missingCapabilitiesAreReportedButNotificationIsCoveredByRoot() {
        val withoutRoot = AgentSelfAudit.audit(
            input(
                root = false,
                capabilities = mapOf("accessibility" to false, "notifications" to false, "usage" to true),
            ),
        )
        assertEquals(2, withoutRoot.findings.size)
        assertTrue(withoutRoot.findings.all { it.severity == AgentSelfAudit.Severity.P1 })

        val withRoot = AgentSelfAudit.audit(
            input(
                root = true,
                capabilities = mapOf("accessibility" to false, "notifications" to false, "usage" to true),
            ),
        )
        assertEquals("有 root 时通知读取仍可用，不该算缺陷", 1, withRoot.findings.size)
        assertTrue(withRoot.findings.single().id == "capability-accessibility")
    }

    @Test
    fun repeatedCrashesRightAfterStartLookLikeARestartLoop() {
        val report = AgentSelfAudit.audit(
            input(
                crashFiles = listOf(
                    crash("data_app_crash@10.txt", 2, oomCrash),
                    crash("data_app_crash@11.txt", 1, oomCrash),
                ),
                processStartedMillis = now - 60_000L,
            ),
        )
        val loop = report.findings.first { it.id == "restart-loop" }
        assertEquals(AgentSelfAudit.Severity.P0, loop.severity)
        assertEquals("这是推断，不是观察到的事实", AgentSelfAudit.Confidence.INFERRED, loop.confidence)
    }

    /**
     * 真机踩过的假话：刚 `pm install -r` 完，进程只跑了 217 秒，而窗口内两次崩溃都在装包之前，
     * 却被报成 P0「崩溃循环」。装包本身就会换进程，不能当成崩溃证据。
     */
    @Test
    fun freshInstallExplainsRestartInsteadOfReportingACrashLoop() {
        val report = AgentSelfAudit.audit(
            input(
                crashFiles = listOf(
                    crash("data_app_crash@10.txt", 2, oomCrash),
                    crash("data_app_crash@11.txt", 1, oomCrash),
                ),
                processStartedMillis = now - 60_000L,
                installUpdatedMillis = now - 90_000L,
            ),
        )
        assertFalse(
            "两次崩溃都在装包之前，循环是装包造成的假象",
            report.findings.any { it.id == "restart-loop" },
        )
        assertTrue(report.clues.any { it.title.contains("不能据此判定崩溃循环") })
    }

    @Test
    fun crashesAfterTheInstallStillCountAsARestartLoop() {
        val report = AgentSelfAudit.audit(
            input(
                crashFiles = listOf(
                    crash("data_app_crash@10.txt", 2, oomCrash),
                    crash("data_app_crash@11.txt", 1, oomCrash),
                ),
                processStartedMillis = now - 60_000L,
                installUpdatedMillis = now - 3 * 3_600_000L,
            ),
        )
        val loop = report.findings.single { it.id == "restart-loop" }
        assertEquals(AgentSelfAudit.Severity.P0, loop.severity)
        assertTrue("更新之后的崩溃仍是循环证据", loop.evidence.size == 2)
    }

    @Test
    fun codeDomainWithoutSourceIsMarkedUnavailable() {
        val report = AgentSelfAudit.audit(input(domains = setOf(AgentSelfAudit.Domain.CODE)))
        assertTrue(report.findings.isEmpty())
        assertTrue(report.unavailable.any { it.contains("源码") })
    }

    @Test
    fun unknownGitStateIsNotReportedAsClean() {
        val report = AgentSelfAudit.audit(
            input(
                domains = setOf(AgentSelfAudit.Domain.CODE),
                codeClues = AgentSelfAudit.CodeClues(
                    sourceRoot = "/data/local/tmp/eta/eta-v2",
                    todoCount = 0,
                    uncommittedFiles = -1,
                ),
            ),
        )
        assertTrue(report.clues.any { it.title.contains("读不到 git") })
        assertFalse(
            "读不到 git 时不能报「工作区干净」——设备上根本没有 git",
            report.clues.any { it.title.contains("干净") },
        )
    }

    @Test
    fun findingsAreSortedBySeverity() {
        val report = AgentSelfAudit.audit(
            input(
                root = false,
                capabilities = mapOf("accessibility" to false),
                heapLimitMb = 100,
                javaHeapPssKb = 95 * 1024,
            ),
        )
        assertEquals(2, report.findings.size)
        assertEquals(AgentSelfAudit.Severity.P0, report.findings.first().severity)
        assertEquals(AgentSelfAudit.Severity.P1, report.findings.last().severity)
    }
}
