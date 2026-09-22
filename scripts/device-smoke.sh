#!/usr/bin/env bash
# Anytouch 设备回归冒烟（模拟器口径）：把 evidence/S2 设备补记里逐条手打的命令固化成机器可重跑的断言。
# 用法：bash scripts/device-smoke.sh   （需恰好 1 台 adb 设备、已装 debug APK、无障碍服务已绑）
# 退出码：0=全部通过；1=有失败；2=前置不满足。产品路径零坐标注入、零网络，纯 adb + logcat 回执断言；
# 例外（均为测试通道动作，脚本内留痕）：C6/C7 用 `input tap` 点悬浮停止球（模拟用户手指，非产品定位）；
# C8 真实切换无障碍服务开关（settings put）复现"执行中被系统解绑"，case 尾重绑恢复。
# 输入通道洁净断言（09-22 幽灵触点事件）：C5/C7 用 getevent 布网，合法触点预算均为 0
# （`input tap` 走 InputManager 注入、kernel /dev/input 看不见），任何捕获到的触摸即外部污染 FAIL。
# 防共享模拟器上别人的手把安全负例点成假绿。
set -u

# 设备定向：真机/模拟器并存时必须 export ANDROID_SERIAL=<serial>，否则多设备下裸 adb 全部报错
ADB="adb"
[ -n "${ANDROID_SERIAL:-}" ] && ADB="adb -s ${ANDROID_SERIAL}"

fail=0
RED='\033[0;31m'; GRN='\033[0;32m'; NCT='\033[0m'

pass() { printf "${GRN}PASS${NCT} %s\n" "$1"; }
bad()  { printf "${RED}FAIL${NCT} %s\n" "$1"; fail=1; }

# 等待本轮 S1SMOKE 总回执（每 2s 拉一次 logcat，最多 $2 秒）
wait_receipt() {
    local tag="$1" limit="${2:-40}" i=0 out=""
    while [ "$i" -lt "$limit" ]; do
        out=$(MSYS_NO_PATHCONV=1 $ADB logcat -d -s AnytouchRun:* 2>/dev/null | grep "S1SMOKE ok=" | tail -1 || true)
        if [ -n "$out" ]; then printf '%s' "$out"; return 0; fi
        sleep 2; i=$((i + 2))
    done
    return 1
}

run_case() {
    local name="$1" expect="$2" intent="$3" limit="${4:-40}"
    MSYS_NO_PATHCONV=1 $ADB logcat -c >/dev/null 2>&1
    # shellcheck disable=SC2086
    MSYS_NO_PATHCONV=1 $ADB shell "$intent" >/dev/null 2>&1
    local receipt
    receipt=$(wait_receipt "$name" "$limit")
    if printf '%s' "$receipt" | grep -qF "$expect"; then
        pass "$name :: $receipt"
    else
        # 红项随附分步 DETAIL（同 buffer，下个用例 logcat -c 前抓得到）——归因不用复跑碰运气
        local detail
        detail=$(MSYS_NO_PATHCONV=1 $ADB logcat -d -s AnytouchRun:* 2>/dev/null | grep "S1SMOKE-DETAIL" | tail -2 | tr '\n' ' ' || true)
        bad "$name :: 期望含 [$expect]，实际 [$receipt]${detail:+ | 明细: $detail}"
    fi
}

