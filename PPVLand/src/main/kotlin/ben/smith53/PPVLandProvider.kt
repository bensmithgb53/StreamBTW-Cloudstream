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
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONObject

class PPVLandProvider : MainAPI() {
    override var mainUrl = "https://ppv.land"
    override var name = "PPV Land"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"
    override val hasMainPage = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val hasDownloadSupport = false
    override val instantLinkLoading = true

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    companion object {
        private const val posterUrl = "https://ppv.land/assets/img/ppvland.png"
    }

    private suspend fun fetchEvents(): List<HomePageList> {
        val apiUrl = "$mainUrl/api/streams"
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Content-Type" to "application/json",
            "X-FS-Client" to "FS WebClient 1.0"
        )

        val response = app.get(apiUrl, headers = headers)
        println("Main API Status Code: ${response.code}")
        println("Main API Response Body: ${response.text}")

        if (response.code != 200) {
            println("API Error: Received status code ${response.code}")
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

        val json = JSONObject(response.text)
        val streamsArray = json.getJSONArray("streams")
        println("Number of categories: ${streamsArray.length()}")

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
                    val event = LiveSearchResponse(
                        name = eventName,
                        url = streamId,
                        apiName = this.name,
                        posterUrl = poster
                    )
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

        homePageLists.add(
            HomePageList(
                name = "Test Category",
                list = listOf(
                    LiveSearchResponse(
                        name = "Test Event",
                        url = "test123",
                        apiName = this.name,
                        posterUrl = posterUrl
                    )
                ),
                isHorizontalImages = false
            )
        )
        println("Total categories including test: ${homePageLists.size}")

        return homePageLists
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePageLists = fetchEvents()
        println("Returning ${homePageLists.size} categories to Cloudstream")
        return newHomePageResponse(homePageLists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val homePageLists = fetchEvents()
        return homePageLists.flatMap { it.list }.filter {
            query.lowercase().replace(" ", "") in it.name.lowercase().replace(" ", "")
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val apiUrl = "$mainUrl/api/streams/$url"
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "*/*",
            "Accept-Encoding" to "gzip, deflate, br, zstd",
            "Connection" to "keep-alive",
            "Accept-Language" to "en-US,en;q=0.5",
            "X-FS-Client" to "FS WebClient 1.0"
            // Add "Cookie" header if needed, e.g., "cf_clearance=..." from Python script
        )
        val response = app.get(apiUrl, headers = headers)
        println("Stream API Status Code: ${response.code}")
        println("Stream API Response Body: ${response.text}")

        if (response.code != 200) {
            throw Exception("Failed to load stream details: HTTP ${response.code}")
        }

        val json = JSONObject(response.text)
        if (!json.getBoolean("success")) {
            throw Exception("API Error: ${json.getString("error")}")
        }

        val data = json.getJSONObject("data")
        val m3u8Url = data.getString("m3u8")
        val streamName = data.optString("name", "Stream")

        return LiveStreamLoadResponse(
            name = streamName,
            url = m3u8Url,
            apiName = this.name,
            dataUrl = m3u8Url,
            posterUrl = posterUrl
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
                name = "PPVLand",
                url = data,
                referer = mainUrl,
                quality = -1,
                isM3u8 = true
            )
        )
        println("Provided m3u8 link: $data")
        return true
    }
}
