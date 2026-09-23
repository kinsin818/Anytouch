#!/usr/bin/env bash
# Anytouch 录制 UI 面设备冒烟（U 系列）：步序账「屏上渲染 ↔ 唯一写口 ↔ 回放真值」三方同步 + 编辑门禁 fail-closed。
# 用法：bash scripts/ui-smoke.sh        （需恰好 1 台 adb 设备、装好本轮 debug APK、无障碍已绑）
#        多设备/真机：export ANDROID_SERIAL=<serial>；中文 ROM 用 device-smoke 那套 TXT_* 变量。
# 为什么单开一个脚本：device-smoke.sh 的 13 项是 C 系列回归锁，混编会把"新面未验"混进旧账基线。
# 预置会话档 = 冻结 RecorderSession.serialize 的真产物（逐字锁在 UiSmokeSessionFixtureTest，
# 脚本内不手抄近似值）；UI 面用例不走录制通道（不需要球、零人工手指坐标）。
# 洁净纪律：uiautomator dump 只用于"读 UI 是否同步"，每次 dump 后一律 wait_service_bound 复绑
# （设备实证：dump 注册 UiTestAutomationService 会挤掉自家服务，不复绑则下一条用例必假红）。
set -u

ADB="adb"
[ -n "${ANDROID_SERIAL:-}" ] && ADB="adb -s ${ANDROID_SERIAL}"

# 词表（真机中文 ROM 用环境变量覆盖，与 device-smoke 同一口径）
TXT_CONNECTED="${TXT_CONNECTED:-Connected devices}"

fail=0
RED='\033[0;31m'; GRN='\033[0;32m'; NCT='\033[0m'
pass() { printf "${GRN}PASS${NCT} %s\n" "$1"; }
bad()  { printf "${RED}FAIL${NCT} %s\n" "$1"; fail=1; }
log()  { printf '      | %s\n' "$*"; }

# 预置会话档（与 app/src/test/.../UiSmokeSessionFixtureTest.kt 的 FIXTURE 逐字一致，由该测试锁住）
SESSION_JSON='{"format":"anytouch.recorder.session","version":1,"targetPkg":"com.android.settings","state":"STOPPED","overflowCount":0,"events":[{"type":"window","pkg":"com.android.settings","windowTitle":"session-open","timestampMs":1700000001000},{"type":"action","kind":"CLICK","text":null,"confirmed":true,"timestampMs":1700000002000,"snapshot":{"resourceId":null,"text":"Connected devices","contentDesc":null,"className":"android.widget.TextView","pkg":"com.android.settings","indexPath":[0,1,2]}},{"type":"action","kind":"CLICK","text":null,"confirmed":true,"timestampMs":1700000003000,"snapshot":{"resourceId":null,"text":"Connection preferences","contentDesc":null,"className":"android.widget.TextView","pkg":"com.android.settings","indexPath":[0,1,2,0]}},{"type":"action","kind":"CLICK","text":null,"confirmed":true,"timestampMs":1700000004000,"snapshot":{"resourceId":null,"text":"Bluetooth","contentDesc":null,"className":"android.widget.TextView","pkg":"com.android.settings","indexPath":[0,1,2,1]}}],"rejectedEvents":[]}'

wait_service_bound() {
    local i=0
    while [ "$i" -lt 20 ]; do
        MSYS_NO_PATHCONV=1 $ADB shell dumpsys accessibility 2>/dev/null | grep -q "com.anytouch.app" && return 0
        sleep 1; i=$((i + 1))
    done
    return 1
}

# 等一条日志出现（$1=固定串，$2=秒数）
wait_line() {
    local pat="$1" limit="${2:-15}" i=0 out=""
    while [ "$i" -lt "$limit" ]; do
        out=$(MSYS_NO_PATHCONV=1 $ADB logcat -d -s AnytouchRun:* 2>/dev/null | grep -a "$pat" | tail -1 || true)
        [ -n "$out" ] && { printf '%s' "$out"; return 0; }
        sleep 1; i=$((i + 1))
    done
    return 1
}

logs() { MSYS_NO_PATHCONV=1 $ADB logcat -d -s AnytouchRun:* 2>/dev/null; }

count_line() { logs | grep -ac "$1" || true; }

# 最近一次 S2SMOKE-TASK 的任务 JSON（步序账序列化出的那份，也是任务框里的那份）
latest_task() {
    local line
    line=$(logs | grep -a "S2SMOKE-TASK " | tail -1 || true)
    printf '%s' "${line#*S2SMOKE-TASK }"
}

