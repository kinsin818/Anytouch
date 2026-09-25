#!/usr/bin/env bash
# Anytouch S5-d 重复执行设备冒烟（avd34/avd35，S5-R6 靶机口径：AVD 原生镜像，K40 真机零触碰）
# 军令件：orders/ANYTOUCH-S5d-repeat-loop-ORDER.md（第 1~6 条）+ 裁决 S5-R11 补裁 C（Repeat without asking 开关）
# 判据在册：orders/RULINGS-20260922.md 的 S5-R11 执行面钉（验证矩阵）。
#
# 用法：ANDROID_SERIAL=emulator-5554 bash scripts/s5d-repeat-loop-smoke.sh
#       E9_APK=app/build/outputs/apk/debug/app-debug.apk EXPECTED_VC=4 ANDROID_SERIAL=emulator-5554 bash ...
# 前置：恰好 1 台靶机在线、装好**本轮** debug APK（E9 语义：缺 E9_APK 就是"没装新码也照跑"=假绿，硬性要求）、
#       versionCode 与 EXPECTED_VC 相符（防"跑的是上一版"）、无障碍已绑。
#
# 七格（每格只钉军令一条，判据只加不减）：
#   L1 单发口径不回归（无注入参数=与 v1.0.2 逐字同义，收口行无 round 段、报告无 repeats 字段）
#   L2 跑满设定轮数 + 轮间等待真发生且落在 间隔±1 秒（军令 1/2/3 条）
#   L3 轮间等待期内按急停=剩余轮次一轮都不开（军令 4 条最难的一档：等待不是"没在跑"就不算停）
#   L4 勾了开关：本跑真被面板放行过的类别，后续轮跳过面板（裁决 C 正向）
#   L5 不勾开关：每一轮该弹还弹——第二次面板没人点就按 fail-closed 自拒并停掉后续轮（裁决 C 负向）
#   L6 脏数字（101 / 61 / 1e2）一律拒派发，红字上屏（军令 1 条的范围不是"夹取"而是"拒"）
#   L7 下界：1 轮 1 秒档跑通，等待不出现负数（waitMs 下夹 0 的设备面对拍）
#
# 洁净纪律（沿用 S2/S5 血账）：
# - 执行期禁 uiautomator dump（会挤掉自家服务打断在跑任务）；跑中只读 logcat 与 dumpsys window。
#   每次 idle dump 后一律 wait_bound 复绑再走下一步。
# - 面板"确认"钮按屏定位（find-panel-confirm.py，三拍都量不到才退死坐标试投并记红）；
#   点球/点钮都是测试通道模拟手指（input tap 走 InputManager），不是产品定位。
# - 零网络零模型：本批所有格子都不发任何网络请求（重复执行住在执行侧，编译面不参与）。
# - 设备独占锁：同机第二驱动当场终止。
set -u
export MSYS_NO_PATHCONV=1

ADB="adb"
[ -n "${ANDROID_SERIAL:-}" ] && ADB="adb -s $ANDROID_SERIAL"
E9_APK="${E9_APK:-app/build/outputs/apk/debug/app-debug.apk}"
RAW_DIR="${RAW_DIR:-evidence/S5/raw}"
EXPECTED_VC="${EXPECTED_VC:-4}"
LOCK=/tmp/s5d-repeat.lock

fail=0; passed=0
RED='\033[0;31m'; GRN='\033[0;32m'; NCT='\033[0m'
pass() { printf "${GRN}PASS${NCT} %s\n" "$1"; passed=$((passed + 1)); }
bad()  { printf "${RED}FAIL${NCT} %s\n" "$1"; fail=1; }
log()  { printf '      | %s\n' "$*"; }
RAW="$RAW_DIR/s5d-repeat-$(date +%Y%m%d-%H%M%S).raw.txt"

