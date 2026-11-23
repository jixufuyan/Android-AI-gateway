// Top-level build file（骨架对齐 MyEmpty：插件版本全部走 gradle/libs.versions.toml）。
//
// 与旧版的差异：插件不再写死字面版本号，改用 alias(libs.plugins.*)。
// detekt 保留（:core 里 apply 它）——若初始化卡在 detekt 与 Gradle 9 的兼容性上，
// 把它从本文件与 core/build.gradle.kts 同时摘掉即可（两处一起删，别只删一处）。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
