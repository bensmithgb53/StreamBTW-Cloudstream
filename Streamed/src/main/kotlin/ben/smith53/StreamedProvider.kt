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

        // Group streams by title to avoid duplicates
        val groupedStreams = streamData.streams.groupBy { it.title }
        val streamList = mutableListOf<HomePageList>()

        groupedStreams.forEach { (title, streams) ->
            val streamItems = mutableListOf<SearchResponse>()
            streams.forEach { stream ->
                stream.sources.forEachIndexed { j, source ->
                    source.m3u8.forEachIndexed { k, m3u8Url ->
                        streamItems.add(
                            newLiveSearchResponse(
                                name = "$title - ${source.source} #$k",
                                url = "${stream.id}|${source.source}|$k|${streamData.user_agent}|${streamData.referer}",
                                type = TvType.Live
                            )
                        )
                    }
                }
            }
            if (streamItems.isNotEmpty()) {
                streamList.add(HomePageList(title, streamItems))
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
        val streamData = mapper.readValue<StreamData>(jsonText)

        // Find the stream and source
        val stream = streamData.streams.find { it.id == id } ?: throw ErrorLoadingException("Stream not found: $id")
        val source = stream.sources.find { it.source == sourceName } ?: throw ErrorLoadingException("Source not found: $sourceName")
        val selectedM3u8 = source.m3u8.getOrNull(streamNo.toInt()) ?: throw ErrorLoadingException("Invalid stream number: $streamNo")

        // Pack all M3U8 URLs for the source into dataUrl
        val allM3u8Urls = source.m3u8.joinToString(separator = ";") { "$it|$userAgent|$referer" }

        return newLiveStreamLoadResponse(
            name = "${stream.title} - $sourceName #$streamNo",
            dataUrl = allM3u8Urls,
            url = selectedM3u8
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
                    name = "$name - ${m3u8Entries.size} Source${if (m3u8Entries.size > 1) " #$index" else ""}",
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