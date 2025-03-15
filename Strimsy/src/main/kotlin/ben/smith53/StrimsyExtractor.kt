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
    private val knownStreamHosts = listOf("wikisport.best", "pricesaskeloadsc.com", "streamtp3.com")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val sourceNum = sourceRegex.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 2
        val pageUrl = if (url.contains("?source=")) url else "$url?source=$sourceNum"
        val eventName = eventRegex.find(url)?.groupValues?.get(1)?.replaceFirstChar { it.uppercase() } ?: "Stream"
        val headers = mapOf(
            "Referer" to mainUrl,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        )

        // Fetch main page
        val mainResponse = app.get(pageUrl, headers = headers, timeout = 15).text
        val mainDoc = Jsoup.parse(mainResponse)
        val iframes = mainDoc.select("iframe").mapNotNull { it.attr("src") }
            .filter { !it.contains("chat", ignoreCase = true) }

        if (iframes.isEmpty()) {
            log("No iframes found on $pageUrl")
            return
        }

        iframes.forEach { iframeSrc ->
            val fullIframeUrl = if (iframeSrc.startsWith("http")) iframeSrc else "$mainUrl$iframeSrc"
            log("Processing iframe: $fullIframeUrl")

            try {
                // Fetch iframe content
                val iframeHeaders = mapOf("Referer" to pageUrl, "User-Agent" to headers["User-Agent"]!!)
                val iframeResponse = app.get(fullIframeUrl, headers = iframeHeaders, timeout = 15).text
                val iframeDoc = Jsoup.parse(iframeResponse)

                // Check for nested iframes (common in Strimsy)
                val nestedIframes = iframeDoc.select("iframe").mapNotNull { it.attr("src") }
                if (nestedIframes.isNotEmpty()) {
                    nestedIframes.forEach { nestedSrc ->
                        val fullNestedUrl = if (nestedSrc.startsWith("http")) nestedSrc else fullIframeUrl.substringBeforeLast("/") + nestedSrc
                        log("Processing nested iframe: $fullNestedUrl")
                        extractM3u8(fullNestedUrl, pageUrl, eventName, sourceNum, callback)
                    }
                } else {
                    extractM3u8(fullIframeUrl, pageUrl, eventName, sourceNum, callback, iframeResponse)
                }
            } catch (e: Exception) {
                log("Error fetching iframe $fullIframeUrl: ${e.message}")
                // Fallback: Use iframe URL directly
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = "$name - $eventName Source $sourceNum (Fallback)",
                        url = fullIframeUrl,
                        referer = pageUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = false, // Let player figure it out
                        headers = mapOf("Referer" to pageUrl, "User-Agent" to headers["User-Agent"]!!)
                    )
                )
            }
        }
    }

    private suspend fun extractM3u8(
        url: String,
        referer: String,
        eventName: String,
        sourceNum: Int,
        callback: (ExtractorLink) -> Unit,
        html: String? = null
    ) {
        val headers = mapOf(
            "Referer" to referer,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        )
        val responseText = html ?: app.get(url, headers = headers, timeout = 15).text

        m3u8Regex.findAll(responseText).forEach { match ->
            val m3u8Url = match.groupValues[1]
            val quality = when {
                "hd" in m3u8Url.lowercase() -> "HD"
                "1080" in m3u8Url -> "1080p"
                "720" in m3u8Url -> "720p"
                else -> "Unknown"
            }
            log("Found .m3u8: $m3u8Url")
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

        // Probe known stream hosts if no .m3u8 found
        if (!responseText.contains(".m3u8")) {
            knownStreamHosts.forEach { host ->
                val testUrl = "https://$host/hls/stream.m3u8?ch=${eventName.lowercase()}"
                try {
                    val testResponse = app.get(testUrl, headers = headers, timeout = 5)
                    if (testResponse.code == 200 && testResponse.text.contains("#EXTM3U")) {
                        log("Probed .m3u8: $testUrl")
                        callback.invoke(
                            ExtractorLink(
                                source = this.name,
                                name = "$name - $eventName Source $sourceNum (Probed)",
                                url = testUrl,
                                referer = referer,
                                quality = Qualities.Unknown.value,
                                isM3u8 = true,
                                headers = headers
                            )
                        )
                    }
                } catch (e: Exception) {
                    log("Probe failed for $testUrl: ${e.message}")
                }
            }
        }
    }

    private fun log(message: String) {
        println("StrimsyExtractor: $message")
    }
}
