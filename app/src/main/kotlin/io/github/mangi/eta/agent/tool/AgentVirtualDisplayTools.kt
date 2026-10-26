package io.github.mangi.eta.agent.tool

import org.json.JSONArray
import org.json.JSONObject

/**
 * 虚拟屏工具的 schema 与参数解析（纯逻辑，可单测）。
 *
 * 为什么单独一层：schema 要进模型上下文，参数校验要在调用 Root 前完成，
 * 两者都不该和 Root/DisplayManager 的真实交互耦合。
 */
internal object AgentVirtualDisplayTools {

    /** 模型可见的工具名。 */
    val names: Set<String> = setOf(
        "virtual_display_create",
        "virtual_display_list",
        "virtual_display_release",
        "virtual_display_launch",
    )

    fun appendTo(tools: JSONArray) {
        tools
            .put(
                tool(
                    name = "virtual_display_create",
                    description =
                        "创建一块**独立虚拟屏**，用于在不打扰用户主屏的前提下操作应用。" +
                            "虚拟屏不会显示在主屏上，也不会镜像主屏内容；Agent 可对它启动应用、" +
                            "截图、输入与滚动。适合：需要登录或配置某个应用、批量处理、并行试用，" +
                            "以及任何不想打断用户当前操作的 GUI 任务。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "width",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "宽度（像素），240..3840；留空用 1080"),
                                )
                                .put(
                                    "height",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "高度（像素），240..3840；留空用 1920"),
                                )
                                .put(
                                    "density_dpi",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "屏幕密度，120..640；留空按主屏比例推算"),
                                )
                                .put(
                                    "purpose",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "用途说明，便于日志回溯；最多 120 字"),
                                )
                                .put(
                                    "package_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "description",
                                            "可选：创建后立刻启动该应用（= create + launch 一次完成），" +
                                                "并自动带回 first_observation（observation_id + ui_nodes）",
                                        ),
                                )
                                .put(
                                    "activity",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "可选，配合 package_name：完整组件名 package/.Activity"),
                                )
                                .put(
                                    "force",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "可选，配合 package_name：该应用已在别处运行时是否允许把它搬过来"),
                                ),
                        ),
                )
            )
            .put(
                tool(
                    name = "virtual_display_list",
                    description = "列出当前存活的所有虚拟屏及其 displayId、尺寸、存活秒数。",
                    parameters = JSONObject().put("type", "object").put("properties", JSONObject()),
                )
            )
            .put(
                tool(
                    name = "virtual_display_release",
                    description =
                        "释放虚拟屏。id=all 时释放全部。任务结束或失败后**必须**调用，" +
                            "否则虚拟屏会一直占用系统资源。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "create 返回的 id；或 all 表示全部释放"),
                                ),
                        )
                        .put("required", JSONArray().put("id")),
                )
            )
            .put(
                tool(
                    name = "virtual_display_launch",
                    description =
                        "在指定虚拟屏上启动应用，**不会把用户主屏切走**。" +
                            "应用已在别处运行时默认拒绝（强行启动会把它从主屏搬走）；" +
                            "确实需要时传 force=true。不传 activity 时自动解析启动入口。" +
                            "启动后会用任务栈核验它是否真的落在该虚拟屏上，并**自动观察一次首屏**：" +
                            "结果里的 first_observation 带 observation_id 与 ui_nodes，可直接接着 tap_element，" +
                            "不必再 wait + observe_screen。首屏若仍在加载，再 observe_screen 刷新一次即可。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "create 返回的虚拟屏 id"),
                                )
                                .put(
                                    "package_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "要启动的应用包名"),
                                )
                                .put(
                                    "activity",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "可选，完整组件名 package/.Activity；留空用启动器入口"),
                                ),
                        )
                        .put(
                            "data",
                            JSONObject()
                                .put("type", "string")
                                .put(
                                    "description",
                                    "可选，intent data（如 https://… 或 myapp://…）；带 scheme 的 URI。" +
                                        "常见用途：直接在虚拟屏打开某个页面而不是首页",
                                ),
                        )
                        .put(
                            "action",
                            JSONObject()
                                .put("type", "string")
                                .put(
                                    "description",
                                    "可选，intent action（如 android.intent.action.VIEW）；留空用 MAIN + LAUNCHER",
                                ),
                        )
                        .put(
                            "flags",
                            JSONObject()
                                .put("type", "integer")
                                .put("description", "可选，intent flags 十进制值；留空用 0x10000000（NEW_TASK）"),
                        )
                        .put(
                            "extras",
                            JSONObject()
                                .put("type", "object")
                                .put(
                                    "description",
                                    "可选，要附加的 intent extras（最多 20 个）；" +
                                        "字符串→--es、整数→--ei、布尔→--ez",
                                ),
                        )
                        .put(
                            "force",
                            JSONObject()
                                .put("type", "boolean")
                                .put(
                                    "description",
                                    "默认 false。该应用已有任务时会被系统「搬」到虚拟屏" +
                                        "（用户主屏上的实例随之消失或状态丢失），默认拒绝执行；" +
                                        "明确接受这个后果才传 true。",
                                ),
                        )
                        .put("required", JSONArray().put("id").put("package_name")),
                )
            )
    }

    data class CreateRequest(
        val width: Int,
        val height: Int,
        val densityDpi: Int?,
        val purpose: String,
        /** 可选：创建后立刻启动的应用；空串表示只建屏。 */
        val packageName: String = "",
        val activity: String = "",
        /** 目标应用已在运行时是否允许把它搬到这块新屏（与 launch 同义）。 */
        val force: Boolean = false,
    )

    data class ReleaseRequest(val id: String)

    data class LaunchRequest(
        val id: String,
        val packageName: String,
        val activity: String,
        /** 解析出来的完整组件 `包名/类名`；空表示还没解析（activity 为空的场景）。 */
        val component: String = "",
        /**
         * 是否接受"该应用已有任务"的后果。
         *
         * 默认 false：`am start --display N` 对已有任务的应用不会新建任务，
         * 而是把那个任务搬过来或把实例顶到前台（真机实测），
         * 那就等于动用户的屏——虚拟屏的意义就没了。
         */
        val force: Boolean = false,
        /** 显式 action；空串表示「用启动器入口」。 */
        val action: String,
        /** intent data（URI）；空串表示不带。 */
        val data: String,
        /** intent flags；null 表示用默认 NEW_TASK。 */
        val flags: Int?,
        /** 附加 extras：key → 带类型的值，按类型选 --es / --ei / --ez。 */
        val extras: Map<String, IntentExtra>,
    )

    /** am 的 extras 参数要分类型：字符串 --es、整数 --ei、布尔 --ez。 */
    data class IntentExtra(val key: String, val value: String, val kind: Kind) {
        enum class Kind { STRING, INT, BOOL }

        /** 对应的 am 参数名。 */
        val flag: String
            get() = when (kind) {
                Kind.STRING -> "--es"
                Kind.INT -> "--ei"
                Kind.BOOL -> "--ez"
            }
    }

    /** 解析失败时返回 null，由调用方产出 INVALID_ARGUMENT。 */
    fun parseCreate(args: JSONObject): CreateRequest? {
        val width = args.optInt("width", 1080)
        val height = args.optInt("height", 1920)
        if (width !in 240..3840 || height !in 240..3840) return null
        val dpi = if (args.has("density_dpi")) {
            val value = args.optInt("density_dpi", -1)
            if (value !in 120..640) return null else value
        } else {
            null
        }
        return CreateRequest(
            width = width,
            height = height,
            densityDpi = dpi,
            purpose = args.optString("purpose").take(120),
            packageName = args.optString("package_name").trim().also { packageName ->
                if (packageName.isNotEmpty() && !ANDROID_ID.matches(packageName)) return null
            },
            activity = args.optString("activity").trim().also { activity ->
                if (activity.isNotEmpty() && !COMPONENT.matches(activity)) return null
            },
            force = args.optBoolean("force"),
        )
    }

    fun parseRelease(args: JSONObject): ReleaseRequest? {
        val id = args.optString("id").trim()
        if (id.isEmpty()) return null
        return ReleaseRequest(id)
    }

    fun parseLaunch(args: JSONObject): LaunchRequest? {
        val id = args.optString("id").trim()
        val packageName = args.optString("package_name").trim()
        if (id.isEmpty() || packageName.isEmpty()) return null
        // 组件名只接受合法 Android 标识符，避免把任意字符串拼进 am 命令行。
        if (!ANDROID_ID.matches(packageName)) return null
        val activity = args.optString("activity").trim()
        // `am start -n <pkg>/<cls>` 的组件名必须是 `包名/类名` 形式：
        // 允许 `pkg/.Cls`、`pkg/sub.Cls`、`pkg/com.full.Cls`；
        // 裸类名（"Settings"）与裸包名在命令行上都不合法，一律拒绝。
        if (activity.isNotEmpty() && !COMPONENT.matches(activity)) return null
        val action = args.optString("action").trim()
        if (action.isNotEmpty() && !ACTION_NAME.matches(action)) return null
        val data = args.optString("data").trim()
        if (data.isNotEmpty()) {
            // 只校验「有 scheme、无控制字符」：URI 其余部分交给系统解析，
            // 这里负责的是「不要把换行/引号之类的字符带进 am 命令行」。
            if (data.length > 2048) return null
            if (data.any { it == '\n' || it == '\r' || it == '\u0000' }) return null
            if (!URI_WITH_SCHEME.matches(data)) return null
        }
        val flags = if (args.has("flags")) {
            val value = args.optInt("flags", -1)
            if (value < 0) return null else value
        } else {
            null
        }
        val extras = parseExtras(args.optJSONObject("extras")) ?: return null
        return LaunchRequest(
            id = id,
            packageName = packageName,
            activity = activity,
            force = args.optBoolean("force", false),
            action = action,
            data = data,
            flags = flags,
            extras = extras,
        )
    }

    /** extras 解析：类型决定 am 参数；不认识的类型一律拒绝，避免悄悄丢参数。 */
    private fun parseExtras(json: JSONObject?): Map<String, IntentExtra>? {
        if (json == null) return emptyMap()
        if (json.length() > 20) return null
        val out = LinkedHashMap<String, IntentExtra>()
        for (key in json.keys()) {
            if (!EXTRA_KEY.matches(key)) return null
            // 用无主语的 when：JSONObject.opt 返回平台类型 Any!，
            // 带主语的 when 在 Kotlin 2.x 上会被判成「不穷尽」而报 warning。
            val value = json.opt(key)
            val extra = when {
                value is Boolean -> IntentExtra(key, if (value) "true" else "false", IntentExtra.Kind.BOOL)
                value is Int -> IntentExtra(key, value.toString(), IntentExtra.Kind.INT)
                value is Long -> IntentExtra(key, value.toString(), IntentExtra.Kind.INT)
                value is Number -> IntentExtra(key, value.toString(), IntentExtra.Kind.INT)
                value is String -> {
                    if (value.length > 500) return null
                    IntentExtra(key, value, IntentExtra.Kind.STRING)
                }

                else -> return null
            }
            out[key] = extra
        }
        return out
    }

    /**
     * 拼 `am start --display N` 命令（纯字符串，单测可直接核对）。
     *
     * 两个必须小心的地方：
     * 1. **`-n` 只接受完整组件名**（`pkg/.Cls`）。旧实现在没有 activity 时直接把裸包名
     *    给了 `-n`，am 会回 `Activity class {pkg} does not exist` —— 看起来像「应用没装」，
     *    其实是指令不合法。没有组件时改用 `-p pkg`。
     * 2. **带 data 时不能再加 LAUNCHER category**：launcher 的 intent filter 不含数据 URI，
     *    加上就筛不到任何 activity（表现为「无匹配 Activity」）。
     */
    fun buildAmStartCommand(displayId: Int, request: LaunchRequest): String {
        val builder = StringBuilder("am start --display ").append(displayId)
        val action = request.action.ifEmpty { "android.intent.action.MAIN" }
        builder.append(" -a ").append(shellQuote(action))
        val explicitComponent = request.component.ifEmpty {
            if (request.activity.isNotEmpty()) request.packageName + "/" + request.activity else ""
        }
        if (explicitComponent.isNotEmpty()) {
            builder.append(" -n ").append(shellQuote(explicitComponent))
        } else {
            builder.append(" -p ").append(shellQuote(request.packageName))
        }
        if (request.data.isNotEmpty()) {
            builder.append(" -d ").append(shellQuote(request.data))
        }
        if (request.action.isEmpty() && request.data.isEmpty()) {
            builder.append(" -c android.intent.category.LAUNCHER")
        }
        builder.append(" -f ").append(request.flags ?: 0x10000000)
        request.extras.values.forEach { extra ->
            builder.append(' ').append(extra.flag)
                .append(' ').append(shellQuote(extra.key))
                .append(' ').append(shellQuote(extra.value))
        }
        return builder.toString()
    }

    /**
     * 从 `cmd package resolve-activity --brief ...` 的输出里取启动组件。
     *
     * 为什么需要它：`am start -p <pkg> -c LAUNCHER` 在本机（Android 16 / ColorOS）
     * **解析不出活动**，命令直接失败 —— 于是"不指定 activity"这条最自然的用法根本用不了。
     * 自己先解析成 `包名/类名` 再用 `-n` 启动才稳。
     *
     * 输出样本（真机）：
     * ```
     * priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
     * com.coloros.calculator/com.android.calculator2.Calculator
     * ```
     * 注意组件里的类名可能属于**另一个包**（如上例的 com.android.calculator2），
     * 所以不能假设 `包名 + "/" + 简单类名`。
     */
    fun parseLauncherComponent(resolveOutput: String, packageName: String): String? {
        val candidates = resolveOutput.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains(' ') && it.contains('/') }
            .toList()
        // 优先返回包名匹配的那条；没有则退回最后一条合法组件（别名可能挂在别的包下）。
        return candidates.lastOrNull { it.substringBefore('/') == packageName } ?: candidates.lastOrNull()
    }

    /** 单引号包裹，内部单引号转义：值来自模型参数，一律当数据而不是命令。 */
    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    /** 合法 Android 包名 / 类名标识符（可含点分段，也允许单段）。 */
    private val ANDROID_ID = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*$")
    /** intent action 名：`android.intent.action.VIEW` 这类点分标识符。 */
    private val ACTION_NAME = Regex("^[A-Za-z][A-Za-z0-9_.]*$")
    /** extras 的 key：允许点分段（部分应用用 `android.x.y` 形式的 key）。 */
    private val EXTRA_KEY = Regex("^[A-Za-z_][A-Za-z0-9_.]*$")
    /** data 必须带 scheme：`https:`、`myapp:`、`content:` 之类。 */
    private val URI_WITH_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:[^\\s]*$")

    /** activity 必须是 `包名/类名`：类名可带前导点表示与包名同级的相对写法。 */
    private val COMPONENT = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*/[.]?[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*$")

    private fun tool(name: String, description: String, parameters: JSONObject): JSONObject =
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", parameters),
            )
}
