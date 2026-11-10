package com.tamu.pan123.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

private val Context.store by preferencesDataStore("settings")

/** 非敏感设置（主题等）。敏感信息（token、TMDB 密钥）放在 SecureStore。 */
object Settings {
    // 写入按调用顺序串行执行（limitedParallelism(1) 是先进先出），连续快速切换开关时最后一次一定最后落盘；
    // DataStore 读写出错只记日志，不让未捕获异常把进程带崩。
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1) +
            CoroutineExceptionHandler { _, e -> runCatching { android.util.Log.w("Settings", "datastore: ${e.javaClass.simpleName}") } }
    )
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
        // 只在启动时读一次已保存的值。之前是持续 collect：切换开关时，先落盘的「另一个开关」那次写入会再发一份旧快照，
        // 把刚改好的内存值覆盖回去（例如关掉「简洁命名」后又自己弹回打开）。所有写入都来自本对象，内存值就是最新的。
        scope.launch {
            val p = ctx.store.data.first()
            p[KEY_THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }?.let { _theme.value = it }
            val raw = p[KEY_TMDB_NET]
            val c = raw?.let { runCatching { cfgJson.decodeFromString(com.tamu.pan123.data.tmdb.TmdbNetConfig.serializer(), it) }.getOrNull() }
            com.tamu.pan123.data.tmdb.TmdbNet.apply(c ?: com.tamu.pan123.data.tmdb.TmdbNetConfig())
            val simple = p[KEY_SIMPLE_NAMING] ?: false
            _simpleNaming.value = simple
            com.tamu.pan123.data.naming.Naming.simpleNaming = simple
            val tmdb = (p[KEY_TMDB_IN_NAME] ?: false) && simple
            _tmdbInName.value = tmdb
            com.tamu.pan123.data.naming.Naming.tmdbInFileName = tmdb
            val dbg = p[KEY_DEBUG_LOG] ?: false
            _debugLog.value = dbg
            com.tamu.pan123.data.net.NetLog.fileEnabled = dbg
        }
    }

    fun setTmdbNet(c: com.tamu.pan123.data.tmdb.TmdbNetConfig) {
        val n = c.normalized()
        com.tamu.pan123.data.tmdb.TmdbNet.apply(n)
        if (::ctx.isInitialized) scope.launch { ctx.store.edit { it[KEY_TMDB_NET] = cfgJson.encodeToString(com.tamu.pan123.data.tmdb.TmdbNetConfig.serializer(), n) } }
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
        if (::ctx.isInitialized) scope.launch { ctx.store.edit { it[KEY_DEBUG_LOG] = on } }
    }

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
        if (::ctx.isInitialized) scope.launch { ctx.store.edit { it[KEY_THEME] = mode.name } }
    }
}
