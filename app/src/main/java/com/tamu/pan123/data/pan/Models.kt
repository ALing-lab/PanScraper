package com.tamu.pan123.data.pan

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class PanResponse(
    val code: Int? = null,
    val message: String? = null,
    val msg: String? = null,
    val data: JsonElement? = null,
) {
    val ok get() = code == 0 || code == 200
    fun err(): String = "code=$code ${message ?: msg ?: ""}".trim()
}

@Serializable
data class ListData(
    @SerialName("InfoList") val infoList: List<PanItem> = emptyList(),
    @SerialName("Next") val next: String? = null,
    val Total: Int? = null,
)

@Serializable
data class PanItem(
    @SerialName("FileId") val fileId: Long = 0,
    @SerialName("FileName") val fileName: String = "",
    @SerialName("Type") val type: Int = 0, // 0=file 1=folder
    @SerialName("Size") val size: Long = 0,
    @SerialName("Etag") val etag: String? = null,
    @SerialName("S3KeyFlag") val s3KeyFlag: String? = null,
    @SerialName("CreateAt") val createAt: String? = null,
    @SerialName("UpdateAt") val updateAt: String? = null,
    @SerialName("ParentFileId") val parentFileId: Long? = null,
) {
    val isDir get() = type == 1
}

@Serializable
data class SignInData(
    val token: String? = null,
    @SerialName("Token") val tokenAlt: String? = null,
) {
    fun tokenOrNull(): String? = token ?: tokenAlt
}

/** 面包屑节点。 */
data class Crumb(val id: String, val name: String)

/** 云盘浏览选中状态（可跨目录多选）。 */
data class BrowseSelection(
    val folders: Map<String, String> = emptyMap(), // id -> path/name
    /** 这些文件夹属于哪个账号（切换网盘后旧选择失效）。 */
    val accountId: String = com.tamu.pan123.data.drive.AccountStore.MAIN_123,
) {
    val count get() = folders.size
    fun toggle(id: String, name: String): BrowseSelection =
        if (folders.containsKey(id)) copy(folders = folders - id)
        else copy(folders = folders + (id to name))
    fun clear() = BrowseSelection(accountId = accountId)
}
