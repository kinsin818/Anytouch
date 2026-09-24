#!/usr/bin/env bash
# Anytouch S4-a · 真机全流程自跑（军令 orders/ANYTOUCH-S4-packaging-ORDER.md §1/§3 S4-a）
#
# 覆盖的是 E 系列没串过的那条"用户视角整链"：起应用 → 授权在位 → 代填 Key → 真 HTTPS 编译 →
# 任务框建议与账**逐字**相等（设备半边，D 批只在 JVM 锁过）→ 断网 → 执行任务 → 目标 App 屏上真变化 →
# 复网 → keyed byok-smoke 复跑收尾（证明本批没把机器弄脏，判据 §3-4）。
#
# 用法：ANDROID_SERIAL=<serial> KEY_FILE=<path> bash scripts/s4-fullflow-k40.sh
#   前置：装好本轮 debug APK、无障碍已绑、屏已解锁。Key 只进注入脚本的 input 通道，
#   本脚本**不读不印 Key**（屏上回读只允许 `***尾4`，同 S3-R5 口径）。
#
# 真请求上限（军令 §2-4 代钉）：全轮编译触发点只有 S3（本脚本 1 次）与收尾复跑的 byok-smoke
#   （E5+E8 共 2 次）＝3 次 ≤4；任何失败重试前先看 REQ_USED，触顶即停。
#
# 复用 byok-smoke 的设备实证（不重新发明读数口径）：
#   · dump 只含视口 → "屏上没有"类判断必须先 harvest 到底（到底+无缝两锁），不满足判"读数不可信"；
#   · 执行期禁 dump（会挤掉自家服务并取消 runTask）→ S6 只在执行前后各 dump 一次；
#   · 日志判据走 `logcat -d`（-G 16M 扩档），后台流只留档；
#   · 常驻进程真冷启动在测试通道做不了（F-3）→ S1 记"测试通道所见进程"，不冒称冷启动。
set -u

ADB="adb"
SER="${ANDROID_SERIAL:-}"
[ -n "$SER" ] && ADB="adb -s $SER"
KEY_FILE="${KEY_FILE:-}"

fail=0; ran=0; skipped=0; REQ_USED=0; REQ_CAP="${REQ_CAP:-4}"
RED='\033[0;31m'; GRN='\033[0;32m'; YLW='\033[0;33m'; NCT='\033[0m'
pass() { printf "${GRN}PASS${NCT} %s\n" "$1"; ran=$((ran + 1)); }
bad()  { printf "${RED}FAIL${NCT} %s\n" "$1"; fail=1; }
skip() { printf "${YLW}SKIP${NCT} %s\n" "$1"; skipped=$((skipped + 1)); }
log()  { printf '      | %s\n' "$*"; }
ts()   { printf '[%s] %s\n' "$(date '+%F %T')" "$*"; }

[ -n "$KEY_FILE" ] && [ -f "$KEY_FILE" ] || { ts "前置失败：KEY_FILE 未给或文件不存在（Key 只从文件读，不进本脚本回显）"; exit 2; }
DEVCOUNT=$(adb devices | grep -ac 'device$' || true)
if [ -z "$SER" ] && [ "$DEVCOUNT" != "1" ]; then ts "前置失败：多设备环境必须钉 ANDROID_SERIAL"; exit 2; fi

wait_service_bound() {
    local i=0
    while [ "$i" -lt 20 ]; do
        MSYS_NO_PATHCONV=1 $ADB shell dumpsys accessibility 2>/dev/null | grep -q "com.anytouch.app" && return 0
        sleep 1; i=$((i + 1))
    done
    return 1
}
logs() { MSYS_NO_PATHCONV=1 $ADB logcat -d -s AnytouchRun:* 2>/dev/null; }
reset_logs() { MSYS_NO_PATHCONV=1 $ADB logcat -c >/dev/null 2>&1; }
wait_line() {
    local pat="$1" limit="${2:-15}" i=0 out=""
    while [ "$i" -lt "$limit" ]; do
        out=$(logs | grep -a "$pat" | tail -1 || true)
        [ -n "$out" ] && { printf '%s' "$out"; return 0; }
        sleep 1; i=$((i + 1))
    done
    return 1
}
inject() {
    MSYS_NO_PATHCONV=1 $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --ez keep_fg ${2:-true} $1" >/dev/null 2>&1
    sleep 2
}

