package com.tamu.pan123.data.drive

/**
 * 网盘提供方。v0.2 起扫描 / 执行 / 预览都只依赖 [CloudDrive]，123 只是其中一个实现。
 *
 * - idsArePaths：WebDAV 这类以路径作为 ID 的提供方，改名 / 移动后 ID 会变化（引擎会做 ID 重映射）。
 * - rootId：根目录 ID（123/115 是 "0"，光鸭是 ""，WebDAV 是 "/"）。
 */
enum class ProviderKind(
    val key: String,
    val label: String,
    val idsArePaths: Boolean,
    val rootId: String,
    /** 当前版本是否已实现读写。未实现的提供方可以添加账号，但列目录 / 执行会提示“暂不支持”。 */
    val implemented: Boolean,
    val loginHint: String,
) {
    P123("123", "123云盘", false, "0", true, "网页登录（推荐）或账号密码"),
    P115("115", "115网盘", false, "0", true, "扫码登录（115 App 扫一扫）"),
    GUANGYA("guangya", "光鸭云盘", false, "", true, "网页登录（短信验证码 / 扫码）"),
    WEBDAV("webdav", "WebDAV", true, "/", true, "服务器地址 + 用户名 + 密码"),
    ;

    /** 列在网盘选项下面的小字说明（目前只有 115）。 */
    val authorNote: String?
        get() = if (this == P115) AUTHOR_NOTE_115 else null

    companion object {
        fun of(key: String?): ProviderKind = entries.firstOrNull { it.key == key } ?: P123
    }
}

enum class TrashSupport(val label: String) {
    RECYCLE("删除后进入网盘回收站，可恢复"),
    PERMANENT("WebDAV 没有回收站，删除后不可恢复"),
    NONE("当前网盘不支持由本应用删除"),
    ;

    companion object {
        fun noneHint(kind: ProviderKind) = "${kind.label} 当前的登录方式 / 接口不允许删除，请到 ${kind.label} App 或网页里手动删除"

        /** 执行时的删除错误：权限类错误给出明确提示。 */
        fun explain(kind: ProviderKind, raw: String): String {
            val r = raw.lowercase()
            val perm = listOf("permission", "forbidden", "denied", "unauthorized", "not allowed", "权限", "无权", "禁止", "不允许", " 401", " 403", "(401", "(403", "http 401", "http 403")
            return if (perm.any { it in r } || raw.startsWith("401") || raw.startsWith("403"))
                "没有删除权限：$raw。${noneHint(kind)}"
            else raw
        }
    }
}

/** 统一的目录项。字段名沿用 123 的 PanItem，扫描器代码无需大改。ID 一律是字符串。 */
data class CloudEntry(
    val fileId: String,
    val fileName: String,
    val isDir: Boolean,
    val size: Long = 0,
    val parentFileId: String? = null,
    val etag: String? = null,
)

/**
 * 网盘读写抽象。写操作（rename/mkdir/move/upload）只应由执行引擎在用户确认后调用；
 * 开发机上的测试一律注入 Fake，不连真实账号。
 */
interface CloudDrive {
    val kind: ProviderKind
    val accountId: String
    val rootId: String get() = kind.rootId

    /** 两次写操作之间的最小间隔（毫秒），按各家限频设置。 */
    val writeIntervalMs: Long get() = 200

    /** 列出直属子项（自动翻页）。只读。 */
    suspend fun list(folderId: String): List<CloudEntry>

    /** 改名。返回改名后的 ID（ID 稳定的提供方原样返回；路径型返回新路径）。 */
    suspend fun rename(fileId: String, newName: String): Result<String>

    /** 新建文件夹；同名已存在时返回已有文件夹 ID。 */
    suspend fun mkdir(parentId: String, name: String): Result<String>

    /** 移动到目标文件夹。返回移动后的 ID。 */
    suspend fun move(fileId: String, destParentId: String): Result<String>

    /** 上传小文件（海报 / NFO）。返回提示文本（如 "ok" / "秒传"）。 */
    suspend fun upload(parentId: String, name: String, data: ByteArray): Result<String>

    /**
     * 删除能力（v0.3）：RECYCLE = 进网盘回收站；PERMANENT = 直接删除（WebDAV 没有回收站）；NONE = 当前登录方式不支持。
     */
    val trashSupport: TrashSupport get() = TrashSupport.NONE

    /** 删除文件 / 文件夹（优先进回收站）。只由执行引擎在用户勾选后调用。 */
    suspend fun trash(fileId: String): Result<String> =
        Result.failure(UnsupportedOperationException(TrashSupport.noneHint(kind)))

    /** 目录下是否已有同名项（不区分大小写）。 */
    suspend fun exists(parentId: String, name: String): Boolean =
        list(parentId).any { it.fileName.equals(name, ignoreCase = true) }

    /** 凭据是否可用（列根目录探测）。 */
    suspend fun ping(): Boolean = runCatching { list(rootId); true }.getOrDefault(false)
}

/** 还没实现的提供方：所有操作都返回清晰的“暂不支持”错误，绝不发请求。 */
class PendingDrive(override val kind: ProviderKind, override val accountId: String) : CloudDrive {
    private fun msg() = "暂不支持 ${kind.label}，请切换到其它网盘账号。"
    override suspend fun list(folderId: String): List<CloudEntry> = throw UnsupportedOperationException(msg())
    override suspend fun rename(fileId: String, newName: String) = Result.failure<String>(UnsupportedOperationException(msg()))
    override suspend fun mkdir(parentId: String, name: String) = Result.failure<String>(UnsupportedOperationException(msg()))
    override suspend fun move(fileId: String, destParentId: String) = Result.failure<String>(UnsupportedOperationException(msg()))
    override suspend fun upload(parentId: String, name: String, data: ByteArray) = Result.failure<String>(UnsupportedOperationException(msg()))
    override suspend fun ping(): Boolean = false
}

/** 路径工具（WebDAV 等路径型 ID 使用）。 */
object DrivePaths {
    fun join(parent: String, name: String): String =
        if (parent.isEmpty() || parent == "/") "/$name" else parent.trimEnd('/') + "/" + name

    fun parentOf(path: String): String {
        val p = path.trimEnd('/')
        val i = p.lastIndexOf('/')
        return if (i <= 0) "/" else p.substring(0, i)
    }

    fun nameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')
}

const val AUTHOR_NOTE_115 = "作者没有115网盘，功能如果存在问题请联系作者。"
