import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    // 只看生产代码：测试里的桩类、固定数据常会触发误报
    source.setFrom(files("src/main/kotlin"))
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required.set(true)
        txt.required.set(false)
        xml.required.set(false)
        sarif.required.set(false)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 仅使用 Android 上同样可用的 API（HttpURLConnection + 纯 Java 库），使 :core 既能
    // 在本机 JVM 直接跑测试/联调，也能原样打进 Android 应用。
    implementation(libs.gson)
    implementation(libs.nanohttpd)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
