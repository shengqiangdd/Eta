package io.github.mangi.eta.agent.overlay

/**
 * 运行浮层两个开关的兼容规则。
 *
 * 旧版只有一个「显示运行浮层」开关，它同时控制思考胶囊与屏幕边缘流光：关掉等于两个都不画。
 * 新增独立的「显示边缘流光」后，如果用户从未写过这个键（旧版升级或首次安装），
 * 就不能让流光因为默认值 true 又冒出来——旧版明确关掉过，就必须保持全隐藏。
 * 新键一旦被用户显式写过，就以它为准。
 */
internal object AgentOverlayPreferencePolicy {
    fun glowEnabled(
        glowConfigured: Boolean,
        glowValue: Boolean,
        capsuleConfigured: Boolean,
        capsuleValue: Boolean,
    ): Boolean = when {
        glowConfigured -> glowValue
        capsuleConfigured -> capsuleValue
        else -> true
    }
}
