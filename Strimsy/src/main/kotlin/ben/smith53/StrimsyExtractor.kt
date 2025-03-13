package ben.smith53

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class StrimsyExtractor : ExtractorApi() {
    override val mainUrl = "https://strimsy.top"
    override val name = "Strimsy"
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
            "Referer" to (referer ?: mainUrl),
            "User-Agent" to userAgent
        )

        try {
            // Fetch the event page (e.g., AstonVillaClubBrugge.php)
            val matchPage = app.get(url, headers = headers).text
            val iframeSrc = Regex("""iframe src=["'](/live/[^"']+)["']""")
                .find(matchPage)?.groupValues?.get(1)
                ?: return // Exit if iframe src not found

            val iframeUrl = "$mainUrl$iframeSrc"

            // Fetch the iframe content (e.g., /live/tnt1.php)
            val iframeResponse = app.get(iframeUrl, headers = headers).text
            val m3u8Url = Regex("""playbackURL = ["']([^"']+)["']""")
                .find(iframeResponse)?.groupValues?.get(1)
                ?: return // Exit if m3u8 URL not found

            callback(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Url,
                    referer = iframeUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true,
                    headers = mapOf(
                        "Referer" to iframeUrl,
                        "Origin" to mainUrl,
                        "User-Agent" to userAgent
                    )
                )
            )
        } catch (e: Exception) {
            // Replace logError with a simple println for now
            println("StrimsyExtractor failed: ${e.message}")
        }
    }
}
