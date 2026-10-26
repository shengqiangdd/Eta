package io.github.mangi.eta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检查更新的结论归类。
 *
 * 第一条用例是真机验收抓到的原样缺陷：只有「来源比已装旧」时，
 * 标题却显示「全部已是最新」—— 用户会以为作者没更新，实际该换来源。
 */
class PluginUpdateSummaryTest {

    private fun status(
        id: String,
        state: PluginUpdateStatusUi.State,
        current: String = "1.2.0",
        remote: String = "1.3.0",
    ) = PluginUpdateStatusUi(
        pluginId = id,
        name = id,
        currentVersion = current,
        remoteVersion = remote,
        state = state,
    )

    @Test
    fun `older source alone is not up to date`() {
        val summary = PluginUpdateSummary.of(listOf(status("demo", PluginUpdateStatusUi.State.OLDER)))
        assertEquals(PluginUpdateSummary.Kind.OLDER, summary.kind)
        assertEquals(1, summary.older.size)
        assertTrue(summary.updates.isEmpty())
    }

    @Test
    fun `available update wins over failures`() {
        val summary = PluginUpdateSummary.of(
            listOf(
                status("a", PluginUpdateStatusUi.State.UPDATE_AVAILABLE),
                status("b", PluginUpdateStatusUi.State.FAILED),
            ),
        )
        assertEquals(PluginUpdateSummary.Kind.UPDATES, summary.kind)
        assertEquals(1, summary.failures.size)
    }

    @Test
    fun `failure alone is never reported as up to date`() {
        val summary = PluginUpdateSummary.of(listOf(status("a", PluginUpdateStatusUi.State.FAILED)))
        assertEquals(PluginUpdateSummary.Kind.FAILURES, summary.kind)
    }

    @Test
    fun `older is preferred over up to date when mixed`() {
        val summary = PluginUpdateSummary.of(
            listOf(
                status("a", PluginUpdateStatusUi.State.UP_TO_DATE, remote = "1.2.0"),
                status("b", PluginUpdateStatusUi.State.OLDER),
            ),
        )
        assertEquals(PluginUpdateSummary.Kind.OLDER, summary.kind)
        assertEquals(1, summary.older.size)
    }

    @Test
    fun `all up to date only when every plugin matches its source`() {
        val summary = PluginUpdateSummary.of(
            listOf(
                status("a", PluginUpdateStatusUi.State.UP_TO_DATE, remote = "1.2.0"),
                status("b", PluginUpdateStatusUi.State.UP_TO_DATE, current = "2.0", remote = "2.0.0"),
            ),
        )
        assertEquals(PluginUpdateSummary.Kind.UP_TO_DATE, summary.kind)
        assertEquals(2, summary.checked)
    }

    @Test
    fun `nothing to check is its own kind`() {
        assertEquals(PluginUpdateSummary.Kind.NOTHING, PluginUpdateSummary.of(emptyList()).kind)
    }

    @Test
    fun `counts reflect every status exactly once`() {
        val summary = PluginUpdateSummary.of(
            listOf(
                status("a", PluginUpdateStatusUi.State.UPDATE_AVAILABLE),
                status("b", PluginUpdateStatusUi.State.OLDER),
                status("c", PluginUpdateStatusUi.State.FAILED),
                status("d", PluginUpdateStatusUi.State.UP_TO_DATE, remote = "1.2.0"),
            ),
        )
        assertEquals(4, summary.checked)
        assertEquals(1, summary.updates.size)
        assertEquals(1, summary.failures.size)
        assertEquals(1, summary.older.size)
    }
}
