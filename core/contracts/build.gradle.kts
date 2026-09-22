plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // api 而非 implementation：真实执行器（:app）需无损构造 Command/ActionResult 的 JsonObject 字段。
    // 由主窗在 S1 窄口集成时提升（worker 零新增依赖纪律不受影响——这是既有依赖的可见性，不是新工件）。
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
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
