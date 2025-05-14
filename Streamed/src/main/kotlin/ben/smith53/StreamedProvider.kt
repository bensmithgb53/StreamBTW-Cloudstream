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

    private val sources = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")
    private val maxStreams = 4

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
            val rawList = app.get(request.data, timeout = 20).text
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
            return newLiveStreamLoadResponse(
                name = title,
                url = url,
                dataUrl = url
            ) {
                this.posterUrl = posterUrl
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
        val extractor = StreamedMediaExtractor()
        var success = false
        var attempts = 0
        val maxAttempts = 2

        sources.forEach { source ->
            for (streamNo in 1..maxStreams) {
                while (attempts < maxAttempts) {
                    val streamUrl = "$mainUrl/watch/$matchId/$source/$streamNo"
                    Log.d("StreamedProvider", "Attempt ${attempts + 1} for stream URL: $streamUrl")
                    try {
                        if (extractor.getUrl(streamUrl, matchId, source, streamNo, subtitleCallback, callback)) {
                            success = true
                            break
                        }
                    } catch (e: Exception) {
                        Log.e("StreamedProvider", "Failed attempt ${attempts + 1} for $streamUrl: ${e.message}")
                    }
                    attempts++
                }
                attempts = 0
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
}

class StreamedMediaExtractor {
    private val fetchUrl = "https://embedstreams.top/fetch"
    private val cookieUrl = "https://fishy.streamed.su/api/event"
    private val channelUrl = "https://ann.embedstreams.top/v1/channel"
    private val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Content-Type" to "application/json",
        "Accept" to "*/*",
        "Origin" to "https://embedstreams.top",
        "Sec-Ch-Ua" to "\"Not A(Brand\";v=\"8\", \"Chromium\";v=\"132\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\""
    )
    private val fallbackDomains = listOf(
        "rr.buytommy.top",
        "p2-panel.streamed.su",
        "streamed.su",
        "embedstreams.top",
        "ann.embedstreams.top"
    )
    private val cookieCache = mutableMapOf<String, String>()
    private var cfClearance: String? = null

    suspend fun getUrl(
        streamUrl: String,
        matchId: String,
        source: String,
        streamNo: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StreamedMediaExtractor", "Starting extraction for: $streamUrl")

        // Handle Cloudflare challenge
        if (!bypassCloudflareChallenge(streamUrl)) {
            Log.e("StreamedMediaExtractor", "Cloudflare challenge bypass failed")
            return false
        }

        // Fetch stream page cookies
        val streamResponse = try {
            app.get(streamUrl, headers = baseHeaders + (cfClearance?.let { mapOf("Cookie" to "cf_clearance=$it") } ?: emptyMap()), timeout = 20)
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Stream page fetch failed: ${e.message}")
            return false
        }
        val streamCookies = streamResponse.cookies
        Log.d("StreamedMediaExtractor", "Stream cookies: $streamCookies")

        // Fetch event cookies
        val eventCookies = fetchEventCookies(streamUrl, streamUrl)
        if (eventCookies.isEmpty()) {
            Log.w("StreamedMediaExtractor", "No event cookies obtained, proceeding with stream cookies")
        }
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
        if (combinedCookies.isEmpty()) {
            Log.e("StreamedMediaExtractor", "No cookies obtained")
            return false
        }
        Log.d("StreamedMediaExtractor", "Combined cookies: $combinedCookies")

        // POST to channel endpoint
        val channelSuccess = validateChannel(streamUrl, matchId, source, streamNo, combinedCookies)
        if (!channelSuccess) {
            Log.w("StreamedMediaExtractor", "Channel validation failed, proceeding anyway")
        }

        // POST to fetch encrypted string

        var retry = true
        var encryptedResponse: String = ""
        while (retry) {
            try {
                val postData = mapOf(
                    "source" to source,
                    "id" to matchId,
                    "streamNo" to streamNo.toString()
                )
                val embedReferer = "https://embedstreams.top/embed/$source/$matchId/$streamNo"
                val fetchHeaders = baseHeaders + mapOf(
                    "Referer" to embedReferer,
                    "Cookie" to combinedCookies
                )
                Log.d("StreamedMediaExtractor", "Fetching with data: $postData and headers: $fetchHeaders")

                val response = app.post(fetchUrl, headers = fetchHeaders, json = postData, timeout = 20)
                Log.d("StreamedMediaExtractor", "Fetch response code: ${response.code}")
                if (response.code != 200) {
                    Log.e("StreamedMediaExtractor", "Fetch failed with code: ${response.code}")
                    return false
                }
                encryptedResponse = response.text
                retry = false
            } catch (e: Exception) {
                Log.e("StreamedMediaExtractor", "Fetch failed: ${e.message}")
                retry = false
                return false
            }
        }
        Log.d("StreamedMediaExtractor", "Encrypted response: $encryptedResponse")

        // Decrypt using Deno
        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        val decryptResponse = try {
            app.post(decryptUrl, json = decryptPostData, headers = mapOf("Content-Type" to "application/json"), timeout = 20)
                .parsedSafe<Map<String, String>>()
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Decryption request failed: ${e.message}")
            return false
        }
        val decryptedPath = decryptResponse?.get("decrypted") ?: return false.also {
            Log.e("StreamedMediaExtractor", "Decryption failed or no 'decrypted' key")
        }
        Log.d("StreamedMediaExtractor", "Decrypted path: $decryptedPath")

        // Parse query parameters from decrypted path
        val urlParts = decryptedPath.split("?")
        val basePath = urlParts[0]
        val queryParams = if (urlParts.size > 1) "?${urlParts[1]}" else ""
        Log.d("StreamedMediaExtractor", "Base path: $basePath, Query params: $queryParams")

        // Construct M3U8 URL
        val m3u8BaseUrl = "https://rr.buytommy.top$basePath"
        val m3u8Headers = baseHeaders + mapOf(
            "Referer" to "https://embedstreams.top/embed/$source/$matchId/$streamNo",
            "Cookie" to combinedCookies
        )

        // Test M3U8 with fallbacks
        for (domain in fallbackDomains) {
            try {
                val testUrl = m3u8BaseUrl.replace("rr.buytommy.top", domain) + queryParams
                Log.d("StreamedMediaExtractor", "Testing M3U8 URL: $testUrl")
                val testResponse = app.get(testUrl, headers = m3u8Headers, timeout = 20)
                if (testResponse.code == 200 && testResponse.text.contains("#EXTM3U")) {
                    callback.invoke(
                        newExtractorLink(
                            source = "Streamed",
                            name = "$source Stream $streamNo",
                            url = testUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://embedstreams.top/embed/$source/$matchId/$streamNo"
                            this.quality = Qualities.Unknown.value
                            this.headers = m3u8Headers
                        }
                    )
                    Log.d("StreamedMediaExtractor", "M3U8 URL added: $testUrl")
                    return true
                } else {
                    Log.w("StreamedMediaExtractor", "M3U8 test failed for $domain with code: ${testResponse.code}")
                }
            } catch (e: Exception) {
                Log.e("StreamedMediaExtractor", "M3U8 test failed for $domain: ${e.message}")
            }
        }

        Log.e("StreamedMediaExtractor", "All M3U8 tests failed for $m3u8BaseUrl")
        return false
    }

    private suspend fun bypassCloudflareChallenge(streamUrl: String): Boolean {
        val challengeUrl = "https://embedstreams.top/cdn-cgi/challenge-platform/h/b/jsd/r/${generateRandomChallenge()}"
        val challengeHeaders = baseHeaders + mapOf(
            "Content-Type" to "text/plain",
            "Referer" to streamUrl
        )
        try {
            val response = app.post(challengeUrl, headers = challengeHeaders, data = mapOf(), timeout = 20)
            if (response.code == 200) {
                val cookies = response.headers.filter { it.first == "Set-Cookie" }
                    .map { it.second.split(";")[0] }
                cfClearance = cookies.find { it.startsWith("cf_clearance=") }?.substringAfter("cf_clearance=")
                Log.d("StreamedMediaExtractor", "Cloudflare clearance cookie: $cfClearance")
                return cfClearance != null
            } else {
                Log.e("StreamedMediaExtractor", "Cloudflare challenge failed with code: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Cloudflare challenge failed: ${e.message}")
        }
        return false
    }

    private fun generateRandomChallenge(): String {
        val random = (0..999999999).random().toString().padStart(9, '0')
        val timestamp = System.currentTimeMillis() / 1000
        val token = "glcYqzSHGD7QXJkumUKuTM6n07ZU1RDm4RXDgEN8f2A" // Placeholder
        return "$random:$timestamp:$token/93f8dd2b3d0e001d"
    }

    private suspend fun fetchEventCookies(pageUrl: String, referrer: String): String {
        cookieCache[pageUrl]?.let { cached ->
            Log.d("StreamedMediaExtractor", "Using cached cookies for $pageUrl")
            return cached
        }

        val payload = """{"n":"pageview","u":"$pageUrl","d":"streamed.su","r":"$referrer"}"""
        try {
            val response = app.post(
                cookieUrl,
                data = mapOf(),
                headers = baseHeaders + mapOf("Content-Type" to "text/plain"),
                requestBody = payload.toRequestBody("text/plain".toMediaType()),
                timeout = 20
            )
            val cookies = response.headers.filter { it.first == "Set-Cookie" }
                .map { it.second.split(";")[0] }
            val formattedCookies = listOf("_ddg1_", "_ddg8_", "_ddg9_", "_ddg10_")
                .mapNotNull { key -> cookies.find { it.startsWith(key) } }
                .joinToString("; ")
            if (formattedCookies.isNotEmpty()) {
                cookieCache[pageUrl] = formattedCookies
                Log.d("StreamedMediaExtractor", "Cached new cookies: $formattedCookies")
                return formattedCookies
            } else {
                Log.w("StreamedMediaExtractor", "No relevant cookies found in response")
            }
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Failed to fetch event cookies: ${e.message}")
        }
        return ""
    }

    private suspend fun validateChannel(streamUrl: String, matchId: String, source: String, streamNo: Int, cookies: String): Boolean {
        val channelPayload = mapOf(
            "id" to "Q5CttySU6GaAg2AJxCZK9", // Placeholder; ideally extract dynamically
            "p" to "web",
            "v" to "2.14.3",
            "c" to "1"
        )
        val channelHeaders = baseHeaders + mapOf(
            "Content-Type" to "text/plain;charset=UTF-8",
            "Referer" to streamUrl,
            "Cookie" to cookies
        )
        try {
            val response = app.post(channelUrl, headers = channelHeaders, json = channelPayload, timeout = 20)
            Log.d("StreamedMediaExtractor", "Channel response code: ${response.code}")
            return response.code == 200
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Channel validation failed: ${e.message}")
            return false
        }
    }
}