package ben.smith53

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.util.zip.GZIPInputStream

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su" // Changed to streamed.su
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
        "Accept-Encoding" to "gzip, deflate, br"
    )

    companion object {
        private const val posterBase = "https://streamed.su/api/images/poster" // Adjust if different
        private const val badgeBase = "https://streamed.su/api/images/badge"  // Adjust if different
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
            val badge: String
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
            // Filter for live matches (assuming no 'live' status field; adjust if present)
            val liveMatches = matches.filter { it.date <= System.currentTimeMillis() / 1000 } // Example filter
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

            val eventList = liveMatches.mapNotNull { match ->
                val source = match.sources.firstOrNull() ?: return@mapNotNull null
                println("Processing match: ${match.title} (ID: ${match.id}, Source: ${source.source}, Source ID: ${source.id})")
                val posterUrl = match.poster?.let { poster ->
                    "$mainUrl/api/images/proxy/$poster.webp"
                } ?: match.teams?.let { teams ->
                    "${posterBase}/${teams.home?.badge}/${teams.away?.badge}.webp"
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
            println("Returning ${eventList.size} events")
            return listOf(HomePageList("Live Sports", eventList, isHorizontalImages = false))
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
        val response = app.get(streamUrl, headers = headers, timeout = 15)
        val text = if (response.headers["Content-Encoding"] == "gzip") {
            GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
        } else {
            response.text
        }
        if (!response.isSuccessful || text.contains("Not Found")) {
            throw ErrorLoadingException("Stream not found for URL: $streamUrl")
        }
        val stream: Stream = mapper.readValue(text)
        val embedResponse = app.get(stream.embedUrl, headers = headers, timeout = 15)
        val embedHtml = if (embedResponse.headers["Content-Encoding"] == "gzip") {
            GZIPInputStream(embedResponse.body.byteStream()).bufferedReader().use { it.readText() }
        } else {
            embedResponse.text
        }

        val m3u8Match = Regex("https?://rr\\.vipstreams\\.in[^\\s'\"]+\\.m3u8[^\\s'\"]*").find(embedHtml)
            ?: throw ErrorLoadingException("No M3U8 URL found in embed: ${stream.embedUrl}")
        val m3u8Url = m3u8Match.value

        val m3u8Response = app.get(m3u8Url, headers = headers, timeout = 10)
        val contentEncoding = m3u8Response.headers["Content-Encoding"]?.lowercase()
        val rawContent = m3u8Response.body.bytes()
        val m3u8Content = when {
            rawContent.startsWith("#EXTM3U".toByteArray()) -> String(rawContent)
            contentEncoding == "gzip" -> GZIPInputStream(rawContent.inputStream()).bufferedReader().use { it.readText() }
            contentEncoding == "br" -> {
                if (rawContent.startsWith("#EXTM3U".toByteArray())) String(rawContent)
                else throw ErrorLoadingException("Brotli decoding not supported without explicit library")
            }
            else -> String(rawContent)
        }

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
                referer = "https://streamed.su/",
                quality = -1,
                isM3u8 = true
            )
        )
        return true
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        return this.take(prefix.size).toByteArray().contentEquals(prefix)
    }
}
