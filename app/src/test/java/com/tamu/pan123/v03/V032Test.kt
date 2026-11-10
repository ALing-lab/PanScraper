package com.tamu.pan123.v03

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.github.takahirom.roborazzi.captureRoboImage
import com.tamu.pan123.AppInfo
import com.tamu.pan123.data.Settings
import com.tamu.pan123.ui.screens.AboutScreen
import com.tamu.pan123.ui.screens.NamingRows
import com.tamu.pan123.ui.theme.Ios
import com.tamu.pan123.ui.theme.IosTheme
import com.tamu.pan123.ui.theme.IosType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

/** v0.3.2：关于页（开发者 / 联系方式）+ 精简后的命名开关说明。截图输出 build/outputs/roborazzi/v032_*.png */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class V032Test {
    @get:Rule val rule = createComposeRule()
    @After fun tearDown() { Settings.setSimpleNaming(false) }

    private fun about() = rule.setContent { IosTheme(false) { val nav = rememberNavController(); NavHost(nav, "a") { composable("a") { AboutScreen(nav) } } } }

    @Test fun constants() {
        assertEquals("ALing-lab", AppInfo.AUTHOR)
        assertEquals("ALing-lab/PanScraper", AppInfo.GITHUB_REPO)
        assertEquals("https://github.com/ALing-lab/PanScraper", AppInfo.GITHUB_URL)
    }

    @Test fun contactCopiesOnlyDigits() {
        about()
        assertTrue(rule.onAllNodesWithText("开发者").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("ALing-lab").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("作者").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("QQ 65646589").performClick()
        rule.waitForIdle()
        val cm = RuntimeEnvironment.getApplication().getSystemService(android.content.ClipboardManager::class.java)
        assertEquals("65646589", cm.primaryClip!!.getItemAt(0).text.toString())
        assertEquals("已复制 QQ 号", ShadowToast.getTextOfLatestToast())
    }

    @Test fun aboutLight() { about(); rule.onRoot().captureRoboImage("build/outputs/roborazzi/v032_about_light.png") }

    private fun naming(simple: Boolean, tmdb: Boolean, name: String) {
        Thread.sleep(600) // 等 DataStore 初始读取完成
        Settings.setSimpleNaming(simple); Settings.setTmdbInFileName(tmdb)
        rule.setContent {
            IosTheme(false) {
                val c = Ios.colors
                Column(Modifier.fillMaxSize().background(c.groupedBg).padding(16.dp)) {
                    Text("刮削", style = IosType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp, top = 24.dp))
                    Column(Modifier.clip(RoundedCornerShape(10.dp)).background(c.cell)) { NamingRows(showDivider = false) }
                }
            }
        }
        assertTrue(rule.onAllNodesWithText("收起").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("展开说明").fetchSemanticsNodes().isEmpty())
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/v032_$name.png")
    }
    @Test fun namingOff() = naming(false, false, "naming_off_light")
    @Test fun namingSimple() = naming(true, false, "naming_simple_light")
    @Test fun namingBoth() = naming(true, true, "naming_both_light")
}
