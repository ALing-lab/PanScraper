package com.tamu.pan123.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.tamu.pan123.data.net.NetLog
import com.tamu.pan123.ui.theme.Ios

/**
 * TMDB 海报缩略图：走 TMDB 客户端（DoH / 代理 / 备用图片地址）。
 * 没有海报时显示占位图标；加载失败显示「重试」图标，点一下重新加载，失败原因写进调试日志。
 */
@Composable
fun TmdbImage(url: String?, modifier: Modifier = Modifier) {
    val c = Ios.colors
    Box(modifier) {
        if (url == null) {
            Icon(Icons.Rounded.Movie, null, tint = c.tertiaryLabel, modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        var attempt by remember(url) { mutableIntStateOf(0) }
        val ctx = LocalContext.current
        val req = remember(url, attempt) {
            ImageRequest.Builder(ctx).data(url)
                .setParameter("attempt", attempt, memoryCacheKey = null)
                .memoryCachePolicy(if (attempt > 0) CachePolicy.WRITE_ONLY else CachePolicy.ENABLED)
                .listener(onError = { _, r ->
                    val u = runCatching { java.net.URI(url) }.getOrNull()
                    NetLog.error("TMDB", "缩略图加载失败 ${u?.host.orEmpty()}${u?.path.orEmpty()}：${r.throwable.javaClass.simpleName} ${r.throwable.message.orEmpty()}")
                })
                .build()
        }
        SubcomposeAsyncImage(
            model = req,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            loading = {
                Box(Modifier.fillMaxSize()) {
                    CircularProgressIndicator(Modifier.size(16.dp).align(Alignment.Center), strokeWidth = 2.dp, color = c.tertiaryLabel)
                }
            },
            error = {
                Box(Modifier.fillMaxSize().pressable { attempt++ }) {
                    Icon(Icons.Rounded.Refresh, "重试加载海报", tint = c.secondaryLabel, modifier = Modifier.align(Alignment.Center))
                }
            },
        )
    }
}
