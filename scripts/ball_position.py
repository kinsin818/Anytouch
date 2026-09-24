#!/usr/bin/env python3
"""悬浮球位置读数（V-1 断言用）：从截图里找球那块纯色底，报它的水平中心占比**与它此刻该是哪一档颜色**。

为什么不用 uiautomator dump：球是服务侧 TYPE_ACCESSIBILITY_OVERLAY 的纯 View（无 testTag），
而且 dump 会挤掉自家无障碍服务（设备实证），为了读一个像素位置去冒整条链假红的险不值。

**球的颜色就是状态灯**（`OverlayUi.kt:121`：`if (recording) 0xCCB71C1C else 0xCC1B5E20`，整只视图再乘 alpha=0.7）：
静止=深绿、录制中=深红、执行中另有一隻停止球（同红色系）。
首版只扫绿带，于是"正在录制"这一档压根读不到（设备实证 09-24：录制中截图绿像素=0、
半透明深红实测落在 (205,96,96) 一带）——那不是产品没挂球，是判据只看得到一半状态。
现在两档都扫，并**报出读到的到底是哪一档**：调用方按自己的前置断言状态，
读到"绿球却声称在录制"这种自相矛盾的组合时能当场显形，而不是换个底色就静默漏判。

找不到即 exit 2（宁可响亮失败，不返回 0 骗过断言）。
"""
import sys
from PIL import Image

MIN_MATCHED = 40  # 低于此匹配像素数=不算读到球（噪声/零星红字不许冒充球）


def is_green(p):
    r, g, b = p
    return 40 <= g <= 150 and r <= 110 and b <= 110 and g >= r + 25 and g >= b + 25


def is_red(p):
    # 半透明深红 0xCCB71C1C × alpha 0.7 叠在白底上实测 (205,96,96)：红通道要显著压过绿蓝，
    # 但不要求"暗"——把 alpha 混合后的亮红算进来，否则这一档永远读不到（首版的错处）。
    r, g, b = p
    return r >= 140 and g <= 150 and b <= 160 and r >= g + 60 and r >= b + 45


def main():
    img = Image.open(sys.argv[1]).convert("RGB")
    w, h = img.size
    px = img.load()
    gxs = []
    rxs = []
    for y in range(0, h, 2):
        for x in range(0, w, 2):
            p = px[x, y]
            if is_green(p):
                gxs.append(x)
            elif is_red(p):
                rxs.append(x)
    green_n, red_n = len(gxs), len(rxs)
    if green_n >= MIN_MATCHED and green_n >= red_n:
        state, xs = "idle", gxs
    elif red_n >= MIN_MATCHED:
        state, xs = "recording", rxs
    else:
        print("BALL_NOT_FOUND green=%d red=%d min=%d size=%dx%d" % (green_n, red_n, MIN_MATCHED, w, h))
        return 2
    center = float(sum(xs)) / len(xs)
    print("BALL_STATE=%s BALL_X_RATIO=%.3f matched=%d green=%d red=%d size=%dx%d min=%d max=%d"
          % (state, center / w, len(xs), green_n, red_n, w, h, min(xs), max(xs)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
