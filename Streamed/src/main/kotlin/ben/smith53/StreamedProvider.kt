package ben.smith53

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
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
import java.security.MessageDigest
import java.util.*

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
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
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

        // Fetch match details to get available sources
        val matchDetails = try {
            app.get("$mainUrl/api/matches/live/$matchId", timeout = StreamedMediaExtractor.EXTRACTOR_TIMEOUT_MILLIS).parsedSafe<Match>()
        } catch (e: Exception) {
            Log.e("StreamedProvider", "Failed to fetch match details for $matchId: ${e.message}")
            return false
        }

        val availableSources = matchDetails?.matchSources?.map { it.sourceName }?.toSet() ?: emptySet()
        Log.d("StreamedProvider", "Available sources for $matchId: $availableSources")

        if (availableSources.isEmpty()) {
            Log.e("StreamedProvider", "No sources available for $matchId from API")
            return false
        }

        for (source in availableSources) {
            val streamInfos = try {
                val response = app.get("$mainUrl/api/stream/$source/$matchId", timeout = StreamedMediaExtractor.EXTRACTOR_TIMEOUT_MILLIS).text
                parseJson<List<StreamInfo>>(response).filter { it.embedUrl.isNotBlank() }
            } catch (e: Exception) {
                Log.w("StreamedProvider", "No stream info from API for $source ($matchId): ${e.message}")
                continue
            }

            if (streamInfos.isNotEmpty()) {
                streamInfos.forEach { stream ->
                    val streamId = stream.id
                    val streamNo = stream.streamNo
                    val language = stream.language
                    val isHd = stream.hd
                    val streamUrl = "$mainUrl/watch/$matchId/$source/$streamNo"
                    Log.d("StreamedProvider", "Processing stream URL: $streamUrl (ID: $streamId, Source: $source, StreamNo: $streamNo, Language: $language, HD: $isHd)")
                    if (extractor.getUrl(streamUrl, streamId, source, streamNo, language, isHd, subtitleCallback, callback)) {
                        success = true
                    }
                }
            } else {
                Log.w("StreamedProvider", "Source '$source' is reported as available but no stream info found from API for $matchId")
                // Try fallback stream numbers if API is unreliable
                for (streamNo in 1..maxStreams) {
                    val streamUrl = "$mainUrl/watch/$matchId/$source/$streamNo"
                    Log.d("StreamedProvider", "Processing fallback stream URL: $streamUrl (ID: $matchId, Source: $source, StreamNo: $streamNo)")
                    if (extractor.getUrl(streamUrl, matchId, source, streamNo, "Unknown", false, subtitleCallback, callback)) {
                        success = true
                    }
                }
            }
        }

        if (!success) {
            Log.e("StreamedProvider", "No links found for $matchId after processing available sources")
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
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Content-Type" to "application/json",
        "Accept" to "application/vnd.apple.mpegurl, */*",
        "Origin" to "https://embedstreams.top"
    )
    private val fallbackDomains = listOf("rr.buytommy.top") // Removed invalid domains
    private val cookieCache = mutableMapOf<String, String>()
    private val objectMapper = ObjectMapper()

    companion object {
        const val EXTRACTOR_TIMEOUT_SECONDS = 30
        const val EXTRACTOR_TIMEOUT_MILLIS = EXTRACTOR_TIMEOUT_SECONDS * 1000L
    }

    private suspend fun generateXTok(): String = withContext(Dispatchers.IO) {
        val userAgent = baseHeaders["User-Agent"] ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36"
        val screenSize = "1080x1920"
        val pixelDepth = 32
        val touch = true
        val deviceMemory = 8
        val platform = "Linux armv8l"
        val touchPoints = 5
        val hardwareConcurrency = 8
        val intlDisplayNames = "supported"
        val webglInfo = mapOf(
            "vendor" to "Google Inc.",
            "renderer" to "ANGLE (Qualcomm, Adreno 630)",
            "params" to mapOf("unavailable" to "unavailable"),
            "exts" to listOf("none")
        )
        val mimeTypes = listOf("application/pdf", "video/mp4")
        val plugins = listOf("Chrome PDF Plugin")
        val timezone = TimeZone.getDefault().id
        val timezoneOffset = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / (1000 * 60)

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

        val jsonStr = objectMapper.writeValueAsString(data)
        Log.d("StreamedMediaExtractor", "X-TOK JSON String: $jsonStr")
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(jsonStr.toByteArray(Charsets.UTF_8))
        val hash = hashBytes.joinToString("") { "%02x".format(it) }
        Log.d("StreamedMediaExtractor", "Generated X-TOK: $hash")
        hash
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
        Log.d("StreamedMediaExtractor", "Starting extraction for: $streamUrl (ID: $streamId, Source: $source, StreamNo: $streamNo)")

        // Fetch stream page cookies
        val streamResponse = try {
            app.get(streamUrl, headers = baseHeaders, timeout = EXTRACTOR_TIMEOUT_MILLIS)
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Stream page fetch failed for $source/$streamNo: ${e.message}")
            return false
        }
        val streamCookies = streamResponse.cookies
        val streamCookiesString = streamCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        Log.d("StreamedMediaExtractor", "Stream cookies for $source/$streamNo: $streamCookiesString")

        // Fetch event cookies
        val eventCookies = fetchEventCookies(streamUrl, streamUrl)
        Log.d("StreamedMediaExtractor", "Event cookies for $source/$streamNo: $eventCookies")

        // Combine cookies
        val combinedCookies = mutableListOf<String>()
        if (streamCookiesString.isNotEmpty()) {
            combinedCookies.add(streamCookiesString)
        }
        if (eventCookies.isNotEmpty()) {
            val existingKeys = streamCookies.keys
            val newEventCookies = eventCookies.split("; ").filter { cookie ->
                val key = cookie.substringBefore("=")
                !existingKeys.contains(key)
            }
            combinedCookies.add(newEventCookies.joinToString("; "))
        }

        val finalCombinedCookies = combinedCookies.filter { it.isNotBlank() }.joinToString("; ")
        if (finalCombinedCookies.isEmpty()) {
            Log.e("StreamedMediaExtractor", "No cookies obtained for $source/$streamNo")
            return false
        }
        Log.d("StreamedMediaExtractor", "Final combined cookies for $source/$streamNo: $finalCombinedCookies")

        // Generate X-TOK
        val xTok = generateXTok()
        Log.d("StreamedMediaExtractor", "Generated X-TOK: $xTok")

        // POST to fetch encrypted string
        val postData = mapOf(
            "source" to source,
            "id" to streamId,
            "streamNo" to streamNo.toString()
        )
        val embedReferer = "https://embedstreams.top/embed/$source/$streamId/$streamNo"
        val fetchHeaders = baseHeaders.toMutableMap().apply {
            this["Referer"] = embedReferer
            this["Cookie"] = finalCombinedCookies
            this["X-TOK"] = xTok
        }

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
            Log.e("StreamedMediaExtractor", "Fetch request failed for $source/$streamNo: ${e.message}")
            return false
        }
        Log.d("StreamedMediaExtractor", "Encrypted response for $source/$streamNo: $encryptedResponse")

        // Decrypt using Deno
        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        val decryptResponse = try {
            var retryCount = 0
            val maxRetries = 3
            var result: Map<String, String>? = null
            while (retryCount < maxRetries && result == null) {
                try {
                    result = app.post(
                        decryptUrl,
                        json = decryptPostData,
                        headers = mapOf("Content-Type" to "application/json"),
                        timeout = EXTRACTOR_TIMEOUT_MILLIS
                    ).parsedSafe<Map<String, String>>()
                    Log.d("StreamedMediaExtractor", "Decryption response for $source/$streamNo: $result")
                } catch (e: Exception) {
                    Log.e("StreamedMediaExtractor", "Decryption attempt ${retryCount + 1} failed: ${e.message}")
                    retryCount++
                    if (retryCount < maxRetries) delay(1000L * retryCount)
                }
            }
            result
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Decryption request failed for $source/$streamNo: ${e.message}")
            return false
        }
        val decryptedPath = decryptResponse?.get("decrypted")?.takeIf { it.isNotBlank() } ?: return false.also {
            Log.e("StreamedMediaExtractor", "Decryption failed or no 'decrypted' key in response for $source/$streamNo")
        }
        Log.d("StreamedMediaExtractor", "Decrypted path for $source/$streamNo: $decryptedPath")

        // Construct M3U8 URL
        val m3u8UrlBase = "https://rr.buytommy.top"
        val m3u8Url = "$m3u8UrlBase$decryptedPath"
        val m3u8Headers = baseHeaders.toMutableMap().apply {
            this["Referer"] = embedReferer
            this["Cookie"] = finalCombinedCookies
            this["Accept-Encoding"] = "gzip, deflate, br"
            this["Sec-Fetch-Mode"] = "cors"
            this["Sec-Fetch-Site"] = "cross-site"
            this["Sec-Ch-Ua"] = "\"Not A(Brand\";v=\"8\", \"Chromium\";v=\"132\""
            this["Sec-Ch-Ua-Mobile"] = "?1"
            this["Sec-Ch-Ua-Platform"] = "\"Android\""
        }

        // Test M3U8 with retries
        var linkFound = false
        val domainsToTest = listOf(m3u8UrlBase) + fallbackDomains
        for (domain in domainsToTest) {
            val testUrl = m3u8Url.replace(m3u8UrlBase, "https://$domain")
            var retryCount = 0
            val maxRetries = 3
            while (retryCount < maxRetries && !linkFound) {
                try {
                    Log.d("StreamedMediaExtractor", "Testing M3U8 URL: $testUrl with domain: $domain (Attempt ${retryCount + 1})")
                    Log.d("StreamedMediaExtractor", "M3U8 headers: $m3u8Headers")
                    val testResponse = app.get(testUrl, headers = m3u8Headers, timeout = EXTRACTOR_TIMEOUT_MILLIS)
                    Log.d("StreamedMediaExtractor", "M3U8 response code for $testUrl: ${testResponse.code}")
                    if (testResponse.code == 200) {
                        callback.invoke(
                            newExtractorLink(
                                source = "Streamed",
                                name = "$source Stream $streamNo (${language}${if (isHd) ", HD" else ""})",
                                url = testUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = embedReferer
                                this.quality = if (isHd) Qualities.P1080.value else Qualities.Unknown.value
                                this.headers = m3u8Headers
                            }
                        )
                        Log.d("StreamedMediaExtractor", "M3U8 URL added successfully for $source/$streamNo: $testUrl")
                        linkFound = true
                    } else {
                        Log.w("StreamedMediaExtractor", "M3U8 test failed for $testUrl with code: ${testResponse.code}, response: ${testResponse.text}")
                    }
                } catch (e: Exception) {
                    Log.e("StreamedMediaExtractor", "M3U8 test failed for domain $domain (Attempt ${retryCount + 1}): ${e.message}")
                }
                retryCount++
                if (retryCount < maxRetries && !linkFound) {
                    delay(1000L * retryCount)
                }
            }
            if (linkFound) break
        }

        if (!linkFound) {
            Log.e("StreamedMediaExtractor", "No working M3U8 link found for $source/$streamNo after all domain attempts")
            return false
        }

        return linkFound
    }

    private suspend fun fetchEventCookies(pageUrl: String, referrer: String): String {
        synchronized(cookieCache) {
            cookieCache[pageUrl]?.let { return it }
        }

        val payload = """{"n":"pageview","u":"$pageUrl","d":"streamed.su","r":"$referrer"}"""
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
                Log.d("StreamedMediaExtractor", "Raw event cookies for $pageUrl: $cookies")
                val formattedCookies = cookies.filter { it.startsWith("_ddg") || it.startsWith("cf_clearance") }
                    .joinToString("; ")
                if (formattedCookies.isNotEmpty()) {
                    synchronized(cookieCache) {
                        cookieCache[pageUrl] = formattedCookies
                    }
                    Log.d("StreamedMediaExtractor", "Successfully fetched event cookies on attempt ${currentRetry + 1}: $formattedCookies")
                    return formattedCookies
                } else {
                    Log.w("StreamedMediaExtractor", "No relevant cookies (e.g., _ddg, cf_clearance) found in response on attempt ${currentRetry + 1}")
                }
            } catch (e: Exception) {
                Log.e("StreamedMediaExtractor", "Failed to fetch event cookies on attempt ${currentRetry + 1}: ${e.message}")
            }
            currentRetry++
            if (currentRetry < maxRetries) {
                delay(1000L * currentRetry)
            }
        }
        Log.e("StreamedMediaExtractor", "Failed to fetch event cookies after $maxRetries attempts")
        return ""
    }
}