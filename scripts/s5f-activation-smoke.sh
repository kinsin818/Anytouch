#!/usr/bin/env bash
# Anytouch S5-f 激活码/付费墙设备冒烟（avd34，K40/K80 真机零触碰）
# 军令件：orders/ANYTOUCH-S5f-activation-code-ORDER.md（6 条）+ 附页 §3 判据 1~7
# 裁决在册：orders/RULINGS-20260922.md 的 S5-R13（S5 批）
#
# 用法：E9_APK=app/build/outputs/apk/debug/app-debug.apk EXPECTED_VC=6 ANDROID_SERIAL=emulator-5554 \
#       bash scripts/s5f-activation-smoke.sh
# 前置：恰好 1 台 AVD 在线、装好**本轮** debug APK（E9 语义沿用 S5-d/S5-e：不给 E9_APK=没装新码也照跑=假绿，硬性要求）、
#       versionCode 与 EXPECTED_VC 相符、**机上那份 apk 的 md5 与盘上发布件逐字相同**（跑前对账"机上包=当前源码"）、无障碍已绑。
#
# 七格（每格只钉附页一条，判据只加不减）：
#   G1 未激活态：三枚进墙入口的**注入通道**同样被拒（门禁落入口不落按钮）+ 单发同一条任务照跑（对照格，判据 5）
#   G2 Activate 钮点开对话框、框内三枚 tag 齐、那句"不联网"上屏、空框点 Unlock 落 EMPTY、Cancel 真收起（判据 1）
#   G3 拒因分档：打字通道那一档（CHECKSUM，买家最常见的手滑）+ 其余四档走注入通道（同一个校验器，无旁路）；
#      六档话术**从屏上逐条收进来比对**、互不相同，且被拒时不改写屏上解锁态（判据 2）
#   G4 golden 码逐字打进框 → 解锁；屏上/日志/盘上都只有尾四位（判据 3 + 红线 H 精神面）
#   G5 解锁后：同三条注入语句逐条放行、三句 Upgrade 提示消失（判据 5 的反向半边）
#   G6 install -r 换进程 → 冷启动仍以磁盘件为凭（判据 4）
#   G7 注入复位 → 翻回未激活（提示回来、入口再拒一次、盘上 flag 撤干净），设备收尾在"未激活"这一态
#
# 洁净纪律（沿用 S2/S5/S5-e/S3 血账）：
# - 执行期禁 uiautomator dump；每次 idle dump 后一律 wait_bound 复绑再走下一步。
# - 点亮一律用**产品自己的状态变化**验（日志行/那一格 testTag 上屏/该格文字变了），不是"我点了两下"；
#   Compose 面 `input tap` 落点比 dump 报的格心低约 85px（S5-e run3 实测四档同偏），一律走偏移阶梯 0/85/45/130。
# - 逐字下发、逐字读回（byok-credential-inject 三、四枪：整串一次下发会相邻成对换序，长度对得上也是脏码）。
# - 判据 6（安全面不变）不在本脚本另写一套：以 s5d L4/L5、ui-smoke C 系列、Photos 全链在同一枚 v1.0.5 字节上复绿为凭。
#   判据 7（全英文含对话框层）不在本脚本判：由 ui-english-sweep 段4 在同一枚字节上扫。
# - **S5-g 改口**：激活这一步不再"全在本机"——码与本机标识会出门一次问服务器（fail-closed），
#   执行期仍零网络（红线 C/G 一字未动）。对外话术禁写"防破解"这条继续有效。
# - 解锁格用的码是**租来的在册码**：整码不进日志、不进 raw、不进 git，只回显尾四位。
#
# 读数走位四条硬口径（run2/run3 血账，本批改判据的地方全在这里）：
# - **`run` 派发之后的第一次屏面读数之前，先把自家页拉回前台**：adb 通道触发后产品**主动退后台**
#   （MainActivity 源码注释第 88 行：让目标 App 成为活动窗口，keep_fg 例外）。run3 实测：G1i/G1l 用不带
#   keep_fg 的派发，之后 G1k 与整段 G2/G3(打字档) 读到的是**桌面**——11 条红全出在这一条上，
#   而同轮里带 keep_fg 的注入格（G3c~G3f）与 G4/G5/G6 全绿。读数器读错了窗，产品的罪不成立。
# - **读任何一格之前先把它弄进树**：折叠线以下的节点压根不进无障碍树（S5-d/S5-e 同族），只在原地 dump
#   一次就报"屏上没有"，红的是读数器不是产品。run2 实测 20 条红里 17 条是这一格没滚到——
#   同一屏同一份源码，G1b 滚了就读到 enabled=false、G1a/G1k/G2a 原地 dump 就全读成"缺席"。
#   所以一律走 find_cell（从顶逐屏翻），且**对话框开着时绝不滑页**（滑到框外=一次 outside touch，
#   测试通道自己把框 dismiss 掉，"框留着让人改那一位错的字"这条产品判据就被读数器改掉了）。
# - **缺席类判据必须同屏配一条正锚 + 整页走位**：run2 的 G4d/G5a 在"什么都没读到"的情况下 PASS＝假绿形态。
#   一页 dump 里没有 ≠ 页面上没有；本脚本改为 walk_absent（走完整页、锚必须在某一屏真出现过，
#   且当前态确认为已解锁 UNLOCKED=1 才有资格声称"提示消失了"）。
# - **换进程后步序账是空的**（跨进程只落存档列表）：G6 因此拿"载入存档"当冷启动凭据，不拿"存任务"——
#   后者在空账上本就该吃 EMPTY_LEDGER（run3 实测：产品拒得对，是判据挑错了动作）。
set -u
export MSYS_NO_PATHCONV=1

