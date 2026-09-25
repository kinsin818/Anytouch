#!/usr/bin/env bash
# Anytouch 设备回归冒烟（模拟器口径）：把 evidence/S2 设备补记里逐条手打的命令固化成机器可重跑的断言。
# 用法：bash scripts/device-smoke.sh   （需恰好 1 台 adb 设备、已装 debug APK、无障碍服务已绑）
# 退出码：0=全部通过；1=有失败；2=前置不满足。产品路径零坐标注入、零网络，纯 adb + logcat 回执断言；
# 例外（均为测试通道动作，脚本内留痕）：C6/C7 用 `input tap` 点悬浮停止球（模拟用户手指，非产品定位）；
# C8/C9 真实切换无障碍服务开关（settings put）复现"执行中被系统解绑"与"服务不在场仍想开录"，case 尾重绑恢复；
# C9/C10 = 录制门禁双路径（军令红线 E：无障碍不可用 / 执行中球收起，两条通道都不得开录）；
# C3 前置的 `input swipe`（滚到目标框进无障碍树）也是测试通道动作——只摆环境，不参与产品定位。
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

# 面板真弹出与否只认 Overlay 这条日志（回执只说"最终没被确认"，不说"曾经问过用户"）。
# run_case / 各 case 自己在链首 logcat -c，所以这里读到的必然是本格的。
panel_count() { # $1=rule 串（如 PASSWORD:password）；返回该 rule 的面板弹出次数
    MSYS_NO_PATHCONV=1 $ADB logcat -d -s AnytouchOverlay:* 2>/dev/null |
        grep -ac "second-confirm panel shown: rule=$1" || true
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
# S5-f 判据 10：C3 那一格往 repeat_count 里打字，而该框在未激活态 enabled=false（付费墙），
# 不激活就是"读不到→判红"的假红；前置走注入通道输合法码（同一条校验路径，无旁路）。
AP=$(bash "$(dirname "$0")/activation-preflight.sh" 2>&1) || { echo "前置失败：激活前置未过—— $AP"; exit 2; }
echo "前置 :: $AP"

SAFE='"safety":{"viewport_ok":true,"click_enabled":true}'

# 本地化/几何参数：默认值=模拟器英文系统口径；真机（如中文 MIUI）经环境变量覆盖，见 §T3 摸底档
TXT_SEARCH="${TXT_SEARCH:-Search settings}"
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

# 球心现读现点（09-25 定，两次改错才找到的根因，逐字留痕）：
# ① 执行期自家包同时挂着两个 TYPE_ACCESSIBILITY_OVERLAY 窗：确认面板与停止球。面板块在
#    `dumpsys window windows` 里排在小球前面，实测（面板挂起态）面板 frame=[120,912][960,1487]、
#    小球 frame=[957,1207][1056,1321]。
# ② 前一版取框心的 awk 用 `/^[ ]+Window\{/` 复位块标志——这条正则**永不匹配**（真实行是
#    `  Window #N Window{...}`，"Window" 与 "{" 之间夹着 #序号），于是块标志粘连：面板块之后
#    所有 Frames 行都被当成"自家窗"，第一条被选中的正是面板 → 点进面板正中 (540,1199)，
#    C7 恒红且红得莫名其妙（回执为空＝面板一直没被理，15s 后才默认拒）。
#    这次改为按"每个 Window 块"重算归属，并按宽高双阈值(≤200)排除面板/Activity 窗。
# ③ 写死的 BALL_TAP 也不能留：小球 frame 实测至少两种量级（无面板 [901,1213][1068,1314]、
#    有面板 [957,1207][1056,1321]，此前还采到过 y=765 的一枚），"这一刻它在哪"只能现读。
#    读不到窗才退回 BALL_TAP。产品侧观察（急停球停靠位不固定＝用户预判位置会落空）单独上报，
#    不改代码、不洗回——军令：急停/门禁本体零改动。
ball_xy() { # 打印 "<x> <y>"：自家小球窗（宽、高均 ≤200，排除面板/Activity 窗）的当下框心
    local f
    f=$(MSYS_NO_PATHCONV=1 $ADB shell dumpsys window windows 2>/dev/null | tr -d '\r' |
        awk '
            /^  Window #/ { own = ($0 ~ /u0 com\.anytouch\.app\}:/); next }
            own && /Frames:/ {
                s = $0; sub(/.*frame=\[/, "", s); sub(/\]\[/, ",", s); sub(/\].*/, "", s); gsub(/[^0-9,]/, "", s)
                n = split(s, a, ",")
                if (n == 4 && a[3] > a[1] && a[4] > a[2] && a[3] - a[1] <= 200 && a[4] - a[2] <= 200) {
                    printf "%d %d", (a[1] + a[3]) / 2, (a[2] + a[4]) / 2; exit
                }
            }')
    if [ -n "$f" ]; then printf '%s' "$f"; else printf '%s' "$BALL_TAP"; fi
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
    # 超时=前置没立住，必须响亮地失败。原来这里 `return 0`：一条永不 FAIL 的门禁就是假门禁，
    # 它把"环境没摆好"洗成"产品跑不动"，红项落在 C1 头上、真因埋在列表没排布完/ROM 词表不匹配里。
    return 1
}

# ---------- C1 混合链：Settings 首页 滚动+点击 ----------
stop_settings_ui
# 设备实证（K80/HyperOS）：隐式 ACTION_SETTINGS 偶发被 com.milink.service  Connectivity 页劫持；显式组件名落回自家首页
MSYS_NO_PATHCONV=1 $ADB shell am start -n com.android.settings/.Settings >/dev/null 2>&1
# 设备实证（AVD 三档矩阵收口轮）：宿主并跑 3 台模拟器时 sleep 2/5 不够——首页容器已存在但
# 列表未排布完，scroll/click 明示拒绝出 perform_failed 假红；固定沉降 + scrollable 就绪轮询。
sleep 8
if ! wait_home_scrollable; then
    # 不拿这条未立住的前置去判 C1 的产品成败：真机上 RID_HOME（AOSP 口径）压根不叫这名字（K40 实证：
    # MIUI 首页容器是 nestedheaderlayout/scroll_headers，见 evidence/S2/t3-k40-first-contact.md 真机轮归因）。
    bad "C1 前置未立：15s 内没读到 scrollable=\"$RID_HOME\" —— 本条红归因于环境/ROM 词表，不归产品"
fi
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
# 09-25 五轮才定下来的一格。六条设备事实逐条进证据 evidence/S5/s5d-repeat-loop-device-run.md：
#  ① 高危探针读的是**目标节点当场的 text**（NodeTaskRunner.kt:215-221 用 hit.node.text 造探针）。
#     这格原先打 task_input，而任务框会留着任何一批装载过的模板 JSON → C3 被那张 JSON 里的词判高危，
#     没有人在场答面板 → 15s 默认拒。产品是对的（fail-closed），红的是测试通道欠前置。
#  ② harness 不许自带第二份高危词表：我照 HighRiskRule.kt:47-48 抄的那份判 discord 模板"零命中"，
#     产品当场按 SEND:send 拒了它（词表还有 SEND/PAY 整类）。判据只有一份，harness 不自造口径。
#  ③ "把任务框清成真空"实测走不通：task_input 的落点在无障碍 dump 里不可信（按其 bounds 落指，
#     字符进了 repeat_count；连发 DEL 字数纹丝不动），而收键盘要用的 BACK 一收就连带退出自家窗。
#  ④ 执行器 type_text 是**整段替换**不是追加，且走无障碍 ACTION_SET_TEXT——不需要焦点、不需要键盘
#     （repeat_count 实测 "1"→"X"→空→"1"，三条回执逐字 ok=1 total=1 stopped=false）。所以靶换成
#     一枚**屏上文本天然干净**的自家 Compose 框：本格测的是"执行器能否写进自家 Compose 目标"本身。
#  ⑤ 进格前把原值逐字读出来、出格时写回并**在屏上复核**：本格对设备状态零残留，不给后面任何一格
#     埋雷（这一批就是被上一批留在框里的雷炸的）。复位步恒下发（含"原值本就是空"这一路：实测
#     input:"" 是能落地的整段替换，回执 ok=2/2，屏上读回空），所以本格的期望恒为 ok=2 total=2。
#  ⑥ "节点不在树里" ≠ "节点文本是空的"：折叠线以下的 Compose 节点未合成就不进无障碍树，
#     前置必须先滚到它进树；把前者当后者读就是假绿。
TI_DUMP=.smoke-tmp/c3-target.xml
mkdir -p .smoke-tmp
C3_TID=repeat_count        # 自家 Compose 输入框（testTag 裸 id）；文本天然是一枚短数字
C3_FALLBACK=1              # 原值不是可安全回写的短 token 时，按产品默认值复位（RepeatPolicy 默认 1）
wait_own_node() { # 前置：带前台 + 滚到 $C3_TID 真进无障碍树（事实⑥：不在树里绝不读成"文本是空的"）
    MSYS_NO_PATHCONV=1 $ADB shell am start -n com.anytouch.app/.MainActivity >/dev/null 2>&1
    sleep 2
    local sz w h i=0 prev="" now
    sz=$(MSYS_NO_PATHCONV=1 $ADB shell wm size 2>/dev/null | tr -d '\r' | grep -o '[0-9]*x[0-9]*' | tail -1)
    w=${sz%x*}; h=${sz#*x}
    [ -n "${w:-}" ] && [ -n "${h:-}" ] || { w=500; h=1000; }
    while [ "$i" -lt 24 ]; do
        if MSYS_NO_PATHCONV=1 $ADB shell uiautomator dump /sdcard/.smoke_ti.xml >/dev/null 2>&1 &&
           MSYS_NO_PATHCONV=1 $ADB shell cat /sdcard/.smoke_ti.xml 2>/dev/null | grep -qa "resource-id=\"$C3_TID\""; then
            MSYS_NO_PATHCONV=1 $ADB shell cat /sdcard/.smoke_ti.xml 2>/dev/null | tr -d '\r' > "$TI_DUMP"
            MSYS_NO_PATHCONV=1 $ADB shell rm /sdcard/.smoke_ti.xml >/dev/null 2>&1
            [ "$i" -gt 0 ] && echo "      | C3 前置：滚第 $i 格后 $C3_TID 进树（框里留着长 JSON 时页面更高，8 格不够）"
            return 0
        fi
        MSYS_NO_PATHCONV=1 $ADB shell cat /sdcard/.smoke_ti.xml 2>/dev/null | tr -d '\r' > "$TI_DUMP"
        MSYS_NO_PATHCONV=1 $ADB shell rm /sdcard/.smoke_ti.xml >/dev/null 2>&1
        now=$(grep -o 'resource-id="[^"]*"' "$TI_DUMP" | sort | tr '\n' ' ')   # 节点集不再变化=已到页底，别再空滚
        if [ -n "$prev" ] && [ "$now" = "$prev" ]; then
            cp "$TI_DUMP" .smoke-tmp/c3-miss.xml 2>/dev/null
            echo "      | C3 前置：滚到页底（第 $((i + 1)) 格节点集与上一格相同）仍无 $C3_TID，末树存 .smoke-tmp/c3-miss.xml"
            return 1
        fi
        prev="$now"
        MSYS_NO_PATHCONV=1 $ADB shell input swipe "$((w / 2))" "$((h * 70 / 100))" "$((w / 2))" "$((h * 30 / 100))" 400 >/dev/null 2>&1
        sleep 1
        i=$((i + 1))
    done
    cp "$TI_DUMP" .smoke-tmp/c3-miss.xml 2>/dev/null
    echo "      | C3 前置：滚满 24 格仍无 $C3_TID，末树存 .smoke-tmp/c3-miss.xml（焦点：$(MSYS_NO_PATHCONV=1 $ADB shell dumpsys window 2>/dev/null | tr -d '\r' | grep -o 'mCurrentFocus=Window{[^}]*' | head -1)）"
    return 1
}
nc_text() { # $1=resource-id；从 TI_DUMP 取该节点 text（0=节点在树里，含"文本为空"；1=压根不在树里）
    local n v
    [ -f "$TI_DUMP" ] || return 1
    n=$(grep -o "<node[^>]*resource-id=\"$1\"[^>]*>" "$TI_DUMP" | head -1)
    [ -n "$n" ] || return 1
    v=$(printf '%s' "$n" | sed -n "s/.*text='\([^']*\)'.*/\1/p")
    [ -n "$v" ] || v=$(printf '%s' "$n" | sed -n 's/.*text="\([^"]*\)".*/\1/p')
    printf '%s' "$v"
}
C3_ORIG=""; C3_BACK=""; C3_STEPS=""
if wait_own_node; then
    C3_ORIG=$(nc_text "$C3_TID")
    case "$C3_ORIG" in
        *[!A-Za-z0-9_.-]*) C3_BACK="$C3_FALLBACK"
            echo "      | C3 前置：$C3_TID 屏上原值不是可安全回写的短 token（[${C3_ORIG:0:24}…]），复位按默认值 [$C3_FALLBACK]" ;;
        *)  C3_BACK="$C3_ORIG"
            echo "      | C3 前置：$C3_TID 已在无障碍树里，屏上原值 [$C3_ORIG]（出格按此复位）" ;;
    esac
    C3_STEPS="{\"action_id\":\"t1\",\"type\":\"type_text\",\"source\":\"node\",\"value\":{\"resource_id\":\"$C3_TID\",\"input\":\"smoke c3 $(date +%s)\"},$SAFE}"
    C3_STEPS="$C3_STEPS,{\"action_id\":\"t2\",\"type\":\"type_text\",\"source\":\"node\",\"value\":{\"resource_id\":\"$C3_TID\",\"input\":\"$C3_BACK\"},$SAFE}"
else
    echo "      | C3 前置未成立：滚到页底仍读不到 $C3_TID（下面这条按自家回执出结论，不猜）"
fi
if [ -n "$C3_STEPS" ]; then
    C3_EXPECT="ok=2 total=2 stopped=false"
    run_case "C3 type_text Compose自目标" "$C3_EXPECT" \
        "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '[$C3_STEPS]' --ez keep_fg true"
    # 出格复核：屏上必须回到原值（回执只证明执行器说它写成了，屏上读数才证明真写成了）
    # 复核前先走同一个 wait_own_node：跑完一轮面板/焦点变化后框可能又被推出折叠线（事实⑥），
    # 直接 dump 会把"看不见"读成"没复位"——那是假红，不是残留。
    C3_NOW="<未读到>"
    if wait_own_node; then C3_NOW=$(nc_text "$C3_TID"); else C3_NOW="<滚回页面也没读到节点>"; fi
    if [ "$C3_NOW" = "$C3_BACK" ]; then
        pass "C3b 出格复位复核：屏上 $C3_TID = [$C3_NOW]（本格零残留出格）"
    else
        bad "C3b 出格复位复核 :: 期望屏上 [$C3_BACK]，实际 [$C3_NOW]——本格对设备状态留了残留，必须查"
    fi
fi

# ---------- C4 fail-closed 负例：不存在的节点必须 NODE_NOT_FOUND 停机，不得假绿 ----------
run_case "C4 负例 NODE_NOT_FOUND" "stop=\"NODE_NOT_FOUND\"" \
    "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '[{\"action_id\":\"x1\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"__no_such_node_smoke__\"},$SAFE}]'" 60

# ---------- C5 高危二次确认：无人点击=15s 超时默认拒绝（面板必须真实弹出，见 evidence/S2/stage-highrisk-confirm-device.md） ----------
# 输入通道洁净断言（09-22 幽灵触点事件后加装）：C5 全程合法触点预算=0，
# getevent 抓到任何触摸即判"外部污染"——防宿主鼠标/其他窗口把安全负例点成假绿。
# 高危靶口径（09-25 换）：旧链第 3 步点的是 Settings 搜索结果里的 "Passwords & accounts" 一行，
# 而这台 AVD 的搜索索引从 04:44 那轮起对任何查询都只回 "No results"（复现与恢复尝试见
# evidence/S5/s5d-repeat-loop-device-run.md）。索引活着时那行文本是 ROM 自带的，死了就再也拿不到——
# 判据本身不需要 ROM 送词：第 2 步已经把 "password" 整段替换进了搜索框，第 3 步点这枚**外部 App 节点**，
# 门禁读的仍是它当场的 text（NodeTaskRunner.kt:215-221），命中 PASSWORD 词表 → 面板 → 15s 无人应答默认拒。
# C5/C7 共用这一份链（两格曾各自抄一遍，抄到 C7 在索引失效后静默退化成"定位轮询期点球"=C6 的活儿，
# 还记了绿——单一真源，杜绝再退化）。
RISK_CHAIN="[{\"action_id\":\"s1\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"$TXT_SEARCH\"},$SAFE},{\"action_id\":\"s2\",\"type\":\"type_text\",\"source\":\"node\",\"value\":{\"text\":\"$TXT_SEARCH\",\"input\":\"password\"},$SAFE},{\"action_id\":\"s3\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"password\"},$SAFE}]"
stop_settings_ui
MSYS_NO_PATHCONV=1 $ADB shell am start -n com.android.settings/.Settings >/dev/null 2>&1
sleep 8
GEV=$(mktemp)
MSYS_NO_PATHCONV=1 $ADB shell "getevent -lt" > "$GEV" 2>&1 &
GE_PID=$!
sleep 1
run_case "C5 高危超时默认拒绝" "stop=\"PASSWORD:password\"" \
    "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '$RISK_CHAIN'" 60
kill $GE_PID 2>/dev/null
# 注：`|| true` 是必需的——grep -c 零命中时退出码为 1，且其 0 已先打到 stdout，不能重复 echo。
touches=$(grep -c "ABS_MT_TRACKING_ID   00000000" "$GEV" || true)
if [ "${touches:-0}" -eq 0 ]; then
    pass "C5x 输入通道洁净（零外部触点）"
else
    bad "C5x 输入通道洁净 :: 抓到 $touches 次外部触摸——C5 结果不可信（查谁的手/窗口在模拟器上），trap 存 $GEV"
fi
p5=$(panel_count "PASSWORD:password")
if [ "${p5:-0}" -ge 1 ]; then
    pass "C5p 确认面板真实弹出（rule=PASSWORD:password，共 $p5 次）"
else
    bad "C5p 确认面板真实弹出 :: Overlay 里 0 条 second-confirm panel shown——回执说被拒，但没问过用户；这条判据的另一半（面板可见）不成立"
fi

# ---------- C6 停止球即时响应：定位轮询期点球，回执须是 user_stop（非 NODE_NOT_FOUND）且 ≤5s 到达 ----------
# 回归锁（Task #14 设备雷）：KillSwitch 曾只在步首查询，长等待环里点球无感、末步点球丢归因。
# 落点=ball_xy 现读的球窗框心（END|CENTER_VERTICAL, x=24 只是停靠锚，y 随可用帧变），仅测试通道模拟手指，非产品定位。
stop_settings_ui
MSYS_NO_PATHCONV=1 $ADB shell am start -n com.android.settings/.Settings >/dev/null 2>&1
sleep 8
MSYS_NO_PATHCONV=1 $ADB logcat -c >/dev/null 2>&1
MSYS_NO_PATHCONV=1 $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '[{\"action_id\":\"k1\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"$TXT_CONNECTED\"},$SAFE},{\"action_id\":\"k2\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"__no_such_node_smoke__\"},$SAFE}]'" >/dev/null 2>&1
sleep 4  # 第一步落地、第二步进入 15s 定位轮询
BT6=$(ball_xy)
T0=$(date +%s)
MSYS_NO_PATHCONV=1 $ADB shell input tap $BT6 >/dev/null 2>&1
r6=$(wait_receipt C6 10)
dt=$(( $(date +%s) - T0 ))
if printf '%s' "$r6" | grep -qF 'stop="user_stop"' && [ "$dt" -le 5 ]; then
    pass "C6 停止球即时响应 :: 实测球心($BT6) ${dt}s :: $r6"
else
    bad "C6 停止球即时响应 :: 期望 [stop=\"user_stop\" 且 ≤5s]，实际 [${dt}s, $r6]"
fi

# ---------- C7 面板挂起期点球：确认面板(touch-modal 雷, FLAG_NOT_TOUCH_MODAL)不得吞掉停止球触点 ----------
# 链与 C5 同一份 RISK_CHAIN（超时默认拒绝）。本格判据有两半：**先要看到面板真的挂着**，再看点球 ≤5s 出
# user_stop（而非等满 15s 的 PASSWORD 归因）。09-25 的教训：只断言后半截时，索引失效让第 3 步根本没弹面板，
# 本格静默退化成"定位轮询期点球"（=C6 已经在测的东西）还记绿——前置不成立就不许记账绿。
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
MSYS_NO_PATHCONV=1 $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '$RISK_CHAIN'" >/dev/null 2>&1
P7=0; i7=0
while [ "$i7" -lt 14 ]; do                                 # 等面板真的挂起（弹出后 15s 才默认拒，窗口够）
    P7=$(panel_count "PASSWORD:password")
    [ "${P7:-0}" -ge 1 ] && break
    sleep 1
    i7=$((i7 + 1))
done
BT7=$(ball_xy)          # 面板挂起态下现读球心：这一刻软键盘多半起着，球被顶到可用帧中部
T0=$(date +%s)
MSYS_NO_PATHCONV=1 $ADB shell input tap $BT7 >/dev/null 2>&1   # 面板没弹也照点：把这条链收干净再红
r7=$(wait_receipt C7 10)
dt=$(( $(date +%s) - T0 ))
kill $GE7 2>/dev/null
if [ "${P7:-0}" -lt 1 ]; then
    bad "C7 前置未成立 :: ${i7}s 内没读到 second-confirm panel shown（rule=PASSWORD:password）——面板不在场，本格的 user_stop 只能是定位轮询期点球（那是 C6 的判据），不许记绿 :: 回执 [$r7]"
elif printf '%s' "$r7" | grep -qF 'stop="user_stop"' && [ "$dt" -le 5 ]; then
    pass "C7 面板挂起期点球即停 :: 面板在第 ${i7}s 挂起，实测球心($BT7) 点后 ${dt}s :: $r7"
else
    # 红项归因补一手：面板 15s 才默认拒，本格判据的 10s 窗口必然拿不到终回执——
    # 09-25 两连红都只报出 [12s, 空]，看不出"点空了"还是"点了面板空白"。追等只为把话说全，不改判据。
    r7b=$(wait_receipt C7-attr 14)
    bad "C7 面板挂起期点球即停 :: 期望 [stop=\"user_stop\" 且 ≤5s]，实际 [${dt}s, $r7]｜球心现读=[$BT7] 追等到 $(( $(date +%s) - T0 ))s 的终回执 [$r7b]（若终回执=PASSWORD:password 即球没被点中，先看 C7x 判外部触点）"
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

# ---------- C9 门禁路径一：无障碍未连接 → 开录必拒（军令 L2-① + 红线 E 双路径之①） ----------
# 只置灰按钮不算门禁：adb 注入通道绕过 UI 直调 RecorderStore.start，必须同判。
# 断言两件套：① 拒因留痕（gate=SERVICE_OFF 话术可见）；② 绝无 "record start target=" 成功痕——
# 缺服务时开录=录一段空会话再"成功"给用户看，正是第 8 雷（静默空录）形态。
MSYS_NO_PATHCONV=1 $ADB shell "settings put secure enabled_accessibility_services '${A11Y_NOUS:-null}'" >/dev/null 2>&1
# 解绑是异步的：等 onUnbind 把 serviceConnected 落为 false，否则可能读到"仍连着"的旧态打假绿
for _ in 1 2 3 4 5 6 7 8 9 10; do
    MSYS_NO_PATHCONV=1 $ADB shell dumpsys accessibility | grep -q "com.anytouch.app" || break
    sleep 1
done
MSYS_NO_PATHCONV=1 $ADB logcat -c >/dev/null 2>&1
MSYS_NO_PATHCONV=1 $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --es record_start com.android.settings" >/dev/null 2>&1
sleep 3
r9=$(MSYS_NO_PATHCONV=1 $ADB logcat -d -s AnytouchRun:W 2>/dev/null | grep -c 'record start refused gate=SERVICE_OFF' || true)
s9=$(MSYS_NO_PATHCONV=1 $ADB logcat -d -s AnytouchRun:I 2>/dev/null | grep -c 'S2SMOKE record start target=' || true)
# 恢复开跑前的原始清单（真机上有别家服务在绑，绝不硬覆盖）
MSYS_NO_PATHCONV=1 $ADB shell "settings put secure enabled_accessibility_services '${A11Y_ORIG:-com.anytouch.app/.service.AnytouchAccessibilityService}'" >/dev/null 2>&1
MSYS_NO_PATHCONV=1 $ADB shell settings put secure accessibility_enabled 1 >/dev/null 2>&1
sleep 4
if [ "${r9:-0}" -ge 1 ] && [ "${s9:-0}" -eq 0 ]; then
    pass "C9 服务未连时开录被拒（注入通道同判，无空会话）"
else
    bad "C9 服务未连时开录 :: 期望 [refused gate=SERVICE_OFF ≥1 且 成功痕=0]，实际 [拒=${r9:-0} 成功=${s9:-0}]"
fi

# ---------- C10 门禁路径二：执行中开录必拒（L1"挂不上球=拒绝开始"的设备可复现形态） ----------
# 执行期录制球被显式收起（看不见球=没有急停入口），且此刻开录会把执行器自己的手录成用户意图（假绿）。
# 球挂载事实由服务侧写入 RecorderStore.recordBallAttached，门禁在会话入口读取——
# 这里用"第二步 15s 定位轮询"窗口注入 record_start，真实复现"执行中"这一态。
MSYS_NO_PATHCONV=1 $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '[{\"action_id\":\"g1\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"$TXT_CONNECTED\"},$SAFE},{\"action_id\":\"g2\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"__no_such_node_smoke__\"},$SAFE}]'" >/dev/null 2>&1
sleep 4  # g1 已落地、g2 进入定位轮询 => AppState.running=true
MSYS_NO_PATHCONV=1 $ADB logcat -c >/dev/null 2>&1
MSYS_NO_PATHCONV=1 $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --es record_start com.android.settings" >/dev/null 2>&1
sleep 3
r10=$(MSYS_NO_PATHCONV=1 $ADB logcat -d -s AnytouchRun:W 2>/dev/null | grep -c 'record start refused gate=RUNNING' || true)
s10=$(MSYS_NO_PATHCONV=1 $ADB logcat -d -s AnytouchRun:I 2>/dev/null | grep -c 'S2SMOKE record start target=' || true)
if [ "${r10:-0}" -ge 1 ] && [ "${s10:-0}" -eq 0 ]; then
    pass "C10 执行中开录被拒（球收起态不得录制）"
else
    bad "C10 执行中开录 :: 期望 [refused gate=RUNNING ≥1 且 成功痕=0]，实际 [拒=${r10:-0} 成功=${s10:-0}]"
fi
# 收尾：等执行中的队列自然结束（g2 定位超时），别让下一条用例撞进 running 态
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16; do
    MSYS_NO_PATHCONV=1 $ADB logcat -d -s AnytouchRun:* 2>/dev/null | grep -q "S1SMOKE ok=" && break
    sleep 1
done

echo
if [ "$fail" -eq 0 ]; then echo "device-smoke: ALL PASS"; else echo "device-smoke: 有失败项"; fi
exit "$fail"