SIZE=$(MSYS_NO_PATHCONV=1 $ADB shell wm size 2>/dev/null | tr -d '\r' | grep -o '[0-9]*x[0-9]*' | tail -1)
W=${SIZE%x*}; H=${SIZE#*x}
[ -n "${W:-}" ] && [ -n "${H:-}" ] || { ts "前置失败：读不到屏幕尺寸"; exit 2; }
swipe_up()   { MSYS_NO_PATHCONV=1 $ADB shell input swipe "$((W / 2))" "$((H * 72 / 100))" "$((W / 2))" "$((H * 26 / 100))" 500 >/dev/null 2>&1; sleep 1; }
swipe_down() { MSYS_NO_PATHCONV=1 $ADB shell input swipe "$((W / 2))" "$((H * 26 / 100))" "$((W / 2))" "$((H * 72 / 100))" 500 >/dev/null 2>&1; sleep 1; }

SHEET=/sdcard/.s4page.xml
PAGE_TMP=$(mktemp); SHEET_TMP=$(mktemp); IDS_PREV=$(mktemp); IDS_NOW=$(mktemp)
trap 'rm -f "$PAGE_TMP" "$SHEET_TMP" "$IDS_PREV" "$IDS_NOW"' EXIT
own_ids() { grep -o 'resource-id="[a-z][a-z_0-9]*"' "$1" | sed 's/.*="//; s/"$//' | sort -u; }
# harvest_page：与 byok-smoke 同判据（到底=连续两屏逐字相同；无缝=相邻两屏共享自家节点）。
harvest_page() {
    local max_passes="${1:-12}" i=0 prev_hash="" same=0 hash overlap
    : > "$PAGE_TMP"; : > "$IDS_PREV"; SWEEP_OK=0; SWEEP_GAP=0; SWEEP_PASSES=0
    MSYS_NO_PATHCONV=1 $ADB shell am start -n com.anytouch.app/.MainActivity >/dev/null 2>&1
    sleep 2
    MSYS_NO_PATHCONV=1 $ADB shell input keyevent 111 >/dev/null 2>&1
    sleep 1
    for _ in 1 2 3 4 5 6; do swipe_down; done
    while [ "$i" -lt "$max_passes" ]; do
        MSYS_NO_PATHCONV=1 $ADB shell uiautomator dump "$SHEET" >/dev/null 2>&1 || true
        MSYS_NO_PATHCONV=1 $ADB shell cat "$SHEET" 2>/dev/null | tr -d '\r' > "$SHEET_TMP" || true
        cat "$SHEET_TMP" >> "$PAGE_TMP"
        MSYS_NO_PATHCONV=1 $ADB shell rm "$SHEET" >/dev/null 2>&1
        wait_service_bound || log "dump 后服务未回绑（本条读数仍可用，下一条可能假红）"
        own_ids "$SHEET_TMP" > "$IDS_NOW"
        if [ -s "$IDS_PREV" ] && [ -s "$IDS_NOW" ]; then
            overlap=$(comm -12 "$IDS_PREV" "$IDS_NOW" | wc -l | tr -d ' ')
            [ "${overlap:-0}" = "0" ] && { SWEEP_GAP=1; log "第 $((i + 1)) 屏与上一屏无公共节点（有缝）"; }
        fi
        cp "$IDS_NOW" "$IDS_PREV"
        hash=$(md5sum < "$SHEET_TMP" | cut -c1-32)
        if [ -n "$prev_hash" ] && [ "$hash" = "$prev_hash" ]; then SWEEP_OK=1; SWEEP_PASSES=$((i + 1)); return 0; fi
        prev_hash="$hash"; swipe_up; i=$((i + 1))
    done
    SWEEP_PASSES=$i; return 1
}
# text_of：从整页拼接里取某自家节点的 text 属性（节点=一行，先按 '<' 切段）。
text_of() {
    local tag="$1"
    tr '<' '\n' < "$PAGE_TMP" | grep -a "resource-id=\"$tag\"" | grep -ao 'text="[^"]*"' | head -1 | sed 's/^text="//; s/"$//'
}
xml_unesc() { sed 's/&quot;/"/g; s/&apos;/'"'"'/g; s/&lt;/</g; s/&gt;/>/g; s/&amp;/\&/g'; }
ping_ok() {
    MSYS_NO_PATHCONV=1 $ADB shell ping -c 1 -W 3 8.8.8.8 >/dev/null 2>&1
}

ts "===== S0 前置 ====="
APK=app/build/outputs/apk/debug/app-debug.apk
[ -f "$APK" ] && log "本轮靶 apk md5=$(md5sum "$APK" | cut -c1-32) 路径=$APK"
KG=$(MSYS_NO_PATHCONV=1 $ADB shell dumpsys window 2>/dev/null | tr -d '\r' | grep -a isKeyguardShowing | head -1)
case "$KG" in *true*) bad "S0a :: 锁屏在场（isKeyguardShowing=true），本通道绝不绕锁，终止"; exit 2;; *) pass "S0a 锁屏不在场 :: ${KG:-（未读到旗标，按非锁屏处理）}";; esac
SOT=$(MSYS_NO_PATHCONV=1 $ADB shell settings get system screen_off_timeout 2>/dev/null | tr -d '\r')
[ "${SOT:-0}" -ge 600000 ] 2>/dev/null || { MSYS_NO_PATHCONV=1 $ADB shell settings put system screen_off_timeout 600000 >/dev/null 2>&1; log "息屏超时原为 ${SOT:-未知}，已立 600000ms"; }
MSYS_NO_PATHCONV=1 $ADB logcat -G 16M >/dev/null 2>&1
# S0b 排他前置（r4/r5 轮血账换来）：那次"已终止"的通知骗人——TaskStop 只杀了外层 wrapper，
# 脚本进程活在设备上一边跑 r4 一边又放了 r5，双驱动互污，两轮的 rows=0/空读数全成了不可归因废数。
# 机制锁=同族锁文件：pid 活着=别人在跑，大声退（r4 的孤儿现场被本锁当场抓住就是它存在的理由）。
# 活体进程扫描故意不做：调用链上每层 wrapper 的命令行都含脚本名，扫谁都是自锁——纪律补位：
# 上一跑的结束通知（含 RC 行）未落地，禁止放下一跑。
LOCK=/tmp/s4-fullflow.lock
if [ -f "$LOCK" ] && kill -0 "$(cat "$LOCK" 2>/dev/null)" 2>/dev/null; then
    bad "S0b :: 另一个 s4-fullflow（pid $(cat "$LOCK")）还活着——禁双驱动，本跑终止"; exit 2
