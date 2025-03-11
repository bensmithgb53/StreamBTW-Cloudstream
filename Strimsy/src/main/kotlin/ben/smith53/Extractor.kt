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
        val initialResp = app.get(url, headers = headers, timeout = 10)
        println("Initial response code: ${initialResp.code}")
        val liveIframeUrl = Regex("iframe src=\"(/live/[^\"]+\\.php[^\"]*)\"").find(initialResp.text)?.groupValues?.get(1)
            ?.let { fixUrl(it) }
        if (liveIframeUrl == null) {
            println("No live iframe found. Page snippet: ${initialResp.text.take(200)}")
            return
        }
        println("Found live iframe: $liveIframeUrl")

        // Step 2: Fetch the live iframe content
        val liveDomain = URL(liveIframeUrl).host
        val liveHeaders = headers + mapOf("Referer" to url)
        println("Fetching live iframe: $liveIframeUrl")
        val liveResp = app.get(liveIframeUrl, headers = liveHeaders, timeout = 10)
        println("Live iframe response code: ${liveResp.code}, Headers: ${liveResp.headers}")
        val liveText = liveResp.text

        // Step 3: Extract .m3u8 from live iframe
        var m3u8Url = Regex("https?://[^\\s\"']+\\.m3u8[^\\s\"']*").find(liveText)?.value
        println("Script-parsed m3u8: $m3u8Url")

        // Step 4: Check for a player iframe if no .m3u8 (e.g., voodc.com/player/)
        if (m3u8Url == null) {
            val playerIframeUrl = Regex("iframe src=\"(https?://[^\"]+/player/[^\"]*)\"").find(liveText)?.groupValues?.get(1)
            if (playerIframeUrl != null) {
                println("Found player iframe: $playerIframeUrl")
                val playerResp = app.get(playerIframeUrl, headers = liveHeaders + mapOf("Referer" to liveIframeUrl), timeout = 10)
                println("Player iframe response code: ${playerResp.code}")
                m3u8Url = Regex("https?://[^\\s\"']+\\.m3u8[^\\s\"']*").find(playerResp.text)?.value
                println("Player-parsed m3u8: $m3u8Url")
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
                    referer = "https://$liveDomain/",
                    quality = Qualities.Unknown.value,
                    isM3u8 = true,
                    headers = headers + mapOf("Referer" to "https://$liveDomain/")
                )
            )
        } else {
            println("No m3u8 link found. Live iframe snippet: ${liveText.take(200)}")
        }
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("//")) "https:$url"
        else if (url.startsWith("/")) "$mainUrl$url"
        else if (!url.startsWith("http")) "$mainUrl/$url"
        else url
    }
}
