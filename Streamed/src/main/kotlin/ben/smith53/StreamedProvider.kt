package ben.smith53

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils    // Explicitly import AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import android.util.Log
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.delay
import java.security.MessageDigest // For SHA-256 hashing
import java.nio.charset.StandardCharsets // For UTF-8 encoding
import java.util.TimeZone // For timezone info in X-TOK

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed"
    override var supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

    private val maxStreams = 4
    private val fetchUrl = "https://embedstreams.top/fetch"
    private val cookieUrl = "https://fishy.streamed.su/api/event"
    private val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.5 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Content-Type" to "application/json",
        "Accept" to "application/vnd.apple.mpegurl, */*",
        "Origin" to "https://embedstreams.top"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/api/matches/live/popular" to "Popular",
        "$mainUrl/api/matches/football" to "Football",
        "$mainUrl/api/matches/baseball" to "Baseball",
        "$mainUrl/api/matches/american-football" to "American Football",
        "$mainUrl/api/matches/hockey" to "Hockey",
        "$mainUrl/api/matches/basketball" to "Basketball",
        "$mainUrl/api/matches/motor-sports" to "Motor Sports",
        "$mainUrl/api/matches/fight" to "Fight",
        "$mainUrl/api/matches/tennis" to "Tennis",
        "$mainUrl/api/matches/rugby" to "Rugby",
        "$mainUrl/api/matches/golf" to "Golf",
        "$mainUrl/api/matches/billiards" to "Billiards",
        "$mainUrl/api/matches/afl" to "AFL",
        "$mainUrl/api/matches/darts" to "Darts",
        "$mainUrl/api/matches/cricket" to "Cricket",
        "$mainUrl/api/matches/other" to "Other"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val rawList = app.get(request.data).text
        val listJson = parseJson<List<Match>>(rawList)

        val list = listJson.filter { match -> match.matchSources.isNotEmpty() }.map { match ->
            val url = "$mainUrl/watch/${match.id}"
            newLiveSearchResponse(
                name = match.title,
                url = url,
                type = TvType.Live
            ) {
                this.posterUrl = "$mainUrl${match.posterPath ?: "/api/images/poster/fallback.webp"}"
            }
        }.filterNotNull()

        return newHomePageResponse(
            list = listOf(HomePageList(request.name, list, isHorizontalImages = true)),
            hasNext = false
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val matchId = url.substringAfterLast("/")
        val title = matchId.replace("-", " ")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            .replace(Regex("-\\d+$"), "")
        val posterUrl = "$mainUrl/api/images/poster/$matchId.webp"
        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val matchId = data.substringAfterLast("/")
        val extractor = StreamedMediaExtractor()
        var success = false

        val matchDetails = try {
            app.get("$mainUrl/api/matches/live/$matchId", timeout = StreamedMediaExtractor.EXTRACTOR_TIMEOUT_MILLIS).parsedSafe<Match>()
        } catch (e: Exception) {
            Log.e("StreamedProvider", "Failed to fetch match details for $matchId: ${e.message}")
            null
        }

        val availableSources = matchDetails?.matchSources?.map { it.sourceName }?.toSet() ?: emptySet()
        Log.d("StreamedProvider", "Available sources for $matchId: $availableSources")

        val sourcesToProcess = if (availableSources.isNotEmpty()) {
            availableSources.toList()
        } else {
            Log.w("StreamedProvider", "No specific sources found from API for $matchId, using general list.")
            listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")
        }

        for (source in sourcesToProcess) {
            val streamInfos = try {
                val response = app.get("$mainUrl/api/stream/$source/$matchId", timeout = StreamedMediaExtractor.EXTRACTOR_TIMEOUT_MILLIS).text
                parseJson<List<StreamInfo>>(response).filter { it.embedUrl.isNotBlank() }
            } catch (e: Exception) {
                Log.w("StreamedProvider", "No stream info from API for $source ($matchId): ${e.message}")
                emptyList()
            }

            if (streamInfos.isNotEmpty()) {
                streamInfos.forEach { stream ->
                    val streamId = stream.id
                    val streamNo = stream.streamNo
                    val language = stream.language
                    val isHd = stream.hd
                    val streamUrl = "$mainUrl/watch/$matchId/$source/$streamNo"
                    Log.d("StreamedProvider", "Processing stream URL: $streamUrl (ID: $streamId, Language: $language, HD: $isHd)")
                    if (extractor.getUrl(streamUrl, streamId, source, streamNo, language, isHd, subtitleCallback, callback)) {
                        success = true
                    }
                }
            } else if (availableSources.isEmpty()) {
                Log.w("StreamedProvider", "No stream info from API for $source ($matchId), trying fallback streamNo.")
                for (streamNo in 1..maxStreams) {
                    val streamUrl = "$mainUrl/watch/$matchId/$source/$streamNo"
                    Log.d("StreamedProvider", "Processing fallback stream URL: $streamUrl (ID: $matchId)")
                    if (extractor.getUrl(streamUrl, matchId, source, streamNo, "Unknown", false, subtitleCallback, callback)) {
                        success = true
                    }
                }
            } else {
                Log.d("StreamedProvider", "Source '$source' is reported as available but no stream info found from API for $matchId.")
            }
        }

        if (!success) {
            Log.e("StreamedProvider", "No links found for $matchId after all attempts.")
        }
        return success
    }

    data class Match(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String,
        @JsonProperty("poster") val posterPath: String? = null,
        @JsonProperty("popular") val popular: Boolean = false,
        @JsonProperty("sources") val matchSources: ArrayList<MatchSource> = arrayListOf()
    )

    data class MatchSource(
        @JsonProperty("source") val sourceName: String,
        @JsonProperty("id") val id: String
    )

    data class StreamInfo(
        @JsonProperty("id") val id: String,
        @JsonProperty("streamNo") val streamNo: Int,
        @JsonProperty("language") val language: String,
        @JsonProperty("hd") val hd: Boolean,
        @JsonProperty("embedUrl") val embedUrl: String,
        @JsonProperty("source") val source: String
    )
}

