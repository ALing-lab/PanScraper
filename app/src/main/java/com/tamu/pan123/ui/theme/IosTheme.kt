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

/** iOS 系统色（UIKit 语义色的近似值）。 */
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
)

val LightIos = IosColors(
    dark = false,
    groupedBg = Color(0xFFF2F2F7), cell = Color.White, cellPressed = Color(0xFFD1D1D6), elevated = Color.White,
    label = Color.Black, secondaryLabel = Color(0x993C3C43), tertiaryLabel = Color(0x4D3C3C43),
    separator = Color(0x4A3C3C43), fill = Color(0x1F787880), barBg = Color(0xE6F9F9F9),
    blue = Color(0xFF007AFF), green = Color(0xFF34C759), red = Color(0xFFFF3B30), orange = Color(0xFFFF9500),
    yellow = Color(0xFFFFCC00), indigo = Color(0xFF5856D6), purple = Color(0xFFAF52DE), teal = Color(0xFF30B0C7),
    pink = Color(0xFFFF2D55), gray = Color(0xFF8E8E93),
)

val DarkIos = IosColors(
    dark = true,
    groupedBg = Color.Black, cell = Color(0xFF1C1C1E), cellPressed = Color(0xFF3A3A3C), elevated = Color(0xFF2C2C2E),
    label = Color.White, secondaryLabel = Color(0x99EBEBF5), tertiaryLabel = Color(0x4DEBEBF5),
    separator = Color(0x99545458), fill = Color(0x3D767680), barBg = Color(0xD91C1C1E),
    blue = Color(0xFF0A84FF), green = Color(0xFF30D158), red = Color(0xFFFF453A), orange = Color(0xFFFF9F0A),
    yellow = Color(0xFFFFD60A), indigo = Color(0xFF5E5CE6), purple = Color(0xFFBF5AF2), teal = Color(0xFF40C8E0),
    pink = Color(0xFFFF375F), gray = Color(0xFF8E8E93),
)

/** 接近 SF Pro 的字号/字重/字距（Android 上用系统无衬线字体）。 */
object IosType {
    private val f = FontFamily.Default
    val largeTitle = TextStyle(fontFamily = f, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.011.em, lineHeight = 41.sp)
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
    val m3 = if (dark) darkColorScheme(primary = c.blue, background = c.groupedBg, surface = c.cell, onSurface = c.label, error = c.red)
    else lightColorScheme(primary = c.blue, background = c.groupedBg, surface = c.cell, onSurface = c.label, error = c.red)
    CompositionLocalProvider(LocalIos provides c) {
        MaterialTheme(colorScheme = m3, content = content)
    }
}

object Ios {
    val colors: IosColors @Composable get() = LocalIos.current
}
