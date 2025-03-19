package ben.smith53

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

class StrimsyExtractor : ExtractorApi() {
    override val name = "StrimsyExtractor"
    override val mainUrl = "https://strimsy.top"
    override val requiresReferer = true

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val response = app.get(url, headers = headers, referer = referer)
        val document = response.document

        // Extract playbackURL from JavaScript
        val scriptText = document.select("script").joinToString("\n") { it.html() }
        val playbackUrlRegex = Regex("var playbackURL\\s*=\\s*\"(https?://[^\"']+\\.m3u8[^\"']*)\"")
        val match = playbackUrlRegex.find(scriptText)
        val masterM3u8Url = match?.groupValues?.get(1)

        if (masterM3u8Url != null) {
            // Fetch the master m3u8 playlist to find variant playlists
            val m3u8Response = app.get(masterM3u8Url, headers = headers, referer = url).text
            val variantRegex = Regex("https?://[^\\s\"']+\\.m3u8(?:\\?[^\\s\"']+)?")
            val variantUrls = variantRegex.findAll(m3u8Response).map { it.value }.toList()

            if (variantUrls.isNotEmpty()) {
                // Prefer a variant with "mono.m3u8" if available, otherwise take the first one
                val selectedVariant = variantUrls.firstOrNull { it.contains("mono.m3u8") } ?: variantUrls.first()
                return listOf(
                    ExtractorLink(
                        source = name,
                        name = "$name (Live)",
                        url = selectedVariant,
                        referer = url,
                        quality = -1, // Use -1 for unknown quality
                        isM3u8 = true,
                        headers = headers
                    )
                )
            }

            // If no variants are found, try using the master m3u8 directly
            return listOf(
                ExtractorLink(
                    source = name,
                    name = "$name (Live)",
                    url = masterM3u8Url,
                    referer = url,
                    quality = -1,
                    isM3u8 = true,
                    headers = headers
                )
            )
        }

        // Fallback: Try to find m3u8 URLs directly in the page source
        val m3u8Regex = Regex("https?://[^\\s\"']+\\.m3u8(?:\\?[^\\s\"']+)?")
        val m3u8Urls = m3u8Regex.findAll(response.text).map { it.value }.toList()

        if (m3u8Urls.isNotEmpty()) {
            return m3u8Urls.map { m3u8Url ->
                ExtractorLink(
                    source = name,
                    name = "$name (Live)",
                    url = m3u8Url,
                    referer = url,
                    quality = -1,
                    isM3u8 = true,
                    headers = headers
                )
            }
        }

        return null
    }
}
