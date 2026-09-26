#!/usr/bin/env bash
# Anytouch S5-g 服务器激活面·设备冒烟（avd34，K40/K80 真机零触碰）
# 军令件：orders/ANYTOUCH-S5g-server-activation-ORDER.md + 附页 §5 判据 3(新增档)/4/5/7/8
#
# 这一支只打 **APP↔服务器这条路本身**：本地形状档、对话框逐字打字、三句 Upgrade 的消失回来
# 都是 s5f 那一支的格（同一枚字节上跑），这里一概不重复、不充数。
#
# 八格：
#   R1 通道自证（"靶子是真的"）：租一枚在册码 → 设备注入 → 屏/日志 ok，**且机上 journal 同窗出现该尾四位的 activate 行**
#   R2 幂等重放：同一台第二次交同一枚码 → 仍 ok 且 seats 不涨（服务器说没占第二格）
#   R3 判据 3 新增档：本地格式全对但未在册 → gate=SERVER_INVALID + 屏上 "Activation code invalid" + 盘上无 flag + 入口照旧拒
#   R4 判据 2 设备半边：服务器侧把该码占满 2 格 → 设备注入落 seats_full（军令原话上屏）；解绑一格后设备进得去
#   R5 判据 4 fail-closed 两切法：撤路由（真不可达）/ 停服务（连得上没人应）→ 都必须保持未解锁、屏上给**联网归因**句
#      （**码必须在切之前租**：停了服务就没人给码，run1 的 R5b 就是这么自毁的）
#   R6 判据 8 TLS 反向锁：把靶机指到**第二枚自签证书**上 → 设备必须自己断。
#      这一格先要证"通道真的指在假靶上"：本机经中继打一次 health，**假靶自己的访问日志必须记到这一条**，
#      然后才轮到"POST /api/activate 恒为 0 条"这条正判据（run1 缺的就是前者，红得毫无道理还红反方向）。
#   R7 判据 5 已激活离线照用：install -r 换进程 + 飞行（**中继留着＝有路可走而不走**）→ 解锁态仍在、三入口放行、
#      游标之后服务器侧 activate 请求 0 条；随后再撤中继补"路也没了"那一半。
#   R8 判据 7 尾四位：整页语义树分页 + logcat 全缓冲 + RAW 自身，三处反向扫描整枚在册码与完整指纹 0 命中
#
# 洁净纪律（沿用 S5-f/S5-e 血账）：
# - 判据 10：测试通道**零旁路**。租到的码送的还是那 18 个字符、走同一个 submit；拿不到在册码 / 服务器不通 = exit 2 响亮失败。
# - 假红假绿同罪：任何"没读到"都先按缺席判据作废自己（锚 + 整页走位），不给产品定罪。
# - 每次 dump 后一律 wait_bound 复绑；执行期不做无谓 dump。
# - 跨进程只走 `install -r` 同一枚 apk，禁 force-stop 自家。
# - 在册码（buyer 与 staging 皆是）一个字不进仓、不进日志、不进 argv：只在临时文件里活一轮，收尾删。
# - 中继（scripts/s5g-relay.sh）只是**这台测试机的路由事实**，不是产品开关：TLS 端到端仍在机上验，
#   起隧道后**量对面那张证书的 SPKI**，等于本轮期望值才敢往下跑（期望值默认＝APP 固定值，判据 8 那格显式传假靶值）；
#   本脚本退出前必须把路由与机上服务复原。
#
# 用法：E9_APK=app/build/outputs/apk/debug/app-debug.apk EXPECTED_VC=7 ANDROID_SERIAL=emulator-5554 \
#       bash scripts/s5g-server-activation-smoke.sh
# 退码：0=全绿；1=有格红；2=前置不成立（不烧轮次赌运气）。
set -u
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."
source scripts/s5g-server.sh
source scripts/s5g-relay.sh

ADB="adb"
[ -n "${ANDROID_SERIAL:-}" ] && ADB="adb -s $ANDROID_SERIAL"
E9_APK="${E9_APK:-}"
EXPECTED_VC="${EXPECTED_VC:-7}"
RAW_DIR="${RAW_DIR:-evidence/S5g/raw}"
STAMP="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RAW_DIR" .smoke-tmp
RAW="$RAW_DIR/s5g-server-activation-$STAMP.raw.txt"
LOCK=/tmp/s5g-server-activation.lock
CODEBOOK=/tmp/s5g-codes.$$      # 本轮租到/生成过的**整码**只活在这里（600，收尾必删）——反向扫描的名单来源
: > "$CODEBOOK"; chmod 600 "$CODEBOOK" 2>/dev/null || true

fail=0; failed=0; passed=0; UNLOCKED=0
RED='\033[0;31m'; GRN='\033[0;32m'; NCT='\033[0m'
pass() { printf "${GRN}PASS${NCT} %s\n" "$1"; passed=$((passed + 1)); }
# failed 单独计数：只把 fail 当 0/1 用会把"红了四格"印成"FAIL=1"（run1 就这么把四红报成一红，读数虚小）
bad()  { printf "${RED}FAIL${NCT} %s\n" "$1"; failed=$((failed + 1)); fail=1; }
log()  { printf '      | %s\n' "$*"; }
note() { printf '# %s\n' "$*" >> "$RAW"; }

