package io.github.mangi.eta.agent.project

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectRulesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun firstNonEmptyCandidateWinsAndBomIsRemoved() {
        File(temporary.root, "AGENTS.md").writeText("  \n")
        File(temporary.root, "CLAUDE.md").writeText("\uFEFF用 4 空格缩进\n")
        assertEquals("用 4 空格缩进", ProjectRules.load(temporary.root.path))
    }

    @Test fun importsAreExpandedAndMarked() {
        File(temporary.root, "AGENTS.md").writeText("主规则\n@docs/style.md\n")
        val docs = File(temporary.root, "docs").apply { mkdir() }
        File(docs, "style.md").writeText("缩进 4 空格\n")
        val rules = ProjectRules.load(temporary.root.path)
        assertTrue(rules, rules.contains("主规则"))
        assertTrue(rules, rules.contains("缩进 4 空格"))
        assertTrue(rules, rules.contains("<!-- 引用 docs/style.md -->"))
    }

    @Test fun traversalAndAbsoluteImportsAreNotExpanded() {
        File(temporary.root, "AGENTS.md").writeText("@../outside.md\n@/etc/passwd\n")
        File(temporary.root.parentFile, "outside.md").writeText("secret")
        val rules = ProjectRules.load(temporary.root.path)
        assertFalse(rules, rules.contains("secret"))
        assertTrue(rules, rules.contains("@../outside.md"))
        assertTrue(rules, rules.contains("@/etc/passwd"))
    }

    @Test fun cyclicImportsStopInsteadOfLooping() {
        File(temporary.root, "AGENTS.md").writeText("@b.md\n")
        File(temporary.root, "b.md").writeText("@AGENTS.md\ntail\n")
        val rules = ProjectRules.load(temporary.root.path)
        assertTrue(rules, rules.contains("tail"))
        assertTrue(rules, rules.count { it == '@' } <= 2)
    }

    @Test fun totalSizeIsBounded() {
        File(temporary.root, "AGENTS.md").writeText("x".repeat(20_000))
        assertEquals(8_000, ProjectRules.load(temporary.root.path).length)
    }
}
