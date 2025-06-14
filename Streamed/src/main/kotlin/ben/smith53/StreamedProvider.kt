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

    private val maxStreams = 4
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
            val posterUrl = "$mainUrl/api/images/matcher/$matchId.webp"
            // Verify poster URL
            val validPosterUrl = try {
                app.head(posterUrl).text.let { if (it.isSuccessful) posterUrl else "$mainUrl/api/images/poster/fallback.webp" }
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
            Log.e("Error", "Failed to load URL $url:$")
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
            Log.e("Error", "Invalid matchId")
            return false
        }
        var success = false

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

            if (streamInfos.isNotEmpty()) {
                streamInfos.forEach { stream ->
                    repeat(2) { attempt -> // Retry once
                        try {
                            val streamUrl = "$mainUrl/watch/$matchId/$source/${stream.streamNo}"
                            Log.d("StreamedProvider", "Attempt ${attempt + 1} for $streamUrl (ID: ${stream.id}, Language: ${stream.language}, HD: ${stream.hd})")
                            if (extractor.getUrl(streamUrl, stream.id, source, stream.streamNo, stream.language, stream.hd, subtitleCallback, callback)) {
                                success = true
                                return@repeat
                            }
                        } catch (e: Exception) {
                            Log.e("StreamedProvider", "Attempt ${attempt + 1} failed for $source stream ${stream.streamNo}: ${e.message}")
                        }
                    }
                }
            } else if (availableSources.isEmpty()) {
                for (streamNo in 1..maxStreams) {
                    repeat(2) { attempt ->
                        try {
                            val streamUrl = "$mainUrl/watch/$matchId/$source/$streamNo"
                            Log.d("StreamedProvider", "Attempt ${attempt + 1} for fallback $streamUrl")
                            if (extractor.getUrl(streamUrl, matchId, source, streamNo, "Unknown", false, subtitleCallback, callback)) {
                                success = true
                                return@repeat
                            }
                        } catch (e: Exception) {
                            Log.e("StreamedProvider", "Attempt ${attempt + 1} failed for $source stream $streamNo: ${e.message}")
                        }
                    }
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
    private val fetchM3u8Url = "https://bensmithgb53-decrypt-13.deno.dev/fetch-m3u8"
    private val cookieUrl = "https://fishy.streamed.su/api/event"
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
            Log.d("StreamedMediaExtractor", "M3U8 test failed, added fallback URL for $source/$streamNo: $m3u8Response")
            linkFound = true
        }

        return linkFound
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
            Log.e("StreamedMediaExtractor", "Turnstile fetch failed: ${e.message}")
            return false
        }
        if (turnstileResponse.code != 200) {
            Log.e("StreamedMediaExtractor", "Turnstile failed with code: ${turnstileResponse.code}")
            return false
        }

        // Extract flow URL (simplified, replace with regex parsing if needed)
        val flowUrlMatch = Regex("""action="(/flow/ov1/[^"]+)"""").find(turnstileResponse.text)
        val flowUrl = flowUrlMatch?.groupValues?.get(1)?.let { "$challengeBaseUrl$it" }
            ?: return false.also { Log.e("StreamedMediaExtractor", "Failed to extract flow URL") }
        val flowHeaders = baseHeaders + mapOf(
            "Referer" to turnstileUrl,
            "Content-Type" to "text/plain;charset=UTF-8",
            "Origin" to "https://challenges.cloudflare.com"
        )
        val flowResponse = try {
            app.post(flowUrl, headers = flowHeaders, data = mapOf(), timeout = EXTRACTOR_TIMEOUT_MILLIS)
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Flow POST failed: ${e.message}")
            return false
        }
        cfClearance = flowResponse.headers.filter { it.first == "Set-Cookie" }
            .map { it.second.split(";")[0] }
            .find { it.startsWith("cf_clearance=") }?.substringAfter("cf_clearance=")
        Log.d("StreamedMediaExtractor", "Cloudflare clearance cookie: $cfClearance")
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
                Log.d("StreamedMediaExtractor", "Cached cookies: $formattedCookies")
            }
            return formattedCookies
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Failed to fetch event cookies: ${e.message}")
            return ""
        }
    }
}