fi
echo $$ > "$LOCK"; trap 'rm -f "$LOCK"' EXIT
pass "S0b 设备独占前置（锁已立 pid=$$）"

ts "===== S1 起应用 + 授权在位 ====="
MSYS_NO_PATHCONV=1 $ADB shell am start -n com.anytouch.app/.MainActivity >/dev/null 2>&1
sleep 3
PID=$(MSYS_NO_PATHCONV=1 $ADB shell pidof com.anytouch.app 2>/dev/null | tr -d '\r')
log "pid=${PID:-未读到}（测试通道禁 force-stop，常驻进程真冷启动做不了=设备事实 F-3，此处不冒称冷启动）"
if wait_service_bound; then pass "S1b 无障碍服务在位（dumpsys accessibility 含自家包名）"; else bad "S1b :: 20s 内无障碍未绑定，后续全在假前置上跑，终止"; exit 2; fi
# 前置动作：收残留 IME。r1/r2 轮实证——老板点「清除」召出的键盘会数小时挂在屏上（mInputShown=true），
# 其窗盖住凭据框，注入脚本"dump 取点→tap"落在键盘上=焦点检查必败（假红族，产品无罪）。
# 设备事实（r2）：MIUI 的 iFlytek 输入法**不吃 keyevent 111(ESC)**，r3 实证 BACK 可收且不吃 activity。
ime_shown() { MSYS_NO_PATHCONV=1 $ADB shell dumpsys input_method 2>/dev/null | grep -aq "mInputShown=true"; }
MSYS_NO_PATHCONV=1 $ADB shell input keyevent 111 >/dev/null 2>&1; sleep 1
ime_shown && { MSYS_NO_PATHCONV=1 $ADB shell input keyevent 4 >/dev/null 2>&1; sleep 1; }
if ime_shown; then
    bad "S1c :: ESC+BACK 双通道后 IME 仍显示——键盘在场则 S2 注入必假红，终止（不拿已知假红继续记账）"; exit 2
