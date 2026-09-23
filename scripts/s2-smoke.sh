#!/usr/bin/env bash
# Anytouch S2 真事件闭环冒烟（军令 ANYTOUCH-S2-ONDEVICE L0）：
#   adb 开录 → 人工动作（测试通道 input）→ 停止并编译 → 一键回放 → 断言逐步 ok。
# 用法：bash scripts/s2-smoke.sh            （需恰好 1 台 adb 设备、已装本次构建 APK、无障碍已绑）
#   ROUNDS=10 PASS_RATIO=0.9 S2_STEPS='Connected devices|Connection preferences|Bluetooth'
# 退出码：0=达标；1=未达标；2=前置不满足。
# 产品路径零网络、零坐标；`input tap/swipe` 只出现在本测试通道（模拟用户手指，与 device-smoke C6/C7 同豁免口径）。
#
# 测试通道纪律（设备实证换来的两条）：
# ① 录制期**零 uiautomator dump**：dump 注册的 UiTestAutomationService 会把自家服务挤下线，
#    掉线窗口内的事件真漏（曾把"漏录"错记成产品缺陷）。故每轮先"预走一遍"按文本取好坐标，
#    录制期只按预走坐标动手——坐标只活在测试通道，产品侧仍是纯节点链。
# ② 红项必附明细：S2SMOKE-STEP/NOTE 与 S1SMOKE-DETAIL 随行打印，归因不靠复跑碰运气。
# ③ S2_VERBOSE=1 打整轮采集面全量日志（每一下点击的句柄/事件/路径/线索证词），用于**漏步**归因——
#    tail-8 只看得到尾部，丢的是第一下时它一行都不留（本项目 R4 漏录就是这么找不着的）。
set -u

ADB="adb"
[ -n "${ANDROID_SERIAL:-}" ] && ADB="adb -s ${ANDROID_SERIAL}"

fail=0
RED='\033[0;31m'; GRN='\033[0;32m'; YLW='\033[0;33m'; NCT='\033[0m'
pass() { printf "${GRN}PASS${NCT} %s\n" "$1"; }
bad()  { printf "${RED}FAIL${NCT} %s\n" "$1"; fail=1; }
log()  { printf "${YLW}··${NCT} %s\n" "$1"; }

ROUNDS="${ROUNDS:-10}"
PASS_RATIO="${PASS_RATIO:-0.9}"
TARGET_PKG="${TARGET_PKG:-com.android.settings}"
SCROLL_CASE="${SCROLL_CASE:-1}"   # 1=附带"一次慢拖只成一步"的滚动采集用例

# 人工动作脚本（每轮同一套；文本必须是目标 App 英文界面上的可见文本）
STEPS_DEFAULT=("Connected devices" "Connection preferences" "Bluetooth")
if [ -n "${S2_STEPS:-}" ]; then
    IFS='|' read -r -a STEPS <<< "$S2_STEPS"
else
    STEPS=("${STEPS_DEFAULT[@]}")
fi
NS=${#STEPS[@]}

# 整轮采集面日志（仅 S2_VERBOSE=1）：漏步归因要"每一下的证词"，tail-8 只保尾部。
dump_capture_log() {
    [ "${S2_VERBOSE:-0}" = "1" ] || return 0
    MSYS_NO_PATHCONV=1 $ADB logcat -d -s AnytouchRun:* 2>/dev/null |
        grep -a "S2SMOKE" | sed 's/^/      | /'
}

dump_ui() {
    MSYS_NO_PATHCONV=1 $ADB shell uiautomator dump /sdcard/.s2dump.xml >/dev/null 2>&1 || return 1
    MSYS_NO_PATHCONV=1 $ADB shell cat /sdcard/.s2dump.xml 2>/dev/null | tr '\n' ' '
    MSYS_NO_PATHCONV=1 $ADB shell rm /sdcard/.s2dump.xml >/dev/null 2>&1
}

# 按文本/content-desc 查节点中心：**取面积最小的匹配节点**，并拒收超大框（两者都是设备实证换来的）。
# 切分单元必须是 `<node`（一个节点一行）而不是 `/>`：设备实证 uiautomator 的叶子节点写作 `" />`
# （斜杠前有空格），按 "/>" 切分时"文档序第一个叶子"会和它的全部祖先挤在同一行，
# grep -o bounds | head -1 取到的是**根节点**的 bounds——手指会落在屏幕正中（"Navigate up" 整批踩坑）。
# 面积上限的必要性：Settings **二级页**顶栏 collapsing_toolbar 自身带 content-desc="Connected devices"、
# bounds [0,0][1080,598]（645,840px ≈ 屏幕 25%），而首页真行是 [189,1055][636,1126]（≈0.9%）。
# 页面若被上一轮残手留在二级页（或被 kill 后仍在落地的孤儿 input tap 翻走），"唯一匹配"就是那条空区，
# 手指学到 (540,299) 什么也点不开 → 下一步"Connection preferences"在二级页照样找得到 →
# 整轮页面级联错位还一路顺利学到坐标，红得莫名其妙。宁可此处报"预走阶段没找到"（不计产品失败面）。
MAX_MATCH_AREA=300000   # 1080×2400 实测口径：整行卡片 ≤270k 放行，大容器/头图拒收
find_node_center() {
    local want="$1" xml="$2"
    printf '%s' "$xml" | sed 's|<node|\n<node|g' | grep -F "=\"$want\"" |
        grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | grep -o '[0-9]\+' |
        awk -v maxa="$MAX_MATCH_AREA" 'NR%4==1{x1=$1} NR%4==2{y1=$1} NR%4==3{x2=$1} NR%4==0{
                y2=$1; a=(x2-x1)*(y2-y1)
                if (a > 0 && a <= maxa && (best == 0 || a < best)) { best = a; bx = (x1+x2)/2; by = (y1+y2)/2 }
              } END { if (best > 0) printf "%d %d", bx, by }'
}

