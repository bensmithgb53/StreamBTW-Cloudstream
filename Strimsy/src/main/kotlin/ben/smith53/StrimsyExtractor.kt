package ben.smith53

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.Jsoup

class StrimsyExtractor : ExtractorApi() {
    override val mainUrl = "https://strimsy.top"
    override val name = "Strimsy"
    override val requiresReferer = true

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to mainUrl
        )

        // Fetch the main page
        val mainResponse = app.get(mainUrl, headers = headers).text
        val mainDoc = Jsoup.parse(mainResponse)

        // Extract event page links
        val eventLinks = mainDoc.select("table.ramowka td a[href]")
            .map { link -> link.attr("href") }
            .filter { it.startsWith("/") && !it.contains("liga") && !it.contains("football") }
            .map { "$mainUrl$it" }
            .distinct()

        // Process each event page
        eventLinks.forEach { eventUrl ->
            val streamLink = extractStream(eventUrl)
            if (streamLink != null) {
                callback(streamLink)
            }
        }
    }

    private suspend fun extractStream(eventUrl: String): ExtractorLink? {
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to mainUrl
        )

        // Step 1: Fetch the event page (e.g., /Dart.php)
        val eventResponse = app.get(eventUrl, headers = headers).text
        val eventDoc = Jsoup.parse(eventResponse)

        // Extract the /live/ iframe URL (e.g., /live/r16w.php)
        val liveIframe = eventDoc.selectFirst("iframe#rk")?.attr("src") ?: return null
        val liveUrl = if (liveIframe.startsWith("/")) "$mainUrl$liveIframe" else liveIframe

        // Step 2: Fetch the live page
        val liveHeaders = mapOf(
            "User-Agent" to userAgent,
            "Referer" to eventUrl
        )
        val liveResponse = app.get(liveUrl, headers = liveHeaders).text
        val liveDoc = Jsoup.parse(liveResponse)

        // Extract the embed URL (e.g., swipebreed.net/embed/2h8vdh4t5u)
        val embedUrl = liveDoc.selectFirst("iframe#rk")?.attr("src") ?: return null
        val finalEmbedUrl = if (embedUrl.startsWith("//")) "https:$embedUrl" else embedUrl

        // Step 3: Fetch the embed page and extract the .m3u8 stream
        val embedHeaders = mapOf(
            "User-Agent" to userAgent,
            "Referer" to liveUrl
        )
        val embedResponse = app.get(finalEmbedUrl, headers = embedHeaders).text

        // Extract .m3u8 URL from hls.loadSource()
        val streamUrl = Regex("hls\\.loadSource\\(['\"]?(https?://[^'\"]+\\.m3u8[^'\"]*)['\"]?\\)")
            .find(embedResponse)?.groupValues?.get(1)
            ?: return null // If no match, fail gracefully

        // Ensure the stream URL is absolute
        val finalStreamUrl = if (streamUrl.startsWith("//")) "https:$streamUrl" else streamUrl

        return ExtractorLink(
            source = this.name,
            name = this.name,
            url = finalStreamUrl,
            referer = finalEmbedUrl,
            quality = Qualities.Unknown.value,
            isM3u8 = finalStreamUrl.endsWith(".m3u8"),
            headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to finalEmbedUrl,
                "Origin" to "https://swipebreed.net"
            )
        )
    }
}
