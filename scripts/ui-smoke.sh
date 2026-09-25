#!/usr/bin/env bash
# Anytouch 录制 UI 面设备冒烟（U 系列）：步序账「屏上渲染 ↔ 唯一写口 ↔ 回放真值」三方同步 + 编辑门禁 fail-closed。
# 用法：bash scripts/ui-smoke.sh        （需恰好 1 台 adb 设备、装好本轮 debug APK、无障碍已绑）
#        多设备/真机：export ANDROID_SERIAL=<serial>；中文 ROM 用 device-smoke 那套 TXT_* 变量。
# 为什么单开一个脚本：device-smoke.sh 的 13 项是 C 系列回归锁，混编会把"新面未验"混进旧账基线。
# 预置会话档 = 冻结 RecorderSession.serialize 的真产物（逐字锁在 UiSmokeSessionFixtureTest，
# 脚本内不手抄近似值）；UI 面用例不走录制通道（不需要球、零人工手指坐标）。
# 洁净纪律：uiautomator dump 只用于"读 UI 是否同步"，每次 dump 后一律 wait_service_bound 复绑
# （设备实证：dump 注册 UiTestAutomationService 会挤掉自家服务，不复绑则下一条用例必假红）。
# 页读纪律（09-24 主窗补，起因=切片 D 立起 BYOK 面板后 U6b/U11b/U12d/U15c 一族转红）：
# 自家主屏是一列 Compose，**折叠线以下的节点不进无障碍树**，所以"单屏 dump"两个方向都不可信
# （存在性读不到=假红、缺席读不到=假绿）。读数一律走 ui_sweep：先正向确认回到首屏（target_pkg 读到），
# 再逐屏往下扫到"连续两屏内容签名相同"才算看完；没扫到底时"缺席"一侧记 SKIP，不记绿也不记红。
set -u

ADB="adb"
[ -n "${ANDROID_SERIAL:-}" ] && ADB="adb -s ${ANDROID_SERIAL}"

# 词表（真机中文 ROM 用环境变量覆盖，与 device-smoke 同一口径）
TXT_CONNECTED="${TXT_CONNECTED:-Connected devices}"
# 自家包名：dump 里每个自家节点都带 package="…"，用它坐实"这一屏读的是自家窗"（与被断言的 testTag 无关）
OWN_PKG="${OWN_PKG:-com.anytouch.app}"

fail=0
passed=0
skipped=0
RED='\033[0;31m'; GRN='\033[0;32m'; NCT='\033[0m'
pass() { printf "${GRN}PASS${NCT} %s\n" "$1"; passed=$((passed + 1)); }
bad()  { printf "${RED}FAIL${NCT} %s\n" "$1"; fail=1; }
log()  { printf '      | %s\n' "$*"; }
# 前置没立住的读数不记红、也不记绿：跳过必须显形，不许悄悄算通过。
skip() { log "SKIP $*"; skipped=$((skipped + 1)); }

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
    SWEEP_DIRTY=1
}

# ============================================================================================
# 页读：一次扫全页 → 同一条 dump 底子上比多个模式（不"单屏读一次就下结论"）
# ============================================================================================
# 为什么改：自家主屏是一列 Compose，切片 D 把 BYOK 面板立起来之后页面变高，而
# **折叠线以下的节点压根不进无障碍树**（设备实证：首屏 dump 里 step_delete_*/run_task 全 0，
# 往下拖一屏才逐字出现；反过来 target_pkg 只在首屏可见）。于是"单屏 dump"两个方向都不可信：
# 存在性读不到 → 假红；缺席读不到 → 假绿（更贵的那一侧：它把"屏上没同步"判成"同步干净"）。
# 判据与 byok-smoke 的 harvest_page、device-smoke 的 wait_own_task_input 同一条纪律：
#  ① 回顶要正向证据：首屏独有的 target_pkg 读到才算在顶（封顶 TOP_TRIES，拖不到就带位开扫）；
#  ② 到底要正向证据：连续两屏**内容签名**相同 = 列表已到底（SWEEP_OK=1）；
#  ③ 没到底的扫视只支撑"命中"，不支撑"缺席"——缺席一侧记 SKIP，不记绿；
#  ④ 每次扫视先坐实"读的是自家窗"（缓冲里至少一条 package="com.anytouch.app" 节点）：
#     一条都没有时 SWEEP_OK 直接压回 0，命中类断言记 SKIP 而非红（09-24 实测：同一构建同一脚本
#     两轮读数 0↔1 翻转，产品代码未动——那一轮的"0"是"没在读自家窗"，不许判产品的罪）。
TOP_TRIES=6
SCREEN_MAX=6
SWEEP_BUF=""
SWEEP_OK=0
SWEEP_DIRTY=1
SWEEP_GEOM=""
# 本扫视里"含自家窗节点"的屏数 / 总屏数：读数器健康的**独立**判据（见 ui_expect 上方注记）
SWEEP_OWN=0
SWEEP_SCREENS=0