# ---- 预检（先修后跑：任何一条不满足都当场停，不烧轮次赌运气） ----
[ -f "$E9_APK" ] || { bad "预检 :: E9_APK 不在盘（$E9_APK）——不冒称装了新码"; exit 2; }
DEVS=$(adb devices | grep -cw device)
if [ "$DEVS" -ne 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then bad "预检 :: adb 在线 $DEVS 台且未指定 ANDROID_SERIAL——禁双驱动"; exit 2; fi
case "$($ADB shell getprop ro.product.model 2>/dev/null | tr -d '\r')" in
    *Kiwi*|*K40*|kona*|*M2012K11AC*) bad "预检 :: 靶机疑似真机——S5-d 只跑 AVD（K40 本批零触碰）"; exit 2 ;;
esac
if [ -f "$LOCK" ] && kill -0 "$(cat "$LOCK" 2>/dev/null)" 2>/dev/null; then
    bad "预检 :: 另一个 s5d-repeat（pid $(cat "$LOCK")）还活着——禁双驱动，本跑终止"; exit 2
fi
echo $$ > "$LOCK"; trap 'rm -f "$LOCK"' EXIT

wait_bound() {
    local i=0
    while [ "$i" -lt 25 ]; do
        $ADB shell dumpsys accessibility 2>/dev/null | grep -q "Bound services:{Service\[label=Anytouch" && return 0
        sleep 1; i=$((i + 1))
    done
    return 1
}
# 只取 versionCode= 后面那一段数字：dumpsys 这一行是 "versionCode=4 minSdk=26 targetSdk=34"，
# 整行抹掉非数字会得到 42634（首跑实测），比对永远为假＝预检把自己的判据读错了。
VC=$($ADB shell dumpsys package com.anytouch.app 2>/dev/null | tr -d '\r' | grep -m1 versionCode | sed 's/.*versionCode=//; s/[^0-9].*//')
[ "$VC" = "$EXPECTED_VC" ] || { bad "预检 :: 机上 versionCode=$VC 与本轮应有 $EXPECTED_VC 不符——装错包，跑了也是白跑"; exit 2; }
wait_bound || { bad "预检 :: 无障碍服务未绑定"; exit 2; }
SIZE=$($ADB shell wm size | tail -1 | tr -d '\r' | sed 's/.*: *//')
W=${SIZE%x*}; H=${SIZE#*x}
# 停止球默认停靠位（END|CENTER_VERTICAL，device-smoke C6 标定 1002,1272@1080x2400）按分辨率线性缩放
BALL_X=$(( W * 1002 / 1080 )); BALL_Y=$(( H * 1272 / 2400 ))
# 面板"确认"钮兜底坐标（v1.0.2 实测 729,1391@1080x2400；只作试投，不作判据）
BTN_CONFIRM_X=$(( W * 729 / 1080 )); BTN_CONFIRM_Y=$(( H * 1391 / 2400 ))
mkdir -p "$RAW_DIR" .smoke-tmp
log "靶机 $($ADB shell getprop ro.build.version.sdk | tr -d '\r') 号 API · ${W}x${H} · vc=$VC · 球(${BALL_X},${BALL_Y})"
echo "# s5d-repeat-loop 预检 ok · api=$($ADB shell getprop ro.build.version.sdk | tr -d '\r') ${W}x${H} vc=$VC" > "$RAW"

XMIG=/sdcard/.s5d.xml; XHOST=.smoke-tmp/s5d.xml
dump_idle() {
    $ADB shell uiautomator dump /sdcard/.s5d.xml >/dev/null 2>&1
    $ADB shell cat $XMIG | tr -d '\r' > "$XHOST"
    wait_bound || { bad "dump 后服务未复绑 :: 下一条判据不可信"; return 1; }
}
# 两个 tag 都要收：面板在场的那行 "second-confirm panel shown" 由 OverlayUi 打，TAG 是
# AnytouchOverlay（不是 AnytouchRun）。首跑实测教训——只过滤 AnytouchRun 时 L4/L5 的"面板次数"
# 恒读 0，把真弹了 2 次的面板记成"面板未见"（假红），而跳过计数却正常，两半自相矛盾才暴露。
logs() { $ADB logcat -d -s AnytouchRun:* AnytouchOverlay:* 2>/dev/null; }
clr()  { $ADB logcat -c >/dev/null 2>&1; }
# 收口行有两种字面：单发 "S1SMOKE ok=…"，多轮 "S1SMOKE round=2/3 ok=…"。
# 首跑实测教训：只锚 "S1SMOKE ok=" 会把多轮的每一条都读成 0 条——轮数判据当场空转（假绿形态）。
rounds() { logs | grep -aE "S1SMOKE (round=[0-9]+/[0-9]+ )?ok=" ; }
# 注入通道：task_json + 重复执行三枚 extras 全部走 RepeatPolicy.parse（与手点同一判据）
inject() { # $1=task json，$2..=额外 am start 参数
    local t="$1"; shift
    $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '$t' $*" >/dev/null 2>&1
}
wait_for() { # $1=regex，$2=秒上限，$3=格名 → 0 命中 / 1 超时
    local re="$1" lim="${2:-40}" i=0
    while [ "$i" -lt "$lim" ]; do
        logs | grep -aqE "$re" && return 0
        sleep 1; i=$((i + 1))
    done
    return 1
}

# 两轮 wait 任务：每轮 2 秒、ok=2 total=2，整队纯 wait——不碰节点也不碰网络，
# 因此每一轮之后屏态一模一样（多轮可重复，这是本批能钉住"跑满 N 轮"的前提）。
SAFE='"safety":{"viewport_ok":true,"click_enabled":true}'
WAITS="[{\"action_id\":\"w1\",\"type\":\"wait\",\"source\":\"node\",\"value\":{\"ms\":1000},$SAFE},{\"action_id\":\"w2\",\"type\":\"wait\",\"source\":\"node\",\"value\":{\"ms\":1000},$SAFE}]"
# 高危格用自家标定过的稳定节点：Settings 搜索框里的文本一旦含 "password" 就命中 PASSWORD 词表，
# 而**点它不会换页**（不像"删除照片"那样一次就把目标吃掉）——同一轮次序列可反复走到门禁，L4/L5 才量得出面板次数。
# id 取 avd34/avd35 原生镜像实际那一枚（09-25 实测树：`open_search_view_edit_text`，text="password"，clickable=true）。
# 首跑写的是 `android:id/search_src_text`（旧版 Settings 的 id）：页面上确有 text="password"，却长在**另一枚节点**上，
# 于是执行器定位失败、面板一次不弹，"零面板"看着像产品缺陷——前置判据错一位就把测试面的账记到产品头上。
RID_SEARCH="${RID_SEARCH:-com.google.android.settings.intelligence:id/open_search_view_edit_text}"
PWORD="[{\"action_id\":\"pw\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"resource_id\":\"$RID_SEARCH\"},$SAFE}]"

# 面板答一次（按屏定位三拍 + 撤窗复核；与 s5-templates-smoke v1.0.2 修好的那一版同法）
answer_panel_once() { # $1=格名
    local tag="$1" i=0 seen=0 measured=0 a=0 shot meas rc tx ty
    tx=$BTN_CONFIRM_X; ty=$BTN_CONFIRM_Y
    while [ "$i" -lt 40 ]; do
        if $ADB shell dumpsys window 2>/dev/null | grep mCurrentFocus | grep -qE "u0 com\.anytouch\.app\}"; then
            seen=1; a=0
            while [ "$a" -lt 3 ]; do
                a=$((a + 1)); sleep 1
                shot="$RAW_DIR/s5d-$tag-panel-try$a-$(date +%H%M%S).png"
                $ADB exec-out screencap -p > "$shot" 2>/dev/null
                echo "# [$tag] 面板在场截图(第 $a 拍)=$shot 焦点=$($ADB shell dumpsys window 2>/dev/null | grep mCurrentFocus | tr -d '\r')" >> "$RAW"
                meas=$(python scripts/find-panel-confirm.py "$shot" 2>>"$RAW"); rc=$?
                if [ "$rc" -eq 0 ] && [ -n "$meas" ]; then
                    tx=${meas% *}; ty=${meas#* }; measured=1
                    echo "# [$tag] 面板按钮按屏定位=(${tx},${ty}) 量自 $(basename "$shot")（第 $a 拍命中）" >> "$RAW"
                    break
                fi
            done
            [ "$measured" -eq 1 ] || bad "[$tag] :: 面板按钮三拍皆量不到，退死坐标试投——本格不记干净绿"
            $ADB shell input tap $tx $ty
            break
        fi
        sleep 1; i=$((i + 1))
    done
    [ "$seen" -eq 1 ] || { bad "[$tag] :: 40s 内未见高危确认面板（面板判据缺席=本格不成立）"; return 1; }
    i=0
    while [ "$i" -lt 8 ]; do
        $ADB shell dumpsys window 2>/dev/null | grep mCurrentFocus | grep -qE "u0 com\.anytouch\.app\}" || return 0
        sleep 1; i=$((i + 1))
    done
    bad "[$tag] :: 确认点击未落（面板 8s 后仍在焦=(${tx},${ty}) 试投落空，非产品放行逻辑）"
    return 1
}

# 高危格前置：停在 Settings 搜索页，搜索框文本置为 "password"（测试通道 input text，与点球同类豁免）
to_search_with_password() { # $1=格名
    local tag="$1" xy i=0
    $ADB shell am force-stop com.android.settings >/dev/null 2>&1
    $ADB shell am start -n com.android.settings/.Settings >/dev/null 2>&1; sleep 6
    dump_idle || return 1
    xy=$(python scripts/tap_node.py "$XHOST" "Search settings" 2>>"$RAW"); [ -n "$xy" ] || { bad "[$tag] :: 没找到搜索入口（测试通道未就位）"; return 1; }
    $ADB shell input tap $xy >/dev/null 2>&1; sleep 3
    $ADB shell input text password >/dev/null 2>&1; sleep 2
    # 前置判据钉在**执行器将要定位的那一枚节点**上，不是"页面上随便哪里有个 password"：
    # 首跑实测把 resource-id 写成旧版 Settings 的 `android:id/search_src_text`，页面上确有
    # text="password" 却长在另一枚节点上——定位失败、面板零次弹出，读起来像产品不放行。
    # 现在两枚属性同节点核对，对不上就当场红（前置未就位＝本格不成立，不记产品红）。
    while [ "$i" -lt 10 ]; do
        $ADB shell uiautomator dump /sdcard/.s5d.xml >/dev/null 2>&1
        $ADB shell cat $XMIG | tr -d '\r' > "$XHOST"
        target=$(grep -o "<node[^>]*resource-id=\"$RID_SEARCH\"[^>]*>" "$XHOST" | grep -c 'text="password"')
        if [ "${target:-0}" -ge 1 ]; then
            wait_bound || { bad "[$tag] :: 前置后服务未复绑"; return 1; }
            return 0
        fi
        sleep 1; i=$((i + 1))
    done
    bad "[$tag] :: 没读到 resource-id=$RID_SEARCH 且 text=\"password\" 的那一枚节点（高危前置未就位＝面板永远不会弹，本格不成立）"
    grep -o '<node[^>]*password[^>]*>' "$XHOST" | head -5 >> "$RAW"
    wait_bound
    return 1
}

T0ALL=$(date +%s)
# 墙钟只认**本格起点**：从 T0ALL 起算会把上一格的等待也算进本格的"至少 N 秒"，
# 那种断言永远为真=假绿（本批最容易犯的一条，先钉死）。
T0CELL=$T0ALL
cell_start() { T0CELL=$(date +%s); }
cell_clock() { echo $(( $(date +%s) - T0CELL )); }

# ---------------- L1 单发口径不回归 ----------------
cell_start
clr
inject "$WAITS"
if wait_for "S1SMOKE ok=" 40; then
    N=$(rounds | wc -l | tr -d ' ')
    LINE=$(rounds | tail -1)
    if [ "$N" -eq 1 ] && printf '%s' "$LINE" | grep -q "ok=2 total=2 stopped=false" && ! printf '%s' "$LINE" | grep -q "round="; then
        pass "L1 :: 无注入参数=单发旧口径逐字不变（$LINE）"
    else bad "L1 :: 期望一条无 round 段的收口行，实际 $N 条：$LINE"; fi
    if logs | grep -aq "repeats:"; then bad "L1 :: 单发竟写了多轮收口（loops 泄漏进旧口径）"; else pass "L1 :: 单发不产出 repeats 收口（既有断言零回归）"; fi
    # 屏上判据只有在**看得见报告那一格**时才有意义：折叠线以下的 Compose 节点不进无障碍树
    # （device-smoke 血账），拿看不见的东西判"没有"＝恒真通过。看不见就照实记不判，不记绿。
    if dump_idle; then
        if grep -q 'run_report' "$XHOST"; then
            grep -q "repeats:" "$XHOST" && bad "L1 :: 屏上报告出现 repeats 字段（单发不许）" || pass "L1 :: 屏上报告可见且不含 repeats 字段（字节面与旧口径一致）"
        else
            log "L1 :: 屏上未见 run_report（报告在折叠线以下）→ 屏幕面交 ui-smoke 判，本格不记这条绿"
        fi
    fi
else bad "L1 :: 40s 内没有收口行（单发派发失败=后面全部不可信）"; fi

# ---------------- L2 跑满轮数 + 轮间真等待（间隔±1 秒） ----------------
cell_start
clr
inject "$WAITS" --es repeat_count 3 --es repeat_interval 3
RC=0
wait_for "repeats: 3 of 3" 90 || RC=1
N=$(rounds | wc -l | tr -d ' ')
ELAPSED=$(cell_clock)
WAITS_LOG=$(logs | grep -a "S5DSMOKE waiting" | sed 's/.*waiting \([0-9]*\)ms.*/\1/')
BADW=0
for v in $WAITS_LOG; do
    case "$v" in 2000|3000|4000) ;; *) BADW=1 ;; esac
