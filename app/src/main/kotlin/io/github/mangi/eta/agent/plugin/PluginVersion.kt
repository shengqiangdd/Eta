package io.github.mangi.eta.agent.plugin

/**
 * 插件版本号比较。
 *
 * 为什么需要单独一个：检查更新要回答的是「远端这个版本比已装的新吗」。
 * 直接比字符串会把 `1.10.0` 判成比 `1.9.0` **旧**；而「只要不同就算有更新」
 * 又会把来源被回退（远端比本地旧）说成可更新，点下去就是降级。
 *
 * 规则按主流语义版本约定：
 * 1. 去掉前导 `v`、忽略 `+` 之后的构建元数据；
 * 2. 主版本按 `.` 分段、逐段按**数值**比较，缺位补 0（`1.3` == `1.3.0`）；
 * 3. 有预发布后缀的比同号正式版旧（`1.0.0-rc1` < `1.0.0`），两个都有则按字符串比；
 * 4. **解析不了就不猜**：返回 null，由调用方明确说"版本格式无法比较"，
 *    而不是硬说成"有更新"或"已是最新"。
 */
internal object PluginVersion {

    enum class Relation {
        /** 远端比已装新：可更新。 */
        NEWER,

        /** 完全一致（含只有构建元数据不同的情况）。 */
        SAME,

        /** 远端比已装旧：来源被回退了，界面要如实说，不能当成"已是最新"。 */
        OLDER,

        /** 两边都能读出来但**没法排序**（例如 `1.2.0-beta` 与 `1.2.0-alpha` 以外的怪格式）。 */
        DIFFERENT,
    }

    fun relate(remote: String, current: String): Relation {
        val byNumber = compare(remote, current)
        if (byNumber != null) {
            return when {
                byNumber > 0 -> Relation.NEWER
                byNumber < 0 -> Relation.OLDER
                else -> Relation.SAME
            }
        }
        return if (normalize(remote) == normalize(current)) Relation.SAME else Relation.DIFFERENT
    }

    /** >0 表示 remote 更新，<0 表示更旧，0 表示相同；null 表示无法比较。 */
    fun compare(remote: String, current: String): Int? {
        val left = parse(remote) ?: return null
        val right = parse(current) ?: return null
        val core = compareNumbers(left.numbers, right.numbers)
        if (core != 0) return core
        val a = left.pre
        val b = right.pre
        return when {
            a == null && b == null -> 0
            // 有后缀的是预发布：比同号正式版旧。
            a == null -> 1
            b == null -> -1
            a == b -> 0
            else -> if (a > b) 1 else -1
        }
    }

    /** 是否值得让用户点「更新」：更新的，或格式不同到需要人来判断的。 */
    fun isUpdateAvailable(remote: String, current: String): Boolean =
        relate(remote, current).let { it == Relation.NEWER || it == Relation.DIFFERENT }

    private data class Parsed(val numbers: List<Int>, val pre: String?)

    private fun normalize(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("v") || text.startsWith("V")) text = text.substring(1)
        return text.substringBefore('+')
    }

    private fun parse(raw: String): Parsed? {
        val text = normalize(raw)
        if (text.isEmpty()) return null
        val core = text.substringBefore('-')
        val pre = text.substringAfter('-', missingDelimiterValue = "").takeIf { it.isNotBlank() }
        if (core.isEmpty()) return null
        val parts = core.split('.')
        val numbers = ArrayList<Int>(parts.size)
        for (part in parts) {
            // 长度上限挡住 "999999999999999999" 这种溢出：解析不了就是解析不了，不能静默截断。
            if (part.isEmpty() || part.length > 9 || !part.all { it in '0'..'9' }) return null
            numbers.add(part.toInt())
        }
        return Parsed(numbers, pre)
    }

    private fun compareNumbers(left: List<Int>, right: List<Int>): Int {
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) return if (a > b) 1 else -1
        }
        return 0
    }
}
