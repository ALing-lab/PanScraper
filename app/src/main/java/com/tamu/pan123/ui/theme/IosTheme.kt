package com.tamu.pan123.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * 颜色（v0.3.5 起为设计稿「方案 I · 炭与橙」的 token；字段名沿用 iOS 语义，方便各页面不改调用）。
 * - groupedBg / cell / elevated：底 → 面板 → 高一层的面板
 * - blue：现在是「动作色」= 应用图标的橙 #FF8B37（按钮、开关、勾选框、选中的导航图标、进度条等），深浅模式相同
 * - onAccent：橙色上面的字 / 勾 / 开关圆钮，#0F0F0F（8.22:1）
 * - link：可点的文字。深色用橙；浅色下橙字只有 2.33:1 读不清，用同色相加深的 #BB4E00（≥4.5:1）
 * - edge：关着的开关、未勾的勾选框的描边
 */
@Immutable
data class IosColors(
    val dark: Boolean,
    val groupedBg: Color,
    val cell: Color,
    val cellPressed: Color,
    val elevated: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val fill: Color,
    val barBg: Color,
    val blue: Color,
    val green: Color,
    val red: Color,
    val orange: Color,
    val yellow: Color,
    val indigo: Color,
    val purple: Color,
    val teal: Color,
    val pink: Color,
    val gray: Color,
    val accent: Color = Color(0xFFFF8B37),
    val onAccent: Color = Color(0xFF0F0F0F),
    val link: Color = Color(0xFFFF8B37),
    val edge: Color = Color(0xFF8F8F8F),
    val hairline: Color = Color(0x14FFFFFF),
    /** 选中的操作格子用的浅橙底。 */
    val accentTint: Color = Color(0xFF3A2414),
)

val LightIos = IosColors(
    dark = false,
    groupedBg = Color(0xFFFFFFFF), cell = Color(0xFFF3F3F3), cellPressed = Color(0xFFE6E6E6), elevated = Color(0xFFE6E6E6),
    label = Color(0xFF0F0F0F), secondaryLabel = Color(0xFF5F5F5F), tertiaryLabel = Color(0x730F0F0F),
    separator = Color(0x140F0F0F), fill = Color(0xFFE6E6E6), barBg = Color(0xF0FFFFFF),
    blue = Color(0xFFFF8B37), green = Color(0xFF34C759), red = Color(0xFFFF3B30), orange = Color(0xFFFF9500),
    yellow = Color(0xFFFFCC00), indigo = Color(0xFF5856D6), purple = Color(0xFFAF52DE), teal = Color(0xFF30B0C7),
    pink = Color(0xFFFF2D55), gray = Color(0xFF8E8E93),
    link = Color(0xFFBB4E00), edge = Color(0xFF6B6B6B), hairline = Color(0x120F0F0F), accentTint = Color(0xFFFFE3CF),
)

val DarkIos = IosColors(
    dark = true,
    groupedBg = Color(0xFF0F0F0F), cell = Color(0xFF1A1A1A), cellPressed = Color(0xFF242424), elevated = Color(0xFF242424),
    label = Color.White, secondaryLabel = Color(0xFF8F8F8F), tertiaryLabel = Color(0x73FFFFFF),
    separator = Color(0x14FFFFFF), fill = Color(0xFF242424), barBg = Color(0xEB1A1A1A),
    blue = Color(0xFFFF8B37), green = Color(0xFF30D158), red = Color(0xFFFF453A), orange = Color(0xFFFF9F0A),
    yellow = Color(0xFFFFD60A), indigo = Color(0xFF5E5CE6), purple = Color(0xFFBF5AF2), teal = Color(0xFF40C8E0),
    pink = Color(0xFFFF375F), gray = Color(0xFF8E8E93),
    link = Color(0xFFFF8B37), edge = Color(0xFF8F8F8F), hairline = Color(0x14FFFFFF), accentTint = Color(0xFF3A2414),
)

/**
 * 字号沿用 iOS 档位；字体是内置的 Mona Sans（中文回退到内置的 Noto Sans CJK SC 子集，再回退到系统字体），
 * 数字 / 路径 / 文件名用 Geist Mono（[mono]）。见 [AppFonts]。
 */
object IosType {
    private val f get() = AppFonts.sans
    val mono: FontFamily get() = AppFonts.mono
    val largeTitle = TextStyle(fontFamily = f, fontSize = 30.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.03).em, lineHeight = 36.sp)
    val title1 = TextStyle(fontFamily = f, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp)
    val title2 = TextStyle(fontFamily = f, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
    val title3 = TextStyle(fontFamily = f, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp)
    val headline = TextStyle(fontFamily = f, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.024).em, lineHeight = 22.sp)
    val body = TextStyle(fontFamily = f, fontSize = 17.sp, letterSpacing = (-0.024).em, lineHeight = 22.sp)
    val callout = TextStyle(fontFamily = f, fontSize = 16.sp, letterSpacing = (-0.02).em, lineHeight = 21.sp)
    val subhead = TextStyle(fontFamily = f, fontSize = 15.sp, letterSpacing = (-0.015).em, lineHeight = 20.sp)
    val footnote = TextStyle(fontFamily = f, fontSize = 13.sp, letterSpacing = (-0.005).em, lineHeight = 18.sp)
    val caption = TextStyle(fontFamily = f, fontSize = 12.sp, lineHeight = 16.sp)
    val tab = TextStyle(fontFamily = f, fontSize = 10.sp, fontWeight = FontWeight.Medium, lineHeight = 12.sp)
}

/** iOS 动效曲线。 */
object IosMotion {
    val easeOut = CubicBezierEasing(0.2f, 0.9f, 0.1f, 1f)        // UINavigationController push 近似
    val navDuration = 380
    fun <T> navTween() = tween<T>(navDuration, easing = easeOut)
    fun <T> bouncy() = spring<T>(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
    fun <T> snappy() = spring<T>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)
    val offsetSpring = spring<IntOffset>(dampingRatio = 0.86f, stiffness = 420f)
}

val LocalIos = staticCompositionLocalOf { LightIos }

@Composable
fun IosTheme(dark: Boolean, content: @Composable () -> Unit) {
    val c = if (dark) DarkIos else LightIos
    val m3 = if (dark) darkColorScheme(primary = c.accent, onPrimary = c.onAccent, background = c.groupedBg, surface = c.cell, onSurface = c.label, error = c.red)
    else lightColorScheme(primary = c.accent, onPrimary = c.onAccent, background = c.groupedBg, surface = c.cell, onSurface = c.label, error = c.red)
    CompositionLocalProvider(LocalIos provides c) {
        MaterialTheme(colorScheme = m3, content = content)
    }
}

object Ios {
    val colors: IosColors @Composable get() = LocalIos.current
}
