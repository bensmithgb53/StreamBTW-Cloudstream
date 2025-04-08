package ben.smith53

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import android.util.Log

class StreamedExtractor {
    private val fetchUrl = "https://embedstreams.top/fetch"
    private val cookieUrl = "https://fishy.streamed.su/api/event"
    private val baseUrl = "https://rr.buytommy.top"
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Accept" to "*/*",
        "Origin" to "https://embedstreams.top"
    )

    suspend fun getUrl(
        streamUrl: String,
        matchId: String,
        source: String,
        streamNo: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StreamedExtractor", "Starting extraction for: $streamUrl")

        // Step 1: Fetch cookies from fishy.streamed.su/api/event
        val cookieHeaders = baseHeaders + mapOf(
            "Referer" to "https://embedstreams.top/",
            "Content-Type" to "text/plain"
        )
        Log.d("StreamedExtractor", "Fetching cookies from: $cookieUrl with headers: $cookieHeaders")
        val cookieResponse = try {
            app.post(cookieUrl, headers = cookieHeaders, data = "", timeout = 15)
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Cookie fetch failed: ${e.message}")
            return false
        }
        if (cookieResponse.code != 202) {
            Log.e("StreamedExtractor", "Cookie fetch failed with code: ${cookieResponse.code}")
            return false
        }
        val cookies = cookieResponse.cookies
        val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        Log.d("StreamedExtractor", "Cookies fetched: $cookieString")

        // Step 2: Fetch stream page (optional, for additional cookies)
        val streamResponse = try {
            app.get(streamUrl, headers = baseHeaders + mapOf("Cookie" to cookieString), timeout = 15)
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Stream page fetch failed: ${e.message}")
            return false
        }
        val streamCookies = streamResponse.cookies
        val combinedCookieString = (cookies + streamCookies).entries.joinToString("; ") { "${it.key}=${it.value}" }
        Log.d("StreamedExtractor", "Combined cookies: $combinedCookieString")

        // Step 3: Fetch encrypted stream data
        val postData = mapOf(
            "source" to source,
            "id" to matchId,
            "streamNo" to streamNo.toString()
        )
        val fetchHeaders = baseHeaders + mapOf(
            "Referer" to streamUrl,
            "Cookie" to combinedCookieString,
            "Content-Type" to "application/json"
        )
        Log.d("StreamedExtractor", "Fetching with data: $postData and headers: $fetchHeaders")
        val encryptedResponse = try {
            val response = app.post(fetchUrl, headers = fetchHeaders, json = postData, timeout = 15)
            Log.d("StreamedExtractor", "Fetch response code: ${response.code}")
            response.text
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Fetch failed: ${e.message}")
            return false
        }
        Log.d("StreamedExtractor", "Encrypted response: $encryptedResponse")

        // Step 4: Decrypt the response
        val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        val decryptResponse = try {
            app.post(decryptUrl, json = decryptPostData, headers = mapOf("Content-Type" to "application/json"))
                .parsedSafe<Map<String, String>>()
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Decryption request failed: ${e.message}")
            return false
        }
        val decryptedPath = decryptResponse?.get("decrypted") ?: return false.also {
            Log.e("StreamedExtractor", "Decryption failed or no 'decrypted' key")
        }
        Log.d("StreamedExtractor", "Decrypted path: $decryptedPath")

        // Step 5: Build and test .m3u8 URL
        val m3u8Url = "$baseUrl$decryptedPath"
        val m3u8Headers = baseHeaders + mapOf(
            "Referer" to "https://embedstreams.top",
            "Cookie" to combinedCookieString
        )
        Log.d("StreamedExtractor", "Adding M3U8 URL for playback: $m3u8Url with headers: $m3u8Headers")
        callback.invoke(
            newExtractorLink(
                source = "Streamed",
                name = "$source Stream $streamNo",
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "https://embedstreams.top"
                this.quality = Qualities.Unknown.value
                this.headers = m3u8Headers
                this.extractorData = "buffer=30000"
            }
        )
        return true
    }
}