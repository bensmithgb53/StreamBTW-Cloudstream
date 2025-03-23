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
        val response = app.get("$mainUrl/api/matches/all", headers = headers, timeout = 15)
        val text = if (response.headers["Content-Encoding"] == "gzip") {
            GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
        } else {
            response.text
        }
        val matches: List<APIMatch> = mapper.readValue(text)

        val currentTime = System.currentTimeMillis() / 1000
        val liveMatches = matches.filter { 
            val matchTime = it.date / 1000
            matchTime >= (currentTime - 3 * 60 * 60) // Matches within last 3 hours
        }
        
        if (liveMatches.isEmpty()) {
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
        return groupedMatches.map { (category, categoryMatches) ->
            val eventList = categoryMatches.mapNotNull { match ->
                val source = match.sources.firstOrNull() ?: return@mapNotNull null
                val posterUrl = match.poster?.let { "$mainUrl/api/images/proxy/$it.webp" }
                    ?: match.teams?.let { teams ->
                        teams.home?.badge?.let { homeBadge ->
                            teams.away?.badge?.let { awayBadge ->
                                "$posterBase/$homeBadge/$awayBadge.webp"
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
        val (matchId, sourceType, sourceId) = url.split("|").let { 
            if (it.size == 1) listOf(it[0], "alpha", it[0]) else it 
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
        val streams: List<Stream> = mapper.readValue(text)
        val stream = streams.firstOrNull() ?: throw ErrorLoadingException("No streams available")

        val embedUrl = "https://embedme.top/embed/$sourceType/$matchId/${stream.streamNo}"
        val fetchHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            "Referer" to embedUrl,
            "Accept" to "*/*",
            "Origin" to "https://embedme.top"
        )
        val fetchBody = """{"source":"$sourceType","id":"$matchId","streamNo":"${stream.streamNo}"}""".toRequestBody()
        val fetchResponse = app.post("https://embedme.top/fetch", headers = fetchHeaders, requestBody = fetchBody, timeout = 15)
        val encPath = fetchResponse.text
        val m3u8Url = "https://rr.vipstreams.in/$encPath"

        return newLiveStreamLoadResponse(
            name = "${stream.source} - ${if (stream.hd) "HD" else "SD"}",
            url = m3u8Url,
            dataUrl = m3u8Url
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
                isM3u8 = true,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
                    "Origin" to "https://embedme.top",
                    "Accept" to "*/*"
                )
            )
        )
        return true
    }

    private fun String.capitalize(): String {
        return replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
