package com.tamu.pan123

import android.app.Application
import com.tamu.pan123.data.AppDirs
import com.tamu.pan123.data.SecureStore
import com.tamu.pan123.data.Settings
import com.tamu.pan123.data.pan.Pan123Client

class App : Application(), coil.ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // 最先装崩溃记录（之后的初始化出错也能记下来）；目录和 AppDirs.logs 相同
        CrashReporter.install(java.io.File(getExternalFilesDir(null) ?: filesDir, "logs"))
        Settings.init(this)
        SecureStore.init(this)
        AppDirs.init(this)
        com.tamu.pan123.data.net.NetLog.dir = AppDirs.logs
        com.tamu.pan123.data.drive.AccountStore.init()
        Pan123Client.init()
    }
    /** 预览缩略图也走 TMDB 客户端（DoH / 代理 / 备用图片地址），否则大陆网络下全是灰块。 */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .callFactory(okhttp3.Call.Factory { req ->
            // TMDB 图片走 TMDB 客户端（DoH / 代理 / 备用地址）；其它图片（如 115 登录二维码）走普通客户端，不经过 TMDB 代理
            val h = req.url.host
            val tmdb = com.tamu.pan123.data.net.HostMatch.matchesAny(h, listOf("tmdb.org", "themoviedb.org")) || com.tamu.pan123.data.tmdb.TmdbNet.isImageHost(req.url)
            (if (tmdb) com.tamu.pan123.data.tmdb.TmdbNet.client() else plainClient).newCall(req)
        })
        .crossfade(true)
        .build()

    private val plainClient by lazy { okhttp3.OkHttpClient.Builder().build() }

    companion object { lateinit var instance: App; private set }
}
