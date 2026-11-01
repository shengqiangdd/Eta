#!/bin/sh
# 完整门禁：单测 / lint / 打包。
#
# 为什么要有这个脚本：把 testDebugUnitTest 和 :app:lintDebug 写进同一条 Gradle 命令时，
# 测试失败后 Gradle 不再调度 lint（日志里连 :app:lintDebug 都没有），而已经进队列的
# packageDebug / assembleDebug 照跑。事后只看到 BUILD FAILED 和一个变新的 APK，
# 很容易把「lint 根本没跑」读成「lint 通过」——2026-10-24 就踩了一次。
# 所以这里每个阶段单独起一次 Gradle：一个阶段红，后面的阶段照样跑完并各自给结论。
#
# 用法（必须在 Linux 环境：JDK / SDK 在 /workspace 下）：
#   sh tools/gate.sh                    # 全部三个阶段
#   STAGES=test sh tools/gate.sh        # 只跑单测
#   STAGES=lint,assemble sh tools/gate.sh
#   STAGES=test-all sh tools/gate.sh    # 全量单测（不只终端包），很慢，含跑不了的 Robolectric 用例
#   STAGES=test-wide sh tools/gate.sh   # 本机能跑的全部用例（名单见 tools/wide-tests.txt）
# 退出码：0 = 全绿；1 = 有阶段失败。
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT" || exit 2

if [ ! -d /workspace ]; then
  echo "请在 Linux 环境运行（Android 侧没有 gradle / JDK）"
  exit 2
fi

export ANDROID_HOME=${ANDROID_HOME:-/workspace/.tooling/android/sdk}
export ANDROID_SDK_ROOT=$ANDROID_HOME
# JDK 固定：外部环境把 JAVA_HOME 指到 25 时，AGP 的 lint 会异常慢
# （2026-10-30 实测：JDK 25 下 :app:lintAnalyzeDebug 满载跑满 21 分 21 秒才结束；
# JDK 17 是 AGP 8.x 的受支持版本，因此默认用它）。显式用 ETA_GATE_JAVA_HOME 可覆盖；
# 没有自带 JDK 的机器仍沿用外部 JAVA_HOME。JDK 17 下的耗时尚无对比数据。
if [ -n "${ETA_GATE_JAVA_HOME:-}" ]; then
  export JAVA_HOME=$ETA_GATE_JAVA_HOME
elif [ -d /workspace/.tooling/jdk17 ]; then
  export JAVA_HOME=/workspace/.tooling/jdk17
fi
LOGDIR=${LOGDIR:-/tmp/eta-gate}
mkdir -p "$LOGDIR"
STAGES=${STAGES:-test,lint,assemble}

gw() {
  # 默认值留给受限环境。本机 2026-10-24 实测：metaspace 只给 512m 时，
  # :app:testDebugUnitTest / :app:lintDebug 跑到一半 Gradle 自己把 daemon 停掉
  # （日志：`Gradle build daemon has been stopped: stop command received`），
  # 表现成「测试 / lint 失败」，其实是 JVM 元空间耗尽。内存充足的机器可覆盖：
  #   ETA_GATE_JVMARGS="-Xmx3g -XX:MaxMetaspaceSize=1g" STAGES=test sh tools/gate.sh
  # 2026-10-30 再踩一次：512m 下 lintAnalyzeDebug 跑了 9 分钟后直接
  # `OutOfMemoryError: Metaspace`（栈里是 lint 的 symbol 解析），退出码 1，
  # 而同一台机器上 gradle.properties 写的是 1g —— 脚本默认值覆盖了项目设置，
  # 等于「lint 阶段在本机永远过不去」。堆仍留给受限环境，元空间与项目设置对齐。
  # canary 包名透传：默认打 io.github.mangi.eta，而手机侧并行安装的是
  # io.github.mangi.eta.canary。包名不同时 `pm install -r` 会报 Success 却根本不生效
  # （2026-10-25 实测踩到，误判成「装了旧版」）。要装 canary：
  #   ETA_APP_ID_SUFFIX=.canary STAGES=assemble sh tools/gate.sh
  ./gradlew --offline \
    ${ETA_APP_ID_SUFFIX:+-Peta.applicationIdSuffix="$ETA_APP_ID_SUFFIX"} \
    -Dorg.gradle.jvmargs="${ETA_GATE_JVMARGS:--Xmx2000m -XX:MaxMetaspaceSize=1g}" \
    -Pkotlin.compiler.execution.strategy=in-process \
    --console=plain "$@"
}

