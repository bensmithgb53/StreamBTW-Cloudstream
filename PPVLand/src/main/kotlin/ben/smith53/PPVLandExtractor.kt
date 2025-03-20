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
                "Cookie" to "cf_clearance=R069ic2bAS4UNiv32McrpDQk7wa1A39JXSELp57JyYI-1742497570-1.2.1.1-cI5WX2wC2MAzE9BeikYit2VsCpv3R2yrZ9iw95zpZcgT_J6S66MC.cXYgq99TRCBhzrTWtxm5DjvU.wkphHVPjJmkTHIWVBkfW4w258qhtvTWiLTF6ZDuNoYbE_Bbu1gyur8m.wWprE_3kvnjqEeW5ql3w4v_E8pmlGT3G663mJbyiFLFdHolCEhAv61TN2dSuzfrGm9JPUAURv7mBAby2QtSRiLUW1wrs5S2dhn6xnRfs5w91U1ulh9K55D87dgZrENuxLPshER.34ADz8.VFQ3M..RMl6aBxi.A0i1fapGF10oQrwPBU36xH26KuzDkPDRP.kTlvc9tAQxxxi0mt8RGtxyzOaAou.1afbkjxk",
                "Origin" to "https://www.vidembed.re"
            )

            // Fetch the master .m3u8
            println("Fetching master .m3u8: $initialM3u8Url")
            val m3u8Response = app.get(initialM3u8Url, headers = embedHeaders, allowRedirects = true)
            val finalM3u8Url = m3u8Response.url
            println("Master status: ${m3u8Response.code}, Final URL: $finalM3u8Url")
            println("Master body preview: ${m3u8Response.text.take(200)}")

            if (m3u8Response.isSuccessful) {
                // Parse the master .m3u8 for variant playlists
                val m3u8Content = m3u8Response.text
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
                    // Use the master if no variants found
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
                }
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
