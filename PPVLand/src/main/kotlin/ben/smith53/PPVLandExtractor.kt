package ben.smith53.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class PPVLandExtractor : ExtractorApi() {
    override val name = "PPVLandExtractor"
    override val mainUrl = "https://ppv.wtf" // Corrected from ppv.to
    override val requiresReferer = true

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"
    private val HEADERS = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Encoding" to "gzip, deflate, br, zstd",
        "Connection" to "keep-alive",
        "Accept-Language" to "en-US,en;q=0.5",
        "X-FS-Client" to "FS WebClient 1.0",
        "Cookie" to "cf_clearance=..."
    )

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            // Check if the URL is an API stream URL
            if (url.contains("/api/streams/")) {
                val response = app.get(url, headers = HEADERS, referer = referer ?: "$mainUrl/")
                val json = JSONObject(response.text)
                val dataObj = json.optJSONObject("data") ?: json

                // Try direct m3u8 link from the JSON data
                val m3u8Url = dataObj.optString("m3u8").takeIf { it.isNotBlank() }
                if (m3u8Url != null) {
                    return listOf(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = m3u8Url.replace("\\/", "/"),
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }

                // If no m3u8, check the sources array for an embed or m3u8
                val sourcesArray = dataObj.optJSONArray("sources")
                if (sourcesArray != null) {
                    for (i in 0 until sourcesArray.length()) {
                        val source = sourcesArray.optJSONObject(i) ?: continue
                        val sdata = source.optString("data").takeIf { it.isNotBlank() }
                        if (sdata != null) {
                            // If it's an m3u8, return it
                            if (sdata.contains(".m3u8")) {
                                return listOf(
                                    newExtractorLink(
                                        source = this.name,
                                        name = this.name,
                                        url = sdata.replace("\\/", "/"),
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = "$mainUrl/"
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                            }
                            // If it's an iframe embed, try to extract from it
                            val stype = source.optString("type")
                            if (stype.equals("iframe", true) || sdata.contains("/embed/")) {
                                return extractFromEmbed(sdata, referer)
                            }
                        }
                    }
                }
            }

            // If the URL is already an embed page or a direct m3u8, handle that
            if (url.contains("/embed/") || url.contains(".m3u8")) {
                return extractFromEmbed(url, referer)
            }

            return null
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private suspend fun extractFromEmbed(embedUrl: String, referer: String?): List<ExtractorLink>? {
        try {
            val fixed = embedUrl.replace("\\/", "/")
            val resp = app.get(fixed, headers = HEADERS, referer = referer ?: "$mainUrl/").text

            // Try several regex patterns to find an .m3u8 url inside javascript configuration
            // 1) search for file: "https://...m3u8"
            val patterns = listOf(
                "\"(https?://[^\"]+?\\.m3u8[^\"]*)\"",
                "'(https?://[^']+?\\.m3u8[^']*)'",
                "file\\s*:\\s*\"(https?://[^\"]+?\\.m3u8[^\"]*)\"",
                "file\\s*:\\s*'(https?://[^']+?\\.m3u8[^']*)'",
                "playlist\\s*:\\s*\\[\\s*\\{[^}]*file\\s*:\\s*\"(https?://[^\"]+?\\.m3u8[^\"]*)\"",
                "src\\s*=\\s*\"(https?://[^\"]+?\\.m3u8[^\"]*)\"",
                "(https?://[\\w\\d\\.\\-:/_%]+\\.m3u8[\\w\\d\\-\\?=&_%]*)"
            )

            for (p in patterns) {
                val regex = Regex(p)
                val match = regex.find(resp)
                if (match != null && match.groupValues.size >= 2) {
                    val found = match.groupValues[1].replace("\\/", "/")
                    return listOf(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = found,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = fixed
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }

            // If no .m3u8 found but embed page contains another iframe, try to follow it
            val iframeRegex = Regex("<iframe[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            val iframeMatch = iframeRegex.find(resp)
            if (iframeMatch != null) {
                val iframeSrc = iframeMatch.groupValues[1].replace("\\/", "/")
                if (iframeSrc != fixed && iframeSrc.contains("http")) {
                    return extractFromEmbed(iframeSrc, fixed)
                }
            }

            return null
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
