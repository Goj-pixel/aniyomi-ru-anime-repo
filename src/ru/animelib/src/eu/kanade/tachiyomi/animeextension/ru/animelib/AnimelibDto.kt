package eu.kanade.tachiyomi.animeextension.ru.animelib

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class AnimeStatus(
    val id: Int,
)

@Serializable
data class CoverInfo(
    val default: String,
)

@Serializable
data class GenreInfo(
    val id: Int,
    val name: String,
)

@Serializable
data class PublisherInfo(
    val id: Int,
    val name: String,
)

@Serializable
data class AuthorInfo(
    val id: Int,
    val name: String,
)

@Serializable
data class AnimeData(
    val id: Int,
    @SerialName("rus_name") val rusName: String? = null,
    @SerialName("eng_name") val engName: String? = null,
    @SerialName("slug_url") val href: String,
    @SerialName("status") val animeStatus: AnimeStatus,
    val cover: CoverInfo,

    // Optional
    @SerialName("is_licensed") val licensed: Boolean? = null,
    val summary: JsonElement? = null,
    val genres: List<GenreInfo>? = null,
    val publisher: List<PublisherInfo>? = null,
    val authors: List<AuthorInfo>? = null,
    @SerialName("otherNames") val otherNames: JsonElement? = null,
)

@Serializable
data class PageMetaData(
    val next: String? = null,
)

@Serializable
data class AnimeList(
    val data: List<AnimeData>,
    val links: PageMetaData? = null,
)

@Serializable
data class AnimeInfo(
    val data: AnimeData,
)

@Serializable
data class AnimeSimilars(
    val data: List<AnimeSimilar>,
)

@Serializable
data class AnimeSimilar(
    val media: AnimeData,
)

// ============================== Episode ==============================
@Serializable
data class VideoInfo(
    val id: JsonElement? = null,
    val player: String? = null,
    val team: JsonElement? = null,
    @SerialName("team_name") val teamName: String? = null,
    @SerialName("team_id") val teamId: JsonElement? = null,
    @SerialName("translation_type") val translationType: JsonElement? = null,
    val src: JsonElement? = null,
    val href: JsonElement? = null,
    val url: JsonElement? = null,
    val hls: JsonElement? = null,
    val stream: JsonElement? = null,
    val video: JsonElement? = null,
    val subtitles: JsonElement? = null,
)

@Serializable
data class EpisodeInfo(
    val id: Int = 0,
    @SerialName("anime_id") val animeId: JsonElement? = null,
    @SerialName("name") val episodeName: String? = null,
    val number: JsonElement? = null,
    val season: JsonElement? = null,
    @SerialName("created_at") val date: String? = null,
    val players: JsonElement? = null,
    val teams: JsonElement? = null,
    val translations: JsonElement? = null,
)

@Serializable
data class EpisodeVideoData(
    val data: EpisodeInfo,
)

@Serializable
data class EpisodeList(
    val data: List<EpisodeInfo>,
)

// ============================== VideoServer ==============================
@Serializable
data class VideoServerInfo(
    val id: String,
    val label: String,
    val url: String,
)

@Serializable
data class VideoServers(
    val videoServers: List<VideoServerInfo> = emptyList(),
)

@Serializable
data class VideoServerData(
    val data: VideoServers = VideoServers(),
)

// ============================== Kodik ==============================
@Serializable
data class KodikForm(
    val d: String = "",
    @SerialName("d_sign") val dSign: String = "",
    val pd: String = "",
    @SerialName("pd_sign") val pdSign: String = "",
    val ref: String = "",
    @SerialName("ref_sign") val refSign: String = "",
)

@Serializable
data class KodikVideoInfo(
    val src: String,
)

@Serializable
data class KodikVideoQuality(
    @SerialName("480") val bad: List<KodikVideoInfo>,
    @SerialName("720") val good: List<KodikVideoInfo>,
    @SerialName("360") val ugly: List<KodikVideoInfo>,
)

@Serializable
data class KodikData(
    val links: KodikVideoQuality,
)
