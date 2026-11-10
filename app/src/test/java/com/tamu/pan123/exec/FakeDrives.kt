package com.tamu.pan123.exec

import com.tamu.pan123.data.drive.CloudDrive
import com.tamu.pan123.data.drive.CloudEntry
import com.tamu.pan123.data.drive.DrivePaths
import com.tamu.pan123.data.drive.ProviderKind

/** ID 稳定的假网盘（模拟 123/115/光鸭）。不发任何网络请求。 */
class FakeDrive(override val kind: ProviderKind = ProviderKind.P123) : CloudDrive {
    override val accountId = "fake"
    override val writeIntervalMs = 0L
    val renames = mutableListOf<Pair<String, String>>()
    val mkdirs = mutableListOf<Pair<String, String>>()
    val moves = mutableListOf<Pair<String, String>>()
    val uploads = mutableListOf<Triple<String, String, Int>>()
    var failRenameIds: Set<String> = emptySet()
    var failNextOps: Int = 0
    private var nextFolderId = 10_000L

    override suspend fun list(folderId: String): List<CloudEntry> = emptyList()

    override suspend fun rename(fileId: String, newName: String): Result<String> {
        if (failNextOps > 0) { failNextOps--; return Result.failure(RuntimeException("mock rename fail")) }
        if (fileId in failRenameIds) return Result.failure(RuntimeException("mock rename $fileId"))
        renames += fileId to newName
        return Result.success(fileId)
    }

    override suspend fun mkdir(parentId: String, name: String): Result<String> {
        if (failNextOps > 0) { failNextOps--; return Result.failure(RuntimeException("mock mkdir fail")) }
        mkdirs += parentId to name
        return Result.success((nextFolderId++).toString())
    }

    override suspend fun move(fileId: String, destParentId: String): Result<String> {
        if (failNextOps > 0) { failNextOps--; return Result.failure(RuntimeException("mock move fail")) }
        moves += fileId to destParentId
        return Result.success(fileId)
    }

    override suspend fun upload(parentId: String, name: String, data: ByteArray): Result<String> {
        if (failNextOps > 0) { failNextOps--; return Result.failure(RuntimeException("mock upload fail")) }
        uploads += Triple(parentId, name, data.size)
        return Result.success("ok")
    }
}

/** 路径型假网盘（模拟 WebDAV）：内存文件树，改名 / 移动会改变 ID。 */
class FakePathDrive(initial: Set<String>, val dirs: MutableSet<String> = mutableSetOf("/")) : CloudDrive {
    override val kind = ProviderKind.WEBDAV
    override val accountId = "fake-dav"
    override val writeIntervalMs = 0L
    val paths: MutableSet<String> = initial.toMutableSet()

    private fun all() = paths + dirs

    override suspend fun list(folderId: String): List<CloudEntry> =
        all().filter { it != "/" && DrivePaths.parentOf(it) == folderId }
            .map { CloudEntry(it, DrivePaths.nameOf(it), it in dirs, parentFileId = folderId) }

    private fun relocate(from: String, to: String) {
        fun mv(set: MutableSet<String>) {
            val hit = set.filter { it == from || it.startsWith("$from/") }
            set.removeAll(hit.toSet())
            set.addAll(hit.map { to + it.substring(from.length) })
        }
        mv(paths); mv(dirs)
    }

    override suspend fun rename(fileId: String, newName: String): Result<String> = runCatching {
        require(fileId in all()) { "not found: $fileId" }
        val to = DrivePaths.join(DrivePaths.parentOf(fileId), newName)
        relocate(fileId, to); to
    }

    override suspend fun mkdir(parentId: String, name: String): Result<String> = runCatching {
        require(parentId in dirs) { "parent not found: $parentId" }
        DrivePaths.join(parentId, name).also { dirs += it }
    }

    override suspend fun move(fileId: String, destParentId: String): Result<String> = runCatching {
        require(fileId in all()) { "not found: $fileId" }
        require(destParentId in dirs) { "dest not found: $destParentId" }
        val to = DrivePaths.join(destParentId, DrivePaths.nameOf(fileId))
        relocate(fileId, to); to
    }

    override suspend fun upload(parentId: String, name: String, data: ByteArray): Result<String> = runCatching {
        require(parentId in dirs); paths += DrivePaths.join(parentId, name); "ok"
    }
}
