#!/usr/bin/env bash
# s2-demo-rec.sh — S2 增量能力（type_text 落字链路）demo 视频（证据产物，非门禁）
# 内容：Settings 搜索框 ①点击搜索栏 ②输入 "wifi"（结果实时刷新）③点击 "Wi-Fi" 结果 ④进入 Wi-Fi 页，
#       全程 screenrecord，拉回 evidence/S2/demo/anytouch-s2-type-demo.mp4。
# 前置：模拟器已启动、APK 已装且服务已绑（同 s1-demo-rec.sh 口径）。
set -euo pipefail
cd "$(dirname "$0")/.."

SDK="/c/Users/Administrator/AppData/Local/Android/Sdk"
ADB="$SDK/platform-tools/adb"
PKG="com.anytouch.app"
SVC="$PKG.service.AnytouchAccessibilityService"
OUT="evidence/S2/demo"
mkdir -p "$OUT"

SAFE='"safety":{"viewport_ok":true,"click_enabled":true}'
DEFAULT_TASK="[{\"action_id\":\"cs\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"Search settings\"},$SAFE},{\"action_id\":\"ty\",\"type\":\"type_text\",\"source\":\"node\",\"value\":{\"text\":\"Search settings\",\"input\":\"wifi\"},$SAFE},{\"action_id\":\"cw\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"Wi-Fi\"},$SAFE}]"
TASK_JSON="${TASK_JSON:-$DEFAULT_TASK}"

echo "==== 1/4 服务绑定确认"
"$ADB" shell settings put secure enabled_accessibility_services "$PKG/$SVC"
"$ADB" shell settings put secure accessibility_enabled 1
"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 2
"$ADB" shell dumpsys accessibility | grep -q "Anytouch" || { echo "服务未绑定"; exit 1; }

echo "==== 2/4 起录"
MSYS_NO_PATHCONV=1 "$ADB" shell rm -f /data/local/tmp/anytouch-s2-demo.mp4 || true
MSYS_NO_PATHCONV=1 "$ADB" shell screenrecord --time-limit 90 --bit-rate 6000000 /data/local/tmp/anytouch-s2-demo.mp4 >/dev/null 2>&1 &
REC_PID=$!
sleep 2

echo "==== 3/4 跑输入链（Settings 首页起）"
MSYS_NO_PATHCONV=1 "$ADB" shell am force-stop com.android.settings
MSYS_NO_PATHCONV=1 "$ADB" shell am start -a android.settings.SETTINGS >/dev/null
sleep 2
"$ADB" logcat -c
"$ADB" shell "am start -f 536870912 -n $PKG/.MainActivity --es task_json '$TASK_JSON'" >/dev/null
HIT=""
for _ in $(seq 1 45); do
  if MSYS_NO_PATHCONV=1 "$ADB" logcat -d -s AnytouchRun:I 2>/dev/null | grep -q 'S1SMOKE ok=3 total=3 stopped=false'; then HIT=ok; break; fi
  sleep 1
done
if [ -n "$HIT" ]; then echo "  输入链: OK (ok=3 total=3)"; else echo "  输入链: FAIL/TIMEOUT（视频仍为真实过程）"; fi
MSYS_NO_PATHCONV=1 "$ADB" logcat -d -s AnytouchRun:I | tail -3 > "$OUT/receipt.txt" || true
sleep 3   # 让 Wi-Fi 页在视频里停留
"$ADB" shell input keyevent 4   # 收起 IME/返回，收尾画面干净

echo "==== 4/4 停录并拉回"
MSYS_NO_PATHCONV=1 "$ADB" shell "pkill -2 screenrecord" 2>/dev/null || true
wait $REC_PID 2>/dev/null || true
sleep 1
MSYS_NO_PATHCONV=1 "$ADB" pull /data/local/tmp/anytouch-s2-demo.mp4 "$OUT/anytouch-s2-type-demo.mp4"
ls -la "$OUT"
echo "S2 DEMO REC -> $OUT/anytouch-s2-type-demo.mp4"
