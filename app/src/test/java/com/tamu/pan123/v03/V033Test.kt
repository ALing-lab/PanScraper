package com.tamu.pan123.v03

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.github.takahirom.roborazzi.captureRoboImage
import com.tamu.pan123.ui.screens.AboutScreen
import com.tamu.pan123.ui.screens.LicenseDetailScreen
import com.tamu.pan123.ui.screens.LicensesScreen
import com.tamu.pan123.ui.screens.OSS_LIBS
import com.tamu.pan123.ui.theme.IosTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** v0.3.3：应用名 PanScraper + GPL-3.0。截图输出 build/outputs/roborazzi/v033_*.png */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class V033Test {
    @get:Rule val rule = createComposeRule()

    @Test fun appLabel() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals("PanScraper", app.getString(com.tamu.pan123.R.string.app_name))
    }

    @Test fun appLicenseIsGpl3AndAllAssetsExist() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals("GNU GPL v3.0", OSS_LIBS[0].license)
        val gpl = app.assets.open("licenses/gpl-3.0-app.txt").bufferedReader().readText()
        assertTrue(gpl.startsWith("PanScraper\nCopyright (C) 2026 ALing-lab"))
        assertTrue(gpl.contains("GNU GENERAL PUBLIC LICENSE") && gpl.contains("END OF TERMS AND CONDITIONS"))
        // 所有依赖都是 GPL-3.0 兼容许可，且全文都在
        for (l in OSS_LIBS.drop(1)) {
            // v0.3.5 起内置字体（Mona Sans / Geist Mono / Noto Sans SC）为 SIL OFL 1.1，可与 GPL 程序一起分发
            assertTrue(l.license, l.license.startsWith("Apache License 2.0") || l.license.startsWith("MIT") || l.license.startsWith("SIL Open Font License 1.1"))
            assertTrue(l.file, app.assets.open("licenses/${l.file}").bufferedReader().readText().length > 500)
        }
    }

    @Test fun aboutShot() {
        rule.setContent { IosTheme(false) { val nav = rememberNavController(); NavHost(nav, "a") { composable("a") { AboutScreen(nav) } } } }
        assertTrue(rule.onAllNodesWithText("PanScraper").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("GPL-3.0").fetchSemanticsNodes().isNotEmpty())
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/v033_about_light.png")
    }

    @Config(qualifiers = "w411dp-h1300dp-xhdpi") @Test fun licensesShot() {
        rule.setContent { IosTheme(false) { val nav = rememberNavController(); NavHost(nav, "a") { composable("a") { LicensesScreen(nav) } } } }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/v033_licenses_light.png")
    }

    @Test fun gplDetailShot() {
        rule.setContent { IosTheme(false) { val nav = rememberNavController(); NavHost(nav, "a") { composable("a") { LicenseDetailScreen(nav, 0) } } } }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/v033_license_gpl_light.png")
    }
}
