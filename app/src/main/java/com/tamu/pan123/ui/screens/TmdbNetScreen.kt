package com.tamu.pan123.ui.screens

import com.tamu.pan123.ui.theme.IosType
import com.tamu.pan123.ui.components.pressable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.tamu.pan123.data.Settings
import com.tamu.pan123.data.tmdb.TmdbClient
import com.tamu.pan123.data.tmdb.TmdbNet
import com.tamu.pan123.data.tmdb.TmdbNetConfig
import com.tamu.pan123.ui.components.*
import com.tamu.pan123.ui.theme.Ios
import kotlinx.coroutines.launch

private enum class ApiPreset(val label: String) { ALT("api.tmdb.org"), OFFICIAL("api.themoviedb.org"), CUSTOM("自定义 / 反代") }
private enum class ProxyKind(val key: String, val label: String) { NONE("none", "不使用"), HTTP("http", "HTTP"), SOCKS("socks", "SOCKS5") }

/** TMDB 网络：API / 图片地址、DoH、仅 TMDB 使用的代理、测试连接。 */
@Composable
fun TmdbNetScreen(nav: NavHostController) {
    val c = Ios.colors
    val cur = remember { TmdbNet.config.value }
    var preset by remember {
        mutableStateOf(when (cur.apiBase) {
            TmdbNetConfig.DEFAULT_API -> ApiPreset.OFFICIAL
            TmdbNetConfig.ALT_API -> ApiPreset.ALT
            else -> ApiPreset.CUSTOM
        })
    }
    var custom by remember { mutableStateOf(if (preset == ApiPreset.CUSTOM) cur.apiBase else "https://") }
    var image by remember { mutableStateOf(cur.imageBase) }
    var imageFb by remember { mutableStateOf(cur.imageFallback) }
    var doh by remember { mutableStateOf(cur.doh) }
    var mirrors by remember { mutableStateOf(cur.publicMirrors) }
    var proxy by remember { mutableStateOf(ProxyKind.entries.firstOrNull { it.key == cur.proxyType } ?: ProxyKind.NONE) }
    var host by remember { mutableStateOf(cur.proxyHost) }
    var port by remember { mutableStateOf(if (cur.proxyPort > 0) cur.proxyPort.toString() else "") }
    var testing by remember { mutableStateOf(false) }
    var testMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.blue, unfocusedBorderColor = c.separator,
        focusedTextColor = c.label, unfocusedTextColor = c.label,
        cursorColor = c.blue, focusedContainerColor = c.cell, unfocusedContainerColor = c.cell,
        focusedLabelColor = c.secondaryLabel, unfocusedLabelColor = c.secondaryLabel,
    )

    fun build() = TmdbNetConfig(
        apiBase = when (preset) {
            ApiPreset.OFFICIAL -> TmdbNetConfig.DEFAULT_API
            ApiPreset.ALT -> TmdbNetConfig.ALT_API
            ApiPreset.CUSTOM -> custom
        },
        imageBase = image,
        imageFallback = imageFb,
        publicMirrors = mirrors,
        doh = doh,
        proxyType = proxy.key,
        proxyHost = host,
        proxyPort = port.toIntOrNull() ?: 0,
    ).normalized()

    fun save() { Settings.setTmdbNet(build()); TmdbClient.resetFailures() }

    LargeTitlePage("TMDB 网络", onBack = { save(); nav.popBackStack() }, backLabel = "设置", trailing = {
        BarButton("保存", bold = true) { save(); testMsg = "已保存：${TmdbNet.describe()}" }
    }) {
        item {
            GroupedSection(header = "API 地址", footer = "默认 api.tmdb.org（官方备用域名），连不上时自动改试 api.themoviedb.org。仍不行就开 DoH / 代理，或填你自己的 TMDB 反代地址（如 https://tmdb.example.com/3）。") {
                IosSegmented(ApiPreset.entries.toList(), preset, { it.label }, { preset = it },
                    Modifier.fillMaxWidth().padding(12.dp))
                if (preset == ApiPreset.CUSTOM) {
                    OutlinedTextField(custom, { custom = it }, label = { Text("自定义 API 地址") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().padding(12.dp, 0.dp, 12.dp, 12.dp), colors = fieldColors)
                }
            }
        }
        item {
            GroupedSection(header = "图片地址", footer = "默认 https://image.tmdb.org（预览缩略图、重新匹配的搜索结果、上传的海报都走这里，同样使用 DoH / 代理）。主地址连不上、被劫持或报错时，依次改用 image.tmdb.org、TMDB 官网图片 CDN media.themoviedb.org、备用地址，并记住能用的那个。备用地址可填你的反代（如 https://img.example.com），或以 url= 结尾的图片转发（如 https://wsrv.nl/?url=）。第三方镜像会看到你加载了哪些海报，默认不启用。") {
                OutlinedTextField(image, { image = it }, label = { Text("图片地址") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(12.dp, 12.dp, 12.dp, 4.dp), colors = fieldColors)
                OutlinedTextField(imageFb, { imageFb = it }, label = { Text("备用图片地址（可空）") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(12.dp, 4.dp, 12.dp, 4.dp), colors = fieldColors)
                Row(Modifier.fillMaxWidth().padding(12.dp, 0.dp, 12.dp, 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    listOf("填入 wsrv.nl" to "https://wsrv.nl/?url=", "填入 img.amodb.dev" to "https://img.amodb.dev", "清空" to "").forEach { (label, v) ->
                        Text(label, style = IosType.callout, color = c.link, modifier = Modifier.pressable { imageFb = v }.padding(vertical = 6.dp))
                    }
                }
            }
        }
        item {
            GroupedSection(footer = "官方图片地址（image.tmdb.org、media.themoviedb.org）和备用地址都连不上时，再试公共镜像 img.amodb.dev、wsrv.nl。镜像由第三方运营，会看到你加载了哪些海报；不想用可以关掉。") {
                IosRow("自动尝试公共图片镜像", icon = Icons.Rounded.Dns, iconTint = c.orange, showDivider = false,
                    trailing = { IosSwitch(mirrors, { mirrors = it }) })
            }
        }
        item {
            GroupedSection(footer = "用阿里 / 腾讯 DNS-over-HTTPS 解析 TMDB 域名，绕过 DNS 污染；失败时自动回落系统 DNS。只影响 TMDB，不影响网盘。") {
                IosRow("DoH 解析 TMDB 域名", icon = Icons.Rounded.Dns, iconTint = c.teal, showDivider = false,
                    trailing = { IosSwitch(doh, { doh = it }) })
            }
        }
        item {
            GroupedSection(header = "TMDB 代理", footer = "只用于 TMDB 请求和海报下载，网盘流量不走代理。例如 Clash 局域网：192.168.1.10 端口 7890。开启代理时由代理解析域名，DoH 不生效。") {
                IosSegmented(ProxyKind.entries.toList(), proxy, { it.label }, { proxy = it },
                    Modifier.fillMaxWidth().padding(12.dp))
                if (proxy != ProxyKind.NONE) {
                    OutlinedTextField(host, { host = it }, label = { Text("代理主机") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().padding(12.dp, 0.dp, 12.dp, 4.dp), colors = fieldColors)
                    OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("端口") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(12.dp, 4.dp, 12.dp, 12.dp), colors = fieldColors)
                }
            }
        }
        item {
            GroupedSection(footer = testMsg) {
                IosRow(
                    if (testing) "正在连接 TMDB…" else "测试 TMDB 连接",
                    icon = Icons.Rounded.NetworkCheck, iconTint = c.blue, titleColor = c.link, showDivider = false,
                ) {
                    if (testing) return@IosRow
                    save()
                    testing = true; testMsg = null
                    scope.launch {
                        val api = TmdbClient.test().fold({ it }, { "失败：${it.message}" })
                        testMsg = "$api\n正在测试图片…"
                        val img = TmdbClient.testImage().fold({ it }, { "${it.message}" })
                        testMsg = "$api\n$img"
                        testing = false
                    }
                }
            }
        }
    }
}
