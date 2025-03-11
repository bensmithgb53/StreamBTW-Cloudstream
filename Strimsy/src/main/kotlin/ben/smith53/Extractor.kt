package ben.smith53

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URL
import java.net.URLEncoder

class StrimsyExtractor : ExtractorApi() {
    override val mainUrl = "https://strimsy.top"
    override val name = "StrimsyExtractor"
    override val requiresReferer = true
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("Starting extraction for URL: $url with referer: $referer")
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "*/*",
            "Accept-Encoding" to "gzip, deflate, br, zstd",
            "Accept-Language" to "en-GB,en-US;q=0.9,en;q=0.8",
            "Connection" to "keep-alive",
            "Referer" to (referer ?: mainUrl),
            "Origin" to mainUrl
        )

        // Step 1: Fetch the initial strimsy.top page
        println("Fetching initial page: $url")
        val initialResp = app.get(url, headers = headers, timeout = 10) // Add timeout
        println("Initial response code: ${initialResp.code}, Headers: ${initialResp.headers}")
        val iframeUrl = Regex("iframe src=\"([^\"]*/embed/[^\"]*)\"").find(initialResp.text)?.groupValues?.get(1)
            ?.let { fixUrl(it) }
        if (iframeUrl == null) {
            println("No embed iframe found. Page snippet: ${initialResp.text.take(200)}")
            return
        }
        println("Found embed iframe: $iframeUrl")

        // Step 2: Fetch iframe content
        val embedDomain = URL(iframeUrl).host
        val embedId = iframeUrl.substringAfter("/embed/").substringBefore("?")
        val embedHeaders = headers + mapOf("Referer" to url)
        println("Fetching iframe: $iframeUrl")
        val iframeResp = app.get(iframeUrl, headers = embedHeaders, timeout = 10)
        println("Iframe response code: ${iframeResp.code}, Headers: ${iframeResp.headers}")

        // Step 3: Extract .m3u8 from iframe (like DaddyLive)
        val iframeText = iframeResp.text
        var m3u8Url = Regex("https?://[^\\s\"']+\\.m3u8[^\\s\"']*").find(iframeText)?.value
        println("Script-parsed m3u8: $m3u8Url")

        // Step 4: Fallback - Predict .m3u8 if not in script
        if (m3u8Url == null) {
            val predictedM3u8 = "https://270532139.cdnobject.net:8443/hls/$embedId.m3u8"
            println("Trying predicted m3u8: $predictedM3u8")
            val encodedReferer = URLEncoder.encode("https://$embedDomain/", "UTF-8")
            val m3u8Resp = app.get(
                predictedM3u8,
                headers = headers + mapOf(
                    "Referer" to "https://$embedDomain/",
                    "Origin" to "https://$embedDomain/"
                ),
                timeout = 10
            )
            println("Predicted m3u8 response code: ${m3u8Resp.code}, Headers: ${m3u8Resp.headers}")
            if (m3u8Resp.isSuccessful && m3u8Resp.headers["content-type"]?.contains("mpegurl") == true) {
                m3u8Url = m3u8Resp.url
                println("Found m3u8 via prediction: $m3u8Url")
            }
        }

        // Step 5: Return the link if found
        if (m3u8Url?.contains(".m3u8") == true) {
            println("Final m3u8 link: $m3u8Url")
            callback(
                ExtractorLink(
                    source = this.name,
                    name = "Strimsy Stream",
                    url = m3u8Url,
                    referer = "https://$embedDomain/",
                    quality = Qualities.Unknown.value,
                    isM3u8 = true,
                    headers = headers + mapOf("Referer" to "https://$embedDomain/")
                )
            )
        } else {
            println("No m3u8 link found")
        }
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("//")) "https:$url"
        else if (url.startsWith("/")) "$mainUrl$url"
        else if (!url.startsWith("http")) "$mainUrl/$url"
        else url
    }
}