# 预置后自证页面身份：设备实证"force-stop + 显式启动"能把焦点带回 com.android.settings/.Settings，
# 但残手/孤儿 tap 会再把它翻进二级页。
# 取串注意 Window{ 后面还有一段 **userId**（`Window{hash u0 pkg/cls}`）：只剥一段 token 会把
# `u0 com.android.settings/...` 留在结果里，`^$TARGET_PKG/` 永远不匹配 → 断言必假红，
# 每次预置白做一次 force-stop（多一次冷启 = 页面时序整体后移，预走学不到后面的行——本轮 0/1 的元凶之一）。
# 这里按"pkg/cls"整段取，不依赖 token 位置；wait_focus 用的是子串匹配，故未受影响。
focus_window() {
    MSYS_NO_PATHCONV=1 $ADB shell dumpsys window 2>/dev/null | tr -d '\r' |
        grep -a -m1 mCurrentFocus | grep -o '[a-zA-Z0-9._]*/[a-zA-Z0-9._]*' | head -1
}
assert_focus_target() {
    local i=0
    while [ "$i" -lt 10 ]; do
        # 转场瞬间 mCurrentFocus=null（设备实证：按 BACK 后必有一次空窗），单次读必假红，
        # 假红的代价是一次白做的 force-stop 冷启（页面时序整体后移），故轮询到落定。
        printf '%s' "$(focus_window)" | grep -q "^$TARGET_PKG/" && return 0
        sleep 1; i=$((i + 1))
    done
    return 1
}

# 服务在不在（Bound services 行只带 label 不带包名，设备实证）
service_bound() {
    MSYS_NO_PATHCONV=1 $ADB shell dumpsys accessibility 2>/dev/null | tr -d '\r' |
        grep -a "Bound services" | grep -q "Anytouch"
}

wait_service_bound() {
    local i=0
    while [ "$i" -lt 20 ]; do
        service_bound && return 0
        sleep 1; i=$((i + 1))
    done
    return 1
}

wait_line() { # $1 grep pattern, $2 limit seconds -> prints line
    local pat="$1" limit="${2:-30}" i=0 out=""
    while [ "$i" -lt "$limit" ]; do
        out=$(MSYS_NO_PATHCONV=1 $ADB logcat -d -s AnytouchRun:* 2>/dev/null | grep -a "$pat" | tail -1 || true)
        [ -n "$out" ] && { printf '%s' "$out"; return 0; }
        sleep 1; i=$((i + 1))
    done
    return 1
}

# Settings 有"上次页面"恢复：force-stop 后 `am start .Settings` 会直接开在**上次看过的二级页**
# （设备实证：打印服务页，window class = com.android.settings.SubSettings），
# 而预走学的是"从首页进 Connected devices"的坐标——起点在二级页时：
# ① 首页独有的行学不到（响亮地报"预走阶段没找到"）；
# ② 二级页上恰好同名的行（collapsing_toolbar / 分类标题）会被学成假坐标，整轮级联错位。
# 判"在不在首页"只认 window class：一级 = .Settings，二级 = .SubSettings，搜索页 = SettingsIntelligence。
# 保守起见只在这两种串上按 BACK：别的 ROM/档位若不叫这名字，一次都不按，行为与改前完全一致。
back_to_home() {
    local i=0 focus
    while [ "$i" -lt 8 ]; do
        focus=$(focus_window)
        case "$focus" in
            "") sleep 2 ;;  # 转场空窗：既不是首页也不是二级页，等它落定再判（当首页=带着二级页起跑）
            *SubSettings* | *SettingsIntelligence*)
                log "起点在二级页 [$focus]，按返回键回首页"
                MSYS_NO_PATHCONV=1 $ADB shell input keyevent 4 >/dev/null 2>&1
                sleep 2 ;;
            *) return 0 ;;
        esac
        i=$((i + 1))
    done
    return 1
}

