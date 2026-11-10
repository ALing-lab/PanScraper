package com.tamu.pan123.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import com.tamu.pan123.data.drive.AccountStore
import com.tamu.pan123.data.drive.ProviderKind
import com.tamu.pan123.data.guangya.GuangyaDrive
import com.tamu.pan123.data.guangya.GyCreds
import com.tamu.pan123.ui.components.pressable
import com.tamu.pan123.ui.theme.Ios
import com.tamu.pan123.ui.theme.IosType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 光鸭网页登录：从 localStorage 读取 @xbase/sdk 保存的凭据。 */
object GuangyaLogin {
    /** 只允许在光鸭官方域名（及子域名）内跳转，也只在这些页面上读取凭据。 */
    val HOSTS = listOf("guangyapan.com")

    /** 先用内存里的凭据列一次根目录，确认可用再保存；返回验证（可能已续期）后的凭据，失败返回 null。 */
    suspend fun validate(creds: GyCreds): GyCreds? {
        val mem = object : com.tamu.pan123.data.guangya.GyTokenStore {
            @Volatile var c: GyCreds? = creds
            override fun load() = c
            override fun save(c: GyCreds) { this.c = c }
        }
        val ok = runCatching { GuangyaDrive("validate", mem).list(GuangyaDrive.ROOT_ID_FOR_VALIDATE); true }
            .getOrElse { e -> if (e is kotlinx.coroutines.CancellationException) throw e; false }
        return if (ok) mem.c else null
    }

    /** 在页面里执行；返回 JSON 字符串 {c: 凭据原文, d: 网页设备 ID}，未登录返回空串。 */
    val EXTRACT_JS = """
        (function(){try{
          var out=null, did=localStorage.getItem('${GuangyaDrive.DEVICE_KEY}')||'';
          for(var i=0;i<localStorage.length;i++){
            var k=localStorage.key(i);
            if(k && k.indexOf('${GuangyaDrive.CRED_KEY_PREFIX}')===0){
              var v=localStorage.getItem(k);
              if(v && v.indexOf('access_token')>=0){ out=v; break; }
            }
          }
          return out ? JSON.stringify({c:out, d:did}) : '';
        }catch(e){ return ''; }})()
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 解析 evaluateJavascript 的回调值（它本身是 JSON 编码过的字符串）。 */
    fun parseJsResult(result: String?): GyCreds? {
        if (result.isNullOrBlank() || result == "null" || result == "\"\"") return null
        val inner = runCatching { json.parseToJsonElement(result).jsonPrimitive.contentOrNull }.getOrNull() ?: return null
        if (inner.isBlank()) return null
        val o = runCatching { json.parseToJsonElement(inner).jsonObject }.getOrNull() ?: return null
        val raw = (o["c"] as? JsonPrimitive)?.contentOrNull
        val did = (o["d"] as? JsonPrimitive)?.contentOrNull
        return GuangyaDrive.parseWebCredentials(raw, did)
    }

    /** 保存到目标账号（为空则新建）。返回账号 ID。 */
    fun save(creds: GyCreds, targetId: String?): String {
        val secret = GuangyaDrive.encodeCreds(creds)
        val label = "光鸭 · " + (creds.sub.takeLast(4).ifBlank { "账号" })
        val existing = targetId?.let { AccountStore.get(it) }
        return if (existing != null) {
            AccountStore.saveSecret(existing.id, secret)
            if (existing.label == ProviderKind.GUANGYA.label || existing.label.startsWith("光鸭 · ")) {
                AccountStore.update(existing.copy(label = label))
            }
            existing.id
        } else {
            AccountStore.add(ProviderKind.GUANGYA, label, secret = secret).id
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GuangyaLoginScreen(nav: NavHostController, targetId: String?, showWeb: Boolean = true) {
    val c = Ios.colors
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("打开光鸭云盘网页…") }
    var captured by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val scope = rememberCoroutineScope()
    val preview = LocalInspectionMode.current || !showWeb

    var checking by remember { mutableStateOf(false) }
    val rejected = remember { mutableSetOf<String>() }

    /** 先验证再保存：验证失败的凭据不保存，也不会覆盖账号里原有的凭据。 */
    fun onCreds(creds: GyCreds) {
        if (captured || checking || creds.accessToken in rejected) return
        checking = true
        status = "已读取登录凭据，正在验证…"
        scope.launch {
            val good = GuangyaLogin.validate(creds)
            checking = false
            if (good != null) {
                captured = true
                val id = GuangyaLogin.save(good, targetId)
                AccountStore.setActive(id)
                status = "登录成功"
                delay(400)
                nav.popBackStack()
            } else {
                rejected += creds.accessToken
                status = "读取到的凭据验证失败（未保存，原来的登录不受影响）。请确认已登录成功，或点「刷新」重试"
            }
        }
    }

    fun onOfficialPage(wv: WebView) = isOfficialUrl(wv.url, GuangyaLogin.HOSTS)

    // 网页登录成功后 @xbase/sdk 才写 localStorage：页面打开期间每 1.5 秒读一次
    LaunchedEffect(webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        while (!captured) {
            delay(1500)
            if (!onOfficialPage(wv)) continue // 不在光鸭官方页面上时不读取
            wv.evaluateJavascript(GuangyaLogin.EXTRACT_JS) { r ->
                GuangyaLogin.parseJsResult(r)?.let { onCreds(it) }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(c.groupedBg)) {
        Column(Modifier.fillMaxWidth().background(c.barBg)) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Box(Modifier.fillMaxWidth().height(44.dp)) {
                Text(
                    "取消", style = IosType.body, color = c.blue,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 14.dp)
                        .pressable { nav.popBackStack() }.padding(8.dp),
                )
                Text("光鸭云盘登录", style = IosType.headline, color = c.label, modifier = Modifier.align(Alignment.Center))
                Text(
                    "刷新", style = IosType.body, color = c.blue,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 14.dp)
                        .pressable { webViewRef?.reload() }.padding(8.dp),
                )
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
        }
        Text(
            status, style = IosType.footnote, color = c.secondaryLabel,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Text(
            "用手机号 + 短信验证码（或扫码）登录光鸭网页版。登录成功后会自动读取网页保存的令牌并加密保存在本机，之后自动续期；不会保存你的手机号或验证码。",
            style = IosType.caption, color = c.secondaryLabel,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        Box(Modifier.fillMaxSize()) {
            if (!preview) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            // 桌面 UA：光鸭移动版会引导下载 App
                            settings.userAgentString =
                                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            CookieManager.getInstance().setAcceptCookie(true)
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    loading = true
                                }
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    loading = false
                                    if (!captured && !checking) status = if (isOfficialUrl(url, GuangyaLogin.HOSTS)) "请在页面中登录；成功后会自动保存"
                                        else "当前页面不是光鸭官方网页，不会读取登录信息"
                                }
                                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                    if (request?.isForMainFrame == false) return false
                                    val u = request?.url?.toString()
                                    if (isOfficialUrl(u, GuangyaLogin.HOSTS)) return false
                                    status = "已阻止打开非光鸭官方网页：${request?.url?.host ?: u}"
                                    return true
                                }
                            }
                            loadUrl(GuangyaDrive.WEB_URL)
                            webViewRef = this
                        }
                    },
                )
            }
            if (loading && !preview) {
                CircularProgressIndicator(
                    color = c.blue,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp).size(28.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}
