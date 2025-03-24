package ben.smith53

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.util.zip.GZIPInputStream
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
        private const val posterBase = "https://streamed.su/api/images/poster"
        private const val badgeBase = "https://streamed.su/api/images/badge"
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
        val response = app.get("$mainUrl/api/matches/all", headers = headers, timeout = 15)
        val text = if (response.headers["Content-Encoding"] == "gzip") {
            GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
        } else {
            response.text
        }
        println("Matches API response: $text")
        val matches: List<APIMatch> = mapper.readValue(text)
        val currentTime = System.currentTimeMillis() / 1000
        val liveMatches = matches.filter { it.date / 1000 >= (currentTime - 3 * 60 * 60) }

        if (liveMatches.isEmpty()) {
            return listOf(
                HomePageList(
                    "No Live Matches",
                    listOf(newLiveSearchResponse("No live matches available", "$mainUrl|alpha|default", TvType.Live)),
                    isHorizontalImages = false
                )
            )
        }

        val groupedMatches = liveMatches.groupBy { it.category.capitalize() }
        return groupedMatches.map { (category, categoryMatches) ->
            val eventList = categoryMatches.mapNotNull { match ->
                val source = match.sources.firstOrNull() ?: return@mapNotNull null
                println("Match: ${match.title}, Source: ${source.source}, ID: ${source.id}")
                val posterUrl = match.poster?.let { 
                    if (it.startsWith("/")) "$mainUrl/api/images/proxy$it.webp" 
                    else "$mainUrl/api/images/proxy/$it.webp" 
                } ?: match.teams?.let { teams ->
                    teams.home?.badge?.let { homeBadge ->
                        teams.away?.badge?.let { awayBadge ->
                            "$posterBase/$homeBadge/$awayBadge.webp"
                        }
                    }
                }
                val homeBadge = match.teams?.home?.badge?.let { "$badgeBase/$it.webp" }
                newLiveSearchResponse(match.title, "${match.id}|${source.source}|${source.id}", TvType.Live) {
                    this.posterUrl = posterUrl ?: homeBadge
                }
            }
            HomePageList(category, eventList, isHorizontalImages = false)
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
        val parts = url.split("|")
        val matchId = parts[0].split("/").last()  // Extract just "wwe-network"
        val sourceType = if (parts.size > 1) parts[1] else "alpha"
        val sourceId = if (parts.size > 2) parts[2] else matchId
        
        // Fetch stream data
        val streamUrl = "$mainUrl/api/stream/$sourceType/$sourceId"
        val response = app.get(streamUrl, headers = headers, timeout = 15)
        val text = if (response.headers["Content-Encoding"] == "gzip") {
            GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
        } else {
            response.text
        }
        println("Stream API response for $streamUrl: $text")

        if (!response.isSuccessful || text.contains("Not Found")) {
            println("Stream not found for $streamUrl")
            return newLiveStreamLoadResponse(
                "Stream Unavailable",
                url,
                "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
            ) {
                this.apiName = this@StreamedProvider.name
                this.plot = "The requested stream could not be found. Using a test stream instead."
            }
        }

        val streams: List<Stream> = mapper.readValue(text)
        val stream = streams.firstOrNull() ?: return newLiveStreamLoadResponse(
            "No Streams Available",
            url,
            "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        ) {
            this.apiName = this@StreamedProvider.name
            this.plot = "No streams were returned for this match. Using a test stream instead."
        }

        // Fetch embed page to extract M3U8 URL
        val embedUrl = "https://embedme.top/embed/$sourceType/$matchId/${stream.streamNo}"
        val embedResponse = app.get(embedUrl, headers = headers, timeout = 15)
        val embedText = if (embedResponse.headers["Content-Encoding"] == "gzip") {
            GZIPInputStream(embedResponse.body.byteStream()).bufferedReader().use { it.readText() }
        } else {
            embedResponse.text
        }
        println("Embed page response: $embedText")

        // Extract M3U8 URL with regex
        val m3u8Regex = Regex("https://rr\\.vipstreams\\.in/[\\w/-]+/strm\\.m3u8\\?md5=[\\w-]+&expiry=\\d+")
        val m3u8Url = m3u8Regex.find(embedText)?.value ?: run {
            // Fallback to /fetch if regex fails
            val fetchHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
                "Referer" to embedUrl,
                "Content-Type" to "application/json",
                "Origin" to "https://embedme.top",
                "Accept" to "*/*"
            )
            val fetchBody = """{"source":"$sourceType","id":"$matchId","streamNo":"${stream.streamNo}"}""".toRequestBody()
            val fetchResponse = app.post("https://embedme.top/fetch", headers = fetchHeaders, requestBody = fetchBody, timeout = 15)
            val encPath = fetchResponse.text
            println("Encrypted path from embedme: $encPath")

            if (fetchResponse.isSuccessful && !encPath.contains("Not Found") && encPath.isNotBlank()) {
                // Note: This is encrypted and needs decryption; using test stream for now
                println("Encrypted path detected, decryption not implemented, falling back to test stream")
                "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
            } else {
                println("No M3U8 URL found, falling back to test stream")
                "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
            }
        }

        return newLiveStreamLoadResponse(
            "${stream.source} - ${if (stream.hd) "HD" else "SD"}",
            m3u8Url,
            m3u8Url
        ) {
            this.apiName = this@StreamedProvider.name
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
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