ADB="adb"
[ -n "${ANDROID_SERIAL:-}" ] && ADB="adb -s $ANDROID_SERIAL"
. "$(dirname "$0")/s5g-server.sh"   # S5-g：解锁那一格用的码从服务器租，不再拿本地 mint 的码当"能解锁的码"
. "$(dirname "$0")/s5g-relay.sh"    # S5-g：本台测试机出不了网（路由事实），中继不起＝设备这一格必然红
E9_APK="${E9_APK:-}"
RAW_DIR="${RAW_DIR:-evidence/S5/raw}"
EXPECTED_VC="${EXPECTED_VC:-6}"
LOCK=/tmp/s5f-activation.lock
# S5-g 起：能解锁的码只有一枚来源——服务器在册的 staging 段（本地 mint 的那枚现在应当被服务器拒，
# 它从"正例"变成了"格式全对但不在册"的负例，负例格在 scripts/s5g-server-activation-smoke.sh N1）。
# 租不到（服务不可达/token 不对/测试段占满）一律 exit 2：绝不用本地码凑一个"看起来解锁了"的绿。
# 租码排在 pass/bad 定义之后（run 前自查：这一行原来写在 `bad()` 之前，真失败时报的是 command-not-found，
# 把"为什么 exit 2"这条最该有的读数吞掉了）。

fail=0; passed=0; failed=0   # failed=红格**条数**；fail 只是退码 0/1——只报 fail 会把"红了四格"印成"FAIL=1"
UNLOCKED=0                  # 1=**本跑已用屏上锚确认过**已解锁；缺席类判据的资格凭
IN_DIALOG=0                 # 1=对话框开着：话术在框内那一格，只原地 dump、不滚页（见 find_cell 里那条）
RED='\033[0;31m'; GRN='\033[0;32m'; NCT='\033[0m'
pass() { printf "${GRN}PASS${NCT} %s\n" "$1"; passed=$((passed + 1)); }
bad()  { printf "${RED}FAIL${NCT} %s\n" "$1"; failed=$((failed + 1)); fail=1; }
log()  { printf '      | %s\n' "$*"; }
RAW="$RAW_DIR/s5f-activation-$(date +%Y%m%d-%H%M%S).raw.txt"
# 存档名逐轮唯一：磁盘上的"我的任务"列表**跨进程、跨 install -r 都留着**（这正是判据 4 要证的性质），
# 固定名第二次就吃 DUPLICATE_NAME（run4 实测 G5d 红在 run3 留下的同名存货上）。更脏的是连带效应：
# G6c 的"冷启动载入"当时仍绿——它载入的是**上一轮**存下的那一枚，本轮的绿踩着旧存货＝不可信。
RUNTAG=$(date +%H%M%S)
COPIES=/tmp/s5f-copies.$$.txt   # 六档**屏上原文**收在这（判据 2 的"互不相同"以屏面为准，不拿正则表自证）
: > "$COPIES"

# ---- 预检（先修后跑：任何一条不满足都当场停，不烧轮次赌运气） ----
[ -n "$E9_APK" ] && [ -f "$E9_APK" ] || { bad "预检 :: E9_APK 未给或不在盘——不冒称装了新码"; exit 2; }
mkdir -p "$RAW_DIR" .smoke-tmp
DEVS=$(adb devices | grep -cw device)
if [ "$DEVS" -ne 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then bad "预检 :: adb 在线 $DEVS 台且未指定 ANDROID_SERIAL——禁双驱动"; exit 2; fi
case "$($ADB shell getprop ro.product.model 2>/dev/null | tr -d '\r')" in
    *Kiwi*|*K40*|kona*|*M2012K11*) bad "预检 :: 靶机疑似真机——S5-f 只跑 AVD（K40/K80 本批零触碰）"; exit 2 ;;
esac
if [ -f "$LOCK" ] && kill -0 "$(cat "$LOCK" 2>/dev/null)" 2>/dev/null; then
    bad "预检 :: 另一个 s5f-activation（pid $(cat "$LOCK")）还活着——禁双驱动，本跑终止"; exit 2
