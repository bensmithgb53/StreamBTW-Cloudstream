package ben.smith53

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.Jsoup

class PPVLandExtractor : ExtractorApi() {
    override val mainUrl = "https://ppv.land"
    override val name = "PPVLand"
    override val requiresReferer = true

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String, // e.g., https://ppv.land/live/1742487300/CBS
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to (referer ?: mainUrl)
        )

        // Fetch the PPV Land live stream page
        val response = app.get(url, headers = headers).text
        val doc = Jsoup.parse(response)

        // Extract the embed iframe URL from the modal
        val embedCode = doc.selectFirst("#embedcode")?.text()
        val embedUrl = Regex("src=\"(https://www\\.vidembed\\.re/stream/[^\"]+)\"").find(embedCode ?: "")?.groupValues?.get(1)
        if (embedUrl != null) { // e.g., https://www.vidembed.re/stream/977b47d5-ec7c-4211-a25d-bfb1f579c6aa
            val embedHeaders = mapOf(
                "User-Agent" to userAgent,
                "Referer" to url
            )
            val embedResponse = app.get(embedUrl, headers = embedHeaders).text
            val embedDoc = Jsoup.parse(embedResponse)

            // Check if stream is not live yet
            val notifText = embedDoc.selectFirst("#notif")?.text()
            if (notifText?.contains("not live yet", ignoreCase = true) == true) {
                println("Stream not live yet: $embedUrl")
                return
            }

            // Fetch base.js
            val baseJsUrl = "https://www.vidembed.re/assets/base.js?v=0.1.0"
            val baseJsResponse = app.get(baseJsUrl, headers = embedHeaders).text

            // Look for .m3u8 in base.js
            val m3u8Url = Regex("https?://[^\"'\\s]+\\.m3u8").find(baseJsResponse)?.value
                ?: Regex("file:\\s*['\"]?(https?://[^\"'\\s]+\\.m3u8)['\"]?").find(baseJsResponse)?.groupValues?.get(1)
            if (m3u8Url != null) {
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = m3u8Url,
                        referer = embedUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = true,
                        headers = mapOf("Referer" to embedUrl)
                    )
                )
                return
            }

            // Fallback: Try stream ID-based API (speculative)
            val streamId = embedUrl.substringAfterLast("/")
            val potentialApiUrl = "https://www.vidembed.re/api/stream/$streamId"
            val apiResponse = app.get(potentialApiUrl, headers = embedHeaders).text
            val apiM3u8 = Regex("https?://[^\"'\\s]+\\.m3u8").find(apiResponse)?.value
            if (apiM3u8 != null) {
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = apiM3u8,
                        referer = embedUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = true,
                        headers = mapOf("Referer" to embedUrl)
                    )
                )
                return
            }
        }

        println("No .m3u8 found for $url")
        throw Exception("No .m3u8 URL found; check base.js or Network tab.")
    }
}
