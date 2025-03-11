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
            ?: return

        // Step 2: Fetch the iframe content
        val embedDomain = iframeUrl.substringBefore("/embed").substringAfter("://")
        val embedHeaders = headers.plus("Referer" to url)
        val iframeResp = app.get(iframeUrl, headers = embedHeaders)
        var m3u8Url = extractStreamUrlFromScript(iframeResp.document)

        // Step 3: Fallback - Predict .m3u8 based on embed URL
        if (m3u8Url == null) {
            val embedId = iframeUrl.substringAfterLast("/").substringBefore("?")
            val baseDomain = iframeUrl.substringBefore("/embed")
            // Try common patterns
            val possibleM3u8s = listOf(
                "$baseDomain/hls/$embedId.m3u8", // forgepattern.net pattern
                "$baseDomain/$embedId/index.m3u8", // wideiptv.top pattern
                "$baseDomain/$embedId/index.fmp4.m3u8" // fmp4 variant
            )
            for (candidate in possibleM3u8s) {
                val resp = app.get(
                    candidate,
                    headers = headers.plus("Referer" to baseDomain)
                )
                if (resp.isSuccessful && resp.headers["content-type"]?.contains("mpegurl") == true) {
                    m3u8Url = resp.url
                    break
                }
            }
        }

        // Step 4: Handle query params if present in iframe URL
        if (m3u8Url != null && iframeUrl.contains("?")) {
            val params = iframeUrl.substringAfter("?")
            if (!m3u8Url.contains("?")) {
                m3u8Url += "?$params" // Append token if missing
            }
        }

        // Step 5: Return the link if found
        if (m3u8Url?.contains(".m3u8") == true) {
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
        }
    }

    private fun extractStreamUrlFromScript(document: Document): String? {
        val scriptData = document.select("script").joinToString { it.data() }
        return Regex("https?://[^\\s\"']+\\.m3u8[^\\s\"']*").find(scriptData)?.value
            ?: document.selectFirst("source[src*=.m3u8]")?.attr("src")
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("//")) "https:$url"
        else if (url.startsWith("/")) "$mainUrl$url"
        else if (!url.startsWith("http")) "$mainUrl/$url"
        else url
    }
}
