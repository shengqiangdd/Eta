package io.github.mangi.eta.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrollEvidenceContractTest {
    @Test
    fun `undefined accessibility delta is not movement or mismatch`() {
        val delta = ScrollEvidenceContract.normalizeAccessibilityDelta(-1)

        assertNull(delta)
        assertEquals(
            ScrollEvidence.UNVERIFIED,
            ScrollEvidenceContract.classify(
                direction = ScrollDirection.DOWN,
                delta = delta,
                movementSource = ScrollMovementSource.EVENT,
                atBoundary = false,
            ),
        )
    }

    @Test
    fun `classifies event tree boundary and mismatch evidence`() {
        assertEquals(
            ScrollEvidence.MOVED_BY_EVENT,
            classify(delta = 20),
        )
        assertEquals(
            ScrollEvidence.MOVED_BY_ANCHOR_MOTION,
            classify(delta = 20, source = ScrollMovementSource.ANCHOR_MOTION),
        )
        assertEquals(
            ScrollEvidence.AT_BOUNDARY,
            classify(delta = null, boundary = true),
        )
        assertEquals(
            ScrollEvidence.DIRECTION_MISMATCH,
            classify(delta = -20),
        )
    }

    @Test
    fun `partial anchor motion is content changed but direction unconfirmed`() {
        // 锚点动了、但幅度不一致（头部署开/收起同屏）：能确认内容变了，不能确认方向。
        assertEquals(
            ScrollEvidence.PARTIAL_ANCHOR_MOTION,
            classify(delta = null, partial = true),
        )
        // 有确定位移时仍是「确认移动」，不会被 partial 抢走。
        assertEquals(
            ScrollEvidence.MOVED_BY_ANCHOR_MOTION,
            classify(delta = 20, source = ScrollMovementSource.ANCHOR_MOTION, partial = true),
        )
        // 到底了就是到底了，partial 不掩盖边界结论。
        assertEquals(
            ScrollEvidence.AT_BOUNDARY,
            classify(delta = null, boundary = true, partial = true),
        )
    }

    private fun classify(
        delta: Int?,
        source: ScrollMovementSource = ScrollMovementSource.EVENT,
        boundary: Boolean = false,
        partial: Boolean = false,
    ): ScrollEvidence = ScrollEvidenceContract.classify(
        direction = ScrollDirection.DOWN,
        delta = delta,
        movementSource = source,
        atBoundary = boundary,
        partialAnchorMotion = partial,
    )
}
