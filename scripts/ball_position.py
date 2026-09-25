#!/usr/bin/env python3
"""悬浮球位置读数（V-1 断言用）：从截图里找球那块**实心圆盘**，报它的水平中心占比**与它此刻该是哪一档颜色**。

为什么不用 uiautomator dump：球是服务侧 TYPE_ACCESSIBILITY_OVERLAY 的纯 View（无 testTag），
而且 dump 会挤掉自家无障碍服务（设备实证），为了读一个像素位置去冒整条链假红的险不值。

**球的颜色就是状态灯**（`OverlayUi.kt:121`：`if (recording) 0xCCB71C1C else 0xCC1B5E20`，整只视图再乘 alpha=0.7）：
静止=深绿、录制中=深红、执行中另有一隻停止球（同红色系）。
首版只扫绿带，于是"正在录制"这一档压根读不到（设备实证 09-24：录制中截图绿像素=0、
半透明深红实测落在 (205,96,96) 一带）——那不是产品没挂球，是判据只看得到一半状态。现在两档都扫，并**报出读到的到底是哪一档**：
调用方按自己的前置断言状态，读到"绿球却声称在录制"这种自相矛盾的组合时能当场显形，而不是换个底色就静默漏判。

第二版（09-25 S5-f 判据 10 实测）：**"整屏同类色像素求均值当球心"这一条不成立**。上一支脚本留在屏上的
那条红色拒因长文案（`Saved task "f16dirty" step 1 is type=swipe…`，实测色 (179,38,30)，横铺 x=46..538）
被一起算进重心，读数从 0.903 掉到 0.639 → U13 判红，而球其实一直钉在右缘（同一张图里圆盘带 x=878..1066）。
假红与假绿同罪，所以这里改的是**定位法**不是**判据**：先按行找**连续宽段**（圆盘一行能连出 60px 以上，
字笔画连不出来），再按行聚成团，取面积最大的那一团当球；屏上其余同类色一律计入 `discarded_*` 留痕。
判据本身一字未松：中心横占比仍须 >0.75，找不到圆盘状的团仍然 exit 2（宁可响亮失败，不返回 0 骗过断言）。
"""
import sys
from PIL import Image

MIN_MATCHED = 40        # 低于此匹配像素数=不算读到球（噪声/零星红字不许冒充球）
MIN_RUN_PX = 60         # 一"行"里连续同类色至少这么宽才算圆盘的弦（字笔画远窄于此）
MAX_ROW_GAP = 24        # 相邻弦的行距上限：超过就断成两团
MAX_CENTER_SHIFT = 90   # 相邻弦的圆心横移上限：圆盘边缘每行只挪一点点


def is_green(p):
    r, g, b = p
    return 40 <= g <= 150 and r <= 110 and b <= 110 and g >= r + 25 and g >= b + 25


def is_red(p):
    # 半透明深红 0xCCB71C1C × alpha 0.7 叠在白底上实测 (205,96,96)：红通道要显著压过绿蓝，
    # 但不要求"暗"——把 alpha 混合后的亮红算进来，否则这一档永远读不到（首版的错处）。
    r, g, b = p
    return r >= 140 and g <= 150 and b <= 160 and r >= g + 60 and r >= b + 45


def blobs(xs_by_row, step):
    """行→该行连续段（宽度≥MIN_RUN_PX）→按行距与圆心平移聚团；返回按弦数降序的团（每团=一串 (center,count)）。"""
    chords = []  # (y, center, count, x_lo, x_hi)
    for y, row in xs_by_row:
        runs, start, prev = [], None, None
        for x in row:
            if start is None:
                start = prev = x
                continue
            if x - prev > step * 2:  # 采样步长 2，容一档=4px 的空隙仍算同段
                if prev - start >= MIN_RUN_PX - step:
                    runs.append((start, prev))
                start = prev = x
            else:
                prev = x
        if start is not None and prev - start >= MIN_RUN_PX - step:
            runs.append((start, prev))
        for lo, hi in runs:
            chords.append((y, (lo + hi) / 2.0, hi - lo + step, lo, hi))
    chords.sort()
    groups = []  # each: list of chords
    for c in chords:
        placed = False
        for g in groups:
            last = g[-1]
            if c[0] - last[0] <= MAX_ROW_GAP and abs(c[1] - last[1]) <= MAX_CENTER_SHIFT:
                g.append(c)
                placed = True
                break
        if not placed:
            groups.append([c])
    groups.sort(key=lambda g: -sum(x[2] for x in g))
    return groups


def collect(px, w, h, step, pred):
    by_row = []
    total = 0
    for y in range(0, h, step):
        row = [x for x in range(0, w, step) if pred(px[x, y])]
        total += len(row)
        if row:
            by_row.append((y, row))
    return by_row, total


def main():
    img = Image.open(sys.argv[1]).convert("RGB")
    w, h = img.size
    px = img.load()
    step = 2
    g_rows, green_n = collect(px, w, h, step, is_green)
    r_rows, red_n = collect(px, w, h, step, is_red)
    if green_n >= MIN_MATCHED and green_n >= red_n:
        state, rows, others = "idle", g_rows, red_n
    elif red_n >= MIN_MATCHED:
        state, rows, others = "recording", r_rows, green_n
    else:
        print("BALL_NOT_FOUND green=%d red=%d min=%d size=%dx%d" % (green_n, red_n, MIN_MATCHED, w, h))
        return 2
    groups = blobs(rows, step)
    if not groups:
        # 同类色铺满屏却没有圆盘状的团＝屏上只有红字没有球（例：上一支脚本的拒因横幅），
        # 这不是"球在左缘"，是"球不在"——响亮失败，别让重心读数冒充位置。
        print("BALL_NOT_FOUND %s=%d 但无宽度≥%dpx 的连续色带（屏上无圆盘状球体）size=%dx%d"
              % ("red" if state == "recording" else "green", others, MIN_RUN_PX, w, h))
        return 2
    ball = groups[0]
    area = sum(c[2] for c in ball)
    if area < MIN_MATCHED:
        print("BALL_NOT_FOUND %s=%d 最大色团仅 %d（低于 MIN_MATCHED=%d，零星宽条不许冒充球）size=%dx%d"
              % ("red" if state == "recording" else "green", area, area, MIN_MATCHED, w, h))
        return 2
    center = sum(c[1] * c[2] for c in ball) / float(area)
    lo = min(c[3] for c in ball)
    hi = max(c[4] for c in ball)
    print("BALL_STATE=%s BALL_X_RATIO=%.3f matched=%d green=%d red=%d size=%dx%d min=%d max=%d y=[%d,%d] discarded=%d"
          % (state, center / w, area, green_n, red_n, w, h, lo, hi, ball[0][0], ball[-1][0],
             (red_n if state == "recording" else green_n) - area))
    return 0


if __name__ == "__main__":
    sys.exit(main())
