package com.tamu.pan123.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.tamu.pan123.data.drive.AccountStore
import com.tamu.pan123.data.drive.ProviderKind
import com.tamu.pan123.data.p115.Drive115
import com.tamu.pan123.data.p115.P115Creds
import com.tamu.pan123.data.p115.QrSession
import com.tamu.pan123.ui.components.FilledButton
import com.tamu.pan123.ui.components.pressable
import com.tamu.pan123.ui.theme.Ios
import com.tamu.pan123.ui.theme.IosType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 115 扫码登录。设备身份固定为支付宝小程序（alipaymini），不挤掉电脑网页会话。
 * 二维码图片走 115 官方接口；轮询状态，确认后保存 cookies。
 */
@Composable
fun P115LoginScreen(nav: NavHostController, targetId: String?, preview: Boolean = false) {
    val c = Ios.colors
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("正在获取二维码…") }
    var session by remember { mutableStateOf<QrSession?>(null) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    // 预览 / 截图：不发网络请求
    val previewSession = remember {
        if (preview) QrSession("preview-uid", 0, "x", "https://115.com") else null
    }

    fun save(creds: P115Creds) {
        val label = "115 · " + (creds.userName.ifBlank { creds.uid.takeLast(4).ifBlank { "账号" } })
        val existing = targetId?.let { AccountStore.get(it) }
        val id = if (existing != null) {
            AccountStore.saveSecret(existing.id, Drive115.encodeCreds(creds))
            if (existing.label == ProviderKind.P115.label || existing.label.startsWith("115 · ")) {
                AccountStore.update(existing.copy(label = label))
            }
            existing.id
        } else {
            AccountStore.add(ProviderKind.P115, label, secret = Drive115.encodeCreds(creds)).id
        }
        AccountStore.setActive(id)
    }

    fun refresh() {
        if (preview) {
            session = previewSession
            status = "请用 115 App 扫一扫（支付宝小程序身份，不挤掉网页登录）"
            return
        }
        scope.launch {
            busy = true; err = null; done = false
            status = "正在获取二维码…"
            runCatching {
                // 用临时 store，登录成功后再写入目标账号
                val tmp = object : com.tamu.pan123.data.p115.P115TokenStore {
                    var held: P115Creds? = null
                    override fun load(): P115Creds? = held
                    override fun save(x: P115Creds) { held = x }
                }
                val d = Drive115("login-tmp", tmp)
                val qr = d.qrStart()
                session = qr
                status = "请用 115 App 扫一扫（支付宝小程序身份，不挤掉网页登录）"
                busy = false
                var stop = false
                while (!done && !stop) {
                    delay(1500)
                    val st = runCatching { d.qrStatus(qr) }.getOrElse {
                        err = it.message; status = "状态查询失败"; stop = true; return@getOrElse null
                    } ?: continue
                    when {
                        st.waiting -> status = "等待扫码…"
                        st.scanned -> status = "已扫码，请在手机上确认登录"
                        st.allowed -> {
                            status = "正在获取登录凭据…"
                            val creds = d.qrLogin(qr)
                            save(creds)
                            done = true
                            status = "登录成功"
                            delay(400)
                            nav.popBackStack()
                            return@launch
                        }
                        st.expired -> { status = "二维码已过期"; err = "请点击刷新"; stop = true }
                        st.canceled -> { status = "已取消"; stop = true }
                    }
                }
            }.onFailure {
                err = it.message
                status = "获取二维码失败"
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize().background(c.groupedBg)) {
        Column(Modifier.fillMaxWidth().background(c.barBg)) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Box(Modifier.fillMaxWidth().height(44.dp)) {
                Text(
                    "取消", style = IosType.body, color = c.blue,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 14.dp)
                        .pressable { nav.popBackStack() }.padding(8.dp),
                )
                Text("115 扫码登录", style = IosType.headline, color = c.label, modifier = Modifier.align(Alignment.Center))
                Text(
                    "刷新", style = IosType.body, color = c.blue,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 14.dp)
                        .pressable { if (!busy) refresh() }.padding(8.dp),
                )
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
        }
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(status, style = IosType.body, color = c.label, textAlign = TextAlign.Center)
            err?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = IosType.footnote, color = c.red, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier.size(240.dp).clip(RoundedCornerShape(16.dp)).background(c.cell),
                contentAlignment = Alignment.Center,
            ) {
                val s = session
                when {
                    s != null && !preview -> AsyncImage(
                        model = Drive115.qrImageUrl(s.uid),
                        contentDescription = "115 登录二维码",
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        contentScale = ContentScale.Fit,
                    )
                    preview -> Icon(Icons.Rounded.CropFree, null, tint = c.secondaryLabel, modifier = Modifier.size(120.dp))
                    busy -> CircularProgressIndicator(color = c.blue, strokeWidth = 2.dp, modifier = Modifier.size(36.dp))
                    else -> Icon(Icons.Rounded.CropFree, null, tint = c.tertiaryLabel, modifier = Modifier.size(80.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "打开 115 网盘 App → 扫一扫。本应用以「支付宝小程序」身份登录，不会挤掉你电脑上的网页登录。Cookies 加密保存在本机。",
                style = IosType.caption, color = c.secondaryLabel, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(com.tamu.pan123.data.drive.AUTHOR_NOTE_115, style = IosType.caption, color = c.orange, textAlign = TextAlign.Center)
            if (err != null || (session == null && !busy)) {
                Spacer(Modifier.height(20.dp))
                FilledButton("重新获取二维码") { refresh() }
            }
        }
    }
}
