#!/usr/bin/env bash
# S5-g 起：受付费墙影响的冒烟脚本一律先经"从服务器租一枚在册 staging 码 + 注入通道输码"完成激活
# ——与真人同一条校验路径（Extras 送的还是那 18 个字符，走同一个 ActivationStore.submit），**不开旁路**。
#
# 与 S5-f 那份的三个差别（每条都是本批改判据的地方，不留旧口径暗门）：
# 1) **码不再本地 mint**。白名单成为校验权威之后，本地生成的码服务器一概不认（格式合法≠在册），
#    沿用 mint 会让五支脚本一起"对着假靶跑"。改走 `POST /api/admin/lease-staging` 租一枚**在册且未占满**
#    的 staging 码；本地 mint 此后只服务两件事：造格式负例、老板手工给真买家出码。
# 2) **取到必须验 kind**。租到 `kind='buyer'` 当场 exit 2——配置错把手伸进真买家额度，比测试红严重得多。
# 3) **服务器不可达 = exit 2 响亮失败**，绝不为变绿加"测试期跳过服务器"的旁路（判据 10 明令禁的那件事）。
#    CI 从此依赖外网属新事实，已写进 STATUS 门禁列。
#
# 在册码纪律（公开仓 + GPLv3）：本脚本一个字都不写死码原文，日志只打尾四位；
# 取码/重置走 `scripts/s5g-server.sh`（默认机内 ssh 执行，ADMIN_TOKEN 不出机）。
# 异步半边（S5-g 起输码要走一次网络往返）：先 logcat -c 再派发，然后**轮询终态行**——
# 单发一次 grep 会把"还在路上"读成"被拒"（假红），也会把上一轮那行 ok 读成这一轮的（假绿）。
#
# 用法：ANDROID_SERIAL=emulator-5554 bash scripts/activation-preflight.sh          # 租码 + 注入 + 复核
#       ANDROID_SERIAL=emulator-5554 bash scripts/activation-preflight.sh --reset   # 打回未激活
# 退码：0=前置成立；2=前置不成立（调用方必须当场停手，不烧轮次赌运气）。
# 取锁纪律：本脚本**不取设备锁**——只被已经持锁的冒烟脚本调用，第二把锁会把自己判红。
# 前置：调用方已完成自己的 E9/versionCode/md5 对账（本脚本只管激活态那一格）。
set -u
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."
. scripts/s5g-server.sh
. scripts/s5g-relay.sh

ADB="adb"
[ -n "${ANDROID_SERIAL:-}" ] && ADB="adb -s $ANDROID_SERIAL"
TAG_LINE="S5FSMOKE activation"
# 轮询上限：客户端预算是 connect/read 各 8s、整体 10s，加上冷启动与落盘复核，25s 是留给网络往返的余量。
# 到点仍无终态行 = 通道没进校验器或往返未结束，按实照报，不续命、不猜绿。
WAIT_OK="${WAIT_OK:-25}"

[ -n "$($ADB get-serialno 2>/dev/null | tr -d '\r')" ] || { echo "activation-preflight :: 无可读靶机（ANDROID_SERIAL 指错或未在线）"; exit 2; }

if [ "${1:-}" = "--reset" ]; then
    $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --ez keep_fg true --ez activation_reset true" >/dev/null 2>&1
    sleep 2
    OUT=$($ADB logcat -d -s AnytouchRun:* 2>/dev/null | grep -a "activation reset ok=true" | tail -1)
    [ -n "$OUT" ] || { echo "activation-preflight :: 复位未留痕（15s 内无 reset ok=true）"; exit 2; }
    echo "activation-preflight :: 已打回未激活（$OUT）"
    exit 0
fi

# ---- 0/4 靶机到服务器那条路（**这台测试机的路由事实，不是产品开关**） ----
# 本机出网被透明拦截（对无监听的 9999 也"连接建立"），靶机直打 45.32.63.177:8443 实测 8s 无回执。
# 中继只做一件事：把靶机那一条 TCP 转机上回环，TLS 与 SPKI 校验仍在 APP 自己那一层跑。
# 起中继前先核对面指纹等于 APP 固定值（对不上就停手）——对着假靶跑出来的绿一律不算绿。
# 生命周期：本脚本只**起**不**收**（收了下一支脚本又要起）；一轮设备面跑完由人显式收：
#   ANDROID_SERIAL=emulator-5554 bash scripts/s5g-relay.sh --down
if ! s5g_relay_up; then
    echo "activation-preflight :: 中继起不来/指纹核对不过——**绝不为变绿把激活改回本地判据**，本跑终止"
    exit 2
