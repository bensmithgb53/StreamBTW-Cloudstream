package ben.smith53

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class PPVLandProvider : MainAPI() {
    override var mainUrl = "https://ppv.land"
    override var name = "PPV Land"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"
    private val interceptor = CloudflareKiller()

    private val HEADERS = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "*/*",
        "Accept-Encoding" to "gzip, deflate, br, zstd",
        "Connection" to "keep-alive",
        "Accept-Language" to "en-US,en;q=0.5",
        "X-FS-Client" to "FS WebClient 1.0",
        "Cookie" to "cf_clearance=Spt9tCB2G5.prpsED77vIRRv_7DXvw__Jw_Esqm53yw-1742505249-1.2.1.1-VXaRZXapXOenQsbIVYelJXCR2YFju.WlikuWSiXF2DNtDyxt5gjuRRhQq6hznJq9xn11ZqLhHFH4QOaitqLCccDwUXy4T2hJwE9qQ7gxlychuZ8E1zpx_XF0eiriJjZ4sw2ORWwokajxGlnxMLnZVMUGXh9sPkOKGKKyldQaga9r8Xus9esujwBVbTRtv7fCAFrF5f5j18Y1A.Rv3zQ7dxmonhSWOsD4c.mUpqXXid7oUJaNPVPw0OZOtYv1CEAPbGDjr1tAkuSJg.ij.6695qjiZsAj8XipJLbXy5IjACJoGVq32ScAy4ABlsXSTLDAtmbtZLUcqiHzljQsxZmt9Ljb7jq0O_HDx8x2VQ83tvI"
    )

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

                LiveSearchResponse(
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
            dataUrl = "$mainUrl/api/streams/$streamId",
            posterUrl = null
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val apiUrl = if (data.startsWith("$mainUrl/api/streams/")) {
                data
            } else {
                val streamId = data.split("/").firstOrNull { it.matches(Regex("\\d+")) }
                    ?: throw Exception("No numeric stream ID found in data: $data")
                "$mainUrl/api/streams/$streamId"
            }

            val response = app.get(apiUrl, headers = HEADERS, referer = "$mainUrl/").text
            val mapper = jacksonObjectMapper()
            val jsonData = mapper.readValue<Map<String, Any>>(response)

            val jsonDataMap = jsonData["data"] as? Map<String, Any> 
                ?: throw Exception("No 'data' field in JSON")
            val m3u8Url = jsonDataMap["m3u8"] as? String 
                ?: throw Exception("No 'm3u8' URL found in JSON")

            callback(
                ExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8Url,
                    referer = "$mainUrl/",
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
            return true
        } catch (e: Exception) {
            return false
        }
    }
}
