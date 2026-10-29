package io.github.mangi.eta.agent.tool

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** virtual_display_* 工具的参数解析与 schema：纯 JSON，不需要 Android 框架。 */
class AgentVirtualDisplayToolsTest {

    @Test
    fun schemaDeclaresExactlyTheExecutableTools() {
        assertEquals(
            listOf(
                "virtual_display_create",
                "virtual_display_list",
                "virtual_display_release",
                "virtual_display_launch",
            ).sorted(),
            AgentVirtualDisplayTools.names.sorted(),
        )
    }

    @Test
    fun everySchemaIsAWellFormedFunctionObject() {
        val tools = org.json.JSONArray()
        AgentVirtualDisplayTools.appendTo(tools)
        assertEquals(AgentVirtualDisplayTools.names.size, tools.length())
        for (i in 0 until tools.length()) {
            val entry = tools.getJSONObject(i)
            assertEquals("function", entry.getString("type"))
            val function = entry.getJSONObject("function")
            val name = function.getString("name")
            assertTrue(name in AgentVirtualDisplayTools.names)
            assertTrue("$name 缺描述", function.getString("description").length > 10)
            val parameters = function.getJSONObject("parameters")
            assertEquals("object", parameters.getString("type"))
            assertTrue(parameters.has("properties"))
        }
    }

    @Test
    fun createUsesDocumentedDefaultsAndRejectsOutOfRange() {
        val defaults = AgentVirtualDisplayTools.parseCreate(
            JSONObject(),
            mainWidth = 1216,
            mainHeight = 2640,
        )!!
        assertEquals(1216, defaults.width)
        assertEquals(2640, defaults.height)
        // 不传主屏尺寸时退回旧的 1080x1920，保证调用方漏传也不会崩。
        val legacy = AgentVirtualDisplayTools.parseCreate(JSONObject())!!
        assertEquals(1080, legacy.width)
        assertEquals(1920, legacy.height)
        // preset：main/landscape/1080p/1440p/tablet，未知值报错
        assertEquals(1216, AgentVirtualDisplayTools.parseCreate(JSONObject().put("preset", "main"), 1216, 2640)!!.width)
        val landscape = AgentVirtualDisplayTools.parseCreate(JSONObject().put("preset", "landscape"), 1216, 2640)!!
        assertEquals(2640, landscape.width)
        assertEquals(1216, landscape.height)
        assertEquals(1440, AgentVirtualDisplayTools.parseCreate(JSONObject().put("preset", "1440p"))!!.width)
        assertEquals(1600, AgentVirtualDisplayTools.parseCreate(JSONObject().put("preset", "tablet"))!!.width)
        assertNull(AgentVirtualDisplayTools.parseCreate(JSONObject().put("preset", "8k")))
        assertEquals(null, defaults.densityDpi)

        assertNull(AgentVirtualDisplayTools.parseCreate(JSONObject().put("width", 10)))
        assertNull(AgentVirtualDisplayTools.parseCreate(JSONObject().put("height", 4000)))
        assertNull(AgentVirtualDisplayTools.parseCreate(JSONObject().put("density_dpi", 100)))

        val custom = AgentVirtualDisplayTools.parseCreate(
            JSONObject().put("width", 720).put("height", 1280).put("density_dpi", 320).put("purpose", "试用新版本"),
        )!!
        assertEquals(720, custom.width)
        assertEquals(1280, custom.height)
        assertEquals(320, custom.densityDpi)
        assertEquals("试用新版本", custom.purpose)
    }

    @Test
    fun createTruncatesOverlongPurposeInsteadOfFailing() {
        val long = AgentVirtualDisplayTools.parseCreate(
            JSONObject().put("purpose", "用".repeat(500)),
        )!!
        assertTrue("用途说明必须截断到 120 字以内", long.purpose.length <= 120)
    }

    @Test
    fun createCanLaunchInTheSameCallAndDefaultsToCreateOnly() {
        // 不传 package_name：行为与以前完全一致（只建屏），launch 由下一次调用做。
        val createOnly = AgentVirtualDisplayTools.parseCreate(JSONObject())!!
        assertEquals("", createOnly.packageName)
        assertEquals("", createOnly.activity)
        assertFalse(createOnly.force)

        val combined = AgentVirtualDisplayTools.parseCreate(
            JSONObject()
                .put("width", 1080)
                .put("height", 2400)
                .put("package_name", "com.android.settings")
                .put("force", true),
        )!!
        assertEquals("com.android.settings", combined.packageName)
        assertTrue(combined.force)

        // 包名 / 组件名会拼进 am 命令行，所以非法字符必须当场拒掉。
        assertNull(AgentVirtualDisplayTools.parseCreate(JSONObject().put("package_name", "com.foo; rm -rf /")))
        // 2026-10-27：这里原本断的是 `com.foo.Bar` —— 但那是**合法包名**（点分标识符，
        // 首字符为字母），拿它当反例会把正确行为判成缺陷。真正该拒的是带路径分隔符的形式：
        // 拼进 `am start -n` 之后就不再是包名了。
        assertNull(AgentVirtualDisplayTools.parseCreate(JSONObject().put("package_name", "com.foo/Bar")))
        assertNull(
            AgentVirtualDisplayTools.parseCreate(
                JSONObject().put("package_name", "com.android.settings").put("activity", "Settings"),
            ),
        )
    }

