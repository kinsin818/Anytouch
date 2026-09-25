#!/usr/bin/env bash
# Anytouch S5-a 预制模板设备冒烟（T 系列）——老板 S5-R6 靶机改判（AVD 原生 GMS 镜像，非国行 K40）
# + S5-R7 验收分档（相册=全链实证 100%；Gmail/Discord=装载面+结构判据，永不代人登录）。
#
# 用法：ANDROID_SERIAL=emulator-5554 bash scripts/s5-templates-smoke.sh      # 默认 ROUNDS=5
#       ROUNDS=3 ANDROID_SERIAL=emulator-5556 bash scripts/s5-templates-smoke.sh
# 前置：恰好 1 台目标靶机在线、装好本轮 debug APK（E9 语义：显式 E9_APK，缺省退化成"没装新码也照跑"=假绿，故硬性要求文件存在）、
#       无障碍已绑（首跑用 --bind-provision 自动绑：仅限模拟器靶机，见函数内注释）。
# 零模型零额度：模板装载与执行全链**不发任何网络请求**（R3-1 原判），本脚本不动 S4 批账。
#
# 洁净纪律（沿用 C/U 系列血账）：
# - 执行期禁 uiautomator dump（会挤掉自家服务打断在跑任务）；跑中只读 logcat 与 dumpsys window（只读，无扰）。
# - 每次 idle dump 后一律 wait_bound 复绑再走下一步。
# - 高危"确认执行"用测试通道定点 tap（S2 killdemo 同法：模拟用户手指，非产品定位）；
#   面板在场判据=mCurrentFocus 恰好是"无活动后缀"的自家窗（球不可聚焦、面板可聚焦，两窗口签名不同源不会混）。
# - force-stop 只用于第三方（photos）冷启复位；自家常驻禁 force-stop（雷 13）。
# - 设备独占锁（S0b 血账）：同机第二驱动当场终止。
set -u
# Git-Bash 会把 /sdcard/... 改写成 C:/Program Files/Git/...（本机实证两轮）：整脚本域关闭路径改写
export MSYS_NO_PATHCONV=1

ADB="adb"
[ -n "${ANDROID_SERIAL:-}" ] && ADB="adb -s $ANDROID_SERIAL"
ROUNDS="${ROUNDS:-5}"
E9_APK="${E9_APK:-app/build/outputs/apk/debug/app-debug.apk}"
RAW_DIR="${RAW_DIR:-evidence/S5/raw}"
BIND_PROVISION="${BIND_PROVISION:-}"

fail=0; passed=0
RED='\033[0;31m'; GRN='\033[0;32m'; NCT='\033[0m'
pass() { printf "${GRN}PASS${NCT} %s\n" "$1"; passed=$((passed + 1)); }
bad()  { printf "${RED}FAIL${NCT} %s\n" "$1"; fail=1; }
log()  { printf '      | %s\n' "$*"; }

# ---- 预检（先修后跑：任何一条不满足都当场停，不烧轮次赌运气） ----
[ -f "$E9_APK" ] || { bad "预检 :: E9_APK 不在盘（$E9_APK）——不冒称装了新码"; exit 2; }
DEVS=$(adb devices | grep -cw device)
if [ "$DEVS" -ne 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then bad "预检 :: adb 在线 $DEVS 台且未指定 ANDROID_SERIAL——禁双驱动"; exit 2; fi
case "$($ADB shell getprop ro.product.model 2>/dev/null | tr -d '\r')" in
    *Kiwi*|*K40*|kona*) bad "预检 :: 靶机疑似 K40 真机——S5-a 按 S5-R6 只跑 AVD 原生 GMS 镜像"; exit 2 ;;
esac
if [ -f /tmp/s5-templates.lock ] && kill -0 "$(cat /tmp/s5-templates.lock 2>/dev/null)" 2>/dev/null; then
    bad "S0b :: 另一个 s5-templates（pid $(cat /tmp/s5-templates.lock)）还活着——禁双驱动，本跑终止"; exit 2
fi
echo $$ > /tmp/s5-templates.lock; trap 'rm -f /tmp/s5-templates.lock' EXIT