done
if [ "$RC" -eq 0 ] && [ "$N" -eq 3 ] && [ "$BADW" -eq 0 ] && [ "$(printf '%s' "$WAITS_LOG" | grep -c .)" -eq 2 ]; then
    # 3 轮 × 2s + 两次等待各 ≥2s ⇒ 墙钟至少 10s（防"等待被编译器吃掉"的假绿）
    if [ "$ELAPSED" -ge 10 ]; then pass "L2 :: 跑满 3 轮 · 轮间等待 $(echo "$WAITS_LOG" | tr '\n' '/')ms（3±1 档）· 墙钟 ${ELAPSED}s"; else bad "L2 :: 轮数与等待条数都对但墙钟只 ${ELAPSED}s（等待未真发生？）"; fi
else bad "L2 :: 收口/轮数/等待区间不符（rounds=$N 等待=$WAITS_LOG rc=$RC）"; fi
logs | grep -aq "submit accepted .*askEveryRound=true" && pass "L2 :: 未勾选时计划钉为 askEveryRound=true（安全默认）" || bad "L2 :: 缺省档没留下 askEveryRound=true 留痕"

# ---------------- L3 等待期内按急停 ----------------
cell_start
clr
inject "$WAITS" --es repeat_count 5 --es repeat_interval 60
if wait_for "S5DSMOKE waiting" 40; then
    TB=$(date +%s)
    $ADB shell input tap $BALL_X $BALL_Y >/dev/null 2>&1
    if wait_for "rounds stopped before round=2 reason=stop_ball" 6; then
        DT=$(( $(date +%s) - TB ))
        N=$(rounds | wc -l | tr -d ' ')
        REC=$(logs | grep -a "S5DSMOKE repeats:" | tail -1)
        if [ "$N" -eq 1 ] && printf '%s' "$REC" | grep -q "1 of 5 round(s) ran, stopped early (stop_ball)"; then
            pass "L3 :: 等待期点球 ${DT}s 内停下，剩余 4 轮一轮没开（$REC）"
        else bad "L3 :: 停是停了但账面不符（轮数=$N 收口=$REC）"; fi
        [ "$DT" -le 5 ] || bad "L3 :: 急停响应 ${DT}s > 5s（分片轮询没起作用=把 60 秒当一块觉睡）"
    else bad "L3 :: 点球后 6s 内没等到 stop_ball 归因（等待期不看急停=军令 4 条空头）"; fi
    # 收尾：这一跑的 KillSwitch 已被本次请求带走，等总线自己复位（下一条注入的 runTask 开头 reset）
    sleep 2
