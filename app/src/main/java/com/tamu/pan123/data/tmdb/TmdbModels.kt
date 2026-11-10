package com.tamu.pan123.data.tmdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class TmdbSearchPage(
    val results: List<TmdbSearchItem> = emptyList(),
)

@Serializable
data class TmdbSearchItem(
    val id: Int = 0,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_count") val voteCount: Int = 0,
    val popularity: Double = 0.0,
    val video: Boolean = false,
    val overview: String? = null,
)

@Serializable
data class TmdbMovieDetail(
    val id: Int = 0,
    val title: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @SerialName("belongs_to_collection") val belongsToCollection: TmdbCollectionRef? = null,
)

@Serializable
data class TmdbTvDetail(
    val id: Int = 0,
    val name: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @SerialName("number_of_seasons") val numberOfSeasons: Int = 0,
)

@Serializable
data class TmdbCollectionRef(
    val id: Int = 0,
    val name: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
)

@Serializable
data class TmdbTranslations(
    val translations: List<TmdbTranslation> = emptyList(),
)

@Serializable
data class TmdbTranslation(
    @SerialName("iso_3166_1") val iso3166: String? = null,
    @SerialName("iso_639_1") val iso639: String? = null,
    val data: TmdbTranslationData? = null,
)

@Serializable
data class TmdbTranslationData(
    val title: String? = null,
    val name: String? = null,
)

/** 匹配结果（电影或剧集）。 */
data class MatchInfo(
    val kind: String, // movie | tv
    val id: Int,
    val title: String,
    val year: String,
    val posterPath: String? = null,
    val overview: String? = null,
) {
    val posterUrl: String?
        get() = posterPath?.let { com.tamu.pan123.data.tmdb.TmdbNet.imageUrl("https://image.tmdb.org/t/p/w185$it") }
}