    @Test
    fun releaseRequiresAnIdAndAcceptsAll() {
        assertNull(AgentVirtualDisplayTools.parseRelease(JSONObject()))
        assertNull(AgentVirtualDisplayTools.parseRelease(JSONObject().put("id", "  ")))
        assertEquals(
            "all",
            AgentVirtualDisplayTools.parseRelease(JSONObject().put("id", " all "))!!.id,
        )
    }

    @Test
    fun launchRejectsValuesThatCouldBreakTheAmCommandLine() {
        assertNull(AgentVirtualDisplayTools.parseLaunch(JSONObject()))
        assertNull(AgentVirtualDisplayTools.parseLaunch(JSONObject().put("id", "vd1")))
        assertNull(
            AgentVirtualDisplayTools.parseLaunch(
                JSONObject().put("id", "vd1").put("package_name", "com.android.settings; rm -rf /"),
            ),
        )
        assertNull(
            AgentVirtualDisplayTools.parseLaunch(
                JSONObject().put("id", "vd1").put("package_name", "com.android.settings")
                    .put("activity", "com.android.settings/.Settings`whoami`"),
            ),
        )
        assertNull(
            AgentVirtualDisplayTools.parseLaunch(
                JSONObject().put("id", "vd1").put("package_name", "com.android.settings")
                    .put("activity", "Settings"),
            ),
        )
    }

    @Test
    fun launchAcceptsPackageNameAndRelativeComponent() {
        val bare = AgentVirtualDisplayTools.parseLaunch(
            JSONObject().put("id", "vd1").put("package_name", "com.android.settings"),
        )!!
        assertEquals("", bare.activity)

        val relative = AgentVirtualDisplayTools.parseLaunch(
            JSONObject().put("id", "vd1").put("package_name", "com.android.settings")
                .put("activity", "com.android.settings/.Settings"),
        )!!
        assertEquals("com.android.settings/.Settings", relative.activity)

        val absolute = AgentVirtualDisplayTools.parseLaunch(
            JSONObject().put("id", "vd1").put("package_name", "com.android.settings")
                .put("activity", "com.android.settings/com.android.settings.Settings"),
        )!!
        assertEquals("com.android.settings/com.android.settings.Settings", absolute.activity)
    }

    @Test
    fun launchRejectsComponentWithoutSlash() {
        // `am start -n` 只认 `包名/类名`；没有斜杠的写法在命令行上无意义。
        assertNull(
            AgentVirtualDisplayTools.parseLaunch(
                JSONObject().put("id", "vd1").put("package_name", "com.android.settings")
                    .put("activity", "com.android.settings.Settings"),
            ),
        )
        assertNull(
            AgentVirtualDisplayTools.parseLaunch(
                JSONObject().put("id", "vd1").put("package_name", "com.android.settings")
                    .put("activity", "/.Settings"),
            ),
        )
    }

    @Test
    fun everyGestureSchemaAdvertsVirtualDisplaySupport() {
        // display_id 是"不抢主屏"的开关，必须出现在每个可作用于虚拟屏的手势工具里。
        val gesture = org.json.JSONArray()
        io.github.mangi.eta.agent.model.AgentGestureToolCatalog.appendTo(gesture)
        val names = mutableListOf<String>()
        for (i in 0 until gesture.length()) {
            val function = gesture.getJSONObject(i).getJSONObject("function")
            names += function.getString("name")
            val properties = function.getJSONObject("parameters").getJSONObject("properties")
            if (function.getString("name") in setOf("tap", "tap_area", "long_press", "swipe", "scroll")) {
                assertTrue(
                    "${function.getString("name")} 必须支持 display_id",
                    properties.has("display_id"),
                )
            }
        }
        assertTrue(names.containsAll(listOf("tap", "tap_area", "long_press", "swipe", "scroll")))
    }

