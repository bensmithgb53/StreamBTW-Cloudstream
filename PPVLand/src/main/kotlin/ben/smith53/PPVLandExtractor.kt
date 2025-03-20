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
        url: String, // e.g., https://ppv.land/live/uefa-nations-league-turkey-vs-hungary or https://ppv.land/live/1742487300/CBS
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to (referer ?: mainUrl)
        )

        // Fetch the PPV Land live stream page
        val response = app.get(url, headers = headers).text
        val doc = Jsoup.parse(response)

        // Extract the embed iframe URL from the modal
        val embedCode = doc.selectFirst("#embedcode")?.text()
        val embedUrl = Regex("src=\"(https://www\\.vidembed\\.re/stream/[^\"]+)\"").find(embedCode ?: "")?.groupValues?.get(1)
        if (embedUrl != null) { // e.g., https://www.vidembed.re/stream/bbd1a638-c947-4b66-bd91-56fdadf38451
            val streamId = embedUrl.substringAfterLast("/") // e.g., bbd1a638-c947-4b66-bd91-56fdadf38451
            val m3u8Url = "https://eu02-hls.ppv.land/hls/$streamId/index.m3u8" // Constructed URL

            // Verify the .m3u8 URL (optional, but ensures it’s live)
            val embedHeaders = mapOf(
                "User-Agent" to userAgent,
                "Referer" to embedUrl
            )
            val m3u8Response = app.get(m3u8Url, headers = embedHeaders, allowRedirects = true)
            if (m3u8Response.isSuccessful) {
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = m3u8Url,
                        referer = embedUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = true,
                        headers = mapOf("Referer" to embedUrl)
                    )
                )
                return
            } else {
                println("Failed to fetch .m3u8: $m3u8Url - Status: ${m3u8Response.code}")
            }
        }

        println("No embed URL found for $url")
        throw Exception("No .m3u8 URL found; embed URL missing or invalid.")
    }
}
