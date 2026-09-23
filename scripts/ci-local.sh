#!/usr/bin/env bash
# Anytouch S0 本地 CI 门禁 —— STAGE-02
# 用法（仓库根目录）: ./scripts/ci-local.sh
# 任一步失败即以非 0 退出码终止（set -e + 显式 exit 1）。
set -euo pipefail

cd "$(dirname "$0")/.."

# SKIP_GRADLE=1 只跑红线段：给 scripts/redline-probe.sh 逐条放探针用（同一条门禁，不另写一份 grep）
if [ -n "${SKIP_GRADLE:-}" ]; then
    echo "==> [1/4][2/4] gradle SKIPPED (SKIP_GRADLE=1, 只验红线)"
    SKIP_STEPS=1
else
echo "==> [1/4] gradle build (:core:contracts + :byok + :app + :tools:compiler)"
# 依赖解析走 settings.gradle.kts 中配置的阿里云镜像源，离线/不可达环境会失败，属预期
# 口径注记：:tools:compiler 是 host 侧 T2 测量工具（允许网络，Key 仅环境变量），
# 红线 A/C 覆盖的设备产品路径（core/ 与 app/src/main）零网络语义不变。
# S3 起新增 :byok：全仓唯一允许联网的**产品侧**模块（红线 F 白名单），执行路径禁 import 它（红线 G）。
./gradlew :core:contracts:build :byok:build :app:assembleDebug :tools:compiler:build

echo "==> [2/4] gradle test (:core:contracts, :byok, :app, :tools:compiler, --rerun-tasks)"
./gradlew :core:contracts:test --rerun-tasks
./gradlew :byok:test --rerun-tasks
./gradlew :app:testDebugUnitTest --rerun
./gradlew :tools:compiler:test --rerun-tasks
fi

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

# 红线 F（S3）: 全仓网络代码只允许住两处 —— byok/（产品侧唯一联网模块）与 tools/（host 测量工具）。
# 这是把"执行期零网络"从约定升级为结构：能联网的代码在仓库里只有两个住处。
if grep -rnE --include='*.kt' --exclude-dir=byok --exclude-dir=tools --exclude-dir=build --exclude-dir=.git \
    'java\.net|HttpURL|URLConnection|okhttp|HttpClient|Socket\(' .; then
    echo "REDLINE-F HIT: byok/ 与 tools/ 之外出现网络代码"
    exit 1
fi
echo "  redline F clean (network code confined to byok/ and tools/)"

# 红线 G（S3）: 执行路径看不见编译器——executor/locator/service/safety/platform/recorder 与 core/
# 一律禁止 import com.anytouch.byok。创建期用编译器的只许是 UI 面（MainActivity / ui/ / compile/）。
if grep -rn --include='*.kt' "com\.anytouch\.byok" \
    app/src/main/kotlin/com/anytouch/app/executor \
    app/src/main/kotlin/com/anytouch/app/locator \
    app/src/main/kotlin/com/anytouch/app/service \
    app/src/main/kotlin/com/anytouch/app/safety \
    app/src/main/kotlin/com/anytouch/app/platform \
    app/src/main/kotlin/com/anytouch/app/recorder \
    core/; then
    echo "REDLINE-G HIT: 执行路径 import 了编译模块（执行期零网络的结构锁失效）"
    exit 1
fi
echo "  redline G clean (execution path cannot see the compiler module)"

# 红线 H（S3）: Key 不进日志（兜底防手滑；真正的脱敏由 :byok 纯函数 + JVM 用例承担）
# 口径修正由反面锁自证：原来写作 `Log\.[vdiwe] `（字母后要求空格），`Log.d(` 根本匹配不上＝假锁。
if grep -rnE --include='*.kt' '(Log\.[vdiwe]\(|println\().*(apiKey|ApiKey|Bearer|nvapi-|Authorization|authHeader)' \
    byok/ app/src/main/ 2>/dev/null; then
    echo "REDLINE-H HIT: 日志语句里出现密钥形态字段"
    exit 1
fi
echo "  redline H clean (no key-bearing log statements)"

# 红线 I（S3-B）: 凭据只允许住 Keystore 加密后的应用私有文件。SharedPreferences / 外部目录 / 世界可读文件
# 都是"明文 Key 落盘"的常见手滑路径，一旦走了这些口子，红线 H 的日志脱敏就毫无意义。
if grep -rnE --include='*.kt' 'getSharedPreferences|getExternalFilesDir|getExternalStorageDirectory|MODE_WORLD_READABLE|externalCacheDir' \
    app/src/main/; then
    echo "REDLINE-I HIT: 出现明文落盘通道（Key 只能进 Keystore 加密 blob）"
    exit 1
fi
echo "  redline I clean (no plaintext credential storage channel in app)"

echo "==> [4/4] ci-local PASS"
