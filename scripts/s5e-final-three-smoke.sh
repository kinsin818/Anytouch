#!/usr/bin/env bash
# Anytouch S5-e 末三功能设备冒烟（AVD 靶机；K40/K80 本批零触碰）
# 军令件：orders/ANYTOUCH-S5e-final-three-features-ORDER.md 第 1/2/3 条 + 执行面 §3 判据 3~7
#         + RULINGS S5-R12 三裁（①只碰非高危步·高危照旧每次弹 ②我的任务走同一落账口 ③手动步只准节点/输入/等待，禁坐标）
#
# 用法：ANDROID_SERIAL=emulator-5554 EXPECTED_VC=5 bash scripts/s5e-final-three-smoke.sh
#       E9_APK=app/build/outputs/apk/debug/app-debug.apk 可选：给了才跑 F9c/F9d 的跨进程载入（不给按实记 SKIP，不记绿）
# 前置：恰好 1 台靶机在线、装好**本轮** debug APK、versionCode 与 EXPECTED_VC 相符（防"跑的是上一版"）、无障碍已绑。
#
# 十六格（每格只钉三裁/判据一条，判据只加不减）：
#   F1  手动插步入账并真进回放（收口步数 4 不是 3 —— 判据 5）
#   F2  禁坐标可证伪：产物每条 target 为 null、无坐标样键（判据 7"不是嘴上说没有"）
#   F3  手填的高危定位线索照样弹面板，且该步零重试（判据 6 + 三裁①后半句）
#   F4  插步四档拒因各归各 + 五条脏请求零放行（注入通道不等于放行）
#   F5  执行中插步必拒 RUNNING（插这一支不在禁编辑门禁之外）
#   F6  非高危定位失败：整步重试恰 2 次到顶、三次仍失败按现行停（三裁①前半句 · 判据 §3-1）
#   F7  成功步零重试行（负例半边：留痕只在真发生处报，账才读得出哪一步重试过）
#   F8  急停先于额度：重试途中按球即停，剩余额度当场归零
#   F9  存 → 盘上件在 filesDir/saved_tasks 且零坐标 → 冷启（install -r 同 md5）后仍能载入（三裁②）
#   F10 载入即执行走的那同一条派发口（收口步数=存档步数，没有第三条通道）
#   F11 删干净：删后再载必拒 NOT_FOUND、盘上件同步消失（没有半删状态）
#   F12 存档三档拒因：空名 / 重名 / 未知名
#   F13 载入的那本账照样可插步（证明"不另起存储"：换进来的还是同一本可编辑的账）
#   F14 面板上屏：每步后的 "+" 钮可见、展开后"这里没有 x/y 可填"那句英文在屏（三裁③落在屏上不只在工单里）
#   F15 执行中点载入必拒 RUNNING（判据 4 前半："我存过的"不豁免任何一档）
#   F16 含不支持类型的存档必被词表档整本拒、落账口零条 written（判据 4 后半：半本进账=第二套真值）
#
# 洁净纪律（沿用 S2/S5/S5-d/T3 血账）：
# - 执行期禁 uiautomator dump（会挤掉自家服务打断在跑任务）；每次 idle dump 后一律 wait_bound 复绑。
# - 跑任务的注入**不带 keep_fg**：handled 后 MainActivity 自己 moveTaskToBack，目标页才成为活动窗
#   （带 keep_fg 去跑=执行器面对的是自家窗，F3 那一类节点侧判据会读成"定位不到"，把测试面的账记到产品头上）。
# - 点球/点搜索框都是测试通道模拟手指（InputManager），产品资产零坐标（红线 D 不触）。
# - 禁 force-stop 自家（无障碍常驻，force-stop 会连服务一起收走=下一条假红）；跨进程只走 install -r 同一枚 apk。
# - 零网络零模型：本批所有格子都不发网络请求（重试/存档/插步住在执行与编辑侧，编译面不参与）。
# - 设备独占锁：同机第二驱动当场终止。
set -u
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."

ADB="adb"
[ -n "${ANDROID_SERIAL:-}" ] && ADB="adb -s $ANDROID_SERIAL"
RAW_DIR="${RAW_DIR:-evidence/S5/raw}"
EXPECTED_VC="${EXPECTED_VC:-5}"
LOCK=/tmp/s5e-final-three.lock
SAVED="${SAVED:-s5eprobe}"
E9_APK="${E9_APK:-}"
RID_SEARCH="${RID_SEARCH:-com.google.android.settings.intelligence:id/open_search_view_edit_text}"

fail=0; passed=0; skipped=0
RED='\033[0;31m'; GRN='\033[0;32m'; NCT='\033[0m'
pass() { printf "${GRN}PASS${NCT} %s\n" "$1"; passed=$((passed + 1)); }
bad()  { printf "${RED}FAIL${NCT} %s\n" "$1"; fail=1; }
log()  { printf '      | %s\n' "$*"; }
skip() { log "SKIP $*"; skipped=$((skipped + 1)); }
RAW="$RAW_DIR/s5e-final-three-$(date +%Y%m%d-%H%M%S).raw.txt"

# ---- 预检（先修后跑：任一不满足当场停，不烧轮次赌运气） ----
DEVS=$(adb devices | grep -cw device)
if [ "$DEVS" -ne 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then bad "预检 :: adb 在线 $DEVS 台且未指定 ANDROID_SERIAL——禁双驱动"; exit 2; fi
case "$($ADB shell getprop ro.product.model 2>/dev/null | tr -d '\r')" in
    *Kiwi*|*K40*|kona*|*M2012K11AC*) bad "预检 :: 靶机疑似真机——S5-e 本批 K40/K80 零触碰"; exit 2 ;;