else
    pass "S1c 前置键盘已收起（mInputShown=false，注入通道干净）"
fi

ts "===== S2 代填 Key（S3-R5 通道；脚本不读不印 Key） ====="
# 凭据已在机时直接复用：注入脚本对"框内已有不明旧值"fail-closed 是它自己的洁癖判据，
# 但本流程里旧值=上一轮本通道自己注入并 SAVED 的那把（r3 实证），重打反而制造假红。
# 复用的判据不缩水：屏上凭据行在场 + 后续 S3 真编译若 401/403 会当场把这把钥匙判死。
harvest_page 10
TAIL_PRE=$(text_of byok_key_tail)
# r5 教训：复用与否的决策必须留痕——那次没复用成功却无声落进注入分支，归因只能靠猜。
log "S2 决策读数：到底=$SWEEP_OK 缝=$SWEEP_GAP 尾行=[$TAIL_PRE]"
if [ "${SWEEP_OK}" = "1" ] && printf '%s' "$TAIL_PRE" | grep -q '\*\*\*'; then
    pass "S2a 凭据已在机，复用不重打 :: [$TAIL_PRE]（来历=本流程前一轮 S3-R5 注入）"
else
    INJ_OUT=$(ANDROID_SERIAL="$SER" bash scripts/byok-credential-inject.sh --key-file "$KEY_FILE" 2>&1)
    inj_rc=$?
    printf '%s\n' "$INJ_OUT" | sed -E 's/[A-Za-z0-9/._:-]{24,}/«REDACTED»/g' | tail -8
    TAIL_WANT=$(printf '%s\n' "$INJ_OUT" | grep -ao '\*\*\*[A-Za-z0-9]\{1,4\}' | tail -1)
    if [ "$inj_rc" = "0" ] && printf '%s' "$INJ_OUT" | grep -aq 'RESULT=SAVED'; then
        pass "S2a 代填保存成功（屏上回读尾4=$TAIL_WANT）"
    else
        bad "S2a :: 代填没存上（RESULT 非 SAVED），S3 起全部无从谈起，终止"; exit 2
    fi
    harvest_page 10
fi
TAIL_GOT=$(text_of byok_key_tail)
if [ "${SWEEP_OK}" = "1" ] && printf '%s' "$TAIL_GOT" | grep -q "\*\*\*"; then
    pass "S2b 自家面板凭据行在场 :: [$TAIL_GOT]"
else
    bad "S2b :: 凭据状态行读不到（到底=$SWEEP_OK 缝=$SWEEP_GAP）——刚存上却屏上不显=写成功≠存上了那一族的反面"
