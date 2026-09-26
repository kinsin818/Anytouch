#!/usr/bin/env bash
# S5-g 服务器契约测试（主机侧，零设备零 adb）：判据 1（三态）+ 判据 2（绑满即拒·解绑可进）
# + 管理口鉴权 + 幂等重放。TLS 用本地自签证书；本脚本用 `curl -k` 只验**接口逻辑**，
# 证书指纹固定那条判据（判据 8）属 APP 客户端层，另在设备面验，不在这里充数。
# 在册码一律现生成现丢弃：本脚本不落任何码进仓，日志只打尾四位。
set -uo pipefail
cd "$(dirname "$0")/.."

STAMP="$(date +%Y%m%d-%H%M%S)"
WORK=".smoke-tmp/s5g-contract-$STAMP"
RAW=".smoke-tmp/s5g-contract-$STAMP.raw.txt"
mkdir -p "$WORK"
exec > >(sed 's/\x1b\[[0-9;]*m//g' | tee "$RAW") 2>&1

PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  PASS $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  FAIL $1"; }
die()  { echo "  LOUD-FAIL $1"; echo "SCRIPT-RC=2"; exit 2; }
tail4() { echo "${1: -4}"; }

PORT="${PORT:-18443}"
TOKEN="local-test-token-$$"
DB="$WORK/activation.db"

# 解释器名两边不一样：Windows 这台机只有 `python`，Linux 服务器只有 `python3`。
PY="$(command -v python3 || command -v python || true)"
command -v openssl >/dev/null || die "缺 openssl"
command -v curl >/dev/null || die "缺 curl"
[ -n "$PY" ] || die "缺 python 解释器"
"$PY" -c 'import sqlite3,ssl' 2>/dev/null || die "$PY 缺 sqlite3/ssl"
export PYTHONIOENCODING=utf-8
# 残留守卫：本轮的库是新生成的码，若端口上已有服务应答，那起来的就是上一轮的孤儿进程——
# 它对不上本轮任何一枚码，测试会对着假靶跑出满屏红（或更坏：满屏绿）。当场停，不复用。
if curl -sk --max-time 1 "https://127.0.0.1:$PORT/api/health" 2>/dev/null | grep -q '"ok"'; then
  die "端口 $PORT 已有服务在应答（上一轮残留），本轮不放跑"
fi

echo "==> [1/7] 自签证书 + 临时库（SAN=IP:127.0.0.1，仅本地）"
# MSYS_NO_PATHCONV：Git Bash 会把 `-subj /CN=...` 当 Windows 路径改写（实测落成品
# `C:/Program Files/Git/CN=...` 直接报错）；Linux 侧此变量是空操作，两边同一条命令。
MSYS_NO_PATHCONV=1 openssl req -x509 -newkey rsa:2048 -nodes -keyout "$WORK/tls.key" -out "$WORK/tls.crt" \
  -days 2 -subj "/CN=anytouch-activate-test" \
  -addext "subjectAltName=IP:127.0.0.1" >"$WORK/openssl.log" 2>&1 || { cat "$WORK/openssl.log" 2>/dev/null | tail -5; die "证书生成失败"; }
chmod 600 "$WORK/tls.key"

echo "==> [2/7] 白名单 seed：4 枚 staging + 1 枚 buyer（走既有发卡口，同源不另写算法）"
"$PY" scripts/activation-code.py --check >/dev/null || die "发卡口自检红"
"$PY" - "$DB" "$WORK/codes.txt" <<'PYEOF' || die "seed 失败"
import sqlite3, subprocess, sys, time
db, out = sys.argv[1], sys.argv[2]
codes = subprocess.run([sys.executable, "scripts/activation-code.py", "--random", "5"],
                       capture_output=True, text=True, check=True).stdout.split()
assert len(codes) == 5 and len(set(codes)) == 5, f"发卡口给了重码/缺码：{codes}"
conn = sqlite3.connect(db)
conn.execute("CREATE TABLE codes(code TEXT PRIMARY KEY, kind TEXT NOT NULL, created_at TEXT NOT NULL)")
conn.execute("CREATE TABLE bindings(code TEXT NOT NULL, device_hash TEXT NOT NULL, bound_at TEXT NOT NULL, PRIMARY KEY(code, device_hash))")
now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
kinds = ["staging", "staging", "staging", "staging", "buyer"]
for code, kind in zip(codes, kinds):
    conn.execute("INSERT INTO codes VALUES(?,?,?)", (code, kind, now))
