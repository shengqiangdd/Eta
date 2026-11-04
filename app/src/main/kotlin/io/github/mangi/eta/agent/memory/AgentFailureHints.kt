package io.github.mangi.eta.agent.memory

/**
 * 失败码 → 下次该怎么改。
 *
 * 为什么需要这张表：经验教训原本只记「哪个工具、什么错误码、错误首行」，
 * 读起来像一份**事故清单** —— 知道踩过坑，却不知道下一步该做什么。
 * 而同一个错误码对应的动作往往是固定的（`READ_REQUIRED` 永远是「先读再改」），
 * 所以可以在记录时就把改法一起写进去，让后续运行直接照着做。
 *
 * 表里的码来自本仓 72 份真实运行追踪里**实际出现过**的失败，
 * 不是照着文档猜的；每条改法都对应一个能立刻执行的动作，而不是「注意一下」。
 *
 * 纯函数、无 IO、无 Android 依赖：既给 [AgentLearningRecorder] 写记忆用，
 * 也给自审的 agent 域出建议用，两边口径必须一致。
 */
internal object AgentFailureHints {

    /**
     * @param tool 工具名，用于在没有专属错误码时给通用改法。
     * @param code 错误码；null 表示结果里没有 code。
     * @return 一句可直接执行的改法。永远不会返回空串 —— 空串会让调用方写出「下次怎么改：」这种假建议。
     */
    fun fixFor(tool: String, code: String?): String = when (code) {
        "READ_REQUIRED" ->
            "改文件前先用 read_file 读一遍；同一文件的多处修改用 edit_file 的 edits 一次提交，不要连发多次"
        "FILE_CHANGED" ->
            "文件在编辑期间被改过了：重新 read_file 取当前版本再改，不要原样重试同一段 old_text"
        "MATCH_NOT_FOUND" ->
            "old_text 必须与当前文件逐字一致（含缩进空白）：重新读取目标区域复制原文，不要凭记忆写"
        "AMBIGUOUS_MATCH" ->
            "old_text 在文件里出现多次：把上下文扩大到能唯一定位的一两行，不要加 replace_all 赌一把"
        "NO_CHANGES" -> "这次编辑没有产生差异：先确认是否已经改过，别重复提交"
        "FILE_TOO_LARGE" -> "精确编辑只支持 512 KiB 内的文本：缩小到目标函数分段替换，或整体重写"
        "INVALID_ARGUMENT" -> "参数不合法：核对该工具 schema 里的必填项与枚举值，不要猜字段名"
        "NOT_DIRECTORY" -> "路径不是目录：先用 list_directory / glob_files 确认真实路径"
        "FILE_NOT_FOUND" -> "文件不存在：先 glob_files 找真实路径，注意路径前缀与大小写"
        "NOT_REGULAR_FILE" -> "目标不是普通文件（目录 / 设备 / 管道）：换成读目录或改读其它文件"
        "STALE_CONTENT" ->
            "界面内容已变（刷新或跳转）：重新 observe_screen 再决策，不要照旧文字直接点"
        "NO_OBSERVATION" -> "节点动作要带上同一次 observe_screen 的 observation_id：重新观察，不要重放旧坐标"
        "STALE_OBSERVATION" -> "观察快照已过期：重新 observe_screen 拿新的 observation_id 再点"
        "MAIN_INPUT_ACTIVE" -> "主屏正在输入：等用户停手再点，不要抢输入法；必要时请用户自己完成这一步"
        "APP_ALREADY_RUNNING" -> "应用已在别的屏运行：先确认它在哪块屏；需要搬过来才传 force=true"
        "ALREADY_ON_TARGET_DISPLAY" -> "应用已在这块虚拟屏上：不要再启动，直接 observe_screen"
        "VIRTUAL_DISPLAY_NOT_FOUND" -> "虚拟屏已被释放：先 virtual_display_list 看还剩哪几块，再重建"
        "SEARCH_CHALLENGE" -> "搜索要求人工验证：改用 fetch_url 直接读已知 URL，或换关键词，别重试同一查询"
        "ACP_PROTOCOL_ERROR" -> "外部代理协议或模型报错：多半是模型名 / 凭据不匹配，换代理或自己动手"
        "ACP_DELEGATION_DISABLED" -> "用户已关闭委派：自己动手做，不要再尝试委派"
        "TOOL_NOT_AVAILABLE" -> "当前环境没有这个工具 / 命令：先确认它在哪个 environment 下存在，别在错的一侧重试"
        "INVALID_QUERY" -> "搜索表达式不合法（正则转义或长度）：先缩小成字面量再跑"
        else -> when (tool) {
            "terminal" -> "看返回里的 stderr 与退出码；先确认命令在本环境存在（command -v），必要时换 environment 或改用专用工具"
            "edit_file", "edit_files" ->
                "按「读 → 改 → 核对」走：先 read_file，再一次提交所有编辑，最后 read_file 核对"
            "memory_write" -> "写记忆失败多为内容超长或格式不对：拆小段落，或先 memory_get 看结构与 revision"
            "read_file" -> "读之前先确认路径真实存在且是普通文件；大文件用 code_outline 先看结构再按行号读"
            "observe_screen" -> "先确认屏幕上有可读的 UI 树；纯图像界面才需要 include_screenshot=true"
            else -> "按返回里的 code / message 定位原因，补齐前置条件再重试；同一调用不要原样重放"
        }
    }

    /**
     * 这条改法是不是「专属」的（而不是按工具给的通用兜底）。
     *
     * 用来区分「这个坑有明确解法」与「只能说点通用的」——
     * 后者不该被当成一条值得记进长期经验的结论。
     */
    fun hasSpecificFix(code: String?): Boolean = when (code) {
        null -> false
        "READ_REQUIRED", "FILE_CHANGED", "MATCH_NOT_FOUND", "AMBIGUOUS_MATCH", "NO_CHANGES",
        "FILE_TOO_LARGE", "INVALID_ARGUMENT", "NOT_DIRECTORY", "FILE_NOT_FOUND", "NOT_REGULAR_FILE",
        "STALE_CONTENT", "NO_OBSERVATION", "STALE_OBSERVATION", "MAIN_INPUT_ACTIVE",
        "APP_ALREADY_RUNNING", "ALREADY_ON_TARGET_DISPLAY", "VIRTUAL_DISPLAY_NOT_FOUND",
        "SEARCH_CHALLENGE", "ACP_PROTOCOL_ERROR", "ACP_DELEGATION_DISABLED",
        "TOOL_NOT_AVAILABLE", "INVALID_QUERY",
        -> true
        else -> false
    }
}
