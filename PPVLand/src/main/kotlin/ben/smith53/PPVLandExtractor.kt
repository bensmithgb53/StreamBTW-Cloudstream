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
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"

    override suspend fun getUrl(
        url: String, // e.g., https://ppv.land/live/uefa-nations-league-turkey-vs-hungary
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to (referer ?: mainUrl),
            "Accept" to "*/*",
            "Accept-Encoding" to "gzip, deflate, br, zstd",
            "Connection" to "keep-alive"
        )

        // Fetch the PPV Land stream page
        println("Fetching PPV Land page: $url")
        val response = app.get(url, headers = headers).text
        val doc = Jsoup.parse(response)

        // Extract the embed iframe URL
        val embedCode = doc.selectFirst("#embedcode")?.text()
        val embedUrl = Regex("src=\"(https://www\\.vidembed\\.re/stream/[^\"]+)\"").find(embedCode ?: "")?.groupValues?.get(1)
        if (embedUrl != null) { // e.g., https://www.vidembed.re/stream/bbd1a638-c947-4b66-bd91-56fdadf38451
            println("Found embed URL: $embedUrl")
            val streamId = embedUrl.substringAfterLast("/") // e.g., bbd1a638-c947-4b66-bd91-56fdadf38451
            val initialM3u8Url = "https://eu02-hls.ppv.land/hls/$streamId/index.m3u8"

            val embedHeaders = mapOf(
                "User-Agent" to userAgent,
                "Referer" to embedUrl,
                "Accept" to "*/*",
                "Accept-Encoding" to "gzip, deflate, br, zstd",
                "Connection" to "keep-alive",
                "Origin" to "https://www.vidembed.re"
                // Note: cf_clearance cookie omitted as it’s session-specific; may need dynamic fetching if 403 persists
            )

            // Fetch the master .m3u8
            println("Fetching master .m3u8: $initialM3u8Url")
            val m3u8Response = app.get(initialM3u8Url, headers = embedHeaders, allowRedirects = true)
            val finalM3u8Url = m3u8Response.url
            println("Master status: ${m3u8Response.code}, Final URL: $finalM3u8Url")
            println("Master body preview: ${m3u8Response.text.take(200)}")

            if (m3u8Response.isSuccessful) {
                val m3u8Content = m3u8Response.text
                // Check if it's a master playlist
                if (m3u8Content.contains("#EXT-X-STREAM-INF")) {
                    // Extract variant .m3u8
                    val variantUrl = Regex("https?://[^\\s]+\\.m3u8[^\\s]*").find(m3u8Content)?.value
                    if (variantUrl != null) {
                        println("Found variant .m3u8: $variantUrl")
                        val variantResponse = app.get(variantUrl, headers = embedHeaders, allowRedirects = true)
                        if (variantResponse.isSuccessful) {
                            callback(
                                ExtractorLink(
                                    source = this.name,
                                    name = this.name,
                                    url = variantResponse.url,
                                    referer = embedUrl,
                                    quality = Qualities.Unknown.value,
                                    isM3u8 = true,
                                    headers = mapOf("Referer" to embedUrl)
                                )
                            )
                            return
                        } else {
                            println("Failed to fetch variant .m3u8 - Status: ${variantResponse.code}")
                        }
                    } else {
                        println("No variant .m3u8 found in master")
                    }
                }
                // Fallback to master if no variants or it's a media playlist
                println("Using master .m3u8 as fallback")
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = finalM3u8Url,
                        referer = embedUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = true,
                        headers = mapOf("Referer" to embedUrl)
                    )
                )
                return
            } else {
                println("Failed to fetch master .m3u8 - Status: ${m3u8Response.code}")
                throw Exception("Master .m3u8 fetch failed; status: ${m3u8Response.code}")
            }
        } else {
            println("No embed URL found in HTML: ${response.take(500)}")
            throw Exception("No embed URL found in PPV Land page")
        }
    }
}
