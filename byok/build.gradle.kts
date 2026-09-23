plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

// S3 BYOK：全仓唯一允许出现网络代码的产品侧模块（红线 F 的白名单住处，另一处是 tools/）。
// 纯 JVM 工件——设备与 host 探针共用同一份编译判据，禁止任何一侧复制第二份校验逻辑。
dependencies {
    api(project(":core:contracts"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
