package ben.smith53

import com.fasterxml.jackson.annotation.JsonProperty
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

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed"
    override var supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

    private val defaultSources = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")
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
            val rawList = app.get(request.data, timeout = 15).text
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
            Log.e("StreamedProvider", "Failed to load main page ${request.data}: ${e.message}")
            return newHomePageResponse(list = emptyList(), hasNext = false)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        try {
            val matchId = url.substringAfterLast("/")
            val title = matchId.replace("-", " ")
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                .replace(Regex("-\\d+$"), "")
            val posterUrl = "$mainUrl/api/images/poster/$matchId.webp"
            val validPosterUrl = try {
                app.head(posterUrl, timeout = 10).isSuccessful.let { if (it) posterUrl else "$mainUrl/api/images/poster/fallback.webp" }
            } catch (e: Exception) {
                "$mainUrl/api/images/poster/fallback.webp"
            }
            return newLiveStreamLoadResponse(
                name = title,
                url = url,
                dataUrl = url
            ) {
                this.posterUrl = validPosterUrl
            }
        } catch (e: Exception) {
            Log.e("StreamedProvider", "Failed to load URL $url: ${e.message}")
            throw e
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val matchId = data.substringAfterLast("/")
        if (matchId.isBlank()) {
            Log.e("StreamedProvider", "Invalid matchId: $matchId")
            return false
        }
        val extractor = StreamedMediaExtractor()
        var success = false

        // Truncate matchId for fetch/decryption if too long
        val fetchId = if (matchId.length > 50) matchId.take(50) else matchId
        Log.d("StreamedProvider", "Using fetchId: $fetchId for matchId: $matchId")

        // Fetch match details
        val matchDetails = try {
            app.get("$mainUrl/api/matches/live/$matchId", timeout = StreamedMediaExtractor.EXTRACTOR_TIMEOUT_MILLIS).parsedSafe<Match>()
        } catch (e: Exception) {
            Log.w("StreamedProvider", "Failed to fetch match details for $matchId: ${e.message}")
            null
        }
        val availableSources = matchDetails?.matchSources?.map { it.sourceName }?.toSet() ?: emptySet()
        Log.d("StreamedProvider", "Available sources for $matchId: $availableSources")

        val sourcesToProcess = if (availableSources.isNotEmpty()) availableSources.toList() else defaultSources
        for (source in sourcesToProcess) {
            // Fetch stream info
            val streamInfos = try {
                val response = app.get("$mainUrl/api/stream/$source/$matchId", timeout = StreamedMediaExtractor.EXTRACTOR_TIMEOUT_MILLIS).text
                parseJson<List<StreamInfo>>(response).filter { it.embedUrl.isNotBlank() }
            } catch (e: Exception) {
                Log.w("StreamedProvider", "No stream info from API for $source ($matchId): ${e.message}")
                emptyList()
            }
            Log.d("StreamedProvider", "StreamInfo for $source/$matchId: $streamInfos")

            if (streamInfos.isNotEmpty()) {
                // Process all streams from StreamInfo
                streamInfos.forEach { stream ->
                    repeat(3) { attempt -> // Retry up to 3 times
                        try {
                            val streamUrl = "$mainUrl/watch/$matchId/$source/${stream.streamNo}"
                            Log.d("StreamedProvider", "Attempt ${attempt + 1} for $streamUrl (ID: ${stream.id}, Language: ${stream.language}, HD: ${stream.hd})")
                            if (extractor.getUrl(streamUrl, fetchId, source, stream.streamNo, stream.language, stream.hd, subtitleCallback, callback)) {
                                success = true
                                return@repeat
                            }
                        } catch (e: Exception) {
                            Log.e("StreamedProvider", "Attempt ${attempt + 1} failed for $source stream ${stream.streamNo}: ${e.message}")
                        }
                    }
                }
            } else {
                // Fallback only if no API sources and match is likely valid
                if (availableSources.isEmpty() && matchDetails != null) {
                    // Use streamNos from API if available, else try up to 6
                    val maxFallbackStreams = streamInfos.maxOfOrNull { it.streamNo } ?: 6
                    for (streamNo in 1..maxFallbackStreams) {
                        repeat(3) { attempt ->
                            try {
                                val streamUrl = "$mainUrl/watch/$matchId/$source/$streamNo"
                                Log.d("StreamedProvider", "Attempt ${attempt + 1} for fallback $streamUrl")
                                if (extractor.getUrl(streamUrl, fetchId, source, streamNo, "Unknown", false, subtitleCallback, callback)) {
                                    success = true
                                    return@repeat
                                }
                            } catch (e: Exception) {
                                Log.e("StreamedProvider", "Attempt ${attempt + 1} failed for $source stream $streamNo: ${e.message}")
                            }
                        }
                    }
                } else {
                    Log.w("StreamedProvider", "Skipping fallback for $source/$matchId: no match details or sources")
                }
            }
        }
        Log.d("StreamedProvider", "Load links result for $matchId: success=$success")
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
    private val fallbackDomains = listOf("rr.buytommy.top", "p2-panel.streamed.su", "streamed.su", "embedstreams.top", "ann.embedstreams.top")
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
            if (!app.head("https://streamed.su", timeout = 10).isSuccessful) {
                Log.e("StreamedMediaExtractor", "streamed.su is unreachable")
                return false
            }
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Server check failed: ${e.message}")
            return false
        }

        // Fetch Cloudflare clearance with retries
        cfClearance = null
        repeat(3) { attempt ->
            if (fetchCloudflareClearance(streamUrl)) {
                Log.d("StreamedMediaExtractor", "Cloudflare clearance obtained on attempt ${attempt + 1}: $cfClearance")
                return@repeat
            }
            Log.w("StreamedMediaExtractor", "Cloudflare clearance failed on attempt ${attempt + 1}")
        }
        if (cfClearance == null) {
            Log.w("StreamedMediaExtractor", "Proceeding without Cloudflare clearance")
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
        Log.d("StreamedMediaExtractor", "Stream cookies: ${streamCookies.take(200)}")

        // Fetch event cookies
        val eventCookies = fetchEventCookies(streamUrl, streamUrl)
        Log.d("StreamedMediaExtractor", "Event cookies: $eventCookies")

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
        Log.d("StreamedMediaExtractor", "Combined cookies: $combinedCookies")

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
            Log.d("StreamedMediaExtractor", "Fetch response code: ${response.code}")
            if (response.code != 200) {
                Log.e("StreamedMediaExtractor", "Fetch failed with code: ${response.code}, body: ${response.text.take(100)}")
                return false
            }
            response.text.takeIf { it.isNotBlank() } ?: return false.also {
                Log.e("StreamedMediaExtractor", "Empty encrypted response")
            }
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Fetch failed: ${e.message}")
            return false
        }
        Log.d("StreamedMediaExtractor", "Encrypted response: ${encryptedResponse.take(100)}")

        // Decrypt using Deno
        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        val decryptResponse = try {
            app.post(decryptUrl, json = decryptPostData, headers = mapOf("Content-Type" to "application/json"), timeout = EXTRACTOR_TIMEOUT_MILLIS)
                .parsedSafe<Map<String, String>>()
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Decryption request failed: ${e.message}")
            return false
        }
        val decryptedPath = decryptResponse?.get("decrypted")?.takeIf { it.isNotBlank() } ?: return false.also {
            Log.e("StreamedMediaExtractor", "Decryption failed or no 'decrypted' key")
        }
        Log.d("StreamedMediaExtractor", "Decrypted path: $decryptedPath")

        // Parse query parameters
        val urlParts = decryptedPath.split("?")
        val basePath = urlParts[0]
        val queryParams = if (urlParts.size > 1) "?${urlParts[1]}" else ""

        // Construct M3U8 URL
        val keySuffix = if (source == "bravo") "/g.key" else ""
        val m3u8BaseUrl = "https://rr.buytommy.top$basePath$keySuffix"
        val m3u8Headers = baseHeaders + mapOf(
            "Referer" to embedReferer,
            "Cookie" to combinedCookies,
            "Origin" to "https://embedstreams.top"
        )

        // Test M3U8 with fallbacks
        var linkFound = false
        for (domain in fallbackDomains) {
            try {
                val testUrl = m3u8BaseUrl.replace("rr.buytommy.top", domain) + queryParams
                Log.d("StreamedMediaExtractor", "Testing M3U8: $testUrl")
                val testResponse = app.get(testUrl, headers = m3u8Headers, timeout = EXTRACTOR_TIMEOUT_MILLIS)
                if (testResponse.code == 200 && (testResponse.text.contains("#EXTM3U") || testResponse.text.length == 16)) {
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
                    Log.d("StreamedMediaExtractor", "M3U8 added: $testUrl")
                    linkFound = true
                    break
                }
            } catch (e: Exception) {
                Log.e("StreamedMediaExtractor", "M3U8 test failed for $domain: ${e.message}")
            }
        }

        // Fallback: Add original URL if no test succeeds
        if (!linkFound) {
            val fallbackUrl = m3u8BaseUrl + queryParams
            callback.invoke(
                newExtractorLink(
                    source = "Streamed",
                    name = "$source Stream $streamNo ($language${if (isHd) ", HD" else ""})",
                    url = fallbackUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = embedReferer
                    this.quality = if (isHd) Qualities.P1080.value else Qualities.Unknown.value
                    this.headers = m3u8Headers
                }
            )
            Log.d("StreamedMediaExtractor", "Added fallback M3U8: $fallbackUrl")
            linkFound = true
        }

        return linkFound
    }

    private suspend fun fetchCloudflareClearance(streamUrl: String): Boolean {
        try {
            val turnstileUrl = "$challengeBaseUrl/turnstile/if/ov2/av0/rcv/ihoau/0x4AAAAAAAkvKraQY_9hzpmB/auto/fbE/new/normal/auto/"
            val turnstileHeaders = baseHeaders + mapOf(
                "Referer" to streamUrl,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            val turnstileResponse = app.get(turnstileUrl, headers = turnstileHeaders, timeout = EXTRACTOR_TIMEOUT_MILLIS)
            if (turnstileResponse.code != 200) {
                Log.e("StreamedMediaExtractor", "Turnstile failed with code: ${turnstileResponse.code}")
                return false
            }

            // Extract flow URL dynamically
            val flowUrlMatch = Regex("""action="(/flow/ov1/[^"]+)"""").find(turnstileResponse.text)
            val flowUrl = flowUrlMatch?.groupValues?.get(1)?.let { "$challengeBaseUrl$it" }
                ?: return false.also { Log.e("StreamedMediaExtractor", "Failed to extract flow URL") }
            val flowHeaders = baseHeaders + mapOf(
                "Referer" to turnstileUrl,
                "Content-Type" to "text/plain;charset=UTF-8",
                "Origin" to "https://challenges.cloudflare.com"
            )
            val flowResponse = app.post(flowUrl, headers = flowHeaders, data = mapOf(), timeout = EXTRACTOR_TIMEOUT_MILLIS)
            cfClearance = flowResponse.headers.filter { it.first == "Set-Cookie" }
                .map { it.second.split(";")[0] }
                .find { it.startsWith("cf_clearance=") }?.substringAfter("cf_clearance=")
            Log.d("StreamedMediaExtractor", "Cloudflare clearance: $cfClearance")
            return cfClearance != null
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Cloudflare clearance failed: ${e.message}")
            return false
        }
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
            }
            return formattedCookies
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Failed to fetch event cookies: ${e.message}")
            return ""
        }
    }
}