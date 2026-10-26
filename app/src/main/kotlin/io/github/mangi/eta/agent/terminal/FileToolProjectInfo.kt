package io.github.mangi.eta.agent.terminal

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工作区项目识别：只读顶层标记文件和少量小文件，回答「这是什么项目、怎么构建和测试」。
 *
 * 参考 Codex / Claude Code 在陌生仓库里先确认构建命令再动手的做法。这里**不递归扫描**：
 * shell 后端每读一个文件都是一次命令，递归会慢到不可用；顶层一层加几个标记文件已经
 * 足以覆盖 gradle / node / rust / go / python / cmake / make 这些常见项目。
 *
 * 输出只给「推断」，不保证工具链在手机上真的可用（比如 Android 工程需要 JDK）。
 */
internal class FileToolProjectInfo(private val backend: FileToolBackend) {

    fun inspect(path: String): String = fileToolOperation(
        tool = "project_info",
        environment = backend.environment,
        identity = backend.identity,
        path = path,
    ) {
        backend.checkActive()
        val root = backend.resolve(path)
        val rootStat = backend.stat(root)
        if (rootStat.kind != FileToolKind.DIRECTORY) {
            throw FileToolException("NOT_DIRECTORY", "项目根必须是目录")
        }
        val page = backend.list(root, 0, MAX_ENTRIES)
        val entries = page.entries
        val names = entries.map { it.name }.toSet()
        val directories = entries.filter { it.kind == FileToolKind.DIRECTORY }.map { it.name }.toSet()

        val projects = JSONArray()
        fun addProject(type: String, markers: List<String>, commands: Map<String, String>) {
            if (markers.isEmpty()) return
            val commandJson = JSONObject()
            commands.forEach { (name, command) -> commandJson.put(name, command) }
            projects.put(
                JSONObject().put("type", type)
                    .put("markers", JSONArray(markers))
                    .put("commands", commandJson),
            )
        }

        val gradleBuild = listOf("build.gradle.kts", "build.gradle").firstOrNull { it in names }
        val androidMarkers = buildList {
            if ("settings.gradle.kts" in names) add("settings.gradle.kts")
            if ("settings.gradle" in names) add("settings.gradle")
            if ("app" in directories) add("app/")
            gradleBuild?.let { add(it) }
        }
        if (("settings.gradle.kts" in names || "settings.gradle" in names) &&
            ("app" in directories || gradleBuild != null)
        ) {
            val prefix = if ("gradlew" in names) "./gradlew" else "gradle"
            addProject(
                "android-gradle",
                androidMarkers,
                mapOf(
                    "build" to "$prefix assembleDebug",
                    "test" to "$prefix test",
                    "lint" to "$prefix lint",
                    "install" to "$prefix installDebug",
                ),
            )
        } else if ("gradlew" in names || gradleBuild != null) {
            val prefix = if ("gradlew" in names) "./gradlew" else "gradle"
            addProject(
                "gradle",
                listOfNotNull("gradlew".takeIf { it in names }, gradleBuild),
                mapOf("build" to "$prefix build", "test" to "$prefix test"),
            )
        }

        if ("package.json" in names) {
            val packageManager = when {
                "pnpm-lock.yaml" in names -> "pnpm"
                "yarn.lock" in names -> "yarn"
                else -> "npm"
            }
            val scripts = readPackageScripts(root)
            val commands = linkedMapOf("install" to "$packageManager install")
            if (scripts.contains("build")) commands["build"] = if (packageManager == "npm") "npm run build" else "$packageManager build"
            if (scripts.contains("test")) commands["test"] = if (packageManager == "npm") "npm test" else "$packageManager test"
            if (scripts.contains("lint")) commands["lint"] = if (packageManager == "npm") "npm run lint" else "$packageManager lint"
            addProject(
                "node",
                listOfNotNull(
                    "package.json",
                    "pnpm-lock.yaml".takeIf { it in names },
                    "yarn.lock".takeIf { it in names },
                    "package-lock.json".takeIf { it in names },
                ),
                commands,
            )
        }

        if ("Cargo.toml" in names) {
            addProject(
                "rust",
                listOf("Cargo.toml"),
                mapOf("build" to "cargo build", "test" to "cargo test", "lint" to "cargo clippy -- -D warnings"),
            )
        }
        if ("go.mod" in names) {
            addProject(
                "go",
                listOf("go.mod"),
                mapOf("build" to "go build ./...", "test" to "go test ./...", "lint" to "go vet ./..."),
            )
        }
        if ("pyproject.toml" in names || "setup.py" in names || "requirements.txt" in names) {
            addProject(
                "python",
                listOfNotNull(
                    "pyproject.toml".takeIf { it in names },
                    "setup.py".takeIf { it in names },
                    "requirements.txt".takeIf { it in names },
                ),
                mapOf("test" to "python3 -m pytest"),
            )
        }
        if ("CMakeLists.txt" in names) {
            addProject(
                "cmake",
                listOf("CMakeLists.txt"),
                mapOf("build" to "cmake -S . -B build && cmake --build build", "test" to "ctest --test-dir build"),
            )
        }
        if ("Makefile" in names || "GNUmakefile" in names) {
            addProject(
                "make",
                listOfNotNull("Makefile".takeIf { it in names }, "GNUmakefile".takeIf { it in names }),
                mapOf("build" to "make", "test" to "make test"),
            )
        }

        val rules = JSONArray()
        for (name in RULE_FILES) {
            val entry = entries.firstOrNull { it.name == name && it.kind == FileToolKind.FILE } ?: continue
            rules.put(JSONObject().put("name", name).put("size_bytes", entry.sizeBytes))
        }
        if (".eta" in directories) {
            runCatching { backend.stat(join(root, ".eta/rules.md")) }.getOrNull()
                ?.takeIf { it.kind == FileToolKind.FILE }
                ?.let { rules.put(JSONObject().put("name", ".eta/rules.md").put("size_bytes", it.sizeBytes)) }
        }

        val readme = entries.map { it.name }
            .firstOrNull { it.equals("README.md", ignoreCase = true) || it.equals("README_EN.md", ignoreCase = true) || it.equals("README.txt", ignoreCase = true) }

        val topLevel = JSONArray()
        entries.take(MAX_TOP_LEVEL).forEach { entry ->
            topLevel.put(
                JSONObject().put("name", entry.name).put("kind", entry.kind.wireName)
                    .put("size_bytes", entry.sizeBytes),
            )
        }

        JSONObject()
            .put("ok", true).put("tool", "project_info")
            .put("environment", backend.environment).put("identity", backend.identity)
            .put("path", root)
            .put("projects", projects)
            .put("rules", rules)
            .put("readme", readme ?: JSONObject.NULL)
            .put("git", gitInfo(root, entries))
            .put("top_level", topLevel)
            .put("truncated", page.hasMore || entries.size > MAX_TOP_LEVEL)
            .put("note", "命令由顶层标记文件推断（Node 还会读取 package.json 的 scripts，未解析 gradle/cargo/go 配置）；执行前先确认对应工具链在手机/容器里可用。")
    }

