package ben.smith53

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import android.util.Log
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.delay

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed"
    override var supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

    private val sources = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")
    private val maxStreams = 4
    private val fetchUrl = "https://embedstreams.top/fetch"
    private val cookieUrl = "https://fishy.streamed.su/api/event"
    private val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Encoding" to "gzip, deflate, br",
        "Origin" to "https://embedstreams.top",
        "Referer" to "https://embedstreams.top/",
        "Sec-Fetch-Site" to "cross-site",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Dest" to "empty"
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
        try {
            val response = withRetry(3) {
                app.get(request.data, headers = baseHeaders, interceptor = cfKiller, timeout = 15)
            }
            val rawList = response.text
            Log.d("StreamedProvider", "Main page raw response for ${request.data}: ${rawList.take(1000)}")
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
        } catch (e: Exception) {
            Log.e("StreamedProvider", "Main page fetch failed for ${request.data}: ${e.message}")
            return newHomePageResponse(
                list = listOf(HomePageList(request.name, emptyList(), isHorizontalImages = true)),
                hasNext = false
            )
        }
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
        val normalizedMatchId = matchId.replace(Regex("^\\d+-"), "")
        val extractor = StreamedMediaExtractor()
        var success = false

        sources.forEach { source ->
            // Try API-fetched stream IDs
            val streamInfos = try {
                val apiUrl = "$mainUrl/api/stream/$source/$normalizedMatchId"
                val response = withRetry(3) {
                    app.get(apiUrl, headers = baseHeaders, interceptor = cfKiller, timeout = 15)
                }
                Log.d("StreamedProvider", "Stream API response for $source ($normalizedMatchId): ${response.text.take(1000)}")
                if (response.code == 200 && response.text.isNotBlank()) {
                    parseJson<List<StreamInfo>>(response.text).filter { it.embedUrl.isNotBlank() }
                } else {
                    Log.w("StreamedProvider", "Invalid stream API response for $source ($normalizedMatchId): Code ${response.code}")
                    emptyList()
                }
            } catch (e: Exception) {
                Log.w("StreamedProvider", "No streams for $source ($normalizedMatchId): ${e.message}")
                emptyList()
            }

            if (streamInfos.isNotEmpty()) {
                streamInfos.forEach { stream ->
                    val streamId = stream.id
                    val streamNo = stream.streamNo
                    val language = stream.language
                    val isHd = stream.hd
                    val streamUrl = "https://embedstreams.top/embed/$source/$streamId/$streamNo"
                    Log.d("StreamedProvider", "Processing stream URL: $streamUrl (ID: $streamId, Language: $language, HD: $isHd)")
                    if (extractor.getUrl(streamUrl, streamId, source, streamNo, language, isHd, subtitleCallback, callback)) {
                        success = true
                    }
                }
            } else {
                // Fallback to raw matchId
                for (streamNo in 1..maxStreams) {
                    val streamUrl = "https://embedstreams.top/embed/$source/$matchId/$streamNo"
                    Log.d("StreamedProvider", "Processing fallback stream URL: $streamUrl (ID: $matchId)")
                    if (extractor.getUrl(streamUrl, matchId, source, streamNo, "Unknown", false, subtitleCallback, callback)) {
                        success = true
                    }
                }
            }
        }

        if (!success) {
            Log.e("StreamedProvider", "No links found for $matchId")
        }
        return success
    }

    // Retry utility function
    private suspend fun <T> withRetry(attempts: Int, block: suspend () -> T): T {
        var lastException: Exception? = null
        repeat(attempts) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                Log.w("StreamedProvider", "Attempt ${attempt + 1} failed: ${e.message}")
                if (attempt < attempts - 1) delay(1000L * (attempt + 1))
            }
        }
        throw lastException ?: Exception("All retry attempts failed")
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
    private val decryptUrl = "https://bensmith53-decrypt-13.deno.dev/decrypt"
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/vnd.apple.mpegurl, */*",
        "Accept-Encoding" to "gzip, deflate, br",
        "Origin" to "https://embedstreams.top",
        "Referer" to "https://embedstreams.top/",
        "Sec-Fetch-Site" to "cross-site",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Dest" to "empty"
    )
    private val fallbackDomains = listOf("p2-panel.streamed.su", "streamed.su")
    private val cookieCache = mutableMapOf<String, String>()
    private val hardcodedIV = "507dcd1eb7f04bb6983b19be56b89020" // For alpha streams

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

        // Test decryption service
        val decryptTest = try {
            withRetry(3) {
                app.post(
                    decryptUrl,
                    json = mapOf("encrypted" to "test"),
                    headers = mapOf("Content-Type" to "application/json"),
                    timeout = 10
                )
            }
            Log.d("StreamedMediaExtractor", "Decryption service test response: ${decryptTest.text.take(1000)}")
            decryptTest.code == 200
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Decryption service unavailable: ${e.message}")
            return false
        }
        if (!decryptTest) {
            Log.e("StreamedMediaExtractor", "Decryption service failed test for $source/$streamNo")
            return false
        }

        // Fetch stream page cookies
        val streamResponse = try {
            withRetry(3) {
                app.get(streamUrl, headers = baseHeaders, interceptor = cfKiller, timeout = 15)
            }
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

        // POST to fetch encrypted string
        val postData = mapOf(
            "source" to source,
            "id" to streamId,
            "streamNo" to streamNo.toString()
        )
        val fetchHeaders = baseHeaders + mapOf(
            "Referer" to streamUrl,
            "Cookie" to combinedCookies,
            "Content-Type" to "application/json"
        )
        Log.d("StreamedMediaExtractor", "Fetching with data: $postData and headers: $fetchHeaders")

        val encryptedResponse = try {
            val response = withRetry(3) {
                app.post(fetchUrl, headers = fetchHeaders, json = postData, timeout = 15)
            }
            Log.d("StreamedMediaExtractor", "Fetch response code for $source/$streamNo: ${response.code}")
            if (response.code != 200) {
                Log.e("StreamedMediaExtractor", "Fetch failed for $source/$streamNo with code: ${response.code}")
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
            val response = withRetry(3) {
                app.post(decryptUrl, json = decryptPostData, headers = mapOf("Content-Type" to "application/json"), timeout = 15)
            }
            Log.d("StreamedMediaExtractor", "Decrypt response for $source/$streamNo: ${response.text.take(1000)}")
            response.parsedSafe<Map<String, String>>()
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Decryption request failed for $source/$streamNo: ${e.message}")
            return false
        }
        val decryptedPath = decryptResponse?.get("decrypted")?.takeIf { it.isNotBlank() } ?: return false.also {
            Log.e("StreamedMediaExtractor", "Decryption failed or no 'decrypted' key for $source/$streamNo")
        }
        Log.d("StreamedMediaExtractor", "Decrypted path for $source/$streamNo: $decryptedPath")

        // Construct M3U8 URL
        val m3u8Url = "https://rr.buytommy.top$decryptedPath"
        val m3u8Headers = baseHeaders + mapOf(
            "Referer" to streamUrl,
            "Cookie" to combinedCookies
        )

        // Fetch M3U8 to extract EXT-X-KEY for alpha streams
        var keyUri: String? = null
        if (source == "alpha") {
            try {
                val m3u8Response = withRetry(3) {
                    app.get(m3u8Url, headers = m3u8Headers, interceptor = cfKiller, timeout = 15)
                }
                if (m3u8Response.code == 200) {
                    val m3u8Content = m3u8Response.text
                    Log.d("StreamedMediaExtractor", "M3U8 content for $source/$streamNo: ${m3u8Content.take(1000)}")
                    // Extract EXT-X-KEY URI
                    val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""").find(m3u8Content)
                    keyUri = keyMatch?.groups?.get(1)?.value
                    if (keyUri == null) {
                        Log.w("StreamedMediaExtractor", "No EXT-X-KEY found in M3U8 for $source/$streamNo")
                    }
                } else {
                    Log.w("StreamedMediaExtractor", "Failed to fetch M3U8 for $source/$streamNo: ${m3u8Response.code}")
                }
            } catch (e: Exception) {
                Log.e("StreamedMediaExtractor", "Error fetching M3U8 for $source/$streamNo: ${e.message}")
            }
        }

        // Test M3U8 with fallbacks
        for (domain in listOf("rr.buytommy.top") + fallbackDomains) {
            try {
                val testUrl = m3u8Url.replace("rr.buytommy.top", domain)
                val testResponse = withRetry(3) {
                    app.get(testUrl, headers = m3u8Headers, interceptor = cfKiller, timeout = 15)
                }
                if (testResponse.code == 200) {
                    val extractorData = if (keyUri != null) "AES-CBC:$keyUri:$hardcodedIV" else null
                    callback.invoke(
                        ExtractorLink(
                            source = "Streamed",
                            name = "$source Stream $streamNo ($language${if (isHd) ", HD" else ""})",
                            url = testUrl,
                            referrer = streamUrl,
                            quality = if (isHd) Qualities.P1080.value else Qualities.Unknown.value,
                            isM3u8 = true,
                            headers = m3u8Headers,
                            extractorData = extractorData
                        )
                    )
                    Log.d("StreamedMediaExtractor", "M3U8 URL added for $source/$streamNo: $testUrl")
                    return true
                } else {
                    Log.w("StreamedMediaExtractor", "M3U8 test failed for $domain with code: ${testResponse.code}")
                }
            } catch (e: Exception) {
                Log.e("StreamedMediaExtractor", "M3U8 test failed for $domain: ${e.message}")
            }
        }

        // If tests fail, add link anyway
        val extractorData = if (keyUri != null) "AES-CBC:$keyUri:$hardcodedIV" else null
        callback.invoke(
            ExtractorLink(
                source = "Streamed",
                name = "$source Stream $streamNo ($language${if (isHd) ", HD" else ""})",
                url = m3u8Url,
                referrer = streamUrl,
                quality = if (isHd) Qualities.P1080.value else Qualities.Unknown.value,
                isM3u8 = true,
                headers = m3u8Headers,
                extractorData = extractorData
            )
        )
        Log.d("StreamedMediaExtractor", "M3U8 test failed but added anyway for $source/$streamNo: $m3u8Url")
        return true
    }

    private suspend fun fetchEventCookies(pageUrl: String, referrer: String): String {
        cookieCache[pageUrl]?.let { return it }

        val payload = """{"n":"pageview","u":"$pageUrl","d":"embedstreams.top","r":"$referrer"}"""
        try {
            val response = withRetry(3) {
                app.post(
                    cookieUrl,
                    data = mapOf(),
                    headers = mapOf("Content-Type" to "text/plain"),
                    requestBody = payload.toRequestBody("text/plain".toMediaType()),
                    timeout = 15
                )
            }
            val cookies = response.headers.filter { it.first == "Set-Cookie" }
                .map { it.second.split(";")[0] }
            val formattedCookies = listOf("_ddg8_", "_ddg10_", "_ddg9_", "_ddg1_")
                .mapNotNull { key -> cookies.find { it.startsWith(key) } }
                .joinToString("; ")
            if (formattedCookies.isNotEmpty()) {
                cookieCache[pageUrl] = formattedCookies
                return formattedCookies
            }
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Failed to fetch event cookies: ${e.message}")
        }
        return ""
    }
}