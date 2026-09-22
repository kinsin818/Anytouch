#!/usr/bin/env bash
# s1-smoke.sh — S1 模拟器冒烟（主窗专用，不进 worker 领地）
# 流程：boot MarvisPhone AVD -> 装 APK -> adb 开无障碍 -> 断言服务连接
#       -> 跑 20 轮 "设置→System→About phone→Android version" 任务，定位成功率 ≥95% 判 PASS。
# 触发链：adb am start --es task_json（MainActivity 收到后写 AppState 并退后台，目标页成为活动窗口）。
# 任务 JSON 可用环境变量 TASK_JSON 覆盖（镜像语言不同口径时用，如 zh-CN）。
set -euo pipefail
cd "$(dirname "$0")/.."

SDK="/c/Users/Administrator/AppData/Local/Android/Sdk"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator.exe"
PKG="com.anytouch.app"
SVC="$PKG/.service.AnytouchAccessibilityService"
RUNS="${RUNS:-20}"

DEFAULT_TASK='[{"action_id":"open-system","type":"click","source":"node","value":{"text":"System"},"safety":{"viewport_ok":true,"click_enabled":true}},{"action_id":"open-about","type":"click","source":"node","value":{"text":"About phone"},"safety":{"viewport_ok":true,"click_enabled":true}},{"action_id":"version-tap","type":"click","source":"node","value":{"text":"Android version"},"safety":{"viewport_ok":true,"click_enabled":true}}]'
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

step "4/6 开启无障碍服务"
"$ADB" shell settings put secure enabled_accessibility_services "$PKG/$SVC"
"$ADB" shell settings put secure accessibility_enabled 1
"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 3
"$ADB" shell dumpsys accessibility | grep -q "Anytouch" || { echo "SMOKE FAIL: 服务未出现在 dumpsys accessibility"; exit 1; }
echo "  服务已注册"

step "5/6 预热一轮（首次页面加载慢，不计入统计前先人工看一轮）"

step "6/6 跑 ${RUNS} 轮任务并计定位成功率"
hits=0
for i in $(seq 1 "$RUNS"); do
  "$ADB" shell am start -a android.settings.SETTINGS >/dev/null
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
  # 回设置首页：两级返回
  "$ADB" shell input keyevent 4 2>/dev/null || true
  "$ADB" shell input keyevent 4 2>/dev/null || true
done

rate=$(awk "BEGIN{printf \"%.1f\", $hits*100/$RUNS}")
echo "==== 定位成功率: $hits/$RUNS = $rate%"
awk "BEGIN{exit !($rate >= 95)}" && { echo "SMOKE PASS (模拟器口径; 真机口径属 T3)"; } || { echo "SMOKE FAIL (<95%)"; exit 1; }
