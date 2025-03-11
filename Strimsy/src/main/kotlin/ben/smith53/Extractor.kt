package ben.smith53

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class StrimsyExtractor : ExtractorApi() {
    override val name = "StrimsyExtractor"
    override val mainUrl = "https://strimsy.top"
    override val requiresReferer = true

    private val userAgent = 
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

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
            "Referer" to (referer ?: mainUrl),
            "Origin" to mainUrl
        )

        // Step 1: Fetch the strimsy.top page and find the embed iframe
        val initialResp = app.get(url, headers = headers)
        val iframeUrl = Regex("iframe src=\"([^\"]*/embed/[^\"]*)\"").find(initialResp.text)?.groupValues?.get(1)
            ?.let { fixUrl(it) }
        if (iframeUrl == null) {
            println("No embed iframe found in initial page")
            return
        }
        println("Found embed iframe: $iframeUrl")

        // Step 2: Fetch the iframe content
        val embedDomain = iframeUrl.substringBefore("/embed").substringAfter("://")
        val embedId = iframeUrl.substringAfter("/embed/").substringBefore("?")
        val embedHeaders = headers + mapOf("Referer" to url)
        val iframeResp = app.get(iframeUrl, headers = embedHeaders)
        println("Iframe response code: ${iframeResp.code}")

        // Step 3: Extract .m3u8 from scripts (inspired by DaddyLive and hls-proxy.js)
        val iframeText = iframeResp.text
        var m3u8Url = Regex("https?://[^\\s\"']+\\.m3u8[^\\s\"']*").find(iframeText)?.value
        if (m3u8Url == null) {
            // Look for fetch() calls like in hls-proxy.js
            val fetchUrl = Regex("fetch\\(['\"]?(https?://[^\\s\"']+\\.m3u8[^\\s\"']*)").find(iframeText)?.groupValues?.get(1)
            if (fetchUrl != null) {
                println("Found fetch URL: $fetchUrl")
                val fetchResp = app.get(fetchUrl, headers = embedHeaders + mapOf("Referer" to iframeUrl))
                if (fetchResp.isSuccessful) {
                    m3u8Url = fetchResp.url
                    println("Fetched m3u8 from fetch: $m3u8Url")
                }
            }
        }

        // Step 4: Fallback - Predict .m3u8 if not found in script
        if (m3u8Url == null) {
            val predictedM3u8 = "https://270532139.cdnobject.net:8443/hls/$embedId.m3u8"
            println("Trying predicted m3u8: $predictedM3u8")
            val m3u8Resp = app.get(
                predictedM3u8,
                headers = headers + mapOf(
                    "Referer" to "https://$embedDomain/",
                    "Origin" to "https://$embedDomain/"
                )
            )
            if (m3u8Resp.isSuccessful && m3u8Resp.headers["content-type"]?.contains("mpegurl") == true) {
                m3u8Url = m3u8Resp.url
                println("Found m3u8 via prediction: $m3u8Url")
            } else {
                println("Predicted m3u8 failed - Code: ${m3u8Resp.code}")
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
