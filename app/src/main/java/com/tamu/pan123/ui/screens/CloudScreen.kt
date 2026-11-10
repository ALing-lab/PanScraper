package com.tamu.pan123.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.tamu.pan123.data.SecureStore
import com.tamu.pan123.data.pan.BrowseSelection
import com.tamu.pan123.data.pan.Crumb
import com.tamu.pan123.data.drive.AccountStore
import com.tamu.pan123.data.drive.CloudEntry
import com.tamu.pan123.data.drive.DriveAccount
import com.tamu.pan123.data.drive.DriveRegistry
import com.tamu.pan123.ui.bottomInset
import com.tamu.pan123.ui.components.*
import com.tamu.pan123.ui.theme.Ios
import com.tamu.pan123.ui.theme.IosType
import kotlinx.coroutines.launch

/** 跨页面共享的扫描目标（云盘页选好的文件夹）。 */
object ScanTargets {
    private val _selection = kotlinx.coroutines.flow.MutableStateFlow(BrowseSelection())
    private val _fullDrive = kotlinx.coroutines.flow.MutableStateFlow(false)
    val selectionFlow: kotlinx.coroutines.flow.StateFlow<BrowseSelection> = _selection
    val fullDriveFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _fullDrive
    var selection: BrowseSelection
        get() = _selection.value
        set(v) { _selection.value = v }
    var fullDrive: Boolean
        get() = _fullDrive.value
        set(v) { _fullDrive.value = v }
}

/** 测试/截图注入的目录列表；非 null 时跳过网络。 */
object CloudDemo {
    var items: List<CloudEntry>? = null
}

@Composable
fun CloudScreen(nav: NavHostController) {
    val c = Ios.colors
    val session by SecureStore.session.collectAsState()
    val accounts by AccountStore.accounts.collectAsState()
    val activeId by AccountStore.activeId.collectAsState()
    val acct = accounts.firstOrNull { it.id == activeId } ?: accounts.first()
    val loggedIn = remember(acct, session) { AccountStore.isLoggedIn(acct) }
    var picking by remember { mutableStateOf(false) }

    if (!loggedIn || !acct.provider.implemented) {
        LargeTitlePage("云盘", bottomInset = bottomInset()) {
            item { DrivePicker(accounts, acct, picking, { picking = !picking }) { picking = false } }
            item {
                if (!acct.provider.implemented) {
                    EmptyState(
                        Icons.Rounded.Construction,
                        "暂不支持 ${acct.provider.label}",
                        "这个网盘类型暂不支持浏览，请在上方切换到其它账号。",
                    )
                } else {
                    EmptyState(
                        Icons.Rounded.CloudOff,
                        "还没有登录 ${acct.label}",
                        "到「设置 › 资源中心」登录后，这里会显示云盘的文件夹。",
                    )
                }
            }
            item {
                Text(
                    "可在上方切换网盘；添加 / 删除账号请到底部「设置」→「资源中心」。",
                    style = IosType.footnote, color = c.secondaryLabel,
                    modifier = Modifier.padding(horizontal = 40.dp),
                )
            }
        }
        return
    }
    key(acct.id) { CloudBrowser(nav, acct, accounts, picking, { picking = !picking }) { picking = false } }
}

/** 顶部「当前网盘」切换器：点开后列出所有账号，带勾选。 */
@Composable
fun DrivePicker(
    accounts: List<DriveAccount>,
    current: DriveAccount,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPicked: () -> Unit,
) {
    val c = Ios.colors
    GroupedSection {
        IosRow(
            "当前网盘",
            icon = Icons.Rounded.Storage,
            iconTint = c.teal,
            value = current.label,
            chevron = true,
            showDivider = expanded,
        ) { onToggle() }
        if (expanded) {
            accounts.forEachIndexed { i, a ->
                IosRow(
                    a.label,
                    subtitle = a.provider.label + (if (!a.provider.implemented) " · 暂不支持" else "") + (a.provider.authorNote?.let { "\n$it" } ?: ""),
                    showDivider = i < accounts.lastIndex,
                    trailing = if (a.id == current.id) {
                        { Icon(Icons.Rounded.Check, null, tint = c.blue, modifier = Modifier.size(18.dp)) }
                    } else null,
                ) {
                    if (a.id != current.id) {
                        AccountStore.setActive(a.id)
                        // 切换网盘后，旧网盘的扫描目标失效
                        if (ScanTargets.selection.accountId != a.id) {
                            ScanTargets.selection = BrowseSelection(accountId = a.id)
                            ScanTargets.fullDrive = false
                        }
                    }
                    onPicked()
                }
            }
        }
    }
}

@Composable
private fun CloudBrowser(
    nav: NavHostController,
    acct: DriveAccount,
    accounts: List<DriveAccount>,
    picking: Boolean,
    onTogglePicker: () -> Unit,
    onPicked: () -> Unit,
) {
    val c = Ios.colors
    val scope = rememberCoroutineScope()
    val drive = remember(acct.id) { DriveRegistry.create(acct) }
    var crumbs by remember { mutableStateOf(listOf(Crumb(drive.rootId, "根目录"))) }
    var items by remember { mutableStateOf<List<CloudEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(BrowseSelection(accountId = acct.id)) }
    val currentId = crumbs.last().id

    fun load(id: String) {
        val demo = CloudDemo.items
        if (demo != null) {
            items = demo
            loading = false
            error = null
            return
        }
        loading = true; error = null
        scope.launch {
            try {
                items = DriveRegistry.override?.list(id) ?: drive.list(id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "加载失败"
                items = emptyList()
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(currentId) { load(currentId) }

    fun openFolder(item: CloudEntry) {
        if (!item.isDir) return
        crumbs = crumbs + Crumb(item.fileId, item.fileName)
    }

    fun goCrumb(index: Int) {
        crumbs = crumbs.take(index + 1)
    }

    LargeTitlePage(
        title = crumbs.last().name,
        onBack = if (crumbs.size > 1) ({ goCrumb(crumbs.lastIndex - 1) }) else null,
        backLabel = if (crumbs.size > 1) crumbs[crumbs.lastIndex - 1].name.take(6) else "返回",
        bottomInset = bottomInset() + if (selecting && selection.count > 0) 64.dp else 0.dp,
        trailing = {
            BarButton(if (selecting) "完成" else "选择") {
                if (selecting) {
                    ScanTargets.selection = selection
                    if (selection.count > 0) ScanTargets.fullDrive = false
                } else {
                    selection = ScanTargets.selection.takeIf { it.accountId == acct.id }
                        ?: BrowseSelection(accountId = acct.id)
                }
                selecting = !selecting
            }
        },
        header = {
            // 面包屑 + 工具
            Column {
                if (accounts.size > 1 || picking) {
                    DrivePicker(accounts, acct, picking, onTogglePicker, onPicked)
                }
                Row(
                    Modifier.padding(start = 32.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    crumbs.forEachIndexed { i, cr ->
                        if (i > 0) Text(" › ", style = IosType.footnote, color = c.tertiaryLabel)
                        Text(
                            cr.name, style = IosType.footnote,
                            color = if (i == crumbs.lastIndex) c.label else c.blue,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = if (i < crumbs.lastIndex) Modifier.pressable { goCrumb(i) } else Modifier,
                        )
                    }
                }
                GroupedSection {
                    IosRow(
                        "全盘扫描",
                        subtitle = "从根目录开始扫描全部文件夹（较慢）",
                        icon = Icons.Rounded.TravelExplore,
                        iconTint = c.purple,
                        chevron = true,
                    ) {
                        ScanTargets.fullDrive = true
                        ScanTargets.selection = BrowseSelection(accountId = acct.id)
                        selecting = false
                        selection = BrowseSelection(accountId = acct.id)
                    }
                    IosRow(
                        "刷新",
                        icon = Icons.Rounded.Refresh,
                        iconTint = c.gray,
                        showDivider = false,
                    ) { load(currentId) }
                }
                if (error != null) {
                    Text(error!!, style = IosType.footnote, color = c.red,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
                }
            }
        },
    ) {
        if (loading && items.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = c.blue, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                }
            }
        } else if (items.isEmpty() && !loading) {
            item { EmptyState(Icons.Rounded.FolderOpen, "空文件夹", "这里没有文件或子文件夹。") }
        } else {
            item {
                GroupedSection {
                    val list = items
                    list.forEachIndexed { idx, it ->
                        CloudRow(
                            item = it,
                            selecting = selecting,
                            selected = selection.folders.containsKey(it.fileId),
                            showDivider = idx < list.lastIndex,
                            onOpen = { openFolder(it) },
                            onToggle = {
                                if (it.isDir) selection = selection.toggle(it.fileId, pathOf(crumbs, it.fileName))
                            },
                        )
                    }
                }
            }
            if (selecting) {
                item {
                    Text(
                        "勾选要扫描的文件夹，点右上角「完成」后到「刮削」页开始。已选 ${selection.count} 个。",
                        style = IosType.footnote, color = c.secondaryLabel,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }

    // 底部已选操作条
    if (selecting && selection.count > 0) {
        // LargeTitlePage 不方便叠层，用额外提示在 header/footer 已足够；真正的“开始扫描”在刮削页。
    }
}

private fun pathOf(crumbs: List<Crumb>, name: String): String =
    (crumbs.drop(1).map { it.name } + name).joinToString("/")

@Composable
private fun CloudRow(
    item: CloudEntry,
    selecting: Boolean,
    selected: Boolean,
    showDivider: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    val c = Ios.colors
    val icon = if (item.isDir) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile
    val tint = if (item.isDir) c.blue else c.gray
    val sizeText = if (item.isDir) "文件夹" else formatSize(item.size)
    IosRow(
        title = item.fileName,
        subtitle = sizeText,
        icon = icon,
        iconTint = tint,
        chevron = item.isDir && !selecting,
        showDivider = showDivider,
        trailing = if (selecting && item.isDir) {
            {
                Box(
                    Modifier.size(22.dp).clip(CircleShape)
                        .background(if (selected) c.blue else c.fill),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        } else null,
        onClick = {
            when {
                selecting && item.isDir -> onToggle()
                item.isDir -> onOpen()
            }
        },
    )
}

private fun formatSize(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
    n < 1024L * 1024 * 1024 -> "%.1f MB".format(n / (1024.0 * 1024))
    else -> "%.2f GB".format(n / (1024.0 * 1024 * 1024))
}
