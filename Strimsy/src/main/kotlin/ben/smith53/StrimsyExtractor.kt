package ben.smith53

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.jsoup.Jsoup

class StrimsyExtractor : ExtractorApi() {
    override val name = "Strimsy"
    override val mainUrl = "https://strimsy.top"
    override val requiresReferer = true

    private val sourceRegex = Regex("source=(\\d+)")
    private val m3u8Regex = Regex("['\"]?(https?://[^'\"\\s]+\\.m3u8[^'\"\\s]*)['\" ]?")
    private val eventRegex = Regex("(?<=/l/|/)([^/]+?)(?=\\.php|LIV\\.php)")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Extract source number (default to 2 if not specified)
        val sourceNum = sourceRegex.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 2
        val pageUrl = if (url.contains("?source=")) url else "$url?source=$sourceNum"

        // Extract event name for labeling
        val eventName = eventRegex.find(url)?.groupValues?.get(1)?.replaceFirstChar { it.uppercase() } ?: "Stream"

        // Fetch main page
        val mainResponse = app.get(pageUrl, headers = mapOf("Referer" to mainUrl), timeout = 15).text
        val mainDoc = Jsoup.parse(mainResponse)

        // Find iframes, skipping chat
        val iframes = mainDoc.select("iframe").mapNotNull { it.attr("src") }
            .filter { !it.contains("chat", ignoreCase = true) }

        if (iframes.isEmpty()) {
            log("No iframes found on $pageUrl")
            return
        }

        iframes.forEach { iframeSrc ->
            val fullIframeUrl = if (iframeSrc.startsWith("http")) iframeSrc else "$mainUrl$iframeSrc"
            try {
                // Fetch iframe content
                val iframeResponse = app.get(
                    fullIframeUrl,
                    headers = mapOf("Referer" to pageUrl),
                    timeout = 15
                ).text

                // Extract .m3u8 URLs
                m3u8Regex.findAll(iframeResponse).forEach { match ->
                    val m3u8Url = match.groupValues[1]
                    val quality = when {
                        "hd" in m3u8Url.lowercase() -> "HD"
                        "1080" in m3u8Url -> "1080p"
                        "720" in m3u8Url -> "720p"
                        else -> "Unknown"
                    }

                    // Create extractor link
                    callback.invoke(
                        ExtractorLink(
                            source = this.name,
                            name = "$name - $eventName Source $sourceNum",
                            url = m3u8Url,
                            referer = pageUrl,
                            quality = getQualityFromName(quality),
                            isM3u8 = true,
                            headers = mapOf(
                                "Referer" to pageUrl,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
                            )
                        )
                    )

                    // Resolve M3U8 playlist for quality variants
                    M3u8Helper.generateM3u8(
                        source = this.name,
                        streamUrl = m3u8Url,
                        referer = pageUrl
                    ).forEach(callback)
                }
            } catch (e: Exception) {
                log("Error fetching iframe $fullIframeUrl: ${e.message}")
            }
        }
    }

    private fun log(message: String) {
        println("StrimsyExtractor: $message")
    }
}
