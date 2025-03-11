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

        // Step 1: Fetch the specific source page
        println("Fetching source page: $url")
        val initialResp = app.get(url, headers = headers)
        println("Source page response code: ${initialResp.code}, Headers: ${initialResp.headers}")
        val iframeUrl = Regex("iframe src=\"([^\"]+)\"").findAll(initialResp.text)
            .map { fixUrl(it.groupValues[1]) }
            .firstOrNull { !it.contains("chat2.php") }
        if (iframeUrl == null) {
            println("No valid iframe found. Page snippet: ${initialResp.text.take(200)}")
            return
        }
        println("Found iframe for source: $iframeUrl")

        // Step 2: Fetch the iframe content
        val iframeDomain = URL(iframeUrl).host
        val iframeHeaders = headers + mapOf("Referer" to url, "Origin" to mainUrl)
        println("Fetching iframe: $iframeUrl with headers: $iframeHeaders")
        val iframeResp = app.get(iframeUrl, headers = iframeHeaders)
        println("Iframe response code: ${iframeResp.code}, Headers: ${iframeResp.headers}")
        val iframeText = iframeResp.text

        // Step 3: Extract .m3u8 from iframe (broader regex)
        var m3u8Url = Regex("https?://[^\\s\"']+\\.m3u8(?:[^\\s\"']*)").find(iframeText)?.value
        println("Script-parsed m3u8: $m3u8Url")

        // Step 4: Follow additional iframe if no .m3u8
        if (m3u8Url == null) {
            val nextIframeUrl = Regex("iframe asesor=\"(https?://[^\"]+)\"").find(iframeText)?.groupValues?.get(1)
            if (nextIframeUrl != null) {
                println("Found next iframe: $nextIframeUrl")
                val nextResp = app.get(nextIframeUrl, headers = iframeHeaders + mapOf("Referer" to iframeUrl))
                println("Next iframe response code: ${nextResp.code}, Headers: ${nextResp.headers}")
                m3u8Url = Regex("https?://[^\\s\"']+\\.m3u8(?:[^\\s\"']*)").find(nextResp.text)?.value
                println("Next iframe-parsed m3u8: $m3u8Url")
            }
        }

        // Step 5: Verify .m3u8 with a HEAD request (optional)
        if (m3u8Url != null) {
            val m3u8Check = app.head(m3u8Url, headers = iframeHeaders + mapOf("Referer" to "https://$iframeDomain/"))
            println("m3u8 HEAD check code: ${m3u8Check.code}, Headers: ${m3u8Check.headers}")
            if (!m3u8Check.isSuccessful) {
                println("m3u8 HEAD check failed, discarding: $m3u8Url")
                m3u8Url = null
            }
        }

        // Step 6: Return the link if found
        if (m3u8Url?.contains(".m3u8") == true) {
            println("Final m3u8 link: $m3u8Url")
            callback(
                ExtractorLink(
                    source = this.name,
                    name = "Strimsy Stream",
                    url = m3u8Url,
                    referer = "https://$iframeDomain/",
                    quality = Qualities.Unknown.value,
                    isM3u8 = true,
                    headers = headers + mapOf("Referer" to "https://$iframeDomain/")
                )
            )
        } else {
            println("No m3u8 link found. Iframe snippet: ${iframeText.take(500)}")
        }
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("//")) "https:$url"
        else if (url.startsWith("/")) "$mainUrl$url"
        else if (!url.startsWith("http")) "$mainUrl/$url"
        else url
    }
}