dump_view() { # 一屏 dump → stdout；失败打印空串（绝不把上一屏的余货当这一屏）
    MSYS_NO_PATHCONV=1 $ADB shell uiautomator dump /sdcard/.uismoke.xml >/dev/null 2>&1 || true
    MSYS_NO_PATHCONV=1 $ADB shell cat /sdcard/.uismoke.xml 2>/dev/null | tr -d '\r' || true
    MSYS_NO_PATHCONV=1 $ADB shell rm /sdcard/.uismoke.xml >/dev/null 2>&1
    wait_service_bound || log "dump 后服务未回绑（本条读数仍可用，但下一条须自查）"
}

# $1=down（手指下滑=往页首走）/ up（手指上滑=往下翻页）
swipe_page() {
    local dir="$1" y1 y2
    [ -z "$SWEEP_GEOM" ] && SWEEP_GEOM=$(MSYS_NO_PATHCONV=1 $ADB shell wm size 2>/dev/null | tr -d '\r' | grep -o '[0-9]*x[0-9]*' | tail -1)
    local w=${SWEEP_GEOM%x*} h=${SWEEP_GEOM#*x}
    [ -z "$w" ] || [ -z "$h" ] && { log "屏宽高的读数不可用 [$SWEEP_GEOM]：本页无法翻页"; return 1; }
    if [ "$dir" = "down" ]; then y1=$((h * 28 / 100)); y2=$((h * 72 / 100)); else y1=$((h * 72 / 100)); y2=$((h * 28 / 100)); fi
    MSYS_NO_PATHCONV=1 $ADB shell input swipe "$((w / 2))" "$y1" "$((w / 2))" "$y2" 300 >/dev/null 2>&1
    sleep 1
}

page_sig() { # 一屏的"内容签名"：只取 text 与 resource-id 两个集合（排序去重）。
    # 为什么不逐字比 XML：树里还带着 bounds/focused/光标一类的抖动，逐字比对在两屏之间几乎永不相等，
    # "到底"就永远判不出来（09-24 首版即此：U6b 因扫不到底只能记 SKIP）。
    # 为什么签名够用：这里只扫**自家主屏**，步骤行 id 带下标、文案逐行不同，
    # 再翻一屏必然带来集合变化；签名不变 = 这一屏没翻出新内容 = 到底。
    printf '%s' "$1" | grep -ao 'resource-id="[^"]*"\|text="[^"]*"' | sort -u | tr '\n' '|'
}

ui_sweep() {
    # 两个预算各自独立计次：回顶用掉的次数原先从到底的额度里扣，起始位置越靠页尾剩余额度越少，
    # "到底"就越判不出来（09-24 首轮 U6b 正是只能记 SKIP；真因未坐实，故这里补一条"没到底时
    # 把翻了几屏打出来"的日志——下次要能归因，而不是再来一次无据 SKIP）。
    local j=0 i=0 prev="" cur="" prev_sig="" cur_sig=""
    SWEEP_BUF=""; SWEEP_OK=0; SWEEP_DIRTY=0; SWEEP_OWN=0; SWEEP_SCREENS=0
    while [ "$j" -lt "$TOP_TRIES" ]; do
        cur=$(dump_view)
        printf '%s' "$cur" | grep -aq 'resource-id="target_pkg"' && break
        swipe_page down || break
        j=$((j + 1))
    done
    if [ "$j" -ge "$TOP_TRIES" ]; then
        printf '%s' "$cur" | grep -aq 'resource-id="target_pkg"' || log "回顶未坐实（$TOP_TRIES 次下滑仍没读到首屏独有的 target_pkg）：本扫视只支撑命中"
    fi
    prev_sig=""
    while [ "$i" -lt "$SCREEN_MAX" ]; do
        cur=$(dump_view)
        SWEEP_BUF="$SWEEP_BUF
$cur"
        SWEEP_SCREENS=$((SWEEP_SCREENS + 1))
        printf '%s' "$cur" | grep -aq "package=\"$OWN_PKG\"" && SWEEP_OWN=$((SWEEP_OWN + 1))
        cur_sig=$(page_sig "$cur")
        if [ -n "$prev_sig" ] && [ "$cur_sig" = "$prev_sig" ]; then SWEEP_OK=1; break; fi
        prev_sig="$cur_sig"
        swipe_page up || break
        i=$((i + 1))
    done
    # 一条自家窗节点都没读到的扫视，"到底"这个结论本身不成立（判"到底"的是别人的两屏）：
    # 直接压回 SWEEP_OK=0，让所有缺席类断言落到 SKIP 而不是绿。
    if [ "$SWEEP_OWN" = "0" ]; then
        SWEEP_OK=0
        log "本扫视 $SWEEP_SCREENS 屏里 0 屏含 package=\"$OWN_PKG\"：读的不是自家窗（窗被换走或还没画出来）"
    else
        [ "$SWEEP_OK" = "1" ] || log "扫视未到底：自顶起共翻 $i 屏仍无连续两屏签名相同（页长超出 SCREEN_MAX=$SCREEN_MAX 或签名持续抖动，自家窗 $SWEEP_OWN/$SWEEP_SCREENS 屏）"
    fi
}

# 命中 hit / 未命中 miss / 读数器不可用 skip（三档，09-24 加）
# 为什么必须有第三档：同一枚构建、同一份脚本，前一轮 U1b/U6b/U8c/U10d/U11b 五条全读 0、
# 后一轮五条全读 1，中间产品代码一行未改——那个"0"是"这一扫视里一条自家窗节点都没有"，
# 把它记成产品红＝拿未归因的读数定产品的罪（假红与假绿同罪）。
# 为什么判据用 package 而不是被断言的 id：U11b/U12d/U15c 原先拿 run_task 当"自家窗"代理，
# 被测项和读数器健康判据成了同一个数——run_task 真消失时会被误读成"没在读自家窗"而记 SKIP（把红洗成跳过）。
ui_expect() {
    [ "$SWEEP_DIRTY" = "1" ] && ui_sweep
    if [ "$SWEEP_OWN" = "0" ]; then echo skip; return; fi
    printf '%s' "$SWEEP_BUF" | grep -aq "$1" && echo hit || echo miss
}

# 命中 1 / 未命中 0（"未命中"能不能当证据，看 sweep_complete）
# 注意：本函数与 sweep_complete 都必须在**主 shell**里读到同一份缓存才有效——
# `x=$(ui_has …)` 是子 shell，里面 ui_sweep 填的 SWEEP_BUF/SWEEP_OK 带不回来（09-24 实测：
# U6b 因此**永远**只能记 SKIP，SWEEP_OK 在主 shell 里从没被置过 1）。
# 所以每个 ui_has 组之前，主 shell 先 ui_sweep 一次；子 shell 里的调用只读缓存，不再重复翻页。
ui_has() {
    [ "$SWEEP_DIRTY" = "1" ] && ui_sweep
    printf '%s' "$SWEEP_BUF" | grep -aq "$1" && echo 1 || echo 0
}

sweep_complete() { [ "$SWEEP_OK" = "1" ] && echo 1 || echo 0; }

# 读某一枚节点的属性值（$1=resource-id，$2=属性名）。为什么必须有：S5-d 的默认值与安全默认
# 都是"**这一枚**控件里的值"，整页 grep text="1" 会读到别人的节点（假绿），只 grep testTag 又读不到值。
# 只在**主 shell 刚 ui_sweep 过**之后调用（本函数不翻页，只读缓存）。读不到输出空串。
ui_tag_attr() {
    printf '%s' "$SWEEP_BUF" | grep -ao "<node[^>]*resource-id=\"$1\"[^>]*>" | head -1 \
        | grep -o "$2=\"[^\"]*\"" | head -1 | sed "s/$2=\"//;s/\"$//"
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
ui_sweep   # 主 shell 先扫一遍：下面的 ui_expect 都只读这份缓存（见 ui_has 上方那条子 shell 注记）
rows=$(ui_expect 'step_delete_2')
cnt=$(ui_expect 'Steps 3')
if [ "$rows" = "skip" ] || [ "$cnt" = "skip" ]; then
    skip "U1b 步序账上屏 :: 本扫视 $SWEEP_SCREENS 屏里 0 屏含自家窗节点（读的不是自家窗，命中与未命中都读不出证据）"
elif [ "$rows" = "hit" ] && [ "$cnt" = "hit" ]; then
    pass "U1b 步序账上屏：末行按钮在 + 计数文案'步骤 3'在（行 id 走 testTag，自家窗 $SWEEP_OWN/$SWEEP_SCREENS 屏）"
else
    bad "U1b 步序账上屏 :: 末行按钮=$rows 计数文案=$cnt（期望 hit/hit）"
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
ui_sweep   # 必须发生在主 shell：SWEEP_OK 只能由主 shell 的那一次扫视置起来（子 shell 里的扫视带不回结论）
gone=$(ui_has 'step_delete_2')
still=$(ui_has 'step_delete_1')
cnt2=$(ui_has 'Steps 2')
sure=$(sweep_complete)
if [ "$sure" != "1" ]; then
    skip "U6b UI 与账目同步 :: 页没扫到底（连续两屏内容签名未相同；自家窗 $SWEEP_OWN/$SWEEP_SCREENS 屏），\"第 3 行不在屏上\"这条缺席读不出证据"
elif [ "$gone" = "0" ] && [ "$still" = "1" ] && [ "$cnt2" = "1" ]; then
    pass "U6b UI 与账目同步：第 3 行撤下、第 2 行仍在、计数文案改口（扫到底后全页比对）"
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
ui_sweep
empty_ui=$(ui_expect 'step ledger is empty')
if [ "$empty_ui" = "skip" ]; then
    skip "U8c 屏上同步为空态文案 :: 本扫视 $SWEEP_SCREENS 屏里 0 屏含自家窗节点（读的不是自家窗）"
elif [ "$empty_ui" = "hit" ]; then pass "U8c 屏上同步为空态文案"; else bad "U8c 屏上仍挂旧步骤（账已清、屏未清=第二套账）:: 空态文案读数=$empty_ui"; fi

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
ui_sweep
u10d=$(ui_expect 'task_rejection')
if [ "$u10d" = "skip" ]; then
    skip "U10d 拒因上屏 :: 本扫视 $SWEEP_SCREENS 屏里 0 屏含自家窗节点（读的不是自家窗）"
elif [ "$u10d" = "hit" ]; then pass "U10d 拒因上屏（错误必显示，不是静默吞掉一次点击）"; else bad "U10d 拒因未上屏 :: task_rejection 读数=$u10d"; fi

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
ui_sweep
u11b=$(ui_expect 'run_task')
u11c=$(ui_expect 'task_rejection')
sure=$(sweep_complete)
if [ "$u11b" = "skip" ]; then
    skip "U11b :: 本扫视 $SWEEP_SCREENS 屏里 0 屏含自家窗节点（读的不是自家窗或窗已换，命中与缺席都读不出证据）"
elif [ "$u11b" != "hit" ]; then
    bad "U11b :: run_task=$u11b（期望 hit：自家窗已坐实却读不到 run_task=屏上真没有）"
elif [ "$sure" != "1" ]; then
    skip "U11b 放行后拒因撤下 :: 页没扫到底，\"红字不在屏上\"这条缺席读不出证据（自家窗 $SWEEP_OWN/$SWEEP_SCREENS 屏）"
elif [ "$u11c" = "miss" ]; then
    pass "U11b 放行后拒因撤下（自家窗 $SWEEP_OWN/$SWEEP_SCREENS 屏，红字=$u11c，全页扫到底）"
else
    bad "U11b :: 红字=$u11c（期望 miss：放行后红字必须撤）"
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
ui_sweep
u12d=$(ui_expect 'run_task')
u12e=$(ui_expect 'record_rejection')
sure=$(sweep_complete)
if [ "$u12d" = "skip" ]; then
    skip "U12d :: 本扫视 $SWEEP_SCREENS 屏里 0 屏含自家窗节点（读的不是自家窗）"
elif [ "$u12d" != "hit" ]; then
    bad "U12d :: run_task=$u12d（期望 hit：自家窗已坐实却读不到 run_task=屏上真没有）"
elif [ "$sure" != "1" ]; then
    skip "U12d 屏上红字确已消失 :: 页没扫到底，\"红字不在屏上\"这条缺席读不出证据（自家窗 $SWEEP_OWN/$SWEEP_SCREENS 屏）"
elif [ "$u12e" = "miss" ]; then
    pass "U12d 屏上红字确已消失（自家窗 $SWEEP_OWN/$SWEEP_SCREENS 屏，红字=$u12e，全页扫到底）"
else
    bad "U12d :: 红字=$u12e（期望 miss：空闲态不许挂着\"执行中不能开录\"）"
fi

# ---------- U13 V-1 球位：录制球必须在右缘（左缘会压住步骤名框与拒因红字首字） ----------
# 前置自复核（主窗 09-24 补）：球只在**录制会话活着**的时候挂屏，而 U 系列前面全走注入通道、
# 从不真开录——于是旧口径下这条判据读的是"根本没有球的屏"：命中不到就判红（假红），
# 偶发撞上一个残留会话时又判绿（假绿）。两头都不是产品事实。改成先真开一次录制、
# 用 `record start target=` 这条日志坐实球该在屏上，再截屏量像素；用完立刻停录（U14 自带重建账的前置）。
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
PY=$(command -v python3 || command -v python || true)
if [ -z "$PY" ]; then
    skip "U13 球位：无 python（像素判据需要 PIL）"
else
    MSYS_NO_PATHCONV=1 $ADB logcat -c >/dev/null 2>&1
    inject "--es record_start com.android.settings"
    ball_up=$(wait_line "S2SMOKE record start target=com.android.settings" 15)
    if [ -z "$ball_up" ]; then
        skip "U13 球位：前置未立——没拿到 [record start target=…] 回执，球本就不该在屏上（不拿空屏判红也不判绿）"
    else
        sleep 2   # 球是 Overlay 窗口，等它挂上再截
        MSYS_NO_PATHCONV=1 $ADB shell screencap -p /sdcard/.uiball.png >/dev/null 2>&1
        MSYS_NO_PATHCONV=1 $ADB pull /sdcard/.uiball.png uismoke-ball.png >/dev/null 2>&1
        MSYS_NO_PATHCONV=1 $ADB shell rm /sdcard/.uiball.png >/dev/null 2>&1
        ball=$("$PY" "$SCRIPT_DIR/ball_position.py" uismoke-ball.png 2>&1); ball_rc=$?
        ratio=$(printf '%s' "$ball" | sed -n 's/.*BALL_X_RATIO=\([0-9.]*\).*/\1/p')
        state=$(printf '%s' "$ball" | sed -n 's/.*BALL_STATE=\([a-z]*\).*/\1/p')
        if [ "$ball_rc" -ne 0 ]; then
            bad "U13 :: 已在录制（$(printf '%s' "$ball_up" | sed 's/.*AnytouchRun: //')）却截不到任何一档球色 :: $ball"
        elif [ "$state" != "recording" ]; then
            bad "U13 :: 状态灯与前置对不上——脚本已坐实在录制，屏上读到的却是 BALL_STATE=$state :: $ball（要么球没随状态换色，要么这张截图不是这一档）"
        elif awk -v r="$ratio" 'BEGIN{ exit !(r > 0.75) }'; then
            pass "U13 录制球在右缘（录制态深红球，前置已坐实） :: $ball"
        else
            bad "U13 球位 :: $ball（期望中心横占比 >0.75，左缘旧值约 0.11）"
        fi
        rm -f uismoke-ball.png
        inject "--ez record_stop true"
        wait_line "S2SMOKE compiled" 20 >/dev/null || log "U13 收尾：停录后没等到 compiled 回执（U14 会自建 3 步账，不影响后续判定）"
    fi
fi

# ---------- U14 执行中禁编辑（老板 09-23 裁决：门禁落入口，注入通道绕过置灰按钮同样被拒） ----------
# 前置：账上要有步骤（U8 已清零，这里重新编译一份 3 步），并让本轮执行有足够长的定位窗口。
# 先清日志再判"本轮真的编出了 3 步"——不清的话 wait_line 会命中 U1a 那条旧痕，前置就成了假绿。
MSYS_NO_PATHCONV=1 $ADB logcat -c >/dev/null 2>&1
inject "--es session_json '$SESSION_JSON'"
if [ -z "$(wait_line "S2SMOKE record inject accepted" 15)" ]; then
    bad "U14 前置：预置会话未受理，后续不判"; echo "ui-smoke: 前置不满足，后续不判"; exit 2
fi
inject "--ez record_stop true"
if [ -z "$(wait_line 'S2SMOKE compiled ok actions=3' 25)" ]; then
    bad "U14 前置：步序账没重建出 3 步，后续不判"; echo "ui-smoke: 前置不满足，后续不判"; exit 2
fi
# 两步"必然找不到节点"的手敲任务：每步 15s 定位轮询 → 约 30s 执行窗口（走 U11a 同一条手敲放行路径）
LONG2="[{\"action_id\":\"u14a\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"__no_such_node_ui_smoke__\"},$SAFE},{\"action_id\":\"u14b\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"__no_such_node_ui_smoke__\"},$SAFE}]"
MSYS_NO_PATHCONV=1 $ADB logcat -c >/dev/null 2>&1
MSYS_NO_PATHCONV=1 $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '$LONG2'" >/dev/null 2>&1
sleep 3
inject "--es step_remove 0"
refused_line=$(wait_line "step edit refused gate=RUNNING" 12)
if [ -n "$refused_line" ]; then pass "U14a 执行中删步被拒 gate=RUNNING :: $refused_line"; else
    bad "U14a :: 期望日志含 [step edit refused gate=RUNNING]，实际无"
fi
during=$(count_line 'step edit ok=remove')
if [ "${during:-0}" = "0" ]; then
    pass "U14b 执行期零条编辑放行（置灰不是门禁，注入通道同判）"
else
    bad "U14b :: 本轮 ok=remove 计数=$during（期望 0）"
fi
# 账没被动，正证取拒因行自带的 ledger= 字段（本段起点清过日志，S2SMOKE-TASK 回读会是空串=假红）
ledger_at_refusal=$(printf '%s' "$refused_line" | sed -n 's/.*ledger=\([0-9]*\).*/\1/p')
if [ "$ledger_at_refusal" = "3" ]; then pass "U14c 门禁读到的是真账：拒因行 ledger=3（步骤没被删掉）"; else
    bad "U14c :: 拒因行 ledger=[$ledger_at_refusal]（期望 3）"
fi
# 本段**故意不做 uiautomator dump**：设备实证 dump 注册 UiTestAutomationService 会挤掉自家服务、
# 连带把在跑的 runTask 取消（SERVICE_INTERRUPTED），"读屏"本身就成了被测量者的扰动源。
# 执行期屏上红字与置灰提示改由人眼图取证（evidence/S2/img/v4-running-edit-locked.png），
# 屏上"消失"那一半仍可机器断言——放在 U15c，那时已无在跑任务，dump 无害。

# ---------- U15 过期边：跑完 RUNNING 话术必须自动作废，且同一操作立刻恢复可编 ----------
ended=$(wait_line "S1SMOKE ok=" 90)
if [ -n "$ended" ]; then pass "U15a 本轮执行结束 :: $ended"; else bad "U15a :: 等不到执行结束回执"; fi
expired=$(wait_line "edit rejection expired gate_was=RUNNING" 20)
if [ -n "$expired" ]; then pass "U15b 陈旧禁编辑话术自动作废 :: $expired"; else
    bad "U15b :: 期望日志含 [edit rejection expired gate_was=RUNNING]，实际无（空闲态挂着 RUNNING=V-2 在编辑面复发）"
    log "$(logs | grep -a 'edit rejection' | tail -3 | tr '\n' '~')"
fi
MSYS_NO_PATHCONV=1 $ADB shell am start -n com.anytouch.app/.MainActivity >/dev/null 2>&1
sleep 2
ui_sweep
u15c=$(ui_expect 'run_task')
u15d=$(ui_expect 'step_edit_rejection')
u15e=$(ui_expect 'step_edit_locked_hint')
sure=$(sweep_complete)
if [ "$u15c" = "skip" ]; then
    skip "U15c :: 本扫视 $SWEEP_SCREENS 屏里 0 屏含自家窗节点（读的不是自家窗）"
elif [ "$u15c" != "hit" ]; then
    bad "U15c :: run_task=$u15c（期望 hit：自家窗已坐实却读不到 run_task=屏上真没有）"
elif [ "$sure" != "1" ]; then
    skip "U15c 屏上红字与置灰提示均已撤 :: 页没扫到底，两条\"不在屏上\"的缺席读不出证据（自家窗 $SWEEP_OWN/$SWEEP_SCREENS 屏）"
elif [ "$u15d" = "miss" ] && [ "$u15e" = "miss" ]; then
    pass "U15c 屏上红字与置灰提示均已撤（自家窗 $SWEEP_OWN/$SWEEP_SCREENS 屏，红字=$u15d 提示=$u15e，全页扫到底）"
else
    bad "U15c :: 红字=$u15d（期望 miss） 提示=$u15e（期望 miss）"
fi
inject "--es step_remove 0"
assert_edit "U15d 跑完立刻可编：同一请求转放行" "step edit ok=remove index=0 before=3 after=2"

# ---------- U16 S5-d 重复执行面：三枚控件在屏 + 默认值/安全默认 + 脏数字红字 + 单发报告字段缺席 ----------
# 为什么住 ui-smoke 而不是 s5d-repeat-loop-smoke.sh：这一族全是**屏幕侧**判据，只有本脚本的
# ui_sweep 会逐屏翻页扫到底；设备冒烟脚本单次 dump 看不见折叠线以下的节点，"读不到=没有"是假绿。
UWAITS="[{\"action_id\":\"u16a\",\"type\":\"wait\",\"source\":\"node\",\"value\":{\"ms\":300},$SAFE},{\"action_id\":\"u16b\",\"type\":\"wait\",\"source\":\"node\",\"value\":{\"ms\":300},$SAFE}]"
MSYS_NO_PATHCONV=1 $ADB shell am start -n com.anytouch.app/.MainActivity >/dev/null 2>&1
sleep 2
ui_sweep
u16a1=$(ui_expect 'resource-id="repeat_count"')
u16a2=$(ui_expect 'resource-id="repeat_interval"')
u16a3=$(ui_expect 'resource-id="repeat_no_ask"')
u16a4=$(ui_expect 'Repetitions (1-100)')
u16a5=$(ui_expect 'Interval seconds (1-60)')
u16a6=$(ui_expect 'a new kind of risk still asks')
if [ "$u16a1$u16a2$u16a3" = "skip" ]; then
    skip "U16a :: 本扫视 $SWEEP_SCREENS 屏里 0 屏含自家窗节点（读的不是自家窗）"
elif [ "$u16a1" = "hit" ] && [ "$u16a2" = "hit" ] && [ "$u16a3" = "hit" ]; then
    pass "U16a 重复执行三枚控件在屏（轮数框/间隔框/开关）"
else
    bad "U16a :: repeat_count=$u16a1 repeat_interval=$u16a2 repeat_no_ask=$u16a3（期望三枚 hit）"
fi
if [ "$u16a4" = "hit" ] && [ "$u16a5" = "hit" ] && [ "$u16a6" = "hit" ]; then
    pass "U16b 英文范围提示与开关解释同屏（军令 1 条的 1-100/1-60 写在屏上，不只写在代码里）"
else
    bad "U16b :: 范围提示/解释读数 reps=$u16a4 interval=$u16a5 explain=$u16a6（期望三枚 hit）"
fi
# 默认值与"未勾选"是**这一枚节点**的读数：整页 grep text="1" 会读到别人的节点＝假绿
d_reps=$(ui_tag_attr repeat_count text)
d_int=$(ui_tag_attr repeat_interval text)
d_chk=$(ui_tag_attr repeat_no_ask checked)
if [ "$d_reps" = "1" ] && [ "$d_int" = "5" ]; then
    pass "U16c 框内默认值 1 轮 / 5 秒（军令 1 条缺省档，逐字对上 RepeatPolicy.DEFAULT_*）"
else
    bad "U16c :: 默认读数 reps=[$d_reps] interval=[$d_int]（期望 1 / 5）"
fi
case "$d_chk" in
    false) pass "U16d 开关默认未勾选（安全默认：不勾=每轮该弹还弹，裁决 C 的负向半边在屏上）" ;;
    true) bad "U16d :: 开关默认被勾上（checked=true）＝把安全默认改成了少问一次" ;;
    *) skip "U16d 开关默认态读不到（repeat_no_ask 的 checked 属性=$d_chk，空串不许当 false 用）" ;;
