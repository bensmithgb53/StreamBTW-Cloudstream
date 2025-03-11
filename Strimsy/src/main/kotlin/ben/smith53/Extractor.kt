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
            "Origin" to mainUrl,
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site"
        )

        // Step 1: Fetch the initial strimsy.top page
        val document = app.get(url, headers = headers).document
        val iframeUrl = document.select("iframe")
            .firstOrNull { it.attr("src").contains("embed") && !it.attr("src").contains("chat") }
            ?.attr("src")
            ?.let { fixUrl(it) }
        if (iframeUrl == null) {
            println("No video iframe found in initial page")
            println("Available iframes: ${document.select("iframe").map { it.attr("src") }}")
            return
        }
        println("Found video iframe: $iframeUrl")

        // Step 2: Extract embed ID and domain
        val embedDomain = iframeUrl.substringBefore("/embed").substringAfter("://")
        val embedId = iframeUrl.substringAfter("/embed/").substringBefore("?")
        val embedHeaders = headers.plus("Referer" to url)
        val predictedM3u8Base = "https://270532139.cdnobject.net:8443/hls/$embedId.m3u8"

        // Step 3: Fetch iframe and look for params or .m3u8
        val iframeResp = app.get(iframeUrl, headers = embedHeaders)
        println("Iframe response code: ${iframeResp.code}")
        var m3u8Url = extractStreamUrlFromScript(iframeResp.document)
        var queryParams = iframeUrl.substringAfter("?", "")

        // Step 4: If no .m3u8 in script, try predicted URL with params
        if (m3u8Url == null) {
            val testM3u8 = if (queryParams.isNotEmpty()) "$predictedM3u8Base?$queryParams" else predictedM3u8Base
            println("Trying predicted m3u8: $testM3u8")
            val m3u8Resp = app.get(
                testM3u8,
                headers = headers.plus(
                    "Referer" to "https://$embedDomain/",
                    "Origin" to "https://$embedDomain/"
                )
            )
            if (m3u8Resp.isSuccessful && m3u8Resp.headers["content-type"]?.contains("mpegurl") == true) {
                m3u8Url = m3u8Resp.url
                println("Found m3u8 via prediction: $m3u8Url")
            } else {
                println("Predicted m3u8 failed - Code: ${m3u8Resp.code}, Headers: ${m3u8Resp.headers}")
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
                    headers = headers.plus("Referer" to "https://$embedDomain/")
                )
            )
        } else {
            println("No m3u8 link found")
        }
    }

    private fun extractStreamUrlFromScript(document: Document): String? {
        val scriptData = document.select("script").joinToString { it.data() }
        val m3u8 = Regex("https?://[^\\s\"']+\\.m3u8[^\\s\"']*").find(scriptData)?.value
            ?: document.selectFirst("source[src*=.m3u8]")?.attr("src")
        println("Script data snippet: ${scriptData.take(200)}")
        return m3u8
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("//")) "https:$url"
        else if (url.startsWith("/")) "$mainUrl$url"
        else if (!url.startsWith("http")) "$mainUrl/$url"
        else url
    }
}
