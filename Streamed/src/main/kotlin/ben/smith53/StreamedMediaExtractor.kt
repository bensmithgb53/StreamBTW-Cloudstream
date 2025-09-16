package ben.smith53

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class StreamedMediaExtractor {
    private val fetchM3u8Url = "https://bensmithgb53-decrypt-13.deno.dev/fetch-m3u8"
    private val cookieUrl = "https://fishy.streamed.pk/api/event"
    private val challengeBaseUrl = "https://challenges.cloudflare.com/cdn-cgi/challenge-platform/h/g"
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Content-Type" to "application/json",
        "Accept" to "application/vnd.apple.mpegurl, */*",
        "Origin" to "https://embedstreams.top",
        "Accept-Language" to "en-GB,en-US;q=0.9,en;q=0.8",
        "Sec-Ch-Ua" to "\"Not A(Brand\";v=\"8\", \"Chromium\";v=\"132\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\""
    )
    private val fallbackDomains = listOf("rr.buytommy.top", "p2-panel.streamed.pk", "streamed.pk", "embedstreams.top", "ann.embedstreams.top")
    private val cookieCache = mutableMapOf<String, String>()
    private var cfClearance: String? = null

    companion object {
        const val EXTRACTOR_TIMEOUT_SECONDS = 30
        const val EXTRACTOR_TIMEOUT_MILLIS = EXTRACTOR_TIMEOUT_SECONDS * 1000L
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
        Log.d("StreamedMediaExtractor", "Starting extraction for: $streamUrl (ID: $streamId)")

        // Check server availability
        try {
            if (!app.head("https://streamed.su", timeout = EXTRACTOR_TIMEOUT_MILLIS).isSuccessful) {
                Log.e("StreamedMediaExtractor", "streamed.su is unreachable")
                return false
            }
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Server check failed: ${e.message}")
            return false
        }

        // Fetch Cloudflare clearance
        cfClearance = null
        if (fetchCloudflareClearance(streamUrl)) {
            Log.d("StreamedMediaExtractor", "Cloudflare clearance obtained: $cfClearance")
        } else {
            Log.w("StreamedMediaExtractor", "Cloudflare clearance failed, proceeding without it")
        }

        // Fetch stream page cookies
        val streamResponse = try {
            app.get(
                streamUrl,
                headers = baseHeaders + (cfClearance?.let { mapOf("Cookie" to "cf_clearance=$it") } ?: emptyMap()),
                timeout = EXTRACTOR_TIMEOUT_MILLIS
            )
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Stream page fetch failed for $source/$streamNo: ${e.message}")
            return false
        }
        val streamCookies = streamResponse.cookies
        Log.d("StreamedMediaExtractor", "Stream cookies for $source/$streamNo: $streamCookies")

        // Fetch event cookies
        val eventCookies = fetchEventCookies(streamUrl, streamUrl)
        Log.d("StreamedMediaExtractor", "Event cookies for $source/$streamNo: $eventCookies")

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
        if (combinedCookies.isEmpty()) {
            Log.w("StreamedMediaExtractor", "No cookies obtained for $source/$streamNo")
        }
        Log.d("StreamedMediaExtractor", "Combined cookies for $source/$streamNo: $combinedCookies")

        // Fetch M3U8 URL from Deno
        val fetchPostData = mapOf(
            "channelPart1" to source,
            "channelPart2" to streamId,
            "streamNo" to streamNo.toString()
        )
        val embedReferer = "https://embedstreams.top/embed/$source/$streamId/$streamNo"
        val fetchHeaders = baseHeaders + mapOf("Referer" to streamUrl)
        Log.d("StreamedMediaExtractor", "Fetching M3U8 with data: $fetchPostData and headers: $fetchHeaders")

        val m3u8Response = try {
            val response = app.post(fetchM3u8Url, headers = fetchHeaders, json = fetchPostData, timeout = EXTRACTOR_TIMEOUT_MILLIS)
            Log.d("StreamedMediaExtractor", "Fetch M3U8 response code for $source/$streamNo: ${response.code}")
            if (response.code != 200) {
                Log.e("StreamedMediaExtractor", "Fetch M3U8 failed for $source/$streamNo with code: ${response.code}, body: ${response.text.take(100)}")
                return false
            }
            response.parsedSafe<Map<String, String>>()?.get("m3u8")?.takeIf { it.isNotBlank() } ?: return false.also {
                Log.e("StreamedMediaExtractor", "Empty or invalid M3U8 response for $source/$streamNo")
            }
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Fetch M3U8 failed for $source/$streamNo: ${e.message}")
            return false
        }
        Log.d("StreamedMediaExtractor", "M3U8 URL for $source/$streamNo: $m3u8Response")

        // Test M3U8 with fallbacks
        var linkFound = false
        for (domain in fallbackDomains) {
            try {
                val testUrl = m3u8Response.replace("rr.buytommy.top", domain)
                Log.d("StreamedMediaExtractor", "Testing M3U8 URL: $testUrl")
                val testResponse = app.get(testUrl, headers = baseHeaders + mapOf(
                    "Referer" to embedReferer,
                    "Cookie" to combinedCookies
                ), timeout = EXTRACTOR_TIMEOUT_MILLIS)
                Log.d("StreamedMediaExtractor", "M3U8 response code for $domain: ${testResponse.code}, body: ${testResponse.text.take(100)}")
                if (testResponse.code == 200 && testResponse.text.contains("#EXTM3U")) {
                    callback.invoke(
                        newExtractorLink(
                            source = "Streamed",
                            name = "$source Stream $streamNo ($language${if (isHd) ", HD" else ""})",
                            url = testUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = embedReferer
                            this.quality = if (isHd) Qualities.P1080.value else Qualities.Unknown.value
                            this.headers = baseHeaders + mapOf(
                                "Referer" to embedReferer,
                                "Cookie" to combinedCookies
                            )
                        }
                    )
                    Log.d("StreamedMediaExtractor", "M3U8 URL added for $source/$streamNo: $testUrl")
                    linkFound = true
                    break
                }
            } catch (e: Exception) {
                Log.e("StreamedMediaExtractor", "M3U8 test failed for $domain: ${e.message}")
            }
        }

        // Fallback: Add original URL if no test succeeds
        if (!linkFound) {
            callback.invoke(
                newExtractorLink(
                    source = "Streamed",
                    name = "$source Stream $streamNo ($language${if (isHd) ", HD" else ""})",
                    url = m3u8Response,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = embedReferer
                    this.quality = if (isHd) Qualities.P1080.value else Qualities.Unknown.value
                    this.headers = baseHeaders + mapOf(
                        "Referer" to embedReferer,
                        "Cookie" to combinedCookies
                    )
                }
            )
            Log.d("StreamedMediaExtractor", "Original M3U8 URL added as fallback for $source/$streamNo: $m3u8Response")
            linkFound = true
        }
        return linkFound
    }

    private suspend fun fetchCloudflareClearance(url: String): Boolean {
        val clearanceUrl = "https://bensmithgb53-decrypt-13.deno.dev/cloudflare-clearance"
        val requestBody = mapOf("url" to url).toJson().toRequestBody("application/json".toMediaType())
        return try {
            val response = app.post(clearanceUrl, requestBody = requestBody, timeout = EXTRACTOR_TIMEOUT_MILLIS)
            if (response.isSuccessful) {
                val clearance = response.parsedSafe<Map<String, String>>()?.get("cf_clearance")
                if (!clearance.isNullOrBlank()) {
                    cfClearance = clearance
                    true
                } else {
                    Log.e("StreamedMediaExtractor", "Cloudflare clearance response was successful but cf_clearance is empty.")
                    false
                }
            } else {
                Log.e("StreamedMediaExtractor", "Cloudflare clearance request failed with code: ${response.code}, body: ${response.text}")
                false
            }
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Cloudflare clearance request failed: ${e.message}")
            false
        }
    }

    private suspend fun fetchEventCookies(streamUrl: String, referer: String): String {
        val eventPostData = "{}".toRequestBody("application/json".toMediaType())
        val eventHeaders = baseHeaders + mapOf("Referer" to referer)
        return try {
            val response = app.post(cookieUrl, headers = eventHeaders, requestBody = eventPostData, timeout = EXTRACTOR_TIMEOUT_MILLIS)
            response.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Failed to fetch event cookies: ${e.message}")
            ""
        }
    }
}




fun Map<String, String>.toJson(): String {
    return com.lagradost.cloudstream3.utils.AppUtils.mapper.writeValueAsString(this)
}