package com.tamu.pan123.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tamu.pan123.ui.screens.*
import com.tamu.pan123.ui.theme.Ios
import com.tamu.pan123.ui.theme.IosMotion
import com.tamu.pan123.ui.theme.IosType

enum class Tab(val label: String, val icon: ImageVector, val start: String) {
    CLOUD("云盘", Icons.Rounded.Cloud, "cloud"),
    SCRAPE("刮削", Icons.Rounded.AutoAwesome, "scrape"),
    TASKS("任务", Icons.Rounded.Bolt, "tasks"),
    SETTINGS("设置", Icons.Rounded.Settings, "settings"),
}

/** 底部标签栏高度（页面内容需要让出这块空间）。 */
val LocalBottomInset = compositionLocalOf { 0.dp }
/** 是否为平板分栏布局。 */
val LocalWide = compositionLocalOf { false }
/** 跨标签跳转：切到某个标签，并可在该标签里打开一个页面（如 刮削 › 预览）。 */
val LocalTabSwitch = compositionLocalOf<(Tab, String?) -> Unit> { { _, _ -> } }
private val pendingRoutes = androidx.compose.runtime.mutableStateMapOf<Tab, String>()

// ---- iOS push / pop 转场 ----
// 新页面从右侧滑入（350ms，iOS 导航的自然减速曲线），下面的页面同时左移 30% 并轻微变暗；
// 返回时完全反向。不做透明度渐变（之前的 fade 看起来像「模糊」），整个转场裁剪在内容区里，绝不盖住平板左侧栏。
internal const val NAV_MS = 350
internal val NavEasing = androidx.compose.animation.core.CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
private fun <T> navSpec() = tween<T>(NAV_MS, easing = NavEasing)
private fun AnimatedContentTransitionScope<NavBackStackEntry>.pushEnter(): EnterTransition =
    slideInHorizontally(navSpec()) { it }
private fun AnimatedContentTransitionScope<NavBackStackEntry>.pushExit(): ExitTransition =
    slideOutHorizontally(navSpec()) { -it * 3 / 10 }
private fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(): EnterTransition =
    slideInHorizontally(navSpec()) { -it * 3 / 10 }
private fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(): ExitTransition =
    slideOutHorizontally(navSpec()) { it }

/** 当前这次导航是不是返回（用来决定哪一页在下面、需要变暗）。 */
private val LocalNavPop = compositionLocalOf { mutableStateOf(false) }

/**
 * 所有页面的统一外壳：不透明背景 + 处在下面时的轻微变暗（最暗 10% 黑）。
 */
private fun NavGraphBuilder.page(
    route: String,
    content: @Composable androidx.compose.animation.AnimatedContentScope.(NavBackStackEntry) -> Unit,
) = composable(route) { e ->
    val pop = LocalNavPop.current.value
    val dim by transition.animateFloat(transitionSpec = { navSpec() }, label = "dim") { st ->
        when (st) {
            androidx.compose.animation.EnterExitState.Visible -> 0f
            androidx.compose.animation.EnterExitState.PreEnter -> if (pop) 1f else 0f   // 返回时，下面那页从暗变亮
            androidx.compose.animation.EnterExitState.PostExit -> if (pop) 0f else 1f   // 前进时，被盖住的那页变暗
        }
    }
    Box(Modifier.fillMaxSize().background(Ios.colors.groupedBg)) {
        content(e)
        if (dim > 0.001f) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.10f * dim)))
    }
}

@Composable
fun AppRoot(initialTab: Tab = Tab.CLOUD) {
    val c = Ios.colors
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val controllers = Tab.entries.associateWith { rememberNavController() }
    val switchTab: (Tab, String?) -> Unit = { t, route ->
        if (route != null) pendingRoutes[t] = route
        tab = t
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(c.groupedBg)) {
        val wide = maxWidth >= 720.dp
        CompositionLocalProvider(LocalWide provides wide, LocalTabSwitch provides switchTab) {
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Sidebar(tab, onSelect = { t -> if (t == tab) controllers[t]!!.popBackStack(t.start, false) else tab = t })
                    Box(Modifier.fillMaxHeight().width(0.5.dp).background(c.separator))
                    Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                        Crossfade(tab, animationSpec = tween(220), label = "tab") { t: Tab -> TabHost(t, controllers[t]!!) }
                    }
                }
            } else {
                val barH = 49.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                CompositionLocalProvider(LocalBottomInset provides 49.dp) {
                    Box(Modifier.fillMaxSize()) {
                        Crossfade(tab, animationSpec = tween(220), label = "tab") { t: Tab -> TabHost(t, controllers[t]!!) }
                        TabBar(tab, Modifier.align(Alignment.BottomCenter).height(barH),
                            onSelect = { t -> if (t == tab) controllers[t]!!.popBackStack(t.start, false) else tab = t })
                    }
                }
            }
        }
    }
}

