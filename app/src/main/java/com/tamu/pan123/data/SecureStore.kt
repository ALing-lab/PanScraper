package com.tamu.pan123.data

import com.tamu.pan123.BuildConfig
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.Base64

/**
 * 敏感信息：123 token、passport、TMDB API 密钥、各网盘账号凭据。
 * 只用 EncryptedSharedPreferences。设备的加密存储不可用时**不会**再悄悄改存明文：
 * 改为只放在内存里（退出 App 即消失），并通过 [available] 让设置页显示「安全存储不可用」提示。
 * 旧版本可能留下的明文回退文件（secure_v1_fallback）会在启动时迁移进加密存储（或在不可用时只读进内存）然后删除。
 */
object SecureStore {
    private const val PREF = "secure_v1"
    private const val PREF_FALLBACK = "secure_v1_fallback"
    private const val K_TOKEN = "pan_token"
    private const val K_EXP = "pan_exp"
    private const val K_PASSPORT = "pan_passport"
    private const val K_TMDB = "tmdb_key"
    private const val TAG = "SecureStore"

    private lateinit var prefs: SharedPreferences
    private val _available = MutableStateFlow(true)
    /** false = 加密存储不可用：登录信息只保存在内存里，不写入磁盘。 */
    val available: StateFlow<Boolean> = _available.asStateFlow()
    const val UNAVAILABLE_MESSAGE = "安全存储不可用，已禁止保存登录信息（本次登录只保存在内存里，退出 App 后需要重新登录）"

    /**
     * 测试钩子（V35-4）：只在 debug 构建里生效，release 构建里 [BuildConfig.DEBUG] 恒为 false，
     * 读取处的分支会被 R8 删掉，设置了也没有任何效果。
     * - testPlainPrefs：Robolectric 单元测试没有 AndroidKeyStore，用普通 prefs 代替（只在 debug + Robolectric 下自动打开）。
     * - simulateKeystoreFailure：模拟加密存储创建失败。
     */
    @Volatile internal var testPlainPrefs: Boolean = BuildConfig.DEBUG && android.os.Build.FINGERPRINT == "robolectric"
    @Volatile internal var simulateKeystoreFailure: Boolean = false
    private val _session = MutableStateFlow(Session.EMPTY)
    val session: StateFlow<Session> = _session.asStateFlow()
    private val _tmdbKey = MutableStateFlow("")
    val tmdbKey: StateFlow<String> = _tmdbKey.asStateFlow()

    data class Session(
        val token: String,
        val expiresAt: Long,
        val passport: String,
    ) {
        val loggedIn get() = token.isNotBlank() && !expired
        val expired get() = token.isNotBlank() && expiresAt > 0 && expiresAt * 1000L < System.currentTimeMillis()
        val maskedPassport: String
            get() {
                val p = passport
                if (p.isBlank()) return ""
                return when {
                    p.contains("@") -> {
                        val parts = p.split("@", limit = 2)
                        parts[0].take(2) + "***@" + parts[1]
                    }
                    p.length >= 7 -> p.take(3) + "****" + p.takeLast(4)
                    else -> "***"
                }
            }
        companion object { val EMPTY = Session("", 0, "") }
        /** 不在日志 / 崩溃报告里泄露 token。 */
        override fun toString() = "Session(token=${if (token.isBlank()) "" else "***"}, expiresAt=$expiresAt, passport=$maskedPassport)"
    }

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        val app = context.applicationContext
        val enc: SharedPreferences? = try {
            if (BuildConfig.DEBUG && simulateKeystoreFailure) throw java.security.GeneralSecurityException("simulated")
            if (BuildConfig.DEBUG && testPlainPrefs) app.getSharedPreferences("${PREF}_test", Context.MODE_PRIVATE)
            else {
                val master = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                EncryptedSharedPreferences.create(
                    app, PREF, master,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }
        } catch (e: Exception) {
            // 不打印异常细节（可能夹带敏感信息）
            Log.w(TAG, "encrypted prefs unavailable; secrets kept in memory only")
            null
        }
        _available.value = enc != null
        prefs = enc ?: MemoryPrefs()
        migrateLegacyFallback(app, prefs)
        reload()
    }

