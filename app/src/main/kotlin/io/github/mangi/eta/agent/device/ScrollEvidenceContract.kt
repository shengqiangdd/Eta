package io.github.mangi.eta.agent.device

internal enum class ScrollEvidence {
    MOVED_BY_EVENT,
    MOVED_BY_ANCHOR_MOTION,

    /**
     * 确实有锚点动了，但位移幅度彼此不一致（例如列表滚动同时伴着头部署开/收起），
     * 所以只能确认「内容变了」，不能确认「朝请求方向移了多少」。
     */
    PARTIAL_ANCHOR_MOTION,
    DIRECTION_MISMATCH,
    AT_BOUNDARY,
    UNVERIFIED,
}

internal enum class ScrollMovementSource {
    EVENT,
    ANCHOR_MOTION,
}

internal object ScrollEvidenceContract {
    fun normalizeAccessibilityDelta(value: Int): Int? =
        value.takeUnless { it == ACCESSIBILITY_UNDEFINED }

    fun classify(
        direction: ScrollDirection,
        delta: Int?,
        movementSource: ScrollMovementSource?,
        atBoundary: Boolean,
        partialAnchorMotion: Boolean = false,
    ): ScrollEvidence {
        if (
            delta != null &&
            delta != 0 &&
            delta.sign() != direction.scrollDeltaSign
        ) {
            return ScrollEvidence.DIRECTION_MISMATCH
        }
        if (delta != null && delta != 0) {
            return when (movementSource) {
                ScrollMovementSource.EVENT -> ScrollEvidence.MOVED_BY_EVENT
                ScrollMovementSource.ANCHOR_MOTION -> ScrollEvidence.MOVED_BY_ANCHOR_MOTION
                null -> ScrollEvidence.UNVERIFIED
            }
        }
        if (atBoundary) return ScrollEvidence.AT_BOUNDARY
        if (partialAnchorMotion) return ScrollEvidence.PARTIAL_ANCHOR_MOTION
        return ScrollEvidence.UNVERIFIED
    }

    private fun Int.sign(): Int = when {
        this > 0 -> 1
        this < 0 -> -1
        else -> 0
    }

    private const val ACCESSIBILITY_UNDEFINED = -1
}
