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

private val LOGIN_URLS = listOf(
    "https://www.123pan.com/login",
    "https://login.123pan.com/",
    "https://www.123pan.com/",
)

/**
 * 网页登录：加载 123 登录页，登录成功后从 localStorage / cookie 抓 authorToken。
 * 成功后把 token 写入 SecureStore 并 pop。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginScreen(nav: NavHostController) {
    val c = Ios.colors
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("打开登录页…") }
    var captured by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val scope = rememberCoroutineScope()

    var checking by remember { mutableStateOf(false) }
    val rejected = remember { mutableSetOf<String>() }

    /** 先验证（列目录）再保存：验证失败的 token 不保存，也不会覆盖已有的登录。 */
    fun onToken(token: String, source: String) {
        if (captured || checking || token.isBlank() || token.length < 20 || token in rejected) return
        checking = true
        status = "已获取 token（$source），正在验证…"
        scope.launch {
            val ok = try { Pan123Client.ping(token) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }
            checking = false
            if (ok) {
                captured = true
                com.tamu.pan123.data.drive.AccountStore.save123Login(token)
                status = "登录成功"
                delay(400)
                nav.popBackStack()
            } else {
                rejected += token
                status = "读取到的 token 验证失败（未保存，原来的登录不受影响）。请确认已登录成功，或点「刷新」重试"
            }
        }
    }

    fun extract(view: WebView?) {
        if (view == null || !isOfficialUrl(view.url, PAN123_HOSTS)) return
        view.evaluateJavascript(EXTRACT_JS) { r ->
            val t = r?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "null" } ?: return@evaluateJavascript
            onToken(t.replace("\\\"", "\""), "localStorage")
        }
    }

    Column(Modifier.fillMaxSize().background(c.groupedBg)) {
        // 简易顶栏
        Column(Modifier.fillMaxWidth().background(c.barBg)) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Box(Modifier.fillMaxWidth().height(44.dp)) {
                Text(
                    "取消", style = IosType.body, color = c.blue,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 14.dp)
                        .pressable { nav.popBackStack() }.padding(8.dp),
                )
                Text("网页登录", style = IosType.headline, color = c.label, modifier = Modifier.align(Alignment.Center))
                Text(
                    "刷新", style = IosType.body, color = c.blue,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 14.dp)
                        .pressable { webViewRef?.reload() }.padding(8.dp),
                )
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
        }
        Text(status, style = IosType.footnote, color = c.secondaryLabel,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewRef = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        // 不再注入 JS 桥（任何页面都能调用它）；改为只在 123 官方页面上执行脚本、读取返回值
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                loading = true
                                status = "加载中…"
                            }
                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                status = "请在页面中登录；成功后会自动获取 token"
                                if (!isOfficialUrl(url, PAN123_HOSTS)) {
                                    status = "当前页面不是 123 官方网页，不会读取登录信息"
                                    return
                                }
                                extract(view)
                                // 再轮询几次，登录跳转后 localStorage 才写入
                                scope.launch {
                                    repeat(12) {
                                        delay(1500)
                                        if (captured) return@launch
                                        extract(view)
                                        if (isOfficialUrl(view?.url, PAN123_HOSTS)) scrapeCookies()?.let { onToken(it, "cookie") }
                                    }
                                }
                            }
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                // 只允许在 123 官方域名内跳转（主框架）；其它网址一律拦下
                                if (request?.isForMainFrame == false) return false
                                val u = request?.url?.toString()
                                if (isOfficialUrl(u, PAN123_HOSTS)) return false
                                status = "已阻止打开非 123 官方网页：${request?.url?.host ?: u}"
                                return true
                            }
                        }
                        loadUrl(LOGIN_URLS.first())
                    }
                },
                update = { /* keep */ },
            )
            if (loading) {
                CircularProgressIndicator(
                    color = c.blue,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp).size(28.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

/** 从 cookie 字符串里找 authorToken / token。 */
private fun scrapeCookies(): String? {
    val cm = CookieManager.getInstance()
    val hosts = listOf("https://www.123pan.com", "https://123pan.com", "https://login.123pan.com")
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
