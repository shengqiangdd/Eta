package io.github.mangi.eta.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `am stack list` 解析。
 *
 * 样本全部来自真机（PKX110 / Android 16 / ColorOS）的实际输出，
 * 包括一个真实踩过的坑：浏览器任务的亲和性写成 `android.task.browser`，
 * 与包名不同 —— 只按包名判断"这个应用在不在跑"会漏。
 */
class VirtualDisplayTasksTest {

    private val real = """
        RootTask id=7591 bounds=[855,355][2071,2517] displayId=0 userId=0
         configuration={1.0 ??mcc??mnc [zh_CN]}
          taskId=7591: io.github.mangi.eta/io.github.mangi.eta.ui.MainActivity bounds=[855,355][2071,2517] userId=0 visible=true topActivity=ComponentInfo{io.github.mangi.eta/io.github.mangi.eta.ui.MainActivity}

        RootTask id=7612 bounds=[0,0][1216,2640] displayId=0 userId=0
         configuration={1.0 ??mcc??mnc [zh_CN]}
          taskId=7612: com.heytap.browser/com.android.browser.BrowserActivity bounds=[0,0][1216,2640] userId=0 visible=false topActivity=ComponentInfo{com.heytap.browser/com.android.browser.BrowserActivity}

        RootTask id=7615 bounds=[0,0][1080,2400] displayId=10 userId=0
         configuration={1.0 ??mcc??mnc [zh_CN]}
          taskId=7615: com.coloros.note/com.nearme.note.main.MainActivity bounds=[0,0][1080,2400] userId=0 visible=true topActivity=ComponentInfo{com.coloros.note/com.nearme.note.main.MainActivity}

        RootTask id=3 bounds=[0,0][1216,2640] displayId=0 userId=0
         configuration={1.0 ??mcc??mnc [zh_CN]}
          taskId=4: unknown bounds=[0,0][1216,2640] userId=0 visible=false
    """.trimIndent()

    @Test
    fun parsesDisplayAndPackageFromRealOutput() {
        val tasks = VirtualDisplayTasks.parse(real)
        assertEquals(4, tasks.size)
        val note = tasks.single { it.taskId == 7615 }
        assertEquals(10, note.displayId)
        assertEquals("com.coloros.note", note.packageName)
        assertEquals("com.coloros.note", note.topPackage)
        assertTrue(note.visible)
    }

    @Test
    fun unknownTasksAreNotTreatedAsAPackage() {
        val unknown = VirtualDisplayTasks.parse(real).single { it.taskId == 4 }
        assertEquals("", unknown.packageName)
        assertEquals("", unknown.topPackage)
    }

    @Test
    fun hostingUsesBothTaskRootAndTopActivity() {
        val tasks = VirtualDisplayTasks.parse(real)
        // 浏览器任务的根 activity 与栈顶都在它自己身上。
        assertEquals(setOf(0), VirtualDisplayTasks.displaysHosting(tasks, "com.heytap.browser"))
        // 便签只在虚拟屏。
        assertEquals(setOf(10), VirtualDisplayTasks.displaysHosting(tasks, "com.coloros.note"))
        // 没在跑的应用不能误报。
        assertEquals(emptySet<Int>(), VirtualDisplayTasks.displaysHosting(tasks, "com.example.absent"))
    }

    @Test
    fun foregroundPrefersTheVisibleTask() {
        val tasks = VirtualDisplayTasks.parse(real)
        assertEquals("io.github.mangi.eta", VirtualDisplayTasks.foregroundOn(tasks, 0))
        assertEquals("com.coloros.note", VirtualDisplayTasks.foregroundOn(tasks, 10))
        // 不存在的屏：空串，而不是随便挑一条。
        assertEquals("", VirtualDisplayTasks.foregroundOn(tasks, 42))
    }

    @Test
    fun topActivityWinsWhenItDiffersFromTaskRoot() {
        // 真实场景：酷派客的根是 MainActivity，栈顶是详情页。
        val sample = """
            RootTask id=7604 bounds=[0,0][1080,2400] displayId=10 userId=0
              taskId=7604: com.coolapk.market/com.coolapk.market.view.main.MainActivity bounds=[0,0][1080,2400] userId=0 visible=true topActivity=ComponentInfo{com.coolapk.market/com.coolapk.market.view.feed.FeedDetailActivityV8}
        """.trimIndent()
        val task = VirtualDisplayTasks.parse(sample).single()
        assertEquals("com.coolapk.market", task.packageName)
        assertEquals("com.coolapk.market", task.topPackage)
        assertEquals("com.coolapk.market", VirtualDisplayTasks.foregroundOn(listOf(task), 10))
    }

    @Test
    fun crlfAndMissingTopActivityAreTolerated() {
        val crlf = "RootTask id=1 bounds=[0,0][1,1] displayId=7 userId=0\r\n  taskId=9: com.example.app/.Main visible=false\r\n"
        val task = VirtualDisplayTasks.parse(crlf).single()
        assertEquals(7, task.displayId)
        assertEquals(9, task.taskId)
        assertEquals("com.example.app", task.packageName)
        // 没有 topActivity 时退回任务包名，而不是空串。
        assertEquals("com.example.app", task.topPackage)
    }

    @Test
    fun tasksBeforeAnyRootTaskAreIgnored() {
        assertEquals(0, VirtualDisplayTasks.parse("  taskId=1: com.example.app/.Main visible=true").size)
    }
}
