#!/usr/bin/env bash
# 红线反面锁（S3-B 起）：逐条证明每条红线**能 FAIL**。
# 教训来源：切片 A 曾用一个循环同时放三条探针，每轮删掉"另外两条"，于是后两轮在**没有探针文件**
# 的状态下报"clean"——和"自家窗=0 是没在读自家窗"同一类假绿。故本脚本铁律：
#   一轮只放一条探针 -> 独立看 rc 与命中行 -> 删干净 -> 再确认整条门禁回到 PASS。
# 探针走 SKIP_GRADLE=1 的同一条 ci-local 红线段，不另写第二份 grep（两份实现只有坏的那条会被看见）。
set -uo pipefail
cd "$(dirname "$0")/.."

probe_fail=0

cleanup() { rm -rf probe_tmp app/src/main/kotlin/com/anytouch/app/probe 2>/dev/null; }
trap cleanup EXIT

run_gate() { SKIP_GRADLE=1 ./scripts/ci-local.sh 2>&1; }

# $1=红线名 $2=探针相对路径 $3=探针内容
probe_one() {
    local name="$1" path="$2" body="$3" out rc
    cleanup
    mkdir -p "$(dirname "$path")"
    printf '%s\n' "$body" > "$path"
    out=$(run_gate); rc=$?
    if [ $rc -eq 0 ]; then
        echo "PROBE-$name WEAK: 放了探针门禁还 PASS（锁是假的）"
        probe_fail=1
    elif ! printf '%s' "$out" | grep -q "REDLINE-$name HIT"; then
        echo "PROBE-$name MISS: rc=$rc 但没打出 REDLINE-$name HIT（可能撞了别的红线）"
        printf '%s\n' "$out" | tail -5
        probe_fail=1
    else
        echo "PROBE-$name OK: 能 FAIL（$(printf '%s' "$out" | grep -m1 "REDLINE-$name HIT")）"
    fi
    rm -f "$path"
    cleanup
    out=$(run_gate); rc=$?
    if [ $rc -ne 0 ]; then
        echo "PROBE-$name CLEAN-FAIL: 探针删掉后门禁没回到 PASS"
        printf '%s\n' "$out" | tail -5
        probe_fail=1
    fi
}

mkdir -p probe_tmp
probe_one F probe_tmp/ProbeF.kt 'package com.anytouch.probe
import java.net.URI
val probeF = URI("https://example.com")'

probe_one G app/src/main/kotlin/com/anytouch/app/executor/ProbeG.kt 'package com.anytouch.app.executor
import com.anytouch.byok.DslCompiler
val probeG = DslCompiler::class.java.name'

probe_one H app/src/main/kotlin/com/anytouch/app/ProbeH.kt 'package com.anytouch.app
val probeH = "nvapi-abc"
fun logProbeH(apiKey: String) { android.util.Log.d("x", "Bearer $apiKey") }'

probe_one I app/src/main/kotlin/com/anytouch/app/ProbeI.kt 'package com.anytouch.app
fun prefsProbeI(c: android.content.Context) = c.getSharedPreferences("p", 0)'

if [ $probe_fail -ne 0 ]; then
    echo "REDLINE-PROBE FAIL: 至少一条红线证不出能 FAIL"
    exit 1
fi
echo "REDLINE-PROBE PASS: F/G/H/I 四条逐条独立验证能 FAIL，且撤探针后门禁回到 PASS"