esac
if [ -f "$LOCK" ] && kill -0 "$(cat "$LOCK" 2>/dev/null)" 2>/dev/null; then
    bad "预检 :: 另一个 s5e-final-three（pid $(cat "$LOCK")）还活着——禁双驱动，本跑终止"; exit 2
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
# 只取 versionCode= 后那一段数字（整行抹非数字会得到 42634——S5-d 首跑实测，预检会把自己的判据读错）
VC=$($ADB shell dumpsys package com.anytouch.app 2>/dev/null | tr -d '\r' | grep -m1 versionCode | sed 's/.*versionCode=//; s/[^0-9].*//')
[ "$VC" = "$EXPECTED_VC" ] || { bad "预检 :: 机上 versionCode=$VC 与本轮应有 $EXPECTED_VC 不符——装错包，跑了也是白跑"; exit 2; }
wait_bound || { bad "预检 :: 无障碍服务未绑定"; exit 2; }
SIZE=$($ADB shell wm size | tail -1 | tr -d '\r' | sed 's/.*: *//')
W=${SIZE%x*}; H=${SIZE#*x}
BALL_X=$(( W * 1002 / 1080 )); BALL_Y=$(( H * 1272 / 2400 ))
mkdir -p "$RAW_DIR" .smoke-tmp
log "靶机 $($ADB shell getprop ro.build.version.sdk | tr -d '\r') 号 API · ${W}x${H} · vc=$VC · 球(${BALL_X},${BALL_Y})"
{
    echo "# s5e-final-three 预检 ok · api=$($ADB shell getprop ro.build.version.sdk | tr -d '\r') ${W}x${H} vc=$VC"
    echo "# 靶机=$($ADB shell getprop ro.product.model | tr -d '\r') · SAVED=$SAVED · E9_APK=${E9_APK:-未给}"
} > "$RAW"

XMIG=/sdcard/.s5e.xml; XHOST=.smoke-tmp/s5e.xml
dump_idle() {
    $ADB shell uiautomator dump /sdcard/.s5e.xml >/dev/null 2>&1
    $ADB shell cat "$XMIG" | tr -d '\r' > "$XHOST"
    wait_bound || { bad "dump 后服务未复绑 :: 下一条判据不可信"; return 1; }
}
logs() { $ADB logcat -d -s AnytouchRun:* AnytouchOverlay:* 2>/dev/null; }
clr()  { $ADB logcat -c >/dev/null 2>&1; }
cnt()  { logs | grep -acE "$1" || true; }
# 编辑/存档类注入：keep_fg=true，UI 留前台，后面的 dump 才看得见步序账
inject() { $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --ez keep_fg true $*" >/dev/null 2>&1; sleep 2; }
# 派发类注入：**不带** keep_fg（见头部走位纪律第二条）
run()    { $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity $*" >/dev/null 2>&1; }
wait_line() { # $1=固定串 $2=秒上限 → 命中打印该行
    local pat="$1" lim="${2:-30}" i=0 out=""
    while [ "$i" -lt "$lim" ]; do
        out=$(logs | grep -a "$pat" | tail -1 || true)
        [ -n "$out" ] && { printf '%s' "$out"; return 0; }
        sleep 1; i=$((i + 1))
    done
    return 1
}
latest_task() { local l; l=$(logs | grep -a "S2SMOKE-TASK " | tail -1 || true); printf '%s' "${l#*S2SMOKE-TASK }"; }

# 预置会话档 = 冻结 RecorderSession.serialize 的真产物（逐字锁在 UiSmokeSessionFixtureTest，脚本不手抄近似值）
SESSION_JSON='{"format":"anytouch.recorder.session","version":1,"targetPkg":"com.android.settings","state":"STOPPED","overflowCount":0,"events":[{"type":"window","pkg":"com.android.settings","windowTitle":"session-open","timestampMs":1700000001000},{"type":"action","kind":"CLICK","text":null,"confirmed":true,"timestampMs":1700000002000,"snapshot":{"resourceId":null,"text":"Connected devices","contentDesc":null,"className":"android.widget.TextView","pkg":"com.android.settings","indexPath":[0,1,2]}},{"type":"action","kind":"CLICK","text":null,"confirmed":true,"timestampMs":1700000003000,"snapshot":{"resourceId":null,"text":"Connection preferences","contentDesc":null,"className":"android.widget.TextView","pkg":"com.android.settings","indexPath":[0,1,2,0]}},{"type":"action","kind":"CLICK","text":null,"confirmed":true,"timestampMs":1700000004000,"snapshot":{"resourceId":null,"text":"Bluetooth","contentDesc":null,"className":"android.widget.TextView","pkg":"com.android.settings","indexPath":[0,1,2,1]}}],"rejectedEvents":[]}'
SAFE='"safety":{"viewport_ok":true,"click_enabled":true}'
# 纯 wait 单步：跑必成，F7 靠它，不依赖任何页态
ONE_WAIT="[{\"action_id\":\"ow1\",\"type\":\"wait\",\"source\":\"node\",\"value\":{\"ms\":400},$SAFE}]"
# 必不命中的节点（F6/F8 用它吃重试额度；id 带本脚本 pid，不与任何真节点撞车）
MISS="[{\"action_id\":\"m1\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"resource_id\":\"com.anytouch.s5e.absent.$$\"},$SAFE}]"

to_settings_home() {
    $ADB shell am force-stop com.android.settings >/dev/null 2>&1
    $ADB shell am force-stop com.google.android.settings.intelligence >/dev/null 2>&1
    $ADB shell am start -n com.android.settings/.Settings >/dev/null 2>&1
    sleep 8
}
# 账本复位：预置会话编译成 3 步（每组都在已知步数上开工，不拿上一组残账判这一组）
# 必须**两次注入**：一条 `am start` 里 session_json 与 record_stop 的处理顺序是先 stop 后注入
# （run1 实测：`record stop rejected: 无会话` 紧跟 `record inject accepted`），第一次靠上一格剩的
# 会话侥幸编出 3 步＝把"复位"做成了碰运气。先认"会话已受理"这一行，再要编译回执。
reset_ledger() {
    clr
    inject "--es session_json '$SESSION_JSON'"
    wait_line "S2SMOKE record inject accepted" 20 >/dev/null || return 1
    inject "--ez record_stop true"
    wait_line "S2SMOKE compiled ok actions=3" 25 >/dev/null
}
# 高危格前置：搜索页 + 框内 text=password（钉在**执行器将要定位的那一枚节点**上，id 与 text 同节点；
# 与 s5d 同一口径——首跑血账：页面上确有 password 却长在另一枚节点上，定位失败读起来像产品不放行）
to_search_with_password() { # $1=格名
    local tag="$1" i=0 target
    $ADB shell am force-stop com.android.settings >/dev/null 2>&1
    $ADB shell am force-stop com.google.android.settings.intelligence >/dev/null 2>&1
    $ADB shell am start -n com.android.settings/.Settings >/dev/null 2>&1; sleep 6
    dump_idle || return 1
    # 点亮判据用"搜索框那枚节点进树"（run4 血账：run2/run3 裸点侥幸中过两次，这一轮同一行没中，
    # F3 前置当场读不到 text=password 的格子——前置不成立就 SKIP，绝不拿"没弹面板"记产品的红）
    if ! tap_open "Search settings" "$RID_SEARCH"; then
        bad "[$tag] :: Search settings 在树里却四档内点不亮搜索页（红的是注入通道，不是产品）"
        wait_bound; return 1
    fi
    $ADB shell input text password >/dev/null 2>&1; sleep 2
    while [ "$i" -lt 10 ]; do
        $ADB shell uiautomator dump /sdcard/.s5e.xml >/dev/null 2>&1
        $ADB shell cat "$XMIG" | tr -d '\r' > "$XHOST"
        target=$(grep -o "<node[^>]*resource-id=\"$RID_SEARCH\"[^>]*>" "$XHOST" | grep -c 'text="password"')
        [ "${target:-0}" -ge 1 ] && { wait_bound || { bad "[$tag] :: 前置后服务未复绑"; return 1; }; return 0; }
        sleep 1; i=$((i + 1))
    done
    bad "[$tag] :: 读不到 resource-id=$RID_SEARCH 且 text=password 的那一枚节点（高危前置未就位＝面板永不弹，本格不成立）"
    wait_bound; return 1
}

T0CELL=$(date +%s); cell_start() { T0CELL=$(date +%s); }; cell_elapsed() { echo $(( $(date +%s) - T0CELL )); }

# 自家 Compose 面"用手指点开某一格"的校验环。run3 F14b 假红根因（本批实测四档样本同一偏量）：
# 同一次 dump 报 "+ Step" 中心 y=271 → 点 271 不亮、点 186/200 亮；另一次报 y=662 → 点 560/610 亮、
# 点 514 不亮：`input tap` 的落点比 dump 报的格心**低约 85px**（View 面的 Settings/悬浮球无此现象，
# 是注入通道与 Compose 命中层对不上，不在产品判据里）。所以测试面既不硬扛常数、也不"多点几次碰运气"：
# 先按实测偏量点，命中一律用**产品自己的状态变化**验（面板那一格的 testTag 上屏才算点亮），
# 未命中就重新 dump 再换一档；四档内点不亮由调用方记红，红的是通道，绝不记成"屏上没画"。
tap_open() { # $1=锚点（tap_node 正则）$2=命中判据（grep -E）→ 0=点亮且命中
    local anchor="$1" hit="$2" xy x y off
    for off in 85 0 45 130; do
        dump_idle || return 1
        xy=$(python scripts/tap_node.py "$XHOST" "$anchor" 2>>"$RAW")
        [ -n "$xy" ] || return 1
        x=${xy% *}; y=${xy#* }
        $ADB shell input tap "$x" "$((y - off))" >/dev/null 2>&1; sleep 2
        dump_idle || return 1
        grep -qE "$hit" "$XHOST" && return 0
    done
    return 1
}
# 收尾关面板：判据是"panel 标记从树上消失"，关不掉只按实记（下一条格自带 reset_ledger，面板态不会串账）
tap_close() { # $1=锚点 $2=消失判据
    local anchor="$1" gone="$2" xy i=0
    while [ "$i" -lt 4 ]; do
        dump_idle || return 1
        grep -qE "$gone" "$XHOST" || return 0
        xy=$(python scripts/tap_node.py "$XHOST" "$anchor" 2>>"$RAW")
        [ -n "$xy" ] || return 1
        $ADB shell input tap "${xy% *}" "$((${xy#* } - 85))" >/dev/null 2>&1; sleep 2
        i=$((i + 1))
    done
    return 1
}

# 面板点亮之后把这一层读全（run5 F14b 血账）：点亮的是第 0 行那一格时，面板比剩下的屏幕高，
# "没有 x/y 可填"那句和 Cancel 都在折叠线以下——只读点亮那一帧，读到的是半张面板。
# 与旧写法（点完盲扫 6 屏取并集）的区别在**每一帧都必须还看得见 panel 标记**：
# 面板一旦收起就当场停手，绝不把"面板已经不在了的那几屏"混进并集里当证据。
# 产出：$3 文件=逐帧并集；退 0=并集里出现过 $2（读全了），退 1=四屏内没读到或面板中途收起。
panel_read() { # $1=panel 标记正则 $2=要读到的锚点 $3=并集落盘路径
    local mark="$1" want="$2" out="$3" i=0
    : > "$out"
    while [ "$i" -lt 4 ]; do
        dump_idle || return 1
        grep -qE "$mark" "$XHOST" || { cat "$XHOST" >> "$out"; return 1; }
        cat "$XHOST" >> "$out"
        grep -q "$want" "$out" && return 0
        $ADB shell input swipe "$((W / 2))" 1800 "$((W / 2))" 900 300 >/dev/null 2>&1
        sleep 1; i=$((i + 1))
    done
    return 1
}

# 滚到"某一枚锚点进树"为止（$1=锚点字面）：折叠线以下的节点压根不进无障碍树（S5-d 血账同族），
# 不坐实锚点就读缺席＝把"这一屏恰好没画到"当成"屏上没有"——红的是判据，不是产品。
# run1/run2 实测：页顶独有的是 target_pkg，账本头与第一行的 + 钮都在**再往下一屏**，
# 只在原地 dump 一次永远读不到。所以先把页按回顶（六次手指下滑），再从顶逐屏往下找；
# 每一屏 dump 后一律 wait_bound，八屏仍读不到退 1 由调用方按"前置未立"记账。
scroll_until() { # $1=锚点
    local i=0
    for _ in 1 2 3 4 5 6; do
        $ADB shell input swipe "$((W / 2))" 500 "$((W / 2))" 1800 300 >/dev/null 2>&1; sleep 1
    done
    while [ "$i" -lt 8 ]; do
        $ADB shell uiautomator dump "$XMIG" >/dev/null 2>&1
        $ADB shell cat "$XMIG" | tr -d '\r' > "$XHOST"
        grep -q "$1" "$XHOST" && { wait_bound || return 1; return 0; }
        $ADB shell input swipe "$((W / 2))" 1800 "$((W / 2))" 500 300 >/dev/null 2>&1
        sleep 1; i=$((i + 1))
    done
    wait_bound; return 1
}

# ================= F1 手动插步入账 + 真进回放（判据 5） =================
cell_start
clr
reset_ledger || bad "F1 前置 :: 步序账未复位到 3 步"
inject "--es step_insert_at 3 --es step_insert_type wait --es step_insert_name f1_wait --es step_insert_ms 1200"
f1e=$(wait_line "step edit ok=insert index=3" 15)
if [ -n "$f1e" ]; then pass "F1a :: 插步入账留痕（$f1e）"; else bad "F1a :: 期望 [step edit ok=insert index=3]，实际无"; fi
TASK=$(latest_task)
N=$(printf '%s' "$TASK" | grep -ao '"action_id"' | wc -l | tr -d ' ')
if [ "$N" = "4" ]; then pass "F1b :: 发布建议含插进去那一步（账与框同数=4，V-3 才逐字放行）"; else bad "F1b :: 任务 JSON 步数=$N（期望 4） :: $TASK"; fi
to_settings_home
clr
run "--es task_json '$TASK'"
f1r=$(wait_line "S1SMOKE ok=" 90)
if printf '%s' "$f1r" | grep -q "ok=4 total=4"; then
    pass "F1c :: 手动步真进回放并按 4 步收口（$f1r · 本格墙钟 $(cell_elapsed)s）"
else
    bad "F1c :: 期望 [ok=4 total=4]，实际 [$f1r]（插步入账却不落地=编辑面与执行面两本账）"
fi
echo "# [F1] receipt=$f1r elapsed=$(cell_elapsed)s" >> "$RAW"

# ================= F2 禁坐标可证伪（读产物字节，不读注释） =================
BADK=$(printf '%s' "$TASK" | grep -oE '"(x|y|point|coordinate|coordinates|bounds|offset)":[^,}]*' | tr '\n' ' ')
NULLS=$(printf '%s' "$TASK" | grep -o '"target":null' | wc -l | tr -d ' ')
if [ -z "$BADK" ] && [ "$NULLS" = "4" ]; then
    pass "F2 :: 四条 target 全为 null、坐标样键零个（禁坐标落在产物字节上，不在注释里）"
else
    bad "F2 :: 坐标样键=[$BADK] target:null 计数=$NULLS（期望 0 键 / 4 个 null）"
fi
echo "# [F2] task=$TASK" >> "$RAW"

# ================= F3 手填高危线索照样弹 + 该步零重试（判据 6 · 三裁①） =================
cell_start
if ! to_search_with_password F3; then
    skip "F3 :: 高危前置未就位（F3a/F3b/F3c 一律不记绿，也不把前置账算到产品头上）"
else
    reset_ledger || bad "F3 前置 :: 账未复位"
    inject "--es step_insert_at 0 --es step_insert_type click --es step_insert_name f3_high --es step_insert_resource_id $RID_SEARCH"
    [ "$(cnt 'step edit ok=insert index=0')" -ge 1 ] || bad "F3 前置 :: 高危手动步没进账"
    HTASK=$(latest_task)
    clr
    run "--es task_json '$HTASK'"
    if wait_line "second-confirm panel shown" 40 >/dev/null; then
        pass "F3a :: 手填的定位线索命中高危面板（门禁不认来路只认节点=判据 6）"
    else
        bad "F3a :: 面板未弹（手填高危不弹=把安全门禁绕进了一条新来路）"
    fi
    # 面板不点：15s 默认拒。这一格钉的是"高危步失败不当场重试"
    f3r=$(wait_line "S1SMOKE ok=" 60)
    R3=$(cnt 'S5ESMOKE step retry')
    if [ "$R3" = "0" ]; then pass "F3b :: 高危步零重试行（三裁①：失败可能其实已落地，二触=同动作跑第二遍）"; else bad "F3b :: 高危步出现 $R3 条 step retry"; fi
    if printf '%s' "$f3r" | grep -qF 'stop="PASSWORD:password"'; then pass "F3c :: 未获确认按现行停下（$f3r）"; else bad "F3c :: 收口不是高危档默认拒 stop=\"PASSWORD:password\" [$f3r]"; fi
    echo "# [F3] receipt=$f3r retry_lines=$R3" >> "$RAW"
fi

# ================= F4 插步拒因五档各归各 + 零放行 =================
cell_start
reset_ledger || bad "F4 前置 :: 账未复位"
before=$(cnt 'step edit ok=insert')
clr
inject "--es step_insert_at 3 --es step_insert_type click --es step_insert_name f4_clue"
inject "--es step_insert_at 3 --es step_insert_type scroll --es step_insert_name f4_type --es step_insert_text Bluetooth"
inject "--es step_insert_at 3 --es step_insert_type click --es step_insert_name f4_num --es step_insert_text Bluetooth --es step_insert_instance -1"
inject "--es step_insert_at 3 --es step_insert_type wait --es step_insert_name ''"
inject "--es step_insert_at 99 --es step_insert_type wait --es step_insert_name f4_range"
misses=""
for g in INSERT_CLUE INSERT_TYPE INSERT_BAD_NUMBER BLANK_NAME OUT_OF_RANGE; do
    [ "$(cnt "step edit refused gate=$g")" -lt 1 ] && misses="$misses $g"
done
if [ -z "$misses" ]; then pass "F4a :: 五档拒因各有一条（归因分明，用户知道改哪一格）"; else bad "F4a :: 缺拒因:$misses"; fi
after=$(cnt 'step edit ok=insert')
if [ "$after" = "$before" ]; then pass "F4b :: 五条脏请求零放行（ok=insert 计数恒 $before）"; else bad "F4b :: ok=insert 从 $before 涨到 $after（漏网一条=脏步入账）"; fi

# ================= F5 执行中插步必拒 RUNNING =================
cell_start
reset_ledger || bad "F5 前置 :: 账未复位"
TASK5=$(latest_task)
to_settings_home
clr
# 3 轮 × 5 秒间隔：造一个确定的"执行中"窗口给注入通道撞门禁
run "--es task_json '$TASK5' --es repeat_count 3 --es repeat_interval 5"
sleep 3
inject "--es step_insert_at 1 --es step_insert_type wait --es step_insert_name f5_running"
if [ "$(cnt 'step edit refused gate=RUNNING')" -ge 1 ]; then pass "F5 :: 执行中插步被拒 RUNNING（账不许在跑动的路上动笔）"; else bad "F5 :: 执行中插步没拒（RUNNING 档漏在插这一支上）"; fi
$ADB shell input tap $BALL_X $BALL_Y >/dev/null 2>&1
wait_line "USER_STOP" 30 >/dev/null || log "F5 :: 按球后未读到 USER_STOP 行（不影响本格判据，只记机器态）"

# ================= F6 非高危定位失败：重试恰 2 次到顶（判据 §3-1） =================
cell_start
clr
run "--es task_json '$MISS'"
f6r=$(wait_line "S1SMOKE ok=" 120)
R6=$(cnt 'S5ESMOKE step retry')
logs | grep -a "S5ESMOKE step retry" | sed 's/^/# [F6] /' >> "$RAW"
if [ "$R6" = "2" ]; then pass "F6a :: 重试留痕恰 2 条（retry=1/2、2/2，额度封顶不外溢=军令要求 1）"; else bad "F6a :: 期望 2 条 step retry，实际 $R6 条"; fi
if printf '%s' "$f6r" | grep -q "ok=0 total=1"; then pass "F6b :: 三次尝试仍失败按现行停下报错（$f6r）"; else bad "F6b :: 收口行不对 [$f6r]"; fi
E6=$(cell_elapsed)
if [ "$E6" -ge 40 ]; then pass "F6c :: 整步重跑真发生（本格墙钟 ${E6}s ≥ 3×15s 定位窗，计数不是空转）"; else bad "F6c :: 墙钟仅 ${E6}s（重试像没跑，F6a 的计数存疑）"; fi

# ================= F7 成功步零重试行（负例半边） =================
cell_start
clr
run "--es task_json '$ONE_WAIT'"
f7r=$(wait_line "S1SMOKE ok=" 40)
R7=$(cnt 'S5ESMOKE step retry')
if printf '%s' "$f7r" | grep -q "ok=1 total=1" && [ "$R7" = "0" ]; then
    pass "F7 :: 跑通的步不写重试账（留痕只在发生处报，F6 那 2 条才读得出真失败）"
else
    bad "F7 :: 纯 wait 步收口 [$f7r] · 重试行=$R7（期望 ok=1 total=1 与 0 条）"
fi

# ================= F8 急停先于额度 =================
cell_start
clr
run "--es task_json '$MISS'"
sleep 8
$ADB shell input tap $BALL_X $BALL_Y >/dev/null 2>&1
f8r=$(wait_line "S1SMOKE ok=" 60)
R8=$(cnt 'S5ESMOKE step retry')
if printf '%s' "$f8r" | grep -q 'stop="user_stop"'; then pass "F8a :: 重试途中按球即停（$f8r）"; else bad "F8a :: 按球后收口不是 user_stop [$f8r]"; fi
if [ "$R8" -le 1 ]; then pass "F8b :: 急停后额度当场归零（本跑 step retry=$R8 条，未跑满 2 次）"; else bad "F8b :: step retry=$R8（按球在 8s 却吃满两次=停不下来）"; fi

# ================= F9 存 → 盘上件 → 冷启仍在（三裁②） =================
cell_start
reset_ledger || bad "F9 前置 :: 账未复位"
inject "--es step_insert_at 3 --es step_insert_type wait --es step_insert_name f9_wait --es step_insert_ms 300"
wait_line "step edit ok=insert index=3" 15 >/dev/null || bad "F9 前置 :: 插步未放行（存档格缺 4 步账）"
clr
inject "--es task_save $SAVED"
f9s=$(wait_line "saved task op=save ok" 20)
if printf '%s' "$f9s" | grep -q "name=\"$SAVED\" steps=4"; then pass "F9a :: 存档成功且存的正是那本账（steps=4 与屏上同数，$f9s）"; else bad "F9a :: 存档回执不对 [$f9s]"; fi
DISK=$($ADB shell run-as com.anytouch.app cat files/saved_tasks/tasks.json 2>/dev/null | tr -d '\r')
echo "# [F9] saved_tasks.json=$DISK" >> "$RAW"
if printf '%s' "$DISK" | grep -q "\"name\":\"$SAVED\"" && printf '%s' "$DISK" | grep -q '"target":null'; then
    if printf '%s' "$DISK" | grep -qE '"(x|y|point|coordinate|coordinates|bounds|offset)":'; then
        bad "F9b :: 存档件里出现坐标样键（存储面第二道缝）"
    else
        pass "F9b :: 盘上件在 filesDir/saved_tasks/tasks.json、target 全 null、零坐标键（红线 I：不碰 SharedPreferences/外存）"
    fi
else
    bad "F9b :: 盘上件读不到该条目（写回执成功却没有这件=写成功≠存上了）"
fi
log "F9 :: filesDir 清单=[$($ADB shell run-as com.anytouch.app ls files 2>/dev/null | tr -d '\r' | tr '\n' ' ')]"
if [ -n "$E9_APK" ] && [ -f "$E9_APK" ]; then
    PID_B=$($ADB shell pidof com.anytouch.app | tr -d '\r')
    [ -n "$PID_B" ] || bad "F9c 前置 :: 重装前就没进程，'跨进程'无从谈起"
    $ADB install -r "$E9_APK" >/dev/null 2>&1 || bad "F9c :: install -r 失败，跨进程前提没造出来"
    sleep 3
    # 重装把常驻进程收走后它不会自己爬起来：中途读数**为空正是进程真死了的正证**，
    # 不是失败。所以先录空档，再显式拉起一次拿新 pid——两枚 pid 不同才算跨过了进程边界。
    PID_MID=$($ADB shell pidof com.anytouch.app | tr -d '\r')
    [ -n "$PID_MID" ] || $ADB shell am start -f 536870912 -n com.anytouch.app/.MainActivity --ez keep_fg true >/dev/null 2>&1
    sleep 4
    PID_A=$($ADB shell pidof com.anytouch.app | tr -d '\r')
    if [ -n "$PID_A" ] && [ "$PID_A" != "$PID_B" ]; then
        pass "F9c :: 进程真被收走并换成了新 pid $PID_B→$PID_A（重装中途读数=[$PID_MID]；install -r 保 /data，非 force-stop）"
    else
        bad "F9c :: 重装后 pid=[$PID_A]（原 $PID_B、中途 [$PID_MID]），跨进程读数不成立"
    fi
    wait_bound || bad "F9 :: 重启后无障碍未在 25s 内回绑（后面的读数按不可信处理）"
    clr
    inject "--es saved_load $SAVED"
    f9l=$(wait_line "saved task op=load ok" 25)
    if printf '%s' "$f9l" | grep -q "steps=4"; then pass "F9d :: 冷启后仍能载入 4 步（每写一次现读盘，不养内存第二本=真持久化）"; else bad "F9d :: 冷启后载入不对 [$f9l]"; fi
else
    skip "F9c/F9d 跨进程载入 :: E9_APK 未给（am kill 收不走常驻服务型 App，F-3 在册；不记绿也不记红）"
fi

# ================= F10 载入即执行：那同一条派发口 =================
cell_start
to_settings_home
clr
run "--es saved_run $SAVED"
f10l=$(wait_line "saved task op=load ok" 20)
if [ -n "$f10l" ]; then pass "F10a :: 载入走落账口（origin=saved，与预制模板同一条 acceptModelActions，$f10l）"; else bad "F10a :: 载入回执缺席"; fi
f10r=$(wait_line "S1SMOKE ok=" 90)
if printf '%s' "$f10r" | grep -q "ok=4 total=4"; then pass "F10b :: 点一次就载入并跑起来，按存档步数收口（$f10r，派发口没第三条）"; else bad "F10b :: 期望 [ok=4 total=4]，实际 [$f10r]"; fi

# ================= F11 删干净 =================
cell_start
inject "--es saved_delete $SAVED"
f11d=$(wait_line "saved task op=delete ok" 20)
if [ -n "$f11d" ]; then pass "F11a :: 删除回执（$f11d）"; else bad "F11a :: 删除回执缺席"; fi
inject "--es saved_load $SAVED"
if wait_line "saved task op=load refused gate=NOT_FOUND" 15 >/dev/null; then pass "F11b :: 删后再载必拒 NOT_FOUND（没有半删状态）"; else bad "F11b :: 删后仍能载入或拒因不对"; fi
DISK2=$($ADB shell run-as com.anytouch.app cat files/saved_tasks/tasks.json 2>/dev/null | tr -d '\r')
if printf '%s' "$DISK2" | grep -q "\"name\":\"$SAVED\""; then bad "F11c :: 盘上件仍有这一条（屏上删了、盘上没删=两本账）"; else pass "F11c :: 盘上件里这条真没了"; fi

# ================= F12 存档三档拒因 =================
cell_start
clr
inject "--es task_save ''"
f12a=$(wait_line "saved task op=save refused gate=BLANK_NAME" 15)
inject "--es task_save f12dup"
wait_line "saved task op=save ok name=\"f12dup\"" 15 >/dev/null || bad "F12 前置 :: 第一次保存未成功（重名档无从判）"
inject "--es task_save f12dup"
f12b=$(wait_line "saved task op=save refused gate=DUPLICATE_NAME" 15)
inject "--es saved_load f12never"
f12c=$(wait_line "saved task op=load refused gate=NOT_FOUND" 15)
[ -n "$f12a" ] && pass "F12a :: 空名拒 BLANK_NAME（注入通道允许送空串，正是为了让这一档在设备面可证伪）" || bad "F12a :: 空名未拒"
[ -n "$f12b" ] && pass "F12b :: 重名拒 DUPLICATE_NAME（不静默覆写用户存过的任务）" || bad "F12b :: 重名未拒"
[ -n "$f12c" ] && pass "F12c :: 未知名载入拒 NOT_FOUND" || bad "F12c :: 未知名未拒"
inject "--es saved_delete f12dup"
wait_line "saved task op=delete ok name=\"f12dup\"" 15 >/dev/null || log "F12 :: 收尾未读到 f12dup 删除回执（不留脏件，按实记）"

# ================= F13 载入的账照样可插步（不另起存储的反证） =================
cell_start
inject "--es task_save f13tmp"
wait_line "saved task op=save ok name=\"f13tmp\"" 15 >/dev/null || bad "F13 前置 :: 未存上"
inject "--es saved_load f13tmp"
wait_line "saved task op=load ok" 15 >/dev/null || bad "F13 前置 :: 未载入"
clr
inject "--es step_insert_at 0 --es step_insert_type wait --es step_insert_name f13_wait --es step_insert_ms 100"
if [ "$(cnt 'step edit ok=insert index=0')" -ge 1 ]; then pass "F13 :: 换进来的那本账仍可插步（同一写口·同一区间口径，没有第二本只读账）"; else bad "F13 :: 载入后的账插不动（存档来路成了第二本账）"; fi
inject "--es saved_delete f13tmp"
wait_line "saved task op=delete ok" 15 >/dev/null || log "F13 :: 收尾删除未见回执（按实记）"

# ================= F14 面板上屏：+ 钮与"没有 x/y 可填" =================
cell_start
reset_ledger || bad "F14 前置 :: 账未复位"
if scroll_until "step_insert_toggle_0"; then
    pass "F14a :: 每步后面的 + Step 钮在无障碍树里（军令要求 3 的入口上了屏）"
    # 点亮一律走 tap_open：命中判据是"面板那一格自己的 testTag 上屏"，不是"我点了两下"
    # （run3 F14b 的假红就红在按 dump 报的格心裸点——见上方校验环注释的四档实测偏量）。
    PANEL=.smoke-tmp/f14-panel.txt
    if tap_open "\+ Step" "step_insert_panel_[0-9]+"; then
        # 点亮那帧先留底，再按"面板仍在树上"的口径把这一层读全
        cp "$XHOST" "$PANEL"
        panel_read "step_insert_panel_[0-9]+" "no x/y to fill in here" "$PANEL" || true
        grep -oE 'step_insert_[a-z_0-9]+' "$PANEL" | sort -u | sed 's/^/# [F14] 面板格: /' >> "$RAW"
        if grep -q "no x/y to fill in here" "$PANEL"; then
            pass "F14b :: 面板已点亮并明写按节点树找目标、这里没有 x/y 可填（三裁③的边界在屏上，用户看得出不是漏了）"
        else
            bad "F14b :: 面板已点亮（testTag 在树）却在那几屏里读不到那句英文（面板与判据两本账）"
        fi
        if grep -qE 'step_insert_(x|y|coord|point)[a-z_0-9]*"' "$PANEL"; then
            bad "F14c :: 面板里出现坐标格（坐标入口回潮）"
        else
            pass "F14c :: 面板逐屏（每帧都还看得见 panel 标记）里没有任何坐标输入位"
        fi
        tap_close "Cancel \\+" "step_insert_panel_" || log "F14 :: 面板未关掉（按实记，下一条格自带 reset_ledger，不串账）"
    else
        bad "F14b :: + 钮在树里却四档内点不亮面板（红的是测试通道，不是产品：绝不改记成'屏上没画'）"
    fi
else
    bad "F14 前置 :: 从页顶往下 8 屏仍读不到 step_insert_toggle_0（加步入口整条不在树上，不记绿）"
fi

# ================= F15 载入这条来路不绕门禁·RUNNING 档（判据 4 前半） =================
# 三裁②的全部风险都在这一格：如果"我存过的"能盖过"现在有人在跑"，落账口就有了第二套口径。
cell_start
reset_ledger || bad "F15 前置 :: 账未复位"
inject "--es task_save f15probe"
wait_line "saved task op=save ok name=\"f15probe\"" 20 >/dev/null || bad "F15 前置 :: 存档未成（没有可载入的那条，拒就没处试）"
clr
# 派发一枚必不命中的步：三次 15s 定位窗≈45s 的在跑窗口，载入注入正落在窗口中段
run "--es task_json '$MISS'"
sleep 4
inject "--es saved_load f15probe"
f15=$(wait_line "saved task op=load refused gate=RUNNING" 15)
if [ -n "$f15" ]; then pass "F15 :: 执行中点载入被落账口按 RUNNING 拒（存档来路不豁免任何一档）"; else bad "F15 :: 执行中载入没拒（读到 [$f15]）——存档这条道绕过了门禁"; fi
if [ "$(cnt 'saved task op=load ok')" = "0" ]; then pass "F15b :: 拒而未放行（本轮零条 op=load ok）"; else bad "F15b :: 拒因写了却仍载入了（红字与放行同时成立=两套真值）"; fi
f15end=$(wait_line "S1SMOKE ok=" 90)
log "F15 :: 在跑窗口收口=$f15end"
inject "--es saved_delete f15probe"
wait_line "saved task op=delete ok name=\"f15probe\"" 15 >/dev/null || log "F15 :: 收尾删除未见回执（按实记，不留残档给下一格）"

# ================= F16 载入这条来路不绕门禁·词表档（判据 4 后半） =================
# 造一份"与本 build 词表脱钩"的存档只能改盘上件：产品侧永远写不出 type=swipe 的条目（三裁③把
# 手动步的词表钉死在 click/type_text/wait），而老版本存过的条目正是这一档要拦的对象。
# 写通道走 run-as + /data/local/tmp 中转（app 私有件、debug 包专用），零网络零坐标。
cell_start
DIRTY=.smoke-tmp/f16-dirty-tasks.json
printf '%s' '[{"name":"f16dirty","actions":[{"action_id":"d1","type":"swipe","target":null,"value":{"dir":"up"},"source":"node","safety":{"viewport_ok":true,"click_enabled":true,"requires_transition":false}}]}]' > "$DIRTY"
echo "# [F16] 脏存档=$(cat "$DIRTY")" >> "$RAW"
$ADB push "$DIRTY" /data/local/tmp/f16.json >/dev/null 2>&1
$ADB shell run-as com.anytouch.app cp /data/local/tmp/f16.json files/saved_tasks/tasks.json >/dev/null 2>&1
if [ "$($ADB shell run-as com.anytouch.app cat files/saved_tasks/tasks.json 2>/dev/null | grep -c f16dirty)" -ge 1 ]; then
    clr
    inject "--es saved_load f16dirty"
    f16=$(wait_line "saved task op=load refused gate=UNSUPPORTED_TYPE" 15)
    if printf '%s' "$f16" | grep -q 'type=swipe'; then pass "F16 :: 含不支持类型的存档被词表档整本拒（$f16）"; else bad "F16 :: 脏存档没被词表档拦下（读到 [$f16]）"; fi
    if [ "$(cnt 'S3SMOKE model ledger written')" = "0" ]; then
        pass "F16b :: 拒的整半本一条没落账（clr 之后落账口零条 written，屏上账没被动过）"
    else
        bad "F16b :: 词表档拒了却仍有 written 行（半本进账=第二套真值）"
    fi
    $ADB shell run-as com.anytouch.app rm files/saved_tasks/tasks.json >/dev/null 2>&1
    $ADB shell rm /data/local/tmp/f16.json >/dev/null 2>&1
else
    bad "F16 前置 :: 脏存档写不进 app 私有件（本格不成立，不记绿）"
fi

echo
echo "汇总：通过 $passed / 跳过 $skipped（跳过=前置未立，不记绿也不记红）"
echo "原始读数：$RAW"
if [ "$fail" -eq 0 ]; then echo "s5e-final-three: ALL PASS"; else echo "s5e-final-three: 有失败项"; fi
exit "$fail"