conn.commit(); conn.close()
open(out, "w").write("\n".join("%s %s" % (c, k) for c, k in zip(codes, kinds)))
PYEOF
[ -s "$WORK/codes.txt" ] || die "seed 未产出码表"
STG1=$(awk '$2=="staging"{print $1; exit}' "$WORK/codes.txt")
BUYER=$(awk '$2=="buyer"{print $1}' "$WORK/codes.txt")
[ -n "$STG1" ] && [ -n "$BUYER" ] || die "码表里取不到 staging/buyer"

echo "==> [3/7] 起服务（TLS）"
ACTIVATION_DB="$DB" TLS_CERT="$WORK/tls.crt" TLS_KEY="$WORK/tls.key" \
ADMIN_TOKEN="$TOKEN" PORT="$PORT" \
  "$PY" server/activation/app.py > "$WORK/server.log" 2>&1 &
SRV=$!
cleanup() { kill "$SRV" 2>/dev/null; wait "$SRV" 2>/dev/null; rm -rf "$WORK"; }
trap cleanup EXIT
for _ in $(seq 1 40); do
  curl -sk --max-time 2 "https://127.0.0.1:$PORT/api/health" >/dev/null 2>&1 && break
  sleep 0.25
done
curl -sk --max-time 3 "https://127.0.0.1:$PORT/api/health" | grep -q '"ok": true' \
  || { sed -n '1,20p' "$WORK/server.log"; die "服务没起来（或没走 TLS）"; }
echo "  health ok on $PORT (tls)"

post() { curl -sk --max-time 5 -X POST "https://127.0.0.1:$PORT$1" \
  -H 'Content-Type: application/json' -d "$2"; }
field() { "$PY" -c "import json,sys;d=json.loads(sys.argv[1]) or {};print(d.get(sys.argv[2],''))" "$1" "$2"; }
H1="aaaaaaaa11112222"; H2="bbbbbbbb11112222"; H3="cccccccc11112222"; H4="dddddddd11112222"

echo "==> [4/7] 判据 1：三态（invalid / ok / 幂等重放）"
R=$(post /api/activate "{\"code\":\"ANY-0000-0000-00WW\",\"device_hash\":\"$H1\"}")
[ "$(field "$R" reason)" = "invalid" ] && ok "不在册的合法格式码 → invalid" || bad "不在册应答错：$R"
R=$(post /api/activate "{\"code\":\"garbage\",\"device_hash\":\"$H1\"}")
[ "$(field "$R" reason)" = "invalid" ] && ok "结构脏的码 → invalid（同一档，不泄露'在册但格式错'）" || bad "脏码应答错：$R"
R=$(post /api/activate "{\"code\":\"$STG1\",\"device_hash\":\"$H1\"}")
[ "$(field "$R" ok)" = "True" ] && [ "$(field "$R" seats_used)" = "1" ] \
  && ok "在册码首次绑定 → ok seats=1 (tail=$(tail4 "$STG1"))" || bad "首绑应答错：$R"
R=$(post /api/activate "{\"code\":\"$STG1\",\"device_hash\":\"$H1\"}")
[ "$(field "$R" ok)" = "True" ] && [ "$(field "$R" seats_used)" = "1" ] \
  && ok "同码同机重放 → ok 且 seats 仍=1（幂等，不重复占额度）" || bad "幂等破了：$R"

echo "==> [5/7] 判据 2：绑满即拒 + 解绑后可进"
R=$(post /api/activate "{\"code\":\"$STG1\",\"device_hash\":\"$H2\"}")
[ "$(field "$R" ok)" = "True" ] && [ "$(field "$R" seats_used)" = "2" ] && ok "第二台 → ok seats=2" || bad "第二台应答错：$R"
R=$(post /api/activate "{\"code\":\"$STG1\",\"device_hash\":\"$H3\"}")
[ "$(field "$R" ok)" = "False" ] && [ "$(field "$R" reason)" = "seats_full" ] \
  && ok "第三台 → seats_full（军令'最多绑2台'成立）" || bad "第三台没挡住：$R"
R=$(post /api/activate "{\"code\":\"$STG1\",\"device_hash\":\"$H1\"}")
[ "$(field "$R" ok)" = "True" ] && ok "满员后已绑过的机器仍可激活（不被自己锁死）" || bad "老设备被拒：$R"
R=$(post /api/deactivate "{\"code\":\"$STG1\",\"device_hash\":\"$H2\",\"admin_token\":\"$TOKEN\"}")
[ "$(field "$R" ok)" = "True" ] && [ "$(field "$R" seats_used)" = "1" ] && ok "解绑一台 → seats 回 1" || bad "解绑应答错：$R"
R=$(post /api/activate "{\"code\":\"$STG1\",\"device_hash\":\"$H3\"}")
[ "$(field "$R" ok)" = "True" ] && [ "$(field "$R" seats_used)" = "2" ] \
  && ok "解绑后第三台可进（裁1 那条兜底口真有效）" || bad "解绑没放行：$R"

echo "==> [6/7] 并发超绑锁（验 BEGIN IMMEDIATE 那条注释不是写着好看）"
STG_RACE=$(awk '$2=="staging"{print $1}' "$WORK/codes.txt" | sed -n 4p)
[ -n "$STG_RACE" ] || die "缺并发测试专用的第 4 枚 staging 码"
RACE_PIDS=()
for i in $(seq 1 8); do
  # device_hash 必须纯十六进制（服务端 HASH_RE 认 8~64 位 hex）：早先用 "race0001" 那种含 r/t 的
  # 伪哈希会被当脏输入直接拒，测到的就不是并发锁了。
  post /api/activate "{\"code\":\"$STG_RACE\",\"device_hash\":\"fa5e000$i\"}" > "$WORK/race-$i.json" &
  RACE_PIDS+=("$!")
done
# 只等这 8 个 curl：裸 `wait` 会连常驻的服务进程和 `exec > >(sed|tee)` 那条进程替换一起等，
# 两边都永不退出——实测整脚本挂死、raw 一个字不刷（sed 非 tty 时是块缓冲）。
wait "${RACE_PIDS[@]}"
RACE_OK=0; RACE_FULL=0; RACE_OTHER=""
for i in $(seq 1 8); do
  B="$(cat "$WORK/race-$i.json" 2>/dev/null)"
  [ "$(field "$B" ok)" = "True" ] && RACE_OK=$((RACE_OK+1)) && continue
  [ "$(field "$B" reason)" = "seats_full" ] && RACE_FULL=$((RACE_FULL+1)) && continue
  RACE_OTHER="$RACE_OTHER[$i]=$(echo "$B" | tr -d '\n' | cut -c1-60) "
done
echo "  并发应答分布：ok=$RACE_OK seats_full=$RACE_FULL 其他=$((8 - RACE_OK - RACE_FULL))"
[ -n "$RACE_OTHER" ] && echo "  非预期应答原文：$RACE_OTHER"
RACE_SEATS="$("$PY" - "$DB" "$STG_RACE" <<'PYEOF'
import sqlite3, sys
conn = sqlite3.connect(sys.argv[1])
print(conn.execute("SELECT COUNT(*) FROM bindings WHERE code=?", (sys.argv[2],)).fetchone()[0])
conn.close()
PYEOF
)"
[ "$RACE_SEATS" = "2" ] && ok "8 路并发抢 1 码，最终绑定数恰为 2（没超绑）" || bad "并发超绑：库里 seats=$RACE_SEATS"
[ "$RACE_OK" = "2" ] && ok "8 路并发恰好 2 路拿到 ok（不多放）" || bad "并发放行数=$RACE_OK（应为 2）"
[ "$RACE_FULL" = "6" ] && [ $((8 - RACE_OK - RACE_FULL)) -eq 0 ] \
  && ok "落败 6 路逐条明回 seats_full（不是超时/断连/空应答被误当成拒绝）" \
  || bad "落败方拒因不齐：seats_full=$RACE_FULL，其余 $((8 - RACE_OK - RACE_FULL)) 路见上行原文"

