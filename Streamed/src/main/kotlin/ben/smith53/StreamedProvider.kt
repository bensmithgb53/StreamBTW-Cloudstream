package ben.smith53

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.util.zip.GZIPInputStream

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
        private const val TEST_BUNNY_URL = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
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
        val streamNo: String,
        val hd: Boolean,
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
            println("Stream not found for $streamUrl, status=${response.code}")
            return newLiveStreamLoadResponse(
                "Stream Unavailable - $matchId",
                TEST_BUNNY_URL,
                TEST_BUNNY_URL
            ) {
                this.apiName = this@StreamedProvider.name
                this.plot = "The requested stream could not be found. Using test stream."
            }
        }

        val streams: List<Stream> = try {
            mapper.readValue(text, mapper.typeFactory.constructCollectionType(List::class.java, Stream::class.java))
        } catch (e: Exception) {
            println("Failed to parse streams: ${e.message}")
            emptyList()
        }
        val stream = streams.firstOrNull()
        if (stream == null) {
            println("No streams available for $streamUrl")
            return newLiveStreamLoadResponse(
                "No Streams Available - $matchId",
                TEST_BUNNY_URL,
                TEST_BUNNY_URL
            ) {
                this.apiName = this@StreamedProvider.name
                this.plot = "No streams were returned for this match. Using test stream."
            }
        }
        println("Selected stream: id=${stream.id}, streamNo=${stream.streamNo}, hd=${stream.hd}, source=${stream.source}")

        // Try proxy with retry
        var m3u8Urls: List<String> = emptyList()
        val proxyUrl = "https://streamed-proxy.onrender.com/get_m3u8?source=$sourceType&id=$matchId&streamNo=${stream.streamNo}"
        val proxyHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            "Referer" to "https://streamed.su/"
        )
        for (attempt in 1..2) {
            val proxyResponse = app.get(proxyUrl, headers = proxyHeaders, timeout = 60)
            println("Proxy request (attempt $attempt): URL=$proxyUrl, Headers=$proxyHeaders, Status=${proxyResponse.code}")
            val proxyText = proxyResponse.text
            println("Proxy response from $proxyUrl: $proxyText")

            if (proxyResponse.isSuccessful && proxyText.isNotBlank()) {
                try {
                    val json = mapper.readValue<Map<String, Any>>(proxyText)
                    m3u8Urls = when (val urlData = json["m3u8_url"]) {
                        is String -> listOf(urlData)
                        is List<*> -> urlData.filterIsInstance<String>()
                        else -> emptyList()
                    }
                    if (m3u8Urls.isNotEmpty()) {
                        println("Proxy returned valid M3U8 URLs: $m3u8Urls")
                        break
                    } else {
                        println("No valid m3u8_url in proxy response")
                    }
                } catch (e: Exception) {
                    println("Failed to parse proxy response: ${e.message}")
                }
            } else {
                println("Proxy request failed: status=${proxyResponse.code}, response=$proxyText")
            }
            if (attempt == 1) println("Retrying proxy request...")
        }

        // Fallback to embed page if proxy fails
        if (m3u8Urls.isEmpty()) {
            println("Proxy failed after retries, scraping embed page")
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
            val foundUrls = m3u8Regex.findAll(embedText).map { it.value }.toList()
            m3u8Urls = if (foundUrls.isNotEmpty()) {
                foundUrls
            } else {
                val relativeM3u8Regex = Regex("/s/[\\w/-]+\\.m3u8\\?md5=[\\w-]+&expiry=\\d+")
                relativeM3u8Regex.find(embedText)?.value?.let { relativeUrl ->
                    listOf("https://rr.vipstreams.in$relativeUrl")
                } ?: emptyList()
            }
            println("Scraped M3U8 URLs from embed page: $m3u8Urls")
        }

        // Use test bunny if no URLs found
        val finalUrls = if (m3u8Urls.isEmpty()) {
            println("No valid URLs found, using test bunny video")
            listOf(TEST_BUNNY_URL)
        } else {
            m3u8Urls
        }
        println("Final M3U8 URLs: $finalUrls")

        return newLiveStreamLoadResponse(
            "${stream.source} - ${if (stream.hd) "HD" else "SD"}",
            correctedUrl, // Pass original URL as dataUrl, we'll handle multiple links in loadLinks
            finalUrls.first() // Use first URL as primary dataUrl for display
        ) {
            this.apiName = this@StreamedProvider.name
            this.plot = if (finalUrls.contains(TEST_BUNNY_URL)) "Failed to retrieve stream URLs. Using test stream." else null
            this.data = finalUrls.joinToString("|") // Store all URLs in data field
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) {
            println("loadLinks: No URL provided, skipping callback")
            return false
        }
        println("loadLinks: Processing data: $data")
        val urls = data.split("|")
        urls.forEachIndexed { index, url ->
            if (url.isNotBlank()) {
                val linkName = if (urls.size > 1) "Stream ${index + 1}" else "Streamed Sports"
                println("loadLinks: Loading URL $index: $url")
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = linkName,
                        url = url,
                        referer = "https://streamed.su/",
                        quality = -1,
                        isM3u8 = true,
                        headers = headers
                    )
                )
            }
        }
        return urls.any { it.isNotBlank() }
    }

    private fun String.capitalize(): String {
        return replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
