package ben.smith53

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class PPVLandProvider : MainAPI() {
    override var mainUrl = "https://ppv.land"
    override var name = "PPV Land"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"

    private val interceptor = CloudflareKiller()

    private suspend fun fetchEvents(): List<Map<String, Any>> {
        val response = app.get("$mainUrl/api/streams", headers = mapOf("User-Agent" to userAgent)).text
        val mapper = jacksonObjectMapper()
        val jsonData = mapper.readValue<Map<String, Any>>(response)
        return jsonData["streams"] as List<Map<String, Any>>
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val streamsArray = fetchEvents()
        val homePageList = streamsArray.map { categoryData ->
            val categoryName = categoryData["category"] as String
            val streams = categoryData["streams"] as List<Map<String, Any>>

            val searchResponses = streams.mapNotNull { stream ->
                val posterUrl = stream["poster"] as String
                if ("data:image" in posterUrl) return@mapNotNull null

                newLiveSearchResponse(
                    name = stream["name"] as String,
                    url = "$mainUrl/live/${stream["uri_name"]}",
                    apiName = this.name,
                    type = TvType.Live,
                    posterUrl = posterUrl,
                    id = (stream["id"] as Number).toInt()
                )
            }
            HomePageList(categoryName, searchResponses)
        }
        return HomePageResponse(homePageList)
    }

    override suspend fun load(url: String): LoadResponse {
        val streamId = url.split("/").firstOrNull { it.matches(Regex("\\d+")) } 
            ?: throw Exception("No stream ID found in URL: $url")
        return LiveStreamLoadResponse(
            name = url.substringAfterLast("/").replace("-", " ").capitalize(),
            url = url,
            apiName = this.name,
            dataUrl = "$mainUrl/api/streams/$streamId", // Pass API URL directly
            posterUrl = null // Poster already set in search response
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadExtractor(data, mainUrl, subtitleCallback, callback)
        return true
    }
}
