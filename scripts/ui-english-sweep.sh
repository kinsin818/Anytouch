#!/usr/bin/env bash
# Anytouch S5-c 上屏英文清扫（军令⑤"确保没有残留中文字符"的设备侧判据）
# 判据：逐屏 uiautomator dump，只查节点 text / content-desc 两个属性里是否出现 CJK 字符或全角标点。
#       注释、日志（S#SMOKE 英文标签体系）、模型提示词、高危关键词表不在这两个属性里，属 S5-R9 豁免面，
#       由静态字面量扫描单独分类归档（清单见 evidence/S5/cjk-literal-inventory.md），不在本脚本判定范围。
# 走位纪律（假绿血账两轮，都在 evidence/S5/raw/english-sweep/ 里留了原始 dump）：
#   第一轮：页面停在底部连拍 8 张同图=只扫了半屏却报全绿；
#   第二轮：加了"连续两张 md5 相同=到底"的早停，被软键盘吞掉首滑后 4 帧连拍同一状态，
#           于是"不同状态数=2"照样过——所以闸门口径改成**顶锚与底锚必须在不同 dump 里各出现过**
#           （同屏里"Run task"按钮本来就常驻，靠单张含锚=假绿）。
#   每段先清键盘+退桌面重进（起手态一致），再"上滚一屏→dump"交替最多 14 次；顶锚 "Anytouch executor"、
#   底锚 "Run task" 缺一即判红。到底后停在原地重复 dump 是设计内行为，重复帧在走位计数里忽略。
#   段 3（S5-e 起）另走"手动补步骤面板展开态"：两锚换成面板自己那两句（顶="Adds one step at position"、
#   底="node tree, so there is"）——段名换了还沿用整页两锚，就只能证明整页扫过，证明不了这一层进过 dump。
# 用法：ANDROID_SERIAL=emulator-5554 bash scripts/ui-english-sweep.sh
# 前置：恰好 1 台靶机在线且装了本轮 APK、无障碍已绑。
# 洁净纪律：全程无在跑任务（执行期禁 dump 的雷天然不触发）；只读屏 + 模拟手指滑动，不改任何产品资产。
# 注：本站点集不含"高危二次确认面板"（仅执行链挂起时出现，执行期禁 dump）。该屏的 CJK=0 与"不露内部规则 id"
#     两条由 JVM 文案锁 `HighRiskPanelCopyTest`（正文字面逐条断言）+ s5-templates-smoke 每轮面板截屏（人读对拍）合起来覆盖。
set -u
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."

ADB="adb"
[ -n "${ANDROID_SERIAL:-}" ] && ADB="adb -s $ANDROID_SERIAL"
RAW_DIR="${RAW_DIR:-evidence/S5/raw/english-sweep}"
STAMP="${STAMP:-$(date +%Y%m%d-%H%M%S)}"
OUT="$RAW_DIR/$STAMP"
TOP_ANCHOR="Anytouch executor"
BOTTOM_ANCHOR="Run task"

fail=0
bad() { printf '\033[0;31mFAIL\033[0m %s\n' "$1"; fail=1; }
ok()  { printf '\033[0;32mPASS\033[0m %s\n' "$1"; }
log() { printf '      | %s\n' "$*"; }

# ---- 预检（不满足当场停，不拿空屏判红也不判绿） ----
DEVS=$(adb devices | grep -cw device)
if [ "$DEVS" -ne 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then bad "预检 :: adb 在线 $DEVS 台且未指定 ANDROID_SERIAL——禁双驱动"; exit 2; fi
MODEL=$($ADB shell getprop ro.product.model 2>/dev/null | tr -d '\r')
case "$MODEL" in
    *Kiwi*|*K40*|kona*|alioth*) bad "预检 :: 靶机 $MODEL 疑似 K40 真机——S5-c 零触碰真机"; exit 2 ;;
