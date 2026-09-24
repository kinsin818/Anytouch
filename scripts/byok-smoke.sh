#!/usr/bin/env bash
# Anytouch BYOK 面板设备冒烟（E 系列，切片 E）：屏上形态 + 无凭据即拒 + 真 Key 端到端。
# 用法：bash scripts/byok-smoke.sh                 （需恰好 1 台 adb 设备、装好本轮 debug APK、无障碍已绑）
#       多设备/真机：export ANDROID_SERIAL=<serial>
#       E_WIPE=1 bash scripts/byok-smoke.sh        （额外跑 E11「清除双槽」——会真删本机凭据，需重新填）
#       E9_APK=<同一枚 debug apk> bash scripts/byok-smoke.sh
#           （E9 用 `install -r` 真收走进程：常驻服务型 App 在 `am kill` 下 pid 不变，见 E9 段注释。
#            必须是**同一构建**，否则 E5~E10 的结论就不是同一枚 APK 出来的）
#
# 为什么单开一个脚本（与 ui-smoke.sh 同律）：device-smoke.sh 的 13 项是 C 系列回归锁、ui-smoke.sh 的
# 15 项是 U 系列回归锁，混编会把"新面未验"混进旧账基线。E 系列分两组：
#   E-pre（E1~E4）：零凭据、零上行即可判，模拟器就能跑——面板在不在屏上、被拒有没有说话、执行中编不编。
#   E-key （E5~E11）：必须有本机已保存的可用凭据（真端点往返几秒到几十秒），检测不到就**逐条大声 SKIP**，
#                    绝不算 PASS（假绿与假红同罪：把"没跑"记成"跑过"是本批最难看的一种）。
#
# Key 通道（裁决 S3-R4-2 原文：「Key上机你自己在手机上手动输就行，不用adb下发，字面量进shell确实不安全」）：
# 本脚本**一个 Key 字面量都没有，也从不读 Key 输入框的内容**——它只下发意图（ai_intent/ai_compile）、
# 读屏上尾 4 位（***1234 是军令 §3-3 认可的展示形态）与日志。
# 填 Key 那一下自裁决 S3-R5 起由 `scripts/byok-credential-inject.sh` 代填（老板原话："你直接从后台
# 输入不就完事了嘛"）；R5 只放开"谁敲"，没放开"字面量进日志/进 git"，所以下发仍走注入脚本的白名单。
#
# 滚动是必需的，不是保险：切片 D 之后整页变高（STATUS 待办 13②），BYOK 面板在折叠线以下，
# 而 `uiautomator dump` 只含**可见**节点——不滚就读不到，读不到会被写成"面板没了"=假红。
# 洁净纪律同 ui-smoke：每次 dump 后一律 wait_service_bound 复绑。
# 执行期禁止 dump（设备实证：dump 注册 UiTestAutomationService 会挤掉自家服务并取消在跑的 runTask）：
# E4 的"执行中拒编译"整段不做任何 dump，屏上一半改由人眼图取证。
set -u

ADB="adb"
[ -n "${ANDROID_SERIAL:-}" ] && ADB="adb -s ${ANDROID_SERIAL}"

fail=0
skipped=0
ran=0
RED='\033[0;31m'; GRN='\033[0;32m'; YLW='\033[0;33m'; NCT='\033[0m'
pass()   { printf "${GRN}PASS${NCT} %s\n" "$1"; ran=$((ran + 1)); }
bad()    { printf "${RED}FAIL${NCT} %s\n" "$1"; fail=1; }
skip()   { printf "${YLW}SKIP${NCT} %s\n" "$1"; skipped=$((skipped + 1)); }
log()    { printf '      | %s\n' "$*" ; }

PAGE_TMP=$(mktemp)
trap 'rm -f "$PAGE_TMP"' EXIT

wait_service_bound() {
    local i=0
    while [ "$i" -lt 20 ]; do
        MSYS_NO_PATHCONV=1 $ADB shell dumpsys accessibility 2>/dev/null | grep -q "com.anytouch.app" && return 0
        sleep 1; i=$((i + 1))
    done
    return 1
}

# 日志抓取：两条通道各治一种丢证，**只有 -d 那条做判据**。
# ① `logcat -d`（判据通道）：每次现起一个新进程，无主机侧缓冲问题；但它读的是设备 ring buffer，
#    MIUI 默认 buffer 很小——K40 实测 AnytouchRun 行约一分钟内被自家噪声挤掉（本轮 E5a 之后
#    `-d` 读不到刚写过的 404 行）。所以启动时先 -G 扩到 16M 并复查是否生效。
# ② 后台流（留档通道）：整轮 AnytouchRun 全量落本地文件，收工后随证据一起归档。
#    它**不能做判据**：adb.exe 的 stdout 重定向到文件是块缓冲（约 4KB 才刷一次），
#    过滤后行数少 → 一条已发生的日志可能几十秒都不出现在文件里；用它做等待=把"还没刷出来"
#    判成"产品没记账"，正是本轮 E5a 假红的来路。
logs() { MSYS_NO_PATHCONV=1 $ADB logcat -d -s AnytouchRun:* 2>/dev/null; }
count_line() { logs | grep -ac "$1" || true; }

LOG_STREAM=""
LOG_STREAM_PID=""
start_log_stream() {
    LOG_STREAM=$(mktemp)
    MSYS_NO_PATHCONV=1 $ADB logcat -v brief -s AnytouchRun:* >> "$LOG_STREAM" 2>/dev/null &
    LOG_STREAM_PID=$!
    # 扩 ring buffer：默认档太小，真机噪声一两分钟就把我们的行挤掉（见上①）
    MSYS_NO_PATHCONV=1 $ADB logcat -G 16M >/dev/null 2>&1
    local size
    size=$(MSYS_NO_PATHCONV=1 $ADB logcat -g 2>/dev/null | tr -d '\r' | grep -a 'main' | grep -ao '[0-9]*[KMG]' | head -1)
    case "$size" in
        *M|*G) log "ring buffer main=$size（已扩档，-d 判据通道可用）" ;;
        *) log "警告：ring buffer 仍是 ${size:-未知}，-d 可能读不到一分钟前的行（假红来源）" ;;
    esac
}
reset_logs() {
    [ -n "$LOG_STREAM" ] && : > "$LOG_STREAM"
    MSYS_NO_PATHCONV=1 $ADB logcat -c >/dev/null 2>&1
}

# 等一条日志出现（$1=固定串，$2=秒数）
wait_line() {
    local pat="$1" limit="${2:-15}" i=0 out=""
    while [ "$i" -lt "$limit" ]; do
        out=$(logs | grep -a "$pat" | tail -1 || true)
        [ -n "$out" ] && { printf '%s' "$out"; return 0; }
        sleep 1; i=$((i + 1))
    done
    return 1
}

