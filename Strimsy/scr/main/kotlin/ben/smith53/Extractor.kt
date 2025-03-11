package ben.smith53 // Match your package name

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
            "Referer" to referer ?: mainUrl,
            "Origin" to mainUrl
        )

        try {
            val document = app.get(url, headers = headers).document
            extractStreams(document, url, headers, callback)
        } catch (e: Exception) {
            // Handle extraction failure silently or log it
        }
    }

    private suspend fun extractStreams(
        document: Document,
        referer: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        val iframeUrl = document.selectFirst("iframe")?.attr("src")?.let { fixUrl(it) }
            ?: return

        // Try multiple approaches to get the stream
        val streamResponse = app.get(
            iframeUrl,
            referer = referer,
            headers = headers,
            allowRedirects = true
        )

        var streamUrl = streamResponse.url.takeIf { it.contains(".m3u8") }
            ?: extractStreamUrlFromScript(streamResponse.document)

        // Fallback with specific Accept header
        if (streamUrl == null || !streamUrl.contains(".m3u8")) {
            val fallbackResponse = app.get(
                iframeUrl,
                referer = referer,
                headers = headers.plus("Accept" to "application/vnd.apple.mpegurl")
            )
            streamUrl = fallbackResponse.url.takeIf { it.contains(".m3u8") }
                ?: extractStreamUrlFromScript(fallbackResponse.document)
        }

        if (streamUrl?.contains(".m3u8") == true) {
            callback(
                ExtractorLink(
                    source = this.name,
                    name = "Strimsy Stream",
                    url = streamUrl,
                    referer = iframeUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true,
                    headers = headers
                )
            )
        }
    }

    private fun extractStreamUrlFromScript(document: Document): String? {
        val scripts = document.select("script")
        val streamScript = scripts.find { it.data().contains("src=") || it.data().contains("file:") || it.data().contains(".m3u8") }
            ?: return null

        val scriptData = streamScript.data()
        return when {
            scriptData.contains("eval(") -> {
                val unpacked = getAndUnpack(scriptData)
                unpacked.substringAfter("src=\"")?.substringBefore("\"")
                    ?: unpacked.substringAfter("file: \"")?.substringBefore("\"")
                    ?: unpacked.findM3u8Url()
            }
            else -> scriptData.substringAfter("src=\"")?.substringBefore("\"")
                ?: scriptData.substringAfter("file: \"")?.substringBefore("\"")
                ?: scriptData.findM3u8Url()
        } ?: document.selectFirst("source")?.attr("src")
    }

    private fun String.findM3u8Url(): String? {
        val regex = Regex("https?://[^\\s\"']+\\.m3u8(?:\\?[^\\s\"']*)?")
        return regex.find(this)?.value
    }

    // Helper to fix relative URLs
    private fun fixUrl(url: String): String {
        return if (url.startsWith("//")) "https:$url"
        else if (url.startsWith("/")) "$mainUrl$url"
        else if (!url.startsWith("http")) "$mainUrl/$url"
        else url
    }
}
