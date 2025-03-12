package ben.smith53

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URL

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
            "Referer" to (referer ?: mainUrl),
            "Origin" to mainUrl
        )

        // Step 1: Fetch source page
        println("Fetching source page: $url")
        val initialResp = app.get(url, headers = headers)
        println("Source page response code: ${initialResp.code}, Headers: ${initialResp.headers}")
        val iframeUrl = Regex("iframe src=\"([^\"]+)\"").find(initialResp.text)
            ?.groupValues?.get(1)?.let { fixUrl(it) }
        if (iframeUrl == null) {
            println("No iframe found. Snippet: ${initialResp.text.take(500)}")
            return
        }
        println("Found iframe: $iframeUrl")

        // Step 2: Fetch iframe content
        val iframeHeaders = headers + mapOf("Referer" to url)
        println("Fetching iframe: $iframeUrl with headers: $iframeHeaders")
        val iframeResp = app.get(iframeUrl, headers = iframeHeaders)
        println("Iframe response code: ${iframeResp.code}, Headers: ${iframeResp.headers}")
        val iframeText = iframeResp.text

        // Step 3: Extract .m3u8 or build from embed ID
        var m3u8Url = Regex("https?://[^\\s\"']+\\.m3u8(?:[^\\s\"']*)").find(iframeText)?.value
        println("Parsed m3u8: $m3u8Url")

        // Fallback: Construct .m3u8 from browncrossing.net embed ID
        if (m3u8Url == null && iframeUrl.contains("browncrossing.net/embed/")) {
            val embedId = iframeUrl.split("/embed/").last()
            m3u8Url = "https://270532139.cdnobject.net:8443/hls/$embedId.m3u8?s=g6oxAt6-VcgJcNXlt-Ar3A&e=1741778010" // Static params
            println("Constructed m3u8 from embed ID: $m3u8Url")
        }

        // Step 4: Verify .m3u8
        if (m3u8Url != null) {
            val m3u8Check = app.head(m3u8Url, headers = mapOf("Referer" to "https://browncrossing.net/", "User-Agent" to userAgent))
            println("m3u8 HEAD check code: ${m3u8Check.code}, Headers: ${m3u8Check.headers}")
            if (!m3u8Check.isSuccessful) {
                println("m3u8 check failed: $m3u8Url")
                m3u8Url = null
            }
        }

        // Step 5: Deliver link
        if (m3u8Url?.contains(".m3u8") == true) {
            println("Final m3u8 link: $m3u8Url")
            callback(
                ExtractorLink(
                    source = name,
                    name = "Strimsy Stream",
                    url = m3u8Url,
                    referer = "https://browncrossing.net/",
                    quality = Qualities.Unknown.value,
                    isM3u8 = true,
                    headers = mapOf("Referer" to "https://browncrossing.net/")
                )
            )
        } else {
            println("No m3u8 found. Iframe snippet: ${iframeText.take(1000)}")
        }
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("//")) "https:$url"
        else if (url.startsWith("/")) "$mainUrl$url"
        else if (!url.startsWith("http")) "$mainUrl/$url"
        else url
    }
}
