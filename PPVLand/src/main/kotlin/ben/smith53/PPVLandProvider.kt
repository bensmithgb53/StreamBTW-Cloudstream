package ben.smith53

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class PPVLandProvider : MainAPI() {
    override var mainUrl = "https://ppv.land"
    override var name = "PPVLand"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"
    override val hasMainPage = true
    override val hasSearch = false

    private val apiUrl = "$mainUrl/api/streams"
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0",
        "Accept" to "*/*",
        "Accept-Encoding" to "gzip, deflate, br, zstd",
        "Connection" to "keep-alive",
        "Accept-Language" to "en-US,en;q=0.5",
        "X-FS-Client" to "FS WebClient 1.0",
        "Cookie" to "cf_clearance=Spt9tCB2G5.prpsED77vIRRv_7DXvw__Jw_Esqm53yw-1742505249-1.2.1.1-VXaRZXapXOenQsbIVYelJXCR2YFju.WlikuWSiXF2DNtDyxt5gjuRRhQq6hznJ"
    )

    // Initialize CloudflareKiller
    private val cloudflareKiller by lazy { CloudflareKiller() }

    // Fetch the main page with all available streams
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Use CloudflareKiller for the HTTP request
        val response = app.get(
            apiUrl,
            headers = headers,
            timeout = 15,
            interceptor = cloudflareKiller
        ).text
        val json = JSONObject(response)
        val streamsArray = json.getJSONArray("streams")

        val homePageList = mutableListOf<HomePageList>()
        for (i in 0 until streamsArray.length()) {
            val categoryData = streamsArray.getJSONObject(i)
            val categoryName = categoryData.optString("category", "Unknown")
            val streams = categoryData.getJSONArray("streams")
            val streamList = mutableListOf<LiveSearchResponse>()

            for (j in 0 until streams.length()) {
                val stream = streams.getJSONObject(j)
                val poster = stream.optString("poster")
                if ("data:image" in poster) continue // Filter base64 images like in Python

                val streamName = stream.getString("name")
                val streamId = stream.getString("id")
                val uriName = stream.getString("uri_name")

                streamList.add(
                    LiveSearchResponse(
                        name = streamName,
                        url = "$mainUrl/api/streams/$streamId",
                        posterUrl = poster,
                        apiName = this.name
                    )
                )
            }
            if (streamList.isNotEmpty()) {
                homePageList.add(HomePageList(categoryName, streamList))
            }
        }
        return HomePageResponse(homePageList)
    }

    // Load the stream details and extract the m3u8 URL
    override suspend fun load(url: String): LoadResponse? {
        // Use CloudflareKiller for the HTTP request
        val response = app.get(
            url,
            headers = headers,
            timeout = 15,
            interceptor = cloudflareKiller
        ).text
        val json = JSONObject(response)
        val data = json.getJSONObject("data")
        val m3u8Url = data.optString("m3u8") ?: return null
        val streamId = url.substringAfterLast("/")

        val streamJson = app.get(
            "$mainUrl/api/streams/$streamId",
            headers = headers,
            interceptor = cloudflareKiller
        ).parsed<StreamJson>()
        return LiveStreamLoadResponse(
            name = streamJson.name,
            url = m3u8Url,
            apiName = this.name,
            dataUrl = m3u8Url,
            posterUrl = streamJson.poster
        )
    }

    // Extract the stream link
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
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                isM3u8 = true
            )
        )
        return true
    }

    // Helper data class for parsing stream JSON
    data class StreamJson(
        val name: String,
        val poster: String
    )
}
