#!/usr/bin/env python3
"""S5 冒烟测试通道助手：从 uiautomator dump 里按 text/content-desc 正则找节点，打印其中心点。

坐标只住脚本侧（测试通道"模拟手指"），产品资产/执行器词表零坐标——红线 D 不受触碰。
用法: python tap_node.py <xml_file> <regex>   → stdout: "x y"；找不到退 1。
"""
import re
import sys
import xml.etree.ElementTree as ET

def main():
    xml_file, pattern = sys.argv[1], sys.argv[2]
    rx = re.compile(pattern)
    root = ET.parse(xml_file).getroot()
    for node in root.iter('node'):
        # 分别对 text / content-desc 做 search：拼接串会让 ^...$ 锚点被另一字段污染（实测教训）
        if rx.search(node.get('text') or '') or rx.search(node.get('content-desc') or ''):
            b = node.get('bounds')
            m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', b or '')
            if not m:
                continue
            x1, y1, x2, y2 = map(int, m.groups())
            if x2 <= x1 or y2 <= y1:
                continue
            print(f"{(x1 + x2) // 2} {(y1 + y2) // 2}")
            return 0
    return 1

if __name__ == '__main__':
    sys.exit(main())
