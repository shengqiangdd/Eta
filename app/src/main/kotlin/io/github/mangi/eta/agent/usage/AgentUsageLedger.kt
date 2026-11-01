package io.github.mangi.eta.agent.usage

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject

/**
 * 模型用量的本地账本。
 *
 * 只追加 JSONL（每月一个文件），不做数据库迁移：用量是"可丢失的观测数据"，
 * 损坏一行不应影响对话，因此读取时逐行容错、写入失败静默。
 *
 * 口径说明：`cached` 由各服务商返回，有的把缓存命中计入 `input`，有的单列。
 * 写入时同时记录 `prompt`（服务商口径的**总输入**），命中率 = `cached / prompt`；
 * 旧账本没有 `prompt`，读取时回退到 `cached / (cached + input)`（与旧版行为一致）。
 */
internal class AgentUsageLedger(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    data class Record(
        val ts: Long,
        val provider: String,
        val model: String,
        val purpose: String,
        val input: Int?,
        val output: Int?,
        val cached: Int?,
        val reasoning: Int?,
        /** 服务商口径的总输入 token；旧调用方不传时为 null（读取时走旧公式回退）。 */
        val prompt: Int? = null,
    )

    /**
     * 一条真实的模型调用明细，供「最近调用」列表展示。
     *
     * 与 [Record] 的区别：这里是**读出来**的账本行（缺失字段已补 0、空字符串已补占位），
     * 用于 UI 直接渲染；[Record] 是写入口径，允许 null。
     */
    data class Entry(
        val ts: Long,
        val model: String,
        val purpose: String,
        val input: Long,
        val output: Long,
        val cached: Long,
        val reasoning: Long,
    )

    data class Totals(
        val input: Long = 0,
        val output: Long = 0,
        val cached: Long = 0,
        val reasoning: Long = 0,
        val calls: Long = 0,
        /** 命中率分母：新记录为服务商总输入，旧记录回退为 `input + cached`。 */
        val prompt: Long = 0,
    ) {
        operator fun plus(other: Totals): Totals = Totals(
            input = input + other.input,
            output = output + other.output,
            cached = cached + other.cached,
            reasoning = reasoning + other.reasoning,
            calls = calls + other.calls,
            prompt = prompt + other.prompt,
        )

        val totalTokens: Long get() = input + output

        /** 缓存命中占比；分母是总输入，缺少总输入时返回 0。 */
        val cacheShare: Double
            get() {
                if (prompt <= 0) return 0.0
                return (cached.toDouble() / prompt).coerceIn(0.0, 1.0)
            }
    }

    data class DayBucket(val day: String, val totals: Totals)
    data class ModelBucket(val model: String, val totals: Totals)
    /** 用途桶：chat / compact / rewrite / 其他入口自定义值。 */
    data class PurposeBucket(val purpose: String, val totals: Totals)
    /** 最近 [HOUR_WINDOW_HOURS] 小时的用量；只在短窗口统计时填充。 */
    data class HourBucket(val hourStart: Long, val totals: Totals)

    data class Summary(
        val totals: Totals,
        val byDay: List<DayBucket>,
        val byModel: List<ModelBucket>,
        val firstDay: String?,
        val lastDay: String?,
        val byPurpose: List<PurposeBucket> = emptyList(),
        val hourly: List<HourBucket> = emptyList(),
    ) {
        /** 平均每次模型调用的 token；没有调用时返回 0。 */
        val averageTokensPerCall: Double
            get() = if (totals.calls <= 0) 0.0 else totals.totalTokens.toDouble() / totals.calls
    }

    private val lock = Any()

    /** 上次清理的日期：每个进程每天最多扫一次目录。 */
    private var lastPrunedDay: LocalDate? = null

    fun record(record: Record) {
        synchronized(lock) {
            runCatching {
                directory.mkdirs()
                val day = LocalDate.ofInstant(Instant.ofEpochMilli(record.ts), zone)
                // 2026-10-25 起按天分片：单月文件已经 3.5 MB / 2 万条，统计时要把整月都解析一遍。
                // 旧月文件仍然可读（见 [coverageOf]），只是不再往里写。
                val file = File(directory, "usage-$day.jsonl")
                pruneIfDue(day)
                val json = JSONObject()
                    .put("ts", record.ts)
                    .put("provider", record.provider.take(120))
                    .put("model", record.model.take(120))
                    .put("purpose", normalizePurpose(record.purpose).take(24))
                    .put("in", record.input ?: 0)
                    .put("out", record.output ?: 0)
                    .put("cached", record.cached ?: 0)
                    .put("reasoning", record.reasoning ?: 0)
                // 只有服务商真的给了总输入才写：缺省表示旧记录，读取时走兼容公式。
                if (record.prompt != null) json.put("prompt", record.prompt)
                val line = json.toString()
                FileOutputStream(file, true).use { stream ->
                    stream.write((line + "\n").toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    fun summarize(days: Int, nowMs: Long = clock()): Summary {
        val window = days.coerceIn(1, MAX_DAYS)
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val cutoffDay = today.minusDays((window - 1).toLong())
        val cutoffMs = cutoffDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val byDay = LinkedHashMap<String, Totals>()
        val byModel = LinkedHashMap<String, Totals>()
        val byPurpose = LinkedHashMap<String, Totals>()
        val byHour = LinkedHashMap<Long, Totals>()
        val hourlySince = nowMs - HOUR_WINDOW_HOURS * 60L * 60L * 1_000L
        val includeHourly = window <= HOURY_WINDOW_MAX_DAYS
        var totals = Totals()
        files(from = cutoffDay, to = today).forEach { file ->
            readLines(file) { json ->
                val ts = json.optLong("ts", 0)
                if (ts < cutoffMs) return@readLines
                val day = LocalDate.ofInstant(Instant.ofEpochMilli(ts), zone).toString()
                val input = json.optLong("in", 0).coerceAtLeast(0)
                val cached = json.optLong("cached", 0).coerceAtLeast(0)
                // 新记录带服务商总输入；旧记录没有该键，回退到旧公式的 `in + cached`。
                // 总输入为 0 但 cached 有值属于矛盾数据，也回退，避免命中率被算成 0。
                val prompt = (if (json.has("prompt")) json.optLong("prompt", 0).coerceAtLeast(0) else 0L)
                    .takeIf { it > 0 } ?: (input + cached)
                val bucket = Totals(
                    input = input,
                    output = json.optLong("out", 0).coerceAtLeast(0),
                    cached = cached,
                    reasoning = json.optLong("reasoning", 0).coerceAtLeast(0),
                    calls = 1,
                    prompt = prompt,
                )
                totals += bucket
                byDay[day] = (byDay[day] ?: Totals()) + bucket
                val model = json.optString("model").ifBlank { UNKNOWN_MODEL }
                byModel[model] = (byModel[model] ?: Totals()) + bucket
                val purpose = normalizePurpose(json.optString("purpose"))
                byPurpose[purpose] = (byPurpose[purpose] ?: Totals()) + bucket
                if (includeHourly && ts >= hourlySince) {
                    val hour = ts - (ts % HOUR_MS)
                    byHour[hour] = (byHour[hour] ?: Totals()) + bucket
                }
            }
        }
        val dayBuckets = byDay.entries
            .sortedBy { it.key }
            .map { DayBucket(it.key, it.value) }
        return Summary(
            totals = totals,
            byDay = fillMissingDays(dayBuckets, cutoffDay, today),
            byModel = byModel.entries.sortedByDescending { it.value.totalTokens }.map { ModelBucket(it.key, it.value) },
            firstDay = dayBuckets.firstOrNull()?.day,
            lastDay = dayBuckets.lastOrNull()?.day,
            byPurpose = byPurpose.entries
                .sortedByDescending { it.value.totalTokens }
                .map { PurposeBucket(it.key, it.value) },
            hourly = if (includeHourly) {
                fillMissingHours(
                    buckets = byHour.entries.sortedBy { it.key }.map { HourBucket(it.key, it.value) },
                    from = hourlySince - (hourlySince % HOUR_MS),
                    to = nowMs - (nowMs % HOUR_MS),
                )
            } else {
                emptyList()
            },
        )
    }

    /**
     * 最近 [limit] 条调用明细（新的在前）。
     *
     * 从**最新**的账本文件往前读，凑够条数就停：打开用量面板时不该为了十几行明细
     * 把整月账本解析一遍。同一天文件里的行按写入顺序追加，所以文件内倒序即时间倒序。
     *
     * 只回溯 [RECENT_LOOKBACK_DAYS] 天 —— 更早的记录在「按天」卡片里仍看得到。
     */
    fun recent(limit: Int = DEFAULT_RECENT, nowMs: Long = clock()): List<Entry> {
        val max = limit.coerceIn(1, MAX_RECENT)
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val out = ArrayList<Entry>(max)
        val ordered = files(from = today.minusDays(RECENT_LOOKBACK_DAYS.toLong()), to = today)
        for (file in ordered.asReversed()) {
            if (out.size >= max) break
            val batch = ArrayList<Entry>()
            readLines(file) { json -> batch += entryOf(json) }
            for (index in batch.indices.reversed()) {
                out += batch[index]
                if (out.size >= max) break
            }
        }
        return out
    }

    /**
     * 用途键归一化。
     *
     * 历史账本里压缩曾用长名 `compaction` 写入（其它入口用 `compact`），不归一化的话
     * 面板上同一件事会分成两条、其中一条还是未翻译的裸键。旧数据不能重写，就在读时统一。
     */
    private fun normalizePurpose(raw: String): String = when (val value = raw.trim()) {
        "" -> UNKNOWN_PURPOSE
        "compaction" -> "compact"
        else -> value
    }

    private fun entryOf(json: JSONObject) = Entry(
        ts = json.optLong("ts", 0),
        model = json.optString("model").ifBlank { UNKNOWN_MODEL },
        purpose = normalizePurpose(json.optString("purpose")),
        input = json.optLong("in", 0).coerceAtLeast(0),
        output = json.optLong("out", 0).coerceAtLeast(0),
        cached = json.optLong("cached", 0).coerceAtLeast(0),
        reasoning = json.optLong("reasoning", 0).coerceAtLeast(0),
    )

    /**
     * 与窗口相交的账本文件。
     *
     * 历史文件是**按月**（`usage-2026-10.jsonl`），2026-10-25 起改为**按天**
     * （`usage-2026-10-25.jsonl`）：单月文件已 3.5 MB / 2 万条，统计时要把整月解析一遍。
     * 这里按文件名解析出它覆盖的日期区间，只读与窗口相交的文件 —— 窗口外的旧文件直接跳过，
     * 短窗口（1~7 天）实际只读几天，不再为“最近 6 个月”付全量解析成本。
     */
    private fun files(from: LocalDate, to: LocalDate): List<File> = runCatching {
        directory.listFiles { file ->
            file.isFile && file.name.startsWith(FILE_PREFIX) && file.name.endsWith(".jsonl")
        }
            ?.mapNotNull { file -> coverageOf(file.name)?.let { span -> file to span } }
            ?.filter { (_, span) -> !span.endInclusive.isBefore(from) && !span.start.isAfter(to) }
            ?.sortedBy { it.second.start }
            ?.take(MAX_FILES)
            ?.map { it.first }
            .orEmpty()
    }.getOrDefault(emptyList())

    /** 文件名覆盖的日期区间：日文件是单日，旧月文件是整月；不认识的文件名返回 null。 */
    private fun coverageOf(name: String): ClosedRange<LocalDate>? = runCatching {
        val body = name.removePrefix(FILE_PREFIX).removeSuffix(".jsonl")
        when (body.length) {
            10 -> LocalDate.parse(body).let { it..it }
            7 -> LocalDate.parse("$body-01").let { first -> first..first.withDayOfMonth(first.lengthOfMonth()) }
            else -> null
        }
    }.getOrNull()

    /** 每个进程每天最多清一次：删掉早于保留期的文件（读窗口比保留期短，所以永远读不到被删的）。 */
    private fun pruneIfDue(today: LocalDate) {
        if (lastPrunedDay == today) return
        lastPrunedDay = today
        val cutoff = today.minusDays(KEEP_DAYS.toLong())
        runCatching {
            directory.listFiles()?.forEach { file ->
                val span = coverageOf(file.name) ?: return@forEach
                if (span.endInclusive.isBefore(cutoff)) file.delete()
            }
        }
    }

    private fun readLines(file: File, onLine: (JSONObject) -> Unit) {
        runCatching {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val json = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                runCatching { onLine(json) }
            }
        }
    }

    /** 补零到整点，界面上「今天 3 点没有调用」和「数据缺失」才不会混为一谈。 */
    private fun fillMissingHours(
        buckets: List<HourBucket>,
        from: Long,
        to: Long,
    ): List<HourBucket> {
        if (buckets.isEmpty()) return emptyList()
        val indexed = buckets.associateBy { it.hourStart }
        val out = ArrayList<HourBucket>()
        var cursor = from
        while (cursor <= to) {
            out += indexed[cursor] ?: HourBucket(cursor, Totals())
            cursor += HOUR_MS
        }
        return out
    }

    private fun fillMissingDays(days: List<DayBucket>, from: LocalDate, to: LocalDate): List<DayBucket> {
        if (days.isEmpty()) return emptyList()
        val indexed = days.associateBy { it.day }
        val out = ArrayList<DayBucket>()
        var cursor = from
        while (!cursor.isAfter(to)) {
            val key = cursor.toString()
            out += indexed[key] ?: DayBucket(key, Totals())
            cursor = cursor.plusDays(1)
        }
        return out
    }

    companion object {
        const val MAX_DAYS = 180
        /** 账本保留天数；比读取窗口更长，保证想看的数据还在。 */
        private const val KEEP_DAYS = 200
        /** 一个窗口最多读多少个文件：180 个日文件 + 若干旧月文件，留出余量。 */
        private const val MAX_FILES = 200
        private const val FILE_PREFIX = "usage-"
        private const val UNKNOWN_MODEL = "未知模型"
        private const val UNKNOWN_PURPOSE = "unknown"
        /** 「最近调用」回溯天数：够看出「刚花的那笔」，又不至于扫整月。 */
        private const val RECENT_LOOKBACK_DAYS = 7
        /** 面板默认展示多少条明细。 */
        private const val DEFAULT_RECENT = 12
        /** 一次最多返回多少条，防止调用方传入过大值把面板拖慢。 */
        private const val MAX_RECENT = 50
        private const val HOUR_MS = 60L * 60L * 1_000L
        private const val HOUR_WINDOW_HOURS = 24L
        private const val HOURY_WINDOW_MAX_DAYS = 2

        @Volatile
        private var instance: AgentUsageLedger? = null

        fun init(context: Context): AgentUsageLedger =
            AgentUsageLedger(File(context.filesDir, "usage")).also { instance = it }

        /** 未初始化时返回空账本（写入到临时目录，不崩溃），避免测试与非 App 进程调用出错。 */
        fun get(): AgentUsageLedger = instance ?: AgentUsageLedger(
            File(System.getProperty("java.io.tmpdir") ?: ".", "eta-usage"),
        ).also { instance = it }
    }
}
