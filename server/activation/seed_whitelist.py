#!/usr/bin/env python3
"""把整码从 **stdin** 灌进白名单库（一行一枚，可带 ` kind` 后缀）。

为什么走 stdin 不走 argv：argv 会出现在那台机的进程表里（任何一次 `ps` 都能捞走一枚在册码，
而一枚在册码在公开仓口径下等于一张免费许可证）。

本脚本**永不打印整码**，只打尾四位和计数；也不写日志文件。它是部署工具，不是运行时组件。

用法（服务器侧）：
  python3 seed_whitelist.py < codes.txt
  库路径由 ACTIVATION_DB 覆写，缺省 /var/lib/anytouch-activate/activation.db。
"""
import os
import re
import sqlite3
import sys
import time

DB_PATH = os.environ.get("ACTIVATION_DB", "/var/lib/anytouch-activate/activation.db")
CODE_RE = re.compile(r"^ANY-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$")
KINDS = ("buyer", "staging")


def main():
    rows, seen = [], set()
    for lineno, raw in enumerate(sys.stdin, 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        code = parts[0].upper()
        kind = parts[1] if len(parts) > 1 else "buyer"
        if not CODE_RE.match(code):
            raise SystemExit("line %d: malformed code (tail=%s)" % (lineno, code[-4:]))
        if kind not in KINDS:
            raise SystemExit("line %d: kind must be one of %s" % (lineno, KINDS))
        if code in seen:
            raise SystemExit("line %d: duplicate input code (tail=%s)" % (lineno, code[-4:]))
        seen.add(code)
        rows.append((code, kind, time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())))
    if not rows:
        raise SystemExit("stdin empty — refusing to open a whitelist with no seats")

    # 建表与库路径都只认 app.py 那一份：两处各写一遍 CREATE TABLE / 各读一遍环境变量，
    # 漂开的那天就是 seed 往不存在的表里插、或往另一条路径的库里插。
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import app
    app.ensure_schema()
    conn = sqlite3.connect(app.DB_PATH)
    conn.execute("BEGIN IMMEDIATE")
    inserted = 0
    for code, kind, created in rows:
        cur = conn.execute(
            "INSERT OR IGNORE INTO codes(code, kind, created_at) VALUES(?,?,?)", (code, kind, created)
        )
        inserted += cur.rowcount
    conn.execute("COMMIT")
    counts = conn.execute("SELECT kind, COUNT(*) FROM codes GROUP BY kind ORDER BY kind").fetchall()
    conn.close()
    stray = [k for k, _ in counts if k not in KINDS]
    if stray:
        raise SystemExit("库里有未知 kind：%s（先人工查，别让它带着脏 kind 开门）" % stray)
    print("seeded=%d total=%s db=%s" % (inserted, dict(counts), app.DB_PATH))


if __name__ == "__main__":
    main()
