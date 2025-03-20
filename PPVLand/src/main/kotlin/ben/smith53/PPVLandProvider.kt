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
    override var lang = "en"  // English only
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
        val response = app.get(apiUrl, headers = mapOf("User-Agent" to userAgent)).text
        println("API Response: $response") // Debug logging
        
        val json = JSONObject(response)
        val streamsArray = json.getJSONArray("streams")
        
        val homePageLists = mutableListOf<HomePageList>()
        val categoryMap = mutableMapOf<String, MutableList<LiveSearchResponse>>()

        for (i in 0 until streamsArray.length()) {
            val categoryData = streamsArray.getJSONObject(i)
            val categoryName = categoryData.getString("category")
            val streams = categoryData.getJSONArray("streams")

            val categoryEvents = categoryMap.getOrPut(categoryName) { mutableListOf() }

            for (j in 0 until streams.length()) {
                val stream = streams.getJSONObject(j)
                val eventName = stream.getString("name")
                val eventLink = "$mainUrl/live/${stream.getString("uri_name")}"
                val poster = stream.getString("poster")

                if ("english" in eventLink.lowercase() && !poster.contains("data:image")) {
                    val event = LiveSearchResponse(
                        name = eventName,
                        url = eventLink,
                        apiName = this.name,
                        posterUrl = poster
                    )
                    categoryEvents.add(event)
                }
            }
        }

        // Convert category map to HomePageLists
        categoryMap.forEach { (name, events) ->
            if (events.isNotEmpty()) {
                homePageLists.add(
                    HomePageList(
                        name = name,
                        list = events,
                        isHorizontalImages = false
                    )
                )
            }
        }

        return homePageLists
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
        val response = app.get(url, headers = mapOf("User-Agent" to userAgent)).text
        val embedUrl = Regex("src=\"([^\"]+)\"").find(response)?.groupValues?.get(1)
            ?: throw Exception("Embed URL not found")

        return LiveStreamLoadResponse(
            name = url.substringAfterLast("/").replace("-", " ").capitalize(),
            url = url,
            apiName = this.name,
            dataUrl = embedUrl,
            posterUrl = posterUrl
        )
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
