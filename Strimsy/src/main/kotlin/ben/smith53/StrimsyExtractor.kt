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
        val eventName = eventRegex.find(url)?.groupValues?.get(1)?.replaceFirstChar { it.uppercase() } ?: "Stream"
        val baseUrl = url.substringBefore("?")
        val headers = mapOf(
            "Referer" to mainUrl,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        )

        // Try all sources (1-5) to catch all streams
        (1..5).forEach { sourceNum ->
            val pageUrl = "$baseUrl?source=$sourceNum"
            log("Processing $pageUrl")

            try {
                val mainResponse = app.get(pageUrl, headers = headers, timeout = 15).text
                val mainDoc = Jsoup.parse(mainResponse)
                val iframes = mainDoc.select("iframe").mapNotNull { it.attr("src") }
                    .filter { !it.contains("chat", ignoreCase = true) }

                if (iframes.isEmpty()) {
                    log("No iframes found on $pageUrl")
                }

                iframes.forEach { iframeSrc ->
                    val fullIframeUrl = if (iframeSrc.startsWith("http")) iframeSrc else "$mainUrl$iframeSrc"
                    log("Processing iframe: $fullIframeUrl")
                    extractFromIframe(fullIframeUrl, pageUrl, eventName, sourceNum, callback)
                }

                // Check main page scripts for .m3u8
                mainDoc.select("script").forEach { script ->
                    val scriptContent = script.html()
                    m3u8Regex.findAll(scriptContent).forEach { match ->
                        val m3u8Url = match.groupValues[1]
                        log("Found .m3u8 in main script: $m3u8Url")
                        addStream(m3u8Url, pageUrl, eventName, sourceNum, callback)
                    }
                }

                // Fallback: Add iframe URLs directly if no .m3u8 found
                if (iframes.isNotEmpty() && !mainResponse.contains(".m3u8")) {
                    iframes.forEach { iframeSrc ->
                        val fullIframeUrl = if (iframeSrc.startsWith("http")) iframeSrc else "$mainUrl$iframeSrc"
                        log("No .m3u8 found, adding iframe as fallback: $fullIframeUrl")
                        callback.invoke(
                            ExtractorLink(
                                source = this.name,
                                name = "$name - $eventName Source $sourceNum (Fallback)",
                                url = fullIframeUrl,
                                referer = pageUrl,
                                quality = Qualities.Unknown.value,
                                isM3u8 = false,
                                headers = headers
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                log("Error fetching $pageUrl: ${e.message}")
            }
        }
    }

    private suspend fun extractFromIframe(
        iframeUrl: String,
        referer: String,
        eventName: String,
        sourceNum: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Referer" to referer,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        )

        try {
            val iframeResponse = app.get(iframeUrl, headers = headers, timeout = 15).text
            val iframeDoc = Jsoup.parse(iframeResponse)

            // Look for .m3u8 in iframe
            m3u8Regex.findAll(iframeResponse).forEach { match ->
                val m3u8Url = match.groupValues[1]
                log("Found .m3u8 in iframe: $m3u8Url")
                addStream(m3u8Url, referer, eventName, sourceNum, callback)
            }

            // Check for nested iframes
            val nestedIframes = iframeDoc.select("iframe").mapNotNull { it.attr("src") }
            nestedIframes.forEach { nestedSrc ->
                val fullNestedUrl = if (nestedSrc.startsWith("http")) nestedSrc else iframeUrl.substringBeforeLast("/") + nestedSrc
                log("Processing nested iframe: $fullNestedUrl")
                val nestedResponse = app.get(fullNestedUrl, headers = headers, timeout = 15).text
                m3u8Regex.findAll(nestedResponse).forEach { match ->
                    val m3u8Url = match.groupValues[1]
                    log("Found .m3u8 in nested iframe: $m3u8Url")
                    addStream(m3u8Url, referer, eventName, sourceNum, callback)
                }
            }

            // Check iframe scripts
            iframeDoc.select("script").forEach { script ->
                val scriptContent = script.html()
                m3u8Regex.findAll(scriptContent).forEach { match ->
                    val m3u8Url = match.groupValues[1]
                    log("Found .m3u8 in iframe script: $m3u8Url")
                    addStream(m3u8Url, referer, eventName, sourceNum, callback)
                }
            }
        } catch (e: Exception) {
            log("Error fetching iframe $iframeUrl: ${e.message}")
            // Fallback: Use iframe URL directly
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "$name - $eventName Source $sourceNum (Fallback)",
                    url = iframeUrl,
                    referer = referer,
                    quality = Qualities.Unknown.value,
                    isM3u8 = false,
                    headers = headers
                )
            )
        }
    }

    private fun addStream(
        m3u8Url: String,
        referer: String,
        eventName: String,
        sourceNum: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Referer" to referer,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        )
        val quality = when {
            "hd" in m3u8Url.lowercase() -> "HD"
            "1080" in m3u8Url -> "1080p"
            "720" in m3u8Url -> "720p"
            else -> "Unknown"
        }
        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = "$name - $eventName Source $sourceNum",
                url = m3u8Url,
                referer = referer,
                quality = getQualityFromName(quality),
                isM3u8 = true,
                headers = headers
            )
        )
        M3u8Helper.generateM3u8(this.name, m3u8Url, referer).forEach(callback)
    }

    private fun log(message: String) {
        println("StrimsyExtractor: $message")
    }
}
