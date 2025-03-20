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
        println("Step 1: Fetching PPV Land page: $url")
        val response = app.get(url, headers = headers, timeout = 10L).text // 10s timeout
        val doc = Jsoup.parse(response)
        println("Step 1: Page fetched, length: ${response.length}")

        // Extract the embed iframe URL
        val embedCode = doc.selectFirst("#embedcode")?.text()
        val embedUrl = Regex("src=\"(https://www\\.vidembed\\.re/stream/[^\"]+)\"").find(embedCode ?: "")?.groupValues?.get(1)
        if (embedUrl != null) { // e.g., https://www.vidembed.re/stream/bbd1a638-c947-4b66-bd91-56fdadf38451
            println("Step 2: Found embed URL: $embedUrl")
            val streamId = embedUrl.substringAfterLast("/")
            val initialM3u8Url = "https://eu02-hls.ppv.land/hls/$streamId/index.m3u8"

            val embedHeaders = mapOf(
                "User-Agent" to userAgent,
                "Referer" to embedUrl,
                "Accept" to "*/*",
                "Accept-Encoding" to "gzip, deflate, br, zstd",
                "Connection" to "keep-alive",
                "Origin" to "https://www.vidembed.re"
            )

            // Fetch the master .m3u8 with retries
            println("Step 3: Fetching master .m3u8: $initialM3u8Url")
            var m3u8Response = app.get(initialM3u8Url, headers = embedHeaders, timeout = 15L, allowRedirects = true)
            var attempts = 1
            while (!m3u8Response.isSuccessful && attempts < 3) {
                println("Step 3: Retry $attempts - Status: ${m3u8Response.code}")
                Thread.sleep(1000) // Wait 1s before retry
                m3u8Response = app.get(initialM3u8Url, headers = embedHeaders, timeout = 15L, allowRedirects = true)
                attempts++
            }

            val finalM3u8Url = m3u8Response.url
            println("Step 3: Master status: ${m3u8Response.code}, Final URL: $finalM3u8Url")
            println("Step 3: Master body preview: ${m3u8Response.text.take(200)}")

            if (m3u8Response.isSuccessful) {
                val m3u8Content = m3u8Response.text
                if (m3u8Content.contains("#EXT-X-STREAM-INF")) {
                    println("Step 4: Master playlist detected")
                    val variantUrl = Regex("https?://[^\\s]+\\.m3u8[^\\s]*").find(m3u8Content)?.value
                    if (variantUrl != null) {
                        println("Step 5: Found variant .m3u8: $variantUrl")
                        val variantResponse = app.get(variantUrl, headers = embedHeaders, timeout = 15L, allowRedirects = true)
                        println("Step 5: Variant status: ${variantResponse.code}, URL: ${variantResponse.url}")
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
                            println("Step 5: Failed to fetch variant - Status: ${variantResponse.code}")
                        }
                    } else {
                        println("Step 4: No variant .m3u8 found in master")
                    }
                }
                // Fallback to master
                println("Step 4: Using master .m3u8 as fallback")
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
                println("Step 3: Master fetch failed after retries - Status: ${m3u8Response.code}")
                throw Exception("Failed to fetch master .m3u8 - Status: ${m3u8Response.code}")
            }
        } else {
            println("Step 2: No embed URL found in HTML: ${response.take(500)}")
            throw Exception("No embed URL found in PPV Land page")
        }
    }
}
