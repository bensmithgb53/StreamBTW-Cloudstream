package ben.smith53

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URL

class StrimsyExtractor : ExtractorApi() {
    override val mainUrl = "https://strimsy.top"
    override val name = "Strimsy"
    override val requiresReferer = true
    private val userAgent = "Mozilla/5.0 (Android 10; Mobile; rv:91.0) Gecko/91.0 Firefox/91.0"
    private val cfKiller = CloudflareKiller()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var currentUrl = url
        var currentReferer = referer ?: mainUrl
        val headers = mapOf("User-Agent" to userAgent, "Referer" to currentReferer)

        // Follow iframe chain up to 2 levels
        repeat(2) { level ->
            val resp = app.get(currentUrl, headers = headers, interceptor = cfKiller).body.string()
            val iframe = findStreamIframe(resp) ?: return@repeat

            // Resolve iframe URL
            currentUrl = if (iframe.startsWith("/")) "$mainUrl$iframe" else if (iframe.startsWith("//")) "https:$iframe" else iframe
            val parsedUrl = URL(currentUrl)
            currentReferer = "${parsedUrl.protocol}://${parsedUrl.host}"
        }

        // Fetch final player page
        val playerResp = app.get(currentUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to currentReferer), interceptor = cfKiller).body.string()
        val m3u8Url = extractM3u8(playerResp) ?: return

        // Return the stream link
        callback(
            ExtractorLink(
                source = name,
                name = name,
                url = m3u8Url,
                referer = currentReferer,
                quality = Qualities.Unknown.value,
                isM3u8 = true,
                headers = mapOf("User-Agent" to userAgent, "Referer" to currentReferer)
            )
        )
    }

    // Find the iframe likely containing the stream
    private fun findStreamIframe(html: String): String? {
        val iframeRegex = Regex("iframe[^>]+src=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        val iframes = iframeRegex.findAll(html).map { it.groupValues[1] }.toList()

        // Filter for stream-related iframes
        return iframes.firstOrNull { iframe ->
            !iframe.contains(Regex("chat|ads|banner|popup", RegexOption.IGNORE_CASE)) &&
            iframe.contains(Regex("live|embed|player|stream", RegexOption.IGNORE_CASE))
        }
    }

    // Extract .m3u8 from player page, including obfuscated JS
    private fun extractM3u8(html: String): String? {
        // Direct .m3u8 match
        val m3u8Regex = Regex("https?://[^\"']+\\.m3u8(?:\\?[^\"']*)?", RegexOption.IGNORE_CASE)
        var m3u8 = m3u8Regex.find(html)?.value
        if (m3u8 != null) return m3u8

        // Obfuscated eval (e.g., jointexploit.net style)
        val evalRegex = Regex("eval\\(function\\(p,a,c,k,e,d\\).*?'([^']+)'\\.split\\('|'\\)", RegexOption.DOT_MATCHES_ALL)
        val evalMatch = evalRegex.find(html)?.groupValues?.get(1) ?: return null
        val keys = evalMatch.split("|")
        val srcIndex = keys.indexOf("src").takeIf { it >= 0 } ?: return null
        val urlPattern = Regex("\\b$srcIndex\\s*=\\s*\"([^\"]+)\"")
        val urlParts = urlPattern.find(html)?.groupValues?.get(1)?.split("\\d+".toRegex()) ?: return null

        // Reconstruct URL from parts and keys
        m3u8 = urlParts.joinToString("") { part ->
            keys.getOrNull(part.toIntOrNull() ?: 0) ?: part
        }
        return m3u8.takeIf { it.contains(".m3u8") }
    }
}
