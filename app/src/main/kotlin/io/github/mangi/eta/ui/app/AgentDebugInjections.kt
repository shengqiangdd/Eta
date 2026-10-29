package io.github.mangi.eta.ui.app

/**
 * 真机验证用的**调试注入点**，只在 debug 构建里被赋值。
 *
 * 背景：`AgentRuntimeService.supersedeSameConversationRuns` 那条「同一会话新 run 顶掉旧 run」
 * 的取消路径，正常界面走不到（运行中的编辑/发送/插话都被客户端状态挡住，见 docs §21.3）——
 * 只有「客户端以为没在跑、服务端还在跑」的失配才会触发。为了在真机上把这条路径跑一遍，
 * 需要一个只改本地状态、不动服务端 run 的入口。
 *
 * 谁来赋值：`AgentAppState.init` 里的 `BuildConfig.DEBUG` 分支。
 * 谁来触发：`io.github.mangi.eta.debug.DebugRunStateReceiver`（debug 源集，release 包里没有这个类）。
 * release 构建里 `BuildConfig.DEBUG` 是编译期常量 false，注册分支会被裁掉，这个对象永远为 null。
 */
object AgentDebugInjections {

    /** 参数是会话 id；null 表示当前选中的会话。 */
    @Volatile
    var forgetLocalRunState: ((String?) -> Unit)? = null
}
