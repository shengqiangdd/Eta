#!/system/bin/sh
# 真机探针一键跑（设备侧执行，需要 root）。
#
# 为什么要这个脚本：探针必须在「刚装好的 APK」进程里跑，而手工流程有六个易错点，
# 每一个都真实把人骗过一次：
#   1. 包名不带 suffix → `pm install` 报 Success 却装进了另一个包（gate.sh 注释里同款坑）；
#   2. 报告文件没删 → 等的是上一次的结论，看着"很快就有结果"，其实是旧数据；
#   3. 进程没起来就起探针 → 探针根本没跑，报告永远不出现；
#   4. `am start -W` 在本机偶尔卡死 90 秒以上 → 外面必须包 timeout；
#   5. 只看日志不看报告 → FAIL 行淹没在几百行 logcat 里；
#   6. 装完包直接跑探针 → 无障碍服务卡在 Binding，虚拟屏 UI 树全空，
#      整段变成 DISPLAY_NO_VISIBLE_WINDOW 的假失败（本机实测 30 秒都没绑上，
#      而 force-stop 后重新 start 只要 8 秒）。所以这里先空起一次界面、等绑定，
#      再发探针 intent。
#
# 用法（设备侧 root shell）：
#   sh probe.sh scope     # 只跑 STEP15~18（屏作用域 / 按键落点 / 同屏校验 / 参数名提示），约 30 秒
#   sh probe.sh step      # 全量 STEP1~18，约 3 分钟
# 可覆盖：
#   DEV_PKG=io.github.mangi.eta.canary DEV_APK=/data/local/tmp/x.apk \
#     DEV_INPUT_PKG=com.coloros.note sh probe.sh scope
# 退出码：0 = 报告里的 verdict 全部不含 FAIL；1 = 有 FAIL；2 = 参数错；3 = 没产出报告。
set -u

PKG=${DEV_PKG:-io.github.mangi.eta.canary}
A11Y_SERVICE="$PKG/io.github.mangi.eta.agent.accessibility.AgentAccessibilityService"

# ---- 无障碍绑定检查 / 修复 ----------------------------------------------
# 为什么需要：装包会 kills 进程，AMS 可能让无障碍服务长时间停在 `Binding`，
# 而虚拟屏的 UI 树完全依赖这个服务 —— 它没绑上时 uiautomator 在虚拟屏上返回 0 个节点，
# 探针把「环境没准备好」记成一堆 SKIPPED（2026-10-27 实测就因此白跑两轮）。
a11y_bound() {
  dumpsys accessibility 2>/dev/null |
    sed -n '/Bound services:/,/Binding services:/p' | grep -q -F "$A11Y_SERVICE"
}

# 强制重新绑定：先把本包从 enabled 列表摘掉、再原样加回。
# 只「改一次值」没用（系统认为状态没变）；摘掉才会真的解绑，加回才会重新 bind。
# 不用 force-stop：它会把无障碍服务直接禁用（从 enabled 列表里删掉），反而更难恢复。
a11y_rebind() {
  cur=$(settings get secure enabled_accessibility_services)
  rest=$(printf '%s' "$cur" | tr ':' '\n' | grep -v -F "$A11Y_SERVICE" | grep -v '^$' | tr '\n' ':')
  rest=${rest%:}
  settings put secure enabled_accessibility_services "$rest"
  sleep 2
  if [ -n "$rest" ]; then
    settings put secure enabled_accessibility_services "$rest:$A11Y_SERVICE"
  else
    settings put secure enabled_accessibility_services "$A11Y_SERVICE"
  fi
}

wait_a11y_bound() {
  waited=0
  while [ "$waited" -lt "${1:-20}" ]; do
    a11y_bound && return 0
    sleep 2
    waited=$((waited + 2))
  done
  return 1
}

a11y_ensure() {
  if a11y_bound; then
    return 0
  fi
  echo "无障碍未绑定：$A11Y_SERVICE —— 重设 enabled_accessibility_services" >&2
  a11y_rebind
  if wait_a11y_bound 30; then
    echo "无障碍已重新绑定（摘掉再加回）" >&2
  else
    echo "无障碍仍未绑定。如果是系统弹窗拦住了，屏幕上应能看到「检测到 … 获取无障碍权限」：" >&2
    echo "点「保持开启」后重跑本脚本（这个弹窗会拦掉无障碍点击，脚本代点不了）。" >&2
    return 1
  fi
  echo "提示：若屏幕出现「保持开启」弹窗，请点它（脚本代点不了：该弹窗拦无障碍点击）" >&2
}