    @Test
    fun launchWithoutActivityUsesPackageFlagNotComponentFlag() {
        // 裸包名给 `-n` 会让 am 回 "Activity class {pkg} does not exist"，
        // 用户看到的是「应用没装」。没有 activity 时必须用 `-p`。
        val request = AgentVirtualDisplayTools.parseLaunch(
            JSONObject().put("id", "vd1").put("package_name", "com.android.settings"),
        )!!
        val command = AgentVirtualDisplayTools.buildAmStartCommand(7, request)
        assertTrue(command.startsWith("am start --display 7 "))
        assertTrue(command.contains("-p 'com.android.settings'"))
        assertFalse("裸包名不能给 -n", command.contains("-n "))
        assertTrue(command.contains("-a 'android.intent.action.MAIN'"))
        assertTrue(command.contains("-c android.intent.category.LAUNCHER"))
        assertTrue(command.contains("-f 268435456"))
    }

    @Test
    fun launchWithDataDropsLauncherCategory() {
        // LAUNCHER 的 intent filter 不含数据 URI；带上就筛不到 activity。
        val request = AgentVirtualDisplayTools.parseLaunch(
            JSONObject().put("id", "vd1").put("package_name", "com.android.settings")
                .put("data", "https://example.com/x?y=1&z=2")
                .put("action", "android.intent.action.VIEW"),
        )!!
        val command = AgentVirtualDisplayTools.buildAmStartCommand(3, request)
        assertTrue(command.contains("--display 3"))
        assertTrue(command.contains("-d 'https://example.com/x?y=1&z=2'"))
        assertFalse(command.contains("android.intent.category.LAUNCHER"))
        assertFalse(command.contains(" -c "))
    }

    @Test
    fun extrasKeepTheirTypes() {
        val request = AgentVirtualDisplayTools.parseLaunch(
            JSONObject().put("id", "vd1").put("package_name", "com.android.settings")
                .put(
                    "extras",
                    JSONObject().put("title", "x y").put("count", 5).put("flag", true),
                ),
        )!!
        val command = AgentVirtualDisplayTools.buildAmStartCommand(1, request)
        assertTrue(command.contains("--es 'title' 'x y'"))
        assertTrue(command.contains("--ei 'count' '5'"))
        assertTrue(command.contains("--ez 'flag' 'true'"))
    }

    @Test
    fun launchRejectsIntentPiecesThatCouldEscapeTheCommandLine() {
        fun base() = JSONObject().put("id", "vd1").put("package_name", "com.android.settings")
        assertNull("data 必须有 scheme", AgentVirtualDisplayTools.parseLaunch(base().put("data", "no-scheme")))
        assertNull(
            "data 不能带换行",
            AgentVirtualDisplayTools.parseLaunch(base().put("data", "https://x\nrm -rf /")),
        )
        assertNull(
            "action 只允许点分标识符",
            AgentVirtualDisplayTools.parseLaunch(base().put("action", "android.intent.action.VIEW; id")),
        )
        assertNull(
            "extras key 只允许标识符",
            AgentVirtualDisplayTools.parseLaunch(base().put("extras", JSONObject().put("bad key", "v"))),
        )
        assertNull(
            "flags 不能为负",
            AgentVirtualDisplayTools.parseLaunch(base().put("flags", -1)),
        )
        val many = JSONObject()
        repeat(21) { many.put("k$it", "v") }
        assertNull(
            "extras 最多 20 个",
            AgentVirtualDisplayTools.parseLaunch(base().put("extras", many)),
        )
    }

    @Test
    fun shellQuotingEscapesSingleQuotesInsideValues() {
        val request = AgentVirtualDisplayTools.parseLaunch(
            JSONObject().put("id", "vd1").put("package_name", "com.android.settings")
                .put("extras", JSONObject().put("q", "it's")),
        )!!
        val command = AgentVirtualDisplayTools.buildAmStartCommand(1, request)
        assertTrue("单引号必须转义成 '\\''，实际：$command", command.contains("'it'\\''s'"))
    }

    // ---- 启动链路：组件解析 / 显式组件 / force ----

    @Test
    fun launcherComponentPrefersTheMatchingPackage() {
        // 真机输出样本：类名属于另一个包（com.android.calculator2），不能按"包名+简单类名"拼。
        val output = """
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
            com.coloros.calculator/com.android.calculator2.Calculator
        """.trimIndent()
        assertEquals(
            "com.coloros.calculator/com.android.calculator2.Calculator",
            AgentVirtualDisplayTools.parseLauncherComponent(output, "com.coloros.calculator"),
        )
    }

    @Test
    fun launcherComponentFallsBackToAnyValidComponent() {
        // 别名可能挂在别的包下；此时仍应返回可用组件，而不是"解析失败"。
        val output = "priority=0\ncom.other.pkg/.AliasActivity\n"
        assertEquals(
            "com.other.pkg/.AliasActivity",
            AgentVirtualDisplayTools.parseLauncherComponent(output, "com.example.app"),
        )
    }

    @Test
    fun launcherComponentReturnsNullWhenNothingResolvable() {
        assertNull(AgentVirtualDisplayTools.parseLauncherComponent("No activities found", "com.example.app"))
        assertNull(AgentVirtualDisplayTools.parseLauncherComponent("", "com.example.app"))
    }

