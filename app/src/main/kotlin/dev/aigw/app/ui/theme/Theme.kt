package dev.aigw.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// 黑金极简设计规范 Token：主色渐变两端（原版默认 #D4A843 / #B88A20）。
// 名字保留 Gold* 是为了不改动已有引用（Appearance.kt 的 AppColors 自定义字段名同样是 goldStart/goldEnd）；
// 语义就是「主色渐变的两端」。
val GoldAccent = Color(0xFFD4A843)
val GoldText = Color(0xFFB88A20)
val HairlineBorder = Color(0xFFECECF0)

/** 告警琥珀：客户端中断的调用记录与日志 WARN 级别共用（非错误态，区别于 error 红）。 */
val WarningAmber = Color(0xFFB26A00)

/** 近黑深色（与浅色 scheme 的 onSurface 同值）：趋势图「输出」序列的线条/图例与色相环滑块描边等需压深的深色前景。 */
val DeepInk = Color(0xFF1F2329)

/** 用 [app] 的两端金构造浅色 scheme；其余语义色固定。 */
private fun lightScheme(app: AppColors): ColorScheme = lightColorScheme(
    primary = app.goldStart,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFBF6EA),
    onPrimaryContainer = Color(0xFF6B4E0E),
    secondary = app.goldText,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF5F5F7),
    onSecondaryContainer = Color(0xFF0D0D0D),
    tertiary = Color(0xFF3D8B7D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE3F2EC),
    onTertiaryContainer = Color(0xFF2E7D66),
    background = Color(0xFFF5F5F6),
    onBackground = Color(0xFF1F2329),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F2329),
    surfaceVariant = Color(0xFFF0F1F3),
    onSurfaceVariant = Color(0xFF8A8F99),
    surfaceContainerHighest = Color(0xFFF0F0F2),
    outline = HairlineBorder,
    outlineVariant = Color(0xFFECECF0),
    error = Color(0xFFC0453B),
    onError = Color.White,
    errorContainer = Color(0xFFFBE9E7),
    onErrorContainer = Color(0xFFB33F33),
)

/** 用 [app] 的两端金构造深色 scheme；其余语义色固定。 */
private fun darkScheme(app: AppColors): ColorScheme = darkColorScheme(
    primary = app.goldStart,
    onPrimary = Color(0xFF141414),
    primaryContainer = Color(0xFF2A2210),
    onPrimaryContainer = Color(0xFFE5C158),
    secondary = app.goldText,
    onSecondary = Color(0xFF141414),
    secondaryContainer = Color(0xFF1E1E1E),
    onSecondaryContainer = Color(0xFFF5F5F5),
    tertiary = Color(0xFF10B981),
    onTertiary = Color(0xFF141414),
    tertiaryContainer = Color(0xFF064E3B),
    onTertiaryContainer = Color(0xFFD1FAE5),
    background = Color(0xFF101214),
    onBackground = Color(0xFFE9EBEF),
    surface = Color(0xFF17191C),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFF8A8F99),
    surfaceContainerHighest = Color(0xFF262626),
    outline = Color(0xFF2A2A2A),
    outlineVariant = Color(0xFF2A2A2A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/**
 * 应用主题。
 *
 * @param themeMode 明暗模式（跟随系统 / 深色 / 浅色）。
 * @param appColors 金色渐变两端；通过 [LocalAppColors] 下发给 UI。
 * @param backgroundOverride 只替换页面底色，卡片/表面色不受影响。
 * @param dynamicColor 动态取色（Android 12+）；开启时优先级最高，配色方案不再生效。
 */
@Composable
fun AiGatewayTheme(
    dynamicColor: Boolean = false,
    themeMode: ThemeMode = ThemeMode.System,
    appColors: AppColors = defaultAppColors,
    backgroundOverride: Color? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    // 必须 remember：dynamicXxxColorScheme() 每次调用都会重新读取系统资源，
    // 而主题在每次重组时都会被求值——不缓存就是持续的重复分配。
    val scheme = remember(dynamicColor, dark, appColors, backgroundOverride, context) {
        val base = when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            dark -> darkScheme(appColors)
            else -> lightScheme(appColors)
        }
        if (backgroundOverride != null) base.copy(background = backgroundOverride) else base
    }
    CompositionLocalProvider(LocalAppColors provides appColors) {
        MaterialTheme(colorScheme = scheme, shapes = AppShapes, content = content)
    }
}
