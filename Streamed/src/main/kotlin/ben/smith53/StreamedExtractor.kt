package ben.smith53.extractors

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class StreamedExtractor : ExtractorApi() {
    override val name = "StreamedExtractor"
    override val mainUrl = "https://streamed.su"
    override val requiresReferer = true
    private val fetchUrl = "https://embedstreams.top/fetch"
    private val cookieUrl = "https://fishy.streamed.su/api/event"
    private val proxyUrl = "https://owen-proxyts-97.deno.dev/proxy"
    private val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
    private val challengeBaseUrl = "https://challenges.cloudflare.com/cdn-cgi/challenge-platform/h/g"
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Content-Type" to "application/json",
        "Accept" to "application/vnd.apple.mpegurl, */*",
        "Origin" to "https://embedstreams.top",
        "Accept-Language" to "en-GB,en-US;q=0.9,en;q=0.8",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\""
    )
    private val cookieCache = mutableMapOf<String, String>()
    private var cfClearance: String? = null

    companion object {
        const val EXTRACTOR_TIMEOUT_SECONDS = 30
        const val EXTRACTOR_TIMEOUT_MILLIS = EXTRACTOR_TIMEOUT_SECONDS * 1000L
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        // This is the standard Cloudstream3 extractor interface
        // For now, return empty list as the provider handles extraction internally
        return emptyList()
    }

    suspend fun getUrl(
        streamUrl: String,
        streamId: String,
        source: String,
        streamNo: Int,
        language: String,
        isHd: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StreamedExtractor", "Starting extraction for: $streamUrl (ID: $streamId)")

        // Check server availability
        try {
            if (!app.head("https://streamed.su", timeout = EXTRACTOR_TIMEOUT_MILLIS).isSuccessful) {
                Log.e("StreamedExtractor", "streamed.su is unreachable")
                return false
            }
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Server check failed: ${e.message}")
            return false
        }

        // Fetch Cloudflare clearance
        cfClearance = null
        if (fetchCloudflareClearance(streamUrl)) {
            Log.d("StreamedExtractor", "Cloudflare clearance obtained: $cfClearance")
        } else {
            Log.w("StreamedExtractor", "Cloudflare clearance failed, proceeding without it")
        }

        // Fetch stream page cookies
        val streamResponse = try {
            app.get(
                streamUrl,
                headers = baseHeaders + (cfClearance?.let { mapOf("Cookie" to "cf_clearance=$it") } ?: emptyMap()),
                timeout = EXTRACTOR_TIMEOUT_MILLIS
            )
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Stream page fetch failed for $source/$streamNo: ${e.message}")
            return false
        }
        val streamCookies = streamResponse.cookies
        Log.d("StreamedExtractor", "Stream cookies for $source/$streamNo: $streamCookies")

        // Fetch event cookies
        val eventCookies = fetchEventCookies(streamUrl, streamUrl)
        Log.d("StreamedExtractor", "Event cookies for $source/$streamNo: $eventCookies")

        // Combine cookies
        val combinedCookies = buildString {
            if (streamCookies.isNotEmpty()) {
                append(streamCookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
            if (eventCookies.isNotEmpty()) {
                if (isNotEmpty()) append("; ")
                append(eventCookies)
            }
            cfClearance?.let {
                if (isNotEmpty()) append("; ")
                append("cf_clearance=$it")
            }
        }
        Log.d("StreamedExtractor", "Combined cookies for $source/$streamNo: $combinedCookies")

        // POST to fetch encrypted string
        val postData = mapOf(
            "source" to source,
            "id" to streamId,
            "streamNo" to streamNo.toString()
        )
        val embedReferer = "https://embedstreams.top/embed/$source/$streamId/$streamNo"
        val fetchHeaders = baseHeaders + mapOf(
            "Referer" to streamUrl,
            "Cookie" to combinedCookies
        )
        val encryptedResponse = try {
            val response = app.post(fetchUrl, headers = fetchHeaders, json = postData, timeout = EXTRACTOR_TIMEOUT_MILLIS)
            Log.d("StreamedExtractor", "Fetch response code for $source/$streamNo: ${response.code}")
            if (response.code != 200) {
                Log.e("StreamedExtractor", "Fetch failed with code: ${response.code}")
                return false
            }
            response.text.takeIf { it.isNotBlank() } ?: return false.also {
                Log.e("StreamedExtractor", "Empty encrypted response")
            }
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Fetch failed for $source/$streamNo: ${e.message}")
            return false
        }

        // Decrypt using Deno
        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        val decryptResponse = try {
            app.post(decryptUrl, json = decryptPostData, headers = mapOf("Content-Type" to "application/json"), timeout = EXTRACTOR_TIMEOUT_MILLIS)
                .parsedSafe<Map<String, String>>()
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Decryption request failed for $source/$streamNo: ${e.message}")
            return false
        }
        val decryptedPath = decryptResponse?.get("decrypted")?.takeIf { it.isNotBlank() } ?: return false.also {
            Log.e("StreamedExtractor", "Decryption failed or no 'decrypted' key")
        }

        // Construct m3u8 URL and send to proxy
        val m3u8BaseUrl = "https://rr.buytommy.top$decryptedPath"
        val proxyPostData = mapOf(
            "m3u8Url" to m3u8BaseUrl,
            "referer" to embedReferer,
            "cookies" to combinedCookies
        )
        val proxyResponse = try {
            val response = app.post(proxyUrl, json = proxyPostData, headers = baseHeaders, timeout = EXTRACTOR_TIMEOUT_MILLIS)
            Log.d("StreamedExtractor", "Proxy response code: ${response.code}")
            response.parsedSafe<Map<String, String>>()
                    } catch (e: Exception) {
            Log.e("StreamedExtractor", "Proxy request failed: ${e.message}")
            return false
        }
        val proxiedUrl = proxyResponse?.get("proxiedUrl")?.takeIf { it.isNotBlank() } ?: return false.also {
            Log.e("StreamedExtractor", "Proxy returned no valid URL")
        }

        // Pass proxied URL to Cloudstream3
        callback.invoke(
            newExtractorLink(
                source = "StreamedProxy",
                name = "$source Stream $streamNo ($language${if (isHd) ", HD" else ""})",
                url = proxiedUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = embedReferer
                this.quality = if (isHd) Qualities.P1080.value else Qualities.Unknown.value
                this.headers = baseHeaders
            }
        )
        Log.d("StreamedExtractor", "Proxied M3U8 URL added: $proxiedUrl")
        return true
    }

    private suspend fun fetchCloudflareClearance(streamUrl: String): Boolean {
        val turnstileUrl = "$challengeBaseUrl/turnstile/if/ov2/av0/rcv/4c1qj/0x4AAAAAAAkvKraQY_9hzpmB/auto/fbE/new/normal/auto/"
        val turnstileHeaders = baseHeaders + mapOf(
            "Referer" to streamUrl,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )
        val turnstileResponse = try {
            app.get(turnstileUrl, headers = turnstileHeaders, timeout = EXTRACTOR_TIMEOUT_MILLIS)
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Turnstile fetch failed: ${e.message}")
            return false
        }
        if (turnstileResponse.code != 200) {
            Log.e("StreamedExtractor", "Turnstile failed with code: ${turnstileResponse.code}")
            return false
        }
        val flowUrlMatch = Regex("""action="(/flow/ov1/[^"]+)"""").find(turnstileResponse.text)
        val flowUrl = flowUrlMatch?.groupValues?.get(1)?.let { "$challengeBaseUrl$it" } ?: return false.also {
            Log.e("StreamedExtractor", "Failed to extract flow URL")
        }
        val flowHeaders = baseHeaders + mapOf(
            "Referer" to turnstileUrl,
            "Content-Type" to "text/plain;charset=UTF-8",
            "Origin" to "https://challenges.cloudflare.com"
        )
        val flowResponse = try {
            app.post(flowUrl, headers = flowHeaders, data = mapOf(), timeout = EXTRACTOR_TIMEOUT_MILLIS)
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Flow POST failed: ${e.message}")
            return false
        }
        cfClearance = flowResponse.headers.filter { it.first == "Set-Cookie" }
            .map { it.second.split(";")[0] }
            .find { it.startsWith("cf_clearance=") }?.substringAfter("cf_clearance=")
        Log.d("StreamedExtractor", "Cloudflare clearance cookie: $cfClearance")
        return cfClearance != null
    }

    private suspend fun fetchEventCookies(pageUrl: String, referrer: String): String {
        cookieCache[pageUrl]?.let { return it }
        val payload = """{"n":"pageview","u":"$pageUrl","d":"streamed.su","r":"$referrer"}"""
        try {
            val response = app.post(
                cookieUrl,
                data = mapOf(),
                headers = baseHeaders + mapOf("Content-Type" to "text/plain"),
                requestBody = payload.toRequestBody("text/plain".toMediaType()),
                timeout = EXTRACTOR_TIMEOUT_MILLIS
            )
            val cookies = response.headers.filter { it.first == "Set-Cookie" }
                .map { it.second.split(";")[0] }
            val formattedCookies = listOf("_ddg8_", "_ddg10_", "_ddg9_", "_ddg1_")
                .mapNotNull { key -> cookies.find { it.startsWith(key) } }
                .joinToString("; ")
            if (formattedCookies.isNotEmpty()) {
                cookieCache[pageUrl] = formattedCookies
                Log.d("StreamedExtractor", "Cached cookies: $formattedCookies")
            }
            return formattedCookies
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Failed to fetch event cookies: ${e.message}")
            return ""
        }
    }
}