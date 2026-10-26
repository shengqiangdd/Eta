package io.github.mangi.eta.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlindTapSafetyPolicyTest {
    private fun box(left: Int, top: Int, right: Int, bottom: Int) =
        BlindTapSafetyPolicy.Box(left, top, right, bottom)

    private fun candidate(index: Int, left: Int, top: Int, right: Int, bottom: Int, interactive: Boolean) =
        BlindTapSafetyPolicy.Candidate(index, box(left, top, right, bottom), interactive)

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
}
