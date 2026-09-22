#!/usr/bin/env bash
# Anytouch 设备回归冒烟（模拟器口径）：把 evidence/S2 设备补记里逐条手打的命令固化成机器可重跑的断言。
# 用法：bash scripts/device-smoke.sh   （需恰好 1 台 adb 设备、已装 debug APK、无障碍服务已绑）
# 退出码：0=全部通过；1=有失败；2=前置不满足。零坐标注入、零网络，纯 adb + logcat 回执断言。
set -u

fail=0
RED='\033[0;31m'; GRN='\033[0;32m'; NCT='\033[0m'

pass() { printf "${GRN}PASS${NCT} %s\n" "$1"; }
bad()  { printf "${RED}FAIL${NCT} %s\n" "$1"; fail=1; }

# 等待本轮 S1SMOKE 总回执（每 2s 拉一次 logcat，最多 $2 秒）
wait_receipt() {
    local tag="$1" limit="${2:-40}" i=0 out=""
    while [ "$i" -lt "$limit" ]; do
        out=$(MSYS_NO_PATHCONV=1 adb logcat -d -s AnytouchRun:* 2>/dev/null | grep "S1SMOKE ok=" | tail -1 || true)
        if [ -n "$out" ]; then printf '%s' "$out"; return 0; fi
        sleep 2; i=$((i + 2))
    done
    return 1
}

run_case() {
    local name="$1" expect="$2" intent="$3" limit="${4:-40}"
    MSYS_NO_PATHCONV=1 adb logcat -c >/dev/null 2>&1
    # shellcheck disable=SC2086
    MSYS_NO_PATHCONV=1 adb shell "$intent" >/dev/null 2>&1
    local receipt
    receipt=$(wait_receipt "$name" "$limit")
    if printf '%s' "$receipt" | grep -qF "$expect"; then
        pass "$name :: $receipt"
    else
        bad "$name :: 期望含 [$expect]，实际 [$receipt]"
    fi
}

# ---------- 前置 ----------
devices=$(MSYS_NO_PATHCONV=1 adb devices | grep -c "device$" || true)
if [ "$devices" -lt 1 ]; then echo "前置失败：无 adb 设备"; exit 2; fi
if ! MSYS_NO_PATHCONV=1 adb shell dumpsys accessibility | grep -q "com.anytouch.app"; then
    echo "前置失败：无障碍服务未绑定（设置→无障碍→Anytouch 执行器 开启，或重装 APK）"; exit 2
fi

SAFE='"safety":{"viewport_ok":true,"click_enabled":true}'

# ---------- C1 混合链：Settings 首页 滚动+点击 ----------
MSYS_NO_PATHCONV=1 adb shell am force-stop com.android.settings >/dev/null 2>&1
MSYS_NO_PATHCONV=1 adb shell am start -a android.settings.SETTINGS >/dev/null 2>&1
sleep 2
run_case "C1 混合链(scroll+click Settings)" "ok=2 total=2 stopped=false" \
    "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '[{\"action_id\":\"s1\",\"type\":\"scroll\",\"source\":\"node\",\"value\":{\"resource_id\":\"com.android.settings:id/settings_homepage_container\",\"direction\":\"forward\"},$SAFE},{\"action_id\":\"c1\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"Connected devices\"},$SAFE}]'"

# ---------- C2 type_text 经典 EditText（跨 App，按 hint text 定位；输入改变线索，考句柄活读复核） ----------
MSYS_NO_PATHCONV=1 adb shell am start -n com.android.settings/.Settings >/dev/null 2>&1
sleep 2
run_case "C2 type_text 经典EditText" "ok=2 total=2 stopped=false" \
    "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '[{\"action_id\":\"cs\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"Search settings\"},$SAFE},{\"action_id\":\"t1\",\"type\":\"type_text\",\"source\":\"node\",\"value\":{\"text\":\"Search settings\",\"input\":\"smoke c2 $(date +%s)\"},$SAFE}]'"

# ---------- C3 type_text 自家 Compose（resource_id 裸 testTag；keep_fg 自目标） ----------
run_case "C3 type_text Compose自目标" "ok=1 total=1 stopped=false" \
    "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '[{\"action_id\":\"t1\",\"type\":\"type_text\",\"source\":\"node\",\"value\":{\"resource_id\":\"task_input\",\"input\":\"smoke c3 $(date +%s)\"},$SAFE}]' --ez keep_fg true"

# ---------- C4 fail-closed 负例：不存在的节点必须 NODE_NOT_FOUND 停机，不得假绿 ----------
run_case "C4 负例 NODE_NOT_FOUND" "stop=\"NODE_NOT_FOUND\"" \
    "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '[{\"action_id\":\"x1\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"__no_such_node_smoke__\"},$SAFE}]'" 60

# ---------- C5 高危二次确认：无人点击=15s 超时默认拒绝（面板必须真实弹出，见 evidence/S2/stage-highrisk-confirm-device.md） ----------
MSYS_NO_PATHCONV=1 adb shell am force-stop com.android.settings >/dev/null 2>&1
MSYS_NO_PATHCONV=1 adb shell am start -a android.settings.SETTINGS >/dev/null 2>&1
sleep 5
run_case "C5 高危超时默认拒绝" "stop=\"PASSWORD:password\"" \
    "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '[{\"action_id\":\"s1\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"Search settings\"},$SAFE},{\"action_id\":\"s2\",\"type\":\"type_text\",\"source\":\"node\",\"value\":{\"text\":\"Search settings\",\"input\":\"password\"},$SAFE},{\"action_id\":\"s3\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"Passwords & accounts\"},$SAFE}]'" 60

echo
if [ "$fail" -eq 0 ]; then echo "device-smoke: ALL PASS"; else echo "device-smoke: 有失败项"; fi
exit "$fail"
