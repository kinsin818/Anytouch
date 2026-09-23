#!/usr/bin/env python3
"""悬浮球位置读数（V-1 断言用）：从截图里找录制球那块深绿底，报它的水平中心占比。

为什么不用 uiautomator dump：球是服务侧 TYPE_ACCESSIBILITY_OVERLAY 的纯 View（无 testTag），
而且 dump 会挤掉自家无障碍服务（设备实证），为了读一个像素位置去冒整条链假红的险不值。
深绿底 = 0xCC1B5E20 再乘视图 alpha，落在"绿通道明显高于红蓝且不发白"的窄带里；
停止球是深红，不会误命中。找不到即 exit 2（宁可响亮失败，不返回 0 骗过断言）。
"""
import sys
from PIL import Image

img = Image.open(sys.argv[1]).convert("RGB")
w, h = img.size
px = img.load()

xs = []
for y in range(0, h, 2):
    for x in range(0, w, 2):
        r, g, b = px[x, y]
        if 40 <= g <= 150 and r <= 110 and b <= 110 and g >= r + 25 and g >= b + 25:
            xs.append(x)

if len(xs) < 40:
    print(f"BALL_NOT_FOUND matched={len(xs)} size={w}x{h}")
    sys.exit(2)

center = sum(xs) / len(xs)
print(f"BALL_X_RATIO={center / w:.3f} matched={len(xs)} size={w}x{h} min={min(xs)} max={max(xs)}")