else bad "L3 :: 60 秒档却没进等待（轮间等待压根没发生）"; fi

# ---------------- L4 勾了开关：面板只弹一次，后续轮跳过并留痕 ----------------
if to_search_with_password L4; then
    cell_start
    clr
    inject "$PWORD" --es repeat_count 3 --es repeat_interval 1 --ez repeat_no_ask true
    answer_panel_once L4
    if wait_for "repeats: 3 of 3" 90; then
        PANELS=$(logs | grep -ac "second-confirm panel shown")
        SKIPS=$(logs | grep -ac "panel skipped round=")
        N=$(rounds | wc -l | tr -d ' ')
        if [ "$PANELS" -eq 1 ] && [ "$SKIPS" -ge 2 ] && [ "$N" -eq 3 ]; then
            pass "L4 :: 面板 1 次 / 跳过 $SKIPS 次 / 跑满 3 轮（授权不放大：跳过只吃已在面板上放行过的类别）"
        else bad "L4 :: 期望面板 1 次、跳过≥2、轮数 3；实际面板=$PANELS 跳过=$SKIPS 轮数=$N"; fi
        logs | grep -aq "askEveryRound=false" && pass "L4 :: 勾选真落到计划（askEveryRound=false）" || bad "L4 :: 勾选没进计划（注入通道与手点不同判据？）"
        # 裁决 S5-R11 钉 3：每一次自动放行都要留痕，且留痕要写清"是谁授权的"。
        # 留痕条数必须与跳过条数逐条对齐——少一条就是"少问了却没记账"。
        TRACE=$(logs | grep -ac "auto-confirmed by user's \"Repeat without asking\"")
        if [ "$TRACE" -eq "$SKIPS" ] && [ "$TRACE" -ge 2 ]; then
            pass "L4 :: 自动放行留痕 $TRACE 条=跳过 $SKIPS 条，逐字含授权来源字样（钉 3）"
        else bad "L4 :: 留痕 $TRACE 条与跳过 $SKIPS 条不齐（钉 3 要求每次自动放行都留痕）"; fi
    else bad "L4 :: 90s 内没跑满 3 轮（跳过路径可能把整队卡死）"; fi