cleanup() {
    local rc=$?
    s5g_relay_down >/dev/null 2>&1 || true
    $ADB shell "cmd connectivity airplane-mode disable" >/dev/null 2>&1 || true
    # 机上一律复原：正规服务必须在位、假靶必须死、临时件必须清（不许把烂摊子留给下一轮）
    timeout 60 ssh $S5G_SSH_OPTS "$S5G_SSH" \
        'pkill -F /tmp/s5gfake.pid 2>/dev/null; rm -f /tmp/s5gfake.*; systemctl start anytouch-activate; systemctl is-active anytouch-activate' \
        >/dev/null 2>&1 || true
    rm -f "$CODEBOOK" /tmp/s5g-fake-up.$$ /tmp/s5g-fake-out.$$ "$LOCK" 2>/dev/null || true
    note "cleanup rc=$rc（中继已撤、飞行已关、机上正规服务已复用在位）"
    exit $rc
}
trap cleanup EXIT
[ -f "$CODEBOOK" ] || { echo "无法建立本轮码表临时件——绝不带着不落盘的状态跑"; exit 2; }

# ---- 预检（任何一条不满足都当场停） ----
[ -n "$E9_APK" ] && [ -f "$E9_APK" ] || { echo "预检 :: E9_APK 未给或不在盘——不冒称装了新码"; exit 2; }
if [ -f "$LOCK" ] && kill -0 "$(cat "$LOCK" 2>/dev/null)" 2>/dev/null; then
    echo "预检 :: 另一个 s5g-server-activation（pid $(cat "$LOCK")）还活着——禁双驱动"; exit 2
fi
echo $$ > "$LOCK"
DEVS=$(adb devices | grep -cw device)
if [ "$DEVS" -ne 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then echo "预检 :: adb 在线 $DEVS 台且未指定 ANDROID_SERIAL——禁双驱动"; exit 2; fi
case "$($ADB shell getprop ro.product.model 2>/dev/null | tr -d '\r')" in
    *Kiwi*|*K40*|kona*|*M2012K11*) echo "预检 :: 靶机疑似真机——本批零触碰"; exit 2 ;;
esac
[ -n "$($ADB get-serialno 2>/dev/null | tr -d '\r')" ] || { echo "预检 :: 无可读靶机"; exit 2; }
VC=$($ADB shell dumpsys package com.anytouch.app 2>/dev/null | tr -d '\r' | grep -m1 versionCode | sed 's/.*versionCode=//; s/[^0-9].*//')
[ "$VC" = "$EXPECTED_VC" ] || { echo "预检 :: 机上 versionCode=$VC 与本轮应有 $EXPECTED_VC 不符——装错包"; exit 2; }
APK_MD5=$(md5sum "$E9_APK" | cut -d' ' -f1)
PM_PATH=$($ADB shell pm path com.anytouch.app 2>/dev/null | tr -d '\r' | head -1 | sed 's/^package://')
[ -n "$PM_PATH" ] || { echo "预检 :: pm path 读不到机上 apk——对不了账"; exit 2; }
$ADB pull "$PM_PATH" .smoke-tmp/installed-v106.apk >/dev/null 2>&1 || { echo "预检 :: pull 机上 apk 失败"; exit 2; }
INS_MD5=$(md5sum .smoke-tmp/installed-v106.apk | cut -d' ' -f1)
[ "$INS_MD5" = "$APK_MD5" ] || { echo "预检 :: 机上包 md5=$INS_MD5 ≠ 发布件 $APK_MD5——跑出来不是当前源码"; exit 2; }
H=$(s5g_health)
printf '%s' "$H" | grep -q '"ok": true' || { echo "预检 :: 服务器健康口无应答（[$H]）——判据 10 不放行，本跑终止"; exit 2; }
s5g_relay_up || { echo "预检 :: 测试中继起不来（靶机打不到服务器）——**绝不为变绿加本地旁路**，本跑终止"; exit 2; }