# 注入一条（$1=extra 串，$2=keep_fg 真/假）：keep_fg=true 时 UI 留前台，随后的 dump 才看得见自家窗。
inject() {
    local extras="$1" keep="${2:-true}"
    MSYS_NO_PATHCONV=1 $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --ez keep_fg $keep $extras" >/dev/null 2>&1
    sleep 2
}
# 快发（不 sleep）：编译在跑的那几秒窗口里，脚本慢一拍窗口就关了（E8 双拒）。
fire() {
    local extras="$1" keep="${2:-true}"
    MSYS_NO_PATHCONV=1 $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --ez keep_fg $keep $extras" >/dev/null 2>&1
}

# ---------- 滚动取证：从顶到底逐屏 dump，拼成"整页文本"再查 ----------
SIZE=$(MSYS_NO_PATHCONV=1 $ADB shell wm size 2>/dev/null | tr -d '\r' | grep -o '[0-9]*x[0-9]*' | tail -1)
W=${SIZE%x*}; H=${SIZE#*x}
[ -n "${W:-}" ] && [ -n "${H:-}" ] || { echo "前置失败：读不到屏幕尺寸（wm size）"; exit 2; }
# 一律 500ms：设备实测同一坐标 250ms 一次滚了一次没滚（见证据 §5 探针 P2/P3），说明"滚没滚"不是
# 时长的单纯函数——快拖会带惯性 fling，dump 落点之间可能留下整段没读到的缝。
# 时长只是减震，真正的判据在 harvest_page 里：到底（连续两屏逐字相同）+ 无缝（相邻两屏有公共节点）。
swipe_up()   { MSYS_NO_PATHCONV=1 $ADB shell input swipe "$((W / 2))" "$((H * 72 / 100))" "$((W / 2))" "$((H * 26 / 100))" 500 >/dev/null 2>&1; sleep 1; }
swipe_down() { MSYS_NO_PATHCONV=1 $ADB shell input swipe "$((W / 2))" "$((H * 26 / 100))" "$((W / 2))" "$((H * 72 / 100))" 500 >/dev/null 2>&1; sleep 1; }

# harvest_page：回顶 → 逐屏 dump 直到"连续两屏一模一样"（=到底）。
# 滚不到底、或两屏之间**没有公共节点**（=屏与屏之间有缝）→ 一律不许判"屏上没有"：
# 首轮实测就是这么翻车的——250ms 快拖带惯性，dump 落点之间有缝，`byok_key_tail` 恰好整轮没进任何一屏，
# 于是"读不到"被写成了"面板缺格"（假红）。复测（探针 P2/P3）证明这跟时长不是单纯函数关系：
# 同一坐标 250ms 一次滚了一次没滚（到底了）。所以这里不押时长，押**可测终点**：
#   · 到底 = 连续两屏读数逐字相同；
#   · 无缝 = 相邻两屏至少共享一个自家节点（重叠区存在=没有整段内容被跳过）。
SHEET=/sdcard/.byokpage.xml
SHEET_TMP=$(mktemp)
FIRST_TMP=$(mktemp)
IDS_PREV=$(mktemp)
IDS_NOW=$(mktemp)
# 后台抓取流随脚本收（残留的 adb logcat 会一直占着设备端连接）
trap '[ -n "${LOG_STREAM_PID:-}" ] && kill "$LOG_STREAM_PID" 2>/dev/null; rm -f "$PAGE_TMP" "$SHEET_TMP" "$FIRST_TMP" "$IDS_PREV" "$IDS_NOW" "${LOG_STREAM:-}"' EXIT
# 只取自家 tag：Compose 的 testTag 在 dump 里就是**裸名** resource-id（无 `包名:id/` 前缀，设备实证），
# 而系统节点的 id 含 ':' 与 '.'，一律落在下面这个字符类之外。
own_ids() { grep -o 'resource-id="[a-z][a-z_0-9]*"' "$1" | sed 's/.*="//; s/"$//' | sort -u; }
harvest_page() {
    local max_passes="${1:-12}" i=0 prev_hash="" same=0 hash overlap
    : > "$PAGE_TMP"
    : > "$IDS_PREV"
    SWEEP_OK=0
    SWEEP_GAP=0
    SWEEP_PASSES=0
    MSYS_NO_PATHCONV=1 $ADB shell am start -n com.anytouch.app/.MainActivity >/dev/null 2>&1
    sleep 2
    # 收起软键盘：输入法在场时窗格被 pan，且焦点框会吞掉起点落在它内部的拖动
    MSYS_NO_PATHCONV=1 $ADB shell input keyevent 111 >/dev/null 2>&1
    sleep 1
    for _ in 1 2 3 4 5 6; do swipe_down; done
    while [ "$i" -lt "$max_passes" ]; do
        MSYS_NO_PATHCONV=1 $ADB shell uiautomator dump "$SHEET" >/dev/null 2>&1 || true
        MSYS_NO_PATHCONV=1 $ADB shell cat "$SHEET" 2>/dev/null | tr -d '\r' > "$SHEET_TMP" || true
        if [ ! -s "$SHEET_TMP" ]; then log "第 $((i + 1)) 屏 dump 为空（dump 失败≠屏上没有）"; fi
        cat "$SHEET_TMP" >> "$PAGE_TMP"
        MSYS_NO_PATHCONV=1 $ADB shell rm "$SHEET" >/dev/null 2>&1
        wait_service_bound || log "dump 后服务未回绑（本条读数仍可用，但下一条用例可能假红）"
        own_ids "$SHEET_TMP" > "$IDS_NOW"
        if [ -s "$IDS_PREV" ] && [ -s "$IDS_NOW" ]; then
            overlap=$(comm -12 "$IDS_PREV" "$IDS_NOW" | wc -l | tr -d ' ')
            if [ "${overlap:-0}" = "0" ]; then
                SWEEP_GAP=1
                log "第 $((i + 1)) 屏与上一屏**无公共节点**（这一跳跳过了整段内容=有缝）"
            fi
        fi
        cp "$IDS_NOW" "$IDS_PREV"
        hash=$(md5sum < "$SHEET_TMP" | cut -c1-32)
        if [ -n "$prev_hash" ] && [ "$hash" = "$prev_hash" ]; then
            SWEEP_OK=1; SWEEP_PASSES=$((i + 1)); return 0
        fi
        prev_hash="$hash"
        swipe_up
        i=$((i + 1))
    done
    SWEEP_PASSES=$i
    return 1
}
page_has()  { grep -aq "$1" "$PAGE_TMP" && echo 1 || echo 0; }
page_line() { grep -ao "$1" "$PAGE_TMP" | head -1 || true; }   # 取第一条命中（尾 4 位这类短读数）
# first_screen_dump：**不滚动**的单屏 dump，只用于"读到=正向证据"这一档取证。
# 它天生不能判"屏上没有"（折叠、视口、滚动位置都会让内容不在这一屏），所以未命中一律回退整页扫描。
first_screen_dump() {
    : > "$FIRST_TMP"
    MSYS_NO_PATHCONV=1 $ADB shell uiautomator dump /sdcard/.byokfirst.xml >/dev/null 2>&1 || return 1
    MSYS_NO_PATHCONV=1 $ADB shell cat /sdcard/.byokfirst.xml 2>/dev/null | tr -d '\r' > "$FIRST_TMP" || true
    MSYS_NO_PATHCONV=1 $ADB shell rm /sdcard/.byokfirst.xml >/dev/null 2>&1
    [ -s "$FIRST_TMP" ]
}
# 疑似 Key 形态字面**绝不回显内容**（连前缀都不行：证据文件也要过"无完整 Key 形态"这一关）：
# 只报长度与散列前 8 位，够定位到是哪个节点，不够还原出任何东西。
shape_digest() {
    local tok="$1"
    [ -z "$tok" ] && { printf 'none'; return 0; }
    printf 'len=%s sha=%s' "${#tok}" "$(printf '%s' "$tok" | sha1sum | cut -c1-8)"
}
assert_ui() {
    local name="$1" pat="$2" want="${3:-1}" got
    if [ "$want" = "0" ] && { [ "${SWEEP_OK:-0}" != "1" ] || [ "${SWEEP_GAP:-0}" = "1" ]; }; then
        bad "$name :: 本轮整页读数不可信（滚了 $SWEEP_PASSES 屏，到底=$SWEEP_OK 屏间有缝=$SWEEP_GAP）——'读不到'在这种读数上不成证据"
        return 0
    fi
    got=$(page_has "$pat")
    if [ "$got" = "$want" ]; then pass "$name（[$pat] 命中=$got）"; else
        bad "$name :: [$pat] 命中=$got（期望 $want）——整页已滚遍（$SWEEP_PASSES 屏），读不到就是真读不到"
    fi
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
MODEL=$(MSYS_NO_PATHCONV=1 $ADB shell getprop ro.product.model 2>/dev/null | tr -d '\r')
IS_EMU=0
case "$MODEL" in *sdk_gphone*|*Emulator*|*vbox*|*Google_API*) IS_EMU=1 ;; esac

# fresh_start：退到桌面 → `am kill`（**不是 force-stop**：雷 13 实证 force-stop 会清掉无障碍绑定条目）→
# 再进主窗。E1n"空闲即无陈旧结论格"只有在**新进程**上才成立：上一轮脚本留下的 report 会挂在 StateFlow 上，
# 拿"上一跑的结论"判"本轮空闲"就是拿旧痕当本轮证据。
# 设备实证（本轮）：HOME + `am kill` 之后 pid 不变——无障碍服务与悬浮窗让进程永远不算 empty，
# 所以这条在常驻服务型 App 上大概率只能记 SKIP（如实标注，不拿旧读数判过）。
pid_before=$(MSYS_NO_PATHCONV=1 $ADB shell pidof com.anytouch.app | tr -d '\r')
MSYS_NO_PATHCONV=1 $ADB shell input keyevent 3 >/dev/null 2>&1
sleep 1
MSYS_NO_PATHCONV=1 $ADB shell am kill com.anytouch.app >/dev/null 2>&1
sleep 2
MSYS_NO_PATHCONV=1 $ADB shell am start -n com.anytouch.app/.MainActivity >/dev/null 2>&1
sleep 3
pid_after=$(MSYS_NO_PATHCONV=1 $ADB shell pidof com.anytouch.app | tr -d '\r')
[ -n "$pid_after" ] || { echo "前置失败：新进程没起来（pid 空）"; exit 2; }
if [ "$pid_before" = "$pid_after" ]; then
    log "pid 未变（$pid_after）：am kill 没收到（应用仍被视为前台）→ E1n 的空闲档按'可能带旧痕'读，已如实标注"
    FRESH_PROC=0
else
    log "新进程 $pid_before → $pid_after（E1 的屏上形态是干净起点）"
    FRESH_PROC=1
fi
wait_service_bound || { echo "前置失败：新起后无障碍未回绑"; exit 2; }
start_log_stream
reset_logs
log "靶设备：$MODEL（模拟器=$IS_EMU；模拟器上一切结论不得写成「真机已过」）"

# ============================================================
# E1 面板屏上形态（无凭据即可判：滚遍整页找 testTag）
# ============================================================
harvest_page 12
if [ ! -s "$PAGE_TMP" ]; then echo "E1 前置失败：整页 dump 为空，后续不判"; exit 2; fi
if [ "$SWEEP_OK" != "1" ]; then
    echo "前置失败：整页只滚了 $SWEEP_PASSES 屏就没到底（滚不动=读数不完整，缺席类断言全不成证据）；后续不判"
    exit 2
fi
if [ "$SWEEP_GAP" = "1" ]; then
    log "警告：相邻两屏出现过'零公共节点'（有一整段内容没进任何一屏）→ 本轮所有'屏上没有'类断言按不可信处理"
fi
log "整页滚遍：$SWEEP_PASSES 屏后到底，屏间无缝=$([ "$SWEEP_GAP" = "0" ] && echo 是 || echo 否)"
assert_ui "E1a 意图框在屏"      'ai_intent'
assert_ui "E1b 编译按钮在屏"    'ai_compile'
assert_ui "E1c 屏上下文开关在屏" 'ai_ctx_switch'
assert_ui "E1d 服务地址框在屏"  'byok_base_url'
assert_ui "E1e 模型名框在屏"    'byok_model'
assert_ui "E1f Key 输入框在屏"  'byok_key'
assert_ui "E1g 保存钮在屏"      'byok_save'
assert_ui "E1h 清除钮在屏"      'byok_clear'
assert_ui "E1i 凭据状态行在屏"  'byok_key_tail'
# 待办 13② 的实证半边：整页变高之后，手指要用的两个录制入口仍必须"滚得到"
assert_ui "E1j 折叠线以下滚得到：开始录制" 'record_start'
assert_ui "E1k 折叠线以下滚得到：停止并编译" 'record_stop'

# 凭据状态行必须是 either/or 两档之一（先读它，后面几档缺席断言才有前提）
tail_read=$(page_line '本机[^"<]*保存 Key[^"<]*')
if printf '%s' "$tail_read" | grep -q '本机未保存 Key'; then
    KEY_PRESENT=0; pass "E1l 凭据状态行说「本机未保存 Key」 :: [$tail_read]"
elif printf '%s' "$tail_read" | grep -q '本机已保存 Key：\*\*\*'; then
    KEY_PRESENT=1; pass "E1l 凭据状态行已配（屏上只有尾 4 位） :: [$tail_read]"
else
    KEY_PRESENT=-1; bad "E1l 凭据状态行读不出 either/or :: [$tail_read]（两档话术互不雷同，读到第三种=串了）"
fi
# 未配凭据时屏上不许留任何"看起来还配着"的痕迹（无地址回显、无陈旧结论）——
# 这两格在已配凭据的机器上是**本该在场**的，所以只在未配档判。
if [ "$KEY_PRESENT" = "0" ]; then
    assert_ui "E1m 无凭据即无地址回显格" 'byok_host_notice' 0
    if [ "$FRESH_PROC" = "1" ]; then
        assert_ui "E1n 新进程空闲即无陈旧结论格" 'ai_report' 0
    else
        # 设备实证：`am kill` 收不走自家进程（无障碍服务 + 悬浮窗让它永远不算 empty）——
        # 这本身是产品事实（服务常驻，雷 7 家族），但测试通道禁 force-stop（雷 13：会清掉绑定条目），
        # 所以"空闲态不挂旧结论"只能在**装包后第一次进面板**那一次量（真机首跑顺带覆盖）。
        skip "E1n 空闲无陈旧结论格（进程收不走：服务+悬浮窗常驻；换新进程要 force-stop，测试通道禁用）"
    fi
else
    assert_ui "E1m 已配凭据必须有地址回显（裁 §3-4：Key 发往哪儿要先说一次）" 'byok_host_notice' 1
fi
# 屏上不得出现 Key 形态字面（整页文本扫：24+ 连续字母数字段）。未配 Key 时这一条必然为 0，
# 配了 Key 之后才是真判据（军令 §3-3：屏上只见尾 4 位）。
if [ "$SWEEP_GAP" = "1" ]; then
    skip "E1o 整页无 Key 形态字面（屏间有缝=页没读全，'没扫到'不能当'屏上没有'）"
else
key_shape=$(grep -ao '[A-Za-z0-9]\{24,\}' "$PAGE_TMP" | head -1 || true)
if [ -z "$key_shape" ]; then pass "E1o 整页无 Key 形态字面（24+ 连续字母数字段=0）"; else
    bad "E1o 整页出现 Key 形态字面 :: $(shape_digest "$key_shape")（屏上只见尾 4 位是硬约束；只报长度与散列，不回报内容）"
fi
fi

# ============================================================
# E2 无凭据点「AI 编译」：必拒、必说话、零上行、账不动
#    （这一条在已配凭据的机器上会自动改口为"跳过"，由 E5 真编译接管）
# ============================================================
if [ "$KEY_PRESENT" = "0" ]; then
    before_task=$(count_line 'S2SMOKE-TASK')
    before_ok=$(count_line 'S3SMOKE compile ok')
    inject "--es ai_intent e2-anytouch-intent --ez ai_compile true" true
    refused=$(wait_line "S3SMOKE compile refused gate=NO_CREDENTIAL" 15)
    if [ -n "$refused" ]; then pass "E2a 无凭据即拒且有独立档 :: $refused"; else
        bad "E2a :: 期望日志含 [compile refused gate=NO_CREDENTIAL]，实际无"
        log "近期编译日志: $(logs | grep -a 'S3SMOKE compile' | tail -3 | tr '\n' '~')"
    fi
    after_ok=$(count_line 'S3SMOKE compile ok')
    after_task=$(count_line 'S2SMOKE-TASK')
    if [ "$after_ok" = "$before_ok" ] && [ "$after_task" = "$before_task" ]; then
        pass "E2b 被拒不写账：compile ok 计数 $before_ok→$after_ok，任务建议 $before_task→$after_task"
    else
        bad "E2b :: 被拒却动了账（ok $before_ok→$after_ok / task $before_task→$after_task）"
    fi
    harvest_page 12
    assert_ui "E2c 拒因上屏（错误必显示，不是静默禁用）" 'ai_report'
    # 更强的那半边：屏上那句必须与日志那句**逐字同源**（同一份 userCopy 两处用；屏上另写一份=第二套真值）
    copy=$(printf '%s' "$refused" | sed -n 's/.*detail=//p')
    probe=$(printf '%s' "$copy" | cut -c1-24)
    if [ -n "$probe" ] && grep -qF "$probe" "$PAGE_TMP"; then
        pass "E2d 屏上话术与日志话术同源（整句取日志 detail=，比对前 24 字节全等 :: [$probe…]）"
    else
        bad "E2d :: 屏上找不到日志里那句拒因（前 24 字节=[$probe…]）——两套话术=第二套真值"
    fi
    # E2e：被拒的那一次编译**不许把 compileBusy 留在 true**（E0 §7-3 那颗残留雷的设备半边）。
    # 判据在 JVM 锁不住成"真翻格"这一面：AppState.compileBusy 是一格全局 MutableStateFlow，
    # 残留为 true 就是录制面永久拒开——只有真点一次开录能证明它自己复原了。
    fire "--es record_start com.android.settings" true
    ok_start=$(wait_line "S2SMOKE record start target=" 15)
    if [ -n "$ok_start" ]; then pass "E2e 被拒的编译没把互斥留在持有态：开录立刻可用 :: $ok_start"; else
        bad "E2e :: 编译被拒后开录仍不放行（期望 [record start target=]）——compileBusy 残留=录制面永久锁死"
        log "近期: $(logs | grep -a -E 'record start|compile refused' | tail -3 | tr '\n' '~')"
    fi
    # 收掉这一跑会话（停止并编译：空会话→既有"清零不写空建议"那条路），别把录制态留给下一条
    fire "--ez record_stop true" true
    stopped=$(wait_line "S2SMOKE compiled EMPTY task\|record stop refused" 20)
    if printf '%s' "$stopped" | grep -q 'compiled EMPTY task'; then
        pass "E2f 互斥释放后录制面本身照常转（停止并编译走通=空账清零，非被拒） :: $stopped"
    else
        bad "E2f :: 停止并编译没走通 :: [$stopped]"
        log "近期: $(logs | grep -a 'S2SMOKE record' | tail -3 | tr '\n' '~')"
    fi
else
    skip "E2a~E2f 无凭据即拒六断言（本机已配凭据，由 E5 真编译接管）"
    skip "E2b 被拒不写账（同上）"
fi

# ============================================================
# E3 空意图独立档：话术与 NO_CREDENTIAL 不雷同（八档失败必显的两档在设备上各就各位）
# ============================================================
if [ "$KEY_PRESENT" = "0" ]; then
    inject "--es ai_intent ' ' --ez ai_compile true" true
    e3=$(wait_line "S3SMOKE compile refused gate=EMPTY_INTENT" 15)
    if [ -n "$e3" ]; then pass "E3a 空意图独立拒档 :: $e3"; else
        bad "E3a :: 期望日志含 [compile refused gate=EMPTY_INTENT]，实际无"
    fi
    n_ok=$(count_line 'compile refused gate=NO_CREDENTIAL')
    n_empty=$(count_line 'compile refused gate=EMPTY_INTENT')
    # 只锁"两档各留各的痕"（互不雷同由 JVM 的 TaskAdmission 话术表锁住）；条数是否相等与设备无关。
    if [ "${n_empty:-0}" -ge 1 ] && [ "${n_ok:-0}" -ge 1 ]; then
        pass "E3b 两档各计各的：NO_CREDENTIAL=$n_ok EMPTY_INTENT=$n_empty（串档=一档冒充另一档）"
    else
        bad "E3b 档位计数异常 :: NO_CREDENTIAL=$n_ok EMPTY_INTENT=$n_empty"
    fi
else
    skip "E3a~E3b 空意图独立档（本轮不做有凭据下的空意图浪费请求；JVM 已锁两档话术互不雷同）"
fi

# ============================================================
# E4 执行中拒编译：preflight 的 RUNNING 档排在凭据之前 → 无凭据也能量
#    长任务配方同 U12：两步都找不到节点，但第一步 NODE_NOT_FOUND 就终止整跑（S1 既有语义，
#    设备回执实证 ok=0 total=1 stopped=true）→ 执行窗口约 15s，够用（编译预检是毫秒级）。
#    本段**一次 dump 都不做**（执行期 dump 会挤掉自家服务并取消 runTask）。
# ============================================================
SAFE='"safety":{"viewport_ok":true,"click_enabled":true,"requires_transition":false}'
LONG2="[{\"action_id\":\"e4a\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"__no_such_node_byok_smoke__\"},$SAFE},{\"action_id\":\"e4b\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"__no_such_node_byok_smoke__\"},$SAFE}]"
reset_logs
fire "--es task_json '$LONG2'" false
sleep 3
fire "--es ai_intent e4-should-not-run --ez ai_compile true" true
e4=$(wait_line "S3SMOKE compile refused gate=RUNNING" 15)
if [ -n "$e4" ]; then pass "E4a 执行中拒编译（门禁落入口，注入通道不例外） :: $e4"; else
    bad "E4a :: 期望日志含 [compile refused gate=RUNNING]，实际无"
    log "近期编译日志: $(logs | grep -a 'S3SMOKE compile' | tail -3 | tr '\n' '~')"
fi
e4ok=$(count_line 'S3SMOKE compile ok')
if [ "${e4ok:-0}" = "0" ]; then pass "E4b 执行中的那次编译一个字都没发出去（compile ok 计数=0）"; else
    bad "E4b :: 执行中却编成了（ok 计数=$e4ok，期望 0）"
fi
ended=$(wait_line "S1SMOKE ok=" 90)
if [ -n "$ended" ]; then pass "E4c 本轮执行正常收尾 :: $ended"; else bad "E4c :: 等不到执行结束回执"; fi

# ============================================================
# E5~E11：需要本机已保存的可用凭据（真 HTTPS 往返）。检测不到就逐条大声 SKIP。
# ============================================================
run_key_group() {
    # ---------- E5 真 HTTPS 一次编译：出账 + 词表条数 + 建议框与账逐字 ----------
    # 目标窗必须真是活动窗（词表是"当前屏幕"的账，不是自家设置页的账）：先起 Settings，
    # 再用不带 keep_fg 的注入（自家 UI 退后台）→ 采到的就是 Settings 的可见词表。
    MSYS_NO_PATHCONV=1 $ADB shell am start -n com.android.settings/.Settings >/dev/null 2>&1
    sleep 8
    # 意图一律 ASCII：中文经 Windows 侧命令行下发有乱码前科（雷 11 同族），而且产品定位海外、
    # 语料本就是英文（军令 §4-1）——用中文意图等于把"编码通道"和"模型能力"两件事混成一条断言。
    reset_logs
    # ⚠ `ai_compile true` 不能省：MainActivity 是"落意图 / 调开关 / 触发编译"三步分离
    #   （脚本要能先看词表账再决定编不编），只发 ai_intent 就永远等不到 compile 行——
    #   这条本批踩过两次（E5、E10 各一次），表现为"150s 一条日志都没有"的假红。
    # ⚠ `ctx_enabled on` 同理不能省：上一跑的 E10 就是专门把开关按到 off 的，而它活在**常驻进程**的
    #   StateFlow 上（E9 实证进程不因 am kill 而退）——不显式立回 on，E5 读到的是上一跑留下的 off，
    #   rows=0 于是被记成"采集口有问题"。开关默认开是**产品**的默认，不是**脚本**可以假设的前置：
    #   前置动作必须自复核，这里逐字写下要什么，不靠"应该还开着"。（off 字面只此一家，见 ByokPanelState）
    inject "--es ai_intent 'open the page where bluetooth can be turned on, one tap only' --es ctx_enabled on --ez ai_compile true" false
    sleep 2
    local e5
    # 预算 150s：必须**大于**传输层自己的 readTimeoutMs=120_000。同值等于自己判红——
    # 真机实测这一跑就是撞出来的：E5a 等 120s 判不过，而传输层的超时拒绝正好落在 120s 之后。
    # 同时把"被拒"也纳入等待条件：拒因 1 秒就到（换序/404 那类），没必要再空等两分钟。
    e5=$(wait_line "S3SMOKE compile ok steps=\|S3SMOKE compile refused" 150)
    if printf '%s' "$e5" | grep -q 'compile refused'; then
        bad "E5a 真 HTTPS 编译被拒 :: $e5"
        return 1
    fi
    if [ -n "$e5" ]; then pass "E5a 真 HTTPS 编译成功 :: $e5"; else
        bad "E5a :: 150s 内没等到 [compile ok steps=]（传输层超时上限 120s），真 HTTPS 未过"
        log "近期: $(logs | grep -a 'S3SMOKE compile' | tail -3 | tr '\n' '~')"
        return 1
    fi
    steps=$(printf '%s' "$e5" | sed -n 's/.*steps=\([0-9]*\).*/\1/p')
    rows=$(printf '%s' "$e5" | sed -n 's/.*rows=\([0-9]*\).*/\1/p')
    [ "${steps:-0}" -ge 1 ] && pass "E5b 产物步数=$steps（≥1）" || bad "E5b :: steps=$steps"
    # 裁 2：实际上行条数必须上屏（关着=0，开着=真采了几条）
    # 期望话术已收窄：rows=0 的**脚本侧**来路（上一跑 E10 把开关留在 off）现在由注入行显式排除，
    # 剩下的才真是"采集口/窗口归因"的账——不许拿"开关默认开"当前置，那是产品默认不是脚本权限。
    [ "${rows:-0}" -ge 1 ] && pass "E5c 词表真采了几条：rows=$rows 且已入日志（同一条账要上屏，见 E5d）" \
        || bad "E5c :: rows=$rows（本轮已显式 ctx_enabled on、目标窗是 Settings，采到 0 条=采集口或窗口归因有问题）"
    local task types unsupported
    # 标记用 **S3SMOKE-TASK**：那是 AI 落账口 acceptModelActions 自己的取件口（RecorderStore:214）。
    # 本批第一版在这里 grep 了 `S2SMOKE-TASK`——那是**录制**编译路径的标记，AI 路径不发这一行，
    # 于是"任务框里没有产物"判红，判的其实是脚本自己的口径错。两个 marker 各归一条路径，别混。
    task=$(logs | grep -a "S3SMOKE-TASK " | tail -1 || true)
    # 第二版口径错的收口：第一版接着猜"产物得是 click/open_url/set_text 之一"，而这一跑模型交的是
    # {"type":"key","value":{"key":"back"}}——:byok 的词表**明确授权** key（DslCompiler.kt:50/132/150），
    # 契约也留着 ActionType.KEY，于是"框里明明有产物"被记成"框里没有"=假红（与 E5e 取错 marker 同族）。
    # "框里有没有产物"只能看落账口自己序列化出来的东西，不能看脚本猜哪几种 type。
    types=$(printf '%s' "$task" | grep -ao '"type":"[a-z_]*"' | sed 's/.*"type":"//;s/"$//' | sort -u | tr '\n' ',')
    if printf '%s' "$task" | grep -aq 'S3SMOKE-TASK \[' && [ -n "${types//,/}" ]; then
        pass "E5e AI 产物已进任务框（框里的 type 集合=[$types]，建议流走的就是既有那条）"
    else
        bad "E5e :: 任务框里没有产物 :: [$task]"
    fi
    # E5i 是这一跑**逼出来的新判据**（不是凑数）：执行器只走 WAIT + CLICK/SCROLL/TYPE_TEXT
    # （NodeTaskRunner.kt:98-109），其余类型一律 unsupported_type + StopCode.EXECUTOR_ERROR（:111-121）。
    # 编译器词表与执行支持集**从未对拍**过：AI 交 key=back 时账落得下、屏上看得到，一点「执行任务」
    # 第一步就以执行器错误收官（见本条紧随其后的 E5h 原文）。这一条红=真缺陷，不洗成绿。
    unsupported=$(printf '%s' "$types" | tr ',' '\n' | grep -v '^$' \
        | grep -vw -e click -e type_text -e scroll -e wait || true)
    if [ -n "$unsupported" ]; then
        bad "E5i :: 产物含执行面不支持的类型 [$unsupported]：编译器授权了跑不动的动作（假产物），回放以 EXECUTOR_ERROR 收尾。两侧支持集需一句裁决收口（收紧词表 vs 执行面补类型），本轮不擅自改任一侧"
    else
        pass "E5i 产物每一步都在执行面支持集内（click/type_text/scroll/wait） :: types=[$types]"
    fi
    harvest_page 8
    assert_ui "E5d 词表账上屏（ai_ctx_notice：几条/剔了多少）" 'ai_ctx_notice'
    assert_ui "E5f 结论上屏（ai_report 非空话术）" 'ai_report'
    # 屏上只许有尾 4 位：整页再扫一次 Key 形态（这一次是真凭据在场的扫描）
    local shape
    shape=$(grep -ao '[A-Za-z0-9]\{24,\}' "$PAGE_TMP" | head -1 || true)
    if [ "$SWEEP_GAP" = "1" ]; then
        skip "E5g 真凭据在场整页无 Key 形态（屏间有缝=页没读全，这一条不能宣布通过）"
    elif [ -z "$shape" ]; then pass "E5g 真凭据在场时整页仍无 Key 形态字面"; else
        bad "E5g :: 屏上出现 Key 形态字面 $(shape_digest "$shape")（本批最严重缺陷，当场撤回重做）"
    fi
    # V-3 的反面：AI 产物落账后按它执行不得被"账框不符"误拒（同 U10 家族，这里走的是同一条 submitTask）。
    # 产物里若含单引号，走 adb 命令行会被截断——那属于**通道限制**，不拿它判产品，记 SKIP。
    if printf '%s' "$task" | grep -q "'"; then
        skip "E5h AI 产物按框执行（产物含单引号，adb 通道会截断；改由人手点「执行任务」验）"
    elif ! printf '%s' "$task" | grep -aq 'S3SMOKE-TASK \['; then
        # 空产物不配判"放行"：本批第一跑就是这样判红的——E5e 取错标记后 task 是空串，
        # 于是给执行器喂了个空 JSON，它回了 `S1SMOKE ok=0 total=0`，而"等到一条 S1SMOKE ok="
        # 竟被记成 PASS。空跑一次也"说话"，所以只等说话=假绿。宁可 SKIP 也不判过。
        bad "E5h :: 没有可注入的产物（task=[$task]），这一条判不了'放行'"
    else
        reset_logs
        inject "--es task_json '$(printf '%s' "${task#*S3SMOKE-TASK }")'" false
        local v3
        v3=$(wait_line "S1SMOKE ok=" 90)
        local total
        total=$(printf '%s' "$v3" | sed -n 's/.*total=\([0-9]*\).*/\1/p')
        # total≥1 = 执行器真按框里的步数走了一遍（0 步=喂进去的就是空的，见上）
        # 本条只管"没被 V-3 误拒 + 步数按框走"；步本身跑不跑得动归 E5i，别把这条读成"产物跑通了"。
        if [ -n "$v3" ] && [ "${total:-0}" -ge 1 ]; then pass "E5h AI 产物照常放行（没被 V-3 误拒，且真按框跑了 total=$total 步；步内类型能否执行见 E5i） :: $v3"; elif [ -n "$v3" ]; then
            bad "E5h :: 执行器接了但零步（total=$total）——框里的产物根本没进执行，'没被误拒'这句不成立 :: $v3"
        else
            bad "E5h :: AI 产物被 V-3 拦下或没跑 :: [$v3]"
            log "近期: $(logs | grep -a 'submit refused' | tail -2 | tr '\n' '~')"
        fi
    fi

    # ---------- E8 编译在跑时两入口双拒（裁决 S3-R4-1 的设备半边；JVM 已锁判据，此处锁"真点下去"） ----------
    MSYS_NO_PATHCONV=1 $ADB shell am start -n com.anytouch.app/.MainActivity >/dev/null 2>&1
    sleep 2
    reset_logs
    # ctx_enabled 与 E5 同理显式立 on：不押"上一跑留下的开关"。词表有行才带得出长 prompt、
    # 窗口才够宽——但这只是**减震**，下面 E8d/e 的判据不押时长（押时长正是上一版假红的来路）。
    fire "--es ai_intent 'compile again: open the bluetooth settings page' --es ctx_enabled on --ez ai_compile true" true
    fire "--es record_start com.android.settings" true
    fire "--ez record_stop true" true
    local r1 r2
    r1=$(wait_line "record start refused gate=COMPILING" 20)
    r2=$(wait_line "record stop refused gate=COMPILING" 20)
    if [ -n "$r1" ]; then pass "E8a 编译在跑拒开录 :: $r1"; else bad "E8a :: 期望 [record start refused gate=COMPILING]"; fi
    if [ -n "$r2" ]; then pass "E8b 编译在跑拒停止并编译 :: $r2"; else bad "E8b :: 期望 [record stop refused gate=COMPILING]"; fi
    # 成功开录的那一行是 `S2SMOKE record start target=<pkg>`（被拒行 `... start refused` 也含 "record start"，
    # 所以这里必须量**成功行的字面**，量前缀会把拒因行自己数成"开录成功"=假绿）。
    local started
    started=$(count_line 'S2SMOKE record start target=')
    if [ "${started:-0}" = "0" ]; then pass "E8c 拒=整条不动：本轮零次成功开录（会话没被起）"; else
        bad "E8c :: 被拒却起了会话（record start target= 计数=$started）"
    fi
    # E8d/E8e 判据（本批第二版）。第一版是 `harvest_page 6` 之后查两格，结果两条判红，而日志里
    # `record stop rejection expired gate_was=COMPILING` 带时间戳证明：话术在那次扫描期间就正常过期了
    # ——产品撤陈旧话术做对了，脚本拿它判红 = **自造假红**。改成"正向取证优先"三档：
    #   ① 两格就在录制按钮下方（MainActivity.kt:131-148），编译在跑时滚到顶做**单屏** dump，
    #      读到即 PASS——"读到"是正向证据，不需要整页扫描背书（读不到不算反证：折叠/视口会吞）；
    #   ② 未命中才回退整页扫描（沿用"到底 + 屏间无缝"那条纪律）；
    #   ③ 仍未命中时，只有"编译全程在跑 + 整页可信扫遍"才成立为缺陷，
    #      否则（窗口在一次扫描内就关了 / 读数不可信）一律大声 SKIP 并贴原文。
    local shown1=0 shown2=0 i_top
    MSYS_NO_PATHCONV=1 $ADB shell am start -n com.anytouch.app/.MainActivity >/dev/null 2>&1
    sleep 1
    for i_top in 1 2 3; do swipe_down; done
    if first_screen_dump; then
        grep -aq 'record_rejection' "$FIRST_TMP" && shown1=1
        grep -aq 'record_stop_rejection' "$FIRST_TMP" && shown2=1
    fi
    if [ "$shown1" = 1 ] && [ "$shown2" = 1 ]; then
        pass "E8d 开录拒因上屏 :: 编译在跑时单屏 dump 正向命中"
        pass "E8e 停止拒因另起一格 :: 与开录拒因同屏并存（两件事同时红，没互相盖）"
    else
        local done_before done_after expired_line
        done_before=$(count_line 'S3SMOKE compile ok\|S3SMOKE compile refused')
        harvest_page 6
        done_after=$(count_line 'S3SMOKE compile ok\|S3SMOKE compile refused')
        if [ "$shown1" != 1 ] && [ "$(page_has 'record_rejection')" = 1 ]; then shown1=1; fi
        if [ "$shown2" != 1 ] && [ "$(page_has 'record_stop_rejection')" = 1 ]; then shown2=1; fi
        expired_line=$(logs | grep -a 'rejection expired\|S3SMOKE compile ok\|S3SMOKE compile refused' | tail -1)
        if [ "$shown1" = 1 ] && [ "$shown2" = 1 ]; then
            pass "E8d/E8e 屏上拒因由整页扫描补齐 :: 命中=1/1"
        elif [ "${done_before:-0}" != "0" ] || [ "${done_after:-0}" != "0" ]; then
            skip "E8d/E8e 不判红：编译窗口在一次整页扫描内就归位了，此刻话术本就该撤（命中=$shown1/$shown2）:: $expired_line"
        elif [ "${SWEEP_OK:-0}" != 1 ] || [ "${SWEEP_GAP:-0}" = 1 ]; then
            skip "E8d/E8e 读数不可信：滚了 $SWEEP_PASSES 屏 到底=$SWEEP_OK 屏间有缝=$SWEEP_GAP，'读不到'在这种读数上不成证据"
        else
            bad "E8d/E8e :: 编译全程在跑、整页扫遍仍读不到拒因格（命中=$shown1/$shown2）:: $expired_line"
        fi
    fi
    local e8r
    e8r=$(wait_line "S3SMOKE compile" 150)
    expired=$(wait_line "record stop rejection expired" 25)
    if [ -n "$expired" ]; then pass "E8f 编译归位即撤陈旧拒因（过期边真的在设备上转） :: $expired"; else
        bad "E8f :: 期望 [record stop rejection expired]，实际无（编译都回来了还挂着'编译中'=自造假红）"
        log "近期: $(logs | grep -a -E 'rejection|compile' | tail -4 | tr '\n' '~')"
    fi
    [ -n "$e8r" ] && log "第二跑结论（E8 的编译窗口）：$e8r"

    # ---------- E9 Keystore 跨进程：真把进程收走之后，必须读回同一尾 4 位 ----------
    # 本批实测：常驻服务型 App（无障碍 + 悬浮窗 + 前台服务）在 `am kill` 下**根本不会被收走**
    # （K40 两轮 pid 都是 26536），于是 E9b 期望的"新进程现读"永远等不到——那不是产品读不回，
    # 是脚本没造出"跨进程"这个前提。给 E9a 换一条真能收走进程、又不动无障碍绑定条目的通道：
    # `adb install -r` 同一枚 APK（系统会杀进程、保留 /data 与 Keystore 别名），比 am kill 硬、
    # 又比 force-stop 干净（雷 13：force-stop 会清掉 enabled_accessibility_services 条目）。
    local tail_before tail_after pid_before pid_after
    # kill 前那一半必须**现读**，而且读不到就得承认前提没成立。
    # 本批第一版直接吃 PAGE_TMP 的余货，而那份余货是 E5 的 `harvest_page 8`：切片 D 之后整页变高
    # （AI 结论/词表账/两个拒因格都在其间插入），8 屏扫不到凭据行 → tail_before=空 →
    # E9c 判红判的是"尾 4 位不吻合"，而实际是脚本没把左半边读到（假红族第三条，同 E5e/E9 的 am kill）。
    # 现在：回主窗 + 整页扫遍（12 屏上限）+ 最多两轮；仍读不到就记 SKIP，不拿它判产品。
    tail_before=""
    local try_9=1
    while [ "$try_9" -le 2 ]; do
        MSYS_NO_PATHCONV=1 $ADB shell am start -n com.anytouch.app/.MainActivity >/dev/null 2>&1
        sleep 2
        harvest_page 12
        tail_before=$(printf '%s' "$(page_line '本机已保存 Key：[^\"<]*')" | sed 's/本机已保存 Key：//')
        [ -n "$tail_before" ] && break
        log "E9 前置第 $try_9 轮：整页扫遍（$SWEEP_PASSES 屏，无缝=$([ "$SWEEP_GAP" = 0 ] && echo 是 || echo 否)）没现读到凭据行 → 重来一轮"
        try_9=$((try_9 + 1))
    done
    pid_before=$(MSYS_NO_PATHCONV=1 $ADB shell pidof com.anytouch.app | tr -d '\r')
    if [ -n "${E9_APK:-}" ] && [ -f "${E9_APK:-}" ]; then
        MSYS_NO_PATHCONV=1 $ADB install -r "$E9_APK" >/dev/null 2>&1 \
            || { bad "E9a :: install -r 失败，跨进程前提没造出来"; return 1; }
        sleep 3
        pid_after=$(MSYS_NO_PATHCONV=1 $ADB shell pidof com.anytouch.app | tr -d '\r')
        if [ -n "$pid_after" ] && [ "$pid_after" != "$pid_before" ]; then
            pass "E9a 进程被真收走并重来（pid $pid_before→$pid_after；install -r 保留 /data 与 Keystore）"
        else
            bad "E9a :: 重装后 pid 仍是 [$pid_after]（原 $pid_before），跨进程读数不成立"
            return 1
        fi
    else
        log "E9_APK 未给：退化成 `am kill`（常驻服务型 App 上大概率收不走，届时 E9b 只能记'同进程回填'）"
        MSYS_NO_PATHCONV=1 $ADB shell am kill com.anytouch.app >/dev/null 2>&1
        sleep 3
        MSYS_NO_PATHCONV=1 $ADB shell dumpsys activity processes 2>/dev/null | grep -q "com.anytouch.app" \
            && log "am kill 后进程仍在（前台占用/系统策略）：E9 的读数退化为'同进程回填'，已如实记" \
            || pass "E9a 进程确实被收走（am kill 生效，非 force-stop：无障碍条目不动）"
    fi
    reset_logs
    # 重装等于服务重启：无障碍要回绑才谈得上后面的词表采集（不回绑就继续跑=拿"采不到"判产品）
    wait_service_bound || log "警告：E9 重启后无障碍未在 20s 内回绑，后续词表类读数按不可信处理"
    # 先回主窗再等"现读"：凭据回填发生在面板组装时，窗都没起就等 = 自己造空等
    MSYS_NO_PATHCONV=1 $ADB shell am start -n com.anytouch.app/.MainActivity >/dev/null 2>&1
    sleep 3
    loaded=$(wait_line "S3SMOKE config loaded tail=" 30)
    # 右半边与左半边必须走**同一条取数通道**：两边都是整页扫遍（12 屏上限），
    # 一边扫 3 屏一边扫 12 屏的比对不叫逐字一致，叫挑口径。
    harvest_page 12
    tail_after=$(printf '%s' "$(page_line '本机已保存 Key：[^\"<]*')" | sed 's/本机已保存 Key：//')
    if [ -n "$loaded" ]; then pass "E9b 重进界面从 Keystore 现读回填 :: $loaded"; else
        bad "E9b :: 期望 [S3SMOKE config loaded tail=]，实际无（存了却读不回=写成功≠存上了）"
    fi
    if [ -z "$tail_before" ]; then
        # 左半边没读到=前提未成立，这一条既不能判绿也不能把账算到产品头上（屏上读不到≠Keystore 没存）。
        skip "E9c 屏上尾 4 位跨进程比对（kill 前那一半整页扫遍仍现读不到凭据行；E9b 的日志半边已单独记）"
    elif [ "$tail_before" = "$tail_after" ]; then
        pass "E9c 屏上尾 4 位跨进程逐字一致 :: [$tail_after]"
    else
        bad "E9c :: 尾 4 位不吻合（kill 前=[$tail_before] 重启后=[$tail_after]）"
    fi

    # ---------- E10 编译期屏上下文开关：off 档必须真零条上行（裁 2 的"关闭开关时零条"设备半边） ----------
    MSYS_NO_PATHCONV=1 $ADB shell am start -n com.android.settings/.Settings >/dev/null 2>&1
    sleep 8
    reset_logs
    inject "--es ctx_enabled off --es ai_intent 'with context off: open the bluetooth settings page' --ez ai_compile true" false
    local e10
    e10=$(wait_line "S3SMOKE compile refused\|S3SMOKE compile ok" 150)
    local r10
    r10=$(printf '%s' "$e10" | sed -n 's/.*rows=\([0-9]*\).*/\1/p')
    if [ -n "$r10" ] && [ "$r10" = "0" ]; then pass "E10 off 档上行零条：rows=0 :: $e10"; else
        bad "E10 :: 开关关了却仍有条数（rows=[$r10]）或这一跑没出账 :: [$e10]"
    fi

    # ---------- E11 清除双槽（opt-in：E_WIPE=1；真删老板的凭据，默认不动） ----------
    if [ "${E_WIPE:-0}" = "1" ]; then
        reset_logs
        # 清除要走面板按钮（判据在 UI 的 onClick → gateway.clear()，没有注入通道）：
        # 这里只断言"点了之后两槽复查双双消失 + 屏上回到未保存"，点这一下由老板的手指完成。
        log "E11 需要人手：请在屏上滚到「清除本机凭据」点一下（脚本不下发 Key、也不模拟手指点自家面板）。"
        local wiped
        wiped=$(wait_line "S3SMOKE config wipe cleared=" 180)
        if printf '%s' "$wiped" | grep -q 'cleared=true leftover=\[\]'; then
            pass "E11a 清除后两槽复查双双消失 :: $wiped"
        else
            bad "E11a :: 期望 [config wipe cleared=true leftover=[]]，实际 [$wiped]"
        fi
        harvest_page 8
        assert_ui "E11b 屏上回到「本机未保存 Key」" '本机未保存 Key'
        assert_ui "E11c 清除后不留地址回显" 'byok_host_notice' 0
        inject "--es ai_intent e11-after-wipe --ez ai_compile true" true
        local e11d
        e11d=$(wait_line "S3SMOKE compile refused gate=NO_CREDENTIAL" 20)
        if [ -n "$e11d" ]; then pass "E11d 清干净后再编译立刻回到 NO_CREDENTIAL（没留'看起来还配着'） :: $e11d"; else
            bad "E11d :: 清除后仍能编译或拒档不对"
            log "近期: $(logs | grep -a 'S3SMOKE compile' | tail -2 | tr '\n' '~')"
        fi
    else
        skip "E11a~E11d 清除双槽（真删本机凭据需重新手输；确认要跑就 E_WIPE=1）"
    fi
}

if [ "$KEY_PRESENT" = "1" ]; then
    run_key_group
elif [ "$KEY_PRESENT" = "0" ]; then
    log "本机未保存凭据 → E5~E11 需要真端点往返，逐条记 SKIP（不算 PASS）"
    skip "E5a~E5h 真 HTTPS 编译出账 / 词表条数 / AI 产物不被 V-3 误拒（需老板手机上手输 Key）"
    skip "E8a~E8f 编译在跑时开录+停止双拒与过期边（需秒级真往返窗口；判据侧 JVM 已锁）"
    skip "E9a~E9c 真 Keystore 跨进程读回同尾 4 位"
    skip "E10 关开关=零条上行的设备半边"
    skip "E11a~E11d 清除双槽"
else
    bad "E1n 之后凭据状态未定 → E 系列不再往下跑（未知状态既不判过也不判不过）"
fi

echo
printf 'byok-smoke: 断言 %s 条，跳过 %s 条，%s\n' "$ran" "$skipped" "$([ "$fail" = 0 ] && echo '无失败项' || echo '有失败项')"
if [ "$skipped" -gt 0 ]; then
    echo "诚实边界：跳过的都是'真机真 Key'那四条设备点（真 HTTPS/真 Keystore/词表真采/编译期双拒），"
    echo "           模拟器与无凭据机器上一律不记 PASS（S3-R5：注入走 byok-credential-inject.sh，本脚本仍不发 Key 字面量）。"
fi
exit "$fail"
