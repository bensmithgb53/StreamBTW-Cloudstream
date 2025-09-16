package ben.smith53.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class PPVLandExtractor : ExtractorApi() {
    override val name = "PPVLandExtractor"
    override val mainUrl = "https://ppv.to"
    override val requiresReferer = true

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"
    private val HEADERS = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Encoding" to "gzip, deflate, br, zstd",
        "Connection" to "keep-alive",
        "Accept-Language" to "en-US,en;q=0.5",
        "X-FS-Client" to "FS WebClient 1.0",
        "X-CID" to "b127ebf6d409d51e7e1f1f2989081d687bb9c7a6589056efd2948810aaac19c4"
    )

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            val apiUrl = if (url.startsWith("$mainUrl/api/streams/")) {
                url
            } else {
                val streamId = url.split("/").firstOrNull { it.matches(Regex("\\d+")) } ?: throw Exception("No numeric stream ID found in URL: $url")
                "$mainUrl/api/streams/$streamId"
            }
            val response = app.get(apiUrl, headers = HEADERS, referer = referer ?: "$mainUrl/").text
            val mapper = jacksonObjectMapper()
            val jsonData = mapper.readValue<Map<String, Any>>(response)
            val data = jsonData["data"] as? Map<String, Any> ?: throw Exception("No \'data\' field in JSON")
            val m3u8Url = data["m3u8"] as? String ?: throw Exception("No \'m3u8\' URL found in JSON")

            return listOf(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            return null
        }
    }
}