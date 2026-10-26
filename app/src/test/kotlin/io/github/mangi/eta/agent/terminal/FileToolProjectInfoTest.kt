package io.github.mangi.eta.agent.terminal

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileToolProjectInfoTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun inspect(root: File): JSONObject =
        JSONObject(FileToolProjectInfo(LocalFileToolBackend(root)).inspect(""))

    private fun projects(result: JSONObject): Map<String, JSONObject> {
        val array = result.getJSONArray("projects")
        return (0 until array.length()).associate { index ->
            val item = array.getJSONObject(index)
            item.getString("type") to item
        }
    }

    @Test fun androidGradleProjectReportsBuildTestAndRules() {
        File(temporary.root, "settings.gradle.kts").writeText("")
        File(temporary.root, "build.gradle.kts").writeText("")
        File(temporary.root, "gradlew").writeText("#!/bin/sh\n")
        File(temporary.root, "app").mkdir()
        File(temporary.root, "AGENTS.md").writeText("构建前先跑测试")
        val result = inspect(temporary.root)
        assertTrue(result.toString(), result.getBoolean("ok"))
        val android = projects(result).getValue("android-gradle")
        assertEquals("./gradlew assembleDebug", android.getJSONObject("commands").getString("build"))
        assertEquals("./gradlew test", android.getJSONObject("commands").getString("test"))
        assertEquals(1, result.getJSONArray("rules").length())
        assertEquals("AGENTS.md", result.getJSONArray("rules").getJSONObject(0).getString("name"))
    }

    @Test fun nodeProjectUsesLockfileAndOnlyExistingScripts() {
        File(temporary.root, "package.json").writeText(
            """{"scripts":{"build":"vite build","test":"vitest","other":"x"}}""",
        )
        File(temporary.root, "pnpm-lock.yaml").writeText("")
        val result = inspect(temporary.root)
        val node = projects(result).getValue("node")
        assertEquals("pnpm install", node.getJSONObject("commands").getString("install"))
        assertEquals("pnpm build", node.getJSONObject("commands").getString("build"))
        assertEquals("pnpm test", node.getJSONObject("commands").getString("test"))
        assertFalse(node.getJSONObject("commands").has("lint"))
    }

    @Test fun gitBranchAndReadmeAreReported() {
        val git = File(temporary.root, ".git").apply { mkdir() }
        File(git, "HEAD").writeText("ref: refs/heads/feature/x\n")
        File(temporary.root, "README.md").writeText("hello")
        val result = inspect(temporary.root)
        assertTrue(result.getJSONObject("git").getBoolean("present"))
        assertEquals("feature/x", result.getJSONObject("git").getString("branch"))
        assertEquals("README.md", result.getString("readme"))
    }

    @Test fun nonDirectoryTargetFailsExplicitly() {
        val file = temporary.newFile("file.txt").apply { writeText("x") }
        val result = JSONObject(FileToolProjectInfo(LocalFileToolBackend(temporary.root)).inspect(file.name))
        assertFalse(result.getBoolean("ok"))
        assertEquals("NOT_DIRECTORY", result.getString("code"))
    }
}
