package ben.smith53

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONObject
import android.util.Log

class PPVLandProvider : MainAPI() {
    override var mainUrl = "https://ppv.land"
    override var name = "PPVLand"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"
    override val hasMainPage = true

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

    private val cloudflareKiller by lazy { CloudflareKiller() }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        try {
            val response = app.get(
                apiUrl,
                headers = headers,
                timeout = 15,
                interceptor = cloudflareKiller
            )

            // Read the response body once and reuse it
            val responseText = response.text // This handles decompression automatically
            Log.d("PPVLandProvider", "API Response: $responseText")
            Log.d("PPVLandProvider", "API Headers: ${response.headers.toJson()}")

            if (responseText.isBlank()) {
                throw ErrorLoadingException("Empty response from API")
            }

            // Attempt to parse as JSON
            val json = try {
                JSONObject(responseText)
            } catch (e: Exception) {
                Log.e("PPVLandProvider", "Failed to parse JSON: $responseText", e)
                throw ErrorLoadingException("Failed to parse API response as JSON: ${e.message}")
            }

            val streamsArray = json.optJSONArray("streams")
                ?: throw ErrorLoadingException("No 'streams' array in API response")

            val homePageList = mutableListOf<HomePageList>()
            for (i in 0 until streamsArray.length()) {
                val categoryData = streamsArray.getJSONObject(i)
                val categoryName = categoryData.optString("category", "Unknown")
                val streams = categoryData.optJSONArray("streams") ?: continue
                val streamList = mutableListOf<SearchResponse>()

                for (j in 0 until streams.length()) {
                    val stream = streams.getJSONObject(j)
                    val poster = stream.optString("poster")
                    if ("data:image" in poster) continue

                    val streamName = stream.getString("name")
                    val streamId = stream.getString("id")

                    streamList.add(
                        newLiveSearchResponse(
                            name = streamName,
                            url = "$mainUrl/api/streams/$streamId",
                            type = TvType.Live
                        ) {
                            this.posterUrl = poster
                        }
                    )
                }
                if (streamList.isNotEmpty()) {
                    homePageList.add(HomePageList(categoryName, streamList))
                }
            }
            return newHomePageResponse(homePageList)
        } catch (e: Exception) {
            Log.e("PPVLandProvider", "Error in getMainPage: ${e.stackTraceToString()}", e)
            throw ErrorLoadingException("Failed to load main page: ${e.message}")
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val response = app.get(
                url,
                headers = headers,
                timeout = 15,
                interceptor = cloudflareKiller
            )

            val responseText = response.text
            Log.d("PPVLandProvider", "Stream Response: $responseText")
            if (responseText.isBlank()) return null

            val json = JSONObject(responseText)
            val data = json.getJSONObject("data")
            val m3u8Url = data.optString("m3u8") ?: return null
            val streamId = url.substringAfterLast("/")

            val streamJson = app.get(
                "$mainUrl/api/streams/$streamId",
                headers = headers,
                interceptor = cloudflareKiller
            ).parsed<StreamJson>()

            return newLiveStreamLoadResponse(
                name = streamJson.name,
                url = m3u8Url,
                dataUrl = m3u8Url
            ) {
                this.posterUrl = streamJson.poster
            }
        } catch (e: Exception) {
            Log.e("PPVLandProvider", "Error loading stream: ${e.stackTraceToString()}", e)
            return null
        }
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
                name = this.name,
                url = data,
                referer = mainUrl,
                quality = -1,
                isM3u8 = true
            )
        )
        return true
    }

    data class StreamJson(
        val name: String,
        val poster: String
    )
}
