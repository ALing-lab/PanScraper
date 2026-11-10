package com.tamu.pan123.data.drive

import com.tamu.pan123.data.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/** 网盘账号（非敏感部分）。凭据另存于 SecureStore 的 "acct.<id>"。 */
@Serializable
data class DriveAccount(
    val id: String,
    val kind: String,
    val label: String,
    val createdAt: Long = 0,
    /** 非敏感配置，如 WebDAV 地址、用户名、根路径。 */
    val extra: Map<String, String> = emptyMap(),
) {
    val provider: ProviderKind get() = ProviderKind.of(kind)
}

/**
 * 多账号管理。
 * - 原 v0.1 的 123 登录（SecureStore.session）就是 id = [MAIN_123] 的账号，保持兼容。
 * - 其它账号的凭据以 JSON 存在 SecureStore secret 里。
 * - 账号列表 / 当前浏览的账号也放在加密存储里（简单起见）。
 */
object AccountStore {
    const val MAIN_123 = "p123-main"
    private const val K_LIST = "accounts.list"
    private const val K_ACTIVE = "accounts.active"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _accounts = MutableStateFlow<List<DriveAccount>>(emptyList())
    val accounts: StateFlow<List<DriveAccount>> = _accounts.asStateFlow()
    private val _activeId = MutableStateFlow(MAIN_123)
    val activeId: StateFlow<String> = _activeId.asStateFlow()

    /** 登录页完成后写给哪个 123 账号（null = 主账号）。 */
    @Volatile var pending123Target: String? = null

    fun init() {
        val stored = SecureStore.getSecret(K_LIST)?.let {
            runCatching { json.decodeFromString(ListSerializer(DriveAccount.serializer()), it) }.getOrNull()
        } ?: emptyList()
        _accounts.value = withMain(stored)
        _activeId.value = SecureStore.getSecret(K_ACTIVE)
            ?.takeIf { id -> _accounts.value.any { it.id == id } } ?: MAIN_123
    }

    /** 主 123 账号始终在列表首位（即使尚未登录），保证 v0.1 行为不变。 */
    private fun withMain(list: List<DriveAccount>): List<DriveAccount> {
        val others = list.filter { it.id != MAIN_123 }
        val main = list.firstOrNull { it.id == MAIN_123 }
            ?: DriveAccount(MAIN_123, ProviderKind.P123.key, "123云盘", 0)
        return listOf(main) + others
    }

    private fun persist(list: List<DriveAccount>) {
        _accounts.value = withMain(list)
        SecureStore.putSecret(K_LIST, json.encodeToString(ListSerializer(DriveAccount.serializer()), _accounts.value))
    }

    fun get(id: String): DriveAccount? = _accounts.value.firstOrNull { it.id == id }
    fun active(): DriveAccount = get(_activeId.value) ?: _accounts.value.first()

    fun setActive(id: String) {
        if (get(id) == null) return
        _activeId.value = id
        SecureStore.putSecret(K_ACTIVE, id)
    }

    fun add(kind: ProviderKind, label: String, extra: Map<String, String> = emptyMap(), secret: String? = null): DriveAccount {
        val acct = DriveAccount(
            id = "${kind.key}-" + UUID.randomUUID().toString().take(8),
            kind = kind.key,
            label = label.ifBlank { kind.label },
            createdAt = System.currentTimeMillis(),
            extra = extra,
        )
        persist(_accounts.value + acct)
        if (secret != null) SecureStore.putSecret("acct.${acct.id}", secret)
        return acct
    }

    fun update(acct: DriveAccount) {
        persist(_accounts.value.map { if (it.id == acct.id) acct else it })
    }

    /** 删除账号及其凭据。主 123 账号不能删除，只会退出登录。 */
    fun remove(id: String) {
        if (id == MAIN_123) {
            SecureStore.clearToken()
            return
        }
        SecureStore.removeSecret("acct.$id")
        persist(_accounts.value.filter { it.id != id })
        if (_activeId.value == id) setActive(MAIN_123)
    }

    fun secretOf(id: String): String? = SecureStore.getSecret("acct.$id")
    fun saveSecret(id: String, value: String) = SecureStore.putSecret("acct.$id", value)

    /** 123 登录成功（网页 / 密码）后统一走这里：写到 pending 目标账号，默认主账号。 */
    fun save123Login(token: String, passport: String? = null, expiresAt: Long? = null) {
        val target = pending123Target
        pending123Target = null
        if (target == null || target == MAIN_123) {
            SecureStore.saveToken(token, passport, expiresAt)
            return
        }
        val exp = expiresAt ?: SecureStore.jwtExp(token) ?: (System.currentTimeMillis() / 1000 + 30L * 86400)
        val obj = org.json.JSONObject().put("token", token).put("exp", exp).put("passport", passport ?: "")
        saveSecret(target, obj.toString())
        get(target)?.let { a ->
            if (!passport.isNullOrBlank()) update(a.copy(label = "123 · " + mask(passport)))
        }
    }

    /** 某账号是否有可用凭据（不发网络请求）。 */
    fun isLoggedIn(acct: DriveAccount): Boolean = when {
        acct.id == MAIN_123 -> SecureStore.session.value.loggedIn
        acct.provider == ProviderKind.P123 -> token123(acct.id).isNotBlank()
        else -> !secretOf(acct.id).isNullOrBlank()
    }

    fun token123(id: String): String {
        if (id == MAIN_123) return SecureStore.session.value.token
        val s = secretOf(id) ?: return ""
        return runCatching { org.json.JSONObject(s).optString("token") }.getOrDefault("")
    }

    fun mask(p: String): String = when {
        p.contains("@") -> p.substringBefore("@").take(2) + "***@" + p.substringAfter("@")
        p.length >= 7 -> p.take(3) + "****" + p.takeLast(4)
        else -> "***"
    }

    /** 测试用：清空内存状态。 */
    fun resetForTest() {
        _accounts.value = withMain(emptyList())
        _activeId.value = MAIN_123
        pending123Target = null
    }
}
