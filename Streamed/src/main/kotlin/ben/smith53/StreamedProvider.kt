package ben.smith53

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.util.zip.GZIPInputStream
import android.util.Log
import okhttp3.RequestBody.Companion.toRequestBody

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed Sports"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"
    override val hasMainPage = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val hasDownloadSupport = false
    override val instantLinkLoading = true

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        "Referer" to "https://streamed.su/",
        "Accept-Encoding" to "gzip, deflate, br, zstd",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
    )

    companion object {
        private const val TAG = "StreamedProvider"
        private const val posterBase = "https://streamed.su/api/images/poster"
        private const val badgeBase = "https://streamed.su/api/images/badge"
        private const val proxyBase = "https://streamed.su/api/images/proxy"
        private const val fallbackPoster = "https://streamed.su/api/images/poster/fallback.webp"
        private val mapper = jacksonObjectMapper()
    }

    data class APIMatch(
        val id: String,
        val title: String,
        val category: String,
        val date: Long,
        val poster: String? = null,
        val popular: Boolean,
        val teams: Teams? = null,
        val sources: List<Source>
    ) {
        data class Teams(
            val home: Team? = null,
            val away: Team? = null
        )
        data class Team(
            val name: String,
            val badge: String? = null
        )
        data class Source(
            val source: String,
            val id: String
        )
    }

    data class Stream(
        val id: String,
        val streamNo: Int,
        val language: String,
        val hd: Boolean,
        val embedUrl: String,
        val source: String
    )

    private suspend fun fetchLiveMatches(): List<HomePageList> {
        try {
            val response = app.get("$mainUrl/api/matches/all", headers = headers, timeout = 30)
            val text = if (response.headers["Content-Encoding"] == "gzip") {
                GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
            } else {
                response.text
            }
            Log.d(TAG, "Matches API response: $text")
            val matches: List<APIMatch> = mapper.readValue(text)
            val currentTime = System.currentTimeMillis() / 1000
            val liveMatches = matches.filter { it.date / 1000 >= (currentTime - 24 * 60 * 60) }

            if (liveMatches.isEmpty()) {
                Log.w(TAG, "No live matches found")
                return listOf(
                    HomePageList(
                        "No Live Matches",
                        listOf(newLiveSearchResponse("No live matches available", "$mainUrl/no-matches", TvType.Live)),
                        isHorizontalImages = false
                    )
                )
            }

            val groupedMatches = liveMatches.groupBy { it.category.capitalize() }
            return groupedMatches.map { (category, categoryMatches) ->
                val eventList = categoryMatches.mapNotNull { match ->
                    val source = match.sources.firstOrNull() ?: run {
                        Log.w(TAG, "No sources for match: ${match.title}")
                        return@mapNotNull null
                    }
                    Log.d(TAG, "Match: ${match.title}, Source: ${source.source}, ID: ${source.id}")

                    val posterUrl = when {
                        match.poster?.isNotBlank() == true -> {
                            val cleanPoster = match.poster.trim('/').removeSuffix(".webp")
                            "$proxyBase/$cleanPoster.webp"
                        }
                        match.teams?.home?.badge?.isNotBlank() == true && match.teams.away?.badge?.isNotBlank() == true -> {
                            "$posterBase/${match.teams.home.badge}/${match.teams.away.badge}.webp"
                        }
                        match.teams?.home?.badge?.isNotBlank() == true -> {
                            "$badgeBase/${match.teams.home.badge}.webp"
                        }
                        else -> fallbackPoster
                    }
                    Log.d(TAG, "Poster URL for ${match.title}: $posterUrl")

                    newLiveSearchResponse(match.title, "${match.id}|${source.source}|${source.id}", TvType.Live) {
                        this.posterUrl = posterUrl
                    }
                }
                HomePageList(category, eventList, isHorizontalImages = false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching live matches: ${e.message}", e)
            return listOf(
                HomePageList(
                    "Error",
                    listOf(newLiveSearchResponse("Failed to load matches", "$mainUrl/error", TvType.Live)),
                    isHorizontalImages = false
                )
            )
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return newHomePageResponse(fetchLiveMatches())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return fetchLiveMatches().flatMap { it.list }.filter {
            query.lowercase().replace(" ", "") in it.name.lowercase().replace(" ", "")
        }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "Starting load for URL: $url")
        try {
            val parts = url.split("|")
            if (parts.size < 3) {
                Log.w(TAG, "Invalid URL format: $url, expected matchId|sourceType|sourceId")
                return newLiveStreamLoadResponse("Invalid URL", url, "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8") {
                    this.apiName = this@StreamedProvider.name
                    this.plot = "Invalid stream URL format."
                }
            }
            val matchId = parts[0].split("/").last()
            val sourceType = parts[1]
            val sourceId = parts[2]
            Log.d(TAG, "Parsed: matchId=$matchId, sourceType=$sourceType, sourceId=$sourceId")

            val streamUrl = "$mainUrl/api/stream/$sourceType/$sourceId"
            val streamHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
                "Referer" to "https://streamed.su/"
            )
            val response = app.get(streamUrl, headers = streamHeaders, timeout = 30)
            Log.d(TAG, "Stream API request: URL=$streamUrl, Status=${response.code}")
            val text = if (response.headers["Content-Encoding"] == "gzip") {
                GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
            } else {
                response.text
            }
            Log.d(TAG, "Stream API response: $text")

            if (!response.isSuccessful || text.contains("Not Found")) {
                Log.w(TAG, "Stream API failed, falling back to test stream")
                return newLiveStreamLoadResponse("Stream Unavailable - $matchId", url, "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8") {
                    this.apiName = this@StreamedProvider.name
                    this.plot = "The requested stream could not be found."
                }
            }

            val streams: List<Stream> = try {
                mapper.readValue(text)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse streams: ${e.message}", e)
                emptyList()
            }
            val stream = streams.firstOrNull()
            if (stream == null) {
                Log.w(TAG, "No streams available, falling back to test stream")
                return newLiveStreamLoadResponse("No Streams Available - $matchId", url, "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8") {
                    this.apiName = this@StreamedProvider.name
                    this.plot = "No streams were returned for this match."
                }
            }
            Log.d(TAG, "Selected stream: id=${stream.id}, streamNo=${stream.streamNo}, hd=${stream.hd}, source=${stream.source}")

            val embedUrl = "https://embedme.top/embed/$sourceType/$matchId/${stream.streamNo}"
            val embedHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
                "Referer" to "https://streamed.su/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
            )
            val embedResponse = app.get(embedUrl, headers = embedHeaders, timeout = 30)
            val embedText = if (embedResponse.headers["Content-Encoding"] == "gzip") {
                GZIPInputStream(embedResponse.body.byteStream()).bufferedReader().use { it.readText() }
            } else {
                embedResponse.text
            }
            Log.d(TAG, "Embed page response: $embedText")

            val utRegex = Regex("ut=(\\d+)")
            val ut = utRegex.find(embedText)?.groupValues?.get(1) ?: run {
                Log.w(TAG, "UT not found in embed page, defaulting to current timestamp")
                (System.currentTimeMillis() / 1000).toString()
            }
            Log.d(TAG, "Extracted ut parameter: $ut")

            val fetchHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
                "Referer" to "https://embedme.top/",
                "Content-Type" to "application/json",
                "Origin" to "https://embedme.top",
                "Accept" to "*/*"
            )
            val fetchBody = """{"source":"$sourceType","id":"$matchId","streamNo":"${stream.streamNo}","ut":"$ut"}""".toRequestBody()
            val fetchResponse = app.post("https://embedme.top/fetch", headers = fetchHeaders, requestBody = fetchBody, timeout = 30)
            val fetchText = fetchResponse.text
            Log.d(TAG, "Fetch response: $fetchText")

            val m3u8Url = if (fetchResponse.isSuccessful && fetchText.isNotBlank() && !fetchText.contains("Not Found")) {
                if (fetchText.contains(".m3u8")) {
                    Log.d(TAG, "Fetch returned direct M3U8 URL: $fetchText")
                    fetchText
                } else {
                    Log.w(TAG, "Fetch returned encrypted data, scraping embed page as fallback")
                    val m3u8Regex = Regex("https://[\\w.-]+/[\\w/-]+\\.m3u8\\?md5=[\\w-]+&expiry=\\d+")
                    val foundUrl = m3u8Regex.find(embedText)?.value
                    foundUrl ?: run {
                        Log.w(TAG, "No M3U8 found in embed page, using test stream")
                        "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
                    }
                }
            } else {
                Log.w(TAG, "Fetch failed (status=${fetchResponse.code}), scraping embed page")
                val m3u8Regex = Regex("https://[\\w.-]+/[\\w/-]+\\.m3u8\\?md5=[\\w-]+&expiry=\\d+")
                val foundUrl = m3u8Regex.find(embedText)?.value
                foundUrl ?: run {
                    Log.w(TAG, "No M3U8 found in embed page, using test stream")
                    "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
                }
            }
            Log.d(TAG, "Final M3U8 URL: $m3u8Url")

            return newLiveStreamLoadResponse("${stream.source} - ${if (stream.hd) "HD" else "SD"}", m3u8Url, m3u8Url) {
                this.apiName = this@StreamedProvider.name
            }
        } catch (e: Exception) {
            Log.e(TAG, "Load failed: ${e.message}", e)
            return newLiveStreamLoadResponse("Error - $url", url, "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8") {
                this.apiName = this@StreamedProvider.name
                this.plot = "Failed to load stream: ${e.message}"
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "Loading links for: $data")
        val streamHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            "Referer" to "https://embedme.top/",
            "Origin" to "https://embedme.top",
            "Accept" to "*/*"
        )

        callback(
            ExtractorLink(
                source = this.name,
                name = "Streamed Sports",
                url = data,
                referer = "https://embedme.top/",
                quality = -1,
                isM3u8 = true,
                headers = streamHeaders
            )
        )
        return true
    }

    private fun String.capitalize(): String {
        return replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}