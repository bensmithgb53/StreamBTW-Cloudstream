package ben.smith53.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class PPVLandExtractor : ExtractorApi() {
    override val name = "PPVLandExtractor"
    override val mainUrl = "https://ppv.land"
    override val requiresReferer = true

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"
    private val HEADERS = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Encoding" to "gzip, deflate, br, zstd",
        "Connection" to "keep-alive",
        "Accept-Language" to "en-US,en;q=0.5",
        "X-FS-Client" to "FS WebClient 1.0",
        "Cookie" to "cf_clearance=Spt9tCB2G5.prpsED77vIRRv_7DXvw__Jw_Esqm53yw-1742505249-1.2.1.1-VXaRZXapXOenQsbIVYelJXCR2YFju.WlikuWSiXF2DNtDyxt5gjuRRhQq6hznJq9xn11ZqLhHFH4QOaitqLCccDwUXy4T2hJwE9qQ7gxlychuZ8E1zpx_XF0eiriJjZ4sw2ORWwokajxGlnxMLnZVMUGXh9sPkOKGKKyldQaga9r8Xus9esujwBVbTRtv7fCAFrF5f5j18Y1A.Rv3zQ7dxmonhSWOsD4c.mUpqXXid7oUJaNPVPw0OZOtYv1CEAPbGDjr1tAkuSJg.ij.6695qjiZsAj8XipJLbXy5IjACJoGVq32ScAy4ABlsXSTLDAtmbtZLUcqiHzljQsxZmt9Ljb7jq0O_HDx8x2VQ83tvI"
    )

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            val apiUrl = if (url.startsWith("$mainUrl/api/streams/")) {
                url
            } else {
                val streamId = url.split("/").firstOrNull { it.matches(Regex("\\d+")) }
                    ?: throw Exception("No numeric stream ID found in URL: $url")
                "$mainUrl/api/streams/$streamId"
            }

            val response = app.get(apiUrl, headers = HEADERS, referer = referer ?: "$mainUrl/").text
            val mapper = jacksonObjectMapper()
            val jsonData = mapper.readValue<Map<String, Any>>(response)

            val data = jsonData["data"] as? Map<String, Any> ?: throw Exception("No 'data' field in JSON")
            val m3u8Url = data["m3u8"] as? String ?: throw Exception("No 'm3u8' URL found in JSON")

            return listOf(
                ExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8Url,
                    referer = "$mainUrl/",
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
        } catch (e: Exception) {
            return null
        }
    }
}