# 回到首页后归位滚动：首页可能停在上次看的位置（Connected devices 在首屏内，但翻到底就找不到了）。
# 测试通道允许坐标（产品代码禁），这里只是把列表拉回顶部。
home_to_top() {
    local i=0
    while [ "$i" -lt 2 ]; do
        MSYS_NO_PATHCONV=1 $ADB shell input swipe 540 700 540 1900 200 >/dev/null 2>&1
        sleep 1
        i=$((i + 1))
    done
}

reset_settings_home() {
    MSYS_NO_PATHCONV=1 $ADB shell am force-stop com.android.settings >/dev/null 2>&1
    MSYS_NO_PATHCONV=1 $ADB shell am force-stop com.google.android.settings.intelligence >/dev/null 2>&1
    MSYS_NO_PATHCONV=1 $ADB shell am start -n "$TARGET_PKG/.Settings" >/dev/null 2>&1
    sleep 7
    back_to_home
    # 焦点不在目标 App（残手/孤儿 tap 把页面翻走、或启动被别的窗抢掉）就再强置一次：
    # 预走阶段学的是"首页坐标"，起点错了整轮都是错的。
    assert_focus_target || {
        log "预置后焦点不在 $TARGET_PKG，重做一次强置"
        MSYS_NO_PATHCONV=1 $ADB shell am force-stop com.android.settings >/dev/null 2>&1
        MSYS_NO_PATHCONV=1 $ADB shell am start -n "$TARGET_PKG/.Settings" >/dev/null 2>&1
        sleep 6
        back_to_home
    }
    home_to_top
}

start_record() {
    MSYS_NO_PATHCONV=1 $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --es record_start $TARGET_PKG" >/dev/null 2>&1
    wait_line "S2SMOKE record start" 10 >/dev/null || return 1
    # 开录后主窗自己 moveTaskToBack 让位，目标 App 必须真的回到前台才能落指。
    # 设备实证：只 sleep 定长会在"自家窗还在顶上"时按前两下→手指打在录制面板上，
    # 目标 App 一条 viewClicked 都收不到（表现为"整轮空录"而不是"漏一步"）。
    # `dumpsys window` 不注册 UiTestAutomationService，不挤自家服务，可安全轮询。
    wait_focus "开录后"
}

stop_record() {
    MSYS_NO_PATHCONV=1 $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --ez record_stop true" >/dev/null 2>&1
    wait_line "S2SMOKE compiled" 20
}

# 前窗断言：等目标 App 成为**非空**焦点窗（转场中 mCurrentFocus=null，此时落指=打在过渡态上，
# 设备实证会得到"句柄已失联"的点击事件甚至整条无事件）
wait_focus() {
    local label="$1" i=0 focus=""
    while [ "$i" -lt 15 ]; do
        focus=$(MSYS_NO_PATHCONV=1 $ADB shell dumpsys window 2>/dev/null | tr -d '\r' | grep -a -m1 mCurrentFocus | sed 's/.*Window{[^ ]* //; s/}$//')
        printf '%s' "$focus" | grep -q "$TARGET_PKG" && return 0
        sleep 1; i=$((i + 1))
    done
    bad "$label 前窗不是目标 App（[$focus]，手指会打在别处）"
    return 1
}

