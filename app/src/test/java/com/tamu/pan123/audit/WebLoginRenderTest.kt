package com.tamu.pan123.audit

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.navigation.compose.rememberNavController
import com.tamu.pan123.ui.screens.WebLoginScreen
import com.tamu.pan123.ui.theme.IosTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 真网页为空时，原生返回、刷新和线路控件仍应显示在平板上。Robolectric 不加载真实网页。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w1280dp-h800dp-land")
class WebLoginRenderTest {
    @get:Rule val compose=createComposeRule()
    @Test fun tabletShowsNativeControlsAlongsideWebView() {
        compose.setContent { IosTheme(true) { WebLoginScreen(rememberNavController()) } }
        compose.onNodeWithText("网页登录").assertIsDisplayed()
        compose.onNodeWithText("刷新").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
        compose.onNodeWithText("换线路").assertIsDisplayed()
        compose.onNodeWithText("切换手机版").assertIsDisplayed()
    }
}
