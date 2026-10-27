package io.github.mangi.eta.agent.acp

/**
 * 权限裁定：什么时候拒绝代理的「执行命令」请求。
 *
 * 真机教训（PKX110，2026-10-27）：旧实现对 `session/request_permission` 用了
 * 一张「危险词」清单 `write / edit / exec / shell / command / run / terminal / 执行 / 命令 …`，
 * 而它匹配的是**整个 toolCall 文本**（含标题与参数）。凡是让代理跑命令的请求，
 * 标题里几乎必然出现 "run"/"command"/"shell" 之一 —— 于是 **每一次**命令请求都被拒绝，
 * codex 拿到 reject 就结束回合：`stop_reason=cancelled`，用户看到「说了一句就停了」。
 *
 * 现在的判据反过来：**默认放行**，只拒绝明确的破坏性操作。
 * 委派本来就是让代理改代码、跑测试，全部拒绝等于这个工具不存在；
 * 而真正的不可逆操作（抹盘、刷机、关机、删根目录）用精确模式挡住。
 */
private val DENIED_COMMAND_PATTERNS: List<List<String>> = listOf(
    // 递归删除根/家目录/整块盘
    listOf("rm -rf /"),
    listOf("rm -fr /"),
    listOf("rm -rf ~"),
    listOf("rm -rf /*"),
    // 抹盘 / 覆写块设备
    listOf("mkfs"),
    listOf("dd if="),
    listOf("dd of=/dev/"),
    listOf("> /dev/block"),
    listOf("> /dev/sd"),
    // 分区 / 刷机
    listOf("fdisk"),
    listOf("parted "),
    listOf("fastboot "),
    listOf("flash "),
    // 关机 / 重启（会把用户正在做的事直接打断）
    listOf("shutdown"),
    listOf("reboot"),
    // 关掉用户自己的安全设施
    listOf("setenforce 0"),
)

/**
 * @param rawToolCallText 权限请求里 `toolCall` 抽取出的全部可读文本（标题 + 参数）。
 * @return true 表示拒绝（不可逆风险），false 表示放行。
 */
internal fun shouldDenyAcpCommand(rawToolCallText: String): Boolean {
    if (rawToolCallText.isBlank()) return false
    val lower = rawToolCallText.lowercase()
    return DENIED_COMMAND_PATTERNS.any { group -> group.all { lower.contains(it) } }
}
