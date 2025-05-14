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
import java.util.UUID
import android.util.Base64

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
        val maxAttempts = 2

        sources.forEach { source ->
            for (streamNo in 1..maxStreams) {
                var attempts = 0
                val streamUrl = "$mainUrl/watch/$matchId/$source/$streamNo"
                Log.d("StreamedProvider", "Processing stream URL: $streamUrl")
                while (attempts < maxAttempts) {
                    try {
                        if (extractor.getUrl(streamUrl, matchId, source, streamNo, subtitleCallback, callback)) {
                            success = true
                            break
                        }
                    } catch (e: Exception) {
                        Log.e("StreamedProvider", "Attempt ${attempts + 1} failed for $streamUrl: ${e.message}")
                    }
                    attempts++
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
}

class StreamedMediaExtractor {
    private val fetchUrl = "https://embedstreams.top/fetch"
    private val cookieUrl = "https://fishy.streamed.su/api/event"
    private val channelUrl = "https://ann.embedstreams.top/v1/channel"
    private val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
    private val challengeBaseUrl = "https://challenges.cloudflare.com/cdn-cgi/challenge-platform/h/b"
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Accept" to "*/*",
        "Accept-Language" to "en-GB,en-US;q=0.9,en;q=0.8",
        "Content-Type" to "application/json",
        "Origin" to "https://embedstreams.top",
        "Referer" to "https://embedstreams.top/",
        "Sec-Ch-Ua" to "\"Not A(Brand\";v=\"8\", \"Chromium\";v=\"132\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-site"
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

        // Fetch Cloudflare clearance cookie
        if (!fetchCloudflareClearance(streamUrl)) {
            Log.w("StreamedMediaExtractor", "Cloudflare clearance fetch failed, proceeding without it")
        }

        // Fetch stream page cookies
        val streamResponse = try {
            app.get(
                streamUrl,
                headers = baseHeaders + (cfClearance?.let { mapOf("Cookie" to "cf_clearance=$it") } ?: emptyMap()),
                timeout = 20
            )
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Stream page fetch failed: ${e.message}")
            return false
        }
        val streamCookies = streamResponse.cookies
        Log.d("StreamedMediaExtractor", "Stream cookies: $streamCookies")

        // Fetch event cookies (with retry)
        var eventCookies = fetchEventCookies(streamUrl, streamUrl)
        if (eventCookies.isEmpty()) {
            Log.w("StreamedMediaExtractor", "No event cookies obtained, retrying once")
            eventCookies = fetchEventCookies(streamUrl, streamUrl)
            if (eventCookies.isEmpty()) {
                Log.w("StreamedMediaExtractor", "No event cookies after retry, proceeding with stream cookies")
            }
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

        // Perform channel validation (optional for 24/7 streams)
        if (!isKnown247Stream(matchId)) {
            val channelId = generateChannelId(source, matchId, streamNo)
            if (!validateChannel(streamUrl, combinedCookies, channelId)) {
                Log.w("StreamedMediaExtractor", "Channel validation failed, proceeding anyway")
            }
        }

        // POST to fetch encrypted string
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

        var encryptedResponse: String? = null
        var attempts = 0
        val maxAttempts = 2
        while (attempts < maxAttempts && encryptedResponse == null) {
            try {
                val response = app.post(fetchUrl, headers = fetchHeaders, json = postData, timeout = 20)
                Log.d("StreamedMediaExtractor", "Fetch response code: ${response.code}")
                if (response.code == 200) {
                    encryptedResponse = response.text
                } else {
                    Log.w("StreamedMediaExtractor", "Fetch failed with code: ${response.code}, response: ${response.text.take(100)}")
                }
            } catch (e: Exception) {
                Log.e("StreamedMediaExtractor", "Fetch attempt ${attempts + 1} failed: ${e.message}")
            }
            attempts++
        }
        if (encryptedResponse == null) {
            Log.e("StreamedMediaExtractor", "All fetch attempts failed")
            return false
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
            "Referer" to embedReferer,
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
                            this.referer = embedReferer
                            this.quality = Qualities.Unknown.value
                            this.headers = m3u8Headers
                        }
                    )
                    Log.d("StreamedMediaExtractor", "M3U8 URL added: $testUrl")
                    return true
                } else {
                    Log.w("StreamedMediaExtractor", "M3U8 test failed for $domain with code: ${testResponse.code}, response: ${testResponse.text.take(100)}")
                }
            } catch (e: Exception) {
                Log.e("StreamedMediaExtractor", "M3U8 test failed for $domain: ${e.message}")
            }
        }

        Log.e("StreamedMediaExtractor", "All M3U8 tests failed for $m3u8BaseUrl")
        return false
    }

    private suspend fun fetchCloudflareClearance(streamUrl: String): Boolean {
        val turnstileUrl = "$challengeBaseUrl/turnstile/if/ov2/av0/rcv/v99e3/0x4AAAAAAAkvKraQY_9hzpmB/auto/fbE/new/normal/auto/"
        val challengeHeaders = baseHeaders + mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Referer" to streamUrl,
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "Upgrade-Insecure-Requests" to "1"
        )
        val challengeId = generateChallengeId()
        val flowUrl = "$challengeBaseUrl/flow/ov1/1803875011:1747211167:$challengeId/93f9560a7b0d4173/AqckrNa_pDI1r859yUFDpsKp7Y6XPzSGXGKFRTqMPqk-1747214598-1.2.1.1-672A_2F5ZUYW2IP.IL3BxBloTfw1wTuEKtirjmRf68uNytkSMMzNg51318V0eUNE"

        try {
            val flowHeaders = baseHeaders + mapOf(
                "Content-Type" to "text/plain;charset=UTF-8",
                "Referer" to turnstileUrl,
                "Cf-Chl" to "AqckrNa_pDI1r859yUFDpsKp7Y6XPzSGXGKFRTqMPqk-1747214598-1.2.1.1-672A_2F5ZUYW2IP.IL3BxBloTfw1wTuEKtirjmRf68uNytkSMMzNg51318V0eUNE",
                "Cf-Chl-Ra" to "AqckrNa_pDI1r859yUFDpsKp7Y6XPzSGXGKFRTqMPqk-1747214598-1.2.1.1-672A_2F5ZUYW2IP.IL3BxBloTfw1wTuEKtirjmRf68uNytkSMMzNg51318V0eUNE"
            )
            val response = app.post(flowUrl, headers = flowHeaders, data = mapOf(), timeout = 20)
            if (response.code == 200) {
                val cookies = response.headers.filter { it.first == "Set-Cookie" }
                    .map { it.second.split(";")[0] }
                cfClearance = cookies.find { it.startsWith("cf_clearance=") }?.substringAfter("cf_clearance=")
                Log.d("StreamedMediaExtractor", "Cloudflare clearance cookie: $cfClearance")
                return cfClearance != null
            } else {
                Log.w("StreamedMediaExtractor", "Cloudflare flow request failed with code: ${response.code}, response: ${response.text.take(100)}")
            }
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Cloudflare challenge failed: ${e.message}")
        }
        return false
    }

    private fun generateChallengeId(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val randomPart = (1..64).map { chars.random() }.joinToString("")
        val timestamp = System.currentTimeMillis() / 1000
        return "$randomPart-$timestamp-1.2.1.1-${UUID.randomUUID().toString().replace("-", "").substring(0, 32)}"
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
            val formattedCookies = listOf("_ddg8_", "_ddg10_", "_ddg9_", "_ddg1_")
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

    private suspend fun validateChannel(streamUrl: String, cookies: String, channelId: String): Boolean {
        val channelPayload = mapOf(
            "id" to channelId,
            "p" to "web",
            "v" to "2.14.3",
            "c" to "1"
        )
        val channelHeaders = baseHeaders + mapOf(
            "Content-Type" to "text/plain;charset=UTF-8",
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

    private fun generateChannelId(source: String, matchId: String, streamNo: Int): String {
        // Generate Base64-encoded channel ID: embedme-$source-$matchId-$streamNo|#[1]
        val rawId = "embedme-$source-$matchId-$streamNo|#[1]"
        return Base64.encodeToString(rawId.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
    }

    private fun isKnown247Stream(matchId: String): Boolean {
        val known247Streams = listOf(
            "wwe-network",
            "sky-sports-f1",
            "tennis-channel",
            "dazn-f1",
            "motogp-qualifying",
            "italian-open",
            "all-sports-24-7"
        ).map { it.lowercase() }
        return matchId.lowercase().containsAnyOf(known247Streams)
    }

    private fun String.containsAnyOf(substrings: List<String>): Boolean {
        return substrings.any { this.contains(it) }
    }
}