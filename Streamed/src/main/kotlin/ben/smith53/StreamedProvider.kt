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
        "Accept-Encoding" to "gzip, deflate, br, zstd"
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
        try {
            println("Fetching matches from: $mainUrl/api/matches/all")
            val response = app.get("$mainUrl/api/matches/all", headers = headers, timeout = 15)
            println("API response code: ${response.code}")
            println("API response headers: ${response.headers}")
            val text = if (response.headers["Content-Encoding"] == "gzip") {
                GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
            } else {
                response.text
            }
            println("API response body: $text")
            val matches: List<APIMatch> = mapper.readValue(text)
            println("Parsed ${matches.size} matches")

            val currentTime = System.currentTimeMillis() / 1000
            val liveMatches = matches.filter { 
                val matchTime = it.date / 1000
                matchTime >= (currentTime - 3 * 60 * 60)
            }
            if (liveMatches.isEmpty()) {
                println("No live matches found in API response")
                return listOf(
                    HomePageList(
                        "No Live Matches",
                        listOf(newLiveSearchResponse(
                            name = "No live matches available",
                            url = "$mainUrl|alpha|default",
                            type = TvType.Live
                        )),
                        isHorizontalImages = false
                    )
                )
            }

            val groupedMatches = liveMatches.groupBy { it.category.capitalize() }
            val homePageLists = groupedMatches.map { (category, categoryMatches) ->
                val eventList = categoryMatches.mapNotNull { match ->
                    val source = match.sources.firstOrNull() ?: return@mapNotNull null
                    println("Processing match: ${match.title} (ID: ${match.id}, Source: ${source.source}, Source ID: ${source.id})")
                    val posterUrl = match.poster?.let { poster ->
                        "$mainUrl/api/images/proxy/$poster.webp"
                    } ?: match.teams?.let { teams ->
                        teams.home?.badge?.let { homeBadge ->
                            teams.away?.badge?.let { awayBadge ->
                                "${posterBase}/$homeBadge/$awayBadge.webp"
                            }
                        }
                    }
                    val homeBadge = match.teams?.home?.badge?.let { "$badgeBase/$it.webp" }
                    newLiveSearchResponse(
                        name = match.title,
                        url = "${match.id}|${source.source}|${source.id}",
                        type = TvType.Live
                    ) {
                        this.posterUrl = posterUrl ?: homeBadge
                    }
                }
                println("Category $category: Returning ${eventList.size} events")
                HomePageList(category, eventList, isHorizontalImages = false)
            }
            return homePageLists
        } catch (e: Exception) {
            println("Failed to fetch matches: ${e.message}")
            e.printStackTrace()
            return listOf(
                HomePageList(
                    "Error",
                    listOf(newLiveSearchResponse(
                        name = "Failed to load matches: ${e.message}",
                        url = "$mainUrl|alpha|error",
                        type = TvType.Live
                    )),
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
        println("Loading URL: $url")
        val (matchId, sourceType, sourceId) = url.split("|").let {
            when (it.size) {
                1 -> listOf(it[0], "alpha", it[0])
                else -> it
            }
        }
        val streamUrl = "$mainUrl/api/stream/$sourceType/$sourceId"
        println("Fetching stream from: $streamUrl")
        val response = app.get(streamUrl, headers = headers, timeout = 15)
        val text = if (response.headers["Content-Encoding"] == "gzip") {
            GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
        } else {
            response.text
        }
        println("Stream API response: $text")
        if (!response.isSuccessful || text.contains("Not Found")) {
            throw ErrorLoadingException("Stream not found for URL: $streamUrl")
        }
        val streams: List<Stream> = mapper.readValue(text)
        val stream = streams.firstOrNull() ?: throw ErrorLoadingException("No streams available for URL: $streamUrl")

        // Fetch encrypted M3U8 path
        val embedUrl = "https://embedme.top/embed/$sourceType/$matchId/${stream.streamNo}"
        val fetchHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            "Referer" to embedUrl,
            "Accept" to "*/*",
            "Accept-Encoding" to "gzip, deflate, br, zstd",
            "Content-Type" to "application/json",
            "Origin" to "https://embedme.top"
        )
        val fetchBody = """{"source":"$sourceType","id":"$matchId","streamNo":"${stream.streamNo}"}""".toRequestBody()
        val fetchResponse = app.post("https://embedme.top/fetch", headers = fetchHeaders, requestBody = fetchBody, timeout = 15)
        val encPath = if (fetchResponse.headers["Content-Encoding"] == "gzip") {
            GZIPInputStream(fetchResponse.body.byteStream()).bufferedReader().use { it.readText() }
        } else {
            fetchResponse.text
        }
        println("Encrypted M3U8 path: $encPath")

        // Construct M3U8 URL
        val m3u8Url = "https://rr.vipstreams.in/$encPath"
        println("Attempting M3U8 URL: $m3u8Url")

        // Fetch M3U8 with precise headers
        val m3u8Headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            "Referer" to "https://embedme.top/",
            "Accept" to "*/*",
            "Accept-Encoding" to "gzip, deflate, br, zstd",
            "Origin" to "https://embedme.top"
        )
        val m3u8Response = app.get(m3u8Url, headers = m3u8Headers, timeout = 10)
        val contentEncoding = m3u8Response.headers["Content-Encoding"]?.lowercase()
        val rawContent = m3u8Response.body.bytes()
        val m3u8Content = when {
            rawContent.startsWith("#EXTM3U".toByteArray()) -> String(rawContent)
            contentEncoding == "gzip" -> GZIPInputStream(rawContent.inputStream()).bufferedReader().use { it.readText() }
            contentEncoding == "zstd" -> String(rawContent) // Fallback
            else -> String(rawContent)
        }
        println("M3U8 Content: $m3u8Content")

        return newLiveStreamLoadResponse(
            name = "${stream.source} - ${if (stream.hd) "HD" else "SD"}",
            url = m3u8Url,
            dataUrl = m3u8Content
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
        callback(
            ExtractorLink(
                source = this.name,
                name = "Streamed Sports",
                url = data,
                referer = "https://embedme.top/",
                quality = -1,
                isM3u8 = true
            )
        )
        return true
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        return this.take(prefix.size).toByteArray().contentEquals(prefix)
    }

    private fun String.capitalize(): String {
        return replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
