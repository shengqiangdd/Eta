package io.github.mangi.eta.agent.terminal

import android.annotation.SuppressLint

/**
 * 按路径自动纠正 `environment`。
 *
 * 现场：模型经常省略 `environment`（默认 android），却给出 `/workspace/...` 的路径 ——
 * 设备侧没有这个目录，于是 `terminal` 回一句 `cd: can't cd to /workspace`、
 * 文件工具返回 `FILE_NOT_FOUND`。日志里这类失败 44 次，每次都得多花一轮「换 environment 再试」，
 * 有时还会被读成「文件真的不存在」。
 *
 * 判断必须保守：只有**全部**路径都指向另一侧环境时才切换。同时出现两侧路径
 * （例如 `adb pull /data/local/tmp/x /workspace/x`）说明这是跨环境操作，
 * 切任何一边都会让另一半失败 —— 此时保持原样，交给 [EnvironmentMismatchHint] 解释。
 */
internal object EnvironmentPathHeuristic {

    const val ANDROID = "android"
    const val LINUX = "linux"

    /** 设备侧不存在的 Linux 路径前缀（与 [EnvironmentMismatchHint] 保持同一口径）。 */
    private val LINUX_PREFIXES = listOf("/workspace", "/root")

    /**
     * Linux 环境里不存在的 Android 路径前缀。
     *
     * 这些值只用于**字符串前缀识别**，不访问任何文件；换成 `Context.getFilesDir()` 之类的
     * 运行时路径就识别不出来了，所以保留字面量并显式豁免 lint。
     */
    @SuppressLint("SdCardPath")
    private val ANDROID_PREFIXES = listOf(
        "/data/local/tmp",
        "/data/adb",
        "/data/user/",
        "/data/data/",
        "/sdcard",
        "/storage/emulated",
        "/mnt/media_rw",
        "/system/",
        "/vendor/",
    )

    /**
     * 命令里的绝对路径候选。刻意宽松：拿到多余的字符串只会让判断更保守
     * （匹配不上任何前缀就忽略），漏掉真正的路径才会让功能失效。
     */
    private val PATH_IN_COMMAND = Regex("""/[^\s;|&<>"'`(){}$\\]*[^\s;|&<>"'`(){}$\\,.]""")

    /**
     * 两边**都能访问**的路径：chroot 里 `/data/local/tmp`、`/storage/emulated/0`、`/sdcard`
     * 是默认挂载的（本机实测）。
     *
     * 它们不能作为「该切到哪一侧」的依据：`cd /data/local/tmp/... && ./gradlew` 曾被静默切到
     * android，那里没有 bash/gradle，报“bash: not found”。这些路径一律交给显式 environment 决定。
     */
    @SuppressLint("SdCardPath")
    private val SHARED_PREFIXES = listOf("/data/local/tmp", "/sdcard", "/storage/emulated")

    data class Resolution(
        val environment: String,
        val switchedFrom: String? = null,
        private val hint: String? = null,
    ) {
        val switched: Boolean get() = switchedFrom != null

        /** 直接进工具结果的说明；没切换也没有提示时为 null。 */
        val note: String?
            get() = switchedFrom?.let { from ->
                "本次 environment=$from，但路径属于 $environment 环境；已自动切换为 environment=$environment。" +
                    "要作用于另一侧请显式改传 environment=$from。"
            } ?: hint
    }

    /**
     * @param requested 模型传入的 environment（缺省视为 android）。
     * @param paths 本次操作涉及的路径；空元素与非绝对路径会被忽略。
     */
    fun resolve(requested: String, paths: Collection<String>): Resolution {
        val normalized = requested.trim().ifBlank { ANDROID }.lowercase()
        val candidates = paths.map(String::trim).filter { it.startsWith("/") }
        if (candidates.isEmpty()) return Resolution(normalized)
        // 两边都挂载的路径（chroot 里 /data/local/tmp、/sdcard、/storage/emulated 默认挂载）
        // **两边都算数**：既不能单独证明该切 linux，也不能单独证明该切 android。
        val sharedHit = candidates.any { matches(it, SHARED_PREFIXES) }
        val decisive = candidates.filterNot { matches(it, SHARED_PREFIXES) }
        if (decisive.isEmpty()) {
            // 只有共享路径时两侧都说得通 → 不猜，按显式 environment 执行（但说一声，
            // 免得模型以为“路径有 android 前缀所以已经切过去了”）。
            return if (sharedHit) {
                Resolution(
                    environment = normalized,
                    hint = "命令里的路径（/data/local/tmp、/sdcard、/storage/emulated）在两侧都能访问，" +
                        "因此不做切换、按 environment=$normalized 执行。",
                )
            } else {
                Resolution(normalized)
            }
        }
        val linuxHit = sharedHit || decisive.any { matches(it, LINUX_PREFIXES) }
        val androidHit = sharedHit || decisive.any { matches(it, ANDROID_PREFIXES) }
        val target = when {
            linuxHit && !androidHit -> LINUX
            androidHit && !linuxHit -> ANDROID
            // 两侧都有、或哪侧都没有：不猜。
            else -> return Resolution(normalized)
        }
        if (target == normalized) return Resolution(normalized)
        return Resolution(target, normalized)
    }

    /** 从命令文本里提取绝对路径，供 [resolve] 判定。 */
    fun pathsInCommand(command: String): List<String> =
        PATH_IN_COMMAND.findAll(command).map { it.value }.toList()

    private fun matches(path: String, prefixes: List<String>): Boolean = prefixes.any { raw ->
        val base = raw.trimEnd('/')
        path == base || path.startsWith("$base/")
    }
}
