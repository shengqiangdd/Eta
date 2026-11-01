package io.github.mangi.eta.agent.runtime

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个会话作用域的续跑记忆。
 *
 * 三个字段的生命周期刻意不同（第十二批改过，理由见 `docs/MERGE_3.3.5.md` §32.61）：
 * - [resumed]：这条任务已经续过（手动点「继续」也算）。落盘，重启后仍记得。
 * - [chain]：**当前这条自动续跑链**已经自动续了几次。也落盘 —— 原先是进程内计数，
 *   进程被杀就清零，等于「重启就能绕过上限」。用户主动发一条消息会把它清零，
 *   所以它限制的是「用户不在场时的连续自动续跑」，不是「这个会话一辈子只能续两次」。
 * - [seenUntil]：事件水位线 —— 这个作用域里「已经处理到哪一刻」的结束事件。
 *   没有它时，进程重启后 `completed_at` 还在，却不知道该不该补发；有了它，
 *   重启前刚结束、还没处理的任务仍会续，而更早的历史不会被翻出来重放。
 */
internal data class BackgroundResumeState(
    val resumed: List<String> = emptyList(),
    val chain: Int = 0,
    val seenUntil: Long? = null,
)

/**
 * 「这条后台任务已经续过」的落盘记忆，按会话作用域分文件。
 *
 * 为什么需要落盘：卡片里的续跑状态原来只活在进程里，App 被回收再打开，
 * 「继续」按钮又出现在同一批任务上 —— 用户点过一次的任务被当成没点过。
 *
 * 与 [BackgroundEventStore] 同一套取舍：作用域哈希命名、进程级一把写锁、`.tmp` 换名、
 * 坏文件按「没有记录」处理。返回 Boolean 而不是抛异常：这是旁路存储，写不进去不该让界面失败。
 *
 * 向后兼容：第九批只写 `resumed`，第十一批之前没有 `chain` / `seen_until`。
 * 缺字段一律按默认值读（chain=0、seenUntil=null），**不能因为缺一个键就把整份记忆丢掉** ——
 * 那会让升级后的第一次启动把用户点过的「继续」全忘掉。
 */
internal class BackgroundResumeStore(private val root: File) {

    fun fileFor(scopeId: String): File? {
        if (scopeId.isBlank()) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(scopeId.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
            .take(32)
        return File(root, "$digest.json")
    }

    /**
     * 已经续过的任务 id，最近的排在末尾。
     *
     * 空作用域、文件不存在、超过大小上限、JSON 坏、字段类型不对 —— 一律当「没有记录」，
     * 不抛异常：这个记忆只用来少显示一个按钮，读不出来就退回原来的行为。
     */
    fun load(scopeId: String): List<String> = loadState(scopeId).resumed

    /** 完整状态。任何单个字段坏掉只影响它自己，不牵连其余字段。 */
    fun loadState(scopeId: String): BackgroundResumeState {
        val file = fileFor(scopeId) ?: return BackgroundResumeState()
        if (!file.isFile || file.length() > MAX_BYTES) return BackgroundResumeState()
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return BackgroundResumeState()
        val stored = runCatching { JSONObject(text) }.getOrNull() ?: return BackgroundResumeState()
        val array = stored.optJSONArray("resumed")
        val ids = if (array == null) {
            emptyList()
        } else {
            sanitize((0 until array.length()).map { index -> array.optString(index) })
        }
        return BackgroundResumeState(
            resumed = ids,
            // 坏值（负数、溢出、字符串）一律退回 0：宁可多自动续一次，也不要永久停用。
            chain = stored.optInt("chain", 0).coerceIn(0, MAX_CHAIN),
            // 0 / 负数 / 缺失都表示「还没有水位线」，调用方据此做首次初始化。
            seenUntil = stored.optLong("seen_until", 0L).takeIf { it > 0 },
        )
    }

    /**
     * 只覆盖 id，保留磁盘上的 chain / seenUntil —— 「合并写入」语义。
     *
     * 第十八批之前卡片手动点「继续」走的就是这条路径；现在它改走
     * [BackgroundResumeScope.resumeManually]（整份状态 + 作用域锁），
     * 因为同一个作用域有多个写者，逐字段合并不足以防止旧快照盖回去。
     * 保留这个入口是因为它把「只改 id」的语义写清楚了，并且有用例守着；
     * **生产侧目前没有调用方** —— 新增调用前先想清楚现场到底该不该整份写。
     */
    fun save(scopeId: String, ids: List<String>): Boolean {
        val file = fileFor(scopeId) ?: return false
        synchronized(WRITE_LOCK) {
            val current = loadState(scopeId)
            return write(file, current.copy(resumed = sanitize(ids)))
        }
    }

    /** 整份状态覆盖写入；只保留最后 [MAX_IDS] 条 id。 */
    fun saveState(scopeId: String, state: BackgroundResumeState): Boolean {
        val file = fileFor(scopeId) ?: return false
        val cleaned = state.copy(resumed = sanitize(state.resumed))
        synchronized(WRITE_LOCK) { return write(file, cleaned) }
    }

    /** 去掉空白、去重（保留**最近**的一次出现）、只留最后 [MAX_IDS] 条。 */
    private fun sanitize(ids: List<String>): List<String> =
        ids.asReversed()
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_IDS)
            .asReversed()

    private fun write(file: File, state: BackgroundResumeState): Boolean {
        val dir = file.parentFile ?: return false
        return runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) return false
            val payload = JSONObject()
                .put("resumed", JSONArray(state.resumed))
                .put("chain", state.chain.coerceIn(0, MAX_CHAIN))
                .put("seen_until", state.seenUntil ?: JSONObject.NULL)
                .toString()
            // 加了上限还超限，说明单条 id 长得离谱；宁可不写，也不留一个读不回来的文件。
            if (payload.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return false
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(payload, Charsets.UTF_8)
            // 直接 rename 覆盖在某些文件系统上会失败，先删再换；失败就清掉临时文件。
            if (file.exists() && !file.delete()) {
                tmp.delete()
                return false
            }
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return false
            }
            true
        }.getOrDefault(false)
    }

    companion object {
        /** 文件里最多 64 个 id；超过这个大小就是坏文件，直接当作没有记录。 */
        const val MAX_BYTES = 8 * 1024

        /** 每个作用域记多少条。任务 id 会随记录清理消失，留太多只是白占空间。 */
        const val MAX_IDS = 64

        /**
         * 链长只用于「有没有到上限」的比较，不需要真实计数。
         * 上限存在的意义是防止坏文件里一个天文数字让文件永远写不回去。
         */
        const val MAX_CHAIN = 64

        /** 进程级一把锁：同一会话可能有两个 run（主进程 + 语音），按实例加锁等于没加锁。 */
        private val WRITE_LOCK = Any()

        fun forApp(context: Context): BackgroundResumeStore =
            BackgroundResumeStore(File(context.applicationContext.filesDir, "agent/background_resume"))
    }
}