MODE=${1:-scope}
case "$MODE" in
  # 只检查/修复无障碍绑定，不跑探针：装完包立刻跑一次，避免后续探针白跑。
  a11y)
    if a11y_ensure; then
      echo "无障碍绑定正常：$A11Y_SERVICE"
      exit 0
    fi
    exit 4
    ;;
  scope)
    EXTRA=scope_probe
    REPORT_NAME=scope-probe.txt
    DONE_MARK='STEP18c verdict='
    WAIT_S=${DEV_WAIT:-180}
    ;;
  step)
    EXTRA=step_probe
    REPORT_NAME=step-probe.txt
    DONE_MARK='STEP18c verdict='
    WAIT_S=${DEV_WAIT:-300}
    ;;
  text)
    EXTRA=text_probe
    REPORT_NAME=text-probe.txt
    DONE_MARK='TEXT6 verdict='
    WAIT_S=${DEV_WAIT:-150}
    ;;
  *)
    echo "用法: sh probe.sh [scope|step|text|a11y]" >&2
    exit 2
    ;;
esac

APK=${DEV_APK:-/data/local/tmp/Eta-debug.apk}
# 空 = 用 APK 里的默认值（scope: 联系人；step: 设置）。别写死便签：它常被用户留在主屏。
if [ -n "${DEV_INPUT_PKG:-}" ]; then
  INPUT_PKG=$DEV_INPUT_PKG
else
  INPUT_PKG=""
fi
REPORT_DIR=${DEV_REPORT_DIR:-/sdcard/Android/data/$PKG/files}
REPORT=$REPORT_DIR/$REPORT_NAME
ACTIVITY=$PKG/io.github.mangi.eta.ui.MainActivity

# 收尾钩子必须比装包更早挂上：装完包、预热启动之后，前台就已经是测试包了，
# 任何一步失败退出都不能把用户丢在测试界面上。
cleanup_device() {
  local restore_pkg=${DEV_RESTORE_PKG-io.github.mangi.eta}
  [ -n "$restore_pkg" ] || return 0
  [ "$restore_pkg" = "$PKG" ] && return 0
  timeout 15 am start -n "$restore_pkg/io.github.mangi.eta.ui.MainActivity" \
    >/tmp/probe-restore.log 2>&1 ||
    echo "收尾：恢复前台失败（$restore_pkg），见 /tmp/probe-restore.log" >&2
  if [ "${DEV_STOP_TEST_PKG-1}" = "1" ]; then
    am force-stop "$PKG" 2>/dev/null || true
  fi
}
trap cleanup_device EXIT

[ -f "$APK" ] || { echo "找不到 APK: $APK" >&2; exit 2; }

# 无障碍预热：装包会 kills 进程，AMS 可能让无障碍服务长时间停在 Binding（本机实测 46 秒
# 还没绑上，且「Binding 列表为空」两种含义都有：还没开始绑、绑好了。所以不靠它判定，
# 改用经验做法：重启两次进程。装包后第一次启动常绑不上，第二次几秒内就好。
warmup() {
  am force-stop "$PKG" 2>/dev/null
  sleep 2
  timeout 15 am start -n "$ACTIVITY" >/tmp/probe-warmup.log 2>&1
  # 固定 sleep 不够：进程可能还没起。实测 12:20 那次两次 warmup 都只投递了 intent，
  # 进程根本没重建，后面直接 exit 3（假失败）。改成盯 pidof，最多等 10 秒。
  local waited=0
  while [ "$waited" -lt 10 ]; do
    pidof "$PKG" >/dev/null 2>&1 && return 0
    sleep 1
    waited=$((waited + 1))
  done
  return 1
}

if [ "${DEV_SKIP_INSTALL-0}" = "1" ]; then
  echo "DEV_SKIP_INSTALL=1：跳过安装，按设备上已装的 $PKG 测（APK 尺寸仍会核对）"
else
  pm install -r "$APK" >/tmp/probe-install.log 2>&1
  if ! grep -q Success /tmp/probe-install.log; then
    echo "安装失败: $(cat /tmp/probe-install.log)" >&2
    exit 2
  fi
fi

