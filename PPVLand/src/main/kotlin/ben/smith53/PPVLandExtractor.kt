package ben.smith53.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.json.JSONObject

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
        "X-FS-Client" to "FS WebClient 1.0"
    )

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            // Accept either a direct stream API URL or a numeric id
            val apiUrl = if (url.startsWith("$mainUrl/api/streams/")) {
                url
            } else {
                val streamId = url.split("/").firstOrNull { it.matches(Regex("\\d+")) }
                    ?: url // fallback: maybe user passed full url
                "$mainUrl/api/streams/$streamId"
            }

            val responseText = app.get(apiUrl, headers = HEADERS, referer = referer ?: "$mainUrl/").text
            val mapper = jacksonObjectMapper()

            // try parse as JSON map
            val jsonData: Map<String, Any> = try {
                mapper.readValue(responseText)
            } catch (e: Exception) {
                // maybe the API returns a plain JSON object string - try org.json
                val jo = JSONObject(responseText)
                mapOf("data" to jo.opt("data"))
            }

            val data = if (jsonData.containsKey("data") && jsonData["data"] is Map<*, *>) {
                jsonData["data"] as Map<String, Any>
            } else {
                // try to handle when "data" is nested differently
                (jsonData["data"] as? Map<String, Any>) ?: emptyMap()
            }

            // First, try straightforward m3u8 in data.m3u8
            val m3u8Candidate = (data["m3u8"] as? String)?.takeIf { it.contains(".m3u8") }

            if (!m3u8Candidate.isNullOrBlank()) {
                return listOf(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = m3u8Candidate,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }

            // Otherwise follow 'sources' array
            val sources = data["sources"]
            if (sources is List<*>) {
                for (s in sources) {
                    if (s is Map<*, *>) {
                        val stype = (s["type"] as? String) ?: ""
                        val sdata = (s["data"] as? String) ?: (s["url"] as? String) ?: ""
                        if (stype.equals("iframe", ignoreCase = true) || stype.equals("embed", ignoreCase = true)) {
                            if (sdata.isNotBlank()) {
                                try {
                                    val embedResp = app.get(sdata, headers = HEADERS, referer = apiUrl)
                                    val embedText = embedResp.text
                                    // extract first .m3u8
                                    val regex = Regex("""https?:\/\/[^\s'"]+\.m3u8[^\s'"]*""")
                                    val found = regex.find(embedText)
                                    if (found != null) {
                                        val m3u8 = found.value
                                        return listOf(
                                            newExtractorLink(
                                                source = this.name,
                                                name = this.name,
                                                url = m3u8,
                                                type = ExtractorLinkType.M3U8
                                            ) {
                                                this.referer = sdata
                                                this.quality = Qualities.Unknown.value
                                            }
                                        )
                                    }
                                } catch (e: Exception) {
                                    // continue to next source
                                }
                            }
                        } else {
                            // direct file/url
                            val candidate = (s["file"] ?: s["url"] ?: s["data"]) as? String ?: ""
                            if (candidate.contains(".m3u8")) {
                                return listOf(
                                    newExtractorLink(
                                        source = this.name,
                                        name = this.name,
                                        url = candidate,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = apiUrl
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                            }
                        }
                    }
                }
            }

            return null
        } catch (e: Exception) {
            return null
        }
    }
}