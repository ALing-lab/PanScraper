package com.tamu.pan123.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tamu.pan123.ui.theme.Ios
import com.tamu.pan123.ui.theme.IosMotion
import com.tamu.pan123.ui.theme.IosType

/** iOS 式按压缩放 + 变暗。 */
fun Modifier.pressable(
    enabled: Boolean = true,
    scaleDown: Float = 0.97f,
    onClick: () -> Unit,
): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) scaleDown else 1f, IosMotion.snappy(), label = "press")
    this
        .graphicsLayer { scaleX = s; scaleY = s; alpha = if (pressed) 0.7f else 1f }
        .clickable(src, indication = null, enabled = enabled, onClick = onClick)
}


/**
 * 大标题页面：顶部大标题随滚动收起成居中的小标题，导航栏半透明（类似 UINavigationBar 的 large title）。
 */
@Composable
fun LargeTitlePage(
    title: String,
    onBack: (() -> Unit)? = null,
    backLabel: String = "返回",
    trailing: @Composable RowScope.() -> Unit = {},
    state: LazyListState = rememberLazyListState(),
    bottomInset: Dp = 0.dp,
    header: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val c = Ios.colors
    val density = LocalDensity.current
    val collapsed by remember {
        derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > with(density) { 40.dp.toPx() } }
    }
    val barAlpha by animateFloatAsState(if (collapsed) 1f else 0f, IosMotion.snappy(), label = "bar")
    Box(Modifier.fillMaxSize().background(c.groupedBg)) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 44.dp,
                bottom = bottomInset + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
            ),
        ) {
            item(key = "__large_title") {
                Text(
                    title, style = IosType.largeTitle, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp)
                        .graphicsLayer { alpha = 1f - barAlpha * 0.9f },
                )
            }
            if (header != null) item(key = "__header") { header() }
            content()
        }
        // 导航栏（半透明 + 细分隔线）
        Column(
            Modifier.fillMaxWidth().background(c.barBg.copy(alpha = c.barBg.alpha * barAlpha))
        ) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Box(Modifier.fillMaxWidth().height(44.dp)) {
                if (onBack != null) {
                    Row(
                        Modifier.align(Alignment.CenterStart).padding(start = 6.dp).pressable(onClick = onBack)
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, null, tint = c.blue, modifier = Modifier.size(20.dp))
                        Text(backLabel, style = IosType.body, color = c.blue, maxLines = 1)
                    }
                }
                Text(
                    title, style = IosType.headline, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 96.dp).graphicsLayer { alpha = barAlpha },
                )
                Row(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator.copy(alpha = c.separator.alpha * barAlpha)))
        }
    }
}

/** 导航栏右侧文字按钮。 */
@Composable
fun BarButton(text: String, enabled: Boolean = true, bold: Boolean = false, onClick: () -> Unit) {
    val c = Ios.colors
    Text(
        text, style = if (bold) IosType.headline else IosType.body,
        color = if (enabled) c.blue else c.tertiaryLabel,
        modifier = Modifier.pressable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

/** 分组圆角列表（inset grouped）。 */
fun LazyListScope.section(
    key: String,
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    item(key = key) { GroupedSection(header, footer, content = content) }
}

@Composable
fun GroupedSection(
    header: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Ios.colors
    Column(modifier.padding(horizontal = 16.dp).padding(top = if (header == null) 20.dp else 12.dp)) {
        if (header != null) Text(
            header.uppercase(), style = IosType.footnote, color = c.secondaryLabel,
            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp, top = 8.dp),
        )
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.cell), content = content)
        if (footer != null) Text(
            footer, style = IosType.footnote, color = c.secondaryLabel,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
        )
    }
}

/** 彩色圆角小图标（设置页风格）。 */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, size: Dp = 29.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(7.dp)).background(tint), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.62f))
    }
}

