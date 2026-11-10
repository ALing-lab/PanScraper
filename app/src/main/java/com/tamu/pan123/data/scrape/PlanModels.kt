package com.tamu.pan123.data.scrape

import kotlinx.serialization.Serializable

@Serializable
data class ScrapePlan(
    val version: Int = 2,
    /** 计划属于哪个网盘账号（v0.2 起）。旧计划缺省为 123 主账号。 */
    val accountId: String = "p123-main",
    val provider: String = "123",
    val createdTs: Double = 0.0,
    val createdAt: String = "",
    val roots: List<PlanRoot> = emptyList(),
    val groups: List<PlanGroup> = emptyList(),
    val actions: List<PlanAction> = emptyList(),
    val flags: List<String> = emptyList(),
    val stats: PlanStats = PlanStats(),
)

@Serializable
data class PlanRoot(
    val folderId: String,
    val name: String,
    val fullDrive: Boolean = false,
)

@Serializable
data class PlanGroup(
    val id: String, // e.g. movie-27205 / tv-106841 / unmatched-xxx
    val kind: String, // movie | tv | collection | unmatched
    val tmdbId: Int? = null,
    val title: String = "",
    val year: String = "",
    val posterPath: String? = null,
    val folderId: String? = null,
    val folderName: String = "",
    val folderNewName: String? = null,
    val itemIds: List<String> = emptyList(), // action ids belonging to this group
    val note: String? = null,
    /** 匹配可信度：手动 / 高 / 中 / 低。 */
    val confidence: String? = null,
    /** 需要留意的说明（如文件夹名与文件名不一致）。 */
    val warn: String? = null,
)

@Serializable
data class PlanAction(
    val id: String,
    val op: String, // rename | mkdir | move | artwork | delete | skip | unmatched（清理旧文件用 mkdir/move + kind=cleanup）
    val groupId: String = "",
    val fileId: String? = null,
    val parentId: String? = null,
    val oldName: String = "",
    val newName: String = "",
    val destParentId: String? = null,
    val destLabel: String? = null,
    val kind: String = "", // file | folder | subtitle | season_nfo | image | tvshow_nfo
    val mediaKind: String? = null, // movie | tv
    val tmdbId: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val artworkUrl: String? = null,
    val done: Boolean = false,
    val selected: Boolean = true,
    val error: String? = null,
    val note: String? = null,
    /** 目标文件夹由本计划里的哪个 mkdir 新建（move / artwork 用；执行时按 mkdir 的结果 ID 解析）。 */
    val destRef: String? = null,
    /** 不能执行的原因（例如当前网盘不支持删除）；非空时不可勾选。 */
    val blocked: String? = null,
)

@Serializable
data class PlanStats(
    val rename: Int = 0,
    val mkdir: Int = 0,
    val move: Int = 0,
    val artwork: Int = 0,
    val skip: Int = 0,
    val unmatched: Int = 0,
    val groups: Int = 0,
    val cleanup: Int = 0,
    val delete: Int = 0,
    /** 因历史记录跳过的文件夹数。 */
    val history: Int = 0,
)
