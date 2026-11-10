package com.tamu.pan123.data

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
 * 敏感信息：123 token、passport、TMDB API 密钥。
 * 优先 EncryptedSharedPreferences；在 Robolectric / 设备不支持时回退到普通私有 prefs（仍不写日志）。
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
    }

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        val app = context.applicationContext
        prefs = try {
            val master = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                app, PREF, master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // 不打印异常细节里可能夹带的路径以外的敏感信息
            Log.w(TAG, "encrypted prefs unavailable, using private fallback")
            app.getSharedPreferences(PREF_FALLBACK, Context.MODE_PRIVATE)
        }
        reload()
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
            val pad = part + "=".repeat((-part.length) % 4)
            val json = String(Base64.getUrlDecoder().decode(pad))
            JSONObject(json).optLong("exp", 0L).takeIf { it > 0 }
        } catch (_: Exception) { null }
    }
}