# 送一条注入（keep_fg=true：UI 留在前台，后面的 dump 才看得见步序账）
inject() {
    MSYS_NO_PATHCONV=1 $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --ez keep_fg true $*" >/dev/null 2>&1
    sleep 2
}

# UI 面读数（dump 后必复绑）：命中打印 1，未命中 0
ui_has() {
    local pat="$1" xml=""
    MSYS_NO_PATHCONV=1 $ADB shell uiautomator dump /sdcard/.uismoke.xml >/dev/null 2>&1 || true
    xml=$(MSYS_NO_PATHCONV=1 $ADB shell cat /sdcard/.uismoke.xml 2>/dev/null | tr -d '\r' || true)
    MSYS_NO_PATHCONV=1 $ADB shell rm /sdcard/.uismoke.xml >/dev/null 2>&1
    wait_service_bound || log "dump 后服务未回绑（本条读数仍可用，但下一条须自查）"
    printf '%s' "$xml" | grep -aq "$pat" && echo 1 || echo 0
}

# 断言一条编辑日志出现
assert_edit() {
    local name="$1" expect="$2" line
    line=$(wait_line "$expect" 12)
    if [ -n "$line" ]; then pass "$name :: $line"; else
        bad "$name :: 期望日志含 [$expect]，实际无"
        log "近期编辑日志: $(logs | grep -a 'step edit' | tail -3 | tr '\n' '~')"
    fi
}

# 断言一条编辑日志**不**出现（负例半边：被拒不等于悄悄放行）
assert_no_edit() {
    local name="$1" pat="$2" n
    n=$(count_line "$pat")
    if [ "$n" = "0" ]; then pass "$name（[$pat] 计数=0）"; else bad "$name :: [$pat] 出现 $n 次"; fi
}