    private fun readPackageScripts(root: String): Set<String> {
        val text = readSmallText(root, "package.json", MAX_JSON_BYTES) ?: return emptySet()
        val scripts = runCatching { JSONObject(text).optJSONObject("scripts") }.getOrNull() ?: return emptySet()
        return scripts.keys().asSequence().toSet()
    }

    private fun gitInfo(root: String, entries: List<FileToolStat>): JSONObject {
        val git = entries.firstOrNull { it.name == ".git" } ?: return JSONObject().put("present", false)
        val head = if (git.kind == FileToolKind.DIRECTORY) {
            runCatching {
                val stat = backend.stat(join(root, ".git/HEAD"))
                if (stat.kind != FileToolKind.FILE || stat.sizeBytes > MAX_GIT_HEAD_BYTES) return@runCatching null
                backend.readBytes(join(root, ".git/HEAD"), 0, stat.sizeBytes.toInt())
                    .toString(Charsets.UTF_8).trim()
            }.getOrNull()
        } else {
            null
        }
        val branch = head?.removePrefix("ref: refs/heads/")?.takeIf { it.isNotBlank() && !it.startsWith("ref:") }
        return JSONObject().put("present", true).put("branch", branch ?: JSONObject.NULL)
    }

    private fun readSmallText(root: String, name: String, maxBytes: Int): String? = runCatching {
        val file = join(root, name)
        val stat = backend.stat(file)
        if (stat.kind != FileToolKind.FILE || stat.sizeBytes > maxBytes) return null
        backend.readBytes(file, 0, stat.sizeBytes.toInt()).toString(Charsets.UTF_8)
    }.getOrNull()

    private fun join(root: String, name: String): String =
        if (root.endsWith("/")) root + name else "$root/$name"

    private companion object {
        const val MAX_ENTRIES = 80
        const val MAX_TOP_LEVEL = 40
        const val MAX_JSON_BYTES = 64 * 1024
        const val MAX_GIT_HEAD_BYTES = 512L
        val RULE_FILES = listOf("AGENTS.md", "CLAUDE.md", "ETA.md")
    }
}
