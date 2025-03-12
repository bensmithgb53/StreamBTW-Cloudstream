package ben.smith53

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URL

class StrimsyExtractor : ExtractorApi() {
    override val mainUrl = "https://strimsy.top"
    override val name = "StrimsyExtractor"
    override val requiresReferer = true

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to (referer ?: mainUrl),
            "Origin" to mainUrl
        )

        println("Fetching main URL: $url")
        val initialResp = app.get(url, headers = headers)
        val iframeUrl = Regex("""iframe src="([^"]+)"""").find(initialResp.text)?.groupValues?.get(1)
            ?.let { fixUrl(it) }

        if (iframeUrl == null) {
            println("No iframe found.")
            return
        }

        println("Fetching iframe: $iframeUrl")
        val iframeResp = app.get(iframeUrl, headers = headers)
        val iframeText = iframeResp.text

        // Extract HLS stream URL from iframe response
        val m3u8Url = Regex("""https?:\/\/[^\s"']+\.m3u8[^\s"']*""").find(iframeText)?.groupValues?.get(0)

        if (m3u8Url == null) {
            println("No .m3u8 URL found.")
            return
        }

        println("Extracted M3U8 URL: $m3u8Url")

        callback(
            ExtractorLink(
                source = name,
                name = "Strimsy Stream",
                url = m3u8Url,
                referer = iframeUrl,
                quality = Qualities.Unknown.value,
                isM3u8 = true,
                headers = mapOf("Referer" to iframeUrl)
            )
        )
    }

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            !url.startsWith("http") -> "$mainUrl/$url"
            else -> url
        }
    }
}