SIZE=$($ADB shell wm size | tail -1 | tr -d '\r' | sed 's/.*: *//')
W=${SIZE%x*}; H2=${SIZE#*x}
{
    echo "# s5g-server-activation $STAMP · 靶机=$($ADB shell getprop ro.build.version.release | tr -d '\r') api=$VC ${W}x${H2}"
    echo "# E9_APK=$E9_APK md5=$APK_MD5（机上 pull 回来逐字同值）· 服务器 health=[$H]"
    echo "# 通道：靶机 $S5G_TARGET_IP:$S5G_TARGET_PORT → 本机中继 → 机上正规服务（产品代码零改动，TLS 端到端仍在 APP 侧校验）"
} > "$RAW"
log "预检 ok · vc=$VC · md5 对账相符 · 服务器与中继都在位"

# ---- 通用件 ----
XMIG=/sdcard/.s5g.xml; XHOST=.smoke-tmp/s5g.xml
wait_bound() {
    local i=0
    while [ "$i" -lt 25 ]; do
        $ADB shell dumpsys accessibility 2>/dev/null | grep -q "Bound services:{Service\[label=Anytouch" && return 0
        sleep 1; i=$((i + 1))
    done
    return 1
}
dump_idle() {
    $ADB shell uiautomator dump "$XMIG" >/dev/null 2>&1
    $ADB shell cat "$XMIG" | tr -d '\r' > "$XHOST"
    wait_bound || { bad "dump 后服务未复绑 :: 下一条判据不可信"; return 1; }
}
swipe_to_top() { local _i; for _i in 1 2 3 4 5 6; do $ADB shell input swipe "$((W / 2))" 500 "$((W / 2))" 1800 300 >/dev/null 2>&1; sleep 1; done; }
swipe_down()   { $ADB shell input swipe "$((W / 2))" 1800 "$((W / 2))" 500 300 >/dev/null 2>&1; sleep 1; }
find_cell() { # $1=tag → 0=该格在当前 $XHOST（读不到就整页走位，绝不原地 dump 一次就报"屏上没有"）
    local tag="$1" i=0
    dump_idle || return 1
    grep -qa "resource-id=\"$tag\"" "$XHOST" && return 0
    swipe_to_top
    while [ "$i" -lt 10 ]; do
        dump_idle || return 1
        grep -qa "resource-id=\"$tag\"" "$XHOST" && return 0
        swipe_down; i=$((i + 1))
    done
    return 1
}
cell_text() { tr '<' '\n' < "$XHOST" | grep -a "resource-id=\"$1\"" | head -1 | grep -o 'text="[^"]*"' | head -1 | sed 's/^text="//; s/"$//'; }
logs() { $ADB logcat -d -s AnytouchRun:* AnytouchOverlay:* 2>/dev/null; }
clr()  { $ADB logcat -c >/dev/null 2>&1; }
inject() { $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --ez keep_fg true $*" >/dev/null 2>&1; }
launch_home() { inject "--es smoke_probe just_bring_home_up"; }
flag_on_disk() { $ADB shell "run-as com.anytouch.app cat files/activation/flag.txt" 2>&1 | tr -d '\r'; }
journal_grep() { # $1=固定串（只允许尾四位/单词，绝不含整码）$2=秒 → 计数
    timeout 60 ssh $S5G_SSH_OPTS "$S5G_SSH" \
        "journalctl -u anytouch-activate --since '-${2}s' --no-pager | grep -a '$1' | wc -l" 2>/dev/null | tr -d ' \r'
}
# "这段时间里服务器到底收没收到请求"只能用**游标**问，不能用 `--since -Ns` 问：
# run1 的 R7d 断网前查 60 秒窗、断网后查 180 秒窗，两个窗子本来就不等长，
# 于是把 R1/R2/R4e 自己的请求算进"断网期间新增"里，报了个"3 → 9 条"的假红。
# 游标是 journalctl 的绝对位置：`--cursor X` 之后有几条就是几条，与查表时刻的先后无关。
journal_cursor() {
    # journalctl 打的是 `-- cursor: s=…;i=…;b=…;m=…;t=…;x=…`（机上实测原文）：前缀 `-- ` 必须一起吃掉，
    # 而分隔符是分号——上一版 sed 只剥 `^cursor: `，取回空串，于是 R7d 整格判不了（run2 实测）。
    timeout 60 ssh $S5G_SSH_OPTS "$S5G_SSH" \
        'journalctl -u anytouch-activate -n0 --show-cursor --no-pager 2>/dev/null | sed -n "s/^.*cursor: //p" | tail -1' 2>/dev/null | tr -d ' \r'
}
journal_after() { # $1=游标 $2=固定串 → 该游标之后的条数（游标只许 journalctl 那一组字符，别的绝不拼进 ssh 命令）
    local c="${1:-}" pat="$2" n
    case "$c" in
    "" | *[!A-Za-z0-9:_=\;-]*) echo "journal_cursor_invalid" >&2; return 2 ;;
    esac
    n=$(timeout 60 ssh $S5G_SSH_OPTS "$S5G_SSH" \
        "journalctl -u anytouch-activate --cursor '$c' --no-pager 2>/dev/null | grep -ac '$pat'" 2>/dev/null | tr -d ' \r')
    printf '%s' "${n:-0}"
}
# 异步半边：先清日志再派发，然后**轮询终态行**（单发 grep 会把"还在路上"读成"被拒"）
submit_code() { # $1=整码 $2=reset?（1=先打回未激活） → 终态行打印到 stdout；1=超时
    local code="$1" doReset="${2:-1}" i out tail
    tail=${code: -4}
    printf '%s\n' "$code" >> "$CODEBOOK"
    clr
    if [ "$doReset" = "1" ]; then
        inject "--ez activation_reset true --es activation_code '$code'"
    else
        inject "--es activation_code '$code'"
    fi
    i=0
    while [ "$i" -lt 35 ]; do
        out=$(logs | grep -a "S5FSMOKE activation \(ok\|refused\)" | tail -1 | tr -d '\r')
        [ -n "$out" ] && { printf '%s' "$out"; return 0; }
        sleep 1; i=$((i + 1))
    done
    return 1
}
state_says() { # $1=期望子串
    find_cell activation_state || { printf ''; return 1; }
    cell_text activation_state | grep -qa "$1"
}
rejection_says() { # $1=期望子串 $2=禁止出现的子串
    find_cell activation_rejection || { printf ''; return 1; }
    local t; t=$(cell_text activation_rejection)
    printf '%s' "$t" | grep -qa "$1" || return 1
    [ -z "$2" ] || ! printf '%s' "$t" | grep -qa "$2"
}
# 三枚进墙入口的"这一态到底放没放行"——用最硬的一条留痕当凭（同一落账口，s5f 同族）。
# 两条都是这两轮量出来的口径，缺一条就读死人：
# ① **必须轮询**：`S5ESMOKE saved task` 那行回执是异步写的，单发一次 grep 把"还在路上"读成"被拦"（run1 R7b）。
# ② **判据只能是行里那个 gate= 的名字，不是"有没有 ok"**：install -r 换进程后屏上步序账是空的，
#    save 本就该吃 EMPTY_LEDGER（s5f 判据 4 的同一条产品性质）。run1/run2 的 R7b 连续两红红的就是这里——
#    拿"没有 ok 行"当"被付费墙拦"，等于把产品正确的拒绝记成产品的罪。
#    所以：墙放行 ⇔ 出现了回执行 且 gate 不是 NOT_ACTIVATED；出现 ok 更好，但 EMPTY_LEDGER 同样算过墙。
#    "回执行一条都没有"仍然算不过（读数器没打到/进程死了），绝不放行为绿。
entry_gate() { # → 打印本轮回执行；0=付费墙放行，1=仍被墙拦或没读到
    local i=0 line
    clr
    inject "--es task_save g1-$STAMP-$RANDOM"
    while [ "$i" -lt 15 ]; do
        line=$(logs | grep -a "S5ESMOKE saved task op=save" | tail -1 | tr -d '\r')
        if [ -n "$line" ]; then
            printf '%s' "$line"
            printf '%s' "$line" | grep -qa "gate=NOT_ACTIVATED" && return 1
            return 0
        fi
        sleep 1; i=$((i + 1))
    done
    printf ''
    return 1
}
EG_LINE=""; EG_RC=1
entry_wall_refused() { # → 0=确实被墙拦（读到 gate=NOT_ACTIVATED 那一行）
    EG_LINE=$(entry_gate); EG_RC=$?
    [ "$EG_RC" = 1 ] && printf '%s' "$EG_LINE" | grep -qa "gate=NOT_ACTIVATED"
}
entry_wall_open() { # → 0=过了墙（读到回执行，且拦它的不是墙）
    EG_LINE=$(entry_gate); EG_RC=$?
    [ "$EG_RC" = 0 ]
}

# ================= 起始态 =================
lease() { # 取一枚在册 staging 码（只回尾四位给日志）
    local c
    c=$(s5g_lease_staging) || return 2
    printf '%s' "$c"
}
launch_home; clr
inject "--ez activation_reset true"
R=$(logs | grep -a "activation reset ok=" | tail -1 | tr -d '\r')
[ -n "$R" ] || { echo "前置 :: 复位通道没留痕，起始态不可信"; exit 2; }
s5g_reset_staging >/dev/null || { echo "前置 :: staging 段清不干净（服务器侧管理口未应答）"; exit 2; }
if state_says "not activated"; then pass "前置 :: 起始态=未激活且 staging 段已清空"
else bad "前置 :: 起始态未由屏上锚确认（后面每一格都不可信）"; exit 2; fi

# ================= R1 通道自证 =================
log "--- R1 通道自证：设备打到的是真服务器 ---"
C1=$(lease) || { bad "R1 :: 租不到在册 staging 码"; exit 2; }
T1=${C1: -4}
LINE=$(submit_code "$C1" 1); RC=$?
note "R1 终态行：$LINE"
if [ "$RC" = "0" ] && printf '%s' "$LINE" | grep -qa "activation ok tail=$T1"; then
    pass "R1a :: 在册码在设备上解锁成功（$LINE）"
    UNLOCKED=1
else
    bad "R1a :: 在册码没能解锁（实际：${LINE:-35s 无终态行}）——通道不成立，后面全格作废"
    exit 1
fi
J=$(journal_grep "activate ok code=$T1" 60)
[ "${J:-0}" -ge 1 ] && pass "R1b :: 机上 journal 同窗有该尾四位的 activate 行（$J 条）——读数不属于假靶" \
    || bad "R1b :: 设备说 ok 但服务器侧 0 条同窗 activate 行（计数=[$J]）——这一格的绿没有对面"

# ================= R2 幂等重放 =================
log "--- R2 幂等重放：同一台第二次交同一枚码 ---"
LINE=$(submit_code "$C1" 0); RC=$?
note "R2 终态行：$LINE"
if [ "$RC" = "0" ] && printf '%s' "$LINE" | grep -qa "activation ok tail=$T1" && printf '%s' "$LINE" | grep -qa "seats=1/2"; then
    pass "R2 :: 重放仍成功且额度不涨（seats 仍 1/2）"
else
    bad "R2 :: 重放档位不对（实际：${LINE:-无终态行}）"
fi

# ================= R3 本地格式过 · 服务器拒（判据 3 新增档） =================
log "--- R3 本地合法但未在册 ---"
PY="$(command -v python3 || command -v python || true)"
DIRTY=$("$PY" scripts/activation-code.py "PRO9FAN8B1" 2>/dev/null | tail -1 | tr -d '\r')
[ "$("$PY" scripts/activation-code.py --verify "$DIRTY" 2>/dev/null | tail -1 | tr -d '\r')" = "UNLOCKED" ] \
    || { bad "R3 前置 :: mint 出来的码本地自检就不是 UNLOCKED——这一档测不到服务器那一格"; }
TD=${DIRTY: -4}
LINE=$(submit_code "$DIRTY" 1); RC=$?
note "R3 终态行：$LINE"
[ "$RC" = "0" ] && printf '%s' "$LINE" | grep -qa "refused gate=SERVER_INVALID" \
    && pass "R3a :: 本地过·服务器拒 → gate=SERVER_INVALID（尾四位 $TD）" \
    || bad "R3a :: 未落 SERVER_INVALID（实际：${LINE:-无终态行}）——本地格式过却没被服务器判掉，就是旁路"
UNLOCKED=0
rejection_says "Activation code invalid" "" \
    && pass "R3b :: 屏上落军令原话 \"Activation code invalid\"" \
    || bad "R3b :: 屏上没说 \"Activation code invalid\"（读到的：[$(cell_text activation_rejection)]）"
flag_on_disk | grep -qi "no such file" && pass "R3c :: 盘上 flag 缺席（真没解锁，不是显示没跟上）" \
    || bad "R3c :: 屏上说没解锁、盘上却有 flag：$(flag_on_disk)"
entry_wall_refused && pass "R3d :: 被拒后三入口照旧停在付费墙（回执：$EG_LINE）" \
    || bad "R3d :: 被服务器拒过之后入口竟然没被墙拦住（回执：${EG_LINE:-一条都没读到}）——解锁写到了不该写的地方"

# ================= R4 满员与解绑（判据 2 设备半边） =================
log "--- R4 占满 2 格 → 设备落 seats_full → 解绑一格 → 设备进得去 ---"
C2=$(lease) || { bad "R4 :: 租不到码"; }
if [ -n "${C2:-}" ]; then
    T2=${C2: -4}
    A=$(_s5g_post "/api/activate" "{\"code\":\"$C2\",\"device_hash\":\"aa11aa11bb22bb22\"}" plain)
    B=$(_s5g_post "/api/activate" "{\"code\":\"$C2\",\"device_hash\":\"cc11cc11dd22dd22\"}" plain)
    note "R4 服务器侧两格：$A / $B"
    LINE=$(submit_code "$C2" 1); RC=$?
    note "R4 设备终态行：$LINE"
    [ "$RC" = "0" ] && printf '%s' "$LINE" | grep -qa "refused gate=SERVER_SEATS_FULL" \
        && pass "R4a :: 满员的码到设备 = 第 3 台被拒（gate=SERVER_SEATS_FULL）" \
        || bad "R4a :: 第 3 台没被拒（实际：${LINE:-无终态行}）"
    rejection_says "activated on 2 devices, maximum reached" "" \
        && pass "R4b :: 屏上落军令原话那一句满员提示" \
        || bad "R4b :: 屏上没说满员（读到的：[$(cell_text activation_rejection)]）"
    flag_on_disk | grep -qi "no such file" || bad "R4c :: 满员被拒却留下了 flag：$(flag_on_disk)"
    D=$(_s5g_post "/api/deactivate" "{\"code\":\"$C2\",\"device_hash\":\"aa11aa11bb22bb22\",\"admin_token\":\"a-wrong-token\"}" plain)
    note "R4 错 token 解绑（必须 403）：$D"
    printf '%s' "$D" | grep -qa '"forbidden"' && pass "R4d :: 解绑口认错 token（forbidden 原样在册）" \
        || bad "R4d :: 解绑口的鉴权读数不是 forbidden（实际：$D）——先当安全面红来查"
    # 用管理口（token 在机上读，绝不过本机）真解一格，再看设备进得去
    REM=$(s5g_admin "/api/admin/reset-staging")
    note "R4 清 staging：$REM"
    LINE=$(submit_code "$C2" 1); RC=$?
    [ "$RC" = "0" ] && printf '%s' "$LINE" | grep -qa "activation ok tail=$T2" \
        && pass "R4e :: 腾出格子之后同一枚码在设备上进得去（解绑口真有效）" \
        || bad "R4e :: 清完格子仍进不去（实际：${LINE:-无终态行}）"
fi

# ================= R5 fail-closed 两切法（判据 4） =================
log "--- R5 fail-closed ---"
failclosed_cell() { # $1=格名 $2=**切之前**租好的整码
    local seg="$1" C="$2" T LINE
    [ -n "$C" ] || { bad "$seg :: 切之前就没拿到码，切法未证"; return; }
    T=${C: -4}
    LINE=$(submit_code "$C" 1)
    note "$seg 终态行：$LINE"
    printf '%s' "$LINE" | grep -qa "refused gate=SERVER_UNREACHABLE" \
        && pass "$seg :: 切不断的路反而证不了——这一条**必须**落 SERVER_UNREACHABLE（实测档位对，尾四位 $T）" \
        || bad "$seg :: 服务器不可达却没有落 fail-closed（实际：${LINE:-无终态行}）"
    rejection_says "network connection" "invalid" \
        && pass "$seg :: 屏上给的是**联网归因**句，没混进\"码无效\"（买家不该被误导去退单）" \
        || bad "$seg :: 屏上话术不对（读到的：[$(cell_text activation_rejection)]）"
    flag_on_disk | grep -qi "no such file" && pass "$seg :: 盘上 flag 缺席=真没解锁" \
        || bad "$seg :: 不可达却写了 flag：$(flag_on_disk)"
    # 这里的"拒"不是孤立读数：上面那行 refused 就是同一进程同一秒里留的痕，app 活着、intent 收到了
    entry_wall_refused && pass "$seg :: 三入口照旧拒（回执：$EG_LINE）" \
        || bad "$seg :: 未解锁状态下入口竟然没被墙拦住（回执：${EG_LINE:-一条都没读到}）——fail-closed 只是话术"
}
# **两枚码都要在动手切之前租**：R5b 切的是服务本身，切完了管理口就不应答，
# 到那时再去租码就只剩两种结局——要么租空、要么租空的那句错误把真因盖掉（run1 两样都占了：
# 空响应 + `set -u` 下未 def 的 `$S5G_DIRECT` 直接把 R5b 顶成 unbound variable）。
C5A=$(lease) || C5A=""
s5g_relay_down >/dev/null 2>&1
log "R5a :: 撤路由（靶机真打不出去）"
failclosed_cell "R5a" "$C5A"
s5g_relay_up || { echo "R5 后置 :: 中继恢复失败——服务器侧那一切读数从此不可信"; exit 2; }
C5B=$(lease) || C5B=""
timeout 60 ssh $S5G_SSH_OPTS "$S5G_SSH" 'systemctl stop anytouch-activate; sleep 1; systemctl is-active anytouch-activate' >/dev/null 2>&1
log "R5b :: 路由在、对面没人应（服务停）"
failclosed_cell "R5b" "$C5B"
S=$(timeout 60 ssh $S5G_SSH_OPTS "$S5G_SSH" 'systemctl start anytouch-activate; sleep 2; systemctl is-active anytouch-activate' 2>/dev/null | tr -d '\r')
[ "$S" = "active" ] || { echo "R5 收尾 :: 服务没能复原（is-active=[$S]）——绝不带着停机的机往下跑"; exit 2; }
H=$(s5g_health); printf '%s' "$H" | grep -q '"ok": true' || { echo "R5 收尾 :: 服务起了但健康口不应（[$H]）"; exit 2; }
pass "R5z :: 正规服务已复原且在应答（$H）"

# ================= R6 TLS 反向锁（判据 8） =================
log "--- R6 指到第二份自签证书上：APP 必须自己断 ---"
UP=/tmp/s5g-fake-up.$$
cat > "$UP" <<'EOS'
rm -f /tmp/s5gfake.crt /tmp/s5gfake.key /tmp/s5gfake.db /tmp/s5gfake.log /tmp/s5gfake.pid
openssl req -x509 -newkey rsa:2048 -keyout /tmp/s5gfake.key -out /tmp/s5gfake.crt -days 2 -nodes \
  -subj "/CN=anytouch-impostor" >/dev/null 2>&1 || { echo "FAKE=cert-fail"; exit 0; }
ADMIN_TOKEN=impostor-token-not-used ACTIVATION_DB=/tmp/s5gfake.db TLS_CERT=/tmp/s5gfake.crt \
TLS_KEY=/tmp/s5gfake.key PORT=8444 nohup timeout 300 /usr/bin/python3 /opt/anytouch-activate/app.py \
  > /tmp/s5gfake.log 2>&1 &
echo $! > /tmp/s5gfake.pid
sleep 3
echo "LISTEN=$(ss -ltn 'sport = :8444' | tail -1)"
echo "IMPOSTOR_SPKI=$(openssl x509 -in /tmp/s5gfake.crt -pubkey -noout 2>/dev/null | openssl pkey -pubin -outform der 2>/dev/null | openssl dgst -sha256 -r | cut -c1-16)"
EOS
FAKE=$(s5g_box_sh < "$UP" 2>/dev/null | tr -d '\r')
note "R6 假靶起机读数：$FAKE"
ISP=$(printf '%s' "$FAKE" | sed -n 's/^IMPOSTOR_SPKI=//p')
printf '%s' "$FAKE" | grep -q ':8444' || bad "R6 前置 :: 假靶没起在 8444（读数：$FAKE）"
[ -n "$ISP" ] && [ "${ISP:0:16}" != "$S5G_PIN" ] \
    && pass "R6a :: 假靶证书指纹与固定值不同（异 $ISP ≠ $S5G_PIN）——这一格的靶子是真的异指纹" \
    || bad "R6a :: 假靶指纹核对不成立（读到 [$ISP]）——靶子不假，测了也白测"
# 换靶必须**把假靶那张指纹作为期望值传进中继**（旧的 ALLOW_FOREIGN_PIN="什么都不核"那一支已删：
# run1 就是它让"复用已在位的隧道"把旧通道冒充成换靶成功，设备打到真服务器拿回 ok，判据 8 红反）。
# 变量只在子 shell 里生效：`VAR=f` 这种前缀赋值对**函数**会不会残留，bash 各家行为不一致，
# 而这里残留的那一枚是"本轮期望指纹＝假靶"——留着它，后面所有正脸的格都失去意义。故一律括起来跑。
# 这一段**不再吞输出**：中继那两行（对面实测指纹 / 拆旧隧道）是这一格的凭据，必须进 raw。
REL=$( ( export S5G_RELAY_TARGET=127.0.0.1:8444 S5G_RELAY_EXPECT_SPKI="$ISP"; s5g_relay_restart ) 2>&1 ); RRC=$?
note "R6 换靶读数（rc=$RRC）：$REL"
[ "$RRC" = "0" ] || bad "R6 前置 :: 中继换不到假靶对面（rc=$RRC，读数见 raw）——这一格只能红，不能跳"
# 通道正证：本机经中继打一次 health，**假靶自己的访问日志必须记到这一条**。
# 没有这一步，"假靶 0 条 POST"完全可能只是"根本没通到假靶"（run1 的 R6c 就是这种空转的绿）。
PROBE=$(timeout 20 curl -sk --max-time 10 "https://127.0.0.1:$S5G_RELAY_PORT/api/health" 2>/dev/null)
note "R6 通道正证探测（走中继对面）：$PROBE"
sleep 2
HIT=$(timeout 60 ssh $S5G_SSH_OPTS "$S5G_SSH" 'n=$(grep -ac "GET /api/health" /tmp/s5gfake.log 2>/dev/null); echo "N=${n:-0}"' 2>/dev/null | sed -n 's/^N=//p' | tr -d ' \r')
[ "${HIT:-0}" -ge 1 ] 2>/dev/null \
    && pass "R6b :: 通道正证——假靶访问日志记到了这次 health（$HIT 条）⇒ 中继对面确实是它，不是正规服务" \
    || bad "R6b :: 假靶日志 0 条 health（[${PROBE:0:40}]）——通道没指在假靶上，这一格测的是空气，不许拿下面的 0 条 POST 充绿"
J0=$(journal_grep "activate ok" 120)
C3=$(lease) || { bad "R6 :: 租不到码"; }
if [ -n "${C3:-}" ]; then
    LINE=$(submit_code "$C3" 1)
    note "R6 设备终态行：$LINE"
    printf '%s' "$LINE" | grep -qa "refused gate=SERVER_UNREACHABLE" \
        && pass "R6c :: 证书指纹不符时设备**自己断开**并落 SERVER_UNREACHABLE（不是降级放行）" \
        || bad "R6c :: 对着异指纹证书竟然没拒（实际：${LINE:-无终态行}）——指纹固定是装饰，判据 8 红"
    BAD=$(timeout 60 ssh $S5G_SSH_OPTS "$S5G_SSH" 'n=$(grep -ac "POST /api/activate" /tmp/s5gfake.log 2>/dev/null); echo "N=${n:-0}"' 2>/dev/null | sed -n 's/^N=//p' | tr -d ' \r')
    [ "${BAD:-x}" = "0" ] && pass "R6d :: 假靶日志里有 health 却没收到任何 activate 请求（GET=$HIT / POST=0）——握手就没握上，反向锁在 TLS 层生效" \
        || bad "R6d :: 假靶竟然收到了 activate 请求（$BAD 条）——固定值没挡住转发"
    J1=$(journal_grep "POST /api/activate" 120)
    log "正规服务同窗 activate 类行=$J1（起假靶前 activate ok=$J0，只作对照不作判据）"
fi
timeout 60 ssh $S5G_SSH_OPTS "$S5G_SSH" 'pkill -F /tmp/s5gfake.pid 2>/dev/null; rm -f /tmp/s5gfake.*; sleep 1; ss -ltn "sport = :8444" | wc -l' >/dev/null 2>&1
# 必须 restart 而不是 up：假靶的 health 应答里同样写着 "anytouch-activate"，
# 直接 up 会走"复用已在位的隧道"那一支，把"换回正规对面"这句说成假话（SPKI 核对是这里唯一的兜底）
s5g_relay_restart || { echo "R6 收尾 :: 中继回不到正规对面（SPKI 核对没过）"; exit 2; }
pass "R6z :: 假靶已拆、中继回到正规对面（SPKI 核对在 up 里做）"

# ================= R7 已激活离线照用 + 零请求（判据 5） =================
log "--- R7 已激活之后断网照用，且一请求不发 ---"
C4=$(lease) || { bad "R7 :: 租不到码"; }
if [ -n "${C4:-}" ]; then
    T4=${C4: -4}
    LINE=$(submit_code "$C4" 1)
    printf '%s' "$LINE" | grep -qa "activation ok tail=$T4" || bad "R7 前置 :: 没能先解锁（${LINE:-无终态行}）"
    $ADB install -r "$E9_APK" >/dev/null 2>&1 || bad "R7 前置 :: install -r 失败（跨进程格未证）"
    launch_home; sleep 2
    if state_says "unlocked on this device"; then UNLOCKED=1; pass "R7a :: install -r 换进程后仍以磁盘件为凭（屏上锚确认已解锁）"
    else bad "R7a :: 换进程后屏上没报已解锁（读到的：[$(cell_text activation_state)]）"; UNLOCKED=0; fi
    # 游标排在解锁之后：这一格问的是"从这一刻起，服务器还收不收得到这台机器的 activate"
    JC=$(journal_cursor) || { bad "R7 前置 :: 取不到 journal 游标（服务器侧零请求这一判据无法量化）"; JC=""; }
    # **中继留着不撤**：这条判据要的是"有路可走而不走"，把路拆了再数 0 条是必然成立的笑话
    # （run1 是"飞行 + 撤中继 + 两个不等长窗"三件叠一起，报了个 3→9 的假红）。
    $ADB shell "cmd connectivity airplane-mode enable" >/dev/null 2>&1; sleep 4
    entry_wall_open && pass "R7b :: 断网（飞行）后三入口放行（解锁态不依赖每次联网；回执：$EG_LINE）" \
        || bad "R7b :: 已激活的机器断网就被拦（判据 5 的\"离线兜底\"没兑现；回执：${EG_LINE:-一条都没读到}）"
    clr
    inject "--es task_json '[{\"action_id\":\"w1\",\"type\":\"wait\",\"source\":\"node\",\"value\":{\"ms\":1000},\"safety\":{\"viewport_ok\":true,\"click_enabled\":true}}]' --es repeat_count 1 --es repeat_interval 2"
    i=0; RLINE=""
    while [ "$i" -lt 40 ]; do
        RLINE=$(logs | grep -a "S1SMOKE ok=" | tail -1 | tr -d '\r')
        [ -n "$RLINE" ] && break
        sleep 1; i=$((i + 1))
    done
    [ -n "$RLINE" ] && pass "R7c :: 飞行模式下一次任务照跑（$RLINE）" \
        || bad "R7c :: 飞行模式下任务跑不起来（执行期被网络拖住=红线 C/G 破）"
    $ADB shell "cmd connectivity airplane-mode disable" >/dev/null 2>&1; sleep 3
    # 计数装置自检：主机这边经中继打一发 health，机上日志必须出现它——否则"0 条"是仪器坏了，不是没发。
    PROBE2=$(timeout 20 curl -sk --max-time 10 "https://127.0.0.1:$S5G_RELAY_PORT/api/health" 2>/dev/null)
    sleep 2
    PB=$(journal_after "${JC:-}" "GET /api/health")
    PBA=$?
    note "R7 计数装置自检：游标之后 health 行=$PB（探测体 [${PROBE2:0:40}]）"
    POSTS=$(journal_after "${JC:-}" "POST /api/activate")
    if [ "$PBA" != "0" ]; then
        bad "R7d :: 游标不合法，服务器侧零请求这条**未量化**（判据 5 不许用'大概没发'过）"
    elif [ "${PB:-0}" = "0" ]; then
        bad "R7d :: 计数装置没自证（游标之后连我这发 health 都没记到）——那 0 条 activate 不作数，判据 5 红"
    elif [ "${POSTS:-1}" = "0" ]; then
        pass "R7d :: 游标之后服务器侧 activate 请求 0 条（同窗我这发 health 记到了 $PB 条 ⇒ 装置是活的，是这台机器一条没发）"
    else
        bad "R7d :: 已激活且断网，游标之后服务器侧仍多出 $POSTS 条 activate 请求——使用中联网是判据 5/6 的红线"
    fi
    # 再补硬的一半：路也拆了（真不可达）仍然是解锁态、入口照旧放行
    s5g_relay_down >/dev/null 2>&1
    entry_wall_open && pass "R7e :: 连中继一起撤掉仍是解锁态、入口照旧放行（离线兜底不靠每次问服务器；回执：$EG_LINE）" \
        || bad "R7e :: 撤掉中继之后已激活机器被拦（离线兜底只对'网络在'的机器成立=口径不完整；回执：${EG_LINE:-一条都没读到}）"
    s5g_relay_up || { echo "R7 收尾 :: 中继恢复失败"; exit 2; }
fi

# ================= R8 尾四位（判据 7） =================
log "--- R8 整码/完整指纹不出屏 ---"
launch_home; swipe_to_top
SCREEN_HITS=0
i=0
PAGEFILE=.smoke-tmp/s5g-pages.$$.txt; : > "$PAGEFILE"
while [ "$i" -lt 10 ]; do
    dump_idle || break
    cat "$XHOST" >> "$PAGEFILE"
    grep -qa "activation_state" "$XHOST" || true
    swipe_down; i=$((i + 1))
done
note "R8 分页扫描 $i 屏，语义树累计 $(wc -c < "$PAGEFILE") 字节"
[ "$i" -ge 3 ] || bad "R8 前置 :: 只翻了 $i 屏，分页判据不成立（读数器没走到位）"
FULL=$(logs | tr -d '\r')
while IFS= read -r c; do
    [ -n "$c" ] || continue
    grep -qa "$c" "$PAGEFILE" && { SCREEN_HITS=$((SCREEN_HITS + 1)); bad "R8a :: 整枚在册码出现在语义树里（尾四位 ${c: -4}）"; }
    printf '%s' "$FULL" | grep -qa "$c" && { SCREEN_HITS=$((SCREEN_HITS + 1)); bad "R8a :: 整枚在册码出现在 logcat 里（尾四位 ${c: -4}）"; }
done < "$CODEBOOK"
[ "$SCREEN_HITS" = "0" ] && pass "R8a :: $(wc -l < "$CODEBOOK") 枚本轮整码在语义树与 logcat 里 0 命中"
printf '%s' "$FULL" | grep -qaE '[0-9a-f]{16}' \
    && bad "R8b :: logcat 里出现 16 位十六进制串——完整设备指纹漏了（只许尾档）" \
    || pass "R8b :: logcat 里没有任何 16 位十六进制串（指纹不出日志）"
grep -qaE 'ANY-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}' "$RAW" \
    && bad "R8c :: 本脚本自己的 raw 里出现整码形状——脱敏没兜住" \
    || pass "R8c :: raw 自身反向扫描 0 命中（遮没遮住只信反向扫描，不信写法）"

# ================= 收尾：回到"未激活 + staging 空" =================
clr
inject "--ez activation_reset true"
logs | grep -qa "activation reset ok=true" && pass "收尾 :: 设备打回未激活" || bad "收尾 :: 复位未留痕"
s5g_reset_staging >/dev/null && pass "收尾 :: staging 段绑定清空（买家段没被这个口碰过）" || bad "收尾 :: staging 清不干净"
rm -f "$PAGEFILE"
echo "# 结果：PASS=$passed FAIL-CELL=$failed" >> "$RAW"
echo "----------------------------------------"
echo "s5g-server-activation :: PASS=$passed FAIL-CELL=$failed RAW=$RAW"
if [ "$fail" = "1" ]; then echo "SCRIPT-RC=1"; exit 1; fi
echo "SCRIPT-RC=0"; exit 0
