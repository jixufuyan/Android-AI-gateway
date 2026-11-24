import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * release 签名：从 keystore.properties 读，仅在文件存在时装配。
 *
 * 本地开发只出 debug 包（见 buildTypes.debug），不需要 keystore.properties；
 * 云端发版时才由 CI 现生成该文件。文件缺失时不在配置阶段报错，
 * 但 release 构建会因无签名配置而失败——正是期望行为。
 */
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

val hasReleaseKeystore = rootProject.file("keystore.properties").exists()

/**
 * debug 签名用的固定 keystore：从 keystore.properties 的 `debugStoreFile` 读，不存在则不装配。
 *
 * 为什么要固定：容器（PRoot rootfs）重置会重新生成 `~/.android/debug.keystore`，
 * debug 包的签名随之改变，之前装好的 debug 版就再也无法原地升级
 * （`INSTALL_FAILED_UPDATE_INCOMPATIBLE`，系统提示「应用签名不一致」）。
 * 把 debug 密钥放到持久位置并在 keystore.properties 里显式指过去，签名就不再随容器重置漂移。
 *
 * CI/他人机器不写 `debugStoreFile`，此处为空，回落到 AGP 默认的 debug 签名，不受影响。
 */
val debugKeystoreFile: File? =
    keystoreProps.getProperty("debugStoreFile")?.let { File(it) }?.takeIf { it.isFile }

fun keystoreValue(key: String): String =
    keystoreProps.getProperty(key)
        ?: error("缺少签名配置 $key：请检查根目录 keystore.properties")

android {
    namespace = "dev.aigw.app"
    compileSdk = 36
    // buildToolsVersion 不再写死：写死一个未安装的版本会让构建直接失败，
    // 让 AGP 自己选已安装的那套（与 MyEmpty 一致）。

    defaultConfig {
        applicationId = "dev.aigw.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 241
        versionName = "2.39.2"
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        // 只有存在 keystore.properties 时才创建 release 签名；
        // 本地只出 debug 包时不依赖该文件。
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreValue("storeFile"))
                storePassword = keystoreValue("storePassword")
                keyAlias = keystoreValue("keyAlias")
                keyPassword = keystoreValue("keyPassword")
            }
        }
        // debug 签名固定到持久 keystore（见上面的 debugKeystoreFile）。
        if (debugKeystoreFile != null) {
            create("debugStable") {
                storeFile = debugKeystoreFile
                storePassword = keystoreValue("debugStorePassword")
                keyAlias = keystoreValue("debugKeyAlias")
                keyPassword = keystoreValue("debugKeyPassword")
            }
        }
    }

    buildTypes {
        // 两个变体都开 R8。注意 AGP 对 debuggable 构建会禁用「优化与混淆」
        // （构建时会提示 “All code optimizations and obfuscation are disabled for
        // debuggable builds”），但**代码裁剪照常生效**——这才是体积的大头：
        // material-icons-extended 的 10660 个未使用图标类会被裁掉。
        // 裁剪后 debug 3.5MB / release 1.4MB（未开前 debug 是 16.8MB）。
        //
        // debug = 本地开发包：applicationId 加 .debug 后缀、应用名加 Debug，
        // 可与 release 包同机共存、互不覆盖；签名固定到持久 keystore（未配置时用 AGP 默认 debug 签名），
        // 否则容器重置会换 key，装上后的 debug 包永远无法原地升级。
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            applicationIdSuffix = ".debug"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (debugKeystoreFile != null) signingConfig = signingConfigs.getByName("debugStable")
            // 崩溃页里的「制造测试崩溃」入口：只存在于 debug 包，release 包里读不到
            resValue("bool", "crash_test_entry", "true")
        }
        // release = 仅由 GitHub Actions 云端构建并发版；本地不产出 release 包。
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
            resValue("bool", "crash_test_entry", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Robolectric 需要 android.jar 里的资源；不开 includeAndroidResources 拿不到 R 与 strings
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    // :core 用 implementation 引 gson，不传递；app 里要直接构造 JsonObject（如动作 payload）
    implementation(libs.gson)

    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    // 登录走 Custom Tab：裸 ACTION_VIEW 会让进程被系统判 idle 冻结，本地回调端口随之失聪
    implementation(libs.androidx.browser)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    // 1.1.0 起不再依赖旧版 Tink 的 alpha 通道；之前停在 alpha06，是加密存储的核心依赖
    implementation(libs.androidx.security.crypto)

    // 首页编辑态的拖拽排序：自己写的手势在 LazyColumn 里反复被滚动抢手势、
    // 重排动画与跟手位移双重叠加，换成这个库（基于官方 animateItem）。
    implementation(libs.reorderable)

    // 液态玻璃底栏：真正的背景模糊（backdrop blur）。Compose 原生做不到，
    // 1.6.10 是最后一个用 Kotlin 2.1.0 构建的版本，与本项目编译器一致。
    implementation(libs.haze)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // 本地 JVM 上的 Android 单元测试：本仓没有 emulator，instrumented 测试跑不了，
    // 用 Robolectric 在 JVM 里验证文件落盘与导出内容这类真实逻辑。
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
