#!/usr/bin/env bash
# Anytouch S0 本地 CI 门禁 —— STAGE-02
# 用法（仓库根目录）: ./scripts/ci-local.sh
# 任一步失败即以非 0 退出码终止（set -e + 显式 exit 1）。
set -euo pipefail

cd "$(dirname "$0")/.."

echo "==> [1/4] gradle build (:core:contracts + :app)"
# 依赖解析走 settings.gradle.kts 中配置的阿里云镜像源，离线/不可达环境会失败，属预期
./gradlew :core:contracts:build :app:assembleDebug

echo "==> [2/4] gradle test (:core:contracts, :app, --rerun-tasks)"
./gradlew :core:contracts:test --rerun-tasks
./gradlew :app:testDebugUnitTest --rerun

echo "==> [3/4] 红线 grep"

# 红线 A: 生产/测试 Kotlin 源码内禁止真实网络调用关键字（含 upload / http / socket 类 API）
if grep -rnEi --include='*.kt' 'https?://|java\.net|HttpURL|URLConnection|okhttp|HttpClient|URL\(|Socket\(|[Uu]pload' core/; then
    echo "REDLINE-A HIT: 源码内出现网络调用关键字"
    exit 1
fi
echo "  redline A clean (no network-call keywords under core/)"

# 红线 B: 受管源文件（kotlin / gradle kts / workflow yml / 本目录脚本）禁止引用队友工作区
# 字面量拆分书写，避免本脚本 grep 自身模式时误报；shell 展开后仍为完整路径关键字
REDLINE_B_PATTERN=".$(echo du)mate|.$(echo wo)rkbuddy"
if grep -rnE --include='*.kt' --include='*.kts' --include='*.yml' --include='*.sh' "$REDLINE_B_PATTERN" \
    core/ .github/ scripts/ build.gradle.kts settings.gradle.kts; then
    echo "REDLINE-B HIT: 源文件引用了队友工作区目录"
    exit 1
fi
echo "  redline B clean (no teammate-workspace references)"

# 红线 C（S1）: :app 主源码执行期零网络关键字（S3 BYOK 前不得出现）
if grep -rnEi --include='*.kt' 'https?://|java\.net|HttpURL|URLConnection|okhttp|HttpClient|Socket\(|[Uu]pload' app/src/main/; then
    echo "REDLINE-C HIT: app 主源码出现网络关键字"
    exit 1
fi
echo "  redline C clean (no network-call keywords under app/src/main)"

# 红线 D（S1）: 全仓 Kotlin 禁止手势/坐标注入 API（坐标定位硬约束的机器化检查）
if grep -rnE --include='*.kt' 'performGesture|dispatchGesture|input event tap|adb shell input' app/ core/; then
    echo "REDLINE-D HIT: 出现手势/坐标注入 API"
    exit 1
fi
echo "  redline D clean (no gesture/coordinate injection)"

# 红线 E（S1）: manifest 不得回现 SYSTEM_ALERT_WINDOW（悬浮通道 = TYPE_ACCESSIBILITY_OVERLAY）
if grep -q "SYSTEM_ALERT_WINDOW" app/src/main/AndroidManifest.xml; then
    echo "REDLINE-E HIT: manifest 回现 SYSTEM_ALERT_WINDOW"
    exit 1
fi
echo "  redline E clean (manifest free of SYSTEM_ALERT_WINDOW)"

echo "==> [4/4] ci-local PASS"
