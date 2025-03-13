package ben.smith53

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile // Added import for SubtitleFile

class StrimsyExtractor : ExtractorApi() {
    override val mainUrl = "https://strimsy.top"
    override val name = "Strimsy"
    override val requiresReferer = false
    private val userAgent = "Mozilla/5.0 (Android 10; Mobile; rv:91.0) Gecko/91.0 Firefox/91.0"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extractorLink = extractVideo(url)
        if (extractorLink != null) {
            callback(extractorLink)
        }
        // Note: No subtitles are extracted in this implementation, so subtitleCallback is not invoked
    }

    private suspend fun extractVideo(url: String): ExtractorLink? {
        val headers = mapOf("User-Agent" to userAgent)
        
        // Fetch initial page
        val initialResp = app.get(url, headers = headers).body.string()
        
        // Find first iframe src
        val firstIframeSrc = Regex("src\\s*=\\s*\"([^\"]*)\"").find(initialResp)?.groupValues?.get(1)
            ?: return null
        val firstIframeUrl = if (firstIframeSrc.startsWith("http")) firstIframeSrc else "$mainUrl$firstIframeSrc"
        
        // Fetch first iframe page with Referer
        val firstIframeHeaders = headers + mapOf("Referer" to url)
        val firstIframeResp = app.get(firstIframeUrl, headers = firstIframeHeaders).body.string()
        
        // Find second iframe src
        val secondIframeSrc = Regex("src\\s*=\\s*\"([^\"]*)\"").find(firstIframeResp)?.groupValues?.get(1)
            ?: return null
        val secondIframeUrl = if (secondIframeSrc.startsWith("http")) secondIframeSrc else "$mainUrl$secondIframeSrc"
        
        // Fetch player page with Referer
        val playerHeaders = headers + mapOf("Referer" to firstIframeUrl)
        val playerResp = app.get(secondIframeUrl, headers = playerHeaders).body.string()
        
        // Extract m3u8 URL
        val m3u8Url = Regex("var\\s+playbackURL\\s*=\\s*\"([^\"]*)\"").find(playerResp)?.groupValues?.get(1)
            ?: return null
        
        // Return ExtractorLink
        return ExtractorLink(
            source = name, // Use 'source' instead of 'name' as first parameter
            name = name,
            url = m3u8Url,
            referer = secondIframeUrl,
            quality = Qualities.P720.value,
            isM3u8 = true,
            headers = headers
        )
    }
}