else bad "L4 :: 高危前置未就位，本格不成立（不记绿也不记产品红）"; fi

# ---------------- L5 不勾开关：每一轮该弹还弹；没人点就 fail-closed 自拒并停后续轮 ----------------
if to_search_with_password L5; then
    cell_start
    clr
    inject "$PWORD" --es repeat_count 3 --es repeat_interval 1
    answer_panel_once L5   # 只答第一次；第二次故意留给人（没有人在=超时默认拒）
    wait_for "no further rounds" 60
    PANELS=$(logs | grep -ac "second-confirm panel shown")
    SKIPS=$(logs | grep -ac "panel skipped round=")
    REC=$(logs | grep -a "S5DSMOKE no further rounds" | tail -1)
    if [ "$PANELS" -eq 2 ] && [ "$SKIPS" -eq 0 ]; then
        pass "L5 :: 不勾=第二轮照旧弹面板（共 $PANELS 次），零跳过（开关没有被默认打开）"
    else bad "L5 :: 期望面板 2 次且零跳过；实际面板=$PANELS 跳过=$SKIPS"; fi
    printf '%s' "$REC" | grep -q "reason=round_stopped" && pass "L5 :: 超时默认拒中止本轮后剩余轮不开（$REC）" || bad "L5 :: 没看到 round_stopped 归因：$REC"
