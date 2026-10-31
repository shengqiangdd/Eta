#!/bin/sh
# 装前门禁：任何改动在装到正式版之前必须先过这里。
# 背景：第 155 轮只跑了一部分测试就装了，漏登记的 plugin_reload 让「每个新会话」都起不来。
# 用法（Linux 环境）：sh tools/preinstall-gate.sh
# 退出码：0 = 可以安装；非 0 = 禁止安装。
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT" || exit 2

if [ ! -d /workspace ]; then
  echo "请在 Linux 环境运行（Android 侧没有 gradle/JDK）"
  exit 2
fi

export ANDROID_HOME=${ANDROID_HOME:-/workspace/.tooling/android/sdk}
export ANDROID_SDK_ROOT=$ANDROID_HOME
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-/root/.gradle}
META=app/build/outputs/apk/debug/output-metadata.json
# 每次打包都带版本标记：否则包名与装之前那个一模一样，事后没法从界面分辨装的是哪一次。
TAG=${ETA_BUILD_METADATA:-fix2}
GRADLE_ARGS="-Peta.buildMetadata=$TAG"
fail=0

echo "==== 1/4 工具目录一致性 + 启动/插件页/链接安装纯逻辑测试 ===="
./gradlew --offline $GRADLE_ARGS :app:testDebugUnitTest \
  --tests "*AgentToolCatalogRequirementTest" \
  --tests "*AppearanceSnapshotTest" \
  --tests "*PluginItemUiTest" \
  --tests "*PluginLinkSourceTest" \
  --tests "*PluginSourceStoreTest" \
  --tests "*PluginMcpSyncPlanTest" \
  --tests "*PluginVersionTest" \
  --tests "*PluginUpdateSummaryTest" \
  --tests "*PluginDetailsTest" \
  --tests "*PluginHistoryStoreTest" \
  --tests "*PluginPackageInstallerInspectTest" >/tmp/gate-test.log 2>&1
if [ $? -ne 0 ]; then
  echo "  ✗ 测试失败，见 /tmp/gate-test.log"
  grep -E "FAILED|Missing tool" /tmp/gate-test.log | head -10
  fail=1
else
  echo "  ✓ 目录工具登记、插件能力计数、启动快照、链接校验与预览解析都正确"
fi

echo "==== 2/4 工具页卡片 ↔ 能力表 静态对照 ===="
python3 - <<'PY' || fail=1
import io, re, sys
ui = io.open("app/src/main/kotlin/io/github/mangi/eta/ui/app/AgentToolsCatalogUi.kt", encoding="utf-8").read()
# 能力表的名字来自三处：显式 register()、设备目录 AgentPhoneToolCatalog、个人检索 PersonalSearchTools
sources = [
    "app/src/main/kotlin/io/github/mangi/eta/agent/tool/AgentToolRequirements.kt",
    "app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentPhoneToolCatalog.kt",
    "app/src/main/kotlin/io/github/mangi/eta/agent/context/PersonalSearchTools.kt",
]
known = set()
for path in sources:
    known |= set(re.findall(r'"([a-z0-9_]+)"', io.open(path, encoding="utf-8").read()))
cards = set(re.findall(r'ToolItemUi\("([a-z0-9_]+)"', ui))
# 别名卡在 actualToolName() 里映射到真实工具，静态对照时同样接受
alias = {"browser_read", "browser_interact", "browser_screenshot"}
missing = sorted(c for c in cards - alias if c not in known)
if missing:
    print("  ✗ 这些卡片在能力表里不存在：", missing)
    sys.exit(1)
print("  ✓ %d 张卡片全部对得上（含 %d 个别名卡）" % (len(cards), len(cards & alias)))

# 新增文案必须三语齐全：漏一种语言时界面会静默回退成英文，
# 用户切到中文看到英文，会以为功能没做完。
import xml.etree.ElementTree as ET
base_keys = {e.get("name") for e in ET.parse("app/src/main/res/values/strings.xml").getroot()}
plugin_keys = {k for k in base_keys if k.startswith("plugins_")}
for lang in ("values-b+zh+Hans", "values-b+zh+Hant"):
    lang_keys = {e.get("name") for e in ET.parse("app/src/main/res/%s/strings.xml" % lang).getroot()}
    missing = sorted(plugin_keys - lang_keys)
    if missing:
        print("  ✗ %s 缺 %d 条插件文案：%s" % (lang, len(missing), missing[:6]))
        sys.exit(1)
print("  ✓ 插件页 %d 条文案三语齐全" % len(plugin_keys))
PY

# 与 tools/gate.sh 共用同一份判据：动作定义了却没人发，界面就永远不会出现那个入口。
# 放在装前门禁里，是因为「装完才发现某个入口是死的」只能靠用户来回试。
echo "  ── UI 动作接线 ──"
python3 tools/ui_action_wiring.py || fail=1