/** 列表行：图标、标题/副标题、右侧值、箭头；按下时背景变灰（类似 UITableViewCell 选中）。 */
@Composable
fun IosRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    value: String? = null,
    chevron: Boolean = false,
    titleColor: Color? = null,
    showDivider: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = Ios.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val bg by animateColorAsState(if (pressed) c.cellPressed else c.cell, label = "rowbg")
    val indent = when {
        leading != null -> 16.dp + 44.dp
        icon != null -> 16.dp + 29.dp + 14.dp
        else -> 16.dp
    }
    Box(
        Modifier.fillMaxWidth().background(bg)
            .then(if (onClick != null) Modifier.clickable(src, null, onClick = onClick) else Modifier)
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 16.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) { leading(); Spacer(Modifier.width(12.dp)) }
            else if (icon != null) { IconBadge(icon, iconTint ?: c.blue); Spacer(Modifier.width(14.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, style = IosType.body, color = titleColor ?: c.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = IosType.footnote, color = c.secondaryLabel, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (value != null) Text(value, style = IosType.body, color = c.secondaryLabel, maxLines = 1,
                modifier = Modifier.padding(start = 8.dp))
            if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
            if (chevron) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.tertiaryLabel,
                modifier = Modifier.padding(start = 4.dp).size(22.dp))
        }
        if (showDivider) Box(
            Modifier.align(Alignment.BottomStart).padding(start = indent).fillMaxWidth().height(0.5.dp).background(c.separator)
        )
    }
}

/** iOS 开关（51×31，绿色，弹簧动画）。 */
@Composable
fun IosSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = Ios.colors
    val track by animateColorAsState(if (checked) c.green else c.fill, label = "track")
    val x by animateDpAsState(if (checked) 22.dp else 2.dp, IosMotion.bouncy(), label = "thumb")
    var pressed by remember { mutableStateOf(false) }
    val w by animateDpAsState(if (pressed) 34.dp else 27.dp, IosMotion.snappy(), label = "w")
    Box(
        Modifier.size(51.dp, 31.dp).clip(CircleShape).background(track)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .pointerInput(enabled, checked) {
                if (enabled) detectTapGestures(
                    onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                    onTap = { onCheckedChange(!checked) },
                )
            }
    ) {
        Box(
            // 按住时滑块变宽、弹簧动画还会越过终点，算出来的左边距可能 < 0 或超出轨道。
            // 之前用 padding（负值会抛 IllegalArgumentException → 闪退），现在用 offset 并限制在轨道内。
            Modifier.offset(x = (if (checked) x - (w - 27.dp) else x).coerceIn(0.dp, (51.dp - w).coerceAtLeast(0.dp)), y = 2.dp)
                .size(w, 27.dp).shadow(3.dp, CircleShape).clip(CircleShape).background(Color.White)
        )
    }
}

/** iOS 分段控件，选中块带弹簧滑动。 */
@Composable
fun <T> IosSegmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = Ios.colors
    var width by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val idx = options.indexOf(selected).coerceAtLeast(0)
    val segW = if (options.isEmpty()) 0.dp else with(density) { (width / options.size).toDp() }
    val off by animateDpAsState(segW * idx, IosMotion.bouncy(), label = "seg")
    Box(
        modifier.height(32.dp).clip(RoundedCornerShape(9.dp)).background(c.fill).onSizeChanged { width = it.width }
    ) {
        if (width > 0) Box(
            Modifier.padding(2.dp).offset(x = off).width(segW - 4.dp).fillMaxHeight()
                .shadow(2.dp, RoundedCornerShape(7.dp)).clip(RoundedCornerShape(7.dp))
                .background(if (c.dark) Color(0xFF636366) else Color.White)
        )
        Row(Modifier.fillMaxSize()) {
            options.forEach { o ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable(remember { MutableInteractionSource() }, null) { onSelect(o) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label(o), style = IosType.footnote.copy(fontWeight = if (o == selected) androidx.compose.ui.text.font.FontWeight.SemiBold else null), color = c.label)
                }
            }
        }
    }
}

/** 大号填充按钮（iOS 风格）。 */
@Composable
fun FilledButton(text: String, modifier: Modifier = Modifier, color: Color? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Ios.colors
    Box(
        modifier.fillMaxWidth().height(50.dp).pressable(enabled = enabled, onClick = onClick)
            .clip(RoundedCornerShape(12.dp)).background(if (enabled) (color ?: c.blue) else c.fill),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = IosType.headline, color = if (enabled) Color.White else c.tertiaryLabel) }
}

/** 空状态。 */
@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val c = Ios.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = c.tertiaryLabel, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, style = IosType.title3, color = c.label)
        Spacer(Modifier.height(6.dp))
        Text(message, style = IosType.subhead, color = c.secondaryLabel, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(20.dp)); action() }
    }
}

@Composable
fun FadeVisible(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) { content() }
}