fi
KEY_SHAPE=$(grep -ao '[A-Za-z0-9]\{24,\}' "$PAGE_TMP" | head -1 || true)
[ -z "$KEY_SHAPE" ] && pass "S2c 整页扫描无 Key 形态字面（真凭据在场）" \
    || bad "S2c :: 凭据行附近出现 Key 形态字面 len=${#KEY_SHAPE}（最严重缺陷，当场停）"

ts "===== S3 真 HTTPS 编译（第 1 次真请求，上限 $REQ_CAP） ====="
MSYS_NO_PATHCONV=1 $ADB shell am start -n com.android.settings/.Settings >/dev/null 2>&1
sleep 8
reset_logs
REQ_USED=$((REQ_USED + 1))
# 意图与 [K5] 轮逐字同料：换措辞=换模型样本，混进"链路通不通"的判据里会两件事分不开。
inject "--es ai_intent 'open the page where bluetooth can be turned on, one tap only' --es ctx_enabled on --ez ai_compile true" false
E3=$(wait_line "S3SMOKE compile ok steps=\|S3SMOKE compile refused" 150)
if printf '%s' "$E3" | grep -q 'compile refused'; then
    bad "S3a :: 真 HTTPS 编译被拒 :: $E3"; exit 2
elif [ -z "$E3" ]; then
    bad "S3a :: 150s 无 compile 行（传输层上限 120s，超时即产品拒，不该静默）"; exit 2
else
    pass "S3a 真 HTTPS 编译成功 :: $E3"
fi
STEPS=$(printf '%s' "$E3" | sed -n 's/.*steps=\([0-9]*\).*/\1/p')
ROWS=$(printf '%s' "$E3" | sed -n 's/.*rows=\([0-9]*\).*/\1/p')
[ "${STEPS:-0}" -ge 1 ] && pass "S3b 产物步数 steps=$STEPS" || bad "S3b :: steps=$STEPS"
[ "${ROWS:-0}" -ge 1 ] && pass "S3c 上行词表 rows=$ROWS（Settings 为活动窗时采的）" || bad "S3c :: rows=$ROWS"

ts "===== S4 任务框建议 == 落账口账（逐字，设备半边） ====="
TASK_LINE=$(logs | grep -a "S3SMOKE-TASK " | tail -1 || true)
LEDGER_JSON=${TASK_LINE#*S3SMOKE-TASK }
[ -n "$LEDGER_JSON" ] || { bad "S4a :: 落账口没留账（S3SMOKE-TASK 行缺失）"; exit 2; }
MSYS_NO_PATHCONV=1 $ADB shell am start -n com.anytouch.app/.MainActivity >/dev/null 2>&1
sleep 2
harvest_page 10
BOX_RAW=$(text_of task_input)
BOX=$(printf '%s' "$BOX_RAW" | xml_unesc)
if [ -z "$BOX" ]; then
    bad "S4a :: 任务框读数为空（到底=$SWEEP_OK 缝=$SWEEP_GAP）——先判读数可信度再判产品"
elif [ "$BOX" = "$LEDGER_JSON" ]; then
    pass "S4a 建议与账逐字相等（len=${#BOX}）:: ${BOX:0:80}…"
else
    bad "S4a :: 逐字不等 :: 框=${BOX:0:80}… 账=${LEDGER_JSON:0:80}…"
fi

ts "===== S5 断网（正向对照先行，量不到就大声 SKIP 不冒判） ====="
PROBE_OK=0
if ping_ok; then PROBE_OK=1; log "断网前 ping 通（探针自证）"; else
    skip "S5a :: 断网前 ping 就不通——探针不可信，S5/S6 的离线结论全部降级为纯日志证据（宁缺勿冒）"
fi
NET_OFF=0
if [ "$PROBE_OK" = "1" ]; then
    MSYS_NO_PATHCONV=1 $ADB shell cmd connectivity airplane-mode enable >/dev/null 2>&1; sleep 4
    if ! ping_ok; then NET_OFF=1; else
        MSYS_NO_PATHCONV=1 $ADB shell svc wifi disable >/dev/null 2>&1
        MSYS_NO_PATHCONV=1 $ADB shell svc data disable >/dev/null 2>&1; sleep 4
        ping_ok || NET_OFF=1
    fi
fi
if [ "$NET_OFF" = "1" ]; then pass "S5b 设备侧已离线（断网后 ping 失败，双向自证）"
elif [ "$PROBE_OK" = "1" ]; then skip "S5b :: 测试通道收不走网络（MIUI 政策），S6 的离线半边降级为纯日志证据"
else skip "S5b :: 探针不可信，未尝试断网"; fi

ts "===== S6 离线执行任务（零字节出网的行为证据） ====="
# 整屏 md5 不能当"变了"的判据：dump 含状态栏，时钟一分钟一跳就把 md5 洗一次（假绿近亲）。
# 只比**目标 App 窗内**的 text/content-desc 集合：包名过滤掉 systemui/自家，变的才作数。
win_tokens() { tr '<' '\n' | grep -a 'package="com.android.settings"' | grep -ao 'text="[^"]*"\|content-desc="[^"]*"' | sort -u; }
if printf '%s' "$LEDGER_JSON" | grep -q "'"; then
    skip "S6 整段 :: 产物含单引号，adb 命令行通道会截断（E5h 同款通道限制，不判产品）——执行进屏改由人手点「执行任务」验"
else
MSYS_NO_PATHCONV=1 $ADB shell am start -n com.android.settings/.Settings >/dev/null 2>&1
sleep 8
MSYS_NO_PATHCONV=1 $ADB shell uiautomator dump /sdcard/.s4before.xml >/dev/null 2>&1
BEFORE=$(MSYS_NO_PATHCONV=1 $ADB shell cat /sdcard/.s4before.xml 2>/dev/null | tr -d '\r'); MSYS_NO_PATHCONV=1 $ADB shell rm /sdcard/.s4before.xml >/dev/null 2>&1
BEFORE_TOK=$(printf '%s' "$BEFORE" | win_tokens)
reset_logs
inject "--es task_json '$LEDGER_JSON'" false
V6=$(wait_line "S1SMOKE ok=" 90)
if [ -z "$V6" ]; then
    bad "S6a :: 90s 没有执行回执行（离线也不该静默）:: refuse=[$(logs | grep -a 'submit refused' | tail -1)]"
else
    OKN=$(printf '%s' "$V6" | sed -n 's/.*ok=\([0-9]*\).*/\1/p')
    TOT=$(printf '%s' "$V6" | sed -n 's/.*total=\([0-9]*\).*/\1/p')
    pass "S6a $( [ "$NET_OFF" = "1" ] && echo '离线状态下' )执行器走完并按框计数 :: $V6"
    [ "${TOT:-0}" = "${STEPS:-0}" ] && pass "S6b 步数与编译产物一致 total=$TOT==steps=$STEPS" \
        || bad "S6b :: total=$TOT ≠ steps=$STEPS（按框走那条判据断了）"
    [ "${OKN:-0}" -ge 1 ] && pass "S6c 至少一步真成功 ok=$OKN/$TOT" \
        || bad "S6c :: ok=$OKN/$TOT，一步没成——'执行进了目标 App 屏'不成立"
fi
# 执行结束后才准 dump（执行中 dump=挤掉自家服务并取消 runTask，设备实证）。
MSYS_NO_PATHCONV=1 $ADB shell uiautomator dump /sdcard/.s4after.xml >/dev/null 2>&1
AFTER=$(MSYS_NO_PATHCONV=1 $ADB shell cat /sdcard/.s4after.xml 2>/dev/null | tr -d '\r'); MSYS_NO_PATHCONV=1 $ADB shell rm /sdcard/.s4after.xml >/dev/null 2>&1
AFTER_TOK=$(printf '%s' "$AFTER" | win_tokens)
if [ -z "$AFTER" ]; then
    bad "S6d :: 执行后 dump 为空（读数失败，不判产品）"
elif [ "$AFTER_TOK" = "$BEFORE_TOK" ] || [ -z "$BEFORE_TOK" ]; then
    bad "S6d :: 目标 App 窗内词表读数执行前后逐字相同（before 读数可信=$([ -n "$BEFORE_TOK" ] && echo 1 || echo 0)）——ok 计数与屏上现实矛盾，按缺陷记"
elif printf '%s' "$AFTER" | grep -aq 'resource-id="task_input"'; then
    bad "S6d :: 执行后前台是自家面板——读数窗口不干净，S6d 作废复查"
else
    pass "S6d 目标 App 窗内屏态真变（Settings 节点词表前后集合有差，且屏上无自家节点=变的不是自家窗）"
fi
[ "$NET_OFF" = "1" ] && { ping_ok && bad "S6e :: 执行窗口结束后网络又通了——本窗'离线'结论作废复查" || pass "S6e 执行全程网络探针保持失败态（离线未破）"; }
LOG_HTTP=$(logs | grep -ac "S3SMOKE compile" || true)
[ "${LOG_HTTP:-0}" = "0" ] && pass "S6f 执行窗口日志内零次编译类出网行（与离线态合证）" || bad "S6f :: 执行期出现 $LOG_HTTP 条编译行——执行路径混进了网络"
fi

ts "===== S7 复网（双向自证的另外半边） ====="
MSYS_NO_PATHCONV=1 $ADB shell cmd connectivity airplane-mode disable >/dev/null 2>&1
MSYS_NO_PATHCONV=1 $ADB shell svc wifi enable >/dev/null 2>&1
MSYS_NO_PATHCONV=1 $ADB shell svc data enable >/dev/null 2>&1
i=0; until ping_ok || [ "$i" -ge 30 ]; do sleep 1; i=$((i + 1)); done
ping_ok && pass "S7a 复网成功（ping 恢复）" || bad "S7a :: 30s 未复网——把机器留在断网态收工不可接受"

ts "===== S8 收尾：keyed byok-smoke 复跑（判据 §3-4，含其 E5/E8 共 2 次真请求） ====="
# E9 跨进程格必须给 E9_APK：缺省退化成 am kill（常驻服务型 App 收不走，F-3 在册），复跑强度静默缩水。
# 本机盘上 apk 与在机 md5 已三方死证同值（证据 §6-4），同字节 install -r 只起"真收走进程"一个作用。
E9_APK="${E9_APK:-app/build/outputs/apk/debug/app-debug.apk}"
[ -f "$E9_APK" ] || { bad "S8 :: E9_APK 文件不在（$E9_APK）——通道前置缺失，记账前先停，不烧额度跑个缩水轮"; exit 1; }
REQ_USED=$((REQ_USED + 2))
[ "$REQ_USED" -le "$REQ_CAP" ] || { bad "S8 :: 真请求将触顶 $REQ_CAP/$REQ_USED，按军令停"; exit 1; }
SMOKE_TMP=$(mktemp)
ANDROID_SERIAL="$SER" E9_APK="$E9_APK" bash scripts/byok-smoke.sh >"$SMOKE_TMP" 2>&1; SMOKE_RC=$?
tail -25 "$SMOKE_TMP"
if [ "$SMOKE_RC" = "0" ]; then pass "S8a 复跑 RC=0（全流程跑完机器状态仍过全套回归）"; else
    bad "S8a :: byok-smoke 复跑 RC=$SMOKE_RC，S4-a 把它弄脏了或撞出新缺陷（上方尾 25 行）"
fi
rm -f "$SMOKE_TMP"

ts "===== 汇总 ====="
ts "断言 $ran 条 / 跳过 $skipped 条 / $( [ "$fail" = 0 ] && echo '无失败项' || echo '有失败项' )；真请求 $REQ_USED/$REQ_CAP 次；S4SMOKE_RC=$fail"
exit "$fail"