@Composable
private fun TabHost(tab: Tab, nav: NavHostController) {
    val popState = remember { mutableStateOf(false) }
    val stack by nav.currentBackStack.collectAsState()
    val last = remember { IntArray(1) }
    // 在组合阶段同步计算方向，保证转场第一帧就用对的方向
    val size = stack.size
    if (size != last[0]) { if (last[0] != 0) popState.value = size < last[0]; last[0] = size }
    CompositionLocalProvider(LocalNavPop provides popState) {
    NavHost(
        modifier = Modifier.fillMaxSize().clipToBounds(),
        navController = nav, startDestination = tab.start,
        enterTransition = { pushEnter() }, exitTransition = { pushExit() },
        popEnterTransition = { popEnter() }, popExitTransition = { popExit() },
    ) {
        when (tab) {
            Tab.CLOUD -> cloudGraph(nav)
            Tab.SCRAPE -> scrapeGraph(nav)
            Tab.TASKS -> tasksGraph(nav)
            Tab.SETTINGS -> settingsGraph(nav)
        }
    }
    }
    val pending = pendingRoutes[tab]
    LaunchedEffect(pending) {
        if (pending != null) {
            pendingRoutes.remove(tab)
            runCatching {
                if (nav.currentDestination?.route != pending) nav.navigate(pending) { launchSingleTop = true }
            }
        }
    }
}

private fun NavGraphBuilder.cloudGraph(nav: NavHostController) {
    page("cloud") { CloudScreen(nav) }
}
private fun NavGraphBuilder.scrapeGraph(nav: NavHostController) {
    page("scrape") { ScrapeScreen(nav) }
    page("scrape/preview") { PreviewScreen(nav) }
    page("scrape/preview/ops/{type}") { e -> PreviewOpsScreen(nav, e.arguments?.getString("type") ?: "rename") }
    page("scrape/rematch/{groupId}") { entry ->
        RematchScreen(nav, entry.arguments?.getString("groupId").orEmpty())
    }
}
private fun NavGraphBuilder.tasksGraph(nav: NavHostController) {
    page("tasks") { TasksScreen(nav) }
    page("tasks/items/{filter}") { e -> TaskItemsScreen(nav, e.arguments?.getString("filter") ?: "progress") }
}
private fun NavGraphBuilder.settingsGraph(nav: NavHostController) {
    page("settings") { SettingsScreen(nav) }
    page("settings/account") { AccountCenterScreen(nav) }
    page("settings/account/web-login") { WebLoginScreen(nav) }
    page("settings/account/password-login") { PasswordLoginScreen(nav) }
    page("settings/account/tmdb-key") { TmdbKeyScreen(nav) }
    page("settings/account/add") { AddAccountScreen(nav) }
    page("settings/account/add/webdav") { WebDavFormScreen(nav) }
    page("settings/account/guangya-login?target={target}") { e ->
        GuangyaLoginScreen(nav, e.arguments?.getString("target")?.takeIf { it.isNotBlank() })
    }
    page("settings/account/p115-login?target={target}") { e ->
        P115LoginScreen(nav, e.arguments?.getString("target")?.takeIf { it.isNotBlank() })
    }
    page("settings/account/add/info/{kind}") { e -> ProviderInfoScreen(nav, e.arguments?.getString("kind").orEmpty()) }
    page("settings/account/drive/{id}") { e -> DriveAccountScreen(nav, e.arguments?.getString("id").orEmpty()) }
    page("settings/data") { DataScreen(nav) }
    page("settings/about") { AboutScreen(nav) }
    page("settings/licenses") { com.tamu.pan123.ui.screens.LicensesScreen(nav) }
    page("settings/licenses/{i}") { e -> com.tamu.pan123.ui.screens.LicenseDetailScreen(nav, e.arguments?.getString("i")?.toIntOrNull() ?: -1) }
    page("settings/history") { com.tamu.pan123.ui.screens.HistoryScreen(nav) }
    page("settings/history/{id}") { e -> com.tamu.pan123.ui.screens.HistoryDetailScreen(nav, e.arguments?.getString("id").orEmpty()) }
    page("settings/tmdb-net") { com.tamu.pan123.ui.screens.TmdbNetScreen(nav) }
}

@Composable
private fun TabBar(selected: Tab, modifier: Modifier = Modifier, onSelect: (Tab) -> Unit) {
    val c = Ios.colors
    Column(modifier.fillMaxWidth().background(c.barBg)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
        Row(Modifier.fillMaxWidth().height(49.dp)) {
            Tab.entries.forEach { t ->
                val tint by animateColorAsState(if (t == selected) c.blue else c.gray, label = "tabtint")
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .clickable(remember { MutableInteractionSource() }, null) { onSelect(t) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Icon(t.icon, t.label, tint = tint, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(t.label, style = IosType.tab, color = tint)
                }
            }
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

/** 平板：iPadOS 风格侧边栏。 */
@Composable
private fun Sidebar(selected: Tab, onSelect: (Tab) -> Unit) {
    val c = Ios.colors
    Column(
        Modifier.width(300.dp).fillMaxHeight().background(if (c.dark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7))
            .windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text(com.tamu.pan123.AppInfo.NAME, style = IosType.largeTitle, color = c.label, modifier = Modifier.padding(start = 8.dp, bottom = 16.dp))
        Tab.entries.forEach { t ->
            val sel = t == selected
            val bg by animateColorAsState(if (sel) c.blue else Color.Transparent, label = "sbbg")
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp)).background(bg)
                    .clickable(remember { MutableInteractionSource() }, null) { onSelect(t) }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(t.icon, null, tint = if (sel) Color.White else c.blue, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(t.label, style = IosType.body, color = if (sel) Color.White else c.label)
            }
        }
    }
}

@Composable
fun bottomInset(): Dp = LocalBottomInset.current
