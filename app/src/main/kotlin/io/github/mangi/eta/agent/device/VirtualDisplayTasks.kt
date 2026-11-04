package io.github.mangi.eta.agent.device

/**
 * `am stack list` 的解析（纯逻辑，可单测）。
 *
 * 为什么要解析它：虚拟屏"互不干扰"的前提是**知道某个应用的任务此刻在哪块屏上**。
 * 没有这个事实，就只能假设 `am start --display N` 一定把应用放到了 N ——
 * 实测并非如此：对已有任务的应用，系统会把那个任务**搬过来**（或把用户主屏上的实例顶到前台），
 * 于是"在虚拟屏上操作"变成了"动用户的屏"。
 *
 * `dumpsys activity activities` 也能读，但它的分节顺序与 `Display #` 归属在真机上很容易读错
 * （我自己就先读错过一次）；`am stack list` 每条 RootTask 直接带 `displayId=`，
 * 每条 task 直接带 `visible=` 与 `topActivity=`，且是稳定的一行一字段文本。
 *
 * 样本（真机 Android 16 / ColorOS）：
 * ```
 * RootTask id=7615 bounds=[0,0][1080,2400] displayId=10 userId=0
 *   taskId=7615: com.coloros.note/com.nearme.note.main.MainActivity bounds=[0,0][1080,2400] userId=0 visible=true topActivity=ComponentInfo{com.coloros.note/com.nearme.note.main.MainActivity}
 * ```
 */
internal data class DisplayTask(
    /** RootTask id，也就是 `am stack` 语境下的 stack id。 */
    val rootTaskId: Int,
    val displayId: Int,
    val taskId: Int,
    /** 任务根 activity 的包名；`unknown` 任务为空串。 */
    val packageName: String,
    /** 栈顶 activity 的包名；缺 `topActivity` 时退回 [packageName]。 */
    val topPackage: String,
    val visible: Boolean,
)

internal object VirtualDisplayTasks {

    private val ROOT_TASK = Regex("""^\s*RootTask id=(\d+)\b.*?\bdisplayId=(-?\d+)""")
    // 任务行有两种形态：`taskId=9: pkg/Cls ...` 与 `taskId=4: unknown ...`。
    // 第二种照样记一条（包名留空），否则"这块屏上有几个任务"会数错。
    private val TASK = Regex("""^\s*taskId=(\d+):\s*(\S+)(.*)$""")
    private val VISIBLE = Regex("""\bvisible=(true|false)\b""")
    private val TOP_ACTIVITY = Regex("""topActivity=ComponentInfo\{([^/}]+)/""")

    fun parse(stackList: String): List<DisplayTask> {
        val out = ArrayList<DisplayTask>()
        var current: Pair<Int, Int>? = null // rootTaskId to displayId
        for (raw in stackList.lineSequence()) {
            val line = raw.trimEnd('\r')
            val root = ROOT_TASK.find(line)
            if (root != null) {
                current = root.groupValues[1].toIntOrNull()?.let { rootId ->
                    root.groupValues[2].toIntOrNull()?.let { display -> rootId to display }
                }
                continue
            }
            val owner = current ?: continue
            val task = TASK.find(line) ?: continue
            val taskId = task.groupValues[1].toIntOrNull() ?: continue
            // `pkg/Cls` 里取斜杠前的包名；`unknown` 没有斜杠，整段就是它。
            val taskPackage = task.groupValues[2].substringBefore('/')
            val rest = task.groupValues[3]
            val topPackage = TOP_ACTIVITY.find(rest)?.groupValues?.get(1)?.trim().orEmpty()
            out += DisplayTask(
                rootTaskId = owner.first,
                displayId = owner.second,
                taskId = taskId,
                packageName = taskPackage.takeIf { it != "unknown" }.orEmpty(),
                topPackage = topPackage.ifEmpty {
                    taskPackage.takeIf { it != "unknown" }.orEmpty()
                },
                visible = VISIBLE.find(rest)?.groupValues?.get(1) == "true",
            )
        }
        return out
    }

    /** 某块屏上的任务。 */
    fun forDisplay(tasks: List<DisplayTask>, displayId: Int): List<DisplayTask> =
        tasks.filter { it.displayId == displayId }

    /**
     * 某个包当前占据的屏（任务根或栈顶任一命中即算）。
     *
     * 两边都看：`launchMode=singleTask` 的应用根 activity 可能是别的包
     * （例如浏览器任务的亲和性是 `android.task.browser`，但栈顶仍是浏览器自己），
     * 只看包名会漏掉"它其实已经在跑"。
     */
    fun displaysHosting(tasks: List<DisplayTask>, packageName: String): Set<Int> =
        tasks.filter { it.packageName == packageName || it.topPackage == packageName }
            .mapTo(mutableSetOf()) { it.displayId }

    /**
     * 启动前的处置决定：能不能在这块虚拟屏上启动，还是应该直接观察/拒绝。
     *
     * 抽成纯函数是因为这里每一条都对应一次真实的「动了用户的屏」：
     * 2026-11-04 我用 force=true 把用户主屏上的 Eta 搬到了虚拟屏，
     * 用户手上的界面当场被划掉。判据本身不难，难的是**没人测它**。
     */
    sealed interface LaunchVerdict {
        /** 直接启动（目标没在运行，或在别的虚拟屏上）。 */
        data object Proceed : LaunchVerdict

        /** 已经在这块屏上，启动是多余的，观察即可。 */
        data object AlreadyHere : LaunchVerdict

        /** 已有任务在别处，且没给 force。 */
        data class Occupied(val displays: Set<Int>, val onMainScreen: Boolean) : LaunchVerdict
    }

    fun decideLaunch(
        hosting: Set<Int>,
        targetDisplayId: Int,
        force: Boolean,
        mainDisplayId: Int = 0,
    ): LaunchVerdict {
        // 只在这一块屏上 → 不用启动。
        // 注意判据是「唯一且等于目标」：若同时在主屏和目标屏都有任务，
        // 说明状态本就混乱，不该当作「已就绪」放过去。
        if (hosting.size == 1 && hosting.first() == targetDisplayId) return LaunchVerdict.AlreadyHere
        val others = hosting - targetDisplayId
        if (others.isEmpty()) return LaunchVerdict.Proceed
        if (force) return LaunchVerdict.Proceed
        return LaunchVerdict.Occupied(displays = hosting, onMainScreen = mainDisplayId in hosting)
    }

    /**
     * 某块屏当前可见任务对应的包；没有可见任务时退回任一任务。
     *
     * 只信 `visible=true` 的那条：`am stack list` 里同一块屏可能挂着多个后台任务，
     * 拿第一个当"前台"会让观测结果说假话（正是要修的缺陷之一）。
     */
    fun foregroundOn(tasks: List<DisplayTask>, displayId: Int): String {
        val onDisplay = forDisplay(tasks, displayId)
        return (onDisplay.firstOrNull { it.visible } ?: onDisplay.firstOrNull())
            ?.topPackage
            .orEmpty()
    }
}
