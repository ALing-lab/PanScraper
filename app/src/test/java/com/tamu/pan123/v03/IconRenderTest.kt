package com.tamu.pan123.v03

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.tamu.pan123.R
import com.tamu.pan123.ui.screens.AppIconTile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 1024px 图标预览（浅色 / 深色走 drawable-night / 单色主题图标）。输出 build/outputs/roborazzi/icon_*.png */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class IconRenderTest {
    @get:Rule val rule = createComposeRule()

    @Config(qualifiers = "w1024dp-h1024dp-notnight-mdpi") @Test fun light() {
        rule.setContent { Box(Modifier.fillMaxSize()) { AppIconTile(1024.dp) } }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/icon_1024_light.png")
    }
    @Config(qualifiers = "w1024dp-h1024dp-night-mdpi") @Test fun dark() {
        rule.setContent { Box(Modifier.fillMaxSize()) { AppIconTile(1024.dp) } }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/icon_1024_dark.png")
    }
    /** Android 13+ 主题图标（单色层，系统着色）。 */
    @Config(qualifiers = "w1024dp-h1024dp-night-mdpi") @Test fun themed() {
        rule.setContent {
            Box(Modifier.size(1024.dp).clip(RoundedCornerShape(230.dp)).background(Color(0xFF2B2F36)), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_launcher_mono), null, Modifier.fillMaxSize().graphicsLayer(scaleX = 1.5f, scaleY = 1.5f),
                    colorFilter = ColorFilter.tint(Color(0xFFD7E3FF)))
            }
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/icon_1024_themed.png")
    }
}
