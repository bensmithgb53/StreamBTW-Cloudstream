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
        val response = app.get("$mainUrl/api/matches/all", headers = headers, timeout = 30)
        val text = if (response.headers["Content-Encoding"] == "gzip") {
            GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
        } else {
            response.text
        }
        println("Matches API response: $text")
        val matches: List<APIMatch> = mapper.readValue(text)
        val currentTime = System.currentTimeMillis() / 1000
        println("Current time: $currentTime, Filter threshold: ${currentTime - 24 * 60 * 60}")
        val liveMatches = matches.filter { it.date / 1000 >= (currentTime - 24 * 60 * 60) }

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
                    val cleanPoster = it.removeSuffix(".webp")
                    if (cleanPoster.startsWith("/")) "$mainUrl/api/images/proxy$cleanPoster.webp" 
                    else "$mainUrl/api/images/proxy/$cleanPoster.webp" 
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
        val correctedUrl = if (url.startsWith("$mainUrl/watch/")) {
            val parts = url.split("/")
            val matchId = parts[parts.indexOf("watch") + 1].split("-").last()
            val sourceType = parts[parts.size - 2]
            val sourceId = parts.last()
            "$matchId|$sourceType|$sourceId"
        } else {
            url
        }
        val parts = correctedUrl.split("|")
        val matchId = parts[0].split("/").last()
        val sourceType = if (parts.size > 1) parts[1] else "alpha"
        val sourceId = if (parts.size > 2) parts[2] else matchId

        println("Loading stream with corrected URL: $correctedUrl")
        println("Parsed: matchId=$matchId, sourceType=$sourceType, sourceId=$sourceId")

        val streamUrl = "$mainUrl/api/stream/$sourceType/$sourceId"
        val streamHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            "Referer" to "https://streamed.su/"
        )
        val response = app.get(streamUrl, headers = streamHeaders, timeout = 30)
        println("Stream API request: URL=$streamUrl, Headers=$streamHeaders, Status=${response.code}")
        val text = if (response.headers["Content-Encoding"] == "gzip") {
            GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
        } else {
            response.text
        }
        println("Stream API response for $streamUrl: $text")

        if (!response.isSuccessful || text.contains("Not Found")) {
            println("Stream not found for $streamUrl, status=${response.code}, attempting fallback with matchId")
            return newLiveStreamLoadResponse(
                "Stream Unavailable - $matchId",
                correctedUrl,
                "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
            ) {
                this.apiName = this@StreamedProvider.name
                this.plot = "The requested stream could not be found. Using a test stream instead."
            }
        }

        val streams: List<Stream> = try {
            mapper.readValue(text)
        } catch (e: Exception) {
            println("Failed to parse streams: ${e.message}")
            emptyList()
        }
        val stream = streams.firstOrNull()
        if (stream == null) {
            println("No streams available for $streamUrl")
            return newLiveStreamLoadResponse(
                "No Streams Available - $matchId",
                correctedUrl,
                "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
            ) {
                this.apiName = this@StreamedProvider.name
                this.plot = "No streams were returned for this match. Using a test stream instead."
            }
        }
        println("Selected stream: id=${stream.id}, streamNo=${stream.streamNo}, hd=${stream.hd}, source=${stream.source}")

        val fetchHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            "Referer" to "https://embedme.top/",
            "Content-Type" to "application/json",
            "Origin" to "https://embedme.top",
            "Accept" to "*/*"
        )
        val fetchBody = """{"source":"$sourceType","id":"$matchId","streamNo":"${stream.streamNo}"}""".toRequestBody()
        val fetchResponse = app.post("https://embedme.top/fetch", headers = fetchHeaders, requestBody = fetchBody, timeout = 30)
        val fetchText = fetchResponse.text
        println("Fetch response from https://embedme.top/fetch: $fetchText")

        val m3u8Url = if (fetchResponse.isSuccessful && fetchText.isNotBlank() && !fetchText.contains("Not Found")) {
            if (fetchText.contains(".m3u8")) {
                println("Fetch returned direct M3U8 URL: $fetchText")
                fetchText
            } else {
                println("Fetch returned encrypted data: $fetchText, scraping embed page")
                val embedUrl = "https://embedme.top/embed/$sourceType/$matchId/${stream.streamNo}"
                val embedHeaders = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
                    "Referer" to "https://embedme.top/",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
                )
                val embedResponse = app.get(embedUrl, headers = embedHeaders, timeout = 30)
                val embedText = if (embedResponse.headers["Content-Encoding"] == "gzip") {
                    GZIPInputStream(embedResponse.body.byteStream()).bufferedReader().use { it.readText() }
                } else {
                    embedResponse.text
                }
                println("Embed page response for $embedUrl: $embedText")

                val m3u8Regex = Regex("https://[\\w.-]+/[\\w/-]+\\.m3u8\\?md5=[\\w-]+&expiry=\\d+")
                val foundUrl = m3u8Regex.find(embedText)?.value ?: run {
                    val relativeM3u8Regex = Regex("/s/[\\w/-]+\\.m3u8\\?md5=[\\w-]+&expiry=\\d+")
                    relativeM3u8Regex.find(embedText)?.value?.let { relativeUrl ->
                        "https://rr.vipstreams.in$relativeUrl"
                    }
                }
                foundUrl?.let { url ->
                    println("Found M3U8 URL in embed page: $url")
                    url
                } ?: run {
                    println("No M3U8 URL found in embed page, falling back to test stream")
                    "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
                }
            }
        } else {
            println("Fetch failed or returned invalid data: status=${fetchResponse.code}, scraping embed page")
            val embedUrl = "https://embedme.top/embed/$sourceType/$matchId/${stream.streamNo}"
            val embedHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
                "Referer" to "https://embedme.top/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
            )
            val embedResponse = app.get(embedUrl, headers = embedHeaders, timeout = 30)
            val embedText = if (embedResponse.headers["Content-Encoding"] == "gzip") {
                GZIPInputStream(embedResponse.body.byteStream()).bufferedReader().use { it.readText() }
            } else {
                embedResponse.text
            }
            println("Embed page response for $embedUrl: $embedText")
            val m3u8Regex = Regex("https://[\\w.-]+/[\\w/-]+\\.m3u8\\?md5=[\\w-]+&expiry=\\d+")
            val foundUrl = m3u8Regex.find(embedText)?.value ?: run {
                val relativeM3u8Regex = Regex("/s/[\\w/-]+\\.m3u8\\?md5=[\\w-]+&expiry=\\d+")
                relativeM3u8Regex.find(embedText)?.value?.let { relativeUrl ->
                    "https://rr.vipstreams.in$relativeUrl"
                }
            }
            foundUrl?.let { url ->
                println("Found M3U8 URL in embed page: $url")
                url
            } ?: run {
                println("No M3U8 URL found, using test stream")
                "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
            }
        }

        println("Final M3U8 URL: $m3u8Url")
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
