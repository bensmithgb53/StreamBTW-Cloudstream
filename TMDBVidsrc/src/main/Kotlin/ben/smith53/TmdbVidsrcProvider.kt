package ben.smith53

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class TmdbVidsrcProvider : MainAPI() {
    override var mainUrl = "https://www.vidsrc.wtf"
    override var name = "TMDB + VidSrc"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true
    override val hasSearch = true
    private val tmdbApiUrl = "https://api.themoviedb.org/3"
    private val tmdbApiKey = "YOUR_TMDB_API_KEY" // Replace with your TMDB API key

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val categories = listOf(
            Pair("Popular Movies", "movie/popular"),
            Pair("Popular TV Shows", "tv/popular")
        )
        val homePageLists = categories.map { (name, endpoint) ->
            val response = app.get(
                "$tmdbApiUrl/$endpoint?api_key=$tmdbApiKey&page=$page"
            ).parsed<TmdbResponse>()
            val items = response.results.map { item ->
                newMovieSearchResponse(
                    name = item.title ?: item.name ?: "Unknown",
                    url = item.id.toString(),
                    type = if (endpoint.contains("movie")) TvType.Movie else TvType.TvSeries
                ) {
                    this.posterUrl = "https://image.tmdb.org/t/p/w500${item.poster_path}"
                }
            }
            HomePageList(name, items, isHorizontalImages = true)
        }
        return newHomePageResponse(homePageLists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "$tmdbApiUrl/search/multi?api_key=$tmdbApiKey&query=${query.urlEncode()}"
        ).parsed<TmdbResponse>()
        return response.results.mapNotNull { item ->
            if (item.media_type == "movie" || item.media_type == "tv") {
                newMovieSearchResponse(
                    name = item.title ?: item.name ?: "Unknown",
                    url = item.id.toString(),
                    type = if (item.media_type == "movie") TvType.Movie else TvType.TvSeries
                ) {
                    this.posterUrl = "https://image.tmdb.org/t/p/w500${item.poster_path}"
                }
            } else null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val tmdbId = url
        val isTvShow = url.contains("season") // Simplified check; refine if needed
        val endpoint = if (isTvShow) "tv/$tmdbId" else "movie/$tmdbId"
        val response = app.get(
            "$tmdbApiUrl/$endpoint?api_key=$tmdbApiKey"
        ).parsed<TmdbDetails>()

        return if (isTvShow) {
            val seasons = app.get(
                "$tmdbApiUrl/tv/$tmdbId?api_key=$tmdbApiKey"
            ).parsed<TmdbTvDetails>().seasons
            newTvSeriesLoadResponse(
                name = response.name ?: "Unknown",
                url = tmdbId,
                type = TvType.TvSeries,
                episodes = seasons.flatMap { season ->
                    val seasonResponse = app.get(
                        "$tmdbApiUrl/tv/$tmdbId/season/${season.season_number}?api_key=$tmdbApiKey"
                    ).parsed<TmdbSeason>()
                    seasonResponse.episodes.map { episode ->
                        newEpisode(
                            data = "$tmdbId|${season.season_number}|${episode.episode_number}"
                        ) {
                            this.name = episode.name
                            this.season = season.season_number
                            this.episode = episode.episode_number
                            this.posterUrl = "https://image.tmdb.org/t/p/w500${episode.still_path}"
                        }
                    }
                }
            ) {
                this.posterUrl = "https://image.tmdb.org/t/p/w500${response.poster_path}"
                this.plot = response.overview
                this.year = response.first_air_date?.substring(0, 4)?.toIntOrNull()
            }
        } else {
            newMovieLoadResponse(
                name = response.title ?: "Unknown",
                url = tmdbId,
                type = TvType.Movie,
                dataUrl = tmdbId
            ) {
                this.posterUrl = "https://image.tmdb.org/t/p/w500${response.poster_path}"
                this.plot = response.overview
                this.year = response.release_date?.substring(0, 4)?.toIntOrNull()
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (tmdbId, season, episode) = data.split("|").let {
            when (it.size) {
                3 -> Triple(it[0], it[1].toIntOrNull(), it[2].toIntOrNull())
                else -> Triple(it[0], null, null)
            }
        }
        val isTvShow = season != null && episode != null
        val apiVersions = listOf("2", "3", "4")

        coroutineScope {
            apiVersions.map { version ->
                async {
                    val apiUrl = if (isTvShow) {
                        "$mainUrl/api/$version/tv/?id=$tmdbId&s=$season&e=$episode"
                    } else {
                        "$mainUrl/api/$version/movie/?id=$tmdbId"
                    }
                    try {
                        val response = app.get(apiUrl).text
                        extractLinksFromHtml(response, version, apiUrl, isTvShow, subtitleCallback, callback)
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll()
        }

        return true
    }

    private suspend fun extractLinksFromHtml(
        html: String,
        version: String,
        referer: String,
        isTvShow: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = Jsoup.parse(html)

        // Extract iframe sources
        doc.select("iframe").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotEmpty() && iframeSrc.contains("http")) {
                newExtractorLink(
                    source = "$name (v$version)",
                    name = "$name (v$version - iframe)",
                    url = iframeSrc,
                    type = ExtractorLinkType.M3U8 // Assume M3U8; refine if needed
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }.let(callback)
                // Follow iframe for nested sources
                try {
                    val iframeResponse = app.get(iframeSrc).text
                    extractIframeSources(iframeResponse, version, iframeSrc, subtitleCallback, callback)
                } catch (e: Exception) {}
                loadExtractor(url = iframeSrc, referer = referer, subtitleCallback, callback)
            }
        }

        // Extract sources from <select id="languageSelect">
        doc.select("select#languageSelect option").forEach { option ->
            val sourceName = option.text().trim() // e.g., "vidlink.pro", "warezcdn (portuguese)"
            val sourceValue = option.attr("value") // e.g., "vidlink.pro"
            if (sourceValue.isNotEmpty()) {
                // Guess URL based on common patterns
                val possibleUrl = when {
                    sourceValue.startsWith("http") -> sourceValue
                    sourceValue.contains("vidsrc") -> "https://$sourceValue"
                    else -> "https://$sourceValue/embed/${if (isTvShow) "tv" else "movie"}?tmdb=$tmdbId" +
                            if (isTvShow) "&season=$season&episode=$episode" else ""
                }
                newExtractorLink(
                    source = "$name (v$version)",
                    name = "$name (v$version - $sourceName)",
                    url = possibleUrl,
                    type = ExtractorLinkType.M3U8 // Assume M3U8; refine if needed
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }.let(callback)
                loadExtractor(url = possibleUrl, referer = referer, subtitleCallback, callback)
            }
        }

        // Handle version 2 language options (English, Hindi)
        if (version == "2") {
            doc.select("a[data-lang]").forEach { langElement ->
                val lang = langElement.attr("data-lang") // e.g., "English"
                val langUrl = langElement.attr("href")
                if (langUrl.contains("http")) {
                    try {
                        val langResponse = app.get(langUrl).text
                        extractLinksFromHtml(langResponse, "2-$lang", langUrl, isTvShow, subtitleCallback, callback)
                    } catch (e: Exception) {}
                }
            }
        }

        // Extract subtitles from <track> tags
        doc.select("track").forEach { track ->
            val src = track.attr("src")
            if (src.isNotEmpty()) {
                subtitleCallback(SubtitleFile(track.attr("label") ?: "Unknown", src))
            }
        }
    }

    private suspend fun extractIframeSources(
        html: String,
        version: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = Jsoup.parse(html)

        // Extract nested iframes
        doc.select("iframe").forEach { nestedIframe ->
            val nestedSrc = nestedIframe.attr("src")
            if (nestedSrc.isNotEmpty() && nestedSrc.contains("http")) {
                newExtractorLink(
                    source = "$name (v$version)",
                    name = "$name (v$version - nested iframe)",
                    url = nestedSrc,
                    type = ExtractorLinkType.M3U8 // Assume M3U8; refine if needed
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }.let(callback)
                loadExtractor(url = nestedSrc, referer = referer, subtitleCallback, callback)
            }
        }

        // Extract sources from <select name="servers">
        doc.select("select[name=servers] option").forEach { option ->
            val serverName = option.text().trim() // e.g., "mixdrop.ag (476.8 MB)"
            val serverUrl = option.attr("value")
            if (serverUrl.isNotEmpty() && serverUrl.contains("http")) {
                newExtractorLink(
                    source = "$name (v$version)",
                    name = "$name (v$version - $serverName)",
                    url = serverUrl,
                    type = ExtractorLinkType.M3U8 // Assume M3U8; refine if needed
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }.let(callback)
                loadExtractor(url = serverUrl, referer = referer, subtitleCallback, callback)
            }
        }

        // Extract subtitles from nested <track> tags
        doc.select("track").forEach { track ->
            val src = track.attr("src")
            if (src.isNotEmpty()) {
                subtitleCallback(SubtitleFile(track.attr("label") ?: "Unknown", src))
            }
        }
    }
}

// TMDB API response data classes
data class TmdbResponse(
    val results: List<TmdbItem>
)

data class TmdbItem(
    val id: Int,
    val title: String?,
    val name: String?,
    val poster_path: String?,
    val media_type: String
)

data class TmdbDetails(
    val id: Int,
    val title: String?,
    val name: String?,
    val poster_path: String?,
    val overview: String?,
    val release_date: String?,
    val first_air_date: String?
)

data class TmdbTvDetails(
    val seasons: List<TmdbSeason>
)

data class TmdbSeason(
    val season_number: Int,
    val episodes: List<TmdbEpisode>
)

data class TmdbEpisode(
    val episode_number: Int,
    val name: String?,
    val still_path: String?
)
