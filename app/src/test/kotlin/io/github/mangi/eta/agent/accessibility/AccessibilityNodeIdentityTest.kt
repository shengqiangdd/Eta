package io.github.mangi.eta.agent.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityNodeIdentityTest {
    @Test
    fun `same view id with recycled row text is not the same node`() {
        val original = identity(text = "第一行")
        val recycled = identity(text = "插入后的新行")

        assertFalse(original.matches(recycled))
    }

    @Test
    fun `unique id cannot hide changed action semantics`() {
        val original = identity(uniqueId = "virtual-42", text = "旧状态")
        val refreshed = identity(uniqueId = "virtual-42", text = "新状态")

        assertFalse(original.matches(refreshed))
        assertTrue(original.matches(identity(uniqueId = "virtual-42", text = "旧状态")))
    }

    @Test
    fun `blank semantic node is weak when window content changes`() {
        assertFalse(identity(text = "", description = "").strong)
        assertTrue(identity(text = "按钮").strong)
    }

    @Test
    fun `identity gaining a unique id is treated as replacement`() {
        assertFalse(identity(uniqueId = "").matches(identity(uniqueId = "virtual-42")))
    }

    @Test
    fun `truncated snapshot cannot promote semantic identity to globally unique`() {
        assertFalse(
            AccessibilityIdentityFreshnessPolicy.canBypassContentChange(
                hasUniqueId = false,
                snapshotTruncated = true,
                identityMatchCount = 1,
            ),
        )
        assertTrue(
            AccessibilityIdentityFreshnessPolicy.canBypassContentChange(
                hasUniqueId = true,
                snapshotTruncated = true,
                identityMatchCount = 1,
            ),
        )
    }

    @Test
    fun `complete snapshot requires exactly one semantic identity match`() {
        assertTrue(
            AccessibilityIdentityFreshnessPolicy.canBypassContentChange(
                hasUniqueId = false,
                snapshotTruncated = false,
                identityMatchCount = 1,
            ),
        )
        assertFalse(
            AccessibilityIdentityFreshnessPolicy.canBypassContentChange(
                hasUniqueId = false,
                snapshotTruncated = false,
                identityMatchCount = 2,
            ),
        )
    }

    @Test
    fun `input field can act even when window content changes`() {
        assertTrue(
            AccessibilityIdentityFreshnessPolicy.canBypassContentChangeForInputField(
                structuralMatchCount = 1,
                refreshedStructureUnchanged = true,
            ),
        )
    }

    @Test
    fun `input field needs a unique structure and an unchanged rect`() {
        // 同矩形/同类名的候选多于一个：不再当作"就是它"。
        assertFalse(
            AccessibilityIdentityFreshnessPolicy.canBypassContentChangeForInputField(
                structuralMatchCount = 2,
                refreshedStructureUnchanged = true,
            ),
        )
        // 刷新后控件换了位置：宁可报快照过期，也不能写进别的框。
        assertFalse(
            AccessibilityIdentityFreshnessPolicy.canBypassContentChangeForInputField(
                structuralMatchCount = 1,
                refreshedStructureUnchanged = false,
            ),
        )
    }

    private fun identity(
        uniqueId: String = "",
        text: String = "条目",
        description: String = "",
    ): AccessibilityNodeIdentity = AccessibilityNodeIdentity(
        uniqueId = uniqueId,
        windowId = 7,
        packageName = "example.app",
        className = "android.widget.TextView",
        viewId = "example.app:id/row",
        text = text,
        description = description,
        password = false,
    )
}
