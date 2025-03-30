package ben.smith53 // Assuming this is your package name as per your request

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
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
        "Referer" to "$mainUrl/",
        "Accept-Encoding" to "gzip, deflate, br, zstd",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
    )

    companion object {
        private const val TAG = "StreamedProvider"
        private const val posterBase = "$mainUrl/api/images/poster"
        private const val badgeBase = "$mainUrl/api/images/badge"
        private const val proxyBase = "$mainUrl/api/images/proxy"
        private const val fallbackPoster = "$mainUrl/api/images/poster/fallback.webp"
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
            val text = response.body.use { body ->
                if (response.headers["Content-Encoding"] == "gzip") {
                    GZIPInputStream(body.byteStream()).bufferedReader().use { it.readText() }
                } else {
                    body.string()
                }
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
                        !match.poster.isNullOrBlank() -> "$proxyBase/${match.poster.trim('/').removeSuffix(".webp")}.webp"
                        match.teams?.home?.badge?.isNotBlank() == true && match.teams.away?.badge?.isNotBlank() == true ->
                            "$posterBase/${match.teams.home.badge}/${match.teams.away.badge}.webp"
                        match.teams?.home?.badge?.isNotBlank() == true -> "$badgeBase/${match.teams.home.badge}.webp"
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
        Log.d(TAG, "Loading URL: $url")
        try {
            val parts = url.split("|")
            if (parts.size < 3) throw IllegalArgumentException("Invalid URL format: expected matchId|sourceType|sourceId")

            val matchId = parts[0].split("/").last()
            val sourceType = parts[1]
            val sourceId = parts[2]
            Log.d(TAG, "Parsed: matchId=$matchId, sourceType=$sourceType, sourceId=$sourceId")

            // Fetch stream metadata
            val streamUrl = "$mainUrl/api/stream/$sourceType/$sourceId"
            val streamHeaders = headers + mapOf("Referer" to "$mainUrl/")
            val streamResponse = app.get(streamUrl, headers = streamHeaders, timeout = 30)
            val streamText = streamResponse.body.use { body ->
                if (streamResponse.headers["Content-Encoding"] == "gzip") {
                    GZIPInputStream(body.byteStream()).bufferedReader().use { it.readText() }
                } else {
                    body.string()
                }
            }
            Log.d(TAG, "Stream API response: $streamText")

            val streams: List<Stream> = mapper.readValue(streamText)
            val stream = streams.firstOrNull() ?: throw Exception("No streams available")

            // Fetch embed page
            val embedUrl = "https://embedme.top/embed/$sourceType/$matchId/${stream.streamNo}"
            val embedHeaders = headers + mapOf("Referer" to "https://embedme.top/")
            val embedResponse = app.get(embedUrl, headers = embedHeaders, timeout = 30)
            val embedText = embedResponse.body.use { body ->
                if (embedResponse.headers["Content-Encoding"] == "gzip") {
                    GZIPInputStream(body.byteStream()).bufferedReader().use { it.readText() }
                } else {
                    body.string()
                }
            }
            Log.d(TAG, "Embed page snippet: ${embedText.take(500)}")

            // Extract 'ut' parameter
            val utRegex = Regex("ut=(\\d+)")
            val ut = utRegex.find(embedText)?.groupValues?.get(1) ?: (System.currentTimeMillis() / 1000).toString()
            Log.d(TAG, "UT: $ut")

            // Fetch M3U8 URL
            val fetchHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
                "Referer" to "https://embedme.top/",
                "Origin" to "https://embedme.top",
                "Content-Type" to "application/json",
                "Accept" to "*/*"
            )
            val fetchBody = """{"source":"$sourceType","id":"$matchId","streamNo":"${stream.streamNo}","ut":"$ut"}""".toRequestBody()
            val fetchResponse = app.post("https://embedme.top/fetch", headers = fetchHeaders, requestBody = fetchBody, timeout = 30)
            var m3u8Url = fetchResponse.text.trim()
            Log.d(TAG, "Fetch response: $m3u8Url")

            // Handle potential encrypted response (WASM hint)
            if (!m3u8Url.contains(".m3u8") && m3u8Url.isNotBlank()) {
                Log.w(TAG, "Fetch returned non-M3U8 data, falling back to embed page scraping")
                val m3u8Regex = Regex("https://[\\w.-]+/[\\w/-]+\\.m3u8\\?md5=[\\w-]+&expiry=\\d+")
                m3u8Url = m3u8Regex.find(embedText)?.value ?: "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
            } else if (m3u8Url.isBlank() || fetchResponse.code != 200) {
                Log.w(TAG, "Fetch failed (code=${fetchResponse.code}), scraping embed page")
                val m3u8Regex = Regex("https://[\\w.-]+/[\\w/-]+\\.m3u8\\?md5=[\\w-]+&expiry=\\d+")
                m3u8Url = m3u8Regex.find(embedText)?.value ?: "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
            }

            Log.d(TAG, "Final M3U8 URL: $m3u8Url")
            return newLiveStreamLoadResponse(
                "${stream.source} - ${if (stream.hd) "HD" else "SD"}",
                url,
                m3u8Url
            ) {
                this.apiName = this@StreamedProvider.name
                this.plot = "Live stream: ${stream.language}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Load failed: ${e.message}", e)
            return newLiveStreamLoadResponse("Error", url, "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8") {
                this.apiName = this@StreamedProvider.name
                this.plot = "Error: ${e.message}"
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
        if (data.contains("test-streams")) {
            Log.w(TAG, "Using fallback test stream")
        }

        callback(
            ExtractorLink(
                source = name,
                name = "Streamed Sports",
                url = data,
                referer = "https://embedme.top/",
                quality = Qualities.Unknown.value,
                isM3u8 = true,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
                    "Referer" to "https://embedme.top/"
                )
            )
        )
        return true
    }

    private fun String.capitalize(): String {
        return replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}