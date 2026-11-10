package com.tamu.pan123.v03

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.tamu.pan123.ui.theme.IosType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.tamu.pan123.data.AppDirs
import com.tamu.pan123.data.SecureStore
import com.tamu.pan123.data.Settings
import com.tamu.pan123.data.naming.Naming
import com.tamu.pan123.ui.components.IosSwitch
import com.tamu.pan123.ui.screens.NamingRows
import com.tamu.pan123.ui.theme.Ios
import com.tamu.pan123.ui.theme.IosTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** v0.3.4：打开「简洁命名」/「播放器兼容命名」时闪退。用真实的点按手势（按下→停留→抬起）点开关，并让动画跑完。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1300dp-xhdpi")
class V034Test {
    @get:Rule val rule = createComposeRule()

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        SecureStore.init(app); AppDirs.init(app)
        Thread.sleep(600) // 等 App.onCreate 里 DataStore 的初始读取完成
        Settings.setSimpleNaming(false); Settings.setTmdbInFileName(false)
    }
    @After fun tearDown() { Settings.setSimpleNaming(false); Naming.simpleNaming = false; Naming.tmdbInFileName = false }

    /** 像手指一样点：按下、停留 [holdMs]、抬起，然后把动画逐帧跑完。 */
    private fun tapSwitchBesides(label: String, holdMs: Long = 120) {
        val row = rule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val d = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        val p = Offset(root.right - (14f + 25.5f) * d, row.center.y)
        rule.mainClock.autoAdvance = false
        rule.onRoot().performTouchInput { down(p) }
        rule.mainClock.advanceTimeBy(holdMs)
        rule.onRoot().performTouchInput { up() }
        repeat(100) { rule.mainClock.advanceTimeByFrame() } // ~1.6s 动画，逐帧渲染
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
    }

    @Test fun toggleNamingSwitchesOnAndOffDoesNotCrash() {
        rule.setContent {
            IosTheme(false) {
                Column(Modifier.fillMaxSize().background(Ios.colors.cell)) { NamingRows(showDivider = false) }
            }
        }
        tapSwitchBesides("简洁命名")
        assertTrue(Settings.simpleNaming.value)
        tapSwitchBesides("播放器兼容命名")
        assertTrue(Settings.tmdbInFileName.value)
        assertTrue(Naming.simpleNaming && Naming.tmdbInFileName)
        rule.onNodeWithText("{tmdb-295558}", substring = true).assertExists()
        // 关掉子开关、再关主开关（回弹动画会越过起点）
        tapSwitchBesides("播放器兼容命名")
        assertEquals(false, Settings.tmdbInFileName.value)
        tapSwitchBesides("简洁命名")
        assertEquals(false, Settings.simpleNaming.value)
    }

    /** 所有用 IosSwitch 的开关（调试日志、历史记录、TMDB 网络、WebDAV 等）共用同一个组件：快点 / 慢点、开 / 关都不能崩。 */
    @Test fun iosSwitchSurvivesTapsWithAnyHoldTime() {
        rule.setContent {
            IosTheme(false) {
                var on by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxSize()) {
                    com.tamu.pan123.ui.components.IosRow("开关", showDivider = false, trailing = { IosSwitch(on, { on = it }) })
                }
            }
        }
        for (hold in listOf(0L, 16L, 40L, 80L, 120L, 300L, 800L)) {
            tapSwitchBesides("开关", hold); tapSwitchBesides("开关", hold)
        }
    }
    /** 截图：点开两个开关后的「刮削」命名区（浅色）。输出 build/outputs/roborazzi/v034_naming_on_light.png */
    @Test fun namingOnShotAfterRealTaps() {
        rule.setContent {
            IosTheme(false) {
                val c = Ios.colors
                Column(Modifier.fillMaxSize().background(c.groupedBg).padding(16.dp)) {
                    Text("刮削", style = IosType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp, top = 24.dp))
                    Column(Modifier.clip(RoundedCornerShape(10.dp)).background(c.cell)) { NamingRows(showDivider = false) }
                }
            }
        }
        tapSwitchBesides("简洁命名"); tapSwitchBesides("播放器兼容命名")
        assertTrue(Settings.simpleNaming.value && Settings.tmdbInFileName.value)
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/v034_naming_on_light.png")
    }
}
