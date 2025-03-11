package ben.smith53

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document

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
            "Accept-Language" to "en-GB,en-US;q=0.9,en;q=0.8",
            "Referer" to (referer ?: mainUrl),
            "Origin" to mainUrl
        )

        // Step 1: Fetch the initial strimsy.top page
        val document = app.get(url, headers = headers).document
        val iframeUrl = document.selectFirst("iframe")?.attr("src")?.let { fixUrl(it) }
        if (iframeUrl == null) {
            println("No iframe found in initial page")
            return
        }
        println("Found iframe: $iframeUrl")

        // Step 2: Fetch iframe content and extract .m3u8
        val embedDomain = iframeUrl.substringBefore("/embed").substringAfter("://")
        val embedHeaders = headers.plus("Referer" to url)
        val iframeResp = app.get(iframeUrl, headers = embedHeaders)
        println("Iframe response code: ${iframeResp.code}")
        var m3u8Url = extractStreamUrlFromScript(iframeResp.document)

        // Step 3: If not found, try deeper script-based extraction (like DaddyLive)
        if (m3u8Url == null) {
            val iframeHtml = iframeResp.text
            val fetchUrl = Regex("fetch\\(['\"]([^'\"]+\\.m3u8[^'\"]*)").find(iframeHtml)?.groupValues?.get(1)
            if (fetchUrl != null) {
                println("Found fetch URL: $fetchUrl")
                val fetchResp = app.get(fetchUrl, headers = embedHeaders.plus("Referer" to iframeUrl))
                if (fetchResp.isSuccessful) {
                    m3u8Url = fetchResp.url
                    println("Fetched m3u8 from fetch: $m3u8Url")
                }
            }
        }

        // Step 4: Fallback - Predict .m3u8 based on embed URL
        if (m3u8Url == null) {
            val embedId = iframeUrl.substringAfterLast("/").substringBefore("?")
            val baseDomain = iframeUrl.substringBefore("/embed")
            val possibleM3u8s = listOf(
                "$baseDomain/hls/$embedId.m3u8",
                "$baseDomain/$embedId/index.m3u8",
                "$baseDomain/$embedId/index.fmp4.m3u8",
                "$baseDomain/hls/$embedId/index.m3u8"
            )
            for (candidate in possibleM3u8s) {
                println("Trying predicted m3u8: $candidate")
                val resp = app.get(candidate, headers = embedHeaders.plus("Referer" to baseDomain))
                if (resp.isSuccessful && resp.headers["content-type"]?.contains("mpegurl") == true) {
                    m3u8Url = resp.url
                    println("Found m3u8 via prediction: $m3u8Url")
                    break
                } else {
                    println("Failed $candidate - Code: ${resp.code}")
                }
            }
        }

        // Step 5: Append query params if present
        if (m3u8Url != null && iframeUrl.contains("?")) {
            val params = iframeUrl.substringAfter("?")
            if (!m3u8Url.contains("?")) {
                m3u8Url += "?$params"
                println("Added params to m3u8: $m3u8Url")
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
                    referer = "https://$embedDomain/",
                    quality = Qualities.Unknown.value,
                    isM3u8 = true,
                    headers = headers
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
