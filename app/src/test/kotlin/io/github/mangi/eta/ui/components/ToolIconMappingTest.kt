package io.github.mangi.eta.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Build
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.toolDisplayNameResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 纯 JVM 的图标 / 显示名映射检查。
 *
 * 为什么需要它：真正守「全目录每个工具都有专属图标」的是 `ToolCatalogUiTest`，但那是
 * **Robolectric 测试，本机（Linux aarch64）跑不了**（同一限制见 `tools/gate.sh` 的注释）。
 * 结果是「新工具忘了配图标或显示名」这类改动在本机门禁里**没有任何东西会红** ——
 * §32.39.10 加图标时就是这种情况，只能靠读断言、没有测试可依。
 *
 * 这里只钉住具体几个工具的映射，不替代 `ToolCatalogUiTest` 的全目录断言，
 * 但至少让「删掉某条映射」这种事在本机能红。
 */
class ToolIconMappingTest {
    @Test
    fun selfAuditHasItsOwnIconInsteadOfTheGenericFallback() {
        assertEquals(Icons.AutoMirrored.Rounded.ManageSearch, iconForTool("eta_self_audit"))
        assertNotEquals(
            "落到兜底的 Build 图标说明映射没生效",
            Icons.Rounded.Build,
            iconForTool("eta_self_audit"),
        )
    }

    @Test
    fun selfAuditHasADisplayName() {
        assertEquals(R.string.tool_eta_self_audit, toolDisplayNameResource("eta_self_audit"))
    }
}
