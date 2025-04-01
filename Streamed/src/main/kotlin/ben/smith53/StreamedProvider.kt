package ben.smith53

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONObject

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://raw.githubusercontent.com/bensmithgb53/streamed-links/refs/heads/main"
    override var name = "Streamed Links"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

    private val jsonUrl = "$mainUrl/streams.json"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val jsonText = app.get(jsonUrl).text
        val json = JSONObject(jsonText)

        val userAgent = json.getString("user_agent")
        val referer = json.getString("referer")
        val streamsArray = json.getJSONArray("streams")

        val streamList = mutableListOf<HomePageList>()

        for (i in 0 until streamsArray.length()) {
            val stream = streamsArray.getJSONObject(i)
            val id = stream.getString("id")
            val title = stream.getString("title")
            val sources = stream.getJSONArray("sources")

            val streamItems = mutableListOf<SearchResponse>()
            for (j in 0 until sources.length()) {
                val source = sources.getJSONObject(j)
                val sourceName = source.getString("source")
                val m3u8Array = source.getJSONArray("m3u8")

                for (k in 0 until m3u8Array.length()) {
                    val m3u8Url = m3u8Array.getString(k)
                    streamItems.add(
                        newLiveSearchResponse(
                            name = "$title - $sourceName #$k",
                            url = m3u8Url,
                            apiName = this.name,
                            type = TvType.Live
                        ) {
                            this.data = "$id|$sourceName|$k|$userAgent|$referer"
                        }
                    )
                }
            }
            if (streamItems.isNotEmpty()) {
                streamList.add(HomePageList(title, streamItems))
            }
        }

        return newHomePageResponse(streamList)
    }

    override suspend fun load(url: String): LoadResponse {
        val (id, sourceName, streamNo, userAgent, referer) = url.split("|", limit = 5)
        val jsonText = app.get(jsonUrl).text
        val json = JSONObject(jsonText)
        val streamsArray = json.getJSONArray("streams")

        var title = id
        var m3u8Url = url

        for (i in 0 until streamsArray.length()) {
            val stream = streamsArray.getJSONObject(i)
            if (stream.getString("id") == id) {
                title = stream.getString("title")
                val sources = stream.getJSONArray("sources")
                for (j in 0 until sources.length()) {
                    val source = sources.getJSONObject(j)
                    if (source.getString("source") == sourceName) {
                        val m3u8Array = source.getJSONArray("m3u8")
                        m3u8Url = m3u8Array.getString(streamNo.toInt())
                        break
                    }
                }
                break
            }
        }

        return newLiveStreamLoadResponse(
            name = "$title - $sourceName #$streamNo",
            url = m3u8Url,
            apiName = this.name,
            dataUrl = m3u8Url
        ) {
            this.addHeader("User-Agent", userAgent)
            this.addHeader("Referer", referer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (id, sourceName, streamNo, userAgent, referer) = data.split("|", limit = 5)
        val m3u8Url = data.split("|")[0]

        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = "$sourceName #$streamNo",
                url = m3u8Url,
                referer = referer,
                quality = -1, // Use -1 for unknown quality since Qualities enum is unresolved
                isM3u8 = true,
                headers = mapOf("User-Agent" to userAgent)
            )
        )
        return true
    }
}