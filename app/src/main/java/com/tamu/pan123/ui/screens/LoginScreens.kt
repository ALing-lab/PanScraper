package com.tamu.pan123.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import com.tamu.pan123.data.SecureStore
import com.tamu.pan123.data.pan.Pan123Client
import com.tamu.pan123.ui.components.BarButton
import com.tamu.pan123.ui.components.FilledButton
import com.tamu.pan123.ui.components.GroupedSection
import com.tamu.pan123.ui.components.LargeTitlePage
import com.tamu.pan123.ui.components.pressable
import com.tamu.pan123.ui.theme.Ios
import com.tamu.pan123.ui.theme.IosType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 123 网页登录只允许在这些官方域名（及其子域名）内跳转，也只在这些页面上读取登录状态。 */
internal val PAN123_HOSTS = listOf("123pan.com", "123pan.cn")

/** https 且主机属于 [hosts]（点边界匹配）。 */
internal fun isOfficialUrl(url: String?, hosts: List<String>): Boolean {
    val u = url?.let { runCatching { java.net.URI(it) }.getOrNull() } ?: return false
    return u.scheme.equals("https", true) && com.tamu.pan123.data.net.HostMatch.matchesAny(u.host, hosts)
}

/** 官方网页入口。旧 /login 会跳转到过期站点或明文地址，不能再作为首选。 */
internal val LOGIN_URLS = listOf(
    "https://www.123pan.com/",
    "https://www.123pan.cn/",
    "https://yun.123pan.cn/",
)

/** 保留设备真实 Chromium 版本，桌面模式不再固定伪装成旧版手机。 */
internal fun loginUserAgent(native: String, desktop: Boolean): String {
    val clean = native.replace("; wv", "").replace(" Version/4.0", "")
    if (!desktop) return clean
    return clean.replace(Regex("\\([^)]*\\)"), "(X11; Linux x86_64)").replace(" Mobile", "")
}

internal class LoginAttempts(private val urls: List<String> = LOGIN_URLS) {
    var index: Int = 0; private set
    val current: String get() = urls[index]
    fun next(): String? = if (index + 1 < urls.size) urls[++index] else null
    fun reset(): String { index = 0; return current }
}