fi

# ---- 1/4 服务器可达性：不通就当场停（无旁路可走） ----
HEALTH=$(s5g_health)
case "$HEALTH" in
    *'"ok": true'*|*'"ok":true'*) ;;
    *)
        echo "activation-preflight :: 激活服务不可达（健康检查没回 ok；S5G_DIRECT=${S5G_DIRECT:-0}，响应前 60 字：[${HEALTH:0:60}]）"
        echo "activation-preflight :: 按判据 10 本跑不放行——不为变绿加测试期旁路；这台机出网性质见附页 §4 与部署实录的公网那一节"
        exit 2 ;;
esac

# ---- 2/4 从服务器租一枚 staging 码（kind 断言在 s5g-server.sh 里：租到 buyer 一律 exit 2） ----
CODE=$(s5g_lease_staging) || exit 2
TAIL=${CODE: -4}

# ---- 3/4 先复位再输码，且本轮回执只认本轮的日志 ----
# 同一条 intent 里两个 extra 都给 = "从零重解一次"（MainActivity 那段前置注释定的顺序）。
# 不复位就输码的话，上一轮留下的解锁态会把"这一枚码到底能不能解锁"这件事掩护过去（假绿形态）。
# logcat -c 必须排在派发之前、且与下面的轮询成对：不清就会 grep 到上一轮的 ok 行＝拿旧回执冒充本轮（S5-d 同族血账）。
$ADB logcat -c >/dev/null 2>&1
$ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --ez keep_fg true --ez activation_reset true --es activation_code '$CODE'" >/dev/null 2>&1

OK=""
REF=""
i=0
while [ "$i" -lt "$WAIT_OK" ]; do
    OK=$($ADB logcat -d -s AnytouchRun:* 2>/dev/null | grep -a "activation ok tail=$TAIL" | tail -1)
    [ -n "$OK" ] && break
    REF=$($ADB logcat -d -s AnytouchRun:* 2>/dev/null | grep -a "activation refused" | tail -1)
    [ -n "$REF" ] && break
    sleep 1
    i=$((i + 1))
done
if [ -z "$OK" ]; then
    echo "activation-preflight :: 在册 staging 码没能解锁（等了 ${WAIT_OK}s；最近拒因：${REF:-无终态行=通道没进校验器，或网络往返未结束}）"
    echo "activation-preflight :: 分档提示：本地格式过而服务器说不在册 → 屏上 Activation code invalid；连不上 → SERVER_UNREACHABLE（fail-closed，绝不放行）"
    exit 2
fi

# ---- 4/4 屏面第二遍证据 + 泄漏反向自检 ----
# 日志说解锁了、屏上还挂着三句 Upgrade，就是镜像没跟上（假绿另一半）。
# 先滚回页顶再 dump：折叠线以下的格子压根不进无障碍树（s5f run2 血账——原地 dump 读不到≠屏上没有，
# 前置若因此报红，红的是读数器，还会把五支调用脚本一起误关在门外）
WS=$($ADB shell wm size | tail -1 | tr -d '\r' | sed 's/.*: *//'); WX=${WS%x*}
for _ in 1 2 3 4 5 6; do $ADB shell input swipe "$((WX / 2))" 500 "$((WX / 2))" 1800 300 >/dev/null 2>&1; sleep 1; done
$ADB shell uiautomator dump /sdcard/.apref.xml >/dev/null 2>&1
$ADB shell cat /sdcard/.apref.xml 2>/dev/null | tr -d '\r' | grep -qa "code ending" || {
    echo "activation-preflight :: 日志已 ok 但屏上 activation_state 没报已解锁——镜像与磁盘态不同步"; exit 2; }
$ADB shell dumpsys accessibility 2>/dev/null | grep -qa "Bound services:{Service\[label=Anytouch" || {
    echo "activation-preflight :: dump 后无障碍未复绑（调用方的读数不可信）"; exit 2; }
# 反向自检（本轮新加的雷本轮就防）：整枚在册码不许漏进设备日志，只允许尾四位上屏/上日志。
# 遮罩"写过"不等于"遮住"——判据是拿码形再扫一次，扫不到才算过。
$ADB logcat -d 2>/dev/null | tr -d '\r' | grep -qa "$CODE" && {
    echo "activation-preflight :: 整枚在册码出现在设备 logcat 里（泄漏源就是本轮注入通道）"; exit 2; }
echo "activation-preflight :: 已激活（租到 staging 段，尾四位 $TAIL，$OK）"
exit 0