# ---------- 前置 ----------
devices=$(MSYS_NO_PATHCONV=1 adb devices | grep -c "device$" || true)
if [ "$devices" -lt 1 ]; then echo "前置失败：无 adb 设备"; exit 2; fi
if [ "$devices" -gt 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then
    echo "前置失败：同时接了 $devices 台设备，须 export ANDROID_SERIAL=<serial> 定向（真机/模拟器并存防打错靶）"; exit 2
fi
if ! MSYS_NO_PATHCONV=1 $ADB shell dumpsys accessibility | grep -q "com.anytouch.app"; then
    echo "前置失败：无障碍服务未绑定（设置→无障碍→Anytouch 执行器 开启，或重装 APK）"; exit 2
fi

SAFE='"safety":{"viewport_ok":true,"click_enabled":true}'

# 本地化/几何参数：默认值=模拟器英文系统口径；真机（如中文 MIUI）经环境变量覆盖，见 §T3 摸底档
TXT_SEARCH="${TXT_SEARCH:-Search settings}"
TXT_PASSWORDS="${TXT_PASSWORDS:-Passwords & accounts}"
TXT_CONNECTED="${TXT_CONNECTED:-Connected devices}"
RID_HOME="${RID_HOME:-com.android.settings:id/settings_homepage_container}"
BALL_TAP="${BALL_TAP:-1002 1272}"

# 无障碍清单快照：真机可能绑着别家服务（设备实证：K40 上另有两家），C8 只解绑自家、收尾原样恢复
A11Y_ORIG=$(MSYS_NO_PATHCONV=1 $ADB shell settings get secure enabled_accessibility_services | tr -d '\r')
A11Y_NOUS=$(printf '%s' "$A11Y_ORIG" | sed 's#com\.anytouch\.app/\.service\.AnytouchAccessibilityService##; s#::#:#g; s#^:##; s#:$##')

# 设备实证（AVD API 35）：搜索页跑在 com.google.android.settings.intelligence 独立进程，
# 只 force-stop settings 会留下旧搜索任务赖在前台（含旧查询词态），毒化下一条用例的首步定位。
stop_settings_ui() {
    MSYS_NO_PATHCONV=1 $ADB shell am force-stop com.android.settings >/dev/null 2>&1
    MSYS_NO_PATHCONV=1 $ADB shell am force-stop com.google.android.settings.intelligence >/dev/null 2>&1
}

# 就绪轮询（AVD 三档矩阵收口轮）：固定 sleep 在宿主高负载下不够——容器节点已在树里但条目
# 未排布完，scroll 被设备明示拒绝（perform_failed 假红）。dump 里目标容器 scrollable="true"
# 才是"真可滚"信号；15s 未就绪不判红（交给用例自己出诚实回执）。
wait_home_scrollable() {
    local i=0
    while [ "$i" -lt 15 ]; do
        if MSYS_NO_PATHCONV=1 $ADB shell uiautomator dump /sdcard/.smoke_ready.xml >/dev/null 2>&1 &&
           MSYS_NO_PATHCONV=1 $ADB shell cat /sdcard/.smoke_ready.xml 2>/dev/null |
               grep -qE "id/${RID_HOME##*/}\"[^>]*scrollable=\"true\""; then
            MSYS_NO_PATHCONV=1 $ADB shell rm /sdcard/.smoke_ready.xml >/dev/null 2>&1
            return 0
        fi
        sleep 1
        i=$((i + 1))
    done
    MSYS_NO_PATHCONV=1 $ADB shell rm /sdcard/.smoke_ready.xml >/dev/null 2>&1
    return 0
}

# ---------- C1 混合链：Settings 首页 滚动+点击 ----------
stop_settings_ui
# 设备实证（K80/HyperOS）：隐式 ACTION_SETTINGS 偶发被 com.milink.service  Connectivity 页劫持；显式组件名落回自家首页
MSYS_NO_PATHCONV=1 $ADB shell am start -n com.android.settings/.Settings >/dev/null 2>&1
# 设备实证（AVD 三档矩阵收口轮）：宿主并跑 3 台模拟器时 sleep 2/5 不够——首页容器已存在但
# 列表未排布完，scroll/click 明示拒绝出 perform_failed 假红；固定沉降 + scrollable 就绪轮询。
sleep 8
wait_home_scrollable
run_case "C1 混合链(scroll+click Settings)" "ok=2 total=2 stopped=false" \
    "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '[{\"action_id\":\"s1\",\"type\":\"scroll\",\"source\":\"node\",\"value\":{\"resource_id\":\"$RID_HOME\",\"direction\":\"forward\"},$SAFE},{\"action_id\":\"c1\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"$TXT_CONNECTED\"},$SAFE}]'"

# ---------- C2 type_text 经典 EditText（跨 App，按 hint text 定位；输入改变线索，考句柄活读复核） ----------
# 设备实证（模拟器回归轮）：C1 末步点进二级页后，仅 am start -n .Settings 只会 resume 到 SubSettings（搜索栏缺席→C2 假红）；必须 force-stop 重建首页
stop_settings_ui
MSYS_NO_PATHCONV=1 $ADB shell am start -n com.android.settings/.Settings >/dev/null 2>&1
# 设备实证（AVD 矩阵轮）：sleep 2 在慢 AVD 上首页未就绪，click→type 步间过渡竞态出 set_text_unverified 假红；与 C5/C6/C7 对齐取 8s
sleep 8
run_case "C2 type_text 经典EditText" "ok=2 total=2 stopped=false" \
    "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '[{\"action_id\":\"cs\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"$TXT_SEARCH\"},$SAFE},{\"action_id\":\"t1\",\"type\":\"type_text\",\"source\":\"node\",\"value\":{\"text\":\"$TXT_SEARCH\",\"input\":\"smoke c2 $(date +%s)\"},$SAFE}]'"

# ---------- C3 type_text 自家 Compose（resource_id 裸 testTag；keep_fg 自目标） ----------
run_case "C3 type_text Compose自目标" "ok=1 total=1 stopped=false" \
    "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '[{\"action_id\":\"t1\",\"type\":\"type_text\",\"source\":\"node\",\"value\":{\"resource_id\":\"task_input\",\"input\":\"smoke c3 $(date +%s)\"},$SAFE}]' --ez keep_fg true"

# ---------- C4 fail-closed 负例：不存在的节点必须 NODE_NOT_FOUND 停机，不得假绿 ----------
run_case "C4 负例 NODE_NOT_FOUND" "stop=\"NODE_NOT_FOUND\"" \
    "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '[{\"action_id\":\"x1\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"__no_such_node_smoke__\"},$SAFE}]'" 60

# ---------- C5 高危二次确认：无人点击=15s 超时默认拒绝（面板必须真实弹出，见 evidence/S2/stage-highrisk-confirm-device.md） ----------
# 输入通道洁净断言（09-22 幽灵触点事件后加装）：C5 全程合法触点预算=0，
# getevent 抓到任何触摸即判"外部污染"——防宿主鼠标/其他窗口把安全负例点成假绿。
stop_settings_ui
MSYS_NO_PATHCONV=1 $ADB shell am start -n com.android.settings/.Settings >/dev/null 2>&1
sleep 8
GEV=$(mktemp)
MSYS_NO_PATHCONV=1 $ADB shell "getevent -lt" > "$GEV" 2>&1 &
GE_PID=$!
sleep 1
run_case "C5 高危超时默认拒绝" "stop=\"PASSWORD:password\"" \
    "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '[{\"action_id\":\"s1\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"$TXT_SEARCH\"},$SAFE},{\"action_id\":\"s2\",\"type\":\"type_text\",\"source\":\"node\",\"value\":{\"text\":\"$TXT_SEARCH\",\"input\":\"password\"},$SAFE},{\"action_id\":\"s3\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"$TXT_PASSWORDS\"},$SAFE}]'" 60
kill $GE_PID 2>/dev/null
# 注：`|| true` 是必需的——grep -c 零命中时退出码为 1，且其 0 已先打到 stdout，不能重复 echo。
touches=$(grep -c "ABS_MT_TRACKING_ID   00000000" "$GEV" || true)
if [ "${touches:-0}" -eq 0 ]; then
    pass "C5x 输入通道洁净（零外部触点）"
else
    bad "C5x 输入通道洁净 :: 抓到 $touches 次外部触摸——C5 结果不可信（查谁的手/窗口在模拟器上），trap 存 $GEV"
fi

# ---------- C6 停止球即时响应：定位轮询期点球，回执须是 user_stop（非 NODE_NOT_FOUND）且 ≤5s 到达 ----------
# 回归锁（Task #14 设备雷）：KillSwitch 曾只在步首查询，长等待环里点球无感、末步点球丢归因。
# 坐标 (1002,1272) 是悬浮球默认停靠位（END|CENTER_VERTICAL, x=24），仅测试通道模拟手指，非产品定位。
stop_settings_ui
MSYS_NO_PATHCONV=1 $ADB shell am start -n com.android.settings/.Settings >/dev/null 2>&1
sleep 8
MSYS_NO_PATHCONV=1 $ADB logcat -c >/dev/null 2>&1
MSYS_NO_PATHCONV=1 $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '[{\"action_id\":\"k1\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"$TXT_CONNECTED\"},$SAFE},{\"action_id\":\"k2\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"__no_such_node_smoke__\"},$SAFE}]'" >/dev/null 2>&1
sleep 4  # 第一步落地、第二步进入 15s 定位轮询
T0=$(date +%s)
MSYS_NO_PATHCONV=1 $ADB shell input tap $BALL_TAP >/dev/null 2>&1
r6=$(wait_receipt C6 10)
dt=$(( $(date +%s) - T0 ))
if printf '%s' "$r6" | grep -qF 'stop="user_stop"' && [ "$dt" -le 5 ]; then
    pass "C6 停止球即时响应 :: ${dt}s :: $r6"
else
    bad "C6 停止球即时响应 :: 期望 [stop=\"user_stop\" 且 ≤5s]，实际 [${dt}s, $r6]"
fi

# ---------- C7 面板挂起期点球：确认面板(touch-modal 雷, FLAG_NOT_TOUCH_MODAL)不得吞掉停止球触点 ----------
# 链同 C5（超时默认拒绝），但 +9s 时面板应已弹出，点球后必须 ≤5s 出 user_stop（而非等满 15s 的 PASSWORD 归因）。
# 洁净预算=0：`input tap` 走 InputManager 注入、不经 /dev/input（getevent 看不见自家点球），
# 故 trap 抓到任何触摸都是宿主侧外部点击——09-22 幽灵触点事件：外部鼠标在球停靠位原地下键，
# 恰命中居中面板"确认执行"按钮，把 C5/C7 安全负例点成 ok=3/3 假绿。
stop_settings_ui
MSYS_NO_PATHCONV=1 $ADB shell am start -n com.android.settings/.Settings >/dev/null 2>&1
sleep 8
GEV7=$(mktemp)
MSYS_NO_PATHCONV=1 $ADB shell "getevent -lt" > "$GEV7" 2>&1 &
GE7=$!
sleep 1
MSYS_NO_PATHCONV=1 $ADB logcat -c >/dev/null 2>&1
MSYS_NO_PATHCONV=1 $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '[{\"action_id\":\"p1\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"$TXT_SEARCH\"},$SAFE},{\"action_id\":\"p2\",\"type\":\"type_text\",\"source\":\"node\",\"value\":{\"text\":\"$TXT_SEARCH\",\"input\":\"password\"},$SAFE},{\"action_id\":\"p3\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"$TXT_PASSWORDS\"},$SAFE}]'" >/dev/null 2>&1
sleep 9  # p1 开搜索、p2 落字、p3 命中 PASSWORD 词表 → 面板弹出挂起
T0=$(date +%s)
MSYS_NO_PATHCONV=1 $ADB shell input tap $BALL_TAP >/dev/null 2>&1
r7=$(wait_receipt C7 10)
dt=$(( $(date +%s) - T0 ))
kill $GE7 2>/dev/null
if printf '%s' "$r7" | grep -qF 'stop="user_stop"' && [ "$dt" -le 5 ]; then
    pass "C7 面板挂起期点球即停 :: ${dt}s :: $r7"
else
    bad "C7 面板挂起期点球即停 :: 期望 [stop=\"user_stop\" 且 ≤5s]，实际 [${dt}s, $r7]（若归因 PASSWORD:password=先看 C7x：外部触点可能已点了确认按钮）"
fi
t7=$(grep -c "ABS_MT_TRACKING_ID   00000000" "$GEV7" || true)
if [ "${t7:-0}" -eq 0 ]; then
    pass "C7x 输入通道洁净（触摸 ${t7:-0} 次 = 预算 0）"
else
    bad "C7x 输入通道洁净 :: 抓到 $t7 次触摸 > 预算 0（input tap 不经 /dev/input，任何捕获即外部触点）——外部触点可能点了确认按钮，C7 结果不可信，trap 存 $GEV7"
fi

# ---------- C8 执行中解绑：服务生命周期取消必须留 SERVICE_INTERRUPTED 回执痕（第 7/8 颗雷回归锁） ----------
# 背景：runTask 取消路径曾跳过收尾（running 永挂=执行器永久失能），finally 化后又发现"任务无声消失、
# 报告层无痕"是同类黑洞。本 case 真实切换无障碍服务开关（测试通道动作），case 尾重绑恢复环境。
MSYS_NO_PATHCONV=1 $ADB logcat -c >/dev/null 2>&1
MSYS_NO_PATHCONV=1 $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '[{\"action_id\":\"i1\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"__no_such_node_smoke__\"},$SAFE}]'" >/dev/null 2>&1
sleep 4  # 任务进入 15s 定位轮询中途
MSYS_NO_PATHCONV=1 $ADB shell "settings put secure enabled_accessibility_services '${A11Y_NOUS:-null}'" >/dev/null 2>&1
r8=0
for _ in 1 2 3 4 5 6 7 8; do
    r8=$(MSYS_NO_PATHCONV=1 $ADB logcat -d -s AnytouchRun:W 2>/dev/null | grep 'run cancelled by service lifecycle' | grep -c 'SERVICE_INTERRUPTED' || true)
    [ "${r8:-0}" -ge 1 ] && break
    sleep 1
done
# 恢复开跑前的原始清单（真机上有别家服务在绑，绝不硬覆盖）
MSYS_NO_PATHCONV=1 $ADB shell "settings put secure enabled_accessibility_services '${A11Y_ORIG:-com.anytouch.app/.service.AnytouchAccessibilityService}'" >/dev/null 2>&1
MSYS_NO_PATHCONV=1 $ADB shell settings put secure accessibility_enabled 1 >/dev/null 2>&1
sleep 3
if [ "${r8:-0}" -ge 1 ]; then
    pass "C8 执行中解绑留 SERVICE_INTERRUPTED 中断回执"
else
    bad "C8 执行中解绑 :: 期望日志含 [run cancelled ... SERVICE_INTERRUPTED]，实际 [$r8]（回执黑洞复发）"
fi

# ---------- C8b 解绑后自愈：新任务必须照常执行并出正确归因（防 running 悬挂复发） ----------
run_case "C8b 解绑重绑后执行器自愈" "stop=\"NODE_NOT_FOUND\"" \
    "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '[{\"action_id\":\"x2\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"__no_such_node_smoke__\"},$SAFE}]'" 60

echo
if [ "$fail" -eq 0 ]; then echo "device-smoke: ALL PASS"; else echo "device-smoke: 有失败项"; fi
exit "$fail"
