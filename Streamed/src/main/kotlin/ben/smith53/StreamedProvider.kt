package ben.smith53

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareHttpClient
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.zip.GZIPInputStream

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://embedme.top"
    override var name = "Streamed Sports"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"
    override val hasMainPage = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val hasDownloadSupport = false
    override val instantLinkLoading = true

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        "Referer" to "https://embedme.top/",
        "Accept-Encoding" to "gzip, deflate, br"
    )

    companion object {
        private const val posterBase = "https://embedme.top/api/images/poster"
        private const val badgeBase = "https://embedme.top/api/images/badge"
        private val json = Json { ignoreUnknownKeys = true }
    }

    @Serializable
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
        @Serializable
        data class Teams(
            val home: Team? = null,
            val away: Team? = null
        )

        @Serializable
        data class Team(
            val name: String,
            val badge: String
        )

        @Serializable
        data class Source(
            val source: String,
            val id: String
        )
    }

    @Serializable
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
            val response = app.get("$mainUrl/api/matches/live", headers = headers, timeout = 15)
            val text = if (response.headers["Content-Encoding"] == "gzip") {
                GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
            } else {
                response.text
            }
            val matches = json.decodeFromString<List<APIMatch>>(text)

            val eventList = matches.map { match ->
                val posterUrl = match.poster?.let { "$mainUrl/api/images/proxy/$it.webp" }
                    ?: match.teams?.let { teams -> "${posterBase}/${teams.home?.badge}/${teams.away?.badge}.webp" }
                val homeBadge = match.teams?.home?.badge?.let { "$badgeBase/$it.webp" }
                LiveSearchResponse(
                    name = match.title,
                    url = match.sources.firstOrNull()?.let { source -> "${match.id}|${source.source}|${source.id}" } ?: match.id,
                    apiName = this.name,
                    posterUrl = posterUrl,
                    bannerUrl = homeBadge // Home badge as banner
                )
            }
            return listOf(HomePageList("Live Sports", eventList, isHorizontalImages = false))
        } catch (e: Exception) {
            return listOf(
                HomePageList(
                    "Error",
                    listOf(LiveSearchResponse("Failed to load: ${e.message}", mainUrl, this.name, null)),
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
        val stream = json.decodeFromString<Stream>(text)
        val embedResponse = app.get(stream.embedUrl, headers = headers, timeout = 15)
        val embedHtml = if (embedResponse.headers["Content-Encoding"] == "gzip") {
            GZIPInputStream(embedResponse.body.byteStream()).bufferedReader().use { it.readText() }
        } else {
            embedResponse.text
        }

        val m3u8Match = Regex("https?://rr\\.vipstreams\\.in[^\\s'\"]+\\.m3u8[^\\s'\"]*").find(embedHtml)
            ?: throw Exception("No M3U8 URL found")
        val m3u8Url = m3u8Match.value

        val client = CloudflareHttpClient()
        val m3u8Response = client.get(m3u8Url, headers = headers)
        val contentEncoding = m3u8Response.headers["Content-Encoding"]?.lowercase()
        val rawContent = m3u8Response.body.bytes()
        val m3u8Content = when {
            rawContent.startsWith("#EXTM3U".toByteArray()) -> String(rawContent)
            contentEncoding == "gzip" -> GZIPInputStream(rawContent.inputStream()).bufferedReader().use { it.readText() }
            contentEncoding == "br" -> {
                try {
                    String(org.brotli.dec.BrotliInputStream(rawContent.inputStream()).readBytes())
                } catch (e: Exception) {
                    if (rawContent.startsWith("#EXTM3U".toByteArray())) String(rawContent) else throw e
                }
            }
            else -> String(rawContent)
        }

        return LiveStreamLoadResponse(
            name = stream.source + " - " + (if (stream.hd) "HD" else "SD"),
            url = m3u8Url,
            apiName = this.name,
            dataUrl = m3u8Content,
            posterUrl = null
        )
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
}
