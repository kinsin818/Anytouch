#!/usr/bin/env bash
# s1-demo-rec.sh — 把 S1 现有能力打包成 demo 视频（证据产物，非新门禁）
# 内容：模拟器上连跑 3 轮 "Connected devices → Connection preferences → Bluetooth" 自主执行链，
#       全程 adb screenrecord 录屏，拉回 evidence/S1/demo/anytouch-s1-demo.mp4。
# 前置：模拟器已启动、APK 已装（与 s1-smoke.sh 同口径，步骤 3/4 复用其逻辑）。
set -euo pipefail
cd "$(dirname "$0")/.."

SDK="/c/Users/Administrator/AppData/Local/Android/Sdk"
ADB="$SDK/platform-tools/adb"
PKG="com.anytouch.app"
SVC="$PKG.service.AnytouchAccessibilityService"
OUT="evidence/S1/demo"
mkdir -p "$OUT"

DEFAULT_TASK='[{"action_id":"cd","type":"click","source":"node","value":{"text":"Connected devices"},"safety":{"viewport_ok":true,"click_enabled":true}},{"action_id":"cp","type":"click","source":"node","value":{"text":"Connection preferences"},"safety":{"viewport_ok":true,"click_enabled":true}},{"action_id":"bt","type":"click","source":"node","value":{"text":"Bluetooth"},"safety":{"viewport_ok":true,"click_enabled":true}}]'
TASK_JSON="${TASK_JSON:-$DEFAULT_TASK}"

echo "==== 1/4 服务绑定确认"
"$ADB" shell settings put secure enabled_accessibility_services "$PKG/$SVC"
"$ADB" shell settings put secure accessibility_enabled 1
"$ADB" shell pm grant $PKG android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 2
"$ADB" shell dumpsys accessibility | grep -q "Anytouch" || { echo "服务未绑定，先跑一次 s1-smoke.sh"; exit 1; }

echo "==== 2/4 起录"
MSYS_NO_PATHCONV=1 "$ADB" shell rm -f /data/local/tmp/anytouch-demo.mp4 || true
MSYS_NO_PATHCONV=1 "$ADB" shell screenrecord --time-limit 120 --bit-rate 6000000 /data/local/tmp/anytouch-demo.mp4 >/dev/null 2>&1 &
REC_PID=$!
sleep 2   # 留录制初始化余量

echo "==== 3/4 连跑 3 轮"
OK_RUNS=0
for i in 1 2 3; do
  "$ADB" shell am start -f 0x10008000 -a android.settings.SETTINGS >/dev/null
  sleep 2
  "$ADB" logcat -c
  "$ADB" shell "am start -n $PKG/.MainActivity --es task_json '$TASK_JSON'" >/dev/null
  HIT=""
  for _ in $(seq 1 40); do
    if "$ADB" logcat -d -s AnytouchRun:I 2>/dev/null | grep -q 'S1SMOKE ok=3 total=3 stopped=false'; then HIT=ok; break; fi
    sleep 1
  done
  [ -n "$HIT" ] && OK_RUNS=$((OK_RUNS + 1)) && echo "  run $i: OK" || echo "  run $i: FAIL/TIMEOUT"
  sleep 2
  "$ADB" shell input keyevent 4; "$ADB" shell input keyevent 4; "$ADB" shell input keyevent 4
  sleep 1
done
[ "$OK_RUNS" = "3" ] || echo "WARN: 仅 $OK_RUNS/3 轮成功（视频仍为真实过程）"

echo "==== 4/4 停录并拉回"
# Git-Bash 会把 /sdcard 参数 MSYS 转义成盘符路径，pull/rm 一律加 MSYS_NO_PATHCONV=1
MSYS_NO_PATHCONV=1 "$ADB" shell "pkill -2 screenrecord" 2>/dev/null || true
wait $REC_PID 2>/dev/null || true
sleep 1
MSYS_NO_PATHCONV=1 "$ADB" pull /data/local/tmp/anytouch-demo.mp4 "$OUT/anytouch-s1-demo.mp4"
ls -la "$OUT"
echo "DEMO REC OK -> $OUT/anytouch-s1-demo.mp4"
