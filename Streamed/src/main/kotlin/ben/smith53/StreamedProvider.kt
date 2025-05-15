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

        sources.forEach { source ->
            for (streamNo in 1..maxStreams) {
                try {
                    val streamUrl = "$mainUrl/watch/$matchId/$source/$streamNo"
                    Log.d("StreamedProvider", "Processing stream URL: $streamUrl")
                    if (extractor.getUrl(streamUrl, matchId, source, streamNo, subtitleCallback, callback)) {
                        success = true
                    }
                } catch (e: Exception) {
                    Log.e("StreamedProvider", "Failed for $source stream $streamNo: ${e.message}")
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
    private val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
    private val challengeBaseUrl = "https://challenges.cloudflare.com/cdn-cgi/challenge-platform/h/g"
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Content-Type" to "application/json",
        "Accept" to "*/*",
        "Accept-Language" to "en-GB,en-US;q=0.9,en;q=0.8",
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

        // Fetch Cloudflare clearance for non-24/7 streams
        if (!isKnown247Stream(matchId)) {
            cfClearance = null // Reset clearance
            if (fetchCloudflareClearance(streamUrl)) {
                Log.d("StreamedMediaExtractor", "Cloudflare clearance obtained: $cfClearance")
            } else {
                Log.w("StreamedMediaExtractor", "Cloudflare clearance failed, proceeding without it")
            }
        } else {
            Log.d("StreamedMediaExtractor", "Skipping Cloudflare for 24/7 stream: $matchId")
        }

        // Fetch stream page cookies
        val streamResponse = try {
            app.get(
                streamUrl,
                headers = baseHeaders + (cfClearance?.let { mapOf("Cookie" to "cf_clearance=$it") } ?: emptyMap())
            )
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Stream page fetch failed: ${e.message}")
            return false
        }
        Log.d("StreamedMediaExtractor", "Stream page response code: ${streamResponse.code}")
        val streamCookies = streamResponse.cookies
        Log.d("StreamedMediaExtractor", "Stream cookies: $streamCookies")

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
            "id" to matchId,
            "streamNo" to streamNo.toString()
        )
        val embedReferer = "https://embedstreams.top/embed/$source/$matchId/$streamNo"
        val fetchHeaders = baseHeaders + mapOf(
            "Referer" to embedReferer,
            "Cookie" to combinedCookies
        )
        Log.d("StreamedMediaExtractor", "Fetching with data: $postData and headers: $fetchHeaders")

        val response = try {
            app.post(fetchUrl, headers = fetchHeaders, json = postData)
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Fetch request failed: ${e.message}")
            return false
        }
        Log.d("StreamedMediaExtractor", "Fetch response code: ${response.code}, body: ${response.text.take(100)}")
        if (response.code != 200) {
            Log.e("StreamedMediaExtractor", "Fetch failed with response: ${response.text.take(100)}")
            return false
        }
        val encryptedResponse = response.text
        Log.d("StreamedMediaExtractor", "Encrypted response: $encryptedResponse")

        // Decrypt using Deno
        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        val decryptResponse = try {
            app.post(decryptUrl, json = decryptPostData, headers = mapOf("Content-Type" to "application/json"))
                .parsedSafe<Map<String, String>>()
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Decryption request failed: ${e.message}")
            return false
        }
        val decryptedPath = decryptResponse?.get("decrypted") ?: return false.also {
            Log.e("StreamedMediaExtractor", "Decryption failed or no 'decrypted' key, response: ${decryptResponse}")
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
                val testResponse = app.get(testUrl, headers = m3u8Headers)
                Log.d("StreamedMediaExtractor", "M3U8 response code for $domain: ${testResponse.code}, body: ${testResponse.text.take(100)}")
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
        val turnstileUrl = "$challengeBaseUrl/turnstile/if/ov2/av0/rcv/4c1qj/0x4AAAAAAAkvKraQY_9hzpmB/auto/fbE/new/normal/auto/"
        val turnstileHeaders = baseHeaders + mapOf(
            "Referer" to streamUrl,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )

        // Step 1: GET turnstile page
        val turnstileResponse = try {
            app.get(turnstileUrl, headers = turnstileHeaders)
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Turnstile fetch failed: ${e.message}")
            return false
        }
        Log.d("StreamedMediaExtractor", "Turnstile response code: ${turnstileResponse.code}")
        if (turnstileResponse.code != 200) {
            Log.e("StreamedMediaExtractor", "Turnstile failed with code: ${turnstileResponse.code}, body: ${turnstileResponse.text.take(100)}")
            return false
        }

        // Extract flow URL from response (simplified, as we have it from network tap)
        val flowUrl = "$challengeBaseUrl/flow/ov1/774330535:1747278818:WUDp6Se8nL3EwO5AalJed8g9kilqv3afqrdjKhjsv4Y/93ffbb2478e2b36d/pjGRW52qRkUpkgzIBNBKPhatSjq7LmDx1USCnpS0yaM-1747281654-1.2.1.1-NxBC.Z7zTsIGuUQBOyZUSj7bUoKYt1SLDzEg4H94VnfXL7Qz3Pi6EwfllA_.wT9N"
        val flowHeaders = baseHeaders + mapOf(
            "Referer" to turnstileUrl,
            "Content-Type" to "text/plain;charset=UTF-8",
            "Origin" to "https://challenges.cloudflare.com",
            "Cf-Chl" to "pjGRW52qRkUpkgzIBNBKPhatSjq7LmDx1USCnpS0yaM-1747281654-1.2.1.1-NxBC.Z7zTsIGuUQBOyZUSj7bUoKYt1SLDzEg4H94VnfXL7Qz3Pi6EwfllA_.wT9N",
            "Cf-Chl-Ra" to "bl35arS1b2dT..."
        )

        // Step 2: POST to flow URL
        val flowResponse = try {
            app.post(flowUrl, headers = flowHeaders, data = mapOf())
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Flow POST failed: ${e.message}")
            return false
        }
        Log.d("StreamedMediaExtractor", "Flow response code: ${flowResponse.code}")
        if (flowResponse.code != 200) {
            Log.e("StreamedMediaExtractor", "Flow failed with code: ${flowResponse.code}, body: ${flowResponse.text.take(100)}")
            return false
        }

        // Extract cf_clearance cookie
        val cookies = flowResponse.headers.filter { it.first == "Set-Cookie" }
            .map { it.second.split(";")[0] }
        cfClearance = cookies.find { it.startsWith("cf_clearance=") }?.substringAfter("cf_clearance=")
        Log.d("StreamedMediaExtractor", "Cloudflare clearance cookie: $cfClearance")
        return cfClearance != null
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
                requestBody = payload.toRequestBody("text/plain".toMediaType())
            )
            Log.d("StreamedMediaExtractor", "Event cookie response code: ${response.code}")
            val cookies = response.headers.filter { it.first == "Set-Cookie" }
                .map { it.second.split(";")[0] }
            val formattedCookies = listOf("_ddg8_", "_ddg10_", "_ddg9_", "_ddg1_")
                .mapNotNull { key -> cookies.find { it.startsWith(key) } }
                .joinToString("; ")
            if (formattedCookies.isNotEmpty()) {
                cookieCache[pageUrl] = formattedCookies
                Log.d("StreamedMediaExtractor", "Cached new cookies: $formattedCookies")
            } else {
                Log.w("StreamedMediaExtractor", "No relevant cookies found in response")
            }
            return formattedCookies
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Failed to fetch event cookies: ${e.message}")
            return ""
        }
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
        return known247Streams.any { matchId.lowercase().contains(it) }
    }
}