# 预走一遍：按文本取好每步中心坐标（此期 dump 挤下线无所谓——不在录制中）
# 输出：全局数组 COORDS（下标与 STEPS 对齐）；任何一步取不到即返回 1（测试通道问题，不算产品账）
COORDS=()
learn_steps() {
    local i=0 want center xml
    COORDS=()
    # 坐标复用通道：`S2_COORDS='x y|x y|...'` 时本录全程零 dump。
    # 设备实证怀疑点：预走期的 uiautomator dump 会把自家服务挤下线再重绑，重绑后的
    # rootInActiveWindow 可能给出**残缺树**（36 节点，无被点行）→ 采集面判"路径不可证"。
    # 那是测试通道的副作用还是产品缺陷，必须能分开归因，故留此开关。
    if [ -n "${S2_COORDS:-}" ]; then
        IFS='|' read -r -a COORDS <<< "$S2_COORDS"
        [ "${#COORDS[@]}" -eq "$NS" ] || { bad "S2_COORDS 给了 ${#COORDS[@]} 组，与 $NS 步不符"; return 1; }
        log "坐标复用（本录全程零 dump）：${COORDS[*]}"
        reset_settings_home
        return 0
    fi
    reset_settings_home
    for want in "${STEPS[@]}"; do
        local tries=0
        while [ "$tries" -lt 8 ]; do
            xml=$(dump_ui || true)
            center=$(find_node_center "$want" "$xml")
            [ -n "$center" ] && break
            sleep 1; tries=$((tries + 1))
        done
        if [ -z "$center" ]; then
            bad "预走阶段没找到 [$want]（第 $((i + 1)) 步）——测试通道未就位，本轮不计产品失败面"
            return 1
        fi
        COORDS[$i]="$center"
        MSYS_NO_PATHCONV=1 $ADB shell input tap $center >/dev/null 2>&1
        sleep 2
        i=$((i + 1))
    done
    # 打印成 S2_COORDS 可直接复用的形态（`|` 分隔），供"零 dump 归因轮"使用
    log "预走坐标可复用：S2_COORDS='$(printf '%s|' "${COORDS[@]}" | sed 's/|$//')'"
    return 0
}

