package ben.smith53

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://raw.githubusercontent.com/bensmithgb53/streamed-links/refs/heads/main"
    override var name = "Streamed Links"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

    private val jsonUrl = "$mainUrl/streams.json"
    private val mapper = jacksonObjectMapper()

    // Data classes to match streams.json structure
    data class StreamData(
        val user_agent: String,
        val referer: String,
        val streams: List<Stream>
    )

    data class Stream(
        val id: String,
        val title: String,
        val sources: List<Source>
    )

    data class Source(
        val source: String,
        val m3u8: List<String>
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val jsonText = app.get(jsonUrl).text
        val streamData = mapper.readValue<StreamData>(jsonText)

        // Use a Set to track unique titles and avoid duplicates
        val seenTitles = mutableSetOf<String>()
        val streamList = mutableListOf<HomePageList>()

        streamData.streams.forEach { stream ->
            val normalizedTitle = stream.title.trim() // Normalize to avoid sneaky duplicates
            if (seenTitles.add(normalizedTitle)) { // Only add if we haven’t seen this title
                val streamItems = mutableListOf<SearchResponse>()
                stream.sources.forEachIndexed { j, source ->
                    source.m3u8.forEachIndexed { k, m3u8Url ->
                        streamItems.add(
                            newLiveSearchResponse(
                                name = "${stream.title} - ${source.source} #$k",
                                url = "${stream.id}|${source.source}|$k|${streamData.user_agent}|${streamData.referer}",
                                type = TvType.Live
                            )
                        )
                    }
                }
                if (streamItems.isNotEmpty()) {
                    streamList.add(HomePageList(normalizedTitle, streamItems))
                }
            }
        }

        return newHomePageResponse(streamList)
    }

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = url.replace(mainUrl, "").trimStart('/')
        val parts = cleanUrl.split("|", limit = 5)
        if (parts.size < 5) throw ErrorLoadingException("Invalid URL format: $url")
        val (id, sourceName, streamNo, userAgent, referer) = parts

        // Fetch and parse JSON
        val jsonText = app.get(jsonUrl).text
        println("DEBUG: Fetched JSON: $jsonText")
        println("DEBUG: Original URL: $url")
        println("DEBUG: Cleaned URL: $cleanUrl")
        println("DEBUG: Looking for ID: $id")
        val streamData = mapper.readValue<StreamData>(jsonText)

        // Find the stream and source
        val stream = streamData.streams.find { it.id == id } ?: throw ErrorLoadingException("Stream not found: $id")
        val source = stream.sources.find { it.source == sourceName } ?: throw ErrorLoadingException("Source not found: $sourceName")
        val m3u8Url = source.m3u8.getOrNull(streamNo.toInt()) ?: throw ErrorLoadingException("Invalid stream number: $streamNo")

        // Pass all M3U8 URLs for the source as a semicolon-separated string
        val allM3u8Urls = source.m3u8.joinToString(";") { "$it|$userAgent|$referer" }

        return newLiveStreamLoadResponse(
            name = "${stream.title} - $sourceName #$streamNo",
            dataUrl = allM3u8Urls, // Carry all URLs for loadLinks
            url = m3u8Url // Original URL for reference
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val m3u8Entries = data.split(";")
        m3u8Entries.forEachIndexed { index, entry ->
            val (m3u8Url, userAgent, referer) = entry.split("|", limit = 3)
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "$name - Source #$index",
                    url = m3u8Url,
                    referer = referer,
                    quality = -1,
                    isM3u8 = true,
                    headers = mapOf("User-Agent" to userAgent)
                )
            )
        }
        return true
    }
}