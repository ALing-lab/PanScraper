package com.tamu.pan123.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.tamu.pan123.data.SecureStore
import com.tamu.pan123.data.drive.AccountStore
import com.tamu.pan123.data.drive.DriveAccount
import com.tamu.pan123.data.drive.ProviderKind
import com.tamu.pan123.ui.components.*
import com.tamu.pan123.ui.theme.Ios
import com.tamu.pan123.ui.theme.IosType
import kotlinx.coroutines.launch

internal fun ProviderKind.icon(): ImageVector = when (this) {
    ProviderKind.P123 -> Icons.Rounded.Cloud
    ProviderKind.P115 -> Icons.Rounded.CloudQueue
    ProviderKind.GUANGYA -> Icons.Rounded.WbSunny
    ProviderKind.WEBDAV -> Icons.Rounded.Dns
}

@Composable
internal fun ProviderKind.tint(): Color {
    val c = Ios.colors
    return when (this) {
        ProviderKind.P123 -> c.blue
        ProviderKind.P115 -> c.indigo
        ProviderKind.GUANGYA -> c.orange
        ProviderKind.WEBDAV -> c.teal
    }
}

private fun statusOf(a: DriveAccount): String = when {
    !a.provider.implemented && AccountStore.isLoggedIn(a) -> "已保存 · 暂不支持"
    !a.provider.implemented -> "暂不支持"
    AccountStore.isLoggedIn(a) -> "已登录"
    a.id == AccountStore.MAIN_123 && SecureStore.session.value.expired -> "已过期"
    else -> "未登录"
}

/** 资源中心：多网盘账号 + TMDB 密钥。 */
@Composable
fun AccountCenterScreen(nav: NavHostController) {
    val c = Ios.colors
    val accounts by AccountStore.accounts.collectAsState()
    val activeId by AccountStore.activeId.collectAsState()
    val session by SecureStore.session.collectAsState()
    val tmdb by SecureStore.tmdbKey.collectAsState()
    // 从登录页返回后清掉未完成的登录目标，避免影响下次主账号登录
    LaunchedEffect(Unit) { AccountStore.pending123Target = null }
    LargeTitlePage("资源中心", onBack = { nav.popBackStack() }, backLabel = "设置") {
        section(
            "drives",
            header = "网盘账号",
            footer = "「云盘」页顶部可以切换正在浏览的网盘。凭据只保存在本机加密存储。",
        ) {
            accounts.forEach { a ->
                key(a.id, session) {
                    IosRow(
                        a.label,
                        subtitle = a.provider.label + (if (a.id == activeId) " · 正在浏览" else "") + (a.provider.authorNote?.let { "\n$it" } ?: ""),
                        icon = a.provider.icon(),
                        iconTint = a.provider.tint(),
                        value = statusOf(a),
                        chevron = true,
                    ) { nav.navigate("settings/account/drive/${a.id}") }
                }
            }
            IosRow(
                "添加网盘账号",
                icon = Icons.Rounded.Add,
                iconTint = c.green,
                titleColor = c.blue,
                showDivider = false,
            ) { nav.navigate("settings/account/add") }
        }
        section("tmdb", header = "TMDB", footer = "密钥只保存在本机，使用系统加密存储，不会写进日志。") {
            IosRow(
                "TMDB API 密钥",
                icon = Icons.Rounded.Movie,
                iconTint = c.green,
                value = if (tmdb.isBlank()) "未设置" else "已设置 · ${tmdb.take(4)}…",
                chevron = true,
                showDivider = false,
            ) { nav.navigate("settings/account/tmdb-key") }
        }
    }
}

/** 选择网盘类型。 */
@Composable
fun AddAccountScreen(nav: NavHostController) {
    val c = Ios.colors
    val session by SecureStore.session.collectAsState()
    LargeTitlePage("添加账号", onBack = { nav.popBackStack() }, backLabel = "资源中心") {
        section(
            "kinds",
            header = "选择网盘",
            footer = "123、115、光鸭云盘与 WebDAV 均已可使用。",
        ) {
            ProviderKind.entries.forEachIndexed { i, k ->
                IosRow(
                    k.label,
                    subtitle = k.loginHint + (if (!k.implemented) " · 暂不支持" else "") + (k.authorNote?.let { "\n$it" } ?: ""),
                    icon = k.icon(),
                    iconTint = k.tint(),
                    chevron = true,
                    showDivider = i < ProviderKind.entries.lastIndex,
                ) {
                    when (k) {
                        ProviderKind.P123 -> {
                            if (session.token.isBlank()) {
                                // 主账号还没登录：直接登录主账号
                                AccountStore.pending123Target = null
                            } else {
                                val acct = AccountStore.add(ProviderKind.P123, "123 · 新账号")
                                AccountStore.pending123Target = acct.id
                            }
                            nav.navigate("settings/account/web-login")
                        }
                        ProviderKind.WEBDAV -> nav.navigate("settings/account/add/webdav")
                        ProviderKind.GUANGYA -> nav.navigate("settings/account/guangya-login?target=")
                        ProviderKind.P115 -> nav.navigate("settings/account/p115-login?target=")
                        else -> nav.navigate("settings/account/add/info/${k.key}")
                    }
                }
            }
        }
    }
}

