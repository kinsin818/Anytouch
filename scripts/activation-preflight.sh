#!/usr/bin/env bash
# S5-f 判据 10 的**统一前置**：受付费墙影响的冒烟脚本一律在预检里先经"注入通道输入合法码"完成激活
# ——与真人同一条校验路径（Extras 送的还是那 18 个字符，走同一个 ActivationStore.submit），**不开旁路**。
#
# 为什么收成一份而不是五份脚本各抄一段：
# 1) 合法码字面量抄进五处就是五份真值——改算法时必有人漏改，前置就成了"装了新码却没激活"的假红源头；
#    这里现场由 `scripts/activation-code.py`（发卡口，与 JVM golden 表同律）mint，脚本侧一个字符都不写死。
# 2) 复核话术只写一处：前置到底算不算成立，五份脚本必须同一把尺子。
#
# 用法：ANDROID_SERIAL=emulator-5554 bash scripts/activation-preflight.sh      # mint + 注入 + 复核
#       ANDROID_SERIAL=emulator-5554 bash scripts/activation-preflight.sh --reset   # 打回未激活（s5f 自己的起始/收尾态）
# 退码：0=前置成立；2=前置不成立（调用方必须当场停手，不烧轮次赌运气）。
# 取锁纪律：本脚本**不取设备锁**——只被已经持锁的冒烟脚本调用，第二把锁会把自己判红。
# 前置：调用方已完成自己的 E9/versionCode/md5 对账（本脚本只管激活态那一格）。
set -u
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."

ADB="adb"
[ -n "${ANDROID_SERIAL:-}" ] && ADB="adb -s $ANDROID_SERIAL"
# golden 表里那枚自由正文（10 位，发卡口补两位校验字母）——与 ActivationCodeTest、minter 自检同表
BODY="${ACT_BODY:-PRO9FAN8B1}"
TAG_LINE="S5FSMOKE activation"

[ -n "$($ADB get-serialno 2>/dev/null | tr -d '\r')" ] || { echo "activation-preflight :: 无可读靶机（ANDROID_SERIAL 指错或未在线）"; exit 2; }

if [ "${1:-}" = "--reset" ]; then
    $ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --ez keep_fg true --ez activation_reset true" >/dev/null 2>&1
    sleep 2
    OUT=$($ADB logcat -d -s AnytouchRun:* 2>/dev/null | grep -a "activation reset ok=true" | tail -1)
    [ -n "$OUT" ] || { echo "activation-preflight :: 复位未留痕（15s 内无 reset ok=true）"; exit 2; }
    echo "activation-preflight :: 已打回未激活（$OUT）"
    exit 0
fi

CODE=$(python scripts/activation-code.py "$BODY" 2>/dev/null | tail -1 | tr -d '\r')
# 发卡口吐的东西必须**自身可校验**（minter 与校验器分家之后，这里就是唯一那道闸）
[ "$(python scripts/activation-code.py --verify "$CODE" 2>/dev/null | tail -1 | tr -d '\r')" = "UNLOCKED" ] || {
    echo "activation-preflight :: mint 出来的码自检不通过（[$CODE]）——不发脏码进设备"; exit 2; }
TAIL=${CODE: -4}

# 先复位再输码：同一条 intent 里两个 extra 都给 = "从零重解一次"（MainActivity 那段前置注释定的顺序）。
# 不复位就输码的话，上一轮留下的解锁态会把"这一枚码到底能不能解锁"这件事掩护过去（假绿形态）。
$ADB shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --ez keep_fg true --ez activation_reset true --es activation_code '$CODE'" >/dev/null 2>&1
sleep 3
OK=$($ADB logcat -d -s AnytouchRun:* 2>/dev/null | grep -a "activation ok tail=$TAIL" | tail -1)
[ -n "$OK" ] || {
    REF=$($ADB logcat -d -s AnytouchRun:* 2>/dev/null | grep -a "activation refused" | tail -1)
    echo "activation-preflight :: 合法码没能解锁（ok 行缺席；最近拒因：${REF:-无拒因行=通道根本没进校验器}）"
    exit 2
}
# 屏面那一格是**第二遍证据**：日志说解锁了、屏上还挂着三句 Upgrade，就是镜像没跟上（假绿另一半）
# 先滚回页顶再 dump：折叠线以下的格子压根不进无障碍树（s5f run2 血账——原地 dump 读不到≠屏上没有，
# 前置若因此报红，红的是读数器，还会把五支调用脚本一起误关在门外）
WS=$($ADB shell wm size | tail -1 | tr -d '\r' | sed 's/.*: *//'); WX=${WS%x*}
for _ in 1 2 3 4 5 6; do $ADB shell input swipe "$((WX / 2))" 500 "$((WX / 2))" 1800 300 >/dev/null 2>&1; sleep 1; done
$ADB shell uiautomator dump /sdcard/.apref.xml >/dev/null 2>&1
$ADB shell cat /sdcard/.apref.xml 2>/dev/null | tr -d '\r' | grep -qa "code ending" || {
    echo "activation-preflight :: 日志已 ok 但屏上 activation_state 没报已解锁——镜像与磁盘态不同步"; exit 2; }
$ADB shell dumpsys accessibility 2>/dev/null | grep -qa "Bound services:{Service\[label=Anytouch" || {
    echo "activation-preflight :: dump 后无障碍未复绑（调用方的读数不可信）"; exit 2; }
echo "activation-preflight :: 已激活（尾四位 $TAIL，$OK）"
exit 0
