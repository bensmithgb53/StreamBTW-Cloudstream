package ben.smith53

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.util.zip.GZIPInputStream

class PPVLandProvider : MainAPI() {
    override var mainUrl = "https://ppv.wtf"
    override var name = "PPV Land"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"
    override val hasMainPage = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val hasDownloadSupport = false
    override val instantLinkLoading = true

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"
    private val headers = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "*/*",
        "Connection" to "keep-alive",
        "Accept-Language" to "en-US,en;q=0.5",
        "X-FS-Client" to "FS WebClient 1.0"
        // Optionally include cf_clearance cookie if you require it
    )

    private fun normalizePoster(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var p = raw.replace("\\/", "/")
        if (p.startsWith("/")) p = "$mainUrl$p"
        return p
    }

    private suspend fun fetchEvents(): List<HomePageList> {
        val apiUrl = "$mainUrl/api/streams"
        try {
            val response = app.get(apiUrl, headers = headers, timeout = 15)
            val decompressedText = if (response.headers["Content-Encoding"] == "gzip") {
                GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
            } else {
                response.text
            }

            if (response.code != 200) {
                return listOf(
                    HomePageList(
                        name = "API Error",
                        list = listOf(
                            newLiveSearchResponse("API Failed", mainUrl) {
                                this.apiName = this@PPVLandProvider.name
                                this.posterUrl = null
                            }
                        ),
                        isHorizontalImages = false
                    )
                )
            }

            val json = JSONObject(decompressedText)
            val streamsArray = json.optJSONArray("streams") ?: return emptyList()
            val categoryMap = mutableMapOf<String, MutableList<com.lagradost.cloudstream3.LiveSearchResponse>>()

            for (i in 0 until streamsArray.length()) {
                val categoryData = streamsArray.getJSONObject(i)
                val categoryName = categoryData.optString("category", "Other")
                val streams = categoryData.optJSONArray("streams") ?: continue
                val categoryEvents = categoryMap.getOrPut(categoryName) { mutableListOf() }

                for (j in 0 until streams.length()) {
                    val stream = streams.getJSONObject(j)
                    val eventName = stream.optString("name", "Unknown")
                    val streamId = stream.optString("id", "")
                    val posterRaw = stream.optString("poster", "")
                    val poster = normalizePoster(posterRaw)

                    val event = newLiveSearchResponse(eventName, streamId) {
                        this.apiName = this@PPVLandProvider.name
                        this.posterUrl = poster
                    }
                    categoryEvents.add(event)
                }
            }

            return categoryMap.map { (name, events) ->
                HomePageList(name = name, list = events, isHorizontalImages = false)
            }
        } catch (e: Exception) {
            return listOf(
                HomePageList(
                    name = "Error",
                    list = listOf(
                        newLiveSearchResponse("Failed to load events: ${e.message}", mainUrl)
                    ),
                    isHorizontalImages = false
                )
            )
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePageLists = fetchEvents()
        return newHomePageResponse(homePageLists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val homePageLists = fetchEvents()
        return homePageLists.flatMap { it.list }.filter {
            query.lowercase().replace(" ", "") in it.name.lowercase().replace(" ", "")
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val streamId = url.substringAfterLast("/").substringAfterLast(":").trim()
        val apiUrl = "$mainUrl/api/streams/$streamId"

        val response = app.get(apiUrl, headers = headers, timeout = 15)
        val decompressedText = if (response.headers["Content-Encoding"] == "gzip") {
            GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
        } else {
            response.text
        }

        if (response.code != 200) {
            throw Exception("Failed to load stream details: HTTP ${response.code}")
        }

        val json = JSONObject(decompressedText)
        val dataObj = json.optJSONObject("data")
        val streamName = dataObj?.optString("name") ?: json.optString("name", "Stream $streamId")

        // Prefer direct m3u8 if present, else extract embed/source
        var m3u8Url: String? = dataObj?.optString("m3u8")?.takeIf { it.isNotBlank() }
            ?: json.optString("m3u8").takeIf { it.isNotBlank() }

        if (m3u8Url.isNullOrBlank()) {
            val sources = dataObj?.optJSONArray("sources")
            if (sources != null) {
                for (i in 0 until sources.length()) {
                    val s = sources.getJSONObject(i)
                    val sdata = s.optString("data", "")
                    val stype = s.optString("type", "")
                    if (sdata.contains(".m3u8")) {
                        m3u8Url = sdata
                        break
                    } else if (stype.equals("iframe", true) || sdata.contains("/embed/")) {
                        m3u8Url = sdata
                        break
                    }
                }
            }
        }

        if (m3u8Url.isNullOrBlank()) {
            throw Exception("No m3u8 or embed source found for stream $streamId")
        }

        m3u8Url = m3u8Url.replace("\\/", "/")

        return newLiveStreamLoadResponse(streamName, m3u8Url, m3u8Url) {
            this.contentRating = null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback.invoke(
            newExtractorLink(this.name, "PPVLand", url = data, type = ExtractorLinkType.M3U8) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}