/** 网盘说明页（兜底；正常流程从「添加账号」直接进入登录）。不发任何网络请求。 */
@Composable
fun ProviderInfoScreen(nav: NavHostController, kindKey: String) {
    val c = Ios.colors
    val k = ProviderKind.of(kindKey)
    val text = when (k) {
        ProviderKind.P115 ->
            "已支持：应用内扫码登录（支付宝小程序身份，不挤掉网页会话），浏览、改名、建文件夹、移动、上传海报/NFO。\n\n" +
                "请从「添加账号」进入扫码登录。\n\n" + com.tamu.pan123.data.drive.AUTHOR_NOTE_115
        ProviderKind.GUANGYA ->
            "已支持：应用内打开光鸭云盘网页登录（手机号 + 短信验证码），自动读取并续期令牌；浏览、改名、建文件夹、移动（异步任务会等待完成）、上传海报/NFO。\n\n" +
                "请从「添加账号」进入网页登录。"
        else -> ""
    }
    LargeTitlePage(k.label, onBack = { nav.popBackStack() }, backLabel = "添加账号") {
        item {
            EmptyState(k.icon(), k.label, text)
        }
        item {
            Text(
                "此页面不会连接 ${k.label}，也不会保存任何凭据。",
                style = IosType.footnote, color = c.secondaryLabel,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
        }
    }
}

/** WebDAV：地址 + 用户名 + 密码（可信任自签名证书）。密码进加密存储。 */
@Composable
fun WebDavFormScreen(nav: NavHostController) {
    val c = Ios.colors
    var url by remember { mutableStateOf("https://") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var insecure by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf<String?>(null) }
    var askPin by remember { mutableStateOf<com.tamu.pan123.data.webdav.UntrustedCertException?>(null) }
    var testMsg by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.blue, unfocusedBorderColor = c.separator,
        focusedTextColor = c.label, unfocusedTextColor = c.label,
        cursorColor = c.blue, focusedContainerColor = c.cell, unfocusedContainerColor = c.cell,
        focusedLabelColor = c.secondaryLabel, unfocusedLabelColor = c.secondaryLabel,
    )
    val valid = (url.startsWith("http://") || url.startsWith("https://")) && url.length > 10
    var helpOpen by remember { mutableStateOf(false) }
    val addrInfo = com.tamu.pan123.data.webdav.WebDavAddress.describe(url)
    LargeTitlePage(
        "WebDAV",
        onBack = { nav.popBackStack() },
        backLabel = "添加账号",
        trailing = {
            BarButton("保存", bold = true, enabled = valid) {
                val host = runCatching { java.net.URI(url.trim()).host }.getOrNull().orEmpty()
                val acct = AccountStore.add(
                    ProviderKind.WEBDAV,
                    label.ifBlank { "WebDAV · $host" },
                    extra = buildMap {
                        put("url", url.trim().trimEnd('/')); put("user", user.trim()); put("insecure", if (insecure) "1" else "0")
                        if (insecure) pin?.let { put("pin", it) }
                    },
                    secret = org.json.JSONObject().put("password", pass).toString(),
                )
                nav.popBackStack("settings/account", inclusive = false)
                nav.navigate("settings/account/drive/${acct.id}")
            }
        },
    ) {
        item {
            GroupedSection(
                footer = "地址要写完整：协议 + 主机 + 端口（可省略默认端口）+ 路径，例如 http://192.168.1.5:5005/dav 。端口直接写在地址里冒号后面，没有单独的端口栏。用户名和密码是 WebDAV 服务自己的账号，只保存在本机加密存储。",
            ) {
                OutlinedTextField(url, { url = it }, label = { Text("服务器地址") }, singleLine = true,
                    placeholder = { Text("http://192.168.1.5:5005/dav") },
                    isError = addrInfo.error,
                    supportingText = { Text(addrInfo.text, color = if (addrInfo.error) c.red else c.secondaryLabel) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(12.dp, 12.dp, 12.dp, 4.dp), colors = fieldColors)
                OutlinedTextField(user, { user = it }, label = { Text("用户名") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(12.dp, 4.dp), colors = fieldColors)
                OutlinedTextField(pass, { pass = it }, label = { Text("密码") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(12.dp, 4.dp), colors = fieldColors)
                OutlinedTextField(label, { label = it }, label = { Text("显示名称（可选）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(12.dp, 4.dp, 12.dp, 12.dp), colors = fieldColors)
            }
        }
        item(key = "help") {
            GroupedSection {
                val rot by androidx.compose.animation.core.animateFloatAsState(if (helpOpen) 90f else 0f, label = "help")
                IosRow("怎么填？", icon = Icons.Rounded.HelpOutline, iconTint = c.blue, showDivider = helpOpen,
                    trailing = { Icon(Icons.Rounded.ChevronRight, null, tint = c.tertiaryLabel, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rot }) },
                ) { helpOpen = !helpOpen }
                androidx.compose.animation.AnimatedVisibility(helpOpen) {
                    Column(Modifier.padding(16.dp, 8.dp, 16.dp, 14.dp)) {
                        Text(com.tamu.pan123.data.webdav.WebDavAddress.HELP, style = IosType.footnote, color = c.secondaryLabel)
                        Spacer(Modifier.height(10.dp))
                        Text("常见写法（点一下填入，再改成你的 IP / 域名）", style = IosType.footnote, color = c.label)
                        com.tamu.pan123.data.webdav.WebDavAddress.EXAMPLES.forEach { (name, ex) ->
                            Row(Modifier.fillMaxWidth().pressable { url = ex }.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                                Text(name, style = IosType.footnote, color = c.secondaryLabel, modifier = Modifier.width(96.dp))
                                Text(ex, style = IosType.footnote, color = c.blue, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
        item {
            GroupedSection(footer = "仅在 NAS 使用自签名 HTTPS 证书时打开；只对这个账号生效。打开后点「测试连接」，核对并确认证书指纹，之后只信任这一张证书。" +
                (if (insecure && pin != null) "\n已信任证书：SHA-256 ${pin!!.take(23)}…" else "")) {
                IosRow(
                    "信任自签名证书",
                    icon = Icons.Rounded.GppMaybe,
                    iconTint = c.orange,
                    showDivider = false,
                    trailing = { IosSwitch(insecure, { insecure = it; if (!it) pin = null }) },
                )
            }
        }
        item {
            GroupedSection(footer = testMsg) {
                IosRow(
                    if (testing) "正在连接…" else "测试连接",
                    icon = Icons.Rounded.NetworkCheck,
                    iconTint = c.blue,
                    titleColor = c.blue,
                    showDivider = false,
                ) {
                    if (!valid || testing) return@IosRow
                    testing = true; testMsg = null
                    scope.launch {
                        val r = com.tamu.pan123.data.webdav.WebDavDrive("test", url.trim(), user.trim(), pass, insecure, pin).check()
                        val untrusted = r.exceptionOrNull() as? com.tamu.pan123.data.webdav.UntrustedCertException
                        if (untrusted != null && insecure) { askPin = untrusted; testMsg = "需要确认服务器证书" }
                        else testMsg = r.fold({ "连接成功，根目录有 $it 项。" }, { "连接失败：${it.message}" + webDavPlainHint(url) })
                        testing = false
                    }
                }
            }
        }
    }
    askPin?.let { e ->
        CertTrustAlert(e, onTrust = { pin = e.fingerprint; askPin = null; testMsg = "已信任这张证书，请再点一次「测试连接」。" }, onDismiss = { askPin = null })
    }
}

/** http:// 地址提醒：明文传输。 */
internal fun webDavPlainHint(url: String) =
    if (url.trim().startsWith("http://", true)) "\n（http:// 是明文传输，只建议在家里的局域网使用）" else ""

/** 首次连接自签名证书：展示 SHA-256 指纹，请用户核对后信任。 */
@Composable
internal fun CertTrustAlert(e: com.tamu.pan123.data.webdav.UntrustedCertException, onTrust: () -> Unit, onDismiss: () -> Unit) {
    com.tamu.pan123.ui.components.IosAlert(
        title = "信任这张证书？",
        message = "服务器使用的证书不受系统信任。请在 NAS 的证书设置里核对 SHA-256 指纹是否一致：\n\n${e.fingerprint}\n\n" +
            (if (e.subject.isNotBlank()) "证书主体：${e.subject}\n\n" else "") +
            "信任后只接受这一张证书；以后证书变了会拒绝连接并再次询问。",
        confirm = "信任",
        onConfirm = onTrust,
        onDismiss = onDismiss,
    )
}

/** 单个账号详情：状态、重新登录、设为当前、删除。 */
@Composable
fun DriveAccountScreen(nav: NavHostController, id: String) {
    val c = Ios.colors
    val accounts by AccountStore.accounts.collectAsState()
    val activeId by AccountStore.activeId.collectAsState()
    val session by SecureStore.session.collectAsState()
    val acct = accounts.firstOrNull { it.id == id }
    var confirmDelete by remember { mutableStateOf(false) }
    if (acct == null) {
        LargeTitlePage("账号", onBack = { nav.popBackStack() }, backLabel = "资源中心") {
            item { EmptyState(Icons.Rounded.PersonOff, "账号已删除", "返回资源中心查看其它账号。") }
        }
        return
    }
    val isMain = acct.id == AccountStore.MAIN_123
    val loggedIn = remember(acct, session) { AccountStore.isLoggedIn(acct) }
    LargeTitlePage(acct.label, onBack = { nav.popBackStack() }, backLabel = "资源中心") {
        section("info", header = "状态") {
            IosRow("类型", icon = acct.provider.icon(), iconTint = acct.provider.tint(), value = acct.provider.label)
            IosRow("登录状态", value = statusOf(acct))
            if (isMain && session.passport.isNotBlank()) IosRow("账号", value = session.maskedPassport)
            if (isMain) IosRow("Token 过期", value = formatExpiry(session.expiresAt))
            acct.extra["url"]?.let { IosRow("地址", value = it.removePrefix("https://").removePrefix("http://").take(28)) }
            acct.extra["user"]?.takeIf { it.isNotBlank() }?.let { IosRow("用户名", value = it) }
            IosRow(
                "设为正在浏览",
                value = if (acct.id == activeId) "是" else null,
                titleColor = if (acct.id == activeId) null else c.blue,
                showDivider = false,
            ) { AccountStore.setActive(acct.id) }
        }
        if (acct.provider == ProviderKind.P123) {
            section("login", header = "123 云盘") {
                IosRow(
                    "网页登录",
                    subtitle = "打开 123 登录页，自动获取 token（推荐）",
                    icon = Icons.Rounded.Language,
                    iconTint = c.blue,
                    chevron = true,
                ) {
                    AccountStore.pending123Target = acct.id
                    nav.navigate("settings/account/web-login")
                }
                IosRow(
                    "账号密码登录",
                    subtitle = "调用网页登录接口；海外 IP 可能被风控",
                    icon = Icons.Rounded.Key,
                    iconTint = c.indigo,
                    chevron = true,
                    showDivider = false,
                ) {
                    AccountStore.pending123Target = acct.id
                    nav.navigate("settings/account/password-login")
                }
            }
        } else if (acct.provider == ProviderKind.WEBDAV) {
            item(key = "davtest") {
                var msg by remember { mutableStateOf<String?>(null) }
                var busy by remember { mutableStateOf(false) }
                var askPin by remember { mutableStateOf<com.tamu.pan123.data.webdav.UntrustedCertException?>(null) }
                val scope = rememberCoroutineScope()
                val pinned = acct.extra["pin"]
                val footer = msg ?: when {
                    acct.extra["insecure"] == "1" && pinned != null -> "已信任自签名证书：SHA-256 ${pinned.take(23)}…"
                    acct.extra["insecure"] == "1" -> "已打开「信任自签名证书」，但还没有确认证书指纹：请点「测试连接」核对后信任。"
                    else -> null
                }
                askPin?.let { e ->
                    CertTrustAlert(e, onTrust = {
                        AccountStore.update(acct.copy(extra = acct.extra + ("pin" to e.fingerprint)))
                        askPin = null; msg = "已信任这张证书，请再点一次「测试连接」。"
                    }, onDismiss = { askPin = null })
                }
                GroupedSection(header = "WebDAV", footer = footer) {
                    IosRow(
                        if (busy) "正在连接…" else "测试连接",
                        icon = Icons.Rounded.NetworkCheck,
                        iconTint = c.blue,
                        titleColor = c.blue,
                        showDivider = false,
                    ) {
                        if (busy) return@IosRow
                        busy = true; msg = null
                        scope.launch {
                            val r = com.tamu.pan123.data.webdav.WebDavDrive.from(acct).check()
                            val untrusted = r.exceptionOrNull() as? com.tamu.pan123.data.webdav.UntrustedCertException
                            if (untrusted != null && acct.extra["insecure"] == "1") { askPin = untrusted; msg = "需要确认服务器证书" }
                            else msg = r.fold({ "连接成功，根目录有 $it 项。" }, { "连接失败：${it.message}" + webDavPlainHint(acct.extra["url"].orEmpty()) })
                            busy = false
                        }
                    }
                }
            }
        } else if (acct.provider == ProviderKind.P115) {
            item(key = "p115test") {
                var msg by remember { mutableStateOf<String?>(null) }
                var busy by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                GroupedSection(
                    header = "115 网盘",
                    footer = msg ?: ("以支付宝小程序身份登录，不挤掉电脑网页会话。Cookies 加密保存在本机。\n" + com.tamu.pan123.data.drive.AUTHOR_NOTE_115),
                ) {
                    IosRow(
                        if (loggedIn) "重新扫码登录" else "扫码登录",
                        subtitle = "用 115 App 扫一扫",
                        icon = Icons.Rounded.QrCodeScanner,
                        iconTint = c.indigo,
                        chevron = true,
                    ) { nav.navigate("settings/account/p115-login?target=${acct.id}") }
                    IosRow(
                        if (busy) "正在连接…" else "测试连接",
                        icon = Icons.Rounded.NetworkCheck,
                        iconTint = c.blue,
                        titleColor = c.blue,
                        showDivider = false,
                    ) {
                        if (busy) return@IosRow
                        busy = true; msg = null
                        scope.launch {
                            val r = runCatching { com.tamu.pan123.data.p115.Drive115.from(acct).list("0") }
                            msg = r.fold({ "连接成功，根目录有 ${it.size} 项。" }, { "连接失败：${it.message}" })
                            busy = false
                        }
                    }
                }
            }
        } else if (acct.provider == ProviderKind.GUANGYA) {
            item(key = "gytest") {
                var msg by remember { mutableStateOf<String?>(null) }
                var busy by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                GroupedSection(
                    header = "光鸭云盘",
                    footer = msg ?: "令牌加密保存在本机，过期前会用 refresh_token 自动续期；续期失败时请重新登录。",
                ) {
                    IosRow(
                        if (loggedIn) "重新登录" else "网页登录",
                        subtitle = "打开光鸭网页版，短信验证码登录后自动保存",
                        icon = Icons.Rounded.Language,
                        iconTint = c.orange,
                        chevron = true,
                    ) { nav.navigate("settings/account/guangya-login?target=${acct.id}") }
                    IosRow(
                        if (busy) "正在连接…" else "测试连接",
                        icon = Icons.Rounded.NetworkCheck,
                        iconTint = c.blue,
                        titleColor = c.blue,
                        showDivider = false,
                    ) {
                        if (busy) return@IosRow
                        busy = true; msg = null
                        scope.launch {
                            val r = runCatching { com.tamu.pan123.data.guangya.GuangyaDrive.from(acct).list("") }
                            msg = r.fold({ "连接成功，根目录有 ${it.size} 项。" }, { "连接失败：${it.message}" })
                            busy = false
                        }
                    }
                }
            }
        } else {
            section("pending", footer = "暂不支持 ${acct.provider.label} 的浏览和执行。") {
                IosRow("登录方式", value = acct.provider.loginHint, showDivider = false)
            }
        }
        section("danger") {
            IosRow(
                if (isMain) "退出登录" else "删除账号",
                titleColor = c.red,
                showDivider = false,
            ) { confirmDelete = true }
        }
    }
    if (confirmDelete) {
        IosAlert(
            title = if (isMain) "退出 123 登录？" else "删除这个账号？",
            message = if (isMain) "会清除本机保存的 123 token。云盘里的文件不受影响。"
            else "会删除本机保存的「${acct.label}」凭据。云盘里的文件不受影响。",
            confirm = if (isMain) "退出" else "删除",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                AccountStore.remove(acct.id)
                if (!isMain) nav.popBackStack()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