fail=0
has_stage() {
  case ",$STAGES," in *",$1,"*) return 0 ;; *) return 1 ;; esac
}

# ---- 单测 ---------------------------------------------------------------
# 只检查「有没有失败」是不够的：白名单必须精确比对 —— 失败集合不等于白名单就是红。
# **白名单现在是空的**（2026-10-24 收尾时清空）。
# 最后一条曾经挂在这里：
#   RootShellTerminalControllerCancellationTest.completedBackgroundJobCleansGrandchildProcess
#   原因：用 python3 写 setsid 包装起后台任务，报 PROCESS_START_FAILED（此环境起不来进程）。
#   清空依据：2026-10-24 用 `--rerun-tasks` 连跑两轮（每轮 3 个用例，强制真跑而不是 UP-TO-DATE），
#   两轮都是 0 失败；门禁整体 366 例也全绿。留着它反而危险 —— 这条路径将来真坏时会被当成
#   「已知环境问题」放过，正是白名单最容易骗人的方式。
# 另外两个曾经挂在这里的用例（Robolectric 不支持 Linux/aarch64、root 无法造只读父目录）
# 已改成「平台不支持就 assume 跳过」：跳过比失败更诚实，也不该再占白名单。
#
# 工作区的几个纯 JVM 测试必须一起跑：2026-10-24 新增的 WorkspaceRootCommandTest /
# WorkspaceMessagesTest 就是为了在这台机器上能真跑；只跑终端包 = 新门禁永远不执行。
# 注意不能写 `Workspace*`：WorkspaceFileStoreTest 是 Robolectric，在 aarch64 上必红。
#
# AgentRunRecoveryCoordinatorTest 是纯 JVM（只用 data class + 纯函数 plan()），
# 2026-10-24 多会话事故后补上：它守着「primary 结束不能把后台 run 判成中断」这条不变量。
#
# LocaleResourceParityTest 是纯 JVM（只读 res 下的 XML），2026-10-25 补上：
# 它守着「英文资源里不能有中文」「三语键集一致」「占位符一致」这三条。
#
# ConversationHistoryExportTest 是纯 JVM（假数据源 + StringWriter），2026-10-25 补上：
# 它守着「完整历史导出逐块流式写、没有被当成整串拼进内存」和「正常结束必有 end 行」——
# 大会话 journal 有 3100 万字符，任意一处退化成都行拼接都会让导出在真机上 OOM。
#
# AgentWireHistoryHandoffTest 是纯 JVM（只碰 ConcurrentHashMap），2026-11-01 补上：
# §32.33 的进程内直传靠三条硬约束撑着 —— 单次消费、TTL / 容量自清、句柄失效即失败。
# 它此前**一次都没被门禁跑过**；同一段代码的另一条用例正是在全量跑里被发现断言漂移的。
# 注意 AgentRuntimeWireTest 不能加：它带 @Config(sdk = [33, 36])，sdk 36 实例在
# Linux/aarch64 上必然撞 Robolectric native runtime，加进来等于给门禁塞固定红。
#
# AgentSelfAuditTest 是纯 JVM（判定逻辑不碰 Android），2026-11-01 随 eta_self_audit 一起加：
# 它守着「窗口外的旧崩溃、别人的崩溃、有 root 兜底的通知权限都不算本应用缺陷」，
# 以及「没有 root 时必须写进 unavailable，而不是报『没有崩溃』」。
FAST_TEST_FILTERS='--tests io.github.mangi.eta.agent.terminal.*
  --tests io.github.mangi.eta.agent.runtime.AgentWireHistoryHandoffTest
  --tests io.github.mangi.eta.agent.tool.AgentSelfAuditTest
  --tests io.github.mangi.eta.data.repository.ConversationHistoryExportTest
  --tests io.github.mangi.eta.ui.components.AgentChatTimelineEntriesTest
  --tests io.github.mangi.eta.ui.components.ToolIconMappingTest
  --tests io.github.mangi.eta.ui.app.AgentRunRecoveryCoordinatorTest
  --tests io.github.mangi.eta.ui.app.AgentAttachRecoveryTest
  --tests io.github.mangi.eta.ui.app.LocaleResourceParityTest
  --tests io.github.mangi.eta.ui.app.ToolCatalogUiTest
  --tests io.github.mangi.eta.ui.app.WorkspaceRootCommandTest
  --tests io.github.mangi.eta.ui.app.WorkspaceMessagesTest
  --tests io.github.mangi.eta.ui.app.WorkspaceExportNameTest
  --tests io.github.mangi.eta.ui.app.WorkspaceDirectoryBrowserTest
  --tests io.github.mangi.eta.ui.app.WorkspacePickerHistoryTest
  --tests io.github.mangi.eta.agent.tool.AgentToolRequirementsTest
  --tests io.github.mangi.eta.agent.device.UiNodeWireTest
  --tests io.github.mangi.eta.agent.device.VirtualDisplayFallbackPolicyTest
  --tests io.github.mangi.eta.agent.device.VirtualDisplayOwnershipTest
  --tests io.github.mangi.eta.agent.device.RootScrollResendPolicyTest
  --tests io.github.mangi.eta.agent.device.BlindTapSafetyPolicyTest
  --tests io.github.mangi.eta.agent.device.ScrollTargetPolicyTest
  --tests io.github.mangi.eta.agent.device.ScrollRegionPolicyTest
  --tests io.github.mangi.eta.agent.device.TextInputVerificationTest
  --tests io.github.mangi.eta.agent.device.TextWriteSupportTest
  --tests io.github.mangi.eta.agent.device.KeyPressRoutePolicyTest
  --tests io.github.mangi.eta.agent.device.ScrollEvidenceContractTest
  --tests io.github.mangi.eta.agent.tool.CoordinateDimensionPolicyTest
  --tests io.github.mangi.eta.agent.tool.ObservationScopePolicyTest
  --tests io.github.mangi.eta.agent.tool.AgentVirtualDisplayToolsTest
  --tests io.github.mangi.eta.agent.tool.ImplicitDisplayPolicyTest
  --tests io.github.mangi.eta.agent.tool.DisplayTextTargetPolicyTest
  --tests io.github.mangi.eta.agent.tool.AfterActionScopePolicyTest
  --tests io.github.mangi.eta.agent.tool.ToolArgumentHintPolicyTest
  --tests io.github.mangi.eta.agent.tool.ToolArgumentSchemaTest
  --tests io.github.mangi.eta.agent.accessibility.AccessibleWindowSelectionTest
  --tests io.github.mangi.eta.agent.accessibility.AccessibilityNodeIdentityTest
  --tests io.github.mangi.eta.agent.runtime.AgentContextCompactionNoticeTest
  --tests io.github.mangi.eta.agent.model.AgentProviderSummaryEffortTest
  --tests io.github.mangi.eta.agent.model.AgentBusySendModeTest
  --tests io.github.mangi.eta.agent.model.AgentWorkCollapseTimingTest
  --tests io.github.mangi.eta.agent.model.AgentTranscriptViewTest
  --tests io.github.mangi.eta.agent.model.AgentSubmitDedupPolicyTest
  --tests io.github.mangi.eta.agent.runtime.AgentGoalStoreTest
  --tests io.github.mangi.eta.agent.runtime.AgentLegacyConversationProjectionTest
  --tests io.github.mangi.eta.agent.model.AgentConversationCodecTest
  --tests io.github.mangi.eta.agent.model.AgentContextSummarizerParallelTest
  --tests io.github.mangi.eta.agent.tool.AgentAfterActionSummaryVolumeTest
  --tests io.github.mangi.eta.ui.app.AgentRunMessageProjectorTest
  --tests io.github.mangi.eta.agent.acp.AcpProtocolTest
  --tests io.github.mangi.eta.agent.acp.AcpOrphanReaperTest
  --tests io.github.mangi.eta.agent.acp.AcpFatalStderrTest
  --tests io.github.mangi.eta.agent.acp.AcpPermissionPolicyTest
  --tests io.github.mangi.eta.agent.acp.AcpUpdateAccumulatorTest
  --tests io.github.mangi.eta.agent.acp.AcpDelegateSummaryTest
  --tests io.github.mangi.eta.agent.acp.AcpModelsTest
  --tests io.github.mangi.eta.agent.acp.AcpLaunchEnvTest
  --tests io.github.mangi.eta.ui.app.MessageSortAlignmentTest
  --tests io.github.mangi.eta.ui.app.AgentMessageQueueReducerTest
  --tests io.github.mangi.eta.ui.app.ToolStatusRestoreTest
  --tests io.github.mangi.eta.ui.model.AgentAskUiTest
  --tests io.github.mangi.eta.ui.app.AgentPendingAskAnswersTest'