    /** 旧版明文回退文件：内容搬进当前存储（加密 / 内存），然后清空并删除文件。 */
    private fun migrateLegacyFallback(app: Context, target: SharedPreferences) {
        val legacy = app.getSharedPreferences(PREF_FALLBACK, Context.MODE_PRIVATE)
        val all = legacy.all
        if (all.isNotEmpty()) {
            val ed = target.edit()
            for ((k, v) in all) {
                if (target.contains(k)) continue // 加密存储里已有的以加密存储为准
                when (v) {
                    is String -> ed.putString(k, v)
                    is Long -> ed.putLong(k, v)
                    is Int -> ed.putInt(k, v)
                    is Boolean -> ed.putBoolean(k, v)
                    is Float -> ed.putFloat(k, v)
                }
            }
            ed.commit()
            legacy.edit().clear().commit()
            Log.w(TAG, "migrated ${all.size} legacy plaintext entries")
        }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 24) app.deleteSharedPreferences(PREF_FALLBACK)
            else java.io.File(app.applicationInfo.dataDir, "shared_prefs/$PREF_FALLBACK.xml").delete()
        }
    }

    /** 测试用：重置单例。 */
    internal fun resetForTest() {
        if (::prefs.isInitialized) prefs.edit().clear().commit()
        val f = SecureStore::class.java.getDeclaredField("prefs"); f.isAccessible = true; f.set(this, null)
        _available.value = true
        _session.value = Session.EMPTY
        _tmdbKey.value = ""
    }

    fun reload() {
        if (!::prefs.isInitialized) return
        val token = prefs.getString(K_TOKEN, "") ?: ""
        val exp = prefs.getLong(K_EXP, 0L).let { stored ->
            if (stored > 0) stored else jwtExp(token) ?: 0L
        }
        _session.value = Session(token, exp, prefs.getString(K_PASSPORT, "") ?: "")
        _tmdbKey.value = prefs.getString(K_TMDB, "") ?: ""
    }

    fun saveToken(token: String, passport: String? = null, expiresAt: Long? = null) {
        val exp = expiresAt ?: jwtExp(token) ?: (System.currentTimeMillis() / 1000 + 30L * 86400)
        val ed = prefs.edit().putString(K_TOKEN, token).putLong(K_EXP, exp)
        if (passport != null) ed.putString(K_PASSPORT, passport)
        ed.apply()
        reload()
    }

    fun clearToken() {
        prefs.edit().remove(K_TOKEN).remove(K_EXP).remove(K_PASSPORT).apply()
        reload()
    }

    fun saveTmdbKey(key: String) {
        prefs.edit().putString(K_TMDB, key.trim()).apply()
        _tmdbKey.value = key.trim()
    }

    fun clearTmdbKey() {
        prefs.edit().remove(K_TMDB).apply()
        _tmdbKey.value = ""
    }

    // ---- 通用加密键值（多账号凭据：cookies / token / 密码）。值绝不写日志。 ----
    fun putSecret(key: String, value: String) {
        if (!::prefs.isInitialized) return
        prefs.edit().putString("sec.$key", value).apply()
    }

    fun getSecret(key: String): String? =
        if (!::prefs.isInitialized) null else prefs.getString("sec.$key", null)

    fun removeSecret(key: String) {
        if (!::prefs.isInitialized) return
        prefs.edit().remove("sec.$key").apply()
    }

    /** 从 JWT payload 读 exp（秒）。失败返回 null。绝不打印 token。 */
    fun jwtExp(token: String): Long? {
        if (token.isBlank() || token.count { it == '.' } < 2) return null
        return try {
            val part = token.split('.')[1]
            val pad = part + "=".repeat((4 - part.length % 4) % 4)
            val json = String(Base64.getUrlDecoder().decode(pad))
            JSONObject(json).optLong("exp", 0L).takeIf { it > 0 }
        } catch (_: Exception) { null }
    }
}
