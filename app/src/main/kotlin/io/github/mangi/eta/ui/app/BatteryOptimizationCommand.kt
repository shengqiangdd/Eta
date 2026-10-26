package io.github.mangi.eta.ui.app

/**
 * OPlus 系机型的「后台运行」授权跳转命令构造。
 *
 * 这条命令由 root 执行，目标 Activity 是厂商电源管理页。它的 `--es pkgName` 参数
 * 决定权限写进哪个包的白名单 —— 一旦写死包名，其他 applicationId（如 canary 边车包）
 * 点进去授权的就是另一个包，界面上的状态永远显示未授权。
 *
 * 因此包名一律由调用方传入，这里只负责拼装与转义。
 */
internal object BatteryOptimizationCommand {

    private const val COMPONENT =
        "com.oplus.battery/com.oplus.powermanager.fuelgaue.PowerControlActivity"

    /** 构造跳转命令；[packageName] 必须来自 `Context.packageName`，不能是常量。 */
    fun powerControlCommand(packageName: String): String {
        require(packageName.isNotBlank()) { "包名不能为空" }
        // 包名只允许字母数字下划线和点，出现其他字符说明调用方传错了，拒绝拼进 shell 命令。
        require(packageName.all { it.isLetterOrDigit() || it == '.' || it == '_' }) {
            "包名包含非法字符：$packageName"
        }
        return "am start --user current -n $COMPONENT " +
            "--es title Eta --es pkgName $packageName --es drainType APP"
    }
}
