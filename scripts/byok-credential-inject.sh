#!/usr/bin/env bash
# Anytouch BYOK 凭据代填（S3-E 用，裁决 S3-R5 覆盖 S3-R4-2 的手输半边）
#
# 老板 2026-09-23 原话（裁决 S3-R5，语音原文照录）：
#   "这些KEY都是免费的，你就直接干就完事了，本来就是拿来测试用的，所以没有顾虑，我手动输入的话，
#    key太长了，还容易弄错" / "你直接从后台输入不就完事了嘛" / "这个文件里面包含了所有我当前可用的key"
# 覆盖的是 R4-2 里"由老板手指完成"那半边；R4-2 的安全理由（字面量进 shell 不安全）老板已知悉并放弃。
#
# 本脚本仍然守住的（这三条不随裁决放开）：
#   1) 脚本内**一个字面的 Key 都没有**，只从 --key-file 读指定行；
#   2) 全程**不打印** Key（连部分回显都不做）；屏上回读只允许出现 `***尾4` 这种军令 §3-3 认可形态；
#   3) 三个值下发前先做**字符集校验**，出现 `input text` 需要转义的字符就整条中止——
#      宁可不填，也不把猜测的转义结果敲进凭据框（填错=后面所有断言全在错数据上跑）。
#
# 用法：ANDROID_SERIAL=<serial> bash scripts/byok-credential-inject.sh --key-file <path> [--key-line N]
#       BASE=… MODEL=… 可覆盖默认端点/模型。
set -u

ADB="adb"
[ -n "${ANDROID_SERIAL:-}" ] && ADB="adb -s ${ANDROID_SERIAL}"
BASE="${BASE:-https://integrate.api.nvidia.com/v1}"
MODEL="${MODEL:-deepseek-ai/deepseek-v4.1-flash}"
KEY_FILE=""
KEY_LINE=1

while [ $# -gt 0 ]; do
    case "$1" in
        --key-file) KEY_FILE="${2:-}"; shift 2 ;;
        --key-line) KEY_LINE="${2:-}"; shift 2 ;;
        *) echo "未知参数：$1"; exit 2 ;;
    esac
done
[ -n "$KEY_FILE" ] && [ -f "$KEY_FILE" ] || { echo "必须给 --key-file，且文件要存在"; exit 2; }
case "$KEY_LINE" in (''|*[!0-9]*) echo "--key-line 必须是正整数"; exit 2 ;; esac

KEY=$(sed -n "${KEY_LINE}p" "$KEY_FILE" | tr -d '\r\n[:space:]')
[ -n "$KEY" ] || { echo "第 $KEY_LINE 行是空的，中止"; exit 2; }