esac
VER=$($ADB shell dumpsys package com.anytouch.app | grep -m1 versionName | tr -d '\r')
log "靶机=$MODEL $VER"
$ADB shell dumpsys accessibility | grep -q "Bound services:{Service\[label=Anytouch" || { bad "预检 :: 无障碍服务未绑定"; exit 2; }
mkdir -p "$OUT"

dump_to() { # $1=目标文件名（不带目录）
    $ADB shell uiautomator dump /sdcard/sweep.xml >/dev/null 2>&1
    $ADB pull /sdcard/sweep.xml "$OUT/$1" >/dev/null 2>&1
    $ADB shell screencap -p /sdcard/sweep.png >/dev/null 2>&1
    $ADB pull /sdcard/sweep.png "$OUT/${1%.xml}.png" >/dev/null 2>&1
}

cjk_scan_all() { # 对 $OUT 下全部 xml 做一次并集 CJK 扫描，并打印去重后的上屏文案条数
    python - "$OUT" <<'PY'
import glob, re, sys
import xml.etree.ElementTree as ET
CJK = re.compile(r'[　-〿぀-鿿豈-﫿＀-￯]')
seen, hits = set(), []
for p in sorted(glob.glob(sys.argv[1] + '/*.xml')):
    for node in ET.parse(p).getroot().iter('node'):
        for attr in ('text', 'content-desc'):
            v = node.get(attr) or ''
            if not v:
                continue
            seen.add(v)
            if CJK.search(v):
                hits.append('%s: %s=%r id=%s' % (p.split('/')[-1], attr, v[:80], node.get('resource-id')))
print('distinct on-screen strings = %d' % len(seen))
with open(sys.argv[1] + '/on-screen-strings.txt', 'w', encoding='utf-8') as fh:
    for v in sorted(seen):
        fh.write(v + '\n')
for h in hits:
    print('CJK  ' + h)
sys.exit(1 if hits else 0)
PY
}

swipe() { $ADB shell input swipe "$1" "$2" "$1" "$3" 400 >/dev/null 2>&1; sleep 1; }
to_top() { for _ in 1 2 3 4 5 6; do swipe 500 500 1800; done; }

walk() { # $1=段名 [$2=顶锚（默认全局顶锚）] [$3=底锚（默认全局底锚）]
    # 两锚可换：整页段认"Anytouch executor / Run task"，面板段认面板自己那两句（不然"走位合格"只证明
    # 走完了整页，却证明不了这一屏面板真被扫进过 dump——段名换、闸门口径不换，才是同一把尺子）。
    local seg="$1" top_a="${2:-$TOP_ANCHOR}" bot_a="${3:-$BOTTOM_ANCHOR}"
    local idx=0 prev="" cur states=0 top_ok=0 bottom_ok=0
    to_top
    while [ $idx -lt 12 ]; do
        idx=$((idx + 1))
        [ $idx -gt 1 ] && swipe 500 1800 500   # 手指上移=页面向下走
        local name
        name=$(printf '%s-%02d' "$seg" "$idx")
        dump_to "$name.xml"
        cur=$(md5sum "$OUT/$name.xml" | cut -d' ' -f1)
        grep -q "$top_a" "$OUT/$name.xml" && top_ok=1
        grep -q "$bot_a" "$OUT/$name.xml" && bottom_ok=1
        if [ "$cur" = "$prev" ]; then
            # 到底判据：与上一屏逐字节相同。这张重复图留着（证据只追加不改），统计里按并集去重
            break
        fi
        prev="$cur"; states=$((states + 1))
    done
    [ $top_ok -eq 1 ] && [ $bottom_ok -eq 1 ] && [ $states -ge 2 ] || {
        bad "$seg :: 走位不合格（顶锚=$top_ok 底锚=$bottom_ok 不同状态数=$states）——半屏 dump 不算扫过全页"; return 1; }
    ok "$seg :: 顶到底走位完成，不同状态 $states 屏"
}

