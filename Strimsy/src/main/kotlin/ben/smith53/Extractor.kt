package ben.smith53

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class StrimsyExtractor : ExtractorApi() {
    override val mainUrl = "https://strimsy.top"
    override val name = "Strimsy"
    override val requiresReferer = true // For potential iframe restrictions

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String = mainUrl, // Default to main page
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to mainUrl
        )

        // Step 1: Fetch the main page and extract event page links
        val mainResponse = app.get(mainUrl, headers = headers).text
        val mainDoc = app.parseHtml(mainResponse)

        val eventLinks = mainDoc.select("table.ramowka td a[href]")
            .map { it.attr("href") }
            .filter { it.startsWith("/") && !it.contains("liga") && !it.contains("football") } // Filter out non-event links
            .map { "$mainUrl$it" }
            .distinct()

        // Step 2: Process each event page to get stream URLs
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

        // Step 3: Fetch the event page (e.g., /Dart.php)
        val eventResponse = app.get(eventUrl, headers = headers).text
        val eventDoc = app.parseHtml(eventResponse)

        // Extract the /live/ iframe URL (e.g., /live/r16w.php)
        val liveIframe = eventDoc.selectFirst("iframe#rk")?.attr("src") ?: return null
        val liveUrl = if (liveIframe.startsWith("/")) "$mainUrl$liveIframe" else liveIframe

        // Step 4: Fetch the live page to get the final stream URL
        val liveResponse = app.get(liveUrl, headers = headers).text
        val liveDoc = app.parseHtml(liveResponse)

        // Extract the external stream URL (e.g., swipebreed.net/embed/2h8vdh4t5u)
        val streamUrl = liveDoc.selectFirst("iframe#rk")?.attr("src") ?: return null
        val finalStreamUrl = if (streamUrl.startsWith("//")) "https:$streamUrl" else streamUrl

        return ExtractorLink(
            source = this.name,
            name = this.name, // Minimal naming since you don’t need event details
            url = finalStreamUrl,
            referer = mainUrl,
            quality = Qualities.Unknown.value, // No quality info needed
            isM3u8 = finalStreamUrl.endsWith(".m3u8"), // Auto-detect m3u8
            headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to mainUrl,
                "Origin" to mainUrl
            )
        )
    }
}
