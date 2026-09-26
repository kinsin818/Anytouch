#!/usr/bin/env bash
# S5-g 测试通道的"让靶机真能打到服务器"那一段：主机中继 + 靶机 DNAT。
#
# 为什么需要它（两条都是今天量出来的事实，不是猜测）：
# - 本台 Windows 的出网被透明拦截：对**明知无监听的 9999 端口** `connect` 照样"建立"，
#   而对 8443 打 HTTPS 15 秒零字节 ⇒ 主机侧的 connect 读数一律作废（部署实录同一口径）。
# - 靶机在没有中继时打 45.32.63.177:8443 = 8 秒后 `SERVER_UNREACHABLE`；
#   起了中继之后同一枚码 1 秒 `ok seats=1/2`，**机上 journal 同窗出现该尾四位的 activate 行**。
#   ⇒ 差别只在"这一台测试机的路由"，产品代码一个字没动。
#
# 这**不是**产品旁路（判据 10 禁的是那个）：
# 1) 产品仍然只认它自己的端点、自己的证书指纹校验——中继只是转发 TCP 字节，TLS 端到端点还在机上；
#    所以这里额外做一次 **SPKI 指纹核对**：中继对面那张证书必须等于**本轮明说的那 16 位**，
#    否则当场停手（指到假靶上跑，测出来的绿全是假绿——端口被别的进程占着是本脚本唯一的真实自毁方式）。
# 2) 撤除与建立同样显式：`s5g_relay_down` 删规则、杀隧道，测试结束后靶机回到"真不可达"那一态。
#
# "允许任意异指纹"那一支已经**删掉了**（09-26 run1 血账，原文入 raw）：
# 上一版判据 8 为了指假靶开的是 `S5G_RELAY_ALLOW_FOREIGN_PIN=1`＝"中继这层什么都不核"。
# 而"18443 上已有应答就复用"那一句只认 health 字符串——假靶跑的是**同一个 app.py**、
# health 里同样写着 anytouch-activate，于是旧隧道没换成靶，设备打到的是真服务器，
# 拿回 `ok seats=1/2`，那一格红得毫无道理（而且红的方向还是反的：它把"通道没指对"洗成了"指纹固定是装饰"）。
# 换成"必须先把期望的那 16 位说出来"之后：对面不等于期望值＝换靶没换成，当场停手；
# 旧通道冒充新靶这一条从此在结构上不成立。
#
# 用法（被 source）：source scripts/s5g-relay.sh
#   s5g_relay_up                → 起隧道 + 下 DNAT + 对指纹；失败 return 2（调用方 exit 2 语义）
#   s5g_relay_down              → 删 DNAT + 杀隧道（幂等，收尾必调）
#   S5G_RELAY_EXPECT_SPKI=<16位> s5g_relay_restart   → 换靶（判据 8 那枚第二份自签证书用；不给期望值=不换靶）
set -u

S5G_RELAY_SSH="${S5G_RELAY_SSH:-root@45.32.63.177}"
S5G_RELAY_PORT="${S5G_RELAY_PORT:-18443}"
S5G_RELAY_TARGET="${S5G_RELAY_TARGET:-127.0.0.1:8443}"
S5G_TARGET_IP="${S5G_TARGET_IP:-45.32.63.177}"
S5G_TARGET_PORT="${S5G_TARGET_PORT:-8443}"
# APP 侧固定的那 16 位 SPKI SHA-256 前缀（唯一真值见 APPENDIX §1；这里只做核对，不做凭据）
S5G_PIN="${S5G_PIN:-3d7146a142f8d08f}"
# 本轮"对面应该是谁"。留空＝正脸，必须等于 APP 的固定值；判据 8 那格由调用方把假靶指纹传进来。
S5G_RELAY_EXPECT_SPKI="${S5G_RELAY_EXPECT_SPKI:-}"
S5G_RELAY_PIDFILE="${S5G_RELAY_PIDFILE:-/tmp/s5g-relay.pid}"

_s5g_adb() { adb ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} "$@"; }

s5g_relay_want() { printf '%s' "${S5G_RELAY_EXPECT_SPKI:-$S5G_PIN}"; }

_s5g_relay_match() { # $1=实测 spki $2=期望前缀 → 0=对得上
    local spki="$1" want="$2"
    [ -n "$spki" ] || return 1
    case "$spki" in "$want"*) return 0 ;; *) return 1 ;; esac
}

