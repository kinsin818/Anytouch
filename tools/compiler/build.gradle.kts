plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:contracts"))
    // S3：编译器判据搬到 :byok（设备与探针共用一份），本模块只留 host 侧 java.net.http 传输与探针入口。
    implementation(project(":byok"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("com.anytouch.tools.compiler.ProbeMainKt")
}
