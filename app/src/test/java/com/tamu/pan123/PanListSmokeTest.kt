package com.tamu.pan123

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 只读冒烟：用 ~/.config/123pan_token.json 列根目录第一页。
 * 不改名、不上传。默认跳过：只有设置环境变量 PAN123_LIVE_SMOKE=1 时才会连真实 123 账号。
 */
class PanListSmokeTest {
    @Test
    fun listRootFirstPage() {
        assumeTrue("live smoke disabled (set PAN123_LIVE_SMOKE=1)", System.getenv("PAN123_LIVE_SMOKE") == "1")
        val f = File(System.getProperty("user.home"), ".config/123pan_token.json")
        assumeTrue("no token cache", f.isFile)
        val token = JSONObject(f.readText()).optString("token")
        assumeTrue("empty token", token.isNotBlank())
        val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val url = "https://123pan.com/b/api/file/list/new?driveId=0&parentFileId=0&limit=5&Page=1&orderBy=file_name&orderDirection=asc&trashed=false&inDirectSpace=false&event=homeListFile&OnlyLookAbnormalFile=0"
        val req = Request.Builder().url(url)
            .header("authorization", "Bearer $token")
            .header("platform", "open_platform")
            .header("app-version", "3")
            .header("user-agent", "Mozilla/5.0 AppleWebKit/600 Safari/600 Chrome/124.0.0.0 Edg/124.0.0.0")
            .get().build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val obj = JSONObject(body)
            val code = obj.optInt("code")
            assert(code == 0 || code == 200) { "list failed code=$code" }
            val list = obj.optJSONObject("data")?.optJSONArray("InfoList")
            assert(list != null) { "no InfoList" }
            // 只断言数量，不打印文件名以外的敏感信息；文件名本身可打印
            println("listRoot ok: ${list!!.length()} items")
        }
    }
}
