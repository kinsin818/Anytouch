#!/usr/bin/env bash
# S5-g 判据面配套：一键清 staging 段的绑定表（裁 3 的"独立测试段"要能被反复复位，否则 20 枚码
# 会在几轮冒烟里被假设备占满，之后的每一轮都吃 seats_full——那不是产品的罪，是测试通道的自锁）。
#
# 只清 staging：管理口那条 DELETE 在 SQL 里按 kind 收窄（server/activation/app.py::_reset_staging），
# 误调用动不到真买家额度；本轮跑完照实把 removed/staging_left/buyer_left 三个数记进 raw。
# ADMIN_TOKEN 全程不出机（见 scripts/s5g-server.sh 的口径注）。
set -u
cd "$(dirname "$0")/.."
. scripts/s5g-server.sh

RAW_DIR="${RAW_DIR:-evidence/S5g/raw}"
STAMP="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RAW_DIR"
OUT="$RAW_DIR/s5g-reset-staging-$STAMP.raw.txt"

RESP=$(s5g_reset_staging) || { echo "s5g-reset-staging :: 复位未成立（见上方 stderr）" | tee "$OUT"; echo "SCRIPT-RC=2"; exit 2; }
OK=$(s5g_json_field "$RESP" ok)
REMOVED=$(s5g_json_field "$RESP" removed)
STG=$(s5g_json_field "$RESP" staging_left)
BUY=$(s5g_json_field "$RESP" buyer_left)
{
    echo "# s5g-reset-staging $STAMP"
    echo "# ok=$OK removed=$REMOVED staging_left=$STG buyer_left=$BUY"
    echo "# 响应体（本口不返整码，字段只有计数）：$RESP"
} > "$OUT"
echo "s5g-reset-staging :: ok=$OK removed=$REMOVED staging_left=$STG buyer_left=$BUY → $OUT"
[ "$OK" = "True" ] || [ "$OK" = "true" ] || { echo "SCRIPT-RC=2"; exit 2; }
echo "SCRIPT-RC=0"
