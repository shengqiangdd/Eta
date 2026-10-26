package io.github.mangi.eta.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlindTapSafetyPolicyTest {
    private fun box(left: Int, top: Int, right: Int, bottom: Int) =
        BlindTapSafetyPolicy.Box(left, top, right, bottom)

    private fun candidate(
        index: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        interactive: Boolean,
        toggles: Boolean = false,
    ) = BlindTapSafetyPolicy.Candidate(index, box(left, top, right, bottom), interactive, toggles)

    @Test
    fun `root container whose center lands on a switch is rejected`() {
        // 真实事故：设置首页根 ScrollView [0,0][720,1280]，中心 (360,640)；
        // 那个点上压着快捷开关（飞行模式就在这一行），盲点直接拨了开关。
        val hit = BlindTapSafetyPolicy.centerHitOnOtherNode(
            targetIndex = 0,
            target = box(0, 0, 720, 1280),
            candidates = listOf(
                candidate(0, 0, 0, 720, 1280, interactive = false),
                candidate(4, 0, 317, 720, 1280, interactive = false),
                candidate(11, 300, 600, 620, 690, interactive = true),
            ),
        )
        assertEquals(11, hit?.index)
        assertTrue(hit!!.interactive)
    }

    @Test
    fun `title bar center with nothing under it stays allowed`() {
        // 顶部标题栏 [0,0][720,316] 的中心 (360,158)：下面没有别的节点 → 盲点安全，不拦。
        // 探针 STEP2 就是点这个节点，必须继续放行。
        val hit = BlindTapSafetyPolicy.centerHitOnOtherNode(
            targetIndex = 1,
            target = box(0, 0, 720, 316),
            candidates = listOf(
                candidate(1, 0, 0, 720, 316, interactive = false),
                candidate(2, 0, 213, 720, 317, interactive = true),
            ),
        )
        assertNull(hit)
    }

    @Test
    fun `only the target itself means nothing to collide with`() {
        assertNull(
            BlindTapSafetyPolicy.centerHitOnOtherNode(
                targetIndex = 5,
                target = box(0, 0, 100, 100),
                candidates = listOf(candidate(5, 0, 0, 100, 100, interactive = false)),
            ),
        )
    }

    @Test
    fun `empty bounds never blocks the action`() {
        assertNull(
            BlindTapSafetyPolicy.centerHitOnOtherNode(
                targetIndex = 0,
                target = box(0, 0, 0, 0),
                candidates = listOf(candidate(3, 0, 0, 720, 1280, interactive = true)),
            ),
        )
    }

    @Test
    fun `ancestor full screen container never blocks the blind tap`() {
        // 真实数据（720x1280 设置首页）：标题栏 [0,0][720,316] 中心 (360,158)，
        // 全屏根 ScrollView 覆盖这个点 —— 但它是祖先、不是被点到的控件，不该拦。
        // 少了这条，探针 STEP2 会永远「跳过」（第一版就是这个毛病）。
        val hit = BlindTapSafetyPolicy.centerHitOnOtherNode(
            targetIndex = 1,
            target = box(0, 0, 720, 316),
            candidates = listOf(
                candidate(0, 0, 0, 720, 1280, interactive = false),
                candidate(1, 0, 0, 720, 316, interactive = false),
                candidate(2, 0, 213, 720, 317, interactive = true),
            ),
        )
        assertNull(hit)
    }

    @Test
    fun `tightest node wins so the hint points at the real hit target`() {
        // 大容器与其中的小按钮同时命中：报面积最小的那个，模型才能一步点对。
        val hit = BlindTapSafetyPolicy.centerHitOnOtherNode(
            targetIndex = 0,
            target = box(0, 0, 720, 1280),
            candidates = listOf(
                candidate(4, 0, 317, 720, 1280, interactive = false),
                candidate(7, 200, 500, 520, 700, interactive = false),
                candidate(9, 340, 580, 380, 660, interactive = true),
            ),
        )
        assertEquals(9, hit?.index)
    }

    @Test
    fun `toggle inside the target area is blocked even when the center misses it`() {
        // 真实形态：整行可点、行内是 Switch；目标节点中心压在行内空白处，
        // 第一层（中心命中）不触发，但一个盲点就拨动了开关。
        val hit = BlindTapSafetyPolicy.toggleInsideTarget(
            targetIndex = 9,
            target = box(0, 640, 720, 871),
            targetToggles = false,
            candidates = listOf(
                candidate(9, 0, 640, 720, 871, interactive = true),
                candidate(11, 475, 717, 608, 801, interactive = true, toggles = true),
            ),
        )
        assertEquals(11, hit?.index)
    }

    @Test
    fun `the toggle itself is always allowed`() {
        assertNull(
            BlindTapSafetyPolicy.toggleInsideTarget(
                targetIndex = 11,
                target = box(475, 717, 608, 801),
                targetToggles = true,
                candidates = listOf(candidate(11, 475, 717, 608, 801, true, toggles = true)),
            ),
        )
    }

    @Test
    fun `toggle outside the target area is not a reason to block`() {
        assertNull(
            BlindTapSafetyPolicy.toggleInsideTarget(
                targetIndex = 1,
                target = box(0, 0, 720, 316),
                targetToggles = false,
                candidates = listOf(
                    candidate(1, 0, 0, 720, 316, interactive = false),
                    candidate(11, 475, 717, 608, 801, interactive = true, toggles = true),
                ),
            ),
        )
    }

    @Test
    fun `a plain clickable row without toggles never blocks`() {
        // 只导航的行（可点、没有开关）：盲点后果可逆，不该拦。
        assertNull(
            BlindTapSafetyPolicy.toggleInsideTarget(
                targetIndex = 4,
                target = box(0, 317, 720, 1280),
                targetToggles = false,
                candidates = listOf(
                    candidate(4, 0, 317, 720, 1280, interactive = false),
                    candidate(6, 0, 341, 720, 608, interactive = true),
                ),
            ),
        )
    }

    @Test
    fun `a two node tree is not enough evidence to blind tap`() {
        // 真机：应用刚启动时无障碍只回 2 个容器节点；此时「中心点上没别的东西」
        // 只说明「还没看到」，不能当成落点是空的。
        assertFalse(
            BlindTapSafetyPolicy.hasEnoughEvidence(
                listOf(
                    candidate(0, 0, 0, 720, 1280, interactive = false),
                    candidate(1, 0, 0, 720, 400, interactive = false),
                ),
            ),
        )
    }

    @Test
    fun `a normal settings home tree is enough evidence`() {
        assertTrue(
            BlindTapSafetyPolicy.hasEnoughEvidence(
                listOf(
                    candidate(0, 0, 0, 720, 1280, false),
                    candidate(4, 0, 317, 720, 1280, false),
                    candidate(9, 0, 640, 720, 871, true),
                    candidate(11, 475, 717, 608, 801, true, toggles = true),
                ),
            ),
        )
    }
}
