#!/usr/bin/env python3
"""测试通道：从高危确认面板截屏里定位「Confirm and run」按钮中心，替代盲投固定坐标。

为什么要有这个文件（S5-c 批 F-A 血账）：面板钮原先按一组按 1080x2400 校准的死坐标点击，
文案一改（中→英、英→人话）按钮就随面板宽高位移，死坐标当场变假红。截屏判据既然已经在拍，
就直接从截屏量，不再用手感数字。

口径：面板底部并排两枚 Material 浅灰按钮 = 屏上成块的浅色带（>=60px 高，文字行只有十几 px），
按列以 <=6px 间隙聚簇后恰好两簇；取右簇中心。找不到就非零退出——调用侧必须如实记红，
禁止退回旧常量冒充命中。已在 v1.0.1 的 8 张面板实截上过对拍（见 evidence/S5/raw/s5a-panel-*.png）。
"""
import sys
from PIL import Image

STEP = 2
GAP = 6
MIN_BTN_W = 60


def light(c):
    return c[0] >= 185 and c[1] >= 185 and c[2] >= 185 and (max(c) - min(c)) <= 22


def row_bands(rows, thresh):
    out, cur = [], None
    for y, n in rows:
        if n > thresh:
            cur = [y, y] if cur is None else [cur[0], y]
        elif cur:
            out.append(tuple(cur))
            cur = None
    if cur:
        out.append(tuple(cur))
    return out


def button_groups(px, y, width):
    xs = [x for x in range(width) if light(px[x, y])]
    groups, g = [], None
    for x in xs:
        if g is None:
            g = [x, x]
        elif x - g[1] <= GAP:
            g[1] = x
        else:
            groups.append(tuple(g))
            g = [x, x]
    if g:
        groups.append(tuple(g))
    return [q for q in groups if q[1] - q[0] >= MIN_BTN_W]


def dark_px(px, width, height):
    """面板底色（0xF2212121 半透明深灰）计数：≈0 = 这张截屏压根没拍到面板内容帧，
    与"拍到了但按钮判据不认"是两回事，必须让调用侧的 RAW 分得清。"""
    n = 0
    for y in range(int(0.25 * height), int(0.85 * height), STEP):
        for x in range(0, width, STEP):
            c = px[x, y]
            if all(abs(c[i] - 0x21) < 12 for i in range(3)):
                n += 1
    return n


def main():
    if len(sys.argv) != 2:
        print("usage: find-panel-confirm.py <panel.png>", file=sys.stderr)
        return 2
    im = Image.open(sys.argv[1]).convert("RGB")
    width, height = im.size
    px = im.load()
    rows = [(y, sum(1 for x in range(0, width, STEP) if light(px[x, y]))) for y in range(0, height, STEP)]
    thresh = (width // STEP) * 0.12
    hits = []
    for y0, y1 in row_bands(rows, thresh):
        ymid = (y0 + y1) // 2
        if y1 - y0 < 60 or not (0.30 * height <= ymid <= 0.85 * height):
            continue
        groups = button_groups(px, ymid, width)
        if len(groups) == 2:
            hits.append((ymid, groups, y0, y1))
    if not hits:
        d = dark_px(px, width, height)
        print(
            "no-two-button-band-found dark-panel-px=%d%s"
            % (d, " (dark~=0: this frame has no panel content at all, i.e. screencap raced the overlay)" if d < 5000 else ""),
            file=sys.stderr,
        )
        return 1
    ymid, groups, y0, y1 = hits[-1]
    right = groups[1]
    print(f"{(right[0] + right[1]) // 2} {ymid}")
    print(
        f"# band y {y0}..{y1} center {ymid} | left {groups[0]} right {right} | screen {width}x{height} | dark-panel-px {dark_px(px, width, height)}",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
