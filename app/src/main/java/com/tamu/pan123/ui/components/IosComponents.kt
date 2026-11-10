package com.tamu.pan123.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
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
                        Text(backLabel, style = IosType.body, color = c.link, maxLines = 1)
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
        color = if (enabled) c.link else c.tertiaryLabel,
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
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(CardRadius)).background(c.cell)
                .border(1.dp, c.hairline, RoundedCornerShape(CardRadius)),
            content = content,
        )
        if (footer != null) Text(
            footer, style = IosType.footnote, color = c.secondaryLabel,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
        )
    }
}

/** 卡片 / 分组的圆角（方案 I：24dp 圆角面板 + 发丝描边）。 */
val CardRadius = 24.dp

/** 彩色圆角小图标（设置页风格）。浅色底（如橙色）上用深色图标，其余用白色。 */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, size: Dp = 29.dp) {
    val glyph = if (tint.luminance() > 0.3f) Color(0xFF0F0F0F) else Color.White
    Box(Modifier.size(size).clip(RoundedCornerShape(9.dp)).background(tint), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = glyph, modifier = Modifier.size(size * 0.62f))
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
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
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

/**
 * 开关（方案 I：52×32；开 = 橙色轨道 + #0F0F0F 圆钮；关 = 面板色轨道 + 描边色圆钮和描边）。
 * 外面留出 48dp 高的点击区域。
 */
@Composable
fun IosSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = Ios.colors
    val track by animateColorAsState(if (checked) c.accent else c.fill, label = "track")
    val border by animateColorAsState(if (checked) c.accent else c.edge, label = "border")
    val knob by animateColorAsState(if (checked) c.onAccent else c.edge, label = "knob")
    val x by animateDpAsState(if (checked) 23.dp else 3.dp, IosMotion.bouncy(), label = "thumb")
    Box(
        Modifier.size(52.dp, 48.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .semantics { role = Role.Switch; toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }
            .pointerInput(enabled, checked) {
                if (enabled) detectTapGestures(onTap = { onCheckedChange(!checked) })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(52.dp, 32.dp).clip(CircleShape).background(track).border(2.dp, border, CircleShape)) {
            // 弹簧动画可能越过终点：用 offset 并限制在轨道内（负 padding 会抛异常 → 闪退，见 v0.3.4）
            Box(
                Modifier.offset(x = x.coerceIn(0.dp, 27.dp), y = 5.dp)
                    .size(22.dp).clip(CircleShape).background(knob)
            )
        }
    }
}

/** 分段控件（方案 I：胶囊形，选中块是高一层的面板色，带弹簧滑动）。 */
@Composable
fun <T> IosSegmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = Ios.colors
    var width by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val idx = options.indexOf(selected).coerceAtLeast(0)
    val segW = if (options.isEmpty()) 0.dp else with(density) { ((width - with(density) { 8.dp.roundToPx() }) / options.size).toDp() }
    val off by animateDpAsState(segW * idx, IosMotion.bouncy(), label = "seg")
    Box(
        modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(c.groupedBg)
            .border(1.dp, c.hairline, RoundedCornerShape(24.dp)).onSizeChanged { width = it.width }.padding(4.dp)
    ) {
        if (width > 0) Box(
            Modifier.offset(x = off).width(segW).fillMaxHeight()
                .clip(RoundedCornerShape(20.dp)).background(c.elevated)
        )
        Row(Modifier.fillMaxSize()) {
            options.forEach { o ->
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .semantics { this.selected = o == selected; role = Role.Tab }
                        .clickable(remember { MutableInteractionSource() }, null) { onSelect(o) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label(o), style = IosType.footnote.copy(fontWeight = if (o == selected) androidx.compose.ui.text.font.FontWeight.SemiBold else null),
                        color = if (o == selected) c.label else c.secondaryLabel, maxLines = 1)
                }
            }
        }
    }
}

/**
 * 大号按钮（方案 I：56dp 高的胶囊，应用图标的橙 #FF8B37 平涂，字是 #0F0F0F；不加描边或阴影）。
 * 传入 [color]（例如红色的危险操作）时用白字。
 */
@Composable
fun FilledButton(text: String, modifier: Modifier = Modifier, color: Color? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Ios.colors
    val bg = if (!enabled) c.fill else color ?: c.accent
    val fg = when {
        !enabled -> c.tertiaryLabel
        color == null || color == c.accent -> c.onAccent
        else -> Color.White
    }
    Box(
        modifier.fillMaxWidth().height(56.dp).pressable(enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button }
            .clip(RoundedCornerShape(28.dp)).background(bg),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = IosType.headline, color = fg) }
}

/**
 * 勾选框（方案 I：22dp 圆角方块，橙色底 + 加粗的 #0F0F0F 勾；部分选中 = 横线；未选 = 描边空框）。
 * 外面是 44dp 的点击区域。
 */
@Composable
fun AccentCheck(checked: Boolean, partial: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Ios.colors
    val on = checked || partial
    Box(
        Modifier.size(44.dp).pressable(enabled = enabled, onClick = onClick)
            .semantics { role = Role.Checkbox; toggleableState = when { checked -> ToggleableState.On; partial -> ToggleableState.Indeterminate; else -> ToggleableState.Off } },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(7.dp))
                .background(if (on && enabled) c.accent else Color.Transparent)
                .then(if (!on || !enabled) Modifier.border(1.5.dp, if (enabled) c.edge else c.tertiaryLabel, RoundedCornerShape(7.dp)) else Modifier),
        ) {
            if (on && enabled) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.1.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                val path = androidx.compose.ui.graphics.Path().apply {
                    if (checked) { moveTo(w * 0.24f, w * 0.52f); lineTo(w * 0.43f, w * 0.70f); lineTo(w * 0.77f, w * 0.31f) }
                    else { moveTo(w * 0.28f, w * 0.5f); lineTo(w * 0.72f, w * 0.5f) }
                }
                drawPath(path, c.onAccent, style = stroke)
            }
        }
    }
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
