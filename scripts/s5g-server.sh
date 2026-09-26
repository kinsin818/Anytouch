#!/usr/bin/env bash
# S5-g 测试通道的服务器侧取码口（主机侧工具，不是产品路径）。
#
# 为什么默认走"机内 ssh 执行"而不是本机 curl（两条都是事实，一条都不能藏）：
# 1) 本机出网被透明拦截：同一台 Windows 上 `https://45.32.63.177:8443` 超时、连 `:80` 也 12 秒零字节，
#    而 emulator 侧 TCP 探 8443/80/9999 **三样全挂**（80 那侧容器日志里有真实公网流量在进）。
#    ⇒ 这个红**不归因到端口**，写"不可达"是假事实，写"可达"更是（部署实录 §公网可达性那一节原文在册）。
# 2) 机内 `curl -sk https://127.0.0.1:8443` 是实测通的（部署时逐条验过），ssh 通道也是现成的。
#    所以取码/重置这类**管理动作**在机上执行，只把结果 JSON 送回来。
# 3) 这不给产品开任何旁路：APP 端仍然只认它自己那枚端点 + 指纹固定（红线 C/F/G 一字未动），
#    租到的码也必须走 `ActivationStore.submit` 那条唯一校验路径，本脚本一个字都不参与设备侧判定。
#
# 隐私口径（本批硬约束）：`ADMIN_TOKEN` **不出机**——在机上从 0600 的 .env 里读，本机与仓内一个字都不落；
# 租到的整码只在调用方的 shell 变量里活一轮，日志一律只打尾四位（在册码进公开仓=给全球开门）。
#
# 用法（被 source）：source scripts/s5g-server.sh
#   s5g_health                      → 机内健康检查，返回体原样打印
#   s5g_admin PATH [JSON]           → 带 admin_token 的 POST；JSON 是**完整对象** `{"k":"v"}`，token 并进同一层
#   s5g_activate CODE HASH          → 直打公开口 /api/activate（造"第二台已占格"这种态用）
#   s5g_deactivate CODE HASH        → 直打 /api/deactivate
#   s5g_lease_staging               → 成功打印 "CODE"（整码，调用方自己保密）；失败 return 2 并说明原因
#   s5g_reset_staging               → 清 staging 段全部绑定，返回体原样
#   S5G_DIRECT=1 时改走本机直连（只在 8443 真被放行后才有意义；那之前直连读数一律作废）
set -u

S5G_SSH="${S5G_SSH:-root@45.32.63.177}"
S5G_ONBOX_URL="${S5G_ONBOX_URL:-https://127.0.0.1:8443}"
S5G_PUBLIC_URL="${S5G_PUBLIC_URL:-https://45.32.63.177:8443}"
S5G_ENV_FILE="${S5G_ENV_FILE:-/opt/anytouch-activate/.env}"
S5G_SSH_OPTS="${S5G_SSH_OPTS:--o BatchMode=yes -o ConnectTimeout=10}"

s5g_json_field() {  # $1=json $2=field → 值（无则空）；不依赖 jq，本机只有 python/python3
    local py
    py="$(command -v python3 || command -v python || true)"
    [ -n "$py" ] || { echo ""; return 1; }
    PYTHONIOENCODING=utf-8 "$py" -c '
import json, sys
try:
    print(json.loads(sys.argv[1]).get(sys.argv[2], ""))
except Exception:
    print("")
' "$1" "$2" 2>/dev/null
}

