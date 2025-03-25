package ben.smith53

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.zip.GZIPInputStream

data class Stream(
    val id: String,
    val streamNo: String,
    val hd: Boolean,
    val source: String
)

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live)

    private val mapper = ObjectMapper()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/category/live").document
        val items = doc.select("div.grid-item").mapNotNull { element ->
            val title = element.selectFirst("h3")?.text() ?: return@mapNotNull null
            val url = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            newLiveSearchResponse(
                name = title,
                url = url,
                type = TvType.Live
            )
        }
        return newHomePageResponse(listOf(HomePageList("Live Streams", items, isHorizontalImages = true)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?q=$query").document
        return doc.select("div.grid-item").mapNotNull { element ->
            val title = element.selectFirst("h3")?.text() ?: return@mapNotNull null
            val url = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            newLiveSearchResponse(
                name = title,
                url = url,
                type = TvType.Live
            )
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
            println("Stream not found for $streamUrl, status=${response.code}, using test stream")
            return newLiveStreamLoadResponse(
                "Stream Unavailable - $matchId",
                correctedUrl,
                "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
            ) {
                this.apiName = this@StreamedProvider.name
                this.plot = "The requested stream could not be found."
            }
        }

        val streams = try {
            mapper.readValue(text, mapper.typeFactory.constructCollectionType(List::class.java, Stream::class.java))
        } catch (e: Exception) {
            println("Failed to parse streams: ${e.message}")
            emptyList<Stream>()
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
                this.plot = "No streams were returned for this match."
            }
        }
        println("Selected stream: id=${stream.id}, streamNo=${stream.streamNo}, hd=${stream.hd}, source=${stream.source}")

        val proxyUrl = "https://streamed-proxy.onrender.com/get_m3u8?source=$sourceType&id=$matchId&streamNo=${stream.streamNo}"
        val proxyResponse = app.get(proxyUrl, timeout = 60)
        val proxyText = proxyResponse.text
        println("Proxy response from $proxyUrl: $proxyText")

        val m3u8Url = if (proxyResponse.isSuccessful) {
            try {
                val json = mapper.readValue(proxyText, Map::class.java)
                json["m3u8_url"]?.toString() ?: run {
                    println("No m3u8_url in proxy response, using test stream")
                    "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
                }
            } catch (e: Exception) {
                println("Failed to parse proxy response: ${e.message}, using test stream")
                "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
            }
        } else {
            println("Proxy request failed: status=${proxyResponse.code}, using test stream")
            "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        }

        println("Final M3U8 URL: $m3u8Url")
        return newLiveStreamLoadResponse(
            "${stream.source} - ${if (stream.hd) "HD" else "SD"}",
            m3u8Url,
            m3u8Url
        ) {
            this.apiName = this@StreamedProvider.name
            this.plot = if (m3u8Url.contains("test-streams")) "Proxy server failed; using test stream." else null
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
                name = this.name,
                url = data,
                referer = "",
                quality = -1, // Unknown quality
                isM3u8 = true
            )
        )
        return true
    }
}
