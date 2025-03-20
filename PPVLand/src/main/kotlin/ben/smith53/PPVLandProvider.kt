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
import com.lagradost.cloudstream3.utils.loadExtractor
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
        println("API Status Code: ${response.code}")
        println("API Response Body: ${response.text}")

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
                val eventLink = "$mainUrl/live/${stream.getString("uri_name")}"
                val poster = stream.getString("poster")
                val iframe = stream.optString("iframe", null)
                val startsAt = stream.getLong("starts_at")
                println("Stream: $eventName, URL: $eventLink, Starts At: $startsAt, Iframe: $iframe")

                if ("english" in eventLink.lowercase() && !poster.contains("data:image")) {
                    val event = LiveSearchResponse(
                        name = eventName,
                        url = iframe ?: eventLink, // Use iframe if available, else fallback to eventLink
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

        // Add test category
        homePageLists.add(
            HomePageList(
                name = "Test Category",
                list = listOf(
                    LiveSearchResponse(
                        name = "Test Event",
                        url = "$mainUrl/live/test-event",
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
        // If URL is already an iframe, use it directly; otherwise, scrape
        return if (url.startsWith("https://www.vidembed.re")) {
            LiveStreamLoadResponse(
                name = "Stream", // Name will be set from search response
                url = url,
                apiName = this.name,
                dataUrl = url,
                posterUrl = posterUrl
            )
        } else {
            val response = app.get(url, headers = mapOf("User-Agent" to userAgent)).text
            val embedUrl = Regex("src=\"([^\"]+)\"").find(response)?.groupValues?.get(1)
                ?: throw виключення("Embed URL not found")
            LiveStreamLoadResponse(
                name = url.substringAfterLast("/").replace("-", " ").capitalize(),
                url = url,
                apiName = this.name,
                dataUrl = embedUrl,
                posterUrl = posterUrl
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return loadExtractor(data, mainUrl, subtitleCallback, callback)
    }
}