s5g_relay_spki() { # $1=host:port → SPKI SHA-256 十六进制（全值；没人应答则空串）
    local hp="${1:-127.0.0.1:$S5G_RELAY_PORT}" h p
    h=${hp%:*}; p=${hp##*:}
    command -v openssl >/dev/null 2>&1 || { echo ""; return 1; }
    timeout 20 openssl s_client -connect "$h:$p" </dev/null 2>/dev/null \
        | openssl x509 -pubkey -noout 2>/dev/null \
        | openssl pkey -pubin -outform der 2>/dev/null \
        | openssl dgst -sha256 -r 2>/dev/null | cut -c1-64
}

_s5g_relay_holders() { # 真在 listen 本机中继端口的 PID（去重）——pidfile 只记"我自己起的最后那一个"，
    # 换靶失败时旧隧道还占着端口，只看 pidfile 就会杀错人（run1 里"复用"那句就是这么来的）。
    # LC_ALL=C：Windows 的 netstat 输出是本地代码页，awk 按 UTF-8 读会报"Invalid multibyte data"（读数没错，噪音很误导）
    LC_ALL=C netstat -ano 2>/dev/null | tr -d '\r' \
        | LC_ALL=C awk -v p=":$S5G_RELAY_PORT" 'toupper($1) ~ /TCP/ && $2 ~ p"$" && toupper($4) ~ /LISTEN/ {print $5}' | sort -u
}

_s5g_relay_kill_tunnel() { # 只杀"自己的 ssh 隧道"；端口被非 ssh 进程占着＝当场停手，绝不动别人的进程
    local pid name
    if [ -f "$S5G_RELAY_PIDFILE" ]; then
        kill "$(cat "$S5G_RELAY_PIDFILE" 2>/dev/null)" >/dev/null 2>&1 || true
        rm -f "$S5G_RELAY_PIDFILE"
    fi
    for pid in $(_s5g_relay_holders); do
        [ -n "$pid" ] || continue
        name=$(tasklist //FI "PID eq $pid" //FO CSV //NH 2>/dev/null | tr -d '\r' | tr -d '"' | cut -d, -f1 | head -1)
        case "$name" in
        ssh.exe | ssh)
            taskkill //PID "$pid" //F >/dev/null 2>&1 || true
            ;;
        *)
            echo "relay :: 端口 $S5G_RELAY_PORT 被非隧道进程占着（[$name] pid=$pid）——不杀别人的进程，残留守卫当场停手" >&2
            return 2
            ;;
        esac
    done
    # 等端口真安静下来再返回：隧道刚死时 127.0.0.1:$S5G_RELAY_PORT 还会短暂应答，
    # 此时紧接一次 up 会走"复用"那一支，把换靶动作洗成没换（假绿形态）
    local j=0
    while [ "$j" -lt 15 ]; do
        [ -z "$(_s5g_relay_holders)" ] && break
        sleep 1; j=$((j + 1))
    done
    [ "$j" -lt 15 ] || { echo "relay :: 杀完隧道端口仍在 listen（$(_s5g_relay_holders | tr '\n' ' ')）——换靶不可信，停手" >&2; return 2; }
    return 0
}

# 空输入也会被打出一个"指纹"：openssl 在拿不到证书时对空串求摘要＝e3b0c44298fc1c149afbf4c8…（实测全值见 run2/run3 日志）。
# 不把这一眼认出来，日志就会在"根本没人应答"的时候写"端口上应答的是别的证书"——一句不实陈述。
_s5g_relay_cert_readable() { # $1=实测 spki → 0=端口上真有一张可读证书
    local spki="${1:-}"
    [ -n "$spki" ] || return 1
    case "$spki" in
    e3b0c44298fc1c149afbf4c8*) return 1 ;;   # SHA-256("")：openssl 没拿到任何证书
    esac
    return 0
}

s5g_relay_up() {
    local want spki i
    want=$(s5g_relay_want)
    command -v ssh >/dev/null 2>&1 || { echo "relay :: 本机无 ssh" >&2; return 2; }
    [ "$(_s5g_adb shell id -u 2>/dev/null | tr -d '\r')" = "0" ] || {
        echo "relay :: 靶机 adbd 非 root，下不了 DNAT（这是 AVD 才允许的动作，真机本批零触碰）" >&2; return 2; }

    # 身份核对**先于**"要不要复用"的判断：认的是证书本身，不是 health 里那句服务名
    # （正靶与假靶跑同一个 app.py，服务名一模一样——run1 就是被这一点骗过去的）。
    local reused=0
    spki=$(s5g_relay_spki)
    if _s5g_relay_match "$spki" "$want"; then
        reused=1
        echo "relay :: 复用已在位的隧道（对面证书=${spki:0:16}＝本轮期望值）" >&2
    elif _s5g_relay_cert_readable "$spki"; then
        echo "relay :: 端口 $S5G_RELAY_PORT 上应答的是别的证书（${spki:0:16} ≠ 期望 $want）——先把旧隧道拆干净再换靶" >&2
        _s5g_relay_kill_tunnel || return 2
    else
        echo "relay :: 端口 $S5G_RELAY_PORT 没人应答（或读不到证书）——正常起隧道" >&2
    fi

    if [ "$reused" = "0" ]; then
        nohup ssh -o BatchMode=yes -o ExitOnForwardFailure=yes -o ServerAliveInterval=20 -o StrictHostKeyChecking=accept-new \
            -N -L "127.0.0.1:$S5G_RELAY_PORT:$S5G_RELAY_TARGET" "$S5G_RELAY_SSH" >/tmp/s5g-relay.log 2>&1 &
        echo $! > "$S5G_RELAY_PIDFILE"
        i=0
        while [ "$i" -lt 20 ]; do
            spki=$(s5g_relay_spki)
            _s5g_relay_match "$spki" "$want" && break
            sleep 1; i=$((i + 1))
        done
        _s5g_relay_match "$spki" "$want" || { echo "relay :: 隧道 20s 内没起出期望对面（target=$S5G_RELAY_TARGET，读到 [${spki:0:16}]，期望 $want）" >&2; return 2; }
    fi

    # 起完再量一次：这一行是"通道里真有人应答，而且应答的是期望那张证书"的凭据（不是变量回显）
    spki=$(s5g_relay_spki)
    case "$spki" in
    "$want"*) : ;;
    *)
        echo "relay :: 中继对面不等于期望指纹（读到 [${spki:0:16}]，期望 $want…）——换靶没换成，绝不对着旧通道跑判据" >&2
        return 2
        ;;
    esac

    _s5g_adb shell "iptables -t nat -C OUTPUT -p tcp -d $S5G_TARGET_IP --dport $S5G_TARGET_PORT -j DNAT --to-destination 10.0.2.2:$S5G_RELAY_PORT" >/dev/null 2>&1 ||
        _s5g_adb shell "iptables -t nat -A OUTPUT -p tcp -d $S5G_TARGET_IP --dport $S5G_TARGET_PORT -j DNAT --to-destination 10.0.2.2:$S5G_RELAY_PORT" >/dev/null 2>&1 || {
        echo "relay :: DNAT 下不去（iptables 拒绝）" >&2; return 2; }
    echo "relay :: up · 靶机 $S5G_TARGET_IP:$S5G_TARGET_PORT → 10.0.2.2:$S5G_RELAY_PORT → 机上 $S5G_RELAY_TARGET · 对面证书实测 ${spki:0:16}（本轮期望 $want）" >&2
    return 0
}

s5g_relay_down() {
    local i=0
    while [ "$i" -lt 8 ]; do
        _s5g_adb shell "iptables -t nat -D OUTPUT -p tcp -d $S5G_TARGET_IP --dport $S5G_TARGET_PORT -j DNAT --to-destination 10.0.2.2:$S5G_RELAY_PORT" >/dev/null 2>&1 || break
        i=$((i + 1))
    done
    _s5g_relay_kill_tunnel || return 2
    echo "relay :: down（DNAT 已撤、隧道已杀；靶机回到真不可达那一态）" >&2
    return 0
}

s5g_relay_restart() { s5g_relay_down || return 2; s5g_relay_up; }

# 不被 source、直接执行时的三个动作。一轮设备面跑完必须 --down 收手：
# 中继留着，下一轮"这条路由到底生没生效"就成了没人能说清的事（残留守卫同族）。
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    case "${1:---up}" in
    --up) s5g_relay_up ;;
    --down) s5g_relay_down ;;
    --status)
        printf 'tunnel-health=%s\n' "$(timeout 12 curl -sk --max-time 8 "https://127.0.0.1:$S5G_RELAY_PORT/api/health" 2>/dev/null)"
        printf 'spki=%s\n' "$(s5g_relay_spki)"
        printf 'want=%s\n' "$(s5g_relay_want)"
        printf 'holders=%s\n' "$(_s5g_relay_holders | tr '\n' ' ')"
        printf 'dnat=%s\n' "$(_s5g_adb shell "iptables -t nat -S OUTPUT" 2>/dev/null | tr -d '\r' | grep -c "$S5G_TARGET_IP" || true)"
        ;;
    *) echo "用法：ANDROID_SERIAL=<avd> bash scripts/s5g-relay.sh --up|--down|--status"; exit 2 ;;
    esac
fi