fi
echo $$ > "$LOCK"; trap 'rm -f "$LOCK" "$COPIES"' EXIT
wait_bound() {
    local i=0
    while [ "$i" -lt 25 ]; do
        $ADB shell dumpsys accessibility 2>/dev/null | grep -q "Bound services:{Service\[label=Anytouch" && return 0
        sleep 1; i=$((i + 1))
    done
    return 1
}
rebind_cmd() {
    printf 'adb -s %s shell settings put secure enabled_accessibility_services com.anytouch.app/.service.AnytouchAccessibilityService && adb -s %s shell settings put secure accessibility_enabled 1' \
        "${ANDROID_SERIAL:-<serial>}" "${ANDROID_SERIAL:-<serial>}"
}
# versionCode 相同也可能是同名旧构建：只取 versionCode= 后面那一段数字（整行抹非数字会得到 42634——S5-d 首跑实测，预检会把自己的判据读错）
VC=$($ADB shell dumpsys package com.anytouch.app 2>/dev/null | tr -d '\r' | grep -m1 versionCode | sed 's/.*versionCode=//; s/[^0-9].*//')
[ "$VC" = "$EXPECTED_VC" ] || { bad "预检 :: 机上 versionCode=$VC 与本轮应有 $EXPECTED_VC 不符——装错包，跑了也是白跑"; exit 2; }
wait_bound || { bad "预检 :: 无障碍服务未绑定（可能被上一支脚本留在 null）——复位命令：$(rebind_cmd)"; exit 2; }
# ---- S5-g 起：解锁那一格要的是"设备真打到服务器"，本台测试机的出网被拦（路由事实，不是产品开关） ----
# 与 activation-preflight.sh 同一口径：这里只负责"起"，撤除是显式动作（bash scripts/s5g-relay.sh --down）——
# 一轮设备面里几支脚本接力跑，"谁起谁撤"必须只有一条规则，否则半路撤了中继后面的格全成假红。
s5g_relay_up || { bad "预检 :: 中继起不来 / 对面证书不等于本轮期望指纹——绝不为变绿把激活改回本地判据，本跑终止"; exit 2; }
GOLDEN=$(bash -c '. scripts/s5g-server.sh; s5g_lease_staging') || { bad "预检 :: 未从服务器取到在册 staging 码——解锁格无证可跑（判据 10 禁旁路）"; exit 2; }
GOLDEN_TAIL=${GOLDEN: -4}
GOLDEN_HEAD=${GOLDEN:4:4}      # 允许上屏的只有尾四位；前段那四个字符一出现就说明整枚码漏了出去
APK_MD5=$(md5sum "$E9_APK" | cut -d' ' -f1)
PM_PATH=$($ADB shell pm path com.anytouch.app 2>/dev/null | tr -d '\r' | head -1 | sed 's/^package://')
[ -n "$PM_PATH" ] || { bad "预检 :: pm path 读不到机上 apk——对不了账"; exit 2; }
$ADB pull "$PM_PATH" .smoke-tmp/installed-v105.apk >/dev/null 2>&1 || { bad "预检 :: pull 机上 apk 失败，对不了账"; exit 2; }
INSTALLED_MD5=$(md5sum .smoke-tmp/installed-v105.apk | cut -d' ' -f1)
[ "$INSTALLED_MD5" = "$APK_MD5" ] || { bad "预检 :: 机上包 md5=$INSTALLED_MD5 ≠ 发布件 $APK_MD5——跑出来是上一版的行为"; exit 2; }
SIZE=$($ADB shell wm size | tail -1 | tr -d '\r' | sed 's/.*: *//')
W=${SIZE%x*}; H=${SIZE#*x}
log "靶机 $($ADB shell getprop ro.build.version.sdk | tr -d '\r') 号 API · ${W}x${H} · vc=$VC · md5 对账 ok($APK_MD5)"
{
    echo "# s5f-activation 预检 ok · api=$($ADB shell getprop ro.build.version.sdk | tr -d '\r') ${W}x${H} vc=$VC"
    echo "# 靶机=$($ADB shell getprop ro.product.model | tr -d '\r') · E9_APK=$E9_APK · md5=$APK_MD5（机上 pull 回来逐字同值）"
} > "$RAW"

XMIG=/sdcard/.s5f.xml; XHOST=.smoke-tmp/s5f.xml
dump_idle() {
    $ADB shell uiautomator dump /sdcard/.s5f.xml >/dev/null 2>&1
    $ADB shell cat "$XMIG" | tr -d '\r' > "$XHOST"
    wait_bound || { bad "dump 后服务未复绑 :: 下一条判据不可信"; return 1; }
}
logs() { $ADB logcat -d -s AnytouchRun:* AnytouchOverlay:* 2>/dev/null; }
clr()  { $ADB logcat -c >/dev/null 2>&1; }
cnt()  { logs | grep -acE "$1" || true; }
wait_line() { # $1=固定串 $2=秒上限 → 命中打印该行
    local pat="$1" lim="${2:-30}" i=0 out=""
    while [ "$i" -lt "$lim" ]; do
        out=$(logs | grep -a "$pat" | tail -1 || true)
        [ -n "$out" ] && { printf '%s' "$out"; return 0; }
        sleep 1; i=$((i + 1))
    done
    return 1
}
inject() { $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --ez keep_fg true $*" >/dev/null 2>&1; sleep 2; }
run()    { $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity $*" >/dev/null 2>&1; }
# 只为把自家页拉回前台：extra 名字故意不含任何被门禁圈的通道关键字（reset 一类的字样也不能沾，防 grep 自污）
launch_home() { inject "--es smoke_probe just_bring_home_up"; }

# 某一格当前屏上的中心点（无 bounds / 零面积=不可见=交给调用方滚动重试，绝不猜坐标：byok 注入第二枪）
center_of() {
    local tag="$1" b x1 y1 x2 y2
    b=$(tr '<' '\n' < "$XHOST" | grep -a "resource-id=\"$tag\"" | grep -ao 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1)
    [ -n "$b" ] || return 1
    set -- $(printf '%s' "$b" | grep -oE '[0-9]+')
    [ $# -ge 4 ] || return 1
    x1=$1; y1=$2; x2=$3; y2=$4
    [ "$x2" -gt "$x1" ] && [ "$y2" -gt "$y1" ] || return 1
    printf '%d %d' "$(( (x1+x2)/2 ))" "$(( (y1+y2)/2 ))"
}
node_attr() { # $1=tag $2=属性名 → 该格那一行里这个属性的值（读的是**已 dump 的** $XHOST）
    tr '<' '\n' < "$XHOST" | grep -a "resource-id=\"$1\"" | head -1 | grep -o "$2=\"[^\"]*\"" | head -1 | sed "s/^$2=\"//; s/\"$//"
}
cell_text() { node_attr "$1" text; }
hide_ime() { # 键盘真在屏上才发 BACK（无条件 BACK 退掉的是应用自己：byok 注入第二枪血账）
    $ADB shell dumpsys input_method 2>/dev/null | grep -qa 'mInputShown=true' || return 0
    $ADB shell input keyevent 4 >/dev/null 2>&1; sleep 1
}
swipe_to_top() { for _ in 1 2 3 4 5 6; do $ADB shell input swipe "$((W / 2))" 500 "$((W / 2))" 1800 300 >/dev/null 2>&1; sleep 1; done; }
swipe_down()   { $ADB shell input swipe "$((W / 2))" 1800 "$((W / 2))" 500 300 >/dev/null 2>&1; sleep 1; }   # 手指上移=页面往下走

# ---- 读某一格之前先把它**弄进树**（run2 血账：本脚本 20 条红里 17 条是这一格没滚到）----
find_cell() { # $1=resource-id（裸 tag 名）→ 0=该格已在当前 $XHOST 里
    local tag="$1" i=0
    dump_idle || return 1
    grep -qa "resource-id=\"$tag\"" "$XHOST" && return 0
    # 对话框开着时**绝不滑页**：滑动手势落在框外=一次 outside touch，会把框自己 dismiss 掉，
    # 于是"框留着让人改那一位错的字"那条产品判据被测试通道改掉了（读数与产品各说一半）。
    # 凭据不靠 IN_DIALOG 变量准不准：直接看树上有没有框本身（变量记错也不放行）。
    [ "$IN_DIALOG" = "1" ] && { log "find_cell :: 对话框开着，不为 $tag 滑页（滑到框外=dismiss，测试通道就成了改判据的那只手）"; return 1; }
    swipe_to_top
    while [ "$i" -lt 10 ]; do
        dump_idle || return 1
        grep -qa "resource-id=\"$tag\"" "$XHOST" && return 0
        swipe_down
        i=$((i + 1))
    done
    return 1
}
read_cell() { # $1=tag → 屏上文字（先滚到那一格；读不到就是空，调用方按"没读到"记账而不是按"屏上没有"）
    find_cell "$1" || { printf ''; return 1; }
    cell_text "$1"
}
# 屏上那句拒因收进 $COPIES（判据 2 的"互不相同"以屏面原文为准，不拿正则表自证）
collect_refusal() { # $1=期望话术子串
    local t
    if [ "$IN_DIALOG" = "1" ]; then
        dump_idle || return 1
    else
        find_cell activation_rejection || return 1
    fi
    t=$(cell_text activation_rejection)
    [ -n "$t" ] || { bad "activation_rejection 那一格是空的（拒因必须上屏，静默=黑洞）"; return 1; }
    printf '%s\n' "$t" >> "$COPIES"
    printf '%s' "$t" | grep -qa "$1"
}
# 屏上 activation_state 现在说的话（失败时不打印诊断：调用方一旦拿"-n 非空"当真，
# 诊断文本就会把"没读到"洗成"读到了"——G4c/G6a 这类锚判全靠这一句返回值得干净）
state_says() { # $1=期望子串 → 0=那一格真说了这句话；1=说了别的或没读到
    local s; s=$(read_cell activation_state)
    printf '%s' "$s" | grep -qa "$1"
}
state_text() { read_cell activation_state; }   # 只给日志/判据红字用，别拿它的返回值当真假
# 点亮某一格：偏移阶梯 0/85/45/130，命中一律由调用方给的证据函数判定（产品自己的状态变化）
tap_tag() { # $1=tag $2=证据函数名
    local tag="$1" proof="$2" xy x y off
    for off in 0 85 45 130; do
        find_cell "$tag" || return 1
        xy=$(center_of "$tag") || xy=""
        [ -n "$xy" ] || return 1
        x=${xy% *}; y=${xy#* }
        $ADB shell input tap "$x" "$((y - off))" >/dev/null 2>&1; sleep 2
        if $proof; then
            echo "# [tap] $tag 命中（中心 $xy，偏移 $off px）" >> "$RAW"
            log "[$tag] 点亮（偏移 $off）"
            return 0
        fi
    done
    echo "# [tap] $tag 四档偏移都没命中（通道账）" >> "$RAW"
    return 1
}
# ---- 证据函数（每条都用产品自己的状态变化当"点中了"的凭） ----
proof_dialog_open() { dump_idle && grep -qa 'resource-id="activation_input"' "$XHOST"; }
proof_dialog_gone() { dump_idle && ! grep -qa 'resource-id="activation_input"' "$XHOST"; }
proof_field_focus() { [ "$(node_attr activation_input focused)" = "true" ]; }
proof_activated_line() { logs | grep -qa 'S5FSMOKE activation ok tail='; }
EXPECT_GATE=""
proof_refused() { logs | grep -qa "S5FSMOKE activation refused gate=$EXPECT_GATE"; }
# 开框/收框：IN_DIALOG 只有这两个出入口，别处不改（判据面"框在不在"与变量必须同源）
open_dialog() {
    dump_idle || return 1
    if grep -qa 'resource-id="activation_input"' "$XHOST"; then
        IN_DIALOG=1; return 0
    fi
    tap_tag activate proof_dialog_open || return 1
    IN_DIALOG=1
}
close_dialog() { # 走产品自己的 Cancel 收起（不靠 BACK 赌：BACK 退掉的是窗口本身）
    tap_tag activation_cancel proof_dialog_gone || return 1
    IN_DIALOG=0
    hide_ime
}
# 逐字下发 + 逐字读回（整串一次下发会成对换序，长度对得上也是脏码：byok 三、四枪）
type_into_input() { # $1=打进框的码
    local value="$1" xy got attempt=1
    dump_idle || return 1
    xy=$(center_of activation_input) || { bad "activation_input 不在屏上（框没开或没滚到）"; return 1; }
    $ADB shell input tap "$xy" >/dev/null 2>&1; sleep 1
    dump_idle || return 1
    proof_field_focus || { bad "前置 :: activation_input 点完没拿到焦点（后面读数不属于本产品）"; return 1; }
    while :; do
        $ADB shell "input keyevent 123; for i in \$(seq 1 25); do input keyevent 67; done" >/dev/null 2>&1
        $ADB shell "for c in $(printf '%s' "$value" | sed -e "s/\(.\)/'\1' /g"); do input text \"\$c\"; done" >/dev/null 2>&1
        sleep 1
        dump_idle || return 1
        got=$(node_attr activation_input text)
        [ "$got" = "$value" ] && { log "框内逐字读回相符（${#got} 字，第 $attempt 轮）"; return 0; }
        attempt=$((attempt + 1))
        [ "$attempt" -le 3 ] || { bad "activation_input 三轮逐字复核全不符（屏上=[$got] 期望 [$value]）——通道账，不是产品账"; return 1; }
        log "第 $((attempt - 1)) 轮读回 [$got] ≠ 期望，清框重打"
    done
}

# 预置会话档（与 s5e 同一枚冻结 fixture，逐字锁在 UiSmokeSessionFixtureTest，脚本不手抄近似值）
SESSION_JSON='{"format":"anytouch.recorder.session","version":1,"targetPkg":"com.android.settings","state":"STOPPED","overflowCount":0,"events":[{"type":"window","pkg":"com.android.settings","windowTitle":"session-open","timestampMs":1700000001000},{"type":"action","kind":"CLICK","text":null,"confirmed":true,"timestampMs":1700000002000,"snapshot":{"resourceId":null,"text":"Connected devices","contentDesc":null,"className":"android.widget.TextView","pkg":"com.android.settings","indexPath":[0,1,2]}},{"type":"action","kind":"CLICK","text":null,"confirmed":true,"timestampMs":1700000003000,"snapshot":{"resourceId":null,"text":"Connection preferences","contentDesc":null,"className":"android.widget.TextView","pkg":"com.android.settings","indexPath":[0,1,2,0]}},{"type":"action","kind":"CLICK","text":null,"confirmed":true,"timestampMs":1700000004000,"snapshot":{"resourceId":null,"text":"Bluetooth","contentDesc":null,"className":"android.widget.TextView","pkg":"com.android.settings","indexPath":[0,1,2,1]}}],"rejectedEvents":[]}'
SAFE='"safety":{"viewport_ok":true,"click_enabled":true}'
WAITS="[{\"action_id\":\"w1\",\"type\":\"wait\",\"source\":\"node\",\"value\":{\"ms\":1000},$SAFE},{\"action_id\":\"w2\",\"type\":\"wait\",\"source\":\"node\",\"value\":{\"ms\":1000},$SAFE}]"
reset_ledger() {
    clr
    inject "--es session_json '$SESSION_JSON'"
    wait_line "S2SMOKE record inject accepted" 20 >/dev/null || return 1
    inject "--ez record_stop true"
    wait_line "S2SMOKE compiled ok actions=3" 25 >/dev/null
}
# 盘上那枚私有件（run-as 只读 debug 包可用；**只读回内容做判据，不当删档捷径**——撤态一律走产品复位口）
flag_on_disk() { $ADB shell "run-as com.anytouch.app cat files/activation/flag.txt" 2>&1 | tr -d '\r'; }
# 整页走位的缺席判据：$1=正锚子串（必须在某一屏真出现过）$2=空格分隔的"该消失的 tag"
# 三条一起满足才算绿：① UNLOCKED=1（当前态已由屏上锚确认过）② 整页走过且锚出现过 ③ 每一屏都没撞上这些 tag
walk_absent() { # $1=锚 $2=tag 列表 $3=格名
    local anchor="$1" tags="$2" seg="$3" i=0 seen_anchor=0 hit="" t
    [ "$UNLOCKED" = "1" ] || { bad "$seg :: 当前态未确认已解锁（UNLOCKED=0）——未激活页面上本就挂着提示，没资格声称它们消失了"; return 1; }
    swipe_to_top
    while [ "$i" -lt 10 ]; do
        dump_idle || return 1
        grep -qa "$anchor" "$XHOST" && seen_anchor=1
        for t in $tags; do
            grep -qa "resource-id=\"$t\"" "$XHOST" && hit="$hit $t"
        done
        i=$((i + 1))
        swipe_down
    done
    [ "$seen_anchor" = 1 ] || { bad "$seg :: 整页 $i 屏都没读到锚 [$anchor]——这一屏没画到位，缺席判据当场作废（宁可记红）"; return 1; }
    [ -z "$hit" ] || { bad "$seg :: 走完整页仍撞上：$hit"; return 1; }
    return 0
}

# ================= 起始态：走产品自己的复位口打回"未激活"（不 rm 文件，测的是判据不是运气） =================
launch_home
clr
inject "--ez activation_reset true"
RESET_LINE=$(wait_line 'S5FSMOKE activation reset ok=true' 15)
if [ -n "$RESET_LINE" ]; then pass "前置 :: 复位通道走完（$RESET_LINE）"
else bad "前置 :: 15s 内未见 [S5FSMOKE activation reset ok=true]——起始态不明，后面每一格都不可信"; exit 2; fi
UNLOCKED=0; IN_DIALOG=0
STATE0=$(state_text)
printf '%s' "$STATE0" | grep -qa "not activated" && pass "前置 :: 起始态=未激活（屏上：$STATE0）" \
    || bad "前置 :: activation_state 没报 not activated（实际：[$STATE0]）"
reset_ledger || bad "前置 :: 步序账未复位到 3 步（G1 的插步/存档格没有账可动）"

# ================= G1 未激活：三枚进墙入口注入同拒 + 单发对照格照跑（判据 5） =================
log "--- G1 未激活态：三入口同拒 + 对照格 ---"
for t in pro_hint_repeat_loop pro_hint_saved_tasks pro_hint_step_insert; do
    find_cell "$t" && pass "G1a :: $t 在屏（$(cell_text $t)）" \
        || bad "G1a :: 整页滚遍仍没读到 $t——未激活的提示缺席=静默拒绝"
done
if find_cell step_insert_toggle_0; then
    EN0=$(node_attr step_insert_toggle_0 enabled)
    [ "$EN0" = "false" ] && pass "G1b :: \"+ Step\" 钮 enabled=false（置灰只是提示，真门禁在入口）" \
        || bad "G1b :: step_insert_toggle_0 enabled=$EN0（期望 false）"
else
    bad "G1b :: 整页滚遍仍没读到 step_insert_toggle_0（读不到=本格未证，不按'钮没了'记账）"
fi

clr; inject "--es task_save f1-$RUNTAG"
L=$(wait_line 'S5ESMOKE saved task op=save refused gate=NOT_ACTIVATED' 15)
[ -n "$L" ] && pass "G1c :: 注入存任务被拒（按钮置灰挡不住通道，门禁落在入口）" || bad "G1c :: 存任务未被付费墙拒（实际：$(logs | grep -a 'op=save' | tail -1)）"
clr; inject "--es saved_load f1-$RUNTAG"
L=$(wait_line 'refused gate=NOT_ACTIVATED' 15)
[ -n "$L" ] && pass "G1d :: 载入停在付费墙而不是 NOT_FOUND——墙排在形状档之前（未激活者不该先吃一次空账）" \
    || bad "G1d :: 载入未停在 NOT_ACTIVATED（实际：$(logs | grep -a 'op=load refused' | tail -1)）"
clr; inject "--es saved_run f1-$RUNTAG"
wait_line 'refused gate=NOT_ACTIVATED' 15 >/dev/null && pass "G1e :: 存档直跑被拒" || bad "G1e :: 存档直跑未被拒"
clr; inject "--es saved_delete f1-$RUNTAG"
wait_line 'refused gate=NOT_ACTIVATED' 15 >/dev/null && pass "G1f :: 删除被拒" || bad "G1f :: 删除未被拒"
clr; inject "--es step_insert_at 3 --es step_insert_type wait --es step_insert_name g1_wait --es step_insert_ms 1200"
L=$(wait_line 'S2SMOKE step edit refused gate=NOT_ACTIVATED' 15)
[ -n "$L" ] && pass "G1g :: 注入插步被拒（唯一写口 applyEdit 判，不在按钮里判）" || bad "G1g :: 插步未被拒（实际：$(logs | grep -a 'step edit' | tail -1)）"
find_cell step_edit_rejection && cell_text step_edit_rejection | grep -qa "Upgrade to Pro" \
    && pass "G1h :: 插步拒因上屏且以 Upgrade to Pro 开头" \
    || bad "G1h :: step_edit_rejection 没说出付费墙（实际：[$(read_cell step_edit_rejection)]）"

# 多轮派发：墙拦在 RepeatPolicy 之后（先读懂数字才知道这一次动用没动用轮数）
clr
run "--es task_json '$WAITS' --es repeat_count 2 --es repeat_interval 2"
L=$(wait_line 'S5FSMOKE pro refused feature=REPEAT_LOOP gate=NOT_ACTIVATED' 15)
[ -n "$L" ] && pass "G1i :: repetitions=2 的派发被拦并留痕" || bad "G1i :: 多轮派发未被拦（实际：$(logs | grep -aE 'S5FSMOKE pro refused|S1SMOKE' | tail -1)）"
[ "$(cnt 'S1SMOKE (round=[0-9]+/[0-9]+ )?ok=')" = "0" ] \
    && pass "G1j :: 被拦那一跑一步没走（收口行 0 条）——话术里那句 Nothing was dispatched 是真话" \
    || bad "G1j :: 拒的同时还跑出了收口行（话术与行为不符）"
launch_home; sleep 1   # G1i 的派发把自家页退到了后台（不拉回来，这一格读的是桌面：run3 血账）
PR=$(read_cell pro_rejection)
printf '%s' "$PR" | grep -qa "Upgrade to Pro" && pass "G1k :: 派发拒因上屏 pro_rejection 那一格" \
    || bad "G1k :: 派发被拒却屏上没说（实际：[$PR]；静默=黑洞）"
sleep 3; clr
run "--es task_json '$WAITS' --es repeat_count 1 --es repeat_interval 2"
L=$(wait_line 'S1SMOKE ok=' 40)
[ -n "$L" ] && pass "G1l :: 对照格——同一条任务单发照跑：墙只圈轮数，没顺手圈住免费面" || bad "G1l :: 单发也被拦了（付费墙越界圈免费面，判据 5 的反面）"

# ================= G2 Activate 钮 → 对话框 + 空框 EMPTY + Cancel（判据 1） =================
log "--- G2 激活对话框 ---"
launch_home   # G1l 的单发对照格是**不带 keep_fg 的派发**（那才是真机上的真实形态），自家页已被退到后台
find_cell activate && pass "G2a :: 首页 Activate 钮在屏" || bad "G2a :: 整页滚遍没读到 activate 钮（军令 §1 那一句没落地，或读数没到位）"
open_dialog && pass "G2b :: 点开弹出输入框（activation_input 上屏）" \
    || bad "G2b :: 点 activate 四档偏移都没点开（通道未命中，不记成\"框没画出来\"）"
dump_idle
grep -qa 'resource-id="activation_confirm"' "$XHOST" && grep -qa 'resource-id="activation_cancel"' "$XHOST" \
    && pass "G2c :: 框内 input/confirm/cancel 三枚 tag 齐（对话框那棵子树自带 testTagsAsResourceId 生效）" \
    || bad "G2c :: 框内 tag 不齐（跨窗语义没带够，设备面就看不见框）"
grep -qa "never goes online" "$XHOST" && pass "G2d :: 那句不联网上屏（屏上说的与判据做的是同一件事）" \
    || bad "G2d :: 对话框没讲清不联网（军令 §4 的对外那一半）"
EXPECT_GATE=EMPTY; clr
tap_tag activation_confirm proof_refused && collect_refusal "Type the activation code" \
    && pass "G2e :: 空框点 Unlock → EMPTY 且框内话术上屏" || bad "G2e :: 空框点 Unlock 没落 EMPTY（要么没点中，要么框根本没送进校验器）"

# ================= G3 拒因分档：打字一档 + 注入四档，六句互不相同（判据 2） =================
log "--- G3 拒因分档 ---"
# 打字通道这一档挑 CHECKSUM：形状全对、只有末两位抄错，是买家最常见的手滑
[ "$IN_DIALOG" = "1" ] || open_dialog || log "G3a :: 重开框失败，打字档改由注入面覆盖（照实记，不静默换判据）"
type_into_input "ANY-A1B2-C3D4-E5ZX" || log "G3a :: 打字通道未就位，本格按实记"
EXPECT_GATE=CHECKSUM; clr
tap_tag activation_confirm proof_refused && collect_refusal "last two letters" \
    && pass "G3a :: 逐字打进框的脏码 → CHECKSUM，框内话术上屏（框留着让人改那一位错的字）" \
    || bad "G3a :: 打字那一档没走到 CHECKSUM（实际：$(logs | grep -a 'activation refused' | tail -1)）"
hide_ime
dump_idle
if ! grep -qa 'resource-id="activation_input"' "$XHOST"; then
    # 只有"框本来就开着"时这句才成立：框从没开过就说"BACK 退掉了框"＝把读数器的事说成产品的事
    [ "$IN_DIALOG" = "1" ] && log "G3a :: BACK 把框一起退掉了（键盘与框同窗那一档），重开框继续走 Cancel 格" \
        || log "G3a :: 此刻树上没有框（打字档未开成，走位问题），重开一次再走 Cancel 格"
    IN_DIALOG=0; open_dialog || bad "G3b :: 框重开失败，Cancel 格未证（不是产品点不掉 Cancel）"
fi
if close_dialog; then
    pass "G3b :: Cancel 真收起对话框（activation_input 从树上消失）"
    echo "# [G3b] Cancel 收框后 activation_input 已不在树上" >> "$RAW"
else
    bad "G3b :: Cancel 点不掉框"
fi

# 其余四档走注入通道：**同一个** ActivationStore.submit，没有旁路；话术落在首页 activation_rejection 那一格
four_case() { # $1=码 $2=期望 gate $3=期望话术子串 $4=格名
    EXPECT_GATE="$2"; IN_DIALOG=0; clr
    inject "--es activation_code '$1'"
    if wait_line "S5FSMOKE activation refused gate=$2 via=adb_inject" 12 >/dev/null && collect_refusal "$3"; then
        pass "$4 :: gate=$2，屏上话术=$3 那一档"
    else
        bad "$4 :: 期望 gate=$2 + [$3]（实际：$(logs | grep -a 'activation refused' | tail -1)）"
    fi
    SA=$(state_text)
    printf '%s' "$SA" | grep -qa "not activated" \
        || bad "$4 :: 被拒之后屏上解锁态被改写了（实际：[$SA]）"
}
four_case "ANY-A1B2-C3D4-E5N"  BAD_LENGTH    "exactly 18 characters" "G3c :: 17 位（军令那句 19 位由裁 2 定为笔误，长度档以格式串为准）"
four_case "NAV-A1B2-C3D4-E5NX" BAD_PREFIX    "start with ANY"        "G3d :: 前缀错"
four_case "ANY_A1B2_C3D4_E5NX" BAD_SEPARATOR "Put a dash after ANY"  "G3e :: 分隔符错（下划线不被\"猜着改成连字符\"）"
four_case "ANY-A1B2-C3D4-E5.N" BAD_CHARSET   "capital letters A-Z"   "G3f :: 字符集脏"
N_SEEN=$(grep -c . "$COPIES" || true)
N_DISTINCT=$(sort -u "$COPIES" | grep -c . || true)
log "屏上收到的拒因共 $N_SEEN 条，去重 $N_DISTINCT 条"
[ "$N_SEEN" = "6" ] && [ "$N_DISTINCT" = "6" ] \
    && pass "G3g :: 六档话术**屏面原文**两两不同（JVM 那条\"两档共用一句\"的锁在设备面同口径）" \
    || bad "G3g :: 屏上拒因收得 $N_SEEN 条/去重 $N_DISTINCT 条（期望 6/6）——见 $COPIES 逐条"

# ================= G4 golden 解锁：只回显尾四位（判据 3） =================
log "--- G4 golden 解锁 ---"
open_dialog || bad "G4 前置 :: 打不开框，后面按实记"
type_into_input "$GOLDEN" || log "G4 :: golden 码没打进框，本格判据按实记"
clr
tap_tag activation_confirm proof_activated_line && pass "G4a :: golden 码输对 → 校验口留下 ok 行（产品自己的状态变化）" \
    || bad "G4a :: 逐字相符的 golden 码没能解锁（实际：$(logs | grep -a activation | tail -1)）"
OKLINE=$(logs | grep -a 'S5FSMOKE activation ok' | tail -1)
printf '%s' "$OKLINE" | grep -qa "tail=$GOLDEN_TAIL" && pass "G4b :: 日志只回显尾四位（$OKLINE）" \
    || bad "G4b :: ok 行 tail 不是 $GOLDEN_TAIL（实际：$OKLINE）"
hide_ime
dump_idle
if grep -qa 'resource-id="activation_input"' "$XHOST"; then
    log "G4 :: 解锁后框仍在前台，走产品 Cancel 收起（解锁态另由 G4c 的屏上 activation_state 证明，不拿收框当解锁）"
    close_dialog || log "G4 :: Cancel 收不掉框，G4c 的屏上读数按实记"
fi
ST=$(state_text)
if printf '%s' "$ST" | grep -qa "$GOLDEN_TAIL"; then
    UNLOCKED=1
    pass "G4c :: 屏上 activation_state 回显尾四位（$ST）——解锁态上屏，后面的「提示消失」类判据自此有锚"
else
    bad "G4c :: 屏上没回显尾四位（实际：[$ST]；空=那一格压根没滚到，属读数账，不记成'已解锁但没显示'）"
fi
# 缺席面（码没漏）必须先过锚：run2 的 G4d 在"什么都没读到"的情况下 PASS＝假绿形态
if [ "$UNLOCKED" = "1" ]; then
    LEAK=""
    logs | grep -qa "$GOLDEN_HEAD" && LEAK="$LEAK 日志"
    grep -qa "$GOLDEN_HEAD" "$XHOST" && LEAK="$LEAK 整棵语义树"
    [ -z "$LEAK" ] && pass "G4d :: 前段正文在日志与语义树里都读不到（同屏锚=已解锁态已上屏，$ST）" \
        || bad "G4d :: 整枚能解锁的串漏进：$LEAK（与 API Key 尾四位同一条律被破，红线 H 精神面）"
else
    bad "G4d :: 锚未过（屏上没有已解锁态）——'没漏'这条缺席判据不能凭读不到自证"
fi
FD=$(flag_on_disk)
[ "$FD" = "$GOLDEN_TAIL" ] && pass "G4e :: 盘上 flag.txt 只存尾四位（读回=$FD，写盘必读回这条律在设备面成立）" \
    || bad "G4e :: flag.txt 读回 [$FD]（期望 $GOLDEN_TAIL）"

# ================= G5 解锁后：同三条注入语句逐条放行（判据 5 反向半边） =================
log "--- G5 解锁后放行 ---"
walk_absent "code ending" "pro_hint_repeat_loop pro_hint_saved_tasks pro_hint_step_insert" "G5a :: 三句 Upgrade 提示" \
    && pass "G5a :: 整页走位 + 锚（code ending）在同页出现，三句 Upgrade 提示全部不在任何一屏"
if find_cell step_insert_toggle_0; then
    [ "$(node_attr step_insert_toggle_0 enabled)" = "true" ] && pass "G5b :: \"+ Step\" 恢复可点 enabled=true" \
        || bad "G5b :: step_insert_toggle_0 enabled=$(node_attr step_insert_toggle_0 enabled)（期望 true）"
else
    bad "G5b :: 整页滚遍仍没读到 step_insert_toggle_0（读不到≠已恢复，本格未证）"
fi
# 与 G1g 逐字同一条语句：改的只有激活态
clr
inject "--es step_insert_at 3 --es step_insert_type wait --es step_insert_name g5_wait --es step_insert_ms 1200"
wait_line 'S2SMOKE step edit ok=insert index=3' 15 >/dev/null && pass "G5c :: 插步放行（与 G1g 同一条语句）" \
    || bad "G5c :: 已激活仍被拒（实际：$(logs | grep -a 'step edit' | tail -1)）"
inject "--es step_remove 3" >/dev/null; sleep 1
clr; inject "--es task_save f5-$RUNTAG"
L=$(wait_line 'S5ESMOKE saved task op=save ok' 15)
[ -n "$L" ] && pass "G5d :: 存任务放行（与 G1c 同一条语句）" || bad "G5d :: 已激活仍存不下（实际：$(logs | grep -a 'op=save' | tail -1)）"
sleep 3; clr
run "--es task_json '$WAITS' --es repeat_count 2 --es repeat_interval 2"
L=$(wait_line 'S1SMOKE round=2/2 ok=' 60)
[ -n "$L" ] && pass "G5e :: 两轮跑满（与 G1i 同一条语句）" || bad "G5e :: 已激活仍跑不满两轮（实际：$(logs | grep -aE 'S1SMOKE|pro refused' | tail -1)）"

# ================= G6 install -r 换进程：冷启动仍以磁盘件为凭（判据 4） =================
log "--- G6 冷启动仍在 ---"
sleep 2; clr
$ADB install -r "$E9_APK" >/dev/null 2>&1 || bad "G6 前置 :: install -r 失败"
VC2=$($ADB shell dumpsys package com.anytouch.app 2>/dev/null | tr -d '\r' | grep -m1 versionCode | sed 's/.*versionCode=//; s/[^0-9].*//')
[ "$VC2" = "$EXPECTED_VC" ] || bad "G6 前置 :: 换进程后 versionCode=$VC2 ≠ $EXPECTED_VC"
IN_DIALOG=0
launch_home; sleep 2
ST6=$(state_text)
if printf '%s' "$ST6" | grep -qa "code ending"; then
    pass "G6a :: install -r 换进程后仍报已解锁（磁盘件真值为凭，不信内存）：$ST6"
else
    UNLOCKED=0
    bad "G6a :: 换进程后没报已解锁（实际：[$ST6]）——冷启动判据不成立，G6b 随之失去锚资格"
fi
walk_absent "code ending" "pro_hint_repeat_loop pro_hint_saved_tasks pro_hint_step_insert" "G6b :: 冷启动后的 Upgrade 提示" \
    && pass "G6b :: 冷启动后整页仍没有 Upgrade 提示挂着（与 G6a 同一把锚）"
# 冷启动这一格凭"载入 G5d 存下的那一枚"，不凭"再存一次"：换进程后**步序账是空的**（只有存档列表落盘），
# task_save 在空账上本就该吃 EMPTY_LEDGER——run3 实测产品拒得对，是判据挑错了动作（拿 save 证冷启动=自找红）。
# 载入能过同时证两件事：存档列表真跨了进程、付费墙在冷启动后仍然放行。
clr; inject "--es saved_load f5-$RUNTAG"
wait_line 'S5ESMOKE saved task op=load ok' 15 >/dev/null && pass "G6c :: 冷启动后载入 G5d 那一枚存档放行（列表跨进程在盘，解锁态不是内存里的乐观值）" \
    || bad "G6c :: 冷启动后载入被拒（实际：$(logs | grep -a 'op=load' | tail -1)）"
# 不留残档给下一支脚本（s5e 同族纪律：它每轮自己也收尾删除）——只记通道账，不加判据
inject "--es saved_delete f5-$RUNTAG" >/dev/null; sleep 1
DEL6=$(logs | grep -a "op=delete ok name=\"f5-$RUNTAG\"" | tail -1)
[ -n "$DEL6" ] && log "G6 收尾 :: 本轮那一枚存档已撤（$RUNTAG）" \
    || log "G6 收尾 :: 未读到删除回执（按实记，不因此记产品红）"

# ================= G7 复位回未激活（收尾态：各脚本自设前置，这里只把设备留在可读的那一态） =================
log "--- G7 复位 ---"
clr; inject "--ez activation_reset true"
wait_line 'S5FSMOKE activation reset ok=true' 15 >/dev/null && pass "G7a :: 复位通道留痕" || bad "G7a :: 复位未留痕"
UNLOCKED=0
ST7=$(state_text)
printf '%s' "$ST7" | grep -qa "not activated" && pass "G7b :: 屏面翻回未激活（红字与提示一起翻，不留假绿）：$ST7" \
    || bad "G7b :: 复位后屏上还挂着已解锁（实际：[$ST7]）"
# 复位后提示该回来（G5a/G6b 那两条缺席判据的反向半边：这一条是"在场"，逐格滚到才算，缺席可以凭走位、在场必须真读到）
MISS7=""
for t in pro_hint_repeat_loop pro_hint_saved_tasks pro_hint_step_insert; do
    find_cell "$t" || MISS7="$MISS7 $t"
done
[ -z "$MISS7" ] && pass "G7c :: 复位后三句 Upgrade 提示全部回到屏上（逐格滚到、正读为凭）" \
    || bad "G7c :: 复位后这些提示没回到屏上：$MISS7（提示不翻回来＝复位只改了半边）"
FD7=$(flag_on_disk)
printf '%s' "$FD7" | grep -qa "No such file\|cat: " && pass "G7d :: 盘上 flag.txt 已撤（读回=$FD7）" \
    || bad "G7d :: 复位后 flag.txt 仍在盘上（读回 [$FD7]）——撤了内存没撤盘=假复位"
clr; inject "--es task_save f7-$RUNTAG"
wait_line 'refused gate=NOT_ACTIVATED' 15 >/dev/null && pass "G7e :: 复位后入口重新被墙拦下（复位是真的，不是留着放行）" \
    || bad "G7e :: 复位后仍能存任务"

# ---- 收尾 ----
{
    echo "# 收尾 :: passed=$passed · fail=$fail · apk md5=$APK_MD5 vc=$VC · 屏上拒因 $N_SEEN 条 · UNLOCKED 末态=$UNLOCKED"
    cat "$COPIES" | sed 's/^/# 拒因| /'
    echo "# 判据 6（安全面不变）不在本脚本另写一套：以 s5d L4/L5、ui-smoke C 系列、Photos 全链在同一枚 v1.0.5 字节上复绿为凭"
    echo "# 判据 7（全英文含对话框层）由 ui-english-sweep 段4 在同一枚字节上扫"
} >> "$RAW"
# 收尾：本轮租到的码在这台机上占了一格测试额度，留给 reset-staging 一键清（脚本不自己解绑：
# 解绑口要整码做参数，多一处整码过手就多一次泄漏面）。
s5g_reset_staging >/dev/null 2>&1 && log "收尾 :: 测试段绑定已清（s5g-reset-staging）"     || log "收尾 :: 测试段未清（不记产品红；下一轮起跑前手动跑 scripts/s5g-reset-staging.sh）"

echo
echo "================ s5f-activation 汇总 ================"
echo "RAW=$RAW"
echo "passed=$passed failed-cells=$failed exit-code=$fail"
[ "$fail" = "0" ] || echo "结论：本批有红格，按实照报——不修脚本判据来凑绿"
exit "$fail"