# 坑 7（真机踩到，最贵的一个）：`pm install` 报 Success，装的却不是这个包。
# 有一次 APK 编译时漏了 `-Peta.applicationIdSuffix=.canary`，包名回落成正式版
# `io.github.mangi.eta` —— 安装照样 Success，canary 一直是上一次的旧包，
# 探针拿旧代码跑出新结论（报告格式都对不上），而且还误把用户日常用的 App 覆盖了。
# 所以用已安装 base.apk 的字节数反查：数量对不上就停，不继续测。
APK_SIZE=$(wc -c < "$APK" | tr -d ' ')
BASE=$(pm path "$PKG" 2>/dev/null | sed -n '1s/^package://p')
BASE_SIZE=$( [ -n "$BASE" ] && wc -c < "$BASE" 2>/dev/null | tr -d ' ' )
BASE_SIZE=${BASE_SIZE:-0}
if [ "$BASE_SIZE" != "$APK_SIZE" ]; then
  echo "安装未生效（可能装到了别的包）：$PKG base=$BASE_SIZE 字节，APK=$APK_SIZE 字节" >&2
  exit 2
fi
VER=$(dumpsys package "$PKG" | grep -m1 versionName | tr -d ' ')
echo "installed $PKG $VER ($APK_SIZE bytes)"

# 坑 6：装包 kills 进程，AMS 可能让无障碍服务卡在 Binding（虚拟屏 UI 树全靠它）。
warmup
sleep 5
warmup
sleep 8
# 预热不等于绑上了：实测有装完之后 30 秒还停在 Binding 的情况，
# 而上一版脚本只靠两次 warmup + sleep，会直接拿一堆 SKIPPED 当结论。
# 这里改成「检查 → 不绑就摘掉再加回 → 再检查」，失败也不跑探针（exit 4）。
a11y_ensure || exit 4

# 坑 2：报告必须在这之后删，前面预热那次的 extra 不该产生报告。
rm -f "$REPORT"

# 坑 3/4：`-W` 会卡，只发 intent；进程已在。
start_probe_intent() {
  timeout 15 am start -n "$ACTIVITY" \
    $(if [ -n "$INPUT_PKG" ]; then echo "--es eta.debug.input_pkg $INPUT_PKG"; fi) \
    --es eta.debug.virtual_display "$EXTRA" >>/tmp/probe-start.log 2>&1
}
start_probe_intent
if ! pidof "$PKG" >/dev/null 2>&1; then
  # 坑 10：最近任务里还留着这个 Task 时，`am start` 只会把 intent 投递给
  # 「已在运行的实例」，而进程刚被装包杀掉 —— intent 投递成功、进程却没重建。
  # 实测就是一条 exit 3 的假失败（手工 force-stop 再 start 一次就正常）。
  am force-stop "$PKG" 2>/dev/null
  sleep 2
  start_probe_intent
fi
if ! pidof "$PKG" >/dev/null 2>&1; then
  echo "进程没起来: $(cat /tmp/probe-start.log)" >&2
  exit 3
fi

# 坑 8：进程活着 ≠ 它在**前台**。2026-10-27 实测：canary 进程正常、无障碍也连上了，
# 但前台还是正式版 io.github.mangi.eta，于是 TEXT1~TEXT3 全部 SKIPPED_FOREGROUND_OTHER。
#
# 坑 9（坑 8 的第一版自己踩的）：`dumpsys activity activities | grep -m1 topResumedActivity`
# 拿到的未必是前台 —— 那些行是**每个 Task 各一行**（不是一个屏一行）：第一个 Task 可能是
# 后台里那个正式版，于是检查永远不通过（实测：警告发了两分钟，探针照样跑出 12 项 OK）。
# 真正的前台看 `dumpsys window` 的 mCurrentFocus（全局只有一行）。
focused_package() {
  dumpsys window 2>/dev/null | grep -m1 "mCurrentFocus=" |
    grep -o "u0 [A-Za-z0-9._]*/" | head -1 | sed -e 's/u0 //' -e 's#/$##'
}

wait_foreground() {
  waited=0
  while [ "$waited" -lt 60 ]; do
    if [ "$(focused_package)" = "$PKG" ]; then
      return 0
    fi
    sleep 2
    waited=$((waited + 2))
  done
  return 1
}