echo "==> [7/8] 管理口鉴权 + lease-staging + buyer 段不被租"
R=$(post /api/admin/lease-staging '{"admin_token":"wrong-token"}')
[ "$(field "$R" reason)" = "forbidden" ] && ok "错 token → 403 forbidden" || bad "鉴权没挡住：$R"
R=$(post /api/deactivate "{\"code\":\"$STG1\",\"device_hash\":\"$H1\"}")
[ "$(field "$R" reason)" = "forbidden" ] && ok "缺 token 的解绑 → forbidden（不会被人白嫖解绑）" || bad "缺 token 竟通过：$R"
R=$(post /api/admin/lease-staging "{\"admin_token\":\"$TOKEN\"}")
K=$(field "$R" kind); C=$(field "$R" code)
[ "$K" = "staging" ] && [ -n "$C" ] && ok "租到 staging 码 (tail=$(tail4 "$C"))" || bad "租码失败：$R"
grep -q "^$C staging$" "$WORK/codes.txt" && ok "租到的确是 staging 段在册码" || bad "租到不在册/非 staging 的码"
# 把其余 staging 全占满，验证租不到 buyer 段（buyer 永不进 lease 池）。
# 注意"补到 2"而不是"插 2 枚"：并发格已经给 STG_RACE 绑了 2 台，硬插会把那一格顶到 4，
# 拆掉的是我自己最后那条"全表 ≤2"的复查，不是服务的罪。
"$PY" - "$DB" "$STG1" "$WORK/codes.txt" "$H1" "$H2" <<'PYEOF' || die "占满 staging 失败"
import sqlite3, sys, time
db, stg1, codes, h1, h2 = sys.argv[1:6]
now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
conn = sqlite3.connect(db)
for line in open(codes):
    code, kind = line.split()
    if kind == "staging" and code != stg1:
        used = conn.execute("SELECT COUNT(*) FROM bindings WHERE code=?", (code,)).fetchone()[0]
        for h in (h1, h2):
            if used < 2:
                conn.execute("INSERT OR IGNORE INTO bindings VALUES(?,?,?)", (code, h, now))
                used += 1
conn.commit(); conn.close()
PYEOF
R=$(post /api/admin/lease-staging "{\"admin_token\":\"$TOKEN\"}")
[ "$(field "$R" reason)" = "no_staging_seat" ] && ok "staging 占满 → no_staging_seat（且绝不回退去租 buyer 段）" || bad "租口越界：$R"
BUYER_TAIL=$(tail4 "$BUYER")
grep -q "$BUYER_TAIL" "$WORK/server.log" && bad "buyer 段码出现在服务端日志（说明被拿去激活或租用了）" || ok "buyer 段全程零使用（未进租约池、未上日志）"
"$PY" - "$DB" <<'PYEOF' || die "额度表复查失败"
import sqlite3, sys
conn = sqlite3.connect(sys.argv[1])
over = conn.execute("SELECT code, COUNT(*) c FROM bindings GROUP BY code HAVING c>2").fetchall()
assert not over, f"有码绑定数超过 2 台：{over}"
PYEOF
ok "全表复查：任何一枚码绑定数都 ≤2"

echo "==> [8/8] reset-staging：一键清测试段，buyer 段一枚不许被清"
# 先给 buyer 段真绑一台（走公开激活口，不是硬插）：重置口若写成"清全表"，被清掉的就是这一格。
R=$(post /api/activate "{\"code\":\"$BUYER\",\"device_hash\":\"$H4\"}")
[ "$(field "$R" ok)" = "True" ] && ok "buyer 段绑定一台成功（本轮重置口的对照格）" || bad "buyer 段绑不上：$R"
R=$(post /api/admin/reset-staging '{"admin_token":"wrong-token"}')
[ "$(field "$R" reason)" = "forbidden" ] && ok "reset-staging 错 token → forbidden" || bad "重置口鉴权没挡住：$R"
R=$(post /api/admin/reset-staging "{\"admin_token\":\"$TOKEN\"}")
[ "$(field "$R" ok)" = "True" ] && ok "reset-staging 成立 removed=$(field "$R" removed)" || bad "重置失败：$R"
[ "$(field "$R" staging_left)" = "0" ] && ok "测试段绑定清零（下一轮从干净额度起跑）" || bad "staging 仍剩 $(field "$R" staging_left)"
[ "$(field "$R" buyer_left)" = "1" ] && ok "买家段那条绑定原样在册（重置口按 kind 收窄，越不了界）" || bad "buyer 段计数=$(field "$R" buyer_left)（应 1）"
R=$(post /api/admin/lease-staging "{\"admin_token\":\"$TOKEN\"}")
[ "$(field "$R" kind)" = "staging" ] && ok "重置后可再租（测试段额度回来了）" || bad "重置后仍租不到：$R"
R=$(post /api/activate "{\"code\":\"$BUYER\",\"device_hash\":\"$H4\"}")
[ "$(field "$R" ok)" = "True" ] && [ "$(field "$R" seats_used)" = "1" ]   && ok "buyer 那台重置后仍在册（幂等重放 seats=1，没被顺手解绑）" || bad "buyer 绑定被重置口动了：$R"

echo
echo "CONTRACT pass=$PASS fail=$FAIL"
if [ "$FAIL" -eq 0 ]; then echo "SCRIPT-RC=0"; else echo "SCRIPT-RC=1"; fi
[ "$FAIL" -eq 0 ]
