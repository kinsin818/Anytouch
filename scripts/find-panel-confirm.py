#!/usr/bin/env python3
"""测试通道：从高危确认面板截屏里定位「Confirm and run」按钮中心，替代盲投固定坐标。

为什么要有这个文件（S5-c 批 F-A 血账）：面板钮原先按一组按 1080x2400 校准的死坐标点击，
文案一改（中→英、英→人话）按钮就随面板宽高位移，死坐标当场变假红。截屏判据既然已经在拍，
就直接从截屏量，不再用手感数字。

口径（S5-d 批改写过一遍，两版都在册）：先按面板底色（0xF2212121 半透明深灰）圈出**面板矩形**，
再只在矩形内部找底部那一条浅色带（并排两枚 Material 浅灰按钮），按列以 <=6px 间隙聚簇后恰好两簇，
取右簇中心。找不到就非零退出——调用侧必须如实记红，禁止退回旧常量冒充命中。

为什么不能像旧版那样"整屏找浅色带"：旧版默认**页面底色是深的**（Photos 黑底），浅色带只可能是按钮。
S5-d 的高危格长在 Settings 搜索页（**浅底**），整屏每一行都是浅色带，带与带连成一片，
"恰好两簇"永远不成立——09-25 实测三拍皆 `no-two-button-band-found` 而 dark-panel-px=99295
（面板明明在场）。判据错在"整屏"这一层，不在阈值：所以改成先圈面板再在里面找，
而不是把阈值调松（调松=在别的页面上会把页面自身读成按钮＝假命中，比假红更贵）。
对拍：18 张历史面板实截（evidence/S5/raw/s5a-panel-*.png：v1.0.1 八张逐字 (685,1282)、
v1.0.2 八张逐字 (730,1391)、轮 4 那张空帧如实 rc=1 失败）+ 本版新增 8 张浅底面板实截
（s5d-L4/L5-panel-*.png，逐张同点 (730,1365)），逐张比坐标的账见
evidence/S5/find-panel-confirm-light-page-regression.md。
"""
import sys
from PIL import Image

STEP = 2
GAP = 6
MIN_BTN_W = 60
MIN_BAND_H = 40
DARK = 0x21
DARK_TOL = 12


def light(c):
    return c[0] >= 185 and c[1] >= 185 and c[2] >= 185 and (max(c) - min(c)) <= 22


def is_dark(c):
    return all(abs(c[i] - DARK) < DARK_TOL for i in range(3))


def runs(items, keep):
    """把 (坐标, 计数) 序列里满足 keep(计数) 的**连续坐标**串成 (起,止) 段。
    行方向与列方向共用这一份聚簇逻辑；返回的是坐标段，不是计数段（第一版把计数当坐标串，
    于是 panel_rect 永远圈不出矩形——离线对拍 20 张全 rc=1 才暴露）。"""
    out, cur = [], None
    for coord, n in items:
        if keep(n):
            cur = [coord, coord] if cur is None else [cur[0], coord]
        elif cur:
            out.append(tuple(cur))
            cur = None
    if cur:
        out.append(tuple(cur))
    return out


def panel_rect(px, width, height):
    """面板矩形：深色行最长的一段 + 该段里深色列的首尾。返回 (x0, y0, x1, y1) 或 None。"""
    rows = [(y, sum(1 for x in range(0, width, STEP) if is_dark(px[x, y]))) for y in range(0, height, STEP)]
    sample_cols = len(range(0, width, STEP))
    bands = runs(rows, lambda n: n > sample_cols * 0.25)
    if not bands:
        return None
    y0, y1 = max(bands, key=lambda b: b[1] - b[0])
    ys = [y for y, _ in rows if y0 <= y <= y1]
    cols = [(x, sum(1 for y in ys if is_dark(px[x, y]))) for x in range(0, width, STEP)]
    cbands = runs(cols, lambda n: n > len(ys) * 0.25)
    if not cbands:
        return None
    cx0, cx1 = max(cbands, key=lambda b: b[1] - b[0])
    return cx0, ys[0], cx1, ys[-1]


def clusters(px, y, x0, x1):
    """按 x 坐标聚簇：间隙 > GAP 才断簇。"""
    xs = [x for x in range(x0, x1 + 1) if light(px[x, y])]
    out, cur = [], None
    for x in xs:
        if cur is None:
            cur = [x, x]
        elif x - cur[1] <= GAP:
            cur[1] = x
        else:
            out.append(tuple(cur))
            cur = None
    if cur:
        out.append(tuple(cur))
    return [g for g in out if g[1] - g[0] >= MIN_BTN_W]


def dark_px(px, width, height):
    """面板底色计数：≈0 = 这张截屏压根没拍到面板内容帧，与"拍到了但按钮判据不认"是两回事，
    必须让调用侧的 RAW 分得清（v1.0.2 轮 4 假红的那条根因）。"""
    n = 0
    for y in range(int(0.25 * height), int(0.85 * height), STEP):
        for x in range(0, width, STEP):
            if is_dark(px[x, y]):
                n += 1
    return n


def main():
    if len(sys.argv) != 2:
        print("usage: find-panel-confirm.py <panel.png>", file=sys.stderr)
        return 2
    im = Image.open(sys.argv[1]).convert("RGB")
    width, height = im.size
    px = im.load()
    rect = panel_rect(px, width, height)
    if rect is None:
        print("no-panel-rect-found dark-panel-px=%d" % dark_px(px, width, height), file=sys.stderr)
        return 1
    x0, y0, x1, y1 = rect
    # 扫描窗：从面板上沿往下，**并且允许越过深色矩形底沿**——按钮那一行几乎全是浅色，
    # 面板底色在它里面只占两侧留白（实测 12%），按"深色行"划出的矩形会正好把它切掉
    # （首版对拍 20 张全红即此）。越过底沿的余量给一屏按钮高度的两倍，不放开整屏。
    inner = range(y0, min(height - STEP, y1 + 2 * (y1 - y0) + 200), STEP)
    span = max(1, x1 - x0)
    rows = [(y, sum(1 for x in range(x0, x1 + 1, STEP) if light(px[x, y]))) for y in inner]
    bands = runs(rows, lambda n: n > (span // STEP) * 0.30)
    hits = []
    for b0, b1 in bands:
        if b1 - b0 < MIN_BAND_H:
            continue
        ymid = (b0 + b1) // 2
        groups = clusters(px, ymid, x0, x1)
        if len(groups) == 2:
            hits.append((ymid, groups, b0, b1))
    if not hits:
        print(
            "no-two-button-band-found dark-panel-px=%d panel-rect=%d,%d-%d,%d"
            % (dark_px(px, width, height), x0, y0, x1, y1),
            file=sys.stderr,
        )
        return 1
    # 取**最靠上**那一段：面板里先出现的就是按钮行，再往下已经是页面背景（整行连成一簇，凑不满"恰好两簇"）
    ymid, groups, b0, b1 = hits[0]
    right = groups[1]
    print(f"{(right[0] + right[1]) // 2} {ymid}")
    print(
        f"# panel rect x {x0}..{x1} y {y0}..{y1} | band y {b0}..{b1} center {ymid} | "
        f"left {groups[0]} right {right} | screen {width}x{height} | dark-panel-px {dark_px(px, width, height)}",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
