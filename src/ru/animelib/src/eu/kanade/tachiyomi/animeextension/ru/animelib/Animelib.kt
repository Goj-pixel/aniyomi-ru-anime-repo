package eu.kanade.tachiyomi.animeextension.ru.animelib

import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.playlistutils.PlaylistUtils
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

class Animelib :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "Animelib"

    override val lang = "ru"

    override val supportsLatest = true

    private val domain: String
        get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!

    override val baseUrl: String
        get() = "https://$domain/ru"

    private val apiSite = "https://api.cdnlibs.org"
    private val apiUrl = "$apiSite/api"

    private val coverDomains = listOf("cover.imglib.info", "cover.hentaicdn.org")
    private val defaultCoverDomain = coverDomains[0]

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val dateFormatter by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH) }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private const val PREF_DOMAIN_KEY = "pref_domain"
        private val PREF_DOMAIN_ENTRIES = arrayOf("animelib.org", "v5.animelib.org", "v4.animelib.org", "v3.animelib.org")
        private const val PREF_DOMAIN_DEFAULT = "animelib.org"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private val PREF_QUALITY_ENTRIES = arrayOf("2160", "1080", "720", "360")

        private const val PREF_USE_MAX_QUALITY_KEY = "pref_use_max_quality"
        private const val PREF_USE_MAX_QUALITY_DEFAULT = true

        private const val PREF_SERVER_KEY = "pref_server"
        private const val SERVER_AUTO = "Авто (как на сайте)"
        private const val SERVER_IMGLIB = "Резервный 2 (video1.imglib.info)"
        private const val SERVER_CDNLIBS = "Основной (video1.cdnlibs.org)"
        private const val SERVER_HENTAICDN = "Резервный 1 (video1.hentaicdn.org)"
        private val PREF_SERVER_ENTRIES = arrayOf(
            SERVER_AUTO,
            SERVER_IMGLIB,
            SERVER_CDNLIBS,
            SERVER_HENTAICDN,
        )

        private const val PREF_DUB_TEAM_KEY = "prev_dub_team"

        private const val PREF_IGNORE_SUBS_KEY = "pref_ignore_subs"
        private const val PREF_IGNORE_SUBS_DEFAULT = false

        private const val PREF_USE_KODIK_KEY = "pref_use_kodik"
        private const val PREF_USE_KODIK_DEFAULT = false

        private val ATOB_REGEX = Regex("atob\\([^\"]")
    }

    override val client = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            val apiHost = apiSite.toHttpUrl().host
            val builder = request.newBuilder()

            val isVideoHost = host.contains("imglib.info") ||
                host.contains("cdnlibs.org") ||
                host.contains("hentaicdn.org")

            if (host in coverDomains || host == apiHost || isVideoHost) {
                builder.header("Referer", "https://$domain/")
                builder.header("Origin", "https://$domain")
                builder.header("User-Agent", DESKTOP_UA)
            }
            if (host == apiHost) {
                builder.header("Accept", "application/json, text/plain, */*")
                builder.header("Site-Id", "5")
                builder.header("X-Requested-With", "XMLHttpRequest")
            }

            val response = chain.proceed(builder.build())
            if (response.code == 403) {
                android.util.Log.w("Animelib", "HTTP 403 for ${request.url} (host=$host)")
            }
            response
        }.build()

    // =============================== Preference ===============================
    private val preferences by getPreferencesLazy()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Домен"
            entries = PREF_DOMAIN_ENTRIES
            entryValues = PREF_DOMAIN_ENTRIES
            summary = "Текущий домен: %s"
            setDefaultValue(PREF_DOMAIN_DEFAULT)
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Предпочитаемый сервер плеера Animelib"
            entries = PREF_SERVER_ENTRIES
            entryValues = PREF_SERVER_ENTRIES
            summary = "%s"
            setDefaultValue(SERVER_AUTO)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_USE_MAX_QUALITY_KEY
            title = "Использовать максимальное доступное качество"
            summary = "Для каждой студии озвучки будет выбрано максимальное качество"
            setDefaultValue(PREF_USE_MAX_QUALITY_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                val value = newValue as Boolean
                val text = if (value) {
                    "Предпочитаемое качество пропадет после закрытия окна настроек"
                } else {
                    "Откройте настройки заново чтобы выбрать предпочитаемое качество"
                }
                Toast.makeText(screen.context, text, Toast.LENGTH_LONG).show()
                preferences.edit().putBoolean(key, value).commit()
            }
        }.also(screen::addPreference)

        if (!preferences.getBoolean(PREF_USE_MAX_QUALITY_KEY, PREF_USE_MAX_QUALITY_DEFAULT)) {
            MultiSelectListPreference(screen.context).apply {
                key = PREF_QUALITY_KEY
                title = "Предпочитаемое качество"
                entries = PREF_QUALITY_ENTRIES
                entryValues = PREF_QUALITY_ENTRIES
                summary = "При отсутствии нужного качества могут возникать ошибки!"
                setDefaultValue(PREF_QUALITY_ENTRIES.toSet())
            }.also(screen::addPreference)
        }

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_USE_KODIK_KEY
            title = "Включить парсинг видео из плеера Kodik"
            summary = "Некоторые видео доступны только в нем (до 720p)"
            setDefaultValue(PREF_USE_KODIK_DEFAULT)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_IGNORE_SUBS_KEY
            title = "Игнорировать субтитры"
            summary = "Исключает видео с субтитрами"
            setDefaultValue(PREF_IGNORE_SUBS_DEFAULT)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_DUB_TEAM_KEY
            title = "Предпочитаемые студии озвучки"
            summary = "Список студий или ключевых слов через запятую"
            setDefaultValue("")
        }.also(screen::addPreference)
    }

    // =============================== Details ===============================
    override fun animeDetailsRequest(anime: SAnime): Request {
        val url = apiUrl.toHttpUrl().newBuilder()
        url.addPathSegment("anime")
        url.addPathSegment(anime.url)
        url.addQueryParameter("fields[]", "genres")
        url.addQueryParameter("fields[]", "summary")
        url.addQueryParameter("fields[]", "authors")
        url.addQueryParameter("fields[]", "publisher")
        url.addQueryParameter("fields[]", "otherNames")
        url.addQueryParameter("fields[]", "anime_status_id")
        return GET(url.build())
    }

    override fun getAnimeUrl(anime: SAnime) = "$baseUrl/${anime.url}"

    override fun animeDetailsParse(response: Response) = response.parseAs<AnimeInfo>().data.toSAnime()

    // =============================== Related ===============================
    override fun relatedAnimeListRequest(anime: SAnime): Request {
        val url = apiUrl.toHttpUrl().newBuilder()
        url.addPathSegment("anime")
        url.addPathSegment(anime.url)
        url.addPathSegment("similar")
        return GET(url.build())
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val animeList = response.parseAs<AnimeSimilars>()
        return animeList.data.map { it.media.toSAnime() }
    }

    // =============================== Episodes ===============================
    override fun episodeListRequest(anime: SAnime): Request {
        val url = apiUrl.toHttpUrl().newBuilder()
        url.addPathSegment("episodes")
        url.addQueryParameter("anime_id", anime.url)
        return GET(url.build())
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodeList = response.parseAs<EpisodeList>()
        return episodeList.data.map { it.toSEpisode() }.reversed()
    }

    // =============================== Video List ===============================
    override fun videoListParse(response: Response): List<Video> = runBlocking {
        val episodeData = response.parseAs<EpisodeVideoData>()
        val serverMap = fetchVideoServersMap()
        val rawTeams = preferences.getString(PREF_DUB_TEAM_KEY, "")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        val allPlayers = episodeData.data.players.orEmpty()
        val preferredTeams = if (rawTeams.isEmpty()) {
            allPlayers
        } else {
            allPlayers.filter { videoInfo ->
                rawTeams.any { videoInfo.team.name.contains(it, true) }
            }
        }

        val useMaxQuality = preferences.getBoolean(
            PREF_USE_MAX_QUALITY_KEY,
            PREF_USE_MAX_QUALITY_DEFAULT,
        )
        val videoInfoList = preferredTeams.filter { videoInfo ->
            val quality = bestQuality(videoInfo)
            val noneBetter = preferredTeams.none {
                bestQuality(it) > quality && it.team.name == videoInfo.team.name
            }
            noneBetter || !useMaxQuality
        }

        val ignoreSubs = preferences.getBoolean(PREF_IGNORE_SUBS_KEY, PREF_IGNORE_SUBS_DEFAULT)

        val videos = videoInfoList.parallelCatchingFlatMap { videoInfo ->
            if (ignoreSubs && videoInfo.translationInfo.id == 1) {
                return@parallelCatchingFlatMap emptyList()
            }
            when (videoInfo.player.lowercase()) {
                "kodik" -> kodikVideoLinks(videoInfo.src, videoInfo.team.name)
                "animelib" -> animelibVideoLinks(videoInfo, serverMap)
                else -> emptyList()
            }
        }

        return@runBlocking videos.sortedByDescending { video ->
            Regex("""(\d+)p""").find(video.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val path = if (episode.url.startsWith("http")) {
            episode.url.toHttpUrl().encodedPath
        } else {
            episode.url
        }
        return GET(
            apiSite.toHttpUrl().newBuilder()
                .addPathSegments(path.removePrefix("/"))
                .build(),
        )
    }

    // =============================== Latest ===============================
    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val url = apiUrl.toHttpUrl().newBuilder()
        url.addPathSegment("anime")
        url.addQueryParameter("page", page.toString())
        url.addQueryParameter("site_id[]", "5")
        url.addQueryParameter("links[]", "")
        url.addQueryParameter("sort_by", "last_episode_at")
        return GET(url.build())
    }

    // =============================== Popular ===============================
    override fun popularAnimeParse(response: Response): AnimesPage {
        val animeList = response.parseAs<AnimeList>()
        val hasNext = !animeList.links?.next.isNullOrEmpty()
        val animes = animeList.data.map { it.toSAnime() }
        return AnimesPage(animes.distinctBy { it.url }, hasNext)
    }

    override fun popularAnimeRequest(page: Int): Request {
        val url = apiUrl.toHttpUrl().newBuilder()
        url.addPathSegment("anime")
        url.addQueryParameter("page", page.toString())
        url.addQueryParameter("site_id[]", "5")
        url.addQueryParameter("links[]", "")
        return GET(url.build())
    }

    // =============================== Search ===============================
    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val searchParams = AnimelibFilters.getSearchParameters(filters)
        val url = apiUrl.toHttpUrl().newBuilder()
        url.addPathSegment("anime")
        url.addQueryParameter("page", page.toString())
        url.addQueryParameter("site_id[]", "5")
        url.addQueryParameter("links[]", "")

        searchParams.genres.include.forEach { url.addQueryParameter("genres[]", it) }
        searchParams.genres.exclude.forEach { url.addQueryParameter("genres_exclude[]", it) }
        searchParams.format.forEach { url.addQueryParameter("types[]", it) }
        searchParams.pegi.forEach { url.addQueryParameter("caution[]", it) }
        searchParams.ongoingStatus.forEach { url.addQueryParameter("status[]", it) }

        url.addQueryParameter("sort_by", searchParams.sortOrder)
        if (searchParams.sortDirection.isNotEmpty()) {
            url.addQueryParameter("sort_type", searchParams.sortDirection)
        }
        url.addQueryParameter("q", query)
        return GET(url.build())
    }

    override fun getFilterList() = AnimelibFilters.FILTER_LIST

    // =============================== Utils ===============================
    private suspend fun fetchVideoServersMap(): Map<String, String> {
        val map = mutableMapOf(
            "main" to "https://video1.cdnlibs.org",
            "secondary" to "https://video1.hentaicdn.org",
            "secondary_2" to "https://video1.imglib.info",
        )
        try {
            val url = apiUrl.toHttpUrl().newBuilder()
                .addPathSegment("constants")
                .addQueryParameter("fields[]", "videoServers")
                .build()
            val videoServers = client.newCall(GET(url)).awaitSuccess().parseAs<VideoServerData>()
            for (srv in videoServers.data.videoServers) {
                if (srv.url.isNotBlank()) {
                    map[srv.id] = srv.url.trimEnd('/')
                    map[srv.label] = srv.url.trimEnd('/')
                }
            }
        } catch (e: Throwable) {
            android.util.Log.w("Animelib", "fetchVideoServersMap fallback used: ${e.message}")
        }
        return map
    }

    private fun resolveServerUrl(videoDomain: String?, serverMap: Map<String, String>): String {
        val pref = preferences.getString(PREF_SERVER_KEY, SERVER_AUTO) ?: SERVER_AUTO
        return when (pref) {
            SERVER_IMGLIB -> serverMap["secondary_2"] ?: "https://video1.imglib.info"
            SERVER_CDNLIBS -> serverMap["main"] ?: "https://video1.cdnlibs.org"
            SERVER_HENTAICDN -> serverMap["secondary"] ?: "https://video1.hentaicdn.org"
            else -> {
                if (!videoDomain.isNullOrBlank()) {
                    serverMap[videoDomain] ?: "https://video1.imglib.info"
                } else {
                    serverMap["secondary_2"] ?: "https://video1.imglib.info"
                }
            }
        }
    }

    private suspend fun kodikVideoLinks(playerUrl: String?, teamName: String): List<Video> {
        val useKodik = preferences.getBoolean(PREF_USE_KODIK_KEY, PREF_USE_KODIK_DEFAULT)
        if (playerUrl.isNullOrEmpty() || !useKodik) {
            return emptyList()
        }

        val kodikPage = UrlUtils.fixUrl(playerUrl) ?: return emptyList()
        val reqHeaders = Headers.Builder()
            .add("Referer", "$baseUrl/")
            .add("User-Agent", DESKTOP_UA)
            .build()

        val page = client.newCall(GET(kodikPage, reqHeaders)).awaitSuccess().useAsJsoup()
        val urlParams = page.selectFirst("script:containsData($domain)")?.data()
            ?: return emptyList()

        val formData = urlParams.substringAfter("urlParams = '")
            .substringBefore("'")
            .parseAs<KodikForm>()

        if (formData.dSign.isEmpty()) {
            return emptyList()
        }

        val kodikDomain = formData.pd
        val formBody = FormBody.Builder()
        formBody.add("d", formData.d)
        formBody.add("d_sign", Uri.decode(formData.dSign))
        formBody.add("pd", formData.pd)
        formBody.add("pd_sign", Uri.decode(formData.pdSign))
        formBody.add("ref", Uri.decode(formData.ref))
        formBody.add("ref_sign", Uri.decode(formData.refSign))

        val urlParts = playerUrl.split('/')
        formBody.add("type", urlParts[3])
        formBody.add("id", urlParts[4])
        formBody.add("hash", urlParts[5])

        val videoInfoRequest = Request.Builder()
            .url("https://$kodikDomain/ftor")
            .post(formBody.build())
            .headers(
                Headers.Builder().apply {
                    set("Referer", "$baseUrl/")
                    set("Origin", "https://$domain")
                    set("User-Agent", DESKTOP_UA)
                }.build(),
            )
            .build()

        val kodikData = client.newCall(videoInfoRequest).awaitSuccess().parseAs<KodikData>()

        val scriptUrl = page.selectFirst("script[src*=player_single]")?.attr("abs:src")
            ?: return emptyList()
        val jsScript = client.newCall(GET(scriptUrl)).awaitSuccess().bodyString()
        val atob = ATOB_REGEX.find(jsScript) ?: return emptyList()

        var encodeScript = ""
        val deque = ArrayDeque<Char>()
        deque.addFirst('(')
        for (i in atob.range.last until jsScript.length) {
            val char = jsScript[i]
            if (char in arrayOf('(', '{')) {
                deque.addFirst(char)
            } else if (char in arrayOf(')', '}')) {
                if (deque.isNotEmpty()) {
                    deque.removeFirst()
                }
            }
            if (deque.isNotEmpty()) {
                encodeScript += char
            } else {
                break
            }
        }

        val useMaxQuality = preferences.getBoolean(
            PREF_USE_MAX_QUALITY_KEY,
            PREF_USE_MAX_QUALITY_DEFAULT,
        )
        val qualityPreference = preferences.getStringSet(PREF_QUALITY_KEY, emptySet())
        val qualityList = if (useMaxQuality) {
            listOf("720")
        } else if (!qualityPreference.isNullOrEmpty()) {
            qualityPreference.toList()
        } else {
            listOf("360", "480", "720")
        }

        return qualityList.flatMap { quality ->
            val quickJs = QuickJs.create()
            val vInfo = when (quality) {
                "360" -> kodikData.links.ugly.firstOrNull()?.src
                "480" -> kodikData.links.bad.firstOrNull()?.src
                "720" -> kodikData.links.good.firstOrNull()?.src
                else -> null
            } ?: return@flatMap emptyList()

            val base64Url = quickJs.use {
                it.evaluate("t='$vInfo'; $encodeScript")
            }.toString()

            val hlsUrl = Base64.decode(base64Url, Base64.DEFAULT).toString(Charsets.UTF_8)
            val playlistUrl = UrlUtils.fixUrl(hlsUrl) ?: return@flatMap emptyList()

            playlistUtils.extractFromHls(
                playlistUrl,
                videoNameGen = { "$teamName (${quality}p Kodik)" },
            )
        }
    }

    private fun animelibVideoLinks(videoInfo: VideoInfo, serverMap: Map<String, String>): List<Video> {
        val videoMeta = videoInfo.video ?: return emptyList()
        if (videoMeta.quality.isEmpty()) return emptyList()

        val serverUrl = resolveServerUrl(videoInfo.videoDomain, serverMap)
        val subtitles = videoInfo.subtitles?.map {
            Track(it.src, "${videoInfo.team.name} (${it.format})")
        } ?: emptyList()

        val videoHeaders = Headers.Builder()
            .add("User-Agent", DESKTOP_UA)
            .add("Referer", "https://$domain/")
            .add("Origin", "https://$domain")
            .build()

        val useMaxQuality = preferences.getBoolean(
            PREF_USE_MAX_QUALITY_KEY,
            PREF_USE_MAX_QUALITY_DEFAULT,
        )
        val maxQuality = bestQuality(videoInfo)
        val qualityPreference = preferences.getStringSet(PREF_QUALITY_KEY, emptySet())

        return videoMeta.quality
            .sortedByDescending { it.quality }
            .mapNotNull {
                if (useMaxQuality && it.quality != maxQuality) {
                    return@mapNotNull null
                } else if (!useMaxQuality && !qualityPreference.isNullOrEmpty()) {
                    if (!qualityPreference.contains(it.quality.toString())) {
                        return@mapNotNull null
                    }
                }

                val href = it.href.trim()
                val url = UrlUtils.fixUrl(href, serverUrl) ?: return@mapNotNull null
                val quality = "${videoInfo.team.name} (${it.quality}p Animelib)"

                Video(url, quality, url, headers = videoHeaders, subtitleTracks = subtitles)
            }
    }

    private fun bestQuality(videoInfo: VideoInfo): Int = when (videoInfo.player.lowercase()) {
        "animelib" -> videoInfo.video?.quality?.maxByOrNull { it.quality }?.quality ?: 0
        "kodik" -> 720
        else -> 0
    }

    // =============================== Converters ===============================
    private fun convertStatus(status: Int): Int = when (status) {
        1 -> SAnime.ONGOING
        2 -> SAnime.COMPLETED
        4 -> SAnime.ON_HIATUS
        5 -> SAnime.CANCELLED
        else -> SAnime.UNKNOWN
    }

    private fun extractTextFromSummary(element: JsonElement?): String? {
        if (element == null) return null
        val sb = StringBuilder()

        fun recurse(el: JsonElement) {
            when (el) {
                is JsonPrimitive -> sb.append(el.content)
                is JsonObject -> {
                    el["text"]?.let { textEl ->
                        if (textEl is JsonPrimitive) sb.append(textEl.content)
                    }
                    el["content"]?.let { recurse(it) }
                }
                is JsonArray -> {
                    for (child in el) {
                        recurse(child)
                        sb.append(" ")
                    }
                }
            }
        }

        recurse(element)
        val result = sb.toString().replace(spaceRegex, " ").trim()
        return result.ifEmpty { null }
    }

    private val spaceRegex by lazy { Regex("\\s+") }

    private fun normalizeCoverUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return UrlUtils.fixUrl(url.trim(), "https://$defaultCoverDomain")
    }

    private fun AnimeData.toSAnime() = SAnime.create().apply {
        setUrlWithoutDomain(href)
        title = rusName ?: engName ?: run {
            when (otherNames) {
                is JsonArray -> otherNames.firstOrNull()?.let { if (it is JsonPrimitive) it.content else null }
                is JsonPrimitive -> otherNames.content
                else -> null
            }
        } ?: href
        thumbnail_url = normalizeCoverUrl(cover.default)
        description = extractTextFromSummary(summary)
        status = convertStatus(animeStatus.id)
        author = publisher?.joinToString { it.name }
        artist = authors?.joinToString { it.name }
    }

    private fun EpisodeInfo.toSEpisode() = SEpisode.create().apply {
        setUrlWithoutDomain("api/episodes/$id")
        val epTitle = episodeName?.trim().orEmpty()
        name = if (epTitle.isNotEmpty()) "Сезон $season Серия $number $epTitle" else "Сезон $season Серия $number"
        episode_number = number.toFloatOrNull() ?: 0f
        date_upload = dateFormatter.tryParse(date)
    }
}