# ---------- 前置 ----------
devices=$(MSYS_NO_PATHCONV=1 adb devices | grep -c "device$" || true)
[ "$devices" -lt 1 ] && { echo "前置失败：无 adb 设备"; exit 2; }
if [ "$devices" -gt 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then
    echo "前置失败：接了 $devices 台设备，须 export ANDROID_SERIAL 定向"; exit 2
fi
if ! MSYS_NO_PATHCONV=1 $ADB shell dumpsys accessibility | grep -q "com.anytouch.app"; then
    echo "前置失败：无障碍服务未绑定"; exit 2
fi
# 采集事件型已进配置——ROM 若裁了 view 事件（小米系实测会裁），这里就该看见，而不是等录制出空档
# 装机/重装后系统重新绑定服务要几秒，一次性读必假红，故轮询到超时（设备实证：重装后立即读=空档）
cfg=""
for _i in $(seq 1 30); do
    cfg=$(MSYS_NO_PATHCONV=1 $ADB shell dumpsys accessibility 2>/dev/null | tr -d '\r')
    printf '%s' "$cfg" | grep -q "TYPE_VIEW_CLICKED" && break
    sleep 1
done
if ! printf '%s' "$cfg" | grep -q "TYPE_VIEW_CLICKED"; then
    bad "前置：服务事件订阅里看不到 TYPE_VIEW_CLICKED（采集配置未生效/装机旧版）"
    echo "s2-smoke: 前置不满足"; exit 2
fi

# ---------- SC 一次慢拖只成一步（滚动采集回归锁） ----------
# 设备实证：500px/900ms 的一次慢拖框架连发 3 条 viewScrolled（逐条成步=回放滚过头），
# 而点击后的布局重排也发 viewScrolled 但 delta/pos/max 全零（曾录成 6 步、回放 perform_failed）。
# 判据：一次慢拖 → 编译产物恰 1 步 scroll，且回放该步 ok。
if [ "$SCROLL_CASE" = "1" ]; then
    log "===== SC 滚动采集：一次慢拖 = 一步 ====="
    reset_settings_home
    wait_service_bound || bad "SC 前置：服务未绑"
    MSYS_NO_PATHCONV=1 $ADB logcat -c >/dev/null 2>&1
    if start_record; then
        MSYS_NO_PATHCONV=1 $ADB shell input swipe 500 1400 500 900 900 >/dev/null 2>&1
        sleep 3
        compiled=$(stop_record)
        json=$(MSYS_NO_PATHCONV=1 $ADB logcat -d -s AnytouchRun:* 2>/dev/null | grep -a "S2SMOKE-TASK" | tail -1 || true)
        json=${json#*S2SMOKE-TASK }
        n=$(printf '%s' "$json" | grep -o '"action_id"' | wc -l | tr -d ' ')
        kind=$(printf '%s' "$json" | grep -o '"type":"[a-z_]*"' | head -1)
        MSYS_NO_PATHCONV=1 $ADB logcat -d -s AnytouchRun:* 2>/dev/null | grep -a -E "S2SMOKE-STEP|S2SMOKE capture note" | tail -4
        if [ "$n" = "1" ] && [ "$kind" = '"type":"scroll"' ]; then
            pass "SC 一次慢拖只成一步 :: $compiled"
        else
            bad "SC 一次慢拖 :: 期望 1 步 scroll，实际 [$n 步 $kind] :: $compiled"
        fi
    else
        bad "SC 开录回执缺席"
    fi
fi

good=0
for round in $(seq 1 "$ROUNDS"); do
    log "===== 第 $round/$ROUNDS 轮：录 → 编 → 放（$NS 步） ====="
    learn_steps || continue

    # 1) 开录（预走的坐标已到手，此期零 dump）
    reset_settings_home
    wait_service_bound || bad "R$round 开录前服务未回绑（预走 dump 后未恢复）"
    MSYS_NO_PATHCONV=1 $ADB logcat -c >/dev/null 2>&1
    if ! start_record; then
        bad "R$round 开录回执缺席"
        continue
    fi

    # 2) 人工动作（测试通道手指，零 dump；每一下都等前窗稳定焦点）
    i=0
    while [ "$i" -lt "$NS" ]; do
        wait_focus "第 $((i + 1)) 下"
        MSYS_NO_PATHCONV=1 $ADB shell input tap ${COORDS[$i]} >/dev/null 2>&1
        sleep 3
        i=$((i + 1))
    done

    # 3) 停止并编译
    compiled=$(stop_record || true)
    taskline=$(MSYS_NO_PATHCONV=1 $ADB logcat -d -s AnytouchRun:* 2>/dev/null | grep -a "S2SMOKE-TASK" | tail -1 || true)
    if [ -z "$compiled" ] || [ -z "$taskline" ]; then
        bad "R$round 编译回执/产物缺席 :: [$compiled]"
        MSYS_NO_PATHCONV=1 $ADB logcat -d -s AnytouchRun:* 2>/dev/null | grep -a -E "S2SMOKE-DETAIL|capture note" | tail -6
        continue
    fi
    json=${taskline#*S2SMOKE-TASK }
    log "编译产物：$compiled"
    MSYS_NO_PATHCONV=1 $ADB logcat -d -s AnytouchRun:* 2>/dev/null | grep -a -E "S2SMOKE-STEP|capture note" | tail -8 | sed 's/^/     /'
    dump_capture_log
    if printf '%s' "$json" | grep -q "'"; then
        bad "R$round 产物含单引号，测试通道无法安全注入（跳过回放）"; continue
    fi

    # 4) 步数账：录到的步数必须等于人工步数（多=噪声成步，少=采集面漏步）
    n_actions=$(printf '%s' "$json" | grep -o '"action_id"' | wc -l | tr -d ' ')
    if [ "$n_actions" -ne "$NS" ]; then
        bad "R$round 录到 $n_actions 步 ≠ 人工 $NS 步"
        MSYS_NO_PATHCONV=1 $ADB logcat -d -s AnytouchRun:* 2>/dev/null |
            grep -a -E "S2SMOKE-STEP|capture note|path unprovable|clue=" | tail -8 | sed 's/^/     /'
    fi

    # 5) 一键回放：录出来的步骤原样回注执行链（同设备同 App，从首页重放）
    reset_settings_home
    MSYS_NO_PATHCONV=1 $ADB logcat -c >/dev/null 2>&1
    MSYS_NO_PATHCONV=1 $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '$json'" >/dev/null 2>&1
    receipt=$(wait_line "S1SMOKE ok=" 90 || true)
    expect="ok=$n_actions total=$n_actions stopped=false"
    if printf '%s' "$receipt" | grep -qF "$expect"; then
        pass "R$round 录→编→放全绿 :: $receipt"
        good=$((good + 1))
    else
        bad "R$round 回放未达 [$expect] :: [$receipt]"
        MSYS_NO_PATHCONV=1 $ADB logcat -d -s AnytouchRun:* 2>/dev/null | grep -a "S1SMOKE-DETAIL" | tail -3 | sed 's/^/     /'
    fi
done

echo
ratio=$(awk -v g="$good" -v t="$ROUNDS" 'BEGIN{printf "%.2f", g/t}')
if awk -v r="$ratio" -v p="$PASS_RATIO" 'BEGIN{exit !(r>=p)}'; then
    pass "S2 闭环达标：$good/$ROUNDS 轮全绿（ratio=$ratio ≥ $PASS_RATIO）"
else
    bad "S2 闭环未达标：$good/$ROUNDS 轮全绿（ratio=$ratio < $PASS_RATIO）"
fi
exit "$fail"
