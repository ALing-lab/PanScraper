package com.tamu.pan123.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.tamu.pan123.R

/**
 * 内置字体（都是 SIL OFL 1.1，许可证在 assets/licenses/ofl-*.txt；大陆网络下不依赖 Google Fonts）。
 * - 正文 / 标题：Mona Sans 可变字体（wght + wdth，宽度用 104，与设计稿一致）
 * - 中文：Noto Sans CJK SC 常用字子集（常规 / 粗体两档）。Android 10+ 用 CustomFallbackBuilder 让中文先找它，
 *   子集里没有的字再交给系统字体；Android 8–9 没有这个接口，中文直接用系统字体。
 * - 等宽：Geist Mono 可变字体（数字、路径、文件名）
 * 初始化失败（例如 Robolectric 截图测试里没有原生字体接口）时退回资源字体 / 系统字体，不影响使用。
 */
@OptIn(ExperimentalTextApi::class)
object AppFonts {
    private val WEIGHTS = listOf(400, 500, 600, 700)
    private const val WIDTH = 104f

    /** 只含 Mona Sans 的资源字体族（Android 8–9，或 [init] 没跑 / 失败时用）。 */
    private val monaRes: FontFamily = FontFamily(WEIGHTS.map { w ->
        Font(R.font.mona_sans, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w), FontVariation.width(WIDTH)))
    })

    var sans: FontFamily = monaRes
        private set

    val mono: FontFamily = FontFamily(WEIGHTS.map { w ->
        Font(R.font.geist_mono, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    })

    fun init(ctx: Context) {
        if (Build.VERSION.SDK_INT < 29) return
        runCatching { sans = FontFamily(buildFallbackTypeface(ctx)) }
    }

    @androidx.annotation.RequiresApi(29)
    private fun buildFallbackTypeface(ctx: Context): android.graphics.Typeface {
        val res = ctx.resources
        val monaFonts = WEIGHTS.map { w ->
            android.graphics.fonts.Font.Builder(res, R.font.mona_sans).setWeight(w)
                .setFontVariationSettings("'wght' $w, 'wdth' $WIDTH").build()
        }
        val mona = android.graphics.fonts.FontFamily.Builder(monaFonts.first()).apply { monaFonts.drop(1).forEach { addFont(it) } }.build()
        val noto = android.graphics.fonts.FontFamily.Builder(
            android.graphics.fonts.Font.Builder(res, R.font.noto_sans_sc_regular).setWeight(400).build()
        ).addFont(android.graphics.fonts.Font.Builder(res, R.font.noto_sans_sc_bold).setWeight(700).build()).build()
        return android.graphics.Typeface.CustomFallbackBuilder(mona)
            .addCustomFallback(noto)
            .setSystemFallback("sans-serif")
            .setStyle(android.graphics.fonts.FontStyle(400, android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT))
            .build()
    }
}