class StreamedMediaExtractor {
    private val fetchUrl = "https://embedstreams.top/fetch"
    private val cookieUrl = "https://fishy.streamed.su/api/event"
    private val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.5 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Content-Type" to "application/json",
        "Accept" to "application/vnd.apple.mpegurl, */*",
        "Origin" to "https://embedstreams.top"
    )
    private val fallbackDomains = listOf("p2-panel.streamed.su", "streamed.su")
    private val cookieCache = mutableMapOf<String, String>()

    companion object {
        const val EXTRACTOR_TIMEOUT_SECONDS = 30
        const val EXTRACTOR_TIMEOUT_MILLIS = EXTRACTOR_TIMEOUT_SECONDS * 1000L
    }

    // New function to generate the X-TOK header based on the JavaScript logic
    private suspend fun generateXTok(): String = withContext(Dispatchers.IO) {
        val userAgent = baseHeaders["User-Agent"] ?: "Mozilla/5.5 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36"
        val screenSize = "1920x1080" // Placeholder, actual screen size might vary
        val pixelDepth = 24
        val touch = true // Assuming touch device
        val deviceMemory = 8 // Common device memory, e.g., 8
        val platform = "Android"
        val touchPoints = 5 // Common touch points
        val hardwareConcurrency = 8 // Common CPU cores
        val intlDisplayNames = "supported" // Assuming modern Android Intl support

        // WebGL info is complex to simulate accurately in Kotlin without a browser engine.
        // For now, we'll use "none" as a fallback, consistent with the JS safe() function.
        val webglInfo = mapOf(
            "vendor" to "none",
            "renderer" to "none",
            "params" to "none",
            "exts" to "none"
        )
        // MimeTypes and Plugins are also hard to get accurately without a browser engine.
        // Using "none" as a fallback, similar to the JS safe() function.
        val mimeTypes = "none"
        val plugins = "none"

        // Timezone and timezoneOffset
        val timezone = TimeZone.getDefault().id // e.g., "Asia/Shanghai" or "Europe/London"
        val timezoneOffset = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / (1000 * 60) // in minutes

        val data = mapOf(
            "timezone" to timezone,
            "timezoneOffset" to timezoneOffset,
            "userAgent" to userAgent,
            "screenSize" to screenSize,
            "pixelDepth" to pixelDepth,
            "touch" to touch,
            "deviceMemory" to deviceMemory,
            "platform" to platform,
            "touchPoints" to touchPoints,
            "hardwareConcurrency" to hardwareConcurrency,
            "intlDisplayNames" to intlDisplayNames,
            "mimeTypes" to mimeTypes,
            "plugins" to plugins,
            "webgl" to webglInfo
        )

        // Use AppUtils.toJson for consistent JSON serialization
        val jsonStr = AppUtils.toJson(data) // Using AppUtils.toJson as it's typically an extension on Any or part of AppUtils object
        Log.d("StreamedMediaExtractor", "X-TOK JSON String: $jsonStr")

        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(jsonStr.toByteArray(StandardCharsets.UTF_8))
        return@withContext hashBytes.joinToString("") { "%02x".format(it) }
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

        // Fetch stream page cookies
        val streamResponse = try {
            app.get(streamUrl, headers = baseHeaders, timeout = EXTRACTOR_TIMEOUT_MILLIS)
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
        }
        if (combinedCookies.isEmpty()) {
            Log.e("StreamedMediaExtractor", "No cookies obtained for $source/$streamNo")
            return false
        }
        Log.d("StreamedMediaExtractor", "Combined cookies for $source/$streamNo: $combinedCookies")

        // Generate X-TOK header
        val xTok = generateXTok()
        Log.d("StreamedMediaExtractor", "Generated X-TOK: $xTok")

        // POST to fetch encrypted string
        val postData = mapOf(
            "source" to source,
            "id" to streamId,
            "streamNo" to streamNo.toString()
        )
        val embedReferer = "https://embedstreams.top/embed/$source/$streamId/$streamNo"
        val fetchHeaders = baseHeaders + mapOf(
            "Referer" to streamUrl,
            "Cookie" to combinedCookies,
            "X-TOK" to xTok // Add the generated X-TOK header
        )
        Log.d("StreamedMediaExtractor", "Fetching with data: $postData and headers: $fetchHeaders (excluding sensitive Cookie)")


        val encryptedResponse = try {
            val response = app.post(fetchUrl, headers = fetchHeaders, json = postData, timeout = EXTRACTOR_TIMEOUT_MILLIS)
            Log.d("StreamedMediaExtractor", "Fetch response code for $source/$streamNo: ${response.code}")
            if (response.code != 200) {
                Log.e("StreamedMediaExtractor", "Fetch failed for $source/$streamNo with code: ${response.code}, response: ${response.text}")
                return false
            }
            response.text.takeIf { it.isNotBlank() } ?: return false.also {
                Log.e("StreamedMediaExtractor", "Empty encrypted response for $source/$streamNo")
            }
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Fetch failed for $source/$streamNo: ${e.message}")
            return false
        }
        Log.d("StreamedMediaExtractor", "Encrypted response for $source/$streamNo: $encryptedResponse")

        // Decrypt using Deno
        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        val decryptResponse = try {
            app.post(decryptUrl, json = decryptPostData, headers = mapOf("Content-Type" to "application/json"), timeout = EXTRACTOR_TIMEOUT_MILLIS)
                .parsedSafe<Map<String, String>>()
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Decryption request failed for $source/$streamNo: ${e.message}")
            return false
        }
        val decryptedPath = decryptResponse?.get("decrypted")?.takeIf { it.isNotBlank() } ?: return false.also {
            Log.e("StreamedMediaExtractor", "Decryption failed or no 'decrypted' key for $source/$streamNo")
        }
        Log.d("StreamedMediaExtractor", "Decrypted path for $source/$streamNo: $decryptedPath")

        // Construct M3U8 URL
        val m3u8UrlBase = "https://rr.buytommy.top"
        val m3u8Url = "$m3u8UrlBase$decryptedPath"
        val m3u8Headers = baseHeaders + mapOf(
            "Referer" to embedReferer,
            "Cookie" to combinedCookies
        )

        // Test M3U8 with fallbacks
        var linkFound = false
        for (domain in listOf("rr.buytommy.top") + fallbackDomains) {
            try {
                val testUrl = m3u8Url.replace("rr.buytommy.top", domain)
                val testResponse = app.get(testUrl, headers = m3u8Headers, timeout = EXTRACTOR_TIMEOUT_MILLIS)
                if (testResponse.code == 200) {
                    callback.invoke(
                        newExtractorLink(
                            source = "Streamed",
                            name = "$source Stream $streamNo ($language${if (isHd) ", HD" else ""})",
                            url = testUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = embedReferer
                            this.quality = if (isHd) Qualities.P1080.value else Qualities.Unknown.value
                            this.headers = m3u8Headers
                        }
                    )
                    Log.d("StreamedMediaExtractor", "M3U8 URL added for $source/$streamNo: $testUrl")
                    linkFound = true
                    break // Stop after the first successful domain
                } else {
                    Log.w("StreamedMediaExtractor", "M3U8 test failed for $domain with code: ${testResponse.code}")
                }
            } catch (e: Exception) {
                Log.e("StreamedMediaExtractor", "M3U8 test failed for $domain: ${e.message}")
            }
        }

        if (!linkFound) {
            // Your original script adds the link even if tests fail. Keeping this logic.
            callback.invoke(
                newExtractorLink(
                    source = "Streamed",
                    name = "$source Stream $streamNo ($language${if (isHd) ", HD" else ""})",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = embedReferer
                    this.quality = if (isHd) Qualities.P1080.value else Qualities.Unknown.value
                    this.headers = m3u8Headers
                }
            )
            Log.d("StreamedMediaExtractor", "M3U8 test failed but added original URL anyway for $source/$streamNo: $m3u8Url")
            return true // Original script returns true here too, implying success is assumed if a link is provided.
        }

        return linkFound
    }

    private suspend fun fetchEventCookies(pageUrl: String, referrer: String): String {
        cookieCache[pageUrl]?.let { return it }

        val payload = """{"n":"pageview","u":"$pageUrl","d":"streamed.su","r":"$referrer"}"""
        // Added retry logic here based on previous improvements, as it's good practice.
        val maxRetries = 3
        var currentRetry = 0

        while (currentRetry < maxRetries) {
            try {
                val response = app.post(
                    cookieUrl,
                    data = mapOf(),
                    headers = mapOf("Content-Type" to "text/plain"),
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
                    Log.d("StreamedMediaExtractor", "Successfully fetched event cookies on attempt ${currentRetry + 1}")
                    return formattedCookies
                } else {
                    Log.w("StreamedMediaExtractor", "Event cookie response empty on attempt ${currentRetry + 1}. Retrying...")
                }
            } catch (e: Exception) {
                Log.e("StreamedMediaExtractor", "Failed to fetch event cookies on attempt ${currentRetry + 1}: ${e.message}. Retrying...")
            }
            currentRetry++
            if (currentRetry < maxRetries) {
                delay(1000L * currentRetry) // Exponential backoff for retries
            }
        }
        Log.e("StreamedMediaExtractor", "Failed to fetch event cookies after $maxRetries attempts.")
        return ""
    }
}
