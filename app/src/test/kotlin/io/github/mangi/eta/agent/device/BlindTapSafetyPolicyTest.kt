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

    @Test
    fun `blocked node that is itself interactive is the suggestion`() {
        val blocked = candidate(11, 300, 600, 620, 690, interactive = true, toggles = true)
        val suggestion = BlindTapSafetyPolicy.suggestTarget(blocked, listOf(blocked))
        assertEquals(11, suggestion?.index)
        assertEquals(BlindTapSafetyPolicy.Suggestion.Kind.BLOCKED_NODE, suggestion?.kind)
        assertEquals("blocked_node", suggestion?.kindName)
        assertTrue(suggestion!!.toggles)
    }

    @Test
    fun `non interactive container suggests the clickable row inside it`() {
        // 上一版提示“请改点 blocked 或它的文本子节点”，而 blocked 本身点不动：
        // 模型会再去一次，然后被同一个判据拦住。suggestion 直接给出可执行的节点。
        val blocked = candidate(4, 0, 317, 720, 1280, interactive = false)
        val row = candidate(9, 0, 640, 720, 871, interactive = true)
        val suggestion = BlindTapSafetyPolicy.suggestTarget(
            blocked,
            listOf(
                blocked,
                row,
                // 行内小图标：面积更小 → 不是首选（整行比小图标好点）
                candidate(10, 600, 700, 660, 760, interactive = true),
            ),
        )
        assertEquals(9, suggestion?.index)
        assertEquals("child_control", suggestion?.kindName)
    }

    @Test
    fun `suggestion prefers the largest child control`() {
        val blocked = candidate(4, 0, 300, 720, 900, interactive = false)
        val suggestion = BlindTapSafetyPolicy.suggestTarget(
            blocked,
            listOf(
                blocked,
                candidate(20, 0, 320, 720, 520, interactive = true),
                candidate(21, 0, 540, 720, 720, interactive = true),
            ),
        )
        assertEquals(20, suggestion?.index)
    }

    @Test
    fun `no interactive child means no suggestion instead of a guess`() {
        // 纯文本标签：范围内没有任何可交互节点 → 不编 index，提示改为“先 observe_screen”。
        val blocked = candidate(30, 0, 300, 720, 400, interactive = false)
        val suggestion = BlindTapSafetyPolicy.suggestTarget(
            blocked,
            listOf(
                blocked,
                candidate(31, 0, 310, 300, 380, interactive = false),
                // 中心在 blocked 之外的控件不算范围内
                candidate(32, 0, 600, 720, 700, interactive = true),
            ),
        )
        assertNull(suggestion)
    }

    @Test
    fun `ancestor container is never mistaken for a child control`() {
        // 面积不小于目标的（祖先）不算子控件，否则会建议去点一整屏根容器。
        val blocked = candidate(40, 100, 100, 400, 400, interactive = false)
        val suggestion = BlindTapSafetyPolicy.suggestTarget(
            blocked,
            listOf(
                blocked,
                candidate(41, 0, 0, 720, 1280, interactive = true),
            ),
        )
        assertNull(suggestion)
    }
}
