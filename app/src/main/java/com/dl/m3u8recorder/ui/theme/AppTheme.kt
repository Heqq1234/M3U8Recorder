package com.dl.m3u8recorder.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext // 新增导入
import androidx.compose.ui.graphics.Color // 新增导入
import androidx.compose.ui.text.TextStyle // 新增导入
import androidx.compose.ui.text.font.FontFamily // 新增导入
import androidx.compose.ui.text.font.FontWeight // 新增导入
import androidx.compose.ui.unit.dp // 新增导入
import androidx.compose.ui.unit.sp // 新增导入
import androidx.compose.foundation.shape.RoundedCornerShape // 新增导入
import androidx.compose.material3.Shapes // 新增导入
import androidx.compose.material3.Typography // 新增导入


// 定义一套更丰富的亮色主题颜色
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF673AB7),        // 紫色 - 主要品牌色
    onPrimary = Color.White,            // 在主色上的文字/图标颜色
    primaryContainer = Color(0xFFD0BCFF), // 主色容器背景色
    onPrimaryContainer = Color(0xFF21005D),

    secondary = Color(0xFF6A0DAD),      // 更深一点的紫色 - 次要强调色
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEADDFF), // 次要色容器背景色
    onSecondaryContainer = Color(0xFF21005D),

    tertiary = Color(0xFF4DD0E1),       // 青色 - 第三颜色，用于不那么强调的元素
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFFCCEBF1),
    onTertiaryContainer = Color(0xFF001F25),

    error = Color(0xFFB00020),          // 错误提示色
    onError = Color.White,
    errorContainer = Color(0xFFFCD8DF),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFF9F7FA),     // 整体背景色 (浅灰白，比纯白更柔和)
    onBackground = Color(0xFF1C1B1F),

    surface = Color.White,              // 卡片、对话框等“纸张”表面颜色
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC), // 表面变体色，用于区分次要表面
    onSurfaceVariant = Color(0xFF49454E),

    outline = Color(0xFF7A757F),        // 边框颜色
    inverseOnSurface = Color(0xFFF4EFF4),
    inverseSurface = Color(0xFF313034),
    inversePrimary = Color(0xFFBD93F9), // 反转主色，用于暗色模式中的亮色元素
    // scrim = Color(0xFF000000), // 遮罩层颜色
    // surfaceTint = primary, // 表面着色，通常与 primary 相同
)

// 定义一套更丰富的暗色主题颜色
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFBD93F9),        // 亮紫色 - 主要品牌色
    onPrimary = Color(0xFF38006E),
    primaryContainer = Color(0xFF4F248C),
    onPrimaryContainer = Color(0xFFD0BCFF),

    secondary = Color(0xFFD4BFF9),      // 次要强调色
    onSecondary = Color(0xFF38006E),
    secondaryContainer = Color(0xFF4F248C),
    onSecondaryContainer = Color(0xFFEADDFF),

    tertiary = Color(0xFF7DDDE6),       // 青色 - 第三颜色
    onTertiary = Color(0xFF00363D),
    tertiaryContainer = Color(0xFF004F58),
    onTertiaryContainer = Color(0xFFCCEBF1),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF2B8B5),

    background = Color(0xFF1C1B1F),     // 整体背景色
    onBackground = Color(0xFFE6E1E5),

    surface = Color(0xFF1C1B1F),        // 卡片、对话框等表面颜色
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF49454E),
    onSurfaceVariant = Color(0xFFCBC4CF),

    outline = Color(0xFF948F99),
    inverseOnSurface = Color(0xFF1C1B1F),
    inverseSurface = Color(0xFFE6E1E5),
    inversePrimary = Color(0xFF673AB7),
    // scrim = Color(0xFF000000),
    // surfaceTint = primary,
)

// 定义排版样式
private val AppTypography = Typography(
    displayLarge = TextStyle( // 例如，用于最大的标题
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    headlineLarge = TextStyle( // 主要标题
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold, // 可以加粗
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle( // 导航栏标题或卡片标题
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle( // 主要正文
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    labelMedium = TextStyle( // 小标签或辅助信息
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
    // 可以继续定义其他 Material 3 的 Typography 样式，如 bodySmall, labelLarge, titleSmall 等
)

// 定义形状样式
private val AppShapes = Shapes(
    small = RoundedCornerShape(4.dp),    // 用于小尺寸组件，如芯片
    medium = RoundedCornerShape(8.dp),   // 用于中等尺寸组件，如按钮、输入框
    large = RoundedCornerShape(12.dp)    // 用于大尺寸组件，如卡片、对话框
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 是否启用动态颜色（仅限 Android 12+）
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            // 如果支持动态颜色且系统版本在 Android 12 (S) 及以上
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme // 如果不支持动态颜色或低于 Android 12，则使用预定义暗色方案
        else -> LightColorScheme    // 否则使用预定义亮色方案
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography, // 应用自定义排版
        shapes = AppShapes,         // 应用自定义形状
        content = content
    )
}