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
        val embedIframeUrl = Regex("iframe src=\"([^\"]*/embed/[^\"]*)\"").find(initialResp.text)?.groupValues?.get(1)
            ?.let { fixUrl(it) }
        if (embedIframeUrl == null) {
            println("No embed iframe found. Page snippet: ${initialResp.text.take(200)}")
            return
        }
        println("Found embed iframe: $embedIframeUrl")

        // Step 2: Fetch the embed iframe
        val embedDomain = URL(embedIframeUrl).host
        val embedHeaders = headers + mapOf("Referer" to url)
        println("Fetching embed iframe: $embedIframeUrl")
        val embedResp = app.get(embedIframeUrl, headers = embedHeaders, timeout = 10)
        println("Embed iframe response code: ${embedResp.code}")
        val embedText = embedResp.text

        // Step 3: Extract the player iframe URL from the script
        val playerPath = Regex("n = \"https://[^\"]+/player/([^\"]*)\"").find(embedText)?.groupValues?.get(1)
        if (playerPath == null) {
            println("No player iframe path found. Embed snippet: ${embedText.take(200)}")
            return
        }
        val playerIframeUrl = "https://$embedDomain/player/$playerPath"
        println("Found player iframe: $playerIframeUrl")

        // Step 4: Fetch the player iframe
        val playerHeaders = headers + mapOf("Referer" to embedIframeUrl)
        println("Fetching player iframe: $playerIframeUrl")
        val playerResp = app.get(playerIframeUrl, headers = playerHeaders, timeout = 10)
        println("Player iframe response code: ${playerResp.code}")
        val playerText = playerResp.text

        // Step 5: Extract .m3u8 from player iframe
        var m3u8Url = Regex("https?://[^\\s\"']+\\.m3u8[^\\s\"']*").find(playerText)?.value
        println("Script-parsed m3u8: $m3u8Url")

        // Step 6: Return the link if found
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
            println("No m3u8 link found. Player snippet: ${playerText.take(200)}")
        }
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("//")) "https:$url"
        else if (url.startsWith("/")) "$mainUrl$url"
        else if (!url.startsWith("http")) "$mainUrl/$url"
        else url
    }
}
