package io.github.mangi.eta.agent.acp

/**
 * 权限裁定：什么时候拒绝代理的「执行命令」请求。
 *
 * ## 为什么改成这个形状
 *
 * 真机事故（PKX110，2026-10-27）：旧实现对 `session/request_permission` 用了
 * 一张「危险词」清单 `exec / shell / command / run / terminal / 执行 / 命令 …`，
 * 匹配的是**整个 toolCall 文本**。凡是让代理跑命令的请求，标题里几乎必然出现
 * 其中一个词 —— 于是**每一次**命令请求都被拒绝，codex 拿到 reject 就结束回合，
 * 用户看到「说了一句就停了」。
 *
 * 教训不是「清单不够长」，而是**按词匹配必然会同时误杀**：同一个词在
 * `git log --grep reboot` 里只是参数，在 `reboot` 里才是动作。
 *
 * ## 所以现在的判据
 *
 * 只看命令**开头**（允许 sudo/env 前缀），即「这条命令实际要执行什么」：
 *
 *     rm -rf /          → 拒绝（目标是根目录）
 *     rm -rf ./build    → 放行（目标是项目内目录）
 *     dd if=… of=/dev/… → 拒绝（写块设备）
 *     dd if=… of=./x.img→ 放行（写普通文件）
 *     echo remember mkfs→ 放行（只是提到）
 *
 * 需要多个条件时用「动作 + 对象」两段判断，任一不满足就放行。
 * 宁可漏一点点（真正的隔离靠容器权限），也不要重演「全拒」那次事故。
 *
 * 注意这**不是**安全边界：它只防手滑与模型误操作。
 * 别因为它存在就放松文件系统权限。
 */
internal fun shouldDenyAcpCommand(rawToolCallText: String): Boolean {
    if (rawToolCallText.isBlank()) return false
    // 抽命令头：取第一个像命令的行，去掉 sudo/env 之类的前缀。
    val command = extractCommandHead(rawToolCallText) ?: return false
    return isDestructive(command)
}

/**
 * 从 toolCall 文本里取出真正要执行的命令。
 *
 * toolCall 通常是「标题 + 换行 + 命令」或「执行命令：<cmd>」的形状，
 * 所以逐行找第一行非空内容，剥掉中文前缀与 sudo/env 前缀。
 */
private fun extractCommandHead(text: String): String? {
    for (rawLine in text.lineSequence()) {
        var line = rawLine.trim()
        if (line.isEmpty()) continue
        // 剥掉「执行命令：」「Run:」这类标签前缀（中英文冒号都可能有）。
        val colon = line.indexOfAny(charArrayOf('：', ':'))
        if (colon in 0..40) {
            val after = line.substring(colon + 1).trim()
            if (after.isNotEmpty()) line = after
        }
        // 剥掉 sudo / env / 变量赋值这类前缀，直到真正的命令名。
        val tokens = line.split(Regex("\\s+")).toMutableList()
        while (tokens.isNotEmpty()) {
            val head = tokens.first()
            if (head == "sudo" || head == "env" || head == "nohup" || head == "time") {
                tokens.removeAt(0)
                // 跳过 sudo 的选项（-u root 之类）
                while (tokens.isNotEmpty() && tokens.first().startsWith("-")) tokens.removeAt(0)
                continue
            }
            if (head.matches(Regex("[A-Za-z_][A-Za-z0-9_]*=.*"))) {
                tokens.removeAt(0)
                continue
            }
            break
        }
        if (tokens.isEmpty()) continue
        return line
    }
    return null
}

/** 判断一条命令是不是不可逆操作。见文件头的取舍说明。 */
private fun isDestructive(command: String): Boolean {
    val lower = command.lowercase()
    val tokens = lower.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return false
    val name = tokens.first().substringAfterLast('/')

    // 重定向写块设备（`echo x > /dev/block/sda`）与命令名无关，先单独看。
    if (Regex(">\\s*/dev/(block|sd|mmcblk|nvme)").containsMatchIn(lower)) return true

    return when {
        // mkfs 家族带后缀：mkfs、mkfs.ext4、mkfs.f2fs …
        name == "mkfs" || name.startsWith("mkfs.") -> true
        // 递归删除：只有目标就是根目录/家目录时才拦。
        name == "rm" -> isRecursiveForce(tokens) && deletesRootOrHome(tokens)
        // 抹盘
        name == "wipefs" || name == "mkswap" -> true
        // 写块设备：看 of= 指向哪
        name == "dd" -> tokens.any { it.startsWith("of=/dev/") }
        // 分区 / 刷机
        name == "fdisk" || name == "parted" || name == "fastboot" || name == "flash_image" -> true
        // 关机 / 重启
        name == "shutdown" || name == "reboot" || name == "poweroff" || name == "halt" -> true
        // 关掉安全设施
        name == "setenforce" -> tokens.getOrNull(1) == "0"
        else -> false
    }
}

/** `rm -rf` / `rm -fr` / `rm -r -f` 这类组合。 */
private fun isRecursiveForce(tokens: List<String>): Boolean {
    val flags = tokens.drop(1).filter { it.startsWith("-") && !it.startsWith("--") }
    val joined = flags.joinToString("")
    return flags.any { it == "-r" || it == "-R" || it == "-f" || it == "-rf" || it == "-fr" } &&
        (joined.contains('r') || joined.contains('R')) &&
        joined.contains('f')
}

/**
 * 删除目标里是否有「根目录」或「家目录」。
 *
 * 判定边界而不是子串：`/` 后面必须结束、空白、或跟通配符，
 * 否则 `/workspace/app/build` 会被当成 `/`（2026-11-12 修的误判）。
 */
private fun deletesRootOrHome(tokens: List<String>): Boolean {
    val targets = tokens.drop(1).filter { !it.startsWith("-") }
    return targets.any { target ->
        target == "/" ||
            target == "/*" ||
            target == "~" ||
            target == "~/" ||
            target == "~/*" ||
            target.matches(Regex("/\\*+"))
    }
}
