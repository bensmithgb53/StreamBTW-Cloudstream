package ben.smith53

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.Jsoup

class PPVLandExtractor : ExtractorApi() {
    override val mainUrl = "https://ppv.land"
    override val name = "PPVLand"
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

        // Fetch VidEmbed page
        val response = app.get(url, headers = headers).text
        val doc = Jsoup.parse(response)

        // Check if stream is not live yet
        if (doc.selectFirst("#notif")?.text() == "This stream is not live yet.") {
            return // Skip extraction, stream not available
        }

        // Look for .m3u8 in the response (assuming it appears when live)
        val m3u8Url = Regex("https?://[^\\s]+\\.m3u8").find(response)?.value
        if (m3u8Url != null) {
            callback(
                ExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8Url,
                    referer = url,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true,
                    headers = mapOf("Referer" to url)
                )
            )
            return
        }

        // Fallback: Check JW Player script or additional requests
        val scriptContent = doc.select("script").map { it.data() }.joinToString("\n")
        val fallbackM3u8 = Regex("https?://[^\\s]+\\.m3u8").find(scriptContent)?.value
        if (fallbackM3u8 != null) {
            callback(
                ExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = fallbackM3u8,
                    referer = url,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true,
                    headers = mapOf("Referer" to url)
                )
            )
            return
        }

        // If still not found, the stream might not be live or requires further inspection
        throw Exception("No .m3u8 URL found; stream may not be live yet.")
    }
}
