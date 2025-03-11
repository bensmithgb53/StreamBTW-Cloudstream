package ben.smith53

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
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
            val iframeBase = document.selectFirst("iframe")?.attr("src")?.let { fixUrl(it) }
                ?: return

            val qualityLinks = document.select("font a").mapIndexed { index, link ->
                val qualityName = when (index) {
                    0 -> "HD"
                    1 -> "Full HD 1"
                    2 -> "Full HD 2"
                    3 -> "Full HD 3"
                    else -> "Stream ${index + 1}"
                }
                val sourceParam = link.attr("href").substringAfter("?source=").takeIf { it.isNotEmpty() } 
                    ?: (index + 1).toString()
                Pair(qualityName, "$url?source=$sourceParam")
            }

            qualityLinks.forEach { pair ->
                try {
                    extractStream(pair.second, iframeBase, pair.first, headers, callback)
                } catch (e: Exception) {
                    // Skip failed quality options silently
                }
            }
        } catch (e: Exception) {
            // Handle general extraction failure silently
        }
    }

    private suspend fun extractStream(
        qualityUrl: String,
        iframeBase: String,
        qualityName: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        val qualityResponse = app.get(qualityUrl, referer = qualityUrl, headers = headers)
        val qualityIframe = qualityResponse.document.selectFirst("iframe")?.attr("src")?.let { fixUrl(it) } 
            ?: iframeBase

        val streamResponse = app.get(
            qualityIframe,
            referer = qualityUrl,
            headers = headers,
            allowRedirects = true
        )

        var finalStreamUrl = streamResponse.url.takeIf { it.contains(".m3u8") }
            ?: extractStreamUrlFromScript(streamResponse.document)

        if (finalStreamUrl == null || !finalStreamUrl.contains(".m3u8")) {
            val deeperResponse = app.get(
                qualityIframe,
                referer = qualityUrl,
                headers = headers.plus("Accept" to "application/vnd.apple.mpegurl")
            )
            finalStreamUrl = deeperResponse.url.takeIf { it.contains(".m3u8") }
                ?: extractStreamUrlFromScript(deeperResponse.document)
        }

        if (finalStreamUrl?.contains(".m3u8") == true) {
            callback(
                ExtractorLink(
                    source = this.name,
                    name = qualityName,
                    url = finalStreamUrl,
                    referer = qualityIframe,
                    quality = when {
                        qualityName.contains("Full HD") -> Qualities.P1080.value
                        qualityName.contains("HD") -> Qualities.P720.value
                        else -> Qualities.Unknown.value
                    },
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

    private fun fixUrl(url: String): String {
        return if (url.startsWith("//")) "https:$url"
        else if (url.startsWith("/")) "$mainUrl$url"
        else if (!url.startsWith("http")) "$mainUrl/$url"
        else url
    }
}
