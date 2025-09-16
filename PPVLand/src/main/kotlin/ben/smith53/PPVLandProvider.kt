package ben.smith53

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
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
        "X-FS-Client" to "FS WebClient 1.0",
        // Keep cookie if you want, but not strictly required for many endpoints:
        "Cookie" to "cf_clearance=..."
    )

    companion object {
        private const val posterUrl = "https://ppv.land/assets/img/ppvland.png"
    }

    private fun normalizePoster(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        // Unescape JSON-escaped slashes and fix relative paths
        var p = raw.replace("\\/", "/")
        if (p.startsWith("/")) {
            p = "$mainUrl$p"
        }
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
                            LiveSearchResponse(
                                name = "API Failed",
                                url = mainUrl,
                                apiName = this.name,
                                posterUrl = posterUrl
                            )
                        ),
                        isHorizontalImages = false
                    )
                )
            }

            val json = JSONObject(decompressedText)
            val streamsArray = json.optJSONArray("streams") ?: return emptyList()

            val categoryMap = mutableMapOf<String, MutableList<LiveSearchResponse>>()

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
                    val poster = normalizePoster(posterRaw) ?: posterUrl
                    val startsAt = stream.optLong("starts_at", 0L)

                    // Accept events even when poster is a data URI (some entries use inline images),
                    // but prefer external images if available.
                    val event = LiveSearchResponse(
                        name = eventName,
                        url = streamId, // keep the stream ID; extractor will resolve API/embed
                        apiName = this.name,
                        posterUrl = poster
                    )
                    categoryEvents.add(event)
                }
            }

            val homePageLists = categoryMap.map { (name, events) ->
                HomePageList(
                    name = name,
                    list = events,
                    isHorizontalImages = false
                )
            }.toMutableList()

            return homePageLists
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
        // url comes in as stream id or something similar; normalize
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
        val poster = dataObj?.optString("poster") ?: json.optString("poster", posterUrl)
        val description = dataObj?.optString("description") ?: json.optString("description", "No description available.")

        // The URL passed to loadLinks should be the API URL, not the m3u8.
        // The Extractor will then handle the API call to get the m3u8.
        val finalUrl = "$mainUrl/api/streams/$streamId"
        
        return newLiveStreamLoadResponse(
            streamName,
            finalUrl
        ) {
            // This is how you set the data and other properties correctly
            this.posterUrl = normalizePoster(poster)
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // The data passed here is the API URL from the load function
        // The extractor will now handle getting the final video link
        callback.invoke(
            newExtractorLink(
                this.name,
                "PPVLand",
                url = data,
                ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
