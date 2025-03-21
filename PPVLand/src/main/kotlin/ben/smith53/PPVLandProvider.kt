package ben.smith53

import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import java.util.zip.GZIPInputStream

class PPVLandProvider : MainAPI() {
    override var mainUrl = "https://ppv.land"
    override var name = "PPVLand"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"

    override val mainPage = mainPageOf(
        "streams" to "Live Streams"
    )

    private val mapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        try {
            val response = app.get("$mainUrl/api/streams").response
            val bodyBytes = response.body.bytes() // Read raw bytes once

            // Check if response is gzip-compressed
            val responseText = if (response.headers["content-encoding"]?.contains("gzip") == true) {
                GZIPInputStream(bodyBytes.inputStream()).bufferedReader().use { it.readText() }
            } else {
                bodyBytes.decodeToString()
            }

            Log.d("PPVLandProvider", "API Response: $responseText")
            Log.d("PPVLandProvider", "API Headers: ${response.headers}")

            // Parse JSON using Jackson (more robust than JSONObject)
            val data: Map<String, List<Map<String, Any>>> = mapper.readValue(responseText)
            val streamsArray = data["streams"] ?: throw Exception("No 'streams' key in response")

            val homePageList = streamsArray.map { category ->
                val categoryName = category["category"] as? String ?: "Unknown"
                val streams = category["streams"] as? List<Map<String, Any>> ?: emptyList()
                val items = streams.map { stream ->
                    val name = stream["name"] as? String ?: "Unnamed"
                    val id = stream["id"] as? String ?: ""
                    val poster = stream["poster"] as? String
                    TvSeriesSearchResponse(
                        name = name,
                        url = "$mainUrl/stream/$id",
                        apiName = this.name,
                        posterUrl = poster
                    )
                }
                HomePageList(categoryName, items)
            }

            return HomePageResponse(homePageList)
        } catch (e: Exception) {
            Log.e("PPVLandProvider", "Error in getMainPage: ${e.message}", e)
            throw ErrorLoadingException("Failed to parse API response: ${e.message}", e)
        }
    }
}