wait_bound() {
    local i=0
    while [ "$i" -lt 25 ]; do
        $ADB shell dumpsys accessibility 2>/dev/null | grep -q "Bound services:{Service\[label=Anytouch" && return 0
        sleep 1; i=$((i + 1))
    done
    return 1
}
if [ -n "$BIND_PROVISION" ]; then
    $ADB shell settings put secure enabled_accessibility_services com.anytouch.app/.service.AnytouchAccessibilityService
    $ADB shell settings put secure accessibility_enabled 1
    $ADB shell monkey -p com.anytouch.app -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
fi
wait_bound || { bad "预检 :: 无障碍服务未绑定（先装 APK 并绑服务，或 BIND_PROVISION=1 仅模拟器靶机用）"; exit 2; }
SIZE=$($ADB shell wm size | tail -1 | tr -d '\r' | sed 's/.*: *//')
W=${SIZE%x*}; H=${SIZE#*x}
# "确认执行"按钮按 killdemo 标定坐标 (702,1294)@1080x2400 线性缩放到本靶机分辨率
# 兜底坐标：仅当"三拍都量不到钮"时试投用（正常轮一律按屏定位，见下方面板块）。
# 数值=v1.0.2 面板实测（截屏量得 729,1391 @1080x2400；v1.0.1 是 685,1282——文案一改钮就位移，
# 所以这组数只作试投，不作判据）。
BTN_CONFIRM_X=$(( W * 729 / 1080 )); BTN_CONFIRM_Y=$(( H * 1391 / 2400 ))
log "靶机 $($ADB shell getprop ro.build.version.sdk | tr -d '\r') 号 API · ${W}x${H} · 面板按钮 (${BTN_CONFIRM_X},${BTN_CONFIRM_Y}) · ROUNDS=$ROUNDS"
mkdir -p "$RAW_DIR"

T0=$(date +%s)
# 种子必须是真实尺寸：1x1 PNG 会被 Photos 网格直接滤掉（实测 run2~run5 NOHIT 真因——
# MediaStore 行在、date_modified 对、is_trashed=0，就是不上屏；64x64 秒上屏且吃 touch 的日期标签）
SEED_B64_A="iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAIAAAAlC+aJAAAAe0lEQVR4nO3PQQkAMAzAwMqpfz0TMxF7HINABFzm7H7dcEEDWtCAFjSgBQ1oQQNa0IAWNKAFDWhBA1rQgBY0oAUNaEEDWtCAFjSgBQ1oQQNa0IAWNKAFDWhBA1rQgBY0oAUNaEEDWtCAFjSgBQ1oQQNa0IAWNLAe8TJwAfOTwQ9ZJ72ZAAAAAElFTkSuQmCC"
SEED_B64_B="iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAIAAAAlC+aJAAAAe0lEQVR4nO3PUQkAIBTAQOMY5/XHMIbw4xAGC3Bbe87XLS5oQAsa0IIGtKABLWhACxrQgga0oAEtaEALGtCCBrSgAS1oQAsa0IIGtKABLWhACxrQgga0oAEtaEALGtCCBrSgAS1oQAsa0IIGtKABLWhACxrQggbGI14GLvsmQTwpY2QkAAAAAElFTkSuQmCC"
# 种子打**互不相同的未来日期** mtime：满屏老照片里唯一点名（网格序会因版本/状态翻脸——run2/run3 实锤
# "第一个 Photo taken"会打到老照片）。
# run10/11 实锤补一条：**日期岔开不够，像素也必须岔开**——同内容两张（哪怕日期不同）网格里仍折成一格，
# 第二张永远 NOHIT，点第一张移篓会把折叠格的第二张一起改名带走。手工对拍（红 64x64 + 蓝 64x64
# + 2030-01-01/2030-02-02）两格齐上屏后固化于此。
SEED_LABEL_A="Jan 1, 2030"
SEED_LABEL_B="Feb 2, 2030"
XMIG=/sdcard/.s5t.xml; XHOST=.smoke-tmp/s5t.xml
mkdir -p .smoke-tmp

dump_idle() {
    $ADB shell uiautomator dump /sdcard/.s5t.xml >/dev/null 2>&1
    $ADB shell cat $XMIG | tr -d '\r' > "$XHOST"
    wait_bound || { bad "dump 后服务未复绑 :: 下一条判据不可信"; return 1; }
}
tapn() { # $1=regex（NUL 结尾=精确等值），$2=秒（tap 后停留）
    local xy; xy=$(python scripts/tap_node.py "$XHOST" "$1") || { log "NOHIT $1"; return 1; }
    MSYS_NO_PATHCONV=1 $ADB shell input tap $xy; sleep "${2:-2}"; dump_idle
}
logs() { MSYS_NO_PATHCONV=1 $ADB logcat -d -s AnytouchRun:* 2>/dev/null; }

# 种子图：设备侧 base64 生成 + 媒体扫描（宿主 /tmp 对 Windows python 不可见——教训在册，不再用 push）
# 文件名必须**逐轮换新**（run10 实锤：同名文件被清过篓后，MediaProvider 不再为旧名重新建行——
# 文件在盘、扫描广播照发、库里就是没行，网格永远 NOHIT）；$1=轮号
seed_photos() {
    local tag="S5SEEDR$1"
    $ADB shell "mkdir -p /sdcard/DCIM/Camera;
echo $SEED_B64_A | base64 -d > /sdcard/DCIM/Camera/${tag}A.png; touch -t 203001010900.00 /sdcard/DCIM/Camera/${tag}A.png; am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/DCIM/Camera/${tag}A.png >/dev/null;
echo $SEED_B64_B | base64 -d > /sdcard/DCIM/Camera/${tag}B.png; touch -t 203002020900.00 /sdcard/DCIM/Camera/${tag}B.png; am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/DCIM/Camera/${tag}B.png >/dev/null"
    sleep 2
    # 播种判据=MediaStore 真源恰好 2 条新名行（run10 教训：只判"文件在盘"会把"建行失败"拖到屏上 NOHIT 才炸）
    local n; n=$($ADB shell "content query --uri content://media/external/images/media --projection _display_name:is_trashed" | grep -c "$tag.*is_trashed=0" || true)
    [ "$n" -eq 2 ] || { bad "播种 :: 第 $1 代种子建行不是恰好 2 条（读到 $n，盘上文件≠库里有行）"; return 1; }
}
# 首启/升级弹框统一收口：不同 Photos 版本/状态各弹各的——
#   登出备份壁 "Sign in to back up"（无跳过钮，点外区 scrim 撤）、
#   升级推广页 UpdateAppTreatmentPromoPage "Update your Google Photos / You're missing out"（有 Not now，avd35 实锤盖网格）。
# 有则就地关，无则跳过（tapn 找不到只 log NOHIT 不判负）。
dismiss_popups() {
    grep -q "Update your Google Photos\|missing out\|Not now" "$XHOST" && tapn "^Not now$" 2
    grep -q "Sign in to back up\|back up your photos" "$XHOST" && { tapn "Skip|No thanks|Later|Not now" 2 || $ADB shell input tap $((W/2)) 700; sleep 2; dump_idle; }
}
# 人工"移入废纸篓"前置（测试通道手指，产品不背这条：模板的职责是清空回收站，不是把照片扔进去）
prime_trash() {
    local tag="S5SEEDR$1"
    $ADB shell "am force-stop com.google.android.apps.photos" >/dev/null 2>&1; sleep 1
    $ADB shell monkey -p com.google.android.apps.photos -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 8
    dump_idle
    dismiss_popups
    local i j lbl
    for i in 1 2; do
        if [ "$i" -eq 1 ]; then lbl="$SEED_LABEL_A"; else lbl="$SEED_LABEL_B"; fi
        # 转场/重排后首帧 dump 可能整屏无种子标签（实测 run2 第 2 张 NOHIT 即此）：复采到出现为止；
        # 第 4 次还没有=不是转场而是页面态丢了（查看器吞了返回键之类）→冷启 Photos 回网格再采（run6 轮 2 实锤）
        j=0
        until grep -q "$lbl" "$XHOST" || [ "$j" -ge 8 ]; do
            [ "$j" -eq 3 ] && { $ADB shell "am force-stop com.google.android.apps.photos"; sleep 1; $ADB shell monkey -p com.google.android.apps.photos -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 7; dump_idle; dismiss_popups; }
            sleep 1; dump_idle; j=$((j + 1))
        done
        tapn "$lbl" 3 || { bad "前置 :: 第 $i 张（$lbl）开种子缩略图失败"; return 1; }
        tapn "Delete$" 2 || { bad "前置 :: 第 $i 张点不到查看器 Delete"; return 1; }
        # "Move to trash" 行（精确等值，锁掉上面那句说明文字——两节点中心差 128px，误点说明=白点）
        dump_idle
        tapn "^Move to trash$" 3 || { bad "前置 :: 第 $i 张点不到 Move to trash 行"; return 1; }
        # 登出态 Photos 首碰移篓弹 "Items moved to trash are removed from all folders" + Got it
        # （pm clear 后必现，run8 实锤：不点 Got it 则整条移篓动作被拦，种子从未进篓——
        # 旧 .trashed 残骸还会冒充"已进篓"计数，故下面的判据同时改成只认本轮两条种子行本身）
        grep -q "removed from all folders" "$XHOST" && tapn "^Got it$" 3
        $ADB shell "input keyevent 4"; sleep 2; dump_idle
    done
    # 进篓真判据=盘上改名事实：本代可见种子归零 + 篓内本代恰好 2 条（历代残骸不算——run9/10 中断轮
    # 留过 .trashed 残骸，>=2 会被冒充）。本 Photos 版移篓是"改名+删索引行"（行整条消失，不是
    # is_trashed=1——run9 实测），MediaStore 计数不可用。
    local vis tr
    vis=$($ADB shell "ls /sdcard/DCIM/Camera/" | grep -c "$tag" || true)
    tr=$($ADB shell "ls -a /sdcard/DCIM/Camera/" | grep -c ".trashed-.*$tag" || true)
    [ "$vis" -eq 0 ] && [ "$tr" -eq 2 ] || { bad "前置 :: 本代移篓未落盘（可见残=$vis 篓内=$tr，应为 0 与 2）"; return 1; }
}

# ---- T0 结构面：Gmail/Discord 装载（S5-R7：只验装载+落账，不验业务步，不代人登录）----
load_tpl() { # $1=id $2=期望步数 → 断言 load ok + ledger written origin=template
    MSYS_NO_PATHCONV=1 $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --ez keep_fg true --es template_load $1" >/dev/null 2>&1
    sleep 2
    logs | grep -aq "S5SMOKE template load ok id=$1 .* steps=$2 " && \
    logs | grep -aq "model ledger written origin=template steps=$2"
}
if load_tpl gmail_cleanup 4; then pass "T0a :: gmail_cleanup 装载面绿（4 步落账 origin=template，未执行任何业务步）"; else bad "T0a :: gmail_cleanup 装载判据不满足"; fi
if load_tpl discord_checkin 5; then pass "T0b :: discord_checkin 装载面绿（5 步落账 origin=template，未执行任何业务步）"; else bad "T0b :: discord_checkin 装载判据不满足"; fi
# 负例：脏 id 当场拒且不落账（fail-closed，注入通道不给人情面）
MSYS_NO_PATHCONV=1 $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --ez keep_fg true --es template_load bogus_x" >/dev/null 2>&1
sleep 2
if logs | grep -aq "template load refused gate=LOADER id=bogus_x"; then pass "T0c :: 脏 id 被装载器拒并留痕（一步不落）"; else bad "T0c :: 脏 id 没被拒或没留痕"; fi

# ---- T1..Tn 相册模板全链实证（S5-R7 唯一"业务步设备实证"档）----
R=1
while [ "$R" -le "$ROUNDS" ]; do
    log "—— 轮 $R/$ROUNDS ——"
    RAW="$RAW_DIR/s5a-$($ADB get-serialno | tr -d '\r')-photos-round$R-$(date +%Y%m%d-%H%M%S).log"
    MSYS_NO_PATHCONV=1 $ADB logcat -c
    { echo "# S5-a photos_cleanup 全链轮 $R  @ $(date -Iseconds)  serial=$($ADB get-serialno)"; } > "$RAW"
    seed_photos "$R" && prime_trash "$R" || { bad "轮 $R :: 前置未立住（见上）"; break; }
    # 装载（每轮重走装载口：同时兑付"库→账→框→放"整链，不只验一次）
    if ! load_tpl photos_cleanup 9; then bad "轮 $R :: photos_cleanup 装载判据不满足"; break; fi
    # 冷启 Photos 首页（模板第一步 "Show Navigation Drawer" 只在家里存在——上轮残页教训：resume 到废纸篓子页必 NODE_NOT_FOUND）
    # 冷启可能再撞升级推广页（avd35 实锤每冷启弹一次），先 dismiss 再判抽屉
    $ADB shell "am force-stop com.google.android.apps.photos" >/dev/null 2>&1; sleep 1
    $ADB shell monkey -p com.google.android.apps.photos -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 8
    dump_idle
    dismiss_popups
    grep -q "Show Navigation Drawer" "$XHOST" || { bad "轮 $R :: 首页无抽屉节点，前置页态不对"; break; }
    TASK=$(logs | grep -a "S3SMOKE-TASK " | tail -1 | sed 's/.*S3SMOKE-TASK //' | tr -d '\r')
    [ -n "$TASK" ] || { bad "轮 $R :: 没抓到装载产出的任务 JSON"; break; }
    MSYS_NO_PATHCONV=1 $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '$TASK'" >/dev/null 2>&1
    # 竞态修复（run6 实锤：注入瞬间 MainActivity 刚 finish，mCurrentFocus 还是无后缀自家窗
    # → i=0 就"见面板"点了空处，第 9 步真面板 15s 超时自拒，第 9 步 NODE_NOT_FOUND）：
    # 先等焦点回到 Photos（=执行真开跑、自家窗已让位），再等自家窗回焦（=面板现身）
    PH=0; i=0
    while [ "$i" -lt 30 ]; do
        if MSYS_NO_PATHCONV=1 $ADB shell dumpsys window 2>/dev/null | grep mCurrentFocus | grep -q "com.google.android.apps.photos"; then
            PH=1; break
        fi
        sleep 1; i=$((i + 1))
    done
    [ "$PH" -eq 1 ] || bad "轮 $R :: 30s 内焦点没回到 Photos（执行未开跑，注入/拒收面有问题）"
    # 面板判据=mCurrentFocus 是无活动后缀的自家窗；出现即点"确认执行"（用户手指的替身，测试通道）
    # 轮 3/5 血账（本批实锤）：点一次就撒手=盲投——固定坐标落空时产品按 fail-closed 15s 自拒，
    # 账面只剩"回执不达"，分不清"测试手指没点到"与"产品确认钮坏了"。现补两件事（判据只加不减）：
    #   ①面板在场瞬间截图留证（screencap 只读，非 uiautomator dump，不打断在跑任务）；
    #   ②点完必须复核面板窗是否撤走（撤走=决策真被吃进），8s 内没撤=坐标落空，如实记红并补点一次
    #     让本轮链走完，但补点事实进 RAW，不洗成"一次点中"。
    PANEL_SEEN=0; i=0
    TAPX=$BTN_CONFIRM_X; TAPY=$BTN_CONFIRM_Y   # 兜底值：未见面板/量不到时不致用未初始化变量（set -u）
    while [ "$i" -lt 40 ]; do
        if MSYS_NO_PATHCONV=1 $ADB shell dumpsys window 2>/dev/null | grep mCurrentFocus | grep -qE "u0 com\.anytouch\.app\}"; then
            PANEL_SEEN=1
            # v1.0.2 首跑轮 4 血账：焦点刚见到自家窗就立刻 screencap，抓到的是"只有压暗层、面板内容
            # 还没合成"的那一帧（该帧面板底色像素=0，正常轮 104548）——按屏定位量不到钮，退死坐标落空，
            # 产品按 fail-closed 15s 自拒（红记在测试通道，产品行为反倒是对的）。
            # 现改成"拍→量→量不到重拍"最多 3 次（每次先睡 1s 让面板画完），三拍全空才记红退死坐标。
            MEASURED=0; a=0
            while [ "$a" -lt 3 ]; do
                a=$((a + 1))
                sleep 1
                PSHOT="$RAW_DIR/s5a-panel-round$R-try$a-$(date +%H%M%S).png"
                MSYS_NO_PATHCONV=1 $ADB exec-out screencap -p > "$PSHOT" 2>/dev/null
                echo "# 面板在场截图(第 $a 拍)=$PSHOT 焦点=$(MSYS_NO_PATHCONV=1 $ADB shell dumpsys window 2>/dev/null | grep mCurrentFocus | tr -d '\r')" >> "$RAW"
                MEAS=$(PYTHONIOENCODING=utf-8 python scripts/find-panel-confirm.py "$PSHOT" 2>>"$RAW"); MEAS_RC=$?
                if [ "$MEAS_RC" -eq 0 ] && [ -n "$MEAS" ]; then
                    TAPX=${MEAS% *}; TAPY=${MEAS#* }; MEASURED=1
                    echo "# 面板按钮按屏定位=(${TAPX},${TAPY}) 量自 $(basename "$PSHOT")（第 $a 拍命中）" >> "$RAW"
                    break
                fi
            done
            [ "$MEASURED" -eq 1 ] || bad "轮 $R :: 面板按钮按屏定位三拍皆失败（三帧都没量到两枚钮），退死坐标试投——本轮即便走完也不记干净绿"
            MSYS_NO_PATHCONV=1 $ADB shell input tap $TAPX $TAPY
            break
        fi
        sleep 1; i=$((i + 1))
    done
    [ "$PANEL_SEEN" -eq 1 ] || bad "轮 $R :: 40s 内未见高危确认面板（第 9 步前必有——没见=链条没走到，磁盘判据再绿也不记全绿）"
    GONE=0; i=0
    while [ "$i" -lt 8 ]; do
        MSYS_NO_PATHCONV=1 $ADB shell dumpsys window 2>/dev/null | grep mCurrentFocus | grep -qE "u0 com\.anytouch\.app\}" || { GONE=1; break; }
        sleep 1; i=$((i + 1))
    done
    if [ "$GONE" -eq 0 ]; then
        bad "轮 $R :: 首次确认点击未落（面板窗 8s 后仍在焦=决策没被吃进，坐标/时机问题，不是产品放行逻辑问题）"
        echo "# 首次点击未落，补点一次以走完本轮链（事实已记红，不洗）" >> "$RAW"
        MSYS_NO_PATHCONV=1 $ADB shell input tap $TAPX $TAPY
    fi
    i=0
    while [ "$i" -lt 30 ]; do
        logs | grep -aq "S1SMOKE ok=" && break
        sleep 1; i=$((i + 1))
    done
    echo "# 回执等待 ${i}s（host=$(date +%H:%M:%S) device=$($ADB shell date '+%H:%M:%S' | tr -d '\r')）" >> "$RAW"
    RLINE=$(logs | grep -a "S1SMOKE ok=" | tail -1)
    echo "$RLINE" >> "$RAW"
    if printf '%s' "$RLINE" | grep -q "ok=9 total=9 stopped=false"; then pass "轮 $R :: 全链回执 ok=9/9 未中止"; else bad "轮 $R :: 回执不达 ok=9 total=9 stopped=false → $RLINE"; fi
    # 磁盘终判：ok=9 落账在前、Photos/MediaProvider 物理删除在后（本批实锤：轮 1 回执 ok=9 当场读
    # 仍见 .trashed=2，稍后再读已归零）。旧脚本靠"点完 sleep 12"顺带吃到了这段异步，改成回执即读
    # 就把这条真延迟变成了假红。现固定留 15s 沉降，再最多三次对拍（0/8/16s），每次读数全进 RAW：
    # 判据本身不松——残留必须归零才记绿，只是不再把"删除在飞"当成"删除失败"。
    sleep 15
    LEFT=""; ANY=""; VERDICT=""
    for k in 1 2 3; do
        [ $k -gt 1 ] && sleep 8
        LEFT=$($ADB shell "ls -a /sdcard/DCIM/Camera/" | grep -c ".trashed-.*S5SEED" || true)
        ANY=$($ADB shell "ls /sdcard/DCIM/Camera/" | grep -c "S5SEED" || true)
        echo "# 磁盘终判 第 $k 读 @$($ADB shell date '+%H:%M:%S' | tr -d '\r') .trashed 残留=$LEFT 种子残留=$ANY" >> "$RAW"
        if [ "$LEFT" -eq 0 ] && [ "$ANY" -eq 0 ]; then VERDICT=clean; break; fi
    done
    if [ "$VERDICT" = clean ]; then
        pass "轮 $R :: 磁盘终判种子物理消失（篓内外双真空，非账面绿）"
        [ "${k:-1}" -gt 1 ] && log "轮 $R :: 归零发生在第 $k 次对拍（删除异步，读数已入 RAW）"
    else bad "轮 $R :: 回收站仍有残留（三次对拍后 trashed=$LEFT any=$ANY）"; fi
    logs >> "$RAW"
    log "raw → $RAW"
    R=$((R + 1))
done

# 轮后设备态复查（网络/绑定/锁释放），只读
$ADB shell "dumpsys connectivity 2>/dev/null | grep -c 'CONNECTED'" >/dev/null 2>&1
log "收尾：服务在绑=$($ADB shell dumpsys accessibility | grep -c 'Bound services:{Service\[label=Anytouch')；用时 $(( $(date +%s) - T0 ))s"
printf 'RESULT fail=%s passed=%d rounds=%s\n' "$fail" "$passed" "$ROUNDS"
exit "$fail"
