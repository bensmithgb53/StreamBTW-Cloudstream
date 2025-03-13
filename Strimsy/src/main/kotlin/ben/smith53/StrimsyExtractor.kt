package ben.smith53

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import android.util.Log
import android.util.Base64

class StrimsyExtractor : ExtractorApi() {
    override val mainUrl = "https://strimsy.top"
    override val name = "Strims"
    override val requiresReferer = true
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("StrimsyExtractor", "Extracting URL: $url with referer: $referer")
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to referer ?: mainUrl,
            "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8"
        )

        try {
            // Step 1: Fetch entry page (e.g., TerajiAkui.php?source=2)
            val resp = app.get(url, headers = headers).text
            Log.d("StrimsyExtractor", "Entry page length: ${resp.length}")

            // Extract source options
            val sources = Regex("href=\"\\?source=(\\d)\"").findAll(resp).map { it.groupValues[1] }.distinct()
            if (sources.toList().isEmpty()) {
                Log.w("StrimsyExtractor", "No sources found in entry page")
            }

            sources.forEach { source ->
                val sourceUrl = url.replace(Regex("source=\\d"), "source=$source")
                Log.d("StrimsyExtractor", "Processing source: $sourceUrl")

                // Step 2: Fetch iframe from entry page (simplified)
                val iframe1 = Regex("<iframe[^>]+src=\"([^\"]+)\"").find(resp)?.groupValues?.get(1)
                    ?: "live/skyaction.php"  // Default if regex fails
                val iframe1Url = if (iframe1.startsWith("http")) iframe1 else "$mainUrl/$iframe1"
                val iframe1Resp = app.get(iframe1Url, headers).text
                Log.d("StrimsyExtractor", "Iframe 1 length: ${iframe1Resp.length}")

                // Step 3: Fetch wiki.js (approximation)
                val scriptSrc = Regex("<script[^>]+src=?,?\"?([^\"\\s>]+wiki\\.js[^\"\\s>]*)\"?>").find(iframe1Resp)?.groupValues?.get(1)
                    ?: "//futuristicsfun.com/static/wiki.js"
                val scriptUrl = if (scriptSrc.startsWith("http")) scriptSrc else "https:$scriptSrc"
                val scriptResp = app.get(scriptUrl, headers).text
                Log.d("StrimsyExtractor", "Script length: ${scriptResp.length}")

                // Step 4: Extract wiki.php URL (simplified)
                val iframe2Match = Regex("document\\.write\\([^>]*src=\"([^\"]+)\"").find(scriptResp)?.groupValues?.get(1)
                    ?: "https://futuristicsfun.com/wiki.php?embedded=desktop&fid=skysact"
                val iframe2Url = iframe2Match.replace("'+ embedded +'", "desktop").replace("'+ fid +'", "skysact")
                val iframe2Resp = app.get(iframe2Url, headers).text
                Log.d("StrimsyExtractor", "Iframe 2 length: ${iframe2Resp.length}")

                // Step 5: Decode Base64 and build .m3u8 (fallback if dynamic fails)
                val m3u8Base64 = Regex("atob\\('([^']+)'\\)").find(iframe2Resp)?.groupValues?.get(1)
                val m3u8Url = if (m3u8Base64 != null) {
                    val decodedPath = String(Base64.decode(m3u8Base64, Base64.DEFAULT), Charsets.UTF_8)
                    "https://futuristicsfun.com$decodedPath"  // Initial guess
                } else {
                    "https://jf6.dunyapurkaraja.com:999/hls/skysact.m3u8?md5=An4iTl-10fBIUuutnxRooQ&expires=1741865043"
                }

                val link = ExtractorLink(
                    source = name,
                    name = "Strims Source $source",
                    url = m3u8Url,
                    referer = mainUrl,
                    isM3u8 = true,
                    headers = headers,
                    quality = Qualities.Unknown.value
                )
                Log.d("StrimsyExtractor", "Invoking callback with: $m3u8Url")
                callback(link)
            }
        } catch (e: Exception) {
            Log.e("StrimsyExtractor", "Extraction failed: ${e.message}")
            val fallbackUrl = "https://jf6.dunyapurkaraja.com:999/hls/skysact.m3u8?md5=An4iTl-10fBIUuutnxRooQ&expires=1741865043"
            callback(
                ExtractorLink(
                    name, "Strims Fallback", fallbackUrl, mainUrl, true, headers, Qualities.Unknown.value
                )
            )
        }
    }
}
