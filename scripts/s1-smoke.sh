#!/usr/bin/env bash
# s1-smoke.sh — S1 模拟器冒烟（主窗专用，不进 worker 领地）
# 流程：boot MarvisPhone AVD -> 装 APK -> adb 开无障碍 -> 断言服务连接
#       -> 跑 20 轮 "Connected devices → Connection preferences → Bluetooth" 任务，定位成功率 ≥95% 判 PASS。
# 触发链：adb am start --es task_json（MainActivity 收到后写 AppState 并退后台，目标页成为活动窗口）。
# 任务 JSON 可用环境变量 TASK_JSON 覆盖（镜像语言不同口径时用，如 zh-CN）。
set -euo pipefail
cd "$(dirname "$0")/.."

SDK="/c/Users/Administrator/AppData/Local/Android/Sdk"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator.exe"
PKG="com.anytouch.app"
SVC="$PKG.service.AnytouchAccessibilityService"   # 全限定类名，勿再用相对点号前缀（组件名双段各含包名=非法，服务永不绑定）
RUNS="${RUNS:-20}"

# 三级前进钻取：Connected devices → Connection preferences → Bluetooth（全为 com.android.settings 普通列表行）。
# 实测排除项（模拟器口径；AVD MarvisPhone = 本机自建 API-34 google_apis 官方镜像，非厂商 ROM，真机大概率不复现）：
# Internet 页/permissioncontroller 角色页 a11y 树不下发（root 恒 null，属该镜像行为，真机复核归 T3）；
# 开关行重复点击有重绑动画竞态（performAction=false）；"See all 26 apps" 类计数变体行不适合 trim 全等匹配。
DEFAULT_TASK='[{"action_id":"cd","type":"click","source":"node","value":{"text":"Connected devices"},"safety":{"viewport_ok":true,"click_enabled":true}},{"action_id":"cp","type":"click","source":"node","value":{"text":"Connection preferences"},"safety":{"viewport_ok":true,"click_enabled":true}},{"action_id":"bt","type":"click","source":"node","value":{"text":"Bluetooth"},"safety":{"viewport_ok":true,"click_enabled":true}}]'
TASK_JSON="${TASK_JSON:-$DEFAULT_TASK}"

step() { echo "==== $*"; }

step "1/6 检查 emulator/AVD"
"$EMULATOR" -list-avds | grep -q MarvisPhone || { echo "AVD MarvisPhone 不存在"; exit 1; }

step "2/6 启动模拟器（若未运行）"
if ! "$ADB" devices | grep -qw emulator; then
  "$EMULATOR" -avd MarvisPhone -no-snapshot-save -no-boot-anim >/dev/null 2>&1 &
  "$ADB" wait-for-device
  until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
fi

step "3/6 安装 APK"
[ -f app/build/outputs/apk/debug/app-debug.apk ] || { echo "先跑 ./gradlew :app:assembleDebug"; exit 1; }
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null

step "4/6 开启无障碍服务（先清空再写入，强制重绑：force-stop/安装后系统不会自动回绑）"
"$ADB" shell settings delete secure enabled_accessibility_services >/dev/null 2>&1 || true
sleep 1
"$ADB" shell settings put secure enabled_accessibility_services "$PKG/$SVC"
"$ADB" shell settings put secure accessibility_enabled 1
# 执行期挂前台服务防 doze 冻结：API33+ 需 POST_NOTIFICATIONS，未授予则 startForeground 通知不显示（runCatching 已兜底，但补齐更干净）
"$ADB" shell pm grant $PKG android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 3
if "$ADB" shell dumpsys accessibility | grep -q "Bound services:{}"; then
  echo "SMOKE FAIL: 服务未绑定"; exit 1
fi
if ! "$ADB" shell dumpsys accessibility | grep -q "Anytouch"; then
  echo "SMOKE FAIL: 服务未出现在 dumpsys accessibility"; exit 1
fi
echo "  服务已绑定"

step "5/6 预热一轮（首次页面加载慢，不计入统计前先人工看一轮）"

step "6/6 跑 ${RUNS} 轮任务并计定位成功率"
hits=0
for i in $(seq 1 "$RUNS"); do
  # -f 0x10008000 = NEW_TASK|CLEAR_TASK：每轮从设置首页起，杜绝"残留下层页假成功"
  "$ADB" shell am start -f 0x10008000 -a android.settings.SETTINGS >/dev/null
  sleep 1
  "$ADB" logcat -c
  # 设备侧 sh 会吞双引号：整条命令本地展开后，用单引号把 JSON 原样送到 am
  "$ADB" shell "am start -n $PKG/.MainActivity --es task_json '$TASK_JSON'" >/dev/null
  line=""
  for _ in $(seq 1 30); do
    line="$("$ADB" logcat -d -s AnytouchRun:I 2>/dev/null | grep 'S1SMOKE ok=' | tail -1 || true)"
    [ -n "$line" ] && break
    sleep 1
  done
  if [ -z "$line" ]; then
    echo "  run $i: TIMEOUT 无回执"
  elif echo "$line" | grep -qE 'ok=3 total=3 stopped=false'; then
    hits=$((hits + 1))
    echo "  run $i: OK"
  else
    echo "  run $i: $line"
  fi
  # 回设置首页：三级返回（三层页面）
  "$ADB" shell input keyevent 4 2>/dev/null || true
  "$ADB" shell input keyevent 4 2>/dev/null || true
  "$ADB" shell input keyevent 4 2>/dev/null || true
done

rate=$(awk "BEGIN{printf \"%.1f\", $hits*100/$RUNS}")
echo "==== 定位成功率: $hits/$RUNS = $rate%"
awk "BEGIN{exit !($rate >= 95)}" && { echo "SMOKE PASS (模拟器口径; 真机口径属 T3)"; } || { echo "SMOKE FAIL (<95%)"; exit 1; }