esac

# 脏数字：注入通道与手点同一判据，拒派发必须显形（红字上屏）且零执行
MSYS_NO_PATHCONV=1 $ADB logcat -c >/dev/null 2>&1
inject "--es task_json '$UWAITS' --es repeat_count 101"
assert_edit "U16e 越界轮数被拒留痕" "submit refused gate=REPEAT_FIELD field=repetitions"
n_exec=$(count_line 'S1SMOKE ok=')
if [ "$n_exec" = "0" ]; then pass "U16f 拒而未放：本轮零执行回执（脏数字没被夹取成 100 轮）"; else
    bad "U16f :: 被拒之后仍有 $n_exec 条执行回执"
fi
ui_sweep
u16g=$(ui_expect 'Nothing was dispatched')
if [ "$u16g" = "skip" ]; then
    skip "U16g 拒因上屏 :: 本扫视 $SWEEP_SCREENS 屏里 0 屏含自家窗节点"
elif [ "$u16g" = "hit" ]; then pass "U16g 拒因上屏（用户看得见『为什么没跑』）"; else
    bad "U16g :: 屏上没有拒因红字（task_rejection 缺席=静默吞掉一次点击）"
fi

# 单发不写多轮字段 / 多轮写：屏上侧只有本脚本扫得全（报告格在页面末尾）
inject "--es task_json '$UWAITS'"
r16=$(wait_line "S1SMOKE ok=" 60)
if [ -z "$r16" ]; then
    bad "U16h :: 单发没跑起来（报告格无从产生）"
    skip "U16h/U16i 屏上报告字段 :: 前置未立"