echo "==== 3/4 打包 ===="
./gradlew --offline $GRADLE_ARGS :app:assembleDebug >/tmp/gate-apk.log 2>&1
if [ $? -ne 0 ]; then
  echo "  ✗ 打包失败，见 /tmp/gate-apk.log"
  tail -5 /tmp/gate-apk.log
  fail=1
else
  echo "  ✓ 打包成功"
fi

echo "==== 4/4 产物检查（dex 里确实带新东西）====="
if [ $fail -eq 0 ]; then
  APK=$(python3 -c "import json;print(json.load(open('$META'))['elements'][0]['outputFile'])")
  python3 - "$APK" <<'PY' || fail=1
import sys, zipfile, subprocess, tempfile, os
apk = "app/build/outputs/apk/debug/" + sys.argv[1]
dexdump = "/workspace/.tooling/android/sdk/build-tools/36.0.0/dexdump"
target = "Lio/github/mangi/eta/agent/tool/AgentToolRequirements;"
# 能力表里必须存在的工具常量（第 155 轮事故的守卫）
required_tools = ["plugin_reload", "plugin_list", "acp_delegate", "virtual_display_create"]
# 新界面必须真的打进包里，否则「加了页面但装上没有」同样是事故
required_classes = [
    "Lio/github/mangi/eta/ui/screens/plugins/AgentPluginsScreenKt;",
    "Lio/github/mangi/eta/ui/app/AgentPluginsStore;",
    "Lio/github/mangi/eta/ui/navigation/AppRoute$Plugins;",
    # 链接安装：「下载 / 预览」这两块必须在包里，否则界面上的入口点了没有执行者
    "Lio/github/mangi/eta/agent/plugin/PluginLinkDownloader;",
    "Lio/github/mangi/eta/ui/model/PluginLinkPreviewUi;",
    # 来源记录：界面上的「来源」与「从来源更新」都要靠它，缺了整个入口就是空按钮
    "Lio/github/mangi/eta/agent/plugin/PluginSourceStore;",
    # MCP 差异计算：从界面装完插件却没注册服务器，靠它兜住
    "Lio/github/mangi/eta/agent/plugin/PluginMcpSyncPlan;",
    # 回滚：上一版留底存在时，「回滚到 vX」才有真正的执行者
    "Lio/github/mangi/eta/agent/plugin/PluginHistoryStore;",
    # 检查更新：版本比较与结论模型缺一个，「可更新」就只是界面上的死标签
    "Lio/github/mangi/eta/agent/plugin/PluginVersion;",
    "Lio/github/mangi/eta/ui/model/PluginUpdateStatusUi;",
    # 结论归类：标题说"已是最新"、正文说"来源更旧"的那种矛盾靠它和单测一起守住
    "Lio/github/mangi/eta/ui/model/PluginUpdateSummary;",
    # 插件详情：安装时间 / 包摘要的格式化（含"没有记录"这个合法空态）
    "Lio/github/mangi/eta/ui/model/PluginDetails;",
]
with zipfile.ZipFile(apk) as z, tempfile.TemporaryDirectory() as tmp:
    dumps = []
    for name in [n for n in z.namelist() if n.endswith(".dex")]:
        path = os.path.join(tmp, os.path.basename(name))
        open(path, "wb").write(z.read(name))
        # dexdump 会把 dex 里的原始字节原样打印，里面可能有非 UTF-8 字符串，必须容错解码
        out = subprocess.run([dexdump, "-d", path], capture_output=True)
        dumps.append((os.path.basename(name), out.stdout.decode("utf-8", "replace")))

    found_tool_class = False
    for dexname, dump in dumps:
        if "Class descriptor  : '%s'" % target not in dump:
            continue
        lines = dump.split("\n")
        start = next(i for i, l in enumerate(lines) if "Class descriptor  : '%s'" % target in l)
        block = []
        for line in lines[start + 1:]:
            if "Class descriptor  :" in line:
                break
            block.append(line)
        body = "\n".join(block)
        missing = [k for k in required_tools if '"%s"' % k not in body]
        if missing:
            print("  ✗ 能力表 <clinit> 里缺：", missing)
            sys.exit(1)
        print("  ✓ %s：能力表 <clinit> 含新工具常量" % dexname)
        found_tool_class = True

    if not found_tool_class:
        print("  ✗ APK 里找不到 AgentToolRequirements 类")
        sys.exit(1)

    for descriptor in required_classes:
        hit = any("Class descriptor  : '%s'" % descriptor in dump for _, dump in dumps)
        if not hit:
            print("  ✗ dex 里找不到：", descriptor)
            sys.exit(1)
        print("  ✓ dex 含 %s" % descriptor.split("/")[-1].rstrip(";"))
PY
fi

echo
if [ "$fail" -ne 0 ]; then
  echo "结论：禁止安装（先修问题）"
  exit 1
fi
echo "结论：可以安装"