# 反向自测尝试过、但**没做成**（2026-10-24）：原打算扫 `ui/app` 下所有 `*Test.kt`，
# 要求每个类都出现在 FAST_TEST_FILTERS 里，否则红。实测**恒红**：该目录下还有一大堆
# 不属于本次快速子集的测试类（RoleplayConversationReducer、ShareIntent、SkillZipImport…），
# 它们从来就不在这个阶段跑（那要 `STAGES=test-all`），恒红会把人逼着把红色当背景。
# 也没法用「最近改动过的文件」当判据：本仓库源码会被 rsync 刷新 mtime，会频繁误报。
# 所以这轮**没有**自动机制拦住「写了测试却没加进过滤列表」，只能靠这行提醒：
# **新增 `ui/app` 下能本机跑的测试，必须同时加进上面的列表；漏加的表现是「用例数不变」。**
# 2026-10-26 又踩一次同一类事：`UiNodeWireTest`（纯 JVM，守着 ui_nodes 体积不可回涨）
# 2026-10-26 同一天第三次（差点第三次放过）：`ScrollTargetPolicyTest`（新增，守 scroll 的
# 「先判定再动手」）也没进列表：门禁报 473 例全绿，它一例都没跑。已补（预计 479 例）。
# 2026-10-26 深夜第四次（这次是写完先说一句才加）：`ScrollRegionPolicyTest`（守「指了哪个节点就滑
# 哪个区域」，防指东滑西）同样必须先加进来，否则用例数不变、绿灯来自「没跑到」。
# **结论：新增任何能在本机跑的测试类，第一件事就是把它加到这个列表里。**
# 2026-10-27（fix167）反过来的坑：Robolectric 测试在本机（Linux aarch64）**跑不了** ——
# 「The Robolectric native runtime is not supported on Linux (aarch64)」，
# 一加进列表就是 3 例全红（B.221 只记了 SQLite native，实际是整套 native runtime）。
# 所以「能在本机跑」是硬前提：需要 Context / AgentLocalTools 这类用 Robolectric 的测试，
# 本机只能靠真机探针（VirtualDisplayDebugHost 的 STEP*）验证，别往这个列表里塞。
# 2026-10-27（fix171）同一个坑又踩了：给 `AccessibilityNodeIdentityTest` 新增两条几何指纹用例，
# 没加进列表 —— 门禁报 547 例全绿，那两条一例没跑；而这条改动正好是「放宽快照过期判定」的，
# 恰恰最需要单测兑。**结论同前：新增任何能在本机跑的测试类，第一件事就是把它加到这个列表。**
# 2026-11-01（§32.45）反过来补上一条：`ToolCatalogUiTest` 原本用 Robolectric 只为拿 Context 读文案，
# 改成可注入文案来源后是纯 JVM，把它的 6 个用例加进了下面的列表 —— 加之前它从没在门禁里跑过，
# 一跑就发现 14 个工具图标落到了默认 Build。
# 2026-11-01（§32.48）同一条坑的第二次实证：`AgentToolRequirementsTest` 也不在列表里，
# 而它守的「无 root 投影不许带 root 路径」早被 `eta_self_audit` 的描述破了 —— 全量跑是红的，
# 门禁一直绿。加进来前 8 例里 1 例失败，修好后 8/0。别再用「门禁绿」代指「测试通过」。
run_test() {
  echo "==== [1/3] 单测 ===="
  rm -rf app/build/test-results/testDebugUnitTest
  if has_stage test-all; then
    gw :app:testDebugUnitTest > "$LOGDIR/test.log" 2>&1
  elif has_stage test-wide; then
    # 名单 = 本机能跑通的全部测试类（两轮全量跑都通过的那些）。刷新：
    #   python3 tools/gate_coverage.py --write-wide   （先自己跑一轮全量，见该脚本说明）
    wide_args=$(grep -v '^$' tools/wide-tests.txt | sed 's/^/--tests /' | tr '\n' ' ')
    # 并行上原来的白名单：wide 名单排除了「有 skip 的类」，里面有 FAST 已覆盖的用例，
    # --tests 是并集，重复不会报错。
    # shellcheck disable=SC2086  # 故意按空格拆成多个 --tests 参数
    gw :app:testDebugUnitTest $FAST_TEST_FILTERS $wide_args > "$LOGDIR/test.log" 2>&1
  else
    # shellcheck disable=SC2086  # 故意按空格拆成多个 --tests 参数
    gw :app:testDebugUnitTest $FAST_TEST_FILTERS > "$LOGDIR/test.log" 2>&1
  fi
  code=$?
  python3 - "$LOGDIR/test.log" <<'PY'
import glob, sys, xml.etree.ElementTree as ET

# 白名单为空：任何失败都是红。要加回临时豁免，必须同时写清「为什么」和「怎么撤」。
KNOWN = set()

files = glob.glob("app/build/test-results/testDebugUnitTest/TEST-*.xml")
if not files:
    print("  ✗ 没有测试结果文件（多半是编译失败），见 " + sys.argv[1])
    sys.exit(2)

tests, failed = 0, set()
for f in files:
    r = ET.parse(f).getroot()
    tests += int(r.get("tests") or 0)
    for c in r.iter("testcase"):
        if c.find("failure") is not None or c.find("error") is not None:
            failed.add("%s.%s" % (c.get("classname"), c.get("name")))

new = sorted(failed - KNOWN)
fixed = sorted(KNOWN - failed)
print("  用例 %d 个，失败 %d 个（白名单 %d 个）" % (tests, len(failed), len(KNOWN)))
if new:
    print("  ✗ 白名单之外还有失败：")
    for n in new:
        print("    - " + n.rsplit(".", 2)[-2] + "." + n.rsplit(".", 1)[-1])
    sys.exit(1)
if fixed:
    print("  提示：这些已知环境用例这次通过了，可以从白名单移除：")
    for n in fixed:
        print("    - " + n)
sys.exit(0)
PY
  case $? in
    0) echo "  ✓ 单测通过" ;;
    1) echo "  ✗ 单测失败，见 $LOGDIR/test.log" ; fail=1 ;;
    *) echo "  ✗ 单测没有可判定的结果，见 $LOGDIR/test.log" ; fail=1 ;;
  esac
}

# ---- lint ---------------------------------------------------------------
# 两个坑都在这里踩过：
# 1. 退出码 0 不代表 lint 跑了：把 test 和 lint 写进同一条 gradle 命令时，测试失败后 lint
#    根本不会被调度，而日志里只留下 BUILD FAILED。
# 2. 退出码 0 也不代表有结论输出：lintReportDebug 复用缓存（UP-TO-DATE）时
#    Gradle 不会打印「Lint found ...」，只看日志行会把正常的 lint 误判成失败。
# 所以判据改成读 lint 自己的报告文件（app/build/reports/lint-results-debug.xml），
# 不依赖 Gradle 打印什么。
run_lint() {
  echo "==== [2/3] lint ===="
  gw :app:lintDebug > "$LOGDIR/lint.log" 2>&1
  code=$?
  if [ "$code" -ne 0 ]; then
    echo "  ✗ lint 失败（退出码 $code），见 $LOGDIR/lint.log"
    tail -5 "$LOGDIR/lint.log"
    fail=1
    return
  fi
  # 判断逻辑抽到 tools/lint_baseline_audit.py：它可以用「喂已知会失败的输入」来验，
  # 而整跑一次 lint 要十分钟，埋在 heredoc 里就等于不可验证。
  python3 tools/lint_baseline_audit.py \
    app/build/reports/lint-results-debug.xml app/lint-baseline.xml
  [ $? -eq 0 ] || fail=1
}

# ---- 打包 ---------------------------------------------------------------
run_assemble() {
  echo "==== [3/3] 打包 ===="
  gw :app:assembleDebug > "$LOGDIR/assemble.log" 2>&1
  code=$?
  if [ "$code" -ne 0 ]; then
    echo "  ✗ 打包失败（退出码 $code），见 $LOGDIR/assemble.log"
    tail -5 "$LOGDIR/assemble.log"
    fail=1
    return
  fi
  APK=$(ls -t app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1)
  if [ -z "$APK" ]; then
    echo "  ✗ 打包成功但没有找到 APK"
    fail=1
    return
  fi
  echo "  ✓ 打包成功：$APK"
  echo "    大小 $(stat -c %s "$APK") 字节 ｜ sha256 $(sha256sum "$APK" | cut -c1-16)…"
}

[ -n "$STAGES" ] || echo "STAGES 为空，什么都不会跑"

# ---- 契约前置检查（与 STAGES 无关，始终执行）-----------------------------
# 为什么需要它：fix174 把「API 30(R) 的版本判据」全删了 —— minSdk 33 之下那些判断恒真。
# 于是 minSdk 33 从「事实」变成了**代码依赖的契约**：一旦有人把 minSdk 降下去，
# windowsOnAllDisplays / getHistoricalProcessExitReasons 就成了未防护调用，
# 而且不会有任何编译错误、lint 也不会提醒（判据已经删了）。这里把它钉成硬检查。
run_contract() {
  echo "==== [0/3] 契约 ===="
  min_sdk=$(python3 -c 'import re,pathlib;t=pathlib.Path("app/build.gradle.kts").read_text(encoding="utf-8");m=re.search(r"minSdk\s*=\s*(\d+)",t);print(m.group(1) if m else "")')
  if [ -z "$min_sdk" ]; then
    echo "  ✗ 读不到 app/build.gradle.kts 里的 minSdk"
    fail=1
    return
  fi
  if [ "$min_sdk" -lt 33 ]; then
    echo "  ✗ minSdk=$min_sdk < 33：本仓库已依赖「API 30 必然可用」，降下去必须先补回版本判据"
    fail=1
    return
  fi
  echo "  ✓ minSdk=$min_sdk（≥ 33：API 30 接口不需要版本判断）"
}

run_contract

# ---- UI 动作接线（与 STAGES 无关，始终执行）-----------------------------
# 为什么无条件跑：多定义一个动作不会让编译、单测、lint 任何一道门变红 ——
# 它只会在界面上「什么都不发生」。2026-11-01 一次清掉 8 个这种动作，其中
# RetryConversationLoad 的分支还是 `-> Unit`（死动作 + 空承诺）。
# 静态扫描是秒级，没有理由把它绑在某一段上。
run_ui_actions() {
  echo "==== UI 动作接线 ===="
  python3 tools/ui_action_wiring.py || fail=1
}

# ---- 死文案（与 STAGES 无关，始终执行）-----------------------------
# 为什么紧跟动作接线：删代码 → 引用消失 → 文案变死，这三件事总是一起发生。
# 等 lint 发现要十几分钟，而且那时已经很难想起是哪次改动造成的（§32.41 踩过）。
# 基线 tools/dead_strings_allow.txt 记录已知技术债，只许减少。
run_dead_strings() {
  echo "==== 死文案（只许减少） ===="
  python3 tools/dead_strings.py || fail=1
}

run_ui_actions
run_dead_strings

if has_stage test || has_stage test-all || has_stage test-wide; then run_test; fi
if has_stage lint; then run_lint; fi
if has_stage assemble; then run_assemble; fi

if [ "$fail" -eq 0 ]; then
  echo "==== 门禁通过 ===="
  exit 0
fi
echo "==== 门禁失败 ===="
exit 1
