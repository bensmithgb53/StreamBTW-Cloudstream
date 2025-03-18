package ben.smithgb53

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.Jsoup

class StrimsyExtractor : ExtractorApi() {
    override val name = "Strimsy"
    override val mainUrl = "https://strimsy.top"
    override val requiresReferer = true
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    private val cookie = "PHPSESSID=sr2ufaf1h3ha63dge62ahob"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("Strimsy", "Extractor called with URL: $url, Referer: $referer")
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to (referer ?: url),
            "Cookie" to cookie
        )
        Log.d("Strimsy", "Headers: $headers")

        try {
            // If the URL is the main page, parse it for events
            if (url == mainUrl) {
                Log.d("Strimsy", "Parsing main page for events")
                val mainResponse = app.get(url, headers = headers).text
                Log.d("Strimsy", "Main page response length: ${mainResponse.length}")
                val eventUrls = parseMainPage(mainResponse)
                Log.d("Strimsy", "Found ${eventUrls.size} events")

                eventUrls.forEach { eventUrl ->
                    Log.d("Strimsy", "Processing event URL: $eventUrl")
                    extractStream(eventUrl, headers, callback)
                }
            } else {
                // Direct stream URL
                Log.d("Strimsy", "Processing direct stream URL")
                extractStream(url, headers, callback)
            }
        } catch (e: Exception) {
            Log.e("Strimsy", "Error in getUrl: ${e.message}", e)
        }
    }

    private fun parseMainPage(html: String): List<String> {
        Log.d("Strimsy", "Parsing HTML for event URLs")
        val urls = mutableListOf<String>()
        val doc = Jsoup.parse(html)
        val tables = doc.select("div.tabcontent table.ramowka")
        tables.forEach { table ->
            table.select("tr td a[href]").forEach { link ->
                val eventUrl = "$mainUrl${link.attr("href")}"
                val eventName = link.text().trim()
                Log.d("Strimsy", "Found event: $eventName - $eventUrl")
                urls.add(eventUrl)
            }
        }
        return urls
    }

    private suspend fun extractStream(url: String, headers: Map<String, String>, callback: (ExtractorLink) -> Unit) {
        try {
            Log.d("Strimsy", "Fetching stream page: $url")
            val response = app.get(url, headers = headers).text
            Log.d("Strimsy", "Stream page response length: ${response.length}")

            // Extract stream ID
            val streamIdMatch = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                .find(response)
            if (streamIdMatch == null) {
                Log.w("Strimsy", "No stream ID found in $url")
                return
            }
            val streamId = streamIdMatch.groupValues[0]
            Log.d("Strimsy", "Found stream ID: $streamId")

            // Hypothesize API call
            val apiUrl = "$url?type=live"
            Log.d("Strimsy", "Fetching API URL: $apiUrl")
            val apiResponse = app.get(apiUrl, headers = headers).text
            Log.d("Strimsy", "API response length: ${apiResponse.length}")

            // Extract m3u8 path
            val m3u8PathMatch = Regex("/[a-zA-Z0-9\\-]+\\.m3u8").find(apiResponse)
            if (m3u8PathMatch == null) {
                Log.w("Strimsy", "No m3u8 path found in API response for $url")
                return
            }
            val m3u8Path = m3u8PathMatch.groupValues[0]
            Log.d("Strimsy", "Found m3u8 path: $m3u8Path")

            // Construct final m3u8 URL
            val baseCdn = "https://ve16o28z6o6dszgkty3jni6ulo3ba9jt.global.ssl.fastly.net"
            val finalUrl = "$baseCdn/v3/fragment/$streamId/tracks-v1a1/$m3u8Path"
            Log.d("Strimsy", "Constructed m3u8 URL: $finalUrl")

            // Verify and callback
            callback.invoke(
                ExtractorLink(
                    name,
                    name,
                    finalUrl,
                    referer ?: url,
                    true,
                    Qualities.Unknown.value,
                    headers = headers
                )
            )
            Log.d("Strimsy", "ExtractorLink callback invoked for $finalUrl")
        } catch (e: Exception) {
            Log.e("Strimsy", "Error extracting stream from $url: ${e.message}", e)
        }
    }
}
