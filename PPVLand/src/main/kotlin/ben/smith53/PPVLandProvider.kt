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
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.util.zip.GZIPInputStream

class PPVLandProvider : MainAPI() {
    override var mainUrl = "https://ppv.to"
    override var name = "PPV Land"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"
    override val hasMainPage = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val hasDownloadSupport = false
    override val instantLinkLoading = true

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"

    private fun generateXCID(): String {
        return "b127ebf6d409d51e7e1f1f2989081d687bb9c7a6589056efd2948810aaac19c4"
    }

    private val headers: Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "*/*",
        "Connection" to "keep-alive",
        "Accept-Language" to "en-US,en;q=0.5",
        "X-FS-Client" to "FS WebClient 1.0",
        "X-CID" to generateXCID()
    )

    companion object {
        private const val posterUrl = "https://ppv.land/assets/img/ppvland.png"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePageLists = fetchEvents()
        println("Returning ${homePageLists.size} categories to Cloudstream")
        return newHomePageResponse(homePageLists)
    }

    private suspend fun fetchEvents(): List<HomePageList> {
        val apiUrl = "$mainUrl/api/streams"
        println("Fetching all streams from: $apiUrl")
        println("Request Headers: $headers")
        try {
            val response = app.get(apiUrl, headers = headers, timeout = 15)
            println("Main API Status Code: ${response.code}")
            // Decompress gzip response
            val decompressedText = if (response.headers["Content-Encoding"] == "gzip") {
                GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
            } else {
                response.text
            }
            println("Main API Response Body: $decompressedText")
            if (response.code != 200) {
                println("API Error: Received status code ${response.code}")
                return listOf(
                    HomePageList(
                        name = "API Error",
                        list = listOf(
                            newLiveSearchResponse(
                                name = "API Failed",
                                url = mainUrl,
                                type = TvType.Live
                            ) {
                                this.posterUrl = posterUrl
                                this.apiName = this@PPVLandProvider.name
                            }
                        ),
                        isHorizontalImages = false
                    )
                )
            }
            val json = JSONObject(decompressedText)
            val streamsArray = json.getJSONArray("streams")
            println("Found ${streamsArray.length()} categories")
            val categoryMap = mutableMapOf<String, MutableList<LiveSearchResponse>>()
            for (i in 0 until streamsArray.length()) {
                val categoryData = streamsArray.getJSONObject(i)
                val categoryName = categoryData.getString("category")
                val streams = categoryData.getJSONArray("streams")
                println("Processing Category: $categoryName with ${streams.length()} streams")
                val categoryEvents = categoryMap.getOrPut(categoryName) { mutableListOf() }
                for (j in 0 until streams.length()) {
                    val stream = streams.getJSONObject(j)
                    val eventName = stream.getString("name")
                    val streamId = stream.getString("id")
                    val poster = stream.getString("poster")
                    val startsAt = stream.getLong("starts_at")
                    println("Stream: $eventName, ID: $streamId, Starts At: $startsAt")
                    if (!poster.contains("data:image")) {
                        val event = newLiveSearchResponse(
                            name = eventName,
                            url = streamId,
                            type = TvType.Live
                        ) {
                            this.posterUrl = poster
                            this.apiName = this@PPVLandProvider.name
                        }
                        categoryEvents.add(event)
                    }
                }
            }
            val homePageLists = categoryMap.map { (name, events) ->
                println("Adding category: $name with ${events.size} events")
                HomePageList(
                    name = name,
                    list = events,
                    isHorizontalImages = false
                )
            }.toMutableList()
            println("Total categories: ${homePageLists.size}")
            return homePageLists
        } catch (e: Exception) {
            println("fetchEvents failed: ${e.message}")
            return listOf(
                HomePageList(
                    name = "Error",
                    list = listOf(
                        newLiveSearchResponse("Failed to load events: ${e.message}", mainUrl, TvType.Live) {
                            this.apiName = this@PPVLandProvider.name
                        }
                    ),
                    isHorizontalImages = false
                )
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val homePageLists = fetchEvents()
        return homePageLists.flatMap { it.list }.filter { query.lowercase().replace(" ", "") in it.name.lowercase().replace(" ", "") }
    }

    override suspend fun load(url: String): LoadResponse {
        val streamId = url.substringAfterLast("/").substringAfterLast(":")
        val apiUrl = "$mainUrl/api/streams/$streamId"
        println("Fetching m3u8 for stream ID $streamId: $apiUrl")
        println("Request Headers: $headers")
        val response = app.get(apiUrl, headers = headers, timeout = 15)
        println("Stream API Status Code: ${response.code}")
        // Decompress gzip response
        val decompressedText = if (response.headers["Content-Encoding"] == "gzip") {
            GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
        } else {
            response.text
        }
        println("Stream API Response Body: $decompressedText")
        if (response.code != 200) {
            throw Exception("Failed to load stream details: HTTP ${response.code}")
        }
        val json = JSONObject(decompressedText)
        if (!json.optBoolean("success", true)) {
            throw Exception("API Error: ${json.optString("error", "Unknown error")}")
        }
        val m3u8Url = json.optJSONObject("data")?.optString("m3u8") ?: json.optString("m3u8") ?: throw Exception("No m3u8 URL found in response")
        val streamName = json.optJSONObject("data")?.optString("name") ?: json.optString("name", "Stream $streamId")
        println("Found m3u8 URL: $m3u8Url")
        return newLiveStreamLoadResponse(
            name = streamName,
            url = m3u8Url,
            apiName = this.name,
            dataUrl = m3u8Url,
            type = TvType.Live
        ) {
            // contentRating is no longer a direct parameter, set within lambda if needed
            // this.contentRating = null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
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
        println("Provided m3u8 link: $data")
        return true
    }
}