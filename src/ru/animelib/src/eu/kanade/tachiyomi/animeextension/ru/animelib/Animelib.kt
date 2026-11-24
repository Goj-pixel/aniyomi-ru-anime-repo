package eu.kanade.tachiyomi.animeextension.ru.animelib

import android.net.Uri
import android.text.InputType
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
import kotlinx.serialization.json.contentOrNull
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

    private val apiSite = "https://hapi.hentaicdn.org"
    private val apiUrl = "$apiSite/api"
    private val defaultVideoServer = "https://video1.cdnlibs.org/.аs/"

    // Some deployments use different cover hosts — include both common ones
    private val coverDomains = listOf("cover.hentaicdn.org", "cover.imglib.info")
    private val defaultCoverDomain = coverDomains[0]

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val dateFormatter by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH) }

    companion object {
        private const val PREF_DOMAIN_KEY = "pref_domain"
        private val PREF_DOMAIN_ENTRIES = arrayOf("animelib.org", "v3.animelib.org", "v4.animelib.org", "v5.animelib.org")
        private const val PREF_DOMAIN_DEFAULT = "animelib.org"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private val PREF_QUALITY_ENTRIES = arrayOf("360", "720", "1080", "2160")

        private const val PREF_USE_MAX_QUALITY_KEY = "pref_use_max_quality"
        private const val PREF_USE_MAX_QUALITY_DEFAULT = true

        private const val PREF_SERVER_KEY = "pref_server"
        private val PREF_SERVER_ENTRIES = arrayOf("Основной", "Резервный 1", "Резервный 2")

        private const val PREF_DUB_TEAM_KEY = "prev_dub_team"

        private const val PREF_AUTH_TOKEN_KEY = "pref_auth_token"

        private const val PREF_IGNORE_SUBS_KEY = "pref_ignore_subs"
        private const val PREF_IGNORE_SUBS_DEFAULT = true

        private const val PREF_USE_KODIK_KEY = "pref_use_kodik"
        private const val PREF_USE_KODIK_DEFAULT = true

        private val ATOB_REGEX = Regex("atob\\([^\"]")
    }

    override val client = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            val apiHost = apiSite.toHttpUrl().host
            val builder = request.newBuilder()
            // Add Referer/Origin for cover and API hosts
            if (host in coverDomains || host == apiHost) {
                builder.header("Referer", "https://$domain/")
                builder.header("Origin", "https://$domain")
            }
            // Additional headers for API requests to bypass simple bot protections
            if (host == apiHost) {
                builder.header("Site-Id", "5")
                builder.header("Accept", "application/json, text/plain, */*")
                builder.header("X-Requested-With", "XMLHttpRequest")
                builder.header("User-Agent", "Mozilla/5.0 (Android)")
                val authorization = authorizationHeader()
                if (authorization == null) {
                    builder.removeHeader("Authorization")
                } else {
                    builder.header("Authorization", authorization)
                }
            }

            val requestToProceed = builder.build()
            val response = chain.proceed(requestToProceed)

            // Log 403 responses for easier debugging
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
            setDefaultValue(PREF_SERVER_ENTRIES[0])
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

        if (!preferences.getBoolean(PREF_USE_MAX_QUALITY_KEY, true)) {
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
            summary = "Некоторые видео доступны только в нем, но он может работать нестабильно"
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
            summary = "Список студий или ключевых слов через запятую (экспериментальная функция)"
            setDefaultValue("")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_AUTH_TOKEN_KEY
            title = "Токен авторизации (Bearer)"
            summary = "Необязательно. Пустое поле отправляет запросы без Authorization."
            setDefaultValue("")
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }.also(screen::addPreference)
    }

    private fun authorizationHeader(): String? {
        val configuredToken = preferences.getString(PREF_AUTH_TOKEN_KEY, "").orEmpty().trim()
        val token = configuredToken
            .replaceFirst(Regex("^Bearer(?:\\s+|$)", RegexOption.IGNORE_CASE), "")
            .trim()
        return token.takeIf { it.isNotBlank() }?.let { "Bearer $it" }
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
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val episodeUri = android.net.Uri.parse(episode.url)
        val animeId = episodeUri.getQueryParameter("anime_id")
        val episodeId = episodeUri.lastPathSegment.orEmpty()

        return try {
            client.newCall(videoListRequest(episode)).awaitSuccess().use(::videoListParse)
        } catch (e: Exception) {
            val fallbackPlayers = fetchFallbackEpisode(animeId, episodeId)?.videoInfoList().orEmpty()
            if (fallbackPlayers.isEmpty()) throw e
            android.util.Log.w("Animelib", "Primary episode request failed; using anime episode fallback", e)
            buildVideoList(fallbackPlayers)
        }
    }

    override fun videoListParse(response: Response): List<Video> = runBlocking {
        val episodeId = response.request.url.pathSegments.lastOrNull().orEmpty()
        val animeId = response.request.url.queryParameter("anime_id")
        val episodeData = try {
            response.parseAs<EpisodeVideoData>().data
        } catch (e: Exception) {
            android.util.Log.w("Animelib", "Could not parse episode players; trying anime episode fallback", e)
            null
        }
        val videoInfoList = episodeData?.videoInfoList()
            ?.takeIf { it.isNotEmpty() }
            ?: fetchFallbackEpisode(animeId, episodeId)?.videoInfoList().orEmpty()
        buildVideoList(videoInfoList)
    }

    private suspend fun buildVideoList(videoInfoList: List<VideoInfo>): List<Video> {
        if (videoInfoList.isEmpty()) return emptyList()
        val videoServer = fetchPreferredVideoServer()
        val teams = preferences.getString(PREF_DUB_TEAM_KEY, "")?.split(',')

        val preferredTeams = videoInfoList.filter { videoInfo ->
            teams.isNullOrEmpty() || teams.any { videoInfo.teamDisplayName.contains(it.trim(), true) }
        }

        val useMaxQuality = preferences.getBoolean(
            PREF_USE_MAX_QUALITY_KEY,
            PREF_USE_MAX_QUALITY_DEFAULT,
        )
        val bestTeamVideos = preferredTeams.filter { videoInfo ->
            val quality = bestQuality(videoInfo)
            val noneBetter = preferredTeams.none {
                bestQuality(it) > quality && it.teamDisplayName == videoInfo.teamDisplayName
            }

            noneBetter || !useMaxQuality
        }

        val ignoreSubs = preferences.getBoolean(PREF_IGNORE_SUBS_KEY, PREF_IGNORE_SUBS_DEFAULT)
        val hasDubbedPlayers = bestTeamVideos.any { it.translationTypeId != 1 }

        return bestTeamVideos.parallelCatchingFlatMap { videoInfo ->
            if (ignoreSubs && hasDubbedPlayers && videoInfo.translationTypeId == 1) {
                return@parallelCatchingFlatMap emptyList()
            }
            val playerName = videoInfo.player.orEmpty().lowercase()
            when (playerName) {
                "kodik" -> kodikVideoLinks(videoInfo.src?.asString(), videoInfo.teamDisplayName)
                "animelib" -> animelibVideoLinks(videoInfo, videoServer)
                else -> emptyList()
            }
        }
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val parsed = android.net.Uri.parse(episode.url)
        val path = parsed.path.orEmpty().removePrefix("/")
        val requestUrl = apiSite.toHttpUrl().newBuilder()
            .addPathSegments(path)
        parsed.getQueryParameter("anime_id")?.let {
            requestUrl.addQueryParameter("anime_id", it)
        }
        return GET(requestUrl.build())
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
    private suspend fun fetchPreferredVideoServer(): String {
        val url = apiUrl.toHttpUrl().newBuilder()
        url.addPathSegment("constants")
        url.addQueryParameter("fields[]", "videoServers")

        val serverPreference = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_ENTRIES[0])
        return try {
            val videoServers = client.newCall(GET(url.build())).awaitSuccess()
                .parseAs<VideoServerData>().data.videoServers
            val chosen = videoServers.firstOrNull { it.label == serverPreference }?.url
                ?: videoServers.firstOrNull()?.url
                ?: fallbackVideoServer(serverPreference)
            android.util.Log.d("Animelib", "fetchPreferredVideoServer: chosen=$chosen preference=$serverPreference")
            chosen
        } catch (e: Exception) {
            android.util.Log.w("Animelib", "Could not load video servers; using the configured CDN fallback", e)
            fallbackVideoServer(serverPreference)
        }
    }

    private fun fallbackVideoServer(serverPreference: String?): String = when (serverPreference) {
        PREF_SERVER_ENTRIES[1] -> "https://video2.cdnlibs.org/"
        PREF_SERVER_ENTRIES[2] -> "https://video1.imglib.info/"
        else -> defaultVideoServer
    }

    private suspend fun fetchFallbackEpisode(animeId: String?, episodeId: String): EpisodeInfo? {
        if (animeId.isNullOrBlank() || episodeId.isBlank()) return null

        val requestUrl = apiUrl.toHttpUrl().newBuilder()
            .addPathSegment("anime")
            .addPathSegment(animeId)
            .addPathSegment("episodes")
            .addQueryParameter("site_id", "5")
            .build()

        return try {
            val payload = client.newCall(GET(requestUrl)).awaitSuccess().parseAs<JsonElement>()
            val episodes = when (val data = (payload as? JsonObject)?.get("data")) {
                is JsonArray -> data
                is JsonObject -> JsonArray(listOf(data))
                else -> JsonArray(emptyList())
            }
            episodes.firstNotNullOfOrNull { item ->
                val itemId = (item as? JsonObject)?.get("id").asString()
                if (itemId == episodeId) item.parseAs<EpisodeInfo>() else null
            }?.also {
                android.util.Log.d("Animelib", "Loaded episode players from anime fallback endpoint")
            }
        } catch (e: Exception) {
            android.util.Log.w("Animelib", "Anime episode fallback unavailable for anime $animeId", e)
            null
        }
    }

    private fun EpisodeInfo.videoInfoList(): List<VideoInfo> {
        val entries = when (val rawPlayers = players) {
            is JsonArray -> rawPlayers.toList()
            is JsonObject -> if ("player" in rawPlayers) {
                listOf(rawPlayers)
            } else {
                rawPlayers.values.toList()
            }
            else -> emptyList()
        }

        return entries.mapNotNull { item ->
            try {
                item.parseAs<VideoInfo>()
            } catch (e: Exception) {
                android.util.Log.w("Animelib", "Skipping an unrecognized player entry", e)
                null
            }
        }
    }

    private fun JsonElement?.asString(): String? = when (this) {
        is JsonPrimitive -> contentOrNull
        else -> null
    }

    private fun JsonElement?.asInt(): Int? {
        val value = when (this) {
            is JsonObject -> get("id").asString()
            else -> asString()
        }
        return value?.toIntOrNull()
    }

    private val VideoInfo.teamDisplayName: String
        get() = (team as? JsonObject)?.get("name").asString()
            ?: teamName
            ?: team.asString()
            ?: teamId.asString()
            ?: "Animelib"

    private val VideoInfo.translationTypeId: Int
        get() {
            val id = translationType.asInt()
            if (id != null) return id
            val label = ((translationType as? JsonObject)?.get("label") ?: translationType).asString().orEmpty()
            return if (label.contains("субтит", ignoreCase = true) || label.contains("subtitle", ignoreCase = true)) {
                1
            } else {
                0
            }
        }

    private fun JsonElement?.findMediaUrl(): String? {
        when (this) {
            is JsonPrimitive -> {
                val value = contentOrNull?.trim().orEmpty()
                if (value.startsWith("http://") || value.startsWith("https://") ||
                    value.startsWith("//") || value.startsWith("/") ||
                    (
                        value.isNotEmpty() && !value.any(Char::isWhitespace) &&
                            ("/" in value || Regex("""\.(m3u8|mp4|webm)(?:[?#].*)?$""", RegexOption.IGNORE_CASE).containsMatchIn(value))
                        )
                ) {
                    return value
                }
            }
            is JsonArray -> for (item in this) {
                item.findMediaUrl()?.let { return it }
            }
            is JsonObject -> {
                for (key in listOf("href", "src", "url", "link", "stream", "hls", "file", "manifest", "m3u8", "path")) {
                    this[key].findMediaUrl()?.let { return it }
                }
                for (value in values) {
                    value.findMediaUrl()?.let { return it }
                }
            }
            else -> Unit
        }
        return null
    }

    private fun VideoInfo.qualityItems(): List<JsonElement> {
        val quality = (video as? JsonObject)?.get("quality") ?: return emptyList()
        return when (quality) {
            is JsonArray -> quality.toList()
            is JsonObject -> if (listOf("href", "src", "url", "hls", "quality").any { it in quality }) {
                listOf(quality)
            } else {
                quality.map { (label, value) ->
                    JsonObject(mapOf("quality" to JsonPrimitive(label), "value" to value))
                }
            }
            else -> emptyList()
        }
    }

    private fun qualityValue(item: JsonElement): Int {
        val raw = when (item) {
            is JsonObject -> (item["quality"] ?: item["name"]).asString()
            is JsonPrimitive -> item.contentOrNull
            else -> null
        }.orEmpty()
        return Regex("\\d+").find(raw)?.value?.toIntOrNull() ?: 0
    }

    private fun normalizeStreamUrl(raw: String, serverUrl: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val absoluteUrl = when {
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("//") -> "https:$value"
            else -> null
        }
        if (absoluteUrl != null) return absoluteUrl.toHttpUrl().toString()

        val serverBase = serverUrl.toHttpUrl()
        val origin = serverBase.newBuilder()
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
            .toString()
            .trimEnd('/')
        val basePath = serverBase.encodedPath.trimEnd('/')
        val candidatePath = "$origin/${value.trimStart('/')}".toHttpUrl().encodedPath
        val hrefIncludesBasePath = basePath.isNotEmpty() &&
            (
                candidatePath.equals(basePath, ignoreCase = true) ||
                    candidatePath.startsWith("$basePath/", ignoreCase = true)
                )
        val joinedUrl = if (hrefIncludesBasePath) {
            "$origin/${value.trimStart('/')}"
        } else {
            "${serverUrl.trimEnd('/')}/${value.trimStart('/')}"
        }
        return joinedUrl.toHttpUrl().toString()
    }

    private fun playerHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", headers["User-Agent"] ?: "Mozilla/5.0 (Android)")
        .build()

    private fun subtitleTracks(videoInfo: VideoInfo, serverUrl: String): List<Track> {
        val subtitleEntries = when (val subtitles = videoInfo.subtitles) {
            is JsonArray -> subtitles.toList()
            is JsonObject -> subtitles.values.toList()
            else -> emptyList()
        }
        return subtitleEntries.mapNotNull { subtitle ->
            val rawUrl = subtitle.findMediaUrl() ?: return@mapNotNull null
            val url = normalizeStreamUrl(rawUrl, serverUrl) ?: return@mapNotNull null
            val format = (subtitle as? JsonObject)?.get("format").asString().orEmpty()
            Track(url, "${videoInfo.teamDisplayName} ($format)")
        }
    }

    private suspend fun kodikVideoLinks(playerUrl: String?, teamName: String): List<Video> {
        val useKodik = preferences.getBoolean(PREF_USE_KODIK_KEY, PREF_USE_KODIK_DEFAULT)
        if (playerUrl.isNullOrEmpty() || !useKodik) {
            return emptyList()
        }

        val kodikPage = UrlUtils.fixUrl(playerUrl) ?: return emptyList()
        val headers = Headers.Builder()
        headers.add("Referer", "$baseUrl/")

        // Parse form parameters for video link request
        val page = client.newCall(GET(kodikPage, headers.build())).awaitSuccess().useAsJsoup()
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
                    set("User-Agent", "Mozilla/5.0 (Android)")
                }.build(),
            )
            .build()

        val kodikData = client.newCall(videoInfoRequest).awaitSuccess().parseAs<KodikData>()

        // Load js with encode algorithm and parse it
        val scriptUrl = page.selectFirst("script[src*=player_single]")?.attr("abs:src")
            ?: return emptyList()
        val jsScript = client.newCall(GET(scriptUrl)).awaitSuccess().bodyString()
        val atob = ATOB_REGEX.find(jsScript) ?: return emptyList()

        var encodeScript = ""
        val deque = ArrayDeque<Char>()
        deque.addFirst('(')
        for (i in atob.range.last..jsScript.length) {
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

        val videoList = qualityList.flatMap { quality ->
            val quickJs = QuickJs.create()
            val videoInfo = when (quality) {
                "360" -> kodikData.links.ugly[0].src
                "480" -> kodikData.links.bad[0].src
                "720" -> kodikData.links.good[0].src
                else -> return@flatMap emptyList()
            }

            val base64Url = quickJs.use {
                it.evaluate("t='$videoInfo'; $encodeScript")
            }.toString()

            val hlsUrl = Base64.decode(base64Url, Base64.DEFAULT).toString(Charsets.UTF_8)
            val playlistUrl = UrlUtils.fixUrl(hlsUrl) ?: return@flatMap emptyList()

            playlistUtils.extractFromHls(
                playlistUrl,

                videoNameGen = { "$teamName (${quality}p Kodik)" },
            )
        }

        return videoList
    }

    private fun animelibVideoLinks(videoInfo: VideoInfo, serverUrl: String): List<Video> {
        val subtitles = subtitleTracks(videoInfo, serverUrl)

        val useMaxQuality = preferences.getBoolean(
            PREF_USE_MAX_QUALITY_KEY,
            PREF_USE_MAX_QUALITY_DEFAULT,
        )
        val maxQuality = bestQuality(videoInfo)
        val qualityPreference = preferences.getStringSet(PREF_QUALITY_KEY, emptySet())

        val qualityItems = videoInfo.qualityItems()
        val candidates = qualityItems.ifEmpty {
            listOfNotNull(videoInfo.video)
        }
        val videoList = candidates.mapNotNull { item ->
            val qualityValue = qualityValue(item)
            if (useMaxQuality && maxQuality > 0 && qualityValue != maxQuality) {
                return@mapNotNull null
            } else if (!useMaxQuality && !qualityPreference.isNullOrEmpty() && qualityValue > 0) {
                if (!qualityPreference.contains(qualityValue.toString())) {
                    return@mapNotNull null
                }
            }

            val videoData = item.findMediaUrl()
                ?: videoInfo.video.findMediaUrl()
                ?: videoInfo.src.findMediaUrl()
                ?: videoInfo.href.findMediaUrl()
                ?: videoInfo.url.findMediaUrl()
                ?: videoInfo.hls.findMediaUrl()
                ?: videoInfo.stream.findMediaUrl()
                ?: return@mapNotNull null
            val url = normalizeStreamUrl(videoData, serverUrl) ?: return@mapNotNull null
            val quality = if (qualityValue > 0) " (${qualityValue}p)" else ""

            Video(
                url,
                "${videoInfo.teamDisplayName}$quality Animelib",
                url,
                headers = playerHeaders(),
                subtitleTracks = subtitles,
            )
        }

        return videoList
    }

    private fun bestQuality(videoInfo: VideoInfo): Int = if (videoInfo.player.equals("animelib", ignoreCase = true)) {
        videoInfo.qualityItems().maxOfOrNull(::qualityValue) ?: 0
    } else if (videoInfo.player.equals("kodik", ignoreCase = true)) {
        720
    } else {
        0
    }

    // =============================== Converters ===============================
    private fun convertStatus(status: Int): Int = when (status) {
        1 -> SAnime.ONGOING

        2 -> SAnime.COMPLETED

        4 -> SAnime.ON_HIATUS

        5 -> SAnime.CANCELLED

        else -> {
            SAnime.UNKNOWN
        }
    }

    // Extract plain text from summary which can be either a string or a structured JSON doc
    private fun extractTextFromSummary(element: JsonElement?): String? {
        if (element == null) return null

        val sb = StringBuilder()

        fun recurse(el: JsonElement) {
            when (el) {
                is JsonPrimitive -> {
                    // Append primitive content (string, number, etc.)
                    sb.append(el.content)
                }
                is JsonObject -> {
                    // If node has a 'text' field, append it
                    el["text"]?.let { textEl ->
                        if (textEl is JsonPrimitive) sb.append(textEl.content)
                    }
                    // Recurse into 'content' arrays or other fields that may contain text
                    el["content"]?.let { recurse(it) }
                }
                is JsonArray -> {
                    for (child in el) {
                        recurse(child)
                        // Separate block nodes with a space
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

    // Normalize cover URL which can be absolute, protocol-relative, or relative path
    private fun normalizeCoverUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()

        return UrlUtils.fixUrl(trimmed, "https://$defaultCoverDomain")
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
        val animeQuery = animeId.asString()?.let { "?anime_id=$it" }.orEmpty()
        setUrlWithoutDomain("api/episodes/$id$animeQuery")
        val episode = number.asString().orEmpty()
        name = "Сезон ${season.asString().orEmpty()} Серия $episode ${episodeName.orEmpty()}".trim()
        episode_number = episode.toFloatOrNull() ?: 0f
        date_upload = dateFormatter.tryParse(date.orEmpty())
    }
}