/** 官方页面打开后检查真实 DOM，避免 HTTP200 空壳页永久留白。 */
internal const val LOGIN_HEALTH_JS = """(function(){var b=document.body;if(!b)return false;return b.innerText.trim().length>0||!!b.querySelector('input,button,iframe,canvas,img,svg');})();"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginScreen(nav: NavHostController) {
    val c = Ios.colors
    val tablet = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    var desktop by remember { mutableStateOf(tablet) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("打开 123 官方网页…") }
    var captured by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var instance by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    var pageSerial by remember { mutableIntStateOf(0) }
    val attempts = remember { LoginAttempts() }
    val scope = rememberCoroutineScope()
    var pollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var checking by remember { mutableStateOf(false) }
    val rejected = remember { mutableSetOf<String>() }

    fun onToken(token: String, source: String) {
        if (captured || checking || token.isBlank() || token.length < 20 || token in rejected) return
        checking = true
        status = "已获取登录状态（$source），正在验证…"
        scope.launch {
            try {
                val ok = Pan123Client.ping(token)
                if (ok) {
                    captured = true
                    com.tamu.pan123.data.drive.AccountStore.save123Login(token)
                    status = "登录成功"
                    delay(400)
                    nav.popBackStack()
                } else {
                    rejected += token
                    status = "登录状态验证失败，未保存。请完成登录后刷新重试"
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { status = "验证登录状态失败，请刷新重试" }
            finally { checking = false }
        }
    }
    fun extract(view: WebView?) {
        if (view == null || !isOfficialUrl(view.url, PAN123_HOSTS)) return
        view.evaluateJavascript(EXTRACT_JS) { raw ->
            if (!isOfficialUrl(view.url, PAN123_HOSTS)) return@evaluateJavascript
            val token = runCatching { JSONObject("{\"value\":${raw ?: "null"}}").optString("value", "") }.getOrDefault("")
            if (token != "null") onToken(token, "网页")
        }
        scrapeCookies()?.let { onToken(it, "cookie") }
    }
    fun retry() {
        pollJob?.cancel(); rejected.clear(); failed = false; loading = true
        if (webViewRef == null) { attempts.reset(); instance++ } else webViewRef?.loadUrl(attempts.reset())
    }
    fun failure(view: WebView?, reason: String) {
        pollJob?.cancel(); failed = true; loading = false
        val next = attempts.next()
        if (next != null && view != null) {
            status = "$reason，正在切换官方线路…"
            view.post { if (webViewRef === view) view.loadUrl(next) }
        } else status = "$reason。可刷新、换线路或切换显示模式；若仍失败，请更新 Android System WebView 后重试"
    }

    // 主文档超时也会退出加载状态，不能永久显示空白 + 转圈。
    LaunchedEffect(pageSerial) {
        val serial = pageSerial
        delay(25_000)
        if (loading && !captured && serial == pageSerial) failure(webViewRef, "网页加载超时")
    }
    Column(Modifier.fillMaxSize().background(c.groupedBg)) {
        Column(Modifier.fillMaxWidth().background(c.barBg)) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Box(Modifier.fillMaxWidth().height(44.dp)) {
                Text("取消", style = IosType.body, color = c.link,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 14.dp).pressable { nav.popBackStack() }.padding(8.dp))
                Text("网页登录", style = IosType.headline, color = c.label, modifier = Modifier.align(Alignment.Center))
                Text("刷新", style = IosType.body, color = c.link,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 14.dp).pressable { retry() }.padding(8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(if (desktop) "切换手机版" else "切换电脑版", color = c.link, style = IosType.footnote,
                    modifier = Modifier.pressable { desktop = !desktop; attempts.reset(); instance++ })
                Text("换线路", color = c.link, style = IosType.footnote,
                    modifier = Modifier.pressable { rejected.clear(); webViewRef?.loadUrl(attempts.next() ?: attempts.reset()) })
            }
        }
        Text(status, style = IosType.footnote, color = if (failed) c.red else c.secondaryLabel,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        // 明确限制原生 View 高度，避免大屏转场中盖住 Compose 的状态/返回栏。
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            key(instance) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx -> WebView(ctx).apply {
                        webViewRef = this
                        layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.userAgentString = loginUserAgent(android.webkit.WebSettings.getDefaultUserAgent(ctx), desktop)
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                pollJob?.cancel(); pageSerial++; failed = false; loading = true
                                status = "正在加载 123 官方网页…"
                            }
                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (failed || captured || view == null || view.url != url) return
                                if (!isOfficialUrl(url, PAN123_HOSTS)) { failure(view, "已阻止非官方登录网页"); return }
                                loading = false; status = "请在网页中登录；成功后会自动保存"
                                val serial = pageSerial
                                pollJob?.cancel()
                                pollJob = scope.launch {
                                    repeat(120) { n ->
                                        if (captured || serial != pageSerial || webViewRef !== view) return@launch
                                        extract(view)
                                        if (n == 5) view.evaluateJavascript(LOGIN_HEALTH_JS) { healthy ->
                                            if (healthy == "false" && serial == pageSerial && !captured) failure(view, "登录网页未显示内容")
                                        }
                                        delay(1500)
                                    }
                                }
                            }
                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                                if (request?.isForMainFrame == true) failure(view, "网页加载失败（${error?.errorCode ?: "未知"}）")
                            }
                            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: android.webkit.WebResourceResponse?) {
                                if (request?.isForMainFrame == true) failure(view, "网页返回 HTTP ${response?.statusCode}")
                            }
                            override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                                handler?.cancel(); failure(view, "网页证书验证失败")
                            }
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                if (request?.isForMainFrame == false) return false
                                if (isOfficialUrl(request?.url?.toString(), PAN123_HOSTS)) return false
                                failed = true; loading = false
                                status = "已阻止不安全或非官方网页，请换线路重试"
                                return true
                            }
                            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                                pollJob?.cancel(); loading = false; failed = true
                                status = "网页显示进程已退出，请刷新重试"
                                if (webViewRef === view) webViewRef = null
                                view?.destroy(); return true
                            }
                        }
                        loadUrl(attempts.current)
                    } },
                    onRelease = { view ->
                        pollJob?.cancel(); view.stopLoading()
                        if (webViewRef === view) webViewRef = null
                        view.destroy()
                    },
                )
            }
            if (loading) CircularProgressIndicator(color = c.blue,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp).size(28.dp), strokeWidth = 2.dp)
        }
    }
}

/** 从 cookie 字符串里找 authorToken / token。 */
private fun scrapeCookies(): String? {
    val cm = CookieManager.getInstance()
    val hosts = listOf("https://www.123pan.com", "https://123pan.com", "https://yun.123pan.cn", "https://www.123pan.cn", "https://123pan.cn")
    for (h in hosts) {
        val raw = cm.getCookie(h) ?: continue
        for (part in raw.split(';')) {
            val kv = part.trim().split('=', limit = 2)
            if (kv.size == 2 && (
                kv[0].equals("authorToken", true) ||
                kv[0].equals("token", true) ||
                kv[0].equals("Authorization", true)
            )) {
                val v = kv[1].removePrefix("Bearer ").trim()
                if (v.length > 20) return v
            }
        }
    }
    return null
}

/**
 * 在页面里扫 localStorage / sessionStorage 找 JWT 形态的 token。
 * 常见键：authorToken、token、Authorization。
 */
private val EXTRACT_JS = """
(function(){
  function looksJwt(s){ return typeof s==='string' && s.split('.').length>=3 && s.length>40; }
  function dig(store){
    try {
      for (var i=0;i<store.length;i++){
        var k=store.key(i); var v=store.getItem(k);
        if (!v) continue;
        var b=v.replace(/^Bearer\s+/i,'');
        if (k && /authorToken|token|Authorization/i.test(k) && looksJwt(b)) return b;
        try {
          var o=JSON.parse(v);
          if (o && typeof o==='object'){
            var ks=['authorToken','token','Token','access_token','accessToken'];
            for (var j=0;j<ks.length;j++){ if (looksJwt(String(o[ks[j]]||''))) return String(o[ks[j]]); }
            if (o.data && looksJwt(String(o.data.token||o.data.Token||''))) return String(o.data.token||o.data.Token);
          }
        } catch(e){}
      }
    } catch(e){}
    return '';
  }
  var t=dig(localStorage); if (t) return t;
  t=dig(sessionStorage); if (t) return t;
  try {
    var m=document.cookie.match(/(?:^|;\s*)(?:authorToken|token)=([^;]+)/i);
    if (m && looksJwt(m[1])) return m[1];
  } catch(e){}
  return '';
})();
""".trimIndent()

@Composable
fun PasswordLoginScreen(nav: NavHostController) {
    val c = Ios.colors
    var passport by remember { mutableStateOf(SecureStore.session.value.passport) }
    var password by remember { mutableStateOf("") }
    var showPwd by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val kb = LocalSoftwareKeyboardController.current
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.blue, unfocusedBorderColor = c.separator,
        focusedTextColor = c.label, unfocusedTextColor = c.label,
        cursorColor = c.blue, focusedContainerColor = c.cell, unfocusedContainerColor = c.cell,
        focusedLabelColor = c.secondaryLabel, unfocusedLabelColor = c.secondaryLabel,
    )

    fun submit() {
        if (busy) return
        if (passport.isBlank() || password.isBlank()) { error = "请填写账号和密码"; return }
        busy = true; error = null; kb?.hide()
        scope.launch {
            try {
                Pan123Client.loginPassport(passport, password)
                password = ""
                nav.popBackStack()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "登录失败"
            } finally {
                busy = false
            }
        }
    }

    LargeTitlePage("账号密码登录", onBack = { nav.popBackStack() }, backLabel = "资源中心") {
        item {
            GroupedSection(footer = "手机号不要带 +86。若提示验证码或 code=7012（海外登录风控），请改用「网页登录」。密码不会被保存。") {
                OutlinedTextField(
                    passport, { passport = it }, label = { Text("手机号或邮箱") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    colors = fieldColors,
                )
                OutlinedTextField(
                    password, { password = it }, label = { Text("密码") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
                    visualTransformation = if (showPwd) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Icon(
                            if (showPwd) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null,
                            tint = c.secondaryLabel,
                            modifier = Modifier.pressable { showPwd = !showPwd }.padding(8.dp),
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    colors = fieldColors,
                )
            }
        }
        item {
            FilledButton(
                if (busy) "登录中…" else "登录",
                Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                enabled = !busy,
            ) { submit() }
        }
        if (error != null) item {
            Text(error!!, style = IosType.footnote, color = c.red,
                modifier = Modifier.padding(horizontal = 32.dp))
        }
    }
}

@Composable
fun TmdbKeyScreen(nav: NavHostController) {
    val c = Ios.colors
    var key by remember { mutableStateOf(SecureStore.tmdbKey.value) }
    var saved by remember { mutableStateOf(false) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.blue, unfocusedBorderColor = c.separator,
        focusedTextColor = c.label, unfocusedTextColor = c.label,
        cursorColor = c.blue, focusedContainerColor = c.cell, unfocusedContainerColor = c.cell,
        focusedLabelColor = c.secondaryLabel, unfocusedLabelColor = c.secondaryLabel,
    )
    LargeTitlePage(
        "TMDB API 密钥",
        onBack = { nav.popBackStack() },
        backLabel = "资源中心",
        trailing = {
            BarButton("保存", bold = true, enabled = key.isNotBlank()) {
                SecureStore.saveTmdbKey(key)
                saved = true
                nav.popBackStack()
            }
        },
    ) {
        item {
            GroupedSection(footer = "在 themoviedb.org 账号设置里申请 API Key（v3 auth）。密钥只保存在本机加密存储，不会上传、不会写进日志。") {
                OutlinedTextField(
                    key, { key = it; saved = false }, label = { Text("API Key") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(12.dp),
                    colors = fieldColors,
                )
            }
        }
        if (SecureStore.tmdbKey.value.isNotBlank()) item {
            GroupedSection {
                Text(
                    "清除密钥", style = IosType.body, color = c.red,
                    modifier = Modifier.fillMaxWidth().pressable {
                        SecureStore.clearTmdbKey(); key = ""; nav.popBackStack()
                    }.padding(16.dp),
                )
            }
        }
    }
}
