#!/usr/bin/env python3
"""S5-f 发卡工具：`ActivationCode.issue()` 的同号镜像（老板出码 + 脚本取码）。

为什么两边都要有一份：码要能**在设备之外**被造出来（老板给买家发货、脚本给自己激活），
而两份实现一旦漂开，最先发现的人是被发了枚不认的码的买家。所以本脚本每次运行都先把自己的
golden 表逐字对一遍（下面 GOLDEN/ANCHORS），再对不出就拒绝出码——红在这里，不红在客户那里。

口径唯一真值：`orders/ANYTOUCH-S5f-activation-code-APPENDIX.md` §1（裁 2 定长 18 位、裁 3 定校验位细则）。
码不是秘密：本仓公开 + GPLv3，算法就在源码里（裁 1 老板亲选"照字面做·装饰性锁"）。

用法：
  python scripts/activation-code.py A1B2C3D4E5      # 交回 10 位自由正文，打印完整码
  python scripts/activation-code.py --random 3      # 出 3 枚随机码（每行一枚）
  python scripts/activation-code.py --verify ANY-PRO9-FAN8-B1CE   # 校验一枚码，退 0=通过
  python scripts/activation-code.py --check         # 只跑自检（golden 表 + 锚点），打印 OK
"""
import re
import secrets
import sys

# 与 Kotlin 侧 ActivationCode.kt 同一张表，逐字（附录 §1）
GOLDEN = [
    ("A1B2C3D4E5", "ANY-A1B2-C3D4-E5NX"),
    ("0000000000", "ANY-0000-0000-00WW"),
    ("ZZZZZZZZZZ", "ANY-ZZZZ-ZZZZ-ZZMM"),
    ("9A8B7C6D5E", "ANY-9A8B-7C6D-5EFN"),
    ("PRO9FAN8B1", "ANY-PRO9-FAN8-B1CE"),
]
# 校验字母锚点：chr(ord(c) % 26 + 65)
ANCHORS = {"A": "N", "Z": "M", "0": "W", "9": "F", "1": "X"}

PREFIX = "ANY"
SEGMENT_LEN = 4
SEGMENT_COUNT = 3
FREE_BODY_LENGTH = SEGMENT_COUNT * SEGMENT_LEN - 2          # 10：b1..b10
CANONICAL_LENGTH = len(PREFIX) + SEGMENT_COUNT * SEGMENT_LEN + SEGMENT_COUNT  # 18：三枚连字符
BODY_RE = re.compile(r"[A-Z0-9]")
CANONICAL_RE = re.compile(r"ANY-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}")


def checksum_letter(source: str) -> str:
    return chr(ord(source) % 26 + 65)


def normalize(raw: str) -> str:
    """去首尾空白 → 转大写 → 去掉内部所有空白。除此之外一个字符都不猜（与 Kotlin 同一条容错边界）。"""
    return "".join(raw.strip().upper().split())


def issue(free_body: str) -> str:
    body = normalize(free_body)
    if len(body) != FREE_BODY_LENGTH:
        raise ValueError(f"free body must be {FREE_BODY_LENGTH} chars, got {len(body)}")
    if not all(BODY_RE.match(c) for c in body):
        raise ValueError(f"free body has a dirty char: {body}")
    tail = checksum_letter(body[0]) + checksum_letter(body[1])
    return (
        f"{PREFIX}-{body[0:4]}-{body[4:8]}-{body[8:10]}{tail}"
    )


def verify(raw: str) -> str:
    """返回 'UNLOCKED' 或拒因档名（与 ActivationVerdict 逐字同名，脚本据此断言档位）。"""
    code = normalize(raw)
    if not code:
        return "EMPTY"
    if code[: len(PREFIX)] != PREFIX:
        return "BAD_PREFIX"
    if len(code) != CANONICAL_LENGTH:
        return "BAD_LENGTH"
    if code[3] != "-" or code[8] != "-" or code[13] != "-":
        return "BAD_SEPARATOR"
    body = code[4:8] + code[9:13] + code[14:]
    if not all(BODY_RE.match(c) for c in body):
        return "BAD_CHARSET"
    if body[FREE_BODY_LENGTH] != checksum_letter(body[0]) or body[FREE_BODY_LENGTH + 1] != checksum_letter(body[1]):
        return "CHECKSUM"
    return "UNLOCKED" if CANONICAL_RE.fullmatch(code) else "BAD_CHARSET"


def tail_for_display(raw: str) -> str:
    return normalize(raw)[-4:]


def self_check() -> None:
    """每次运行都跑：出码/验码/锚点/脏输入四条边，任何一条不成立就拒绝继续（退 2）。"""
    assert CANONICAL_LENGTH == 18, f"长度口径漂了：{CANONICAL_LENGTH}（裁 2 定死 18）"
    for free_body, code in GOLDEN:
        assert issue(free_body) == code, f"发卡与表不符：{free_body} → {issue(free_body)} ≠ {code}"
        assert verify(code) == "UNLOCKED", f"表里的码自己验不过：{code}"
        assert verify(code.lower()) == "UNLOCKED", f"小写不容：{code}"
        assert verify(f"  {code} \n") == "UNLOCKED", f"粘贴空白不容：{code}"
        assert tail_for_display(code) == code[-4:], "回显不是末四位"
    for source, expect in ANCHORS.items():
        # 自由正文 = b1(变量) + 9 位填充；完整码 index 16 = b11（认 b1）、17 = b12（认 b2='1'→X）
        code = issue(source + "1" * (FREE_BODY_LENGTH - 1))
        assert code[16] == expect, f"{source} 的校验字母不是 {expect}：{code}"
        assert code[17] == "X", f"b2 恒为 1，b12 该是 X：{code}"
        assert verify(code) == "UNLOCKED"
        broken = code[:16] + ("O" if expect != "O" else "N") + code[17:]
        assert verify(broken) == "CHECKSUM", f"改掉的校验位没被认出：{broken}"
    # 拒因档各自可达（六档少一档，App 里那句提示就是死代码）
    for raw, expect in [
        ("", "EMPTY"),
        ("XNY-A1B2-C3D4-E5NX", "BAD_PREFIX"),
        ("ANY-A1B2-C3D4-E5N", "BAD_LENGTH"),
        ("ANY_A1B2_C3D4_E5NX", "BAD_SEPARATOR"),
        ("ANY-A1B#-C3D4-E5NX", "BAD_CHARSET"),
        ("ANY-A1B2-C3D4-E5NY", "CHECKSUM"),
    ]:
        assert verify(raw) == expect, f"{raw!r} 应当落 {expect}，实落 {verify(raw)}"
    for dirty in ("A1B2C3D4E", "A1B2C3D4E5F", "A1B2C3D4E#"):
        try:
            issue(dirty)
        except ValueError:
            continue
        raise AssertionError(f"脏正文没被发卡口拒掉：{dirty}")


def main(argv):
    try:
        self_check()
    except AssertionError as err:
        print(f"SELF-CHECK FAILED: {err}", file=sys.stderr)
        return 2
    if not argv or argv[0] == "--check":
        print("OK")
        return 0
    if argv[0] == "--verify":
        verdict = verify(argv[1]) if len(argv) > 1 else "EMPTY"
        print(verdict)
        return 0 if verdict == "UNLOCKED" else 1
    if argv[0] == "--random":
        count = int(argv[1]) if len(argv) > 1 else 1
        alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        for _ in range(count):
            print(issue("".join(secrets.choice(alphabet) for _ in range(FREE_BODY_LENGTH))))
        return 0
    print(issue(argv[0]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
