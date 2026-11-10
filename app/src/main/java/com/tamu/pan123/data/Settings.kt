package com.tamu.pan123.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

private val Context.store by preferencesDataStore("settings")

/** 非敏感设置（主题等）。敏感信息（token、TMDB 密钥）放在 SecureStore。 */
object Settings {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val KEY_THEME = stringPreferencesKey("theme_mode")
    private val _theme = MutableStateFlow(ThemeMode.SYSTEM)
    val theme: StateFlow<ThemeMode> = _theme
    private val KEY_TMDB_NET = stringPreferencesKey("tmdb_net")
    private val cfgJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val KEY_DEBUG_LOG = booleanPreferencesKey("debug_net_log")
    private val _debugLog = MutableStateFlow(false)
    /** 调试日志：把脱敏后的请求记录写进 logs/net.log。 */
    val debugLog: StateFlow<Boolean> = _debugLog
    private val KEY_SIMPLE_NAMING = booleanPreferencesKey("simple_naming")
    private val _simpleNaming = MutableStateFlow(false)
    /** 简洁命名：只影响之后扫描 / 重新匹配生成的改名。 */
    val simpleNaming: StateFlow<Boolean> = _simpleNaming
    private val KEY_TMDB_IN_NAME = booleanPreferencesKey("tmdb_in_file_name")
    private val _tmdbInName = MutableStateFlow(false)
    /** 播放器兼容命名：视频文件名末尾加 {tmdb-ID}。 */
    val tmdbInFileName: StateFlow<Boolean> = _tmdbInName
    private lateinit var ctx: Context

    fun init(context: Context) {
        ctx = context.applicationContext
        scope.launch {
            ctx.store.data.map { p -> p[KEY_THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM }
                .collect { _theme.value = it }
        }
        scope.launch {
            ctx.store.data.map { p -> p[KEY_TMDB_NET] }.collect { raw ->
                val c = raw?.let { runCatching { cfgJson.decodeFromString(com.tamu.pan123.data.tmdb.TmdbNetConfig.serializer(), it) }.getOrNull() }
                com.tamu.pan123.data.tmdb.TmdbNet.apply(c ?: com.tamu.pan123.data.tmdb.TmdbNetConfig())
            }
        }
        scope.launch {
            ctx.store.data.map { p -> p[KEY_SIMPLE_NAMING] ?: false }.collect {
                _simpleNaming.value = it
                com.tamu.pan123.data.naming.Naming.simpleNaming = it
            }
        }
        scope.launch {
            ctx.store.data.map { p -> p[KEY_TMDB_IN_NAME] ?: false }.collect {
                _tmdbInName.value = it
                com.tamu.pan123.data.naming.Naming.tmdbInFileName = it
            }
        }
        scope.launch {
            ctx.store.data.map { p -> p[KEY_DEBUG_LOG] ?: false }.collect {
                _debugLog.value = it
                com.tamu.pan123.data.net.NetLog.fileEnabled = it
            }
        }
    }

    fun setTmdbNet(c: com.tamu.pan123.data.tmdb.TmdbNetConfig) {
        val n = c.normalized()
        com.tamu.pan123.data.tmdb.TmdbNet.apply(n)
        scope.launch { ctx.store.edit { it[KEY_TMDB_NET] = cfgJson.encodeToString(com.tamu.pan123.data.tmdb.TmdbNetConfig.serializer(), n) } }
    }

    fun setSimpleNaming(on: Boolean) {
        if (!on && _tmdbInName.value) setTmdbInFileName(false) // 子开关跟着关
        _simpleNaming.value = on
        com.tamu.pan123.data.naming.Naming.simpleNaming = on
        if (::ctx.isInitialized) scope.launch { ctx.store.edit { it[KEY_SIMPLE_NAMING] = on } }
    }

    fun setTmdbInFileName(on0: Boolean) {
        val on = on0 && _simpleNaming.value // 需先开启简洁命名
        _tmdbInName.value = on
        com.tamu.pan123.data.naming.Naming.tmdbInFileName = on
        if (::ctx.isInitialized) scope.launch { ctx.store.edit { it[KEY_TMDB_IN_NAME] = on } }
    }

    fun setDebugLog(on: Boolean) {
        _debugLog.value = on
        com.tamu.pan123.data.net.NetLog.fileEnabled = on
        com.tamu.pan123.data.net.NetLog.line("app", if (on) "debug log enabled" else "debug log disabled")
        scope.launch { ctx.store.edit { it[KEY_DEBUG_LOG] = on } }
    }

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
        scope.launch { ctx.store.edit { it[KEY_THEME] = mode.name } }
    }
}