else bad "L5 :: 高危前置未就位，本格不成立"; fi
# 上一格留下的超时拒会让本轮 KillSwitch 保持"未停"（本轮是自己中止的），复位仍由下一请求负责
$ADB shell am force-stop com.android.settings >/dev/null 2>&1

# ---------------- L6 脏数字一律拒派发（界外不夹取） ----------------
for SPEC in "101:5:repetitions" "3:61:interval" "1e2:5:repetitions" "3:0:interval"; do
    R=${SPEC%%:*}; rest=${SPEC#*:}; I=${rest%%:*}; F=${rest##*:}
    cell_start
    clr
    inject "$WAITS" --es repeat_count "$R" --es repeat_interval "$I"
    sleep 3
    HIT=$(logs | grep -a "submit refused gate=REPEAT_FIELD" | tail -1)
    NR=$(rounds | wc -l | tr -d ' ')
    if printf '%s' "$HIT" | grep -q "field=$F" && [ "$NR" -eq 0 ]; then
        pass "L6 :: reps=$R interval=$I 拒派发（field=$F）且零执行行"
    else bad "L6 :: reps=$R interval=$I 未被拒或竟有执行行（field 期望 $F，执行行 $NR 条）→ $HIT"; fi
    # 拒因**上屏**那一半不在这里判：task_rejection 落在折叠线以下，单屏 dump 读不到＝恒假红
    # （首跑实测四条屏幕判据全红而四条日志全对）。屏幕面交 scripts/ui-smoke.sh 的 U16g——
    # 那个脚本逐屏翻页扫到底，"看得见才判、看不见不判"的纪律在那边才有实现条件。
done

# ---------------- L7 下界 1 轮 1 秒：等待不出现负数 ----------------
cell_start
clr
inject "$WAITS" --es repeat_count 3 --es repeat_interval 1
if wait_for "repeats: 3 of 3" 60; then
    NEG=$(logs | grep -a "S5DSMOKE waiting" | grep -c "waiting -" || true)
    WA2=$(logs | grep -a "S5DSMOKE waiting" | sed 's/.*waiting \([0-9-]*\)ms.*/\1/')
    OKV=1
    for v in $WA2; do case "$v" in 0|1000|2000) ;; *) OKV=0 ;; esac; done
    if [ "$NEG" -eq 0 ] && [ "$OKV" -eq 1 ]; then pass "L7 :: 1 秒档等待=$(echo "$WA2" | tr '\n' '/')ms（下夹 0 生效，无负延时）"; else bad "L7 :: 等待取值越界：$WA2"; fi
else bad "L7 :: 下界档没跑满 3 轮"; fi

log "收尾：服务在绑=$($ADB shell dumpsys accessibility | grep -c 'Bound services:{Service\[label=Anytouch')；总用时 $(( $(date +%s) - T0ALL ))s"
echo "# RAW 结束 fail=$fail passed=$passed" >> "$RAW"
printf 'RESULT fail=%s passed=%d\n' "$fail" "$passed"
log "raw → $RAW"
exit "$fail"
