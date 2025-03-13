package ben.smith53

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URL

class StrimsyExtractor : ExtractorApi() {
    override val mainUrl = "https://strimsy.top"
    override val name = "Strimsy"
    override val requiresReferer = true
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to referer ?: mainUrl,
            "Accept" to "text/html,application/xhtml+xml"
        )

        val baseResp = app.get(url, headers = headers).text
        
        val sourceLinks = Regex("<a href=\"\\?source=(\\d+)\"[^>]*>(.*?)</a>")
            .findAll(baseResp)
            .map { match ->
                val sourceNum = match.groupValues[1]
                val label = match.groupValues[2].replace("<b>", "").replace("</b>", "").trim()
                kotlin.Pair(label, "$url?source=$sourceNum")
            }
            .toList()
            .ifEmpty { listOf(kotlin.Pair("Default", url)) }

        sourceLinks.forEach { (label, sourceUrl) ->
            extractVideo(sourceUrl, label)?.let { callback(it) }
        }
    }

    private suspend fun extractVideo(url: String, sourceName: String): ExtractorLink? {
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to mainUrl,
            "Accept" to "text/html,application/xhtml+xml"
        )

        val resp = app.get(url, headers = headers).text
        val iframeUrl = Regex("iframe[^>]*src=\"([^\"]+)\"").find(resp)?.groupValues?.get(1)
            ?.let { if (it.startsWith("http")) it else "https://strimsy.top$it" }
            ?.takeIf { !it.contains("chat") }
            ?: return null

        val iframeHeaders = mapOf("User-Agent" to userAgent, "Referer" to url)
        val iframeResp = app.get(iframeUrl, headers = iframeHeaders).text

        val nextUrl = Regex("src=\"([^\"]+\\.js)\"").find(iframeResp)?.groupValues?.get(1)
            ?.let { if (it.startsWith("http")) it else "https://${URL(iframeUrl).host}$it" }
            ?: Regex("iframe[^>]*src=\"([^\"]+)\"").find(iframeResp)?.groupValues?.get(1)
            ?.let { if (it.startsWith("http")) it else "https://${URL(iframeUrl).host}$it" }
            ?: iframeUrl

        val nextResp = app.get(nextUrl, headers = iframeHeaders).text

        val streamUrl = Regex("https://[^\" ]*\\.m3u8").find(nextResp)?.value
            ?: Regex("https://[^\" ]*\\.ts").find(nextResp)?.value
            ?: Regex("https://[^\" ]*\\.mp4").find(nextResp)?.value
            ?: return null

        return ExtractorLink(
            name,
            "$name - $sourceName",
            streamUrl,
            referer = nextUrl,
            isM3u8 = streamUrl.endsWith(".m3u8"),
            quality = when {
                sourceName.contains("FULL HD", true) -> Qualities.P1080.value
                sourceName.contains("HD", true) -> Qualities.P720.value
                else -> Qualities.Unknown.value
            },
            headers = iframeHeaders
        )
    }
}