    @Test
    fun activityThatIsAlreadyAFullComponentIsNotPrefixedTwice() {
        // 真机案例（fix184）：tool 参数 activity 按文档传完整组件名，
        // 旧实现拼成 pkg/pkg/...，am 回 "Activity class does not exist"，
        // 于是 virtual_display_launch 在本包里永远启动不了指定 Activity。
        val request = AgentVirtualDisplayTools.parseLaunch(
            JSONObject()
                .put("id", "vd1")
                .put("package_name", "io.github.mangi.eta.canary")
                .put("activity", "io.github.mangi.eta.canary/io.github.mangi.eta.agent.device.LimitedProbeActivity"),
        )!!
        val command = AgentVirtualDisplayTools.buildAmStartCommand(9, request)
        assertTrue(
            command,
            command.contains(
                "-n 'io.github.mangi.eta.canary/io.github.mangi.eta.agent.device.LimitedProbeActivity'",
            ),
        )
        assertFalse("包名被拼了两次", command.contains("io.github.mangi.eta.canary/io.github.mangi.eta.canary/"))
        // 组件里的类名属于其他包（计算器就是这样）同样保持原样。
        val cross = AgentVirtualDisplayTools.parseLaunch(
            JSONObject()
                .put("id", "vd1")
                .put("package_name", "com.coloros.calculator")
                .put("activity", "com.coloros.calculator/com.android.calculator2.Calculator"),
        )!!
        assertTrue(
            AgentVirtualDisplayTools.buildAmStartCommand(2, cross)
                .contains("-n 'com.coloros.calculator/com.android.calculator2.Calculator'"),
        )
    }

    @Test
    fun explicitComponentWinsOverPackageInTheAmCommand() {
        val request = AgentVirtualDisplayTools.LaunchRequest(
            id = "vd1",
            packageName = "com.coloros.calculator",
            activity = "",
            component = "com.coloros.calculator/com.android.calculator2.Calculator",
            action = "",
            data = "",
            flags = null,
            extras = emptyMap(),
        )
        val command = AgentVirtualDisplayTools.buildAmStartCommand(10, request)
        assertTrue(command, command.contains("-n 'com.coloros.calculator/com.android.calculator2.Calculator'"))
        assertFalse(command, command.contains(" -p "))
    }

    @Test
    fun launchAcceptsForceFlag() {
        val args = JSONObject()
            .put("id", "vd1")
            .put("package_name", "com.example.app")
            .put("force", true)
        val request = AgentVirtualDisplayTools.parseLaunch(args)
        assertNotNull(request)
        assertTrue(request!!.force)
        // 默认必须是 false：不能悄悄搬走用户正在用的应用。
        val plain = AgentVirtualDisplayTools.parseLaunch(
            JSONObject().put("id", "vd1").put("package_name", "com.example.app"),
        )
        assertFalse(plain!!.force)
    }

    /**
     * 任务栈里还留着记录、进程却已经没了：必须能看出「界面不会更新」。
     * 真机上杀掉进程通常连任务记录一起被系统清掉，这个分支只能靠纯函数守住。
     */
    @Test
    fun foregroundNoteExplainsDeadProcessOnly() {
        assertNull(virtualDisplayForegroundNote("com.example.app", true))
        // 查不到（无 Root）不能当成死了。
        assertNull(virtualDisplayForegroundNote("com.example.app", null))
        val note = virtualDisplayForegroundNote("com.example.app", false)
        assertNotNull(note)
        assertTrue(note!!, note.contains("com.example.app"))
        assertTrue(note, note.contains("进程已经不在了"))
        assertTrue(note, note.contains("virtual_display_launch"))
    }

    /**
     * 屏上没有前台任务时要能分清两件事：确实空了，还是任务栈压根没查到。
     * 真机实测：在虚拟屏上 `kill -9` 应用后，系统会把任务从这块屏移除（task_count=0），
     * 但输出里原本什么提示都没有。
     */
    @Test
    fun emptyNoteSeparatesAReallyEmptyDisplayFromAFailedQuery() {
        val empty = virtualDisplayEmptyNote(tasksKnown = true)
        assertTrue(empty, empty.contains("没有任何任务"))
        assertTrue(empty, empty.contains("virtual_display_launch"))
        assertFalse(empty, empty.contains("查询失败"))

        val unknown = virtualDisplayEmptyNote(tasksKnown = false)
        assertTrue(unknown, unknown.contains("查询失败"))
        assertTrue(unknown, unknown.contains("不代表没有任务"))
        // 查不到时不能断言屏上没东西
        assertFalse(unknown, unknown.contains("当前没有任何任务"))
    }
}
