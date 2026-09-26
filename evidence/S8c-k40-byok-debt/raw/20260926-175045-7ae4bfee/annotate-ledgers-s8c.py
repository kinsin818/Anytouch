# -*- coding: utf-8 -*-
# S8c 就地标注（只追加，不抹旧陈述）：METRICS 的 `byok-smoke` 7 行欠格遗留行 + STATUS 的 S5 交付面行
import io

NOTE = (
    "【S8c 复验·09-26 K40 到场未清：靶机 7ae4bfee 在线（真机 M2012K11AC），机上包原为 0.1.0-s1/versionCode 1 不合格、"
    "已按工单装 GitHub 发布件（匿名直链回对 HTTP 200、9,389,205B、md5 d61b9867fcdf164d73ed236ce61f76e1；装后机上 base.apk 同值，"
    "versionName 1.0.6/versionCode 7，INTERNET granted=true），无障碍已绑（Anytouch Executor capabilities=33）；"
    "但只读探屏见 byok_key_tail=`Key saved on this device: ***JgYp`⇒ 带钥档下 byok-smoke.sh 第一格外呼点即 E5a 真编译，"
    "本批令“一律不走真请求”⇒ **未起跑、一格未绿、仍欠 7 格**；真模型请求消耗 0，S4 额度仍 6/8 未动（logcat S3SMOKE compile 计数 0）。"
    "落点 `evidence/S8c-k40-byok-debt/`。新算术：带钥组一轮到底=3 次真请求（E5a/E8/E10）＞剩余额度 2 ⇒ “下一次真机批清完 7 行”"
    "在额度不动的边界内结构上不成立；E9c 两行本身零消耗（install -r 同构建+现读尾 4 位），够不到只因排在 E5 之后 ⇒ "
    "剩两条路：主窗裁“判据只加不减”的零消耗档/重排（本窗未改脚本），或老板手指点一次面板“清除本机凭据”走无钥腿"
    "（点完可用 scripts/byok-credential-inject.sh 零消耗填回，尾 4 仍 JgYp）。三路径算术见 debt-ledger.md §C。】"
)
MARK = "【S8c 复验·09-26"

def do(path, needle, pipe_cell):
    lines = io.open(path, encoding="utf-8").read().split("\n")
    hits = 0
    for i, l in enumerate(lines):
        if needle in l and MARK not in l:
            if pipe_cell:
                body = l.rstrip()
                assert body.endswith("|"), "line %d not a table cell" % (i + 1)
                lines[i] = body[:-1].rstrip() + " " + NOTE + " |"
            else:
                lines[i] = l.rstrip("\r") + " " + NOTE
            hits += 1
    io.open(path, "w", encoding="utf-8", newline="\n").write("\n".join(lines))
    print("%s annotated_lines=%d" % (path, hits))
    return hits

h1 = do("orders/METRICS.md", "`byok-smoke` 7 \u884c\u6b20\u683c\u4e0e K40 \u6b20\u683c\uff08#50\uff09", False)
h2 = do("STATUS.md", "| S5 \u4ea4\u4ed8\u9762\u4e09\u7f3a\uff08\u4e0a\u67b6\u524d\u7f6e\u95e8\uff09", True)
assert h1 == 2, "METRICS hits expected 2, got %d" % h1
assert h2 == 1, "STATUS hits expected 1, got %d" % h2
print("DONE")
