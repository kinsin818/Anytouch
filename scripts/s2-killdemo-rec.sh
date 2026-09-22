#!/usr/bin/env bash
# s2-killdemo-rec.sh — S2 安全链 demo 视频（证据产物，非门禁）
# 两幕：A 高危二次确认→点"确认执行"放行（ok=3/3）；B 同链面板挂起→点悬浮停止球（≤3s user_stop 即停）。
# 前置：模拟器已启动、APK 已装。面板/球坐标为测试通道模拟手指（同 device-smoke C6/C7 口径），非产品定位。
set -euo pipefail
cd "$(dirname "$0")/.."

SDK="/c/Users/Administrator/AppData/Local/Android/Sdk"
ADB="$SDK/platform-tools/adb"
PKG="com.anytouch.app"
SVC="$PKG.service.AnytouchAccessibilityService"
OUT="evidence/S2/demo"
mkdir -p "$OUT"

SAFE='"safety":{"viewport_ok":true,"click_enabled":true}'
CHAIN="[{\"action_id\":\"p1\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"Search settings\"},$SAFE},{\"action_id\":\"p2\",\"type\":\"type_text\",\"source\":\"node\",\"value\":{\"text\":\"Search settings\",\"input\":\"password\"},$SAFE},{\"action_id\":\"p3\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"Passwords & accounts\"},$SAFE}]"
BTN_CONFIRM="702 1294"   # 面板"确认执行"
BALL="1002 1272"         # 悬浮停止球默认停靠位

echo "==== 1/5 服务绑定确认"
"$ADB" shell settings put secure enabled_accessibility_services "$PKG/$SVC"
"$ADB" shell settings put secure accessibility_enabled 1
"$ADB" shell dumpsys accessibility | grep -q "Anytouch" || { echo "服务未绑定"; exit 1; }

echo "==== 2/5 起录"
MSYS_NO_PATHCONV=1 "$ADB" shell rm -f /data/local/tmp/anytouch-s2-safety.mp4 || true
MSYS_NO_PATHCONV=1 "$ADB" shell screenrecord --time-limit 90 --bit-rate 6000000 /data/local/tmp/anytouch-s2-safety.mp4 >/dev/null 2>&1 &
REC_PID=$!
sleep 2

echo "==== 3/5 幕A：面板→确认执行放行"
"$ADB" logcat -c
MSYS_NO_PATHCONV=1 "$ADB" shell am force-stop com.android.settings
MSYS_NO_PATHCONV=1 "$ADB" shell am start -a android.settings.SETTINGS >/dev/null
sleep 5
MSYS_NO_PATHCONV=1 "$ADB" shell "am start -f 536870912 -n $PKG/.MainActivity --es task_json '$CHAIN'" >/dev/null
sleep 9
MSYS_NO_PATHCONV=1 "$ADB" shell input tap $BTN_CONFIRM >/dev/null
A_HIT=""
for _ in $(seq 1 15); do
  if "$ADB" logcat -d -s AnytouchRun:I | grep -q 'S1SMOKE ok=3 total=3 stopped=false'; then A_HIT=ok; break; fi
  sleep 1
done
[ -n "$A_HIT" ] && echo "  幕A: OK (确认放行 ok=3/3)" || echo "  幕A: FAIL/TIMEOUT（视频仍为真实过程）"
"$ADB" logcat -d -s AnytouchRun:I | grep 'S1SMOKE' > "$OUT/safety-receipt.txt" || true
sleep 3

echo "==== 4/5 幕B：面板挂起→点停止球即停"
"$ADB" logcat -c
MSYS_NO_PATHCONV=1 "$ADB" shell am force-stop com.android.settings
MSYS_NO_PATHCONV=1 "$ADB" shell am start -a android.settings.SETTINGS >/dev/null
sleep 5
MSYS_NO_PATHCONV=1 "$ADB" shell "am start -f 536870912 -n $PKG/.MainActivity --es task_json '$CHAIN'" >/dev/null
sleep 9
MSYS_NO_PATHCONV=1 "$ADB" shell input tap $BALL >/dev/null
B_HIT=""
for _ in $(seq 1 8); do
  if "$ADB" logcat -d -s AnytouchRun:I | grep -q 'stop="user_stop"'; then B_HIT=ok; break; fi
  sleep 1
done
[ -n "$B_HIT" ] && echo "  幕B: OK (挂起期点球 user_stop)" || echo "  幕B: FAIL/TIMEOUT（视频仍为真实过程）"
sleep 2
"$ADB" logcat -d -s AnytouchRun:I | grep 'S1SMOKE' >> "$OUT/safety-receipt.txt" || true

echo "==== 5/5 停录并拉回"
MSYS_NO_PATHCONV=1 "$ADB" shell "pkill -2 screenrecord" 2>/dev/null || true
wait $REC_PID 2>/dev/null || true
sleep 1
MSYS_NO_PATHCONV=1 "$ADB" pull /data/local/tmp/anytouch-s2-safety.mp4 "$OUT/anytouch-s2-safety-demo.mp4"
ls -la "$OUT"
echo "S2 SAFETY DEMO -> $OUT/anytouch-s2-safety-demo.mp4"
