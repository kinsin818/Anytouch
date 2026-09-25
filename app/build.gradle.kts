plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

android {
    namespace = "com.anytouch.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anytouch.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.0.4"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:contracts"))
    // 创建期编译模块。允许 import 它的只有 ui/ 与 compile/ 两处——
    // 这条边界由 scripts/ci-local.sh 红线 G 机器锁住：执行路径（executor/locator/service/safety/platform/recorder）
    // 一旦 import 就 FAIL，"编译期联网、执行期零网络"因此是结构而非注释。
    implementation(project(":byok"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation(kotlin("test"))
}

// S2 基设（STAGE-21 提案，主窗落地）：单测逐条打印，失败带堆栈——worker 证据文件不再只有一行总数
tasks.withType<Test> {
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