# ---------- 前置 ----------
devices=$(MSYS_NO_PATHCONV=1 adb devices | grep -c "device$" || true)
if [ "$devices" -lt 1 ]; then echo "前置失败：无 adb 设备"; exit 2; fi
if [ "$devices" -gt 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then
    echo "前置失败：接了 $devices 台设备，须 export ANDROID_SERIAL=<serial>"; exit 2
fi
if ! MSYS_NO_PATHCONV=1 $ADB shell dumpsys accessibility | grep -q "com.anytouch.app"; then
    echo "前置失败：无障碍服务未绑定"; exit 2
fi
MSYS_NO_PATHCONV=1 $ADB logcat -c >/dev/null 2>&1

# ---------- U1 编译产物上屏：3 行 + 行内三操作齐（不 dump 就不知道"账上有、屏上没有"） ----------
inject "--es session_json '$SESSION_JSON'"
if ! wait_line "S2SMOKE record inject accepted" 12 >/dev/null; then
    bad "U1 前置：预置会话未受理"; echo "ui-smoke: 前置不满足，后续不判"; exit 2
fi
inject "--ez record_stop true"
u1=$(wait_line "S2SMOKE compiled ok actions=3" 20)
if [ -n "$u1" ]; then pass "U1a 预置档编译 3 步 :: $u1"; else bad "U1a 预置档编译 3 步 :: 期望 [compiled ok actions=3]"; fi
rows=$(ui_has 'step_delete_2')
cnt=$(ui_has '步骤 3')
if [ "$rows" = "1" ] && [ "$cnt" = "1" ]; then
    pass "U1b 步序账上屏：末行按钮在 + 计数文案'步骤 3'在（行 id 走 testTag）"
else
    bad "U1b 步序账上屏 :: 末行按钮=$rows 计数文案=$cnt（期望 1/1）"
fi

# ---------- U2 移序：0→2 再 2→0 还原（顺序账可逆=移序不夹带副作用） ----------
inject "--es step_move 0 --es step_move_to 2"
assert_edit "U2a 移序 0→2 生效" "step edit ok=move index=0 before=3 after=3"
order=$(latest_task | grep -ao '"action_id":"[^"]*"' | sed 's/.*:"//; s/"$//' | tr '\n' ' ')
[ "$order" = "rec-0002 rec-0003 rec-0001 " ] && pass "U2b 移序后步序账顺序=[$order]" \
    || bad "U2b 移序后顺序异常 :: [$order]（期望 rec-0002 rec-0003 rec-0001）"
inject "--es step_move 2 --es step_move_to 0"
assert_edit "U2c 移回 2→0 生效" "step edit ok=move index=2 before=3 after=3"
order=$(latest_task | grep -ao '"action_id":"[^"]*"' | sed 's/.*:"//; s/"$//' | tr '\n' ' ')
[ "$order" = "rec-0001 rec-0002 rec-0003 " ] && pass "U2d 还原后顺序=[$order]" \
    || bad "U2d 还原后顺序异常 :: [$order]（期望 rec-0001 rec-0002 rec-0003）"

# ---------- U3 改名：只动 actionId，定位线索一条不丢 ----------
inject "--es step_rename 0 --es step_rename_to u3renamed"
assert_edit "U3a 改名生效" "step edit ok=rename index=0"
json=$(latest_task)
if printf '%s' "$json" | grep -aq 'u3renamed' &&
   printf '%s' "$json" | grep -aq 'Connected devices' &&
   printf '%s' "$json" | grep -aq 'Connection preferences' &&
   printf '%s' "$json" | grep -aq 'Bluetooth'; then
    pass "U3b 改名只改 actionId，三条线索原样在账（回放依据没被动）"
else
    bad "U3b 改名副作用 :: 新名或某条线索不在任务 JSON 里 :: $json"
fi

# ---------- U4 负例：空名必拒（注入通道不等于放行） ----------
inject "--es step_rename 0 --es step_rename_to ''"
assert_edit "U4a 空步骤名被拒 gate=BLANK_NAME" "step edit refused gate=BLANK_NAME"
# 成功改名必须**只有 U3 那一次**（空名这次不得被放行）
renamed=$(count_line 'step edit ok=rename index=0')
if [ "${renamed:-0}" = "1" ]; then pass "U4b ok=rename 计数仍为 1（空名未放行）"; else
    bad "U4b ok=rename 计数=$renamed（期望恒为 1）"
fi

# ---------- U5 负例：越界必拒且不崩（旧下标句柄=重组合竞态的真实形态） ----------
inject "--es step_remove 99"
assert_edit "U5a 越界删除被拒 gate=OUT_OF_RANGE" "step edit refused gate=OUT_OF_RANGE"
inject "--es step_move 0 --es step_move_to 99"
assert_edit "U5b 移序目标位越界被拒 gate=OUT_OF_RANGE" "step edit refused gate=OUT_OF_RANGE"
if [ -n "$(MSYS_NO_PATHCONV=1 $ADB shell pidof com.anytouch.app | tr -d '\r')" ]; then
    pass "U5c 被拒不崩：进程仍在（pid=$(MSYS_NO_PATHCONV=1 $ADB shell pidof com.anytouch.app | tr -d '\r')）"
else
    bad "U5c 被拒后进程没了：门禁漏到冻结原语的 require，崩在 UI 线程=无痕丢失"
fi
assert_no_edit "U5d 越界两步均未放行" "step edit ok=remove"

# ---------- U6 UI 同步：删掉的行真从屏上撤下（删的是末步 Bluetooth，留 2 步仍可放） ----------
inject "--es step_remove 2"
assert_edit "U6a 删步生效 3→2" "step edit ok=remove index=2 before=3 after=2"
gone=$(ui_has 'step_delete_2')
still=$(ui_has 'step_delete_1')
cnt2=$(ui_has '步骤 2')
if [ "$gone" = "0" ] && [ "$still" = "1" ] && [ "$cnt2" = "1" ]; then
    pass "U6b UI 与账目同步：第 3 行撤下、第 2 行仍在、计数文案改口"
else
    bad "U6b UI 与账目不同步 :: 旧末行残留=$gone 现有行=$still 计数=$cnt2（期望 0/1/1）"
fi

# ---------- U7 编辑过的账真进回放：删掉的步骤不再执行（步数账 2 不是 3） ----------
stop_settings_ui() {
    MSYS_NO_PATHCONV=1 $ADB shell am force-stop com.android.settings >/dev/null 2>&1
    MSYS_NO_PATHCONV=1 $ADB shell am force-stop com.google.android.settings.intelligence >/dev/null 2>&1
}
json=$(latest_task)
steps_now=$(printf '%s' "$json" | grep -ao '"type":"click"' | wc -l | tr -d ' ')
if [ "$steps_now" = "2" ]; then pass "U7a 取到编辑后任务 JSON 恰 2 步"; else
    bad "U7a 编辑后任务 JSON 步数=$steps_now（期望 2） :: $json"
fi
stop_settings_ui
MSYS_NO_PATHCONV=1 $ADB shell am start -n com.android.settings/.Settings >/dev/null 2>&1
sleep 8
MSYS_NO_PATHCONV=1 $ADB logcat -c >/dev/null 2>&1
MSYS_NO_PATHCONV=1 $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '$json'" >/dev/null 2>&1
r7=$(wait_line "S1SMOKE ok=" 60)
if printf '%s' "$r7" | grep -aq 'ok=2 total=2 stopped=false'; then
    pass "U7b 编辑后回放按新步数执行（删掉的那步没跑） :: $r7"
else
    bad "U7b 编辑后回放 :: 期望 [ok=2 total=2 stopped=false]，实际 [$r7]"
    log "$(logs | grep -a 'S1SMOKE-DETAIL' | tail -2 | tr '\n' '~')"
fi

# ---------- U8 删到清零：账目清零但不写空任务建议（空任务=假绿形态） ----------
inject "--es step_remove 0"
inject "--es step_remove 0"
cleared=$(wait_line "步序账清零" 12)
if [ -n "$cleared" ]; then pass "U8a 删到清零留痕 :: $cleared"; else bad "U8a 删到清零 :: 期望日志含 [步序账清零]"; fi
assert_no_edit "U8b 空账未伪装成品：本轮零条 'S2SMOKE-TASK []'" "S2SMOKE-TASK \[\]"
empty_ui=$(ui_has '步序账为空')
if [ "$empty_ui" = "1" ]; then pass "U8c 屏上同步为空态文案"; else bad "U8c 屏上仍挂旧步骤（账已清、屏未清=第二套账）"; fi

# ---------- U9 空账上再编辑必拒 EMPTY_LEDGER（唯一写口不给空账留缝） ----------
before=$(count_line 'step edit refused gate=EMPTY_LEDGER')
inject "--es step_remove 0"
assert_edit "U9a 空账编辑被拒 gate=EMPTY_LEDGER" "step edit refused gate=EMPTY_LEDGER"
after=$(count_line 'step edit refused gate=EMPTY_LEDGER')
if [ "${before:-0}" = "0" ] && [ "${after:-0}" -ge "1" ]; then
    pass "U9b 拒因计数 0→$after（本轮新造，非旧痕复用）"
else
    bad "U9b 拒因计数异常 :: before=$before after=$after"
fi

# ---------- U10 V-3 准入：孤儿建议必拒（账=0 步，框内还挂着机器上一次发布的建议） ----------
# 走到这里屏上正好是 V-3 的实证形态：U8 把账删到清零，而 U8 中途发布的那条 1 步建议仍是框里那份。
stale=$(latest_task)
stale_steps=$(printf '%s' "$stale" | grep -ao '"type":"click"' | wc -l | tr -d ' ')
if [ "$stale_steps" = "1" ]; then pass "U10a 前置：机器最后一条建议恰 1 步（账已 0 步=孤儿形态）"
else bad "U10a 前置 :: 最后一条建议步数=$stale_steps（期望 1） :: $stale"; fi
MSYS_NO_PATHCONV=1 $ADB logcat -c >/dev/null 2>&1
inject "--es task_json '$stale'"
assert_edit "U10b 孤儿建议被拒留痕" "submit refused gate=STALE_SUGGESTION"
refused=$(count_line 'submit refused gate=STALE_SUGGESTION')
execs=$(count_line 'S1SMOKE ok=')
if [ "${refused:-0}" -ge 1 ] && [ "$execs" = "0" ]; then
    pass "U10c 拒而未放：refused=$refused 且本轮零执行回执（账外步骤没被跑）"
else
    bad "U10c :: refused=$refused 执行回执=$execs（期望 ≥1 / 0）"
    log "$(logs | grep -a -E 'S1SMOKE' | tail -3 | tr '\n' '~')"
fi
u10d=$(ui_has 'task_rejection')
if [ "$u10d" = "1" ]; then pass "U10d 拒因上屏（错误必显示，不是静默吞掉一次点击）"; else bad "U10d 拒因未上屏 :: task_rejection 读数=$u10d"; fi

# ---------- U11 准入的反面：用户手敲的 JSON 照常执行（不夺字，S1 主路径不许被误伤） ----------
SAFE='"safety":{"viewport_ok":true,"click_enabled":true,"requires_transition":false}'
HAND="[{\"action_id\":\"u11\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"$TXT_CONNECTED\"},$SAFE}]"
stop_settings_ui
MSYS_NO_PATHCONV=1 $ADB shell am start -n com.android.settings/.Settings >/dev/null 2>&1
sleep 8
MSYS_NO_PATHCONV=1 $ADB logcat -c >/dev/null 2>&1
inject "--es task_json '$HAND'"
r11=$(wait_line "S1SMOKE ok=" 60)
if printf '%s' "$r11" | grep -aq 'ok=1 total=1'; then
    pass "U11a 账外手敲任务仍放行并跑成 :: $r11"
else
    bad "U11a :: 期望 [ok=1 total=1]，实际 [$r11]"
    log "$(logs | grep -a 'S1SMOKE-DETAIL' | tail -2 | tr '\n' '~')"
fi
# dump 读数互控：同一对模式 U10d 读到 1、这里读到 0（放行后红字必须撤，读数器不能只会命中）
# 必须先显式回自家前台：那一下点击发生在 Settings 窗口里，导航把 Settings 抬到了前面，
# 直接 dump 读到的是别人的窗（首轮实测 自家窗=0——0 在这里不是"红字没了"，是"没在读自家窗"）。
MSYS_NO_PATHCONV=1 $ADB shell am start -n com.anytouch.app/.MainActivity >/dev/null 2>&1
sleep 2
u11b=$(ui_has 'run_task')
u11c=$(ui_has 'task_rejection')
if [ "$u11b" = "1" ] && [ "$u11c" = "0" ]; then
    pass "U11b 放行后拒因撤下（读到自家窗=$u11b，红字=$u11c）"
else
    bad "U11b :: 自家窗=$u11b（期望 1，否则 0 是读错窗口的假绿） 红字=$u11c（期望 0）"
fi

# ---------- U12 V-2 过期边：执行中拒录话术，跑完必须自动作废（空闲态不许挂着"执行中不能开录"） ----------
LONG="[{\"action_id\":\"u12a\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"__no_such_node_ui_smoke__\"},$SAFE}]"
MSYS_NO_PATHCONV=1 $ADB logcat -c >/dev/null 2>&1
MSYS_NO_PATHCONV=1 $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '$LONG'" >/dev/null 2>&1
sleep 3   # 落在 15s 定位轮询窗口内（device-smoke C10 同一配方）
MSYS_NO_PATHCONV=1 $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --es record_start com.android.settings" >/dev/null 2>&1
sleep 2
assert_edit "U12a 执行中开录被拒（话术入 startRejection）" "record start refused gate=RUNNING"
r12=$(wait_line "S1SMOKE ok=0 total=1" 60)
if [ -n "$r12" ]; then pass "U12b 本轮执行结束（running 转假） :: $r12"; else bad "U12b :: 等不到执行结束回执"; fi
expired=$(wait_line "record rejection expired" 20)
if [ -n "$expired" ]; then pass "U12c 陈旧拒因自动作废 :: $expired"; else
    bad "U12c :: 期望日志含 [record rejection expired]，实际无（红字仍挂在空闲态=V-2 复发）"
    log "$(logs | grep -a -E 'record (start|rejection)' | tail -3 | tr '\n' '~')"
fi
MSYS_NO_PATHCONV=1 $ADB shell am start -n com.anytouch.app/.MainActivity >/dev/null 2>&1
sleep 2
u12d=$(ui_has 'run_task')
u12e=$(ui_has 'record_rejection')
if [ "$u12d" = "1" ] && [ "$u12e" = "0" ]; then
    pass "U12d 屏上红字确已消失（自家窗=$u12d 读数器可用，红字=$u12e）"
else
    bad "U12d :: 自家窗=$u12d（期望 1） 红字=$u12e（期望 0）"
fi

# ---------- U13 V-1 球位：录制球必须在右缘（左缘会压住步骤名框与拒因红字首字） ----------
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
PY=$(command -v python3 || command -v python || true)
if [ -z "$PY" ]; then
    log "U13 跳过：无 python（球位像素判据需要 PIL）"
else
    MSYS_NO_PATHCONV=1 $ADB shell screencap -p /sdcard/.uiball.png >/dev/null 2>&1
    MSYS_NO_PATHCONV=1 $ADB pull /sdcard/.uiball.png uismoke-ball.png >/dev/null 2>&1
    MSYS_NO_PATHCONV=1 $ADB shell rm /sdcard/.uiball.png >/dev/null 2>&1
    ball=$("$PY" "$SCRIPT_DIR/ball_position.py" uismoke-ball.png 2>&1); ball_rc=$?
    ratio=$(printf '%s' "$ball" | sed -n 's/.*BALL_X_RATIO=\([0-9.]*\).*/\1/p')
    if [ "$ball_rc" -eq 0 ] && [ -n "$ratio" ] && awk -v r="$ratio" 'BEGIN{ exit !(r > 0.75) }'; then
        pass "U13 录制球在右缘 :: $ball"
    else
        bad "U13 球位 :: $ball（期望中心横占比 >0.75，左缘旧值约 0.11）"
    fi
    rm -f uismoke-ball.png
fi

echo
if [ "$fail" -eq 0 ]; then echo "ui-smoke: ALL PASS"; else echo "ui-smoke: 有失败项"; fi
exit "$fail"