# ---- 段 1：空账本态（意图框 + BYOK 配置面 + 录制/AI 区 + 模板钮） ----
$ADB shell am start -f 536870912 -n com.anytouch.app/.MainActivity --ez keep_fg true >/dev/null 2>&1
sleep 3
walk home || fail=1

# ---- 段 2：装载模板后的步序账面（逐行 type·线索·目标 + Rename/Up/Down/Delete） ----
$ADB shell am start -f 536870912 -n com.anytouch.app/.MainActivity --ez keep_fg true --es template_load photos_cleanup >/dev/null 2>&1
sleep 3
$ADB shell am start -f 536870912 -n com.anytouch.app/.MainActivity --ez keep_fg true >/dev/null 2>&1
sleep 2
walk ledger || fail=1

# ---- 段 3：手动补步骤面板展开态（S5-e 要求 3 / 三裁③那一层的上屏文案） ----
# 面板只能由屏上那一枚 "+ Step" 点开（注入通道不开面板，它是编辑入口不是判据通道），
# 而"折叠线以下不进无障碍树"这条在这里同样成立：页顶那一屏只有意图框，账本行要到再往下一屏。
# 所以先"从顶逐屏找那一格"，找到了才模拟手指点它——只在页顶 dump 一次就判"屏上没有"，
# 红的是读数器（s5e 首/二轮 F14a 正是这么假红的，同批一起修）。
$ADB shell am start -f 536870912 -n com.anytouch.app/.MainActivity --ez keep_fg true --es template_load photos_cleanup >/dev/null 2>&1
sleep 3
to_top
idx3=0; xy3=""
while [ $idx3 -lt 8 ]; do
    idx3=$((idx3 + 1))
    dump_to insert-probe-$idx3.xml
    xy3=$(python scripts/tap_node.py "$OUT/insert-probe-$idx3.xml" "\+ Step" 2>/dev/null)
    [ -n "$xy3" ] && break
    swipe 500 1800 500
done
if [ -n "$xy3" ]; then
    # 点亮判据不认"我点了"，只认产品自己的状态变化：面板那一格的 testTag 上屏才算开。
    # 与 s5e 的 tap_open 同源（run3 F14b 血账）：`input tap` 在自家 Compose 面比 dump 报的格心
    # 低约 85px，裸点报的坐标永远开不了面板——四档内点不亮记的是通道红，绝不记成"屏上没有"。
    x3=${xy3% *}; y3=${xy3#* }; panel_on=0
    for off in 85 0 45 130; do
        $ADB shell input tap "$x3" "$((y3 - off))" >/dev/null 2>&1; sleep 2
        dump_to panel-check-off$off.xml
        if grep -q "step_insert_panel_" "$OUT/panel-check-off$off.xml"; then panel_on=1; break; fi
    done
    if [ "$panel_on" = "1" ]; then
        walk insert "Adds one step at position" "node tree, so there is" || fail=1
    else
        bad "段3 :: + Step 在树里却四档内点不亮面板（注入通道与 Compose 命中层对不上，测试通道红，不是产品）"
    fi
else
    # 进不去面板就明写这一段没扫：整页两段全绿不等于第三段没漏（假绿血账同族）
    bad "段3 :: 从页顶逐屏翻 8 屏仍没读到 + Step 那一格（面板没展开，这一层的上屏文案本次未扫）"
fi

out=$(cjk_scan_all); rc=$?
if [ $rc -ne 0 ]; then bad "上屏出现中文节点"; printf '%s\n' "$out" | sed 's/^/          /';
else ok "上屏 CJK 扫描 $out"; fi

log "上屏文案去重清单（供人工通读）：$OUT/on-screen-strings.txt"
log "原始 dump 与截图：$OUT"
[ $fail -eq 0 ] && echo "UI-ENGLISH-SWEEP PASS (0 CJK on screen)" || echo "UI-ENGLISH-SWEEP FAIL"
exit $fail