# 机上执行的那段脚本：读 token、打本机回环、只回响应体。
# 两条硬口径：① heredoc 用引号包住 = 变量全部在**机上**展开，本机不参与二次解析
# （反引号被当命令替换打空那把雷的同族防法）；② **绝不把 heredoc 塞进 `$( )` 里**——
# 本机 bash 对"if 的 else 分支中、命令替换内接 heredoc"这种形状直接报 syntax error（实测），
# 故这里用顶层 `read -d ''` 把整段收进变量，再由 printf 管道喂给 `bash -s`。
S5G_ONBOX_SNIPPET=""
IFS= read -r -d '' S5G_ONBOX_SNIPPET <<'EOS' || true
path="$1"; b64="$2"; env_file="$3"; base="$4"; mode="$5"
# 形状自检：ssh 会把远端命令行重新按空格切一遍，**空参数在切分时直接消失**（本机实测：
# 传 4 个参数、第二个为空，机上只收到 3 个，env 路径错位进 $2、$3 成了 URL，
# 报出来的却是"token 读不到"——假归因）。这里先认路径形状，错位就报一个独有的原因，不含糊。
case "$env_file" in
/*) ;;
*) printf '{"ok":false,"reason":"admin_call_shape_broken"}'; exit 0 ;;
esac
# body 全程 base64 过链：JSON 里哪怕有一个空格，被远端按空格重切一次就是一副脏 payload；
# 更硬的一条是**在册码不进 argv**——`ps` 捞不走它（与 seed 走 stdin 同一条律，只是这里换了载体）。
[ "$b64" = "-" ] && b64=''
body=$(printf '%s' "$b64" | base64 -d 2>/dev/null) || { printf '{"ok":false,"reason":"admin_body_undecodable"}'; exit 0; }
if [ "$mode" = "admin" ]; then
    tok=$(sed -n 's/^ADMIN_TOKEN=//p' "$env_file" 2>/dev/null | head -n1 | tr -d '"')
    if [ -z "$tok" ]; then printf '{"ok":false,"reason":"admin_token_unreadable_on_box"}'; exit 0; fi
    if [ -z "$body" ]; then body="{\"admin_token\":\"$tok\"}"; else body="{\"admin_token\":\"$tok\"${body#\{}"; fi
fi
curl -sk --max-time 15 -H 'Content-Type: application/json' -d "$body" "${base}${path}"
EOS

_s5g_post() { # $1=path $2=json $3=admin|plain
    local path="$1" body="$2" mode="${3:-admin}" out b64
    if [ "${S5G_DIRECT:-0}" = "1" ]; then
        if [ "$mode" = "admin" ]; then
            body="{\"admin_token\":\"${S5G_ADMIN_TOKEN_DIRECT:-}\"${body#\{}"
        fi
        out=$(timeout 40 curl -sk --max-time 15 -H 'Content-Type: application/json' \
            -d "$body" "$S5G_PUBLIC_URL$path" 2>/dev/null)
    else
        command -v base64 >/dev/null 2>&1 || { printf '{"ok":false,"reason":"admin_base64_missing"}'; return 1; }
        b64=$(printf '%s' "$body" | base64 | tr -d '\r\n')
        # 空 body 编出来还是空串——照样用 "-" 占位，绝不让 argv 出现空参数（上面那把雷的形状）
        out=$(printf '%s\n' "$S5G_ONBOX_SNIPPET" | timeout 60 ssh $S5G_SSH_OPTS "$S5G_SSH" \
            'bash -s --' "$path" "${b64:--}" "$S5G_ENV_FILE" "$S5G_ONBOX_URL" "$mode" 2>/dev/null)
    fi
    printf '%s' "$out"
}

s5g_admin() { _s5g_post "$1" "${2:-}" admin; }

# 公开口直打（/api/activate 那一条）：测试要造"第二台已占格"这种态，只能从服务器侧下单。
# 与判据 1 的契约脚本同一形状，区别只在靶子：这里打的是**公网实例机上那份库**。
s5g_activate() { # $1=整码 $2=device_hash → 响应 JSON（原样，调用方自己保密）
    _s5g_post "/api/activate" "{\"code\":\"$1\",\"device_hash\":\"$2\"}" plain
}

s5g_deactivate() { # $1=整码 $2=device_hash → /api/deactivate 是**管理口**（实测无 token=403），故走 admin
    _s5g_post "/api/deactivate" "{\"code\":\"$1\",\"device_hash\":\"$2\"}" admin
}

# 在机上跑一段脚本（脚本从 stdin 进去，不进 argv；调用方一律用引号 heredoc，本机零展开）。
# 用途只有两件：起停正规服务、起停判据 8 那枚"第二份自签证书"的假服务——都是部署动作，不含码不含 token。
s5g_box_sh() { timeout 150 ssh $S5G_SSH_OPTS "$S5G_SSH" 'bash -s' 2>/dev/null; }

s5g_health() {
    if [ "${S5G_DIRECT:-0}" = "1" ]; then
        timeout 30 curl -sk --max-time 12 "$S5G_PUBLIC_URL/api/health" 2>/dev/null
    else
        timeout 45 ssh $S5G_SSH_OPTS "$S5G_SSH" \
            "curl -sk --max-time 10 '$S5G_ONBOX_URL/api/health'" 2>/dev/null
    fi
}

# 租一枚在册且未占满的 staging 码。**只认 kind=staging**：租到 buyer 一律当场停手，
# 配置错把手伸进真买家额度比测试红一百倍严重（附页 §4 那条断言就为这件事存在）。
s5g_lease_staging() {
    local resp kind code reason
    resp=$(s5g_admin "/api/admin/lease-staging")
    # 两种成因要分开说：`S5G_DIRECT` 只是可选开关，多数调用方根本不会设它，
    # 这里直接写 `$S5G_DIRECT` 会在 `set -u` 下**把"取不到码"变成"脚本自己炸了"**（实测把 R5b 顶成 unbound variable，
    # 真因"服务停着没人应答"反倒被盖住）。空响应绝大多数就是没人应答，不是 token 读不到。
    if [ -z "$resp" ]; then
        echo "s5g-server :: 管理口空响应——多半是服务没在应答（S5G_DIRECT=${S5G_DIRECT:-0}，机内通道与本机直连都没回东西）" >&2
        return 2
    fi
    kind=$(s5g_json_field "$resp" kind)
    code=$(s5g_json_field "$resp" code)
    reason=$(s5g_json_field "$resp" reason)
    if [ "$kind" != "staging" ]; then
        echo "s5g-server :: 取到的不是 staging 段（kind=[$kind] reason=[$reason]）——绝不拿它进设备，测试段与买家段必须隔开" >&2
        return 2
    fi
    case "$code" in
    ANY-????-????-????) printf '%s' "$code" ;;
    *)
        echo "s5g-server :: 管理口说 staging 却没给出形状合法的码（形状 [${code:0:3}]…）" >&2
        return 2
        ;;
    esac
}

# 一键清 staging 绑定表（每轮冒烟起始态）。整码不外传：机上直接从库里删 kind='staging' 的绑定。
s5g_reset_staging() {
    local resp ok
    resp=$(s5g_admin "/api/admin/reset-staging")
    ok=$(s5g_json_field "$resp" ok)
    if [ "$ok" != "True" ] && [ "$ok" != "true" ]; then
        echo "s5g-server :: reset-staging 未成立（响应 [${resp:0:120}]）" >&2
        return 2
    fi
    printf '%s' "$resp"
}
