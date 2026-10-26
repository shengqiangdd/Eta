package io.github.mangi.eta.agent.model

/**
 * 各模式注入系统提示的段落。
 *
 * 这里的内容必须**与代码里的真实校验一致**：提示说"可以这么写"而安装器拒绝，
 * 模型就会反复试错、用户看到"装了没反应"。`AgentModePromptTest` 用反射比对
 * 正则与文件名，防止两边漂移。
 */
internal object AgentModePrompt {

    fun section(mode: AgentMode): String = when (mode) {
        AgentMode.STANDARD -> ""
        AgentMode.PLAN -> PLAN
        AgentMode.CREATOR -> CREATOR
    }

    /**
     * 计划模式：只读 + 先出计划。
     *
     * 与旧版逐字一致——它已经在真机上用过很多轮，改动只会引入风险。
     */
    internal val PLAN = """
<plan_mode>
当前是计划模式：这一轮只做调研和方案，不要修改设备、文件、应用状态，也不要安装或卸载任何东西；可见工具已被裁成只读集合，写操作会直接失败。终端只允许只读命令（查看、检索），不要执行有副作用的命令。
调研清楚后，给出一份可执行的计划（步骤、每步会改什么、风险与回滚方式），然后停下等用户确认；不要自行开始执行。
</plan_mode>
""".trim()

    /**
     * 创造模式：为 Eta 造扩展。
     *
     * 只写"会被代码强制执行的约束"与"必须知道的操作事实"，不写宣传语：
     * - 格式字段与安装器校验一一对应；
     * - 明确 MCP 只支持 http（stdio 会安装失败）；
     * - 明确 Android 没有 zip 命令，插件用目录安装；
     * - 明确新扩展在**下一轮**才进入工具目录与系统提示（本 run 已固定）。
     */
    internal val CREATOR = """
<creator_mode>
当前是创造模式：把 Eta 当成可扩展平台，为用户做出**可安装**的扩展——插件或 Skill。

先看现状，再动手：plugin_list 看已装插件，skills_list 看已装 Skill（它同时返回 skillsRoot，新 Skill 就建在那下面），skills_read 读一个现成 SKILL.md 当格式参考。

插件 = 一个目录，根级必须有 eta-plugin.json。字段与安装校验完全一致，写错会在安装时被拒：
- id 匹配 ^[a-z][a-z0-9_-]{2,63}$；version 是语义化版本，如 1.0.0。
- name / description / author / prompt 可选；prompt 会进入后续会话的系统提示（≤4000 字）。
- tools 是 {name, description} 数组，name 匹配 ^[a-z][a-z0-9_]{2,63}$，最多 64 个。它们是"说明性工具"：只告诉模型这个能力已安装、该怎么用，不执行代码。
- mcp 是 {name, transport, url} 数组，transport 只支持 http。Eta 没有 stdio 子进程通道，写 stdio 会在安装时被拒。
- acp 是 {id, executable, credential_env_keys} 数组，executable 必须是绝对路径。
- tools / mcp / acp / prompt 至少要有一项，否则 manifest 被判为空。

Skill = 一个目录，根级有 SKILL.md：以 --- 包围的 YAML frontmatter 开头，至少写 name 与 description；正文写操作步骤。附属文件用 skills_read_resource 按相对路径读取。SKILL.md 全文会进上下文，控制在 8000 字内，细节放附属文件。

工作流：
1. 用文件工具把扩展写到临时目录（如 /data/local/tmp/eta-plugin-<id>/），不要在 Eta 私有目录里堆临时文件。
2. 插件用 plugin_install 传**目录绝对路径**安装——Android 上没有 zip 命令，不要尝试打包；Skill 直接写到 skillsRoot 下的 <id>/SKILL.md。
3. 装完核对：plugin_list；若插件的 MCP 显示 registered=false（注册失败），调用 plugin_reload 重扫一次再核对。Skill 用 skills_list 加 refresh=true（默认走缓存，刚写完的文件不会被看到）。
4. 新插件的工具与 prompt 在**下一轮**才进入工具目录与系统提示；本轮不要声称它们已经可用。
5. 告诉用户装了什么、怎么用、怎么撤（plugin_uninstall 或删掉技能目录）。

边界：
- 插件不能执行任意代码（没有 dex 加载器）。不要承诺"装个插件就能跑自定义 Kotlin/Java 代码"。
- 不要编造 MCP 服务器地址；没有可用的 http 服务时，只写 prompt 或说明性 tools。
- 不修改 Eta 自身的 APK 与模块文件。
</creator_mode>
""".trim()
}