# 注意：等不到也不能写「会记 SKIPPED」——2026-10-27 实测过这种说法是错的：
# 警告发出后前台在探针开跑前切过来了，TEXT1~3 照样是 OK。日志只能说事实，不能预言结果。
if ! wait_foreground; then
  timeout 15 am start -n "$ACTIVITY" >/tmp/probe-start.log 2>&1
  sleep 3
  wait_foreground ||
    echo "警告：一共等了约 2 分钟，前台焦点是 $(focused_package)（不是 $PKG）；探针照跑，那几项会自己记 SKIPPED" >&2
fi

# 报告写完才落盘，所以「最后一条 verdict」出现即可判定结束。
i=0
while [ $i -lt "$WAIT_S" ]; do
  if [ -f "$REPORT" ] && grep -q "$DONE_MARK" "$REPORT"; then
    break
  fi
  sleep 2
  i=$((i + 2))
done

if [ ! -f "$REPORT" ]; then
  echo "没产出报告（等 ${WAIT_S}s）: $REPORT" >&2
  exit 3
fi
if ! grep -q "$DONE_MARK" "$REPORT"; then
  echo "报告不完整（等 ${WAIT_S}s），先给已有内容：" >&2
  head -40 "$REPORT" >&2
  exit 3
fi

# 报告自证：报告第一行是 BUILD apk_size=…（由 App 自己读 base.apk 写出）。
# 和刚装的 APK 字节数不一致 → 这份报告是别的包（旧代码）跑出来的，结论不可用。
REPORT_SIZE=$(sed -n '1s/.*apk_size=\([0-9]*\).*/\1/p' "$REPORT")
if [ -n "$REPORT_SIZE" ] && [ "$REPORT_SIZE" != "$APK_SIZE" ]; then
  echo "报告来自另一个 APK：报告 apk_size=$REPORT_SIZE ，本次装的 APK=$APK_SIZE 字节" >&2
  exit 3
fi
echo "report apk_size=${REPORT_SIZE:-<没有 BUILD 行：旧版本 App>}"

# 坑 11（2026-10-27）：报告只有固定文件名，后一次运行会把前一次覆盖掉。
# 实测代价：先拿旧 APK 做反向实验、再跑修复版，回头核对时磁盘上只剩「旧 APK 失败」
# 那一份，修复版的 PASS 只能靠记忆复述 —— 等于没有证据。所以每轮都留一份带时间 +
# APK 指纹的副本；只增不改，不参与 PASS/FAIL 判定。
archive_report() {
  a_dir="$REPORT_DIR/archive"
  if ! mkdir -p "$a_dir" 2>/dev/null; then
    echo "警告：留档目录不可写，报告没有备份（$a_dir）" >&2
    return 0
  fi
  a_fp=$(sha256sum "$APK" 2>/dev/null | cut -c1-12)
  if [ -z "$a_fp" ]; then
    a_fp=$(md5sum "$APK" 2>/dev/null | cut -c1-12)
  fi
  [ -n "$a_fp" ] || a_fp=noapk
  a_dest="$a_dir/${REPORT_NAME%.txt}-$(date +%Y%m%d-%H%M%S)-$MODE-$a_fp.txt"
  if cp "$REPORT" "$a_dest" 2>/dev/null; then
    echo "留档：$a_dest"
  else
    echo "警告：报告留档失败（$a_dest）" >&2
  fi
}
archive_report

# 坑 10（2026-10-27）：`OK_UNVERIFIED` 以前会被算成干净的 PASS。
# 它表示「动作被接受了，但没有读回证据」——那正是 fix159/fix160/fix175 反复在修的同一个毛病
# （把「没报错」当成「成功」）。所以它也归入「带跳过的通过」，不拦门禁、也不冒充全绿；
# 同理 `OK_HONEST_DEGRADE`（工具主动降级并说清楚了）也算带跳过。
#
# 坑 5：verdict 是唯一判据，前缀固定。
echo "--- verdict ---"
grep -E 'verdict=|accessibility_ready_after' "$REPORT"
echo "--- 结果 ---"
if grep -E 'verdict=' "$REPORT" | grep -q FAIL; then
  echo "FAIL" >&2
  exit 1
fi
if grep -E 'verdict=' "$REPORT" | grep -q -e SKIPPED -e NOT_ATTEMPTED -e UNVERIFIED -e DEGRADE; then
  echo "PASS_WITH_SKIPS"
  exit 0
fi
echo "PASS"