else
    ui_sweep
    u16h=$(ui_expect 'resource-id="run_report"')
    if [ "$u16h" = "hit" ]; then pass "U16h 单发执行报告上屏（run_report 节点可见）"; else
        bad "U16h :: 执行完屏上没有报告格（run_report=$u16h）"
    fi
    if [ "$(ui_expect 'repeats:')" = "miss" ] && [ "$(sweep_complete)" = "1" ]; then
        pass "U16i 单发屏上无 repeats 字段（v1.0.2 字节面不回归；扫视到底才敢判缺席）"
    elif [ "$SWEEP_OK" != "1" ]; then
        skip "U16i 单发屏上 repeats 缺席 :: 页没扫到底，缺席一侧读不出证据"
    else
        bad "U16i :: 单发屏上竟出现 repeats 字段（旧口径被多轮逻辑污染）"
    fi
fi

# 多轮：收口真进报告格（日志侧在 s5d 脚本判，这里只钉"用户看得见第几轮"）
MSYS_NO_PATHCONV=1 $ADB logcat -c >/dev/null 2>&1
inject "--es task_json '$UWAITS' --es repeat_count 3 --es repeat_interval 1"
if wait_line "repeats: 3 of 3" 90 >/dev/null; then
    ui_sweep
    u16j=$(ui_expect 'repeats: 3 of 3')
    if [ "$u16j" = "hit" ]; then pass "U16j 多轮收口写进屏上报告（repeats: 3 of 3）"; else
        bad "U16j :: 日志说跑满 3 轮、屏上报告却没有（两本账）"
    fi
else
    bad "U16j :: 90s 内没等到多轮收口日志"
fi

echo
echo "汇总：通过 $passed / 失败计数见下 / 跳过 $skipped（跳过=前置未立，不记绿也不记红）"
if [ "$fail" -eq 0 ]; then echo "ui-smoke: ALL PASS（含 $skipped 条 SKIP）"; else echo "ui-smoke: 有失败项"; fi
exit "$fail"