# 字符集白名单（fail-closed）：只允许字母数字与 - _ . / : 。其余一律中止，不做"顺手转义"。
for pair in "base_url:$BASE" "model:$MODEL" "key:$KEY"; do
    name=${pair%%:*}; val=${pair#*:}
    printf '%s' "$val" | grep -qE '^[A-Za-z0-9/._:-]+$' \
        || { echo "$name 含需要转义的字符，中止（不猜转义结果）"; exit 2; }
done

SIZE=$(MSYS_NO_PATHCONV=1 $ADB shell wm size 2>/dev/null | tr -d '\r' | grep -o '[0-9]*x[0-9]*' | tail -1)
W=${SIZE%x*}; H=${SIZE#*x}
[ -n "${W:-}" ] && [ -n "${H:-}" ] || { echo "读不到屏幕尺寸（wm size）"; exit 2; }
swipe_up() { MSYS_NO_PATHCONV=1 $ADB shell input swipe "$((W/2))" "$((H*72/100))" "$((W/2))" "$((H*26/100))" 500 >/dev/null 2>&1; sleep 1; }

DUMP=/sdcard/.byokinj.xml
dump_page() { MSYS_NO_PATHCONV=1 $ADB shell "uiautomator dump $DUMP >/dev/null 2>&1; cat $DUMP" 2>/dev/null | tr -d '\r'; }
# 取某 tag 当前屏上的中心点。**必须有真实 bounds 才算"在屏上"**：
# 设备实证（本机第二枪）——`byok_key` 在输入法顶起后仍出现在 dump 里（带 resource-id），但**整个节点
# 没有 bounds 属性**（Compose 把它裁出了可视区）。我第一版在拿不到坐标时回退成 `1 1`，
# 于是点击落在屏幕左上角，Key 一个字没进框，脚本却因为"框内长度 0"没被触发检查而继续往下走。
# 所以这里改成：无 bounds = 不可见 = 交给调用方滚动重试，绝不猜坐标。
center_of() {
    local tag="$1" b x1 y1 x2 y2
    b=$(dump_page | tr '<' '\n' | grep -a "resource-id=\"$tag\"" | grep -ao 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1)
    [ -n "$b" ] || return 1
    set -- $(printf '%s' "$b" | grep -oE '[0-9]+')
    [ $# -ge 4 ] || return 1
    x1=$1; y1=$2; x2=$3; y2=$4
    # **零面积也算不可见**：本机实测 byok_key 在输入法顶起后报 `bounds="[0,0][0,0]"`——
    # 只判"有没有 bounds 属性"会把它当可见，于是一枪打在屏幕左上角 (0,0)，Key 一个字没进框。
    [ "$x2" -gt "$x1" ] && [ "$y2" -gt "$y1" ] || return 1
    printf '%d %d' "$(( (x1+x2)/2 ))" "$(( (y1+y2)/2 ))"
}
# 该 tag 在树上存在但没有 bounds（=没渲染上屏）：单独报出来，别和"根本没这个格子"混成一句话
exists_without_bounds() {
    local tag="$1" line
    line=$(dump_page | tr '<' '\n' | grep -a "resource-id=\"$tag\"" | head -1)
    [ -n "$line" ] || return 1
    printf '%s' "$line" | grep -aq 'bounds="' && return 1
    return 0
}
# 框里现有内容的**长度**（绝不打印内容：Key 框即使是掩码也不赌）
field_len() {
    local tag="$1" t
    t=$(dump_page | tr '<' '\n' | grep -a "resource-id=\"$tag\"" | grep -o 'text="[^"]*"' | head -1)
    t=${t#text=\"}; t=${t%\"}
    printf '%d' "${#t}"
}
# 框里现有内容的**字面值**——只给非密码框用（密码框 text 恒空，见 is_password_field）。
# 设备实证（本机第三枪）：`input text` 在 MIUI 上会把相邻两字打反——
# 屏上最终存的是 `https://integrate.api.nvidia.com/1v`（v1→1v），**长度 35 完全正确**，
# 于是"只比长度"的复核放行了一个 404 的地址：长度检查看得见"少字/串框"，看不见换序。
# 端点与模型名不是凭据（Key 才是），所以这两个框可以逐字比、必要时逐字报。
field_text() {
    local tag="$1" t
    t=$(dump_page | tr '<' '\n' | grep -a "resource-id=\"$tag\"" | grep -o 'text="[^"]*"' | head -1)
    t=${t#text=\"}; t=${t%\"}
    printf '%s' "$t"
}
hide_ime() {
    # 设备实证（本机第一枪）：`input keyevent 111`(ESCAPE) 在 MIUI 上**收不起输入法**，
    # 于是下一个框的坐标被键盘盖住，点击落在键盘上、焦点仍留在上一个框——
    # 结果 base_url 变成 66 字（35 的地址 + 31 的模型名拼在一起），model 长度 0。
    # BACK 才真的收起键盘；收完 dump 复核焦点窗确实是自家 MainActivity 才算收干净。
    #
    # 设备实证（本机第二枪，还是这个函数）：**键盘没弹起时发 BACK，退掉的是 App 自己**——
    # 焦点窗变成 LauncherOverlayWindow:com.miui.newhome，屏上已是桌面，之后的每次点击都在判桌面。
    # 无条件 BACK = 拿"收起键盘"的动作去干"退出应用"的事。所以 BACK 必须有前置条件：
    # 只读 mInputShown（K40 实测该字段存在，键盘收起时=false），键盘真在屏上才发。
    MSYS_NO_PATHCONV=1 $ADB shell dumpsys input_method 2>/dev/null | grep -qa 'mInputShown=true' || return 0
    MSYS_NO_PATHCONV=1 $ADB shell input keyevent 4 >/dev/null 2>&1
    sleep 1
    # 只看焦点窗的包名/Activity 在不在（第一版把正则切在 `com.anytouch.app` 就断了，
    # 却去比对 `com.anytouch.app/MainActivity` → 永远报"不是自家窗"，一条常亮的假警告）。
    # 这条不是警告是**中止条件**：焦点窗不是自家，后面所有坐标/长度读数都不属于本产品。
    MSYS_NO_PATHCONV=1 $ADB shell "dumpsys window | grep -a mCurrentFocus" 2>/dev/null \
        | grep -qa 'com.anytouch.app/com.anytouch.app.MainActivity' && return 0
    echo "      | 中止：BACK 之后焦点窗不是自家 MainActivity（读数不再属于本产品）"
    return 1
}
# 清框：点进去 → 光标到末尾 → 设备上循环 DEL（一次 adb 往返，不在 PC 侧循环发招）
clear_field() {
    local tag="$1" i=0 xy had
    for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
        xy=$(center_of "$tag") && break
        swipe_up
    done
    [ -n "${xy:-}" ] || { echo "$tag 不可见，清不了框"; return 1; }
    had=$(field_len "$tag")
    [ "$had" != "0" ] || { echo "      | $tag 本来就是空的"; return 0; }
    MSYS_NO_PATHCONV=1 $ADB shell input tap $xy >/dev/null 2>&1; sleep 1
    MSYS_NO_PATHCONV=1 $ADB shell "input keyevent 123; for i in \$(seq 1 $(( had + 20 )) ); do input keyevent 67; done" >/dev/null 2>&1
    hide_ime || return 1
    local had2
    had2=$(field_len "$tag")
    [ "$had2" = "0" ] || { echo "$tag 清框后仍剩 $had2 字，中止"; return 1; }
    echo "      | $tag 已清空"
}
# 该 tag 是否为密码框（password="true"）。**密码框在 uiautomator dump 里 text 恒空**（掩码），
# 所以"下发 70 字就读回 70 字"这条复核在 byok_key 上必然失败——不是没输进去，是**读不到**。
# 密码框改用两条能读的证据：① 点完之后该节点 focused="true"（证明敲的是它，不是隔壁框）；
#                    ② 最终成败交给产品自己的保存读回比对：屏上出现 `***尾4` 才算存上。
is_password_field() {
    dump_page | tr '<' '\n' | grep -a "resource-id=\"$1\"" | grep -qa 'password="true"'
}
is_focused() {
    dump_page | tr '<' '\n' | grep -a "resource-id=\"$1\"" | grep -qa 'focused="true"'
}
# 滚到可见 → 点聚焦 → 敲字 → 逐字复核，不符就清框重打（最多三轮）
type_into() {
    local tag="$1" value="$2" i=0 xy attempt=1 got
    while :; do
        xy=""
        for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
            xy=$(center_of "$tag") && break
            swipe_up
        done
        [ -n "$xy" ] || {
            if exists_without_bounds "$tag"; then
                echo "$tag 在语义树里存在但**没有可用 bounds**（滚了 12 屏也没渲染上屏）——不猜坐标，中止"
            else
                echo "$tag 在 dump 里根本不存在，中止"
            fi
            return 1
        }
        local had
        had=$(field_len "$tag")
        [ "$had" = "0" ] || { echo "$tag 框内已有 $had 字（不明旧值），中止——先清框再来"; return 1; }
        MSYS_NO_PATHCONV=1 $ADB shell input tap $xy >/dev/null 2>&1; sleep 1
        is_focused "$tag" || { echo "$tag 点完没拿到焦点，中止（坐标可能盖在键盘下）"; return 1; }
        # 设备实证（本机第四枪）：一次 `input text` 下发整串会**相邻成对换序**——
        #   base_url → `https://integrate.api.nvidia.com1/v`、model → `deepseek-ai/deepseek-4v1.-flash`
        #   （`-v4.1-` 打成 `4v1.-`：两两对调），且同一串重打三轮能错三轮，读数从 t+1s 起稳定。
        # 不是输入法事后纠正，是**整串一次下发的提交落点顺序本身有竞态**（MIUI 输入法吞字序）。
        # 对策：**一律逐字下发**（设备上 for 循环，一个字一次 `input text`）——实测 31 字 1.7s 且逐字落对。
        # Key 框读不到内容（密码框 text 恒空），没有"逐字复核"这道保险，更不能赌整串下发；
        # 两态一条码，比"明文走快路、密文走慢路"少一个只在一处生效的分支。
        MSYS_NO_PATHCONV=1 $ADB shell \
            "for c in $(printf '%s' "$value" | sed -e "s/\(.\)/'\1' /g"); do input text \"\$c\"; done" >/dev/null 2>&1
        sleep 1
        if is_password_field "$tag"; then
            echo "      | $tag 是密码框（dump 读不到内容），按'聚焦+已下发'记账，真判据在屏上回读那一步"
            break
        fi
        # 逐字比，不是比长度：长度检查看得见"少字/串框"，看不见换序（本机第三、四枪都是这么过去的）。
        got=$(field_text "$tag")
        [ "$got" = "$value" ] && { echo "      | $tag 已下发并逐字读回：[$got]（长度 ${#got}，第 $attempt 轮）"; break; }
        attempt=$((attempt + 1))
        if [ "$attempt" -gt 3 ]; then
            echo "$tag 三轮逐字复核全不符，中止（最后一次屏上=[$got]，期望 [$value]）"
            return 1
        fi
        echo "      | $tag 第 $((attempt - 1)) 轮读回 [$got] ≠ 期望，清框重打"
        hide_ime || return 1
        clear_field "$tag" || return 1
    done
    hide_ime || return 1
    # 复核"没串到别的框"：还没轮到填的明文框必须还是空的，**之前几步填过的**必须逐字等于当初填的值。
    # 状态要跨调用持久（DONE_* 全局），第一版把"已填过"和"还没填"混在一个表达式里判，
    # 于是填 model 时要求 base_url=0，自己把自己判红——检查写反比不检查更费时间。
    # 只给"这一步真的填过的"记账。上一版把两个 DONE_ 一起置上，等于宣布"两个框都填完了"，
    # 于是填 base_url 那一步就要求 model 已经是 31 字——同一个判反错误换了个位置又犯一次。
    case "$tag" in
        byok_base_url) DONE_byok_base_url=$BASE ;;
        byok_model)    DONE_byok_model=$MODEL ;;
    esac
    local other want_now ot
    # DONE_x 只在这一步真填过之后才被赋成那个值；没填过就是空串=期望它屏上还是空的。
    # （BASE/MODEL 本身非空，所以"空 DONE_"与"已填"两态不会混）
    for other in byok_base_url byok_model; do
        eval "want_now=\${DONE_$other:-}"
        ot=$(field_text "$other")
        [ "$ot" = "$want_now" ] || { echo "串框：$other 现在 [$ot]，这一步之后它应该是 [$want_now]"; return 1; }
    done
    return 0
}

MSYS_NO_PATHCONV=1 $ADB shell am start -n com.anytouch.app/.MainActivity >/dev/null 2>&1
sleep 2
echo "注入开始：靶=${ANDROID_SERIAL:-唯一设备} 端点 host=$(printf '%s' "$BASE" | sed -E 's#https?://([^/]+).*#\1#') 模型=$MODEL"
# 先把三个框清成"确定的空"再填：上一轮实测证明"追加进旧值"是真的会发生的（base_url 曾变成 35+31=66 字），
# 而"读回来一模一样"是产品自己的保存判据——脚本不能把没读过的数据当干净输入。
clear_field byok_base_url || exit 1
clear_field byok_model    || exit 1
clear_field byok_key      || exit 1
type_into byok_base_url "$BASE" || exit 1
type_into byok_model     "$MODEL" || exit 1
type_into byok_key       "$KEY"   || exit 1

# 点保存：先滚到 byok_save 可见
xy=""; for i in 1 2 3 4 5 6 7 8 9 10 11 12; do xy=$(center_of byok_save) && break; swipe_up; done
[ -n "$xy" ] || { echo "byok_save 不可见，中止"; exit 1; }
MSYS_NO_PATHCONV=1 $ADB shell input tap $xy >/dev/null 2>&1
sleep 3

# 回读：只允许打印 ***尾4 形态（保存必读回比对是产品判据，这里验的是它有没有真的做到）
TAIL_LINE=$(dump_page | tr '<' '\n' | grep -a 'resource-id="byok_key_tail"' | grep -o 'text="[^"]*"' | head -1)
echo "      | 屏上凭据状态行：${TAIL_LINE:-（未读到）}"
MSG_LINE=$(dump_page | tr '<' '\n' | grep -a 'resource-id="byok_config_message"' | grep -o 'text="[^"]*"' | head -1)
echo "      | 屏上配置回执：${MSG_LINE:-（无）}"
EXPECT="***${KEY: -4}"
case "$TAIL_LINE" in
    *"$EXPECT"*) echo "RESULT=SAVED（屏上回读到的尾 4 位与下发的那把一致：$EXPECT）"; exit 0 ;;
    *) echo "RESULT=NOT-SAVED（期望尾 4 位 $EXPECT 没出现在状态行里——按产品口径这不算存上）"; exit 1 ;;
esac
