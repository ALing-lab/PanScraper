package com.tamu.pan123.data.pan

import com.tamu.pan123.data.drive.AccountStore
import com.tamu.pan123.data.drive.CloudDrive
import com.tamu.pan123.data.drive.CloudEntry
import com.tamu.pan123.data.drive.ProviderKind

/** 123 云盘的 [CloudDrive] 实现：委托给 Pan123Client，ID 用十进制字符串。 */
class Pan123Drive(override val accountId: String) : CloudDrive {
    override val kind = ProviderKind.P123
    override val writeIntervalMs = 200L

    private fun tok(): String = AccountStore.token123(accountId).also {
        if (it.isBlank()) error("未登录 123 云盘")
    }

    private fun id(s: String): Long = s.toLongOrNull() ?: error("无效的 123 文件 ID：$s")

    override suspend fun list(folderId: String): List<CloudEntry> =
        Pan123Client.listFiles(id(folderId.ifBlank { "0" }), token = tok()).map { it.toEntry() }

    override suspend fun rename(fileId: String, newName: String): Result<String> =
        runCatching { Pan123Client.rename(id(fileId), newName, token = tok()).getOrThrow(); fileId }

    override suspend fun mkdir(parentId: String, name: String): Result<String> =
        runCatching { Pan123Client.mkdir(id(parentId), name, token = tok()).getOrThrow().toString() }

    override suspend fun move(fileId: String, destParentId: String): Result<String> =
        runCatching { Pan123Client.move(id(fileId), id(destParentId), token = tok()).getOrThrow(); fileId }

    override val trashSupport = com.tamu.pan123.data.drive.TrashSupport.RECYCLE

    override suspend fun trash(fileId: String): Result<String> =
        runCatching { Pan123Client.trash(id(fileId), token = tok()).getOrThrow(); "已移入回收站" }

    override suspend fun upload(parentId: String, name: String, data: ByteArray): Result<String> =
        runCatching { Pan123Client.uploadBytes(id(parentId), name, data, token = tok()).getOrThrow() }

    override suspend fun ping(): Boolean = runCatching { Pan123Client.ping(token = tok()) }.getOrDefault(false)
}

fun PanItem.toEntry(): CloudEntry = CloudEntry(
    fileId = fileId.toString(),
    fileName = fileName,
    isDir = isDir,
    size = size,
    parentFileId = parentFileId?.toString(),
    etag = etag,
)
