package ben.smith53

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import android.util.Log
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlinx.coroutines.delay

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

        // Check if the stream is live
        val matchDetailsUrl = "$mainUrl/api/matches/id/$matchId"
        val matchDetails = try {
            val response = app.get(matchDetailsUrl).text
            parseJson<Match>(response)
        } catch (e: Exception) {
            Log.e("StreamedProvider", "Failed to fetch match details: ${e.message}")
            return false
        }

        if (matchDetails.matchSources.isEmpty() || !isStreamLive(matchDetails)) {
            Log.w("StreamedProvider", "Stream is not live or has no sources: $matchId")
            return false
        }

        val extractor = StreamedMediaExtractor()
        var success = false

        sources.forEach { source ->
            for (streamNo in 1..maxStreams) {
                val streamUrl = "$mainUrl/watch/$matchId/$source/$streamNo"
                Log.d("StreamedProvider", "Processing stream URL: $streamUrl")
                if (extractor.getUrl(streamUrl, matchId, source, streamNo, subtitleCallback, callback)) {
                    success = true
                }
            }
        }
        return success
    }

    private fun isStreamLive(match: Match): Boolean {
        // Adjust based on API response; example check for non-empty sources
        // Inspect API for fields like "status": "live" or "startTime"
        return match.matchSources.any { it.sourceName.isNotEmpty() }
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
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Accept" to "*/*",
        "Content-Type" to "application/json"
    )
    private val fallbackDomains = listOf("rr.buytommy.top", "p2-panel.streamed.su", "streamed.su")
    private val cookieCache = mutableMapOf<String, String>()
    private val cloudflareKiller = CloudflareKiller() // Initialize for potential internal use

    // OkHttp Interceptor to manage headers and cookies
    private val streamInterceptor = Interceptor { chain ->
        val request = chain.request()
        val url = request.url.toString()

        // Apply to M3U8 playlists, segments, and keys
        if (url.endsWith(".m3u8") || url.endsWith(".ts") || url.endsWith(".js") || url.contains("/key/")) {
            // Try .js if .ts fails (mimicking proxy's fallback)
            val newUrl = if (url.endsWith(".ts")) {
                url.replace(".ts", ".js")
            } else {
                url
            }
            Log.d("StreamedMediaExtractor", "Intercepting URL: $url -> $newUrl")

            val newRequest = request.newBuilder()
                .url(newUrl)
                .header("Referer", "https://embedstreams.top/")
                .header("Origin", "https://embedstreams.top")
                .header("Cookie", cookieCache[request.url.toString().substringBefore("/")] ?: "")
                .build()
            chain.proceed(newRequest)
        } else {
            chain.proceed(request)
        }
    }

    suspend fun getUrl(
        streamUrl: String,
        matchId: String,
        source: String,
        streamNo: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StreamedMediaExtractor", "Starting extraction for: $streamUrl")

        // Fetch stream page
        val streamResponse = try {
            app.get(
                streamUrl,
                headers = baseHeaders,
                interceptor = streamInterceptor,
                timeout = 15
            )
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Stream page fetch failed: ${e.message}")
            return false
        }
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
        }
        if (combinedCookies.isEmpty()) {
            Log.e("StreamedMediaExtractor", "No cookies obtained")
            return false
        }
        Log.d("StreamedMediaExtractor", "Combined cookies: $combinedCookies")

        // Cache cookies for interceptor
        cookieCache[streamUrl] = combinedCookies

        // POST to fetch encrypted string
        val postData = mapOf(
            "source" to source,
            "id" to matchId,
            "streamNo" to streamNo.toString()
        )
        val embedReferer = "https://embedstreams.top/embed/$source/$matchId/$streamNo"
        val fetchHeaders = baseHeaders + mapOf(
            "Referer" to streamUrl,
            "Origin" to "https://embedstreams.top",
            "Cookie" to combinedCookies
        )
        Log.d("StreamedMediaExtractor", "Fetching with data: $postData and headers: $fetchHeaders")

        // Try fetching encrypted string with fallback domains
        val encryptedResponse = fetchWithFallback(fetchUrl, fetchHeaders, postData)
        if (encryptedResponse == null) {
            Log.e("StreamedMediaExtractor", "Failed to fetch encrypted response")
            return false
        }
        Log.d("StreamedMediaExtractor", "Encrypted response: $encryptedResponse")

        // Decrypt using Deno
        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        val decryptResponse = try {
            app.post(
                decryptUrl,
                json = decryptPostData,
                headers = mapOf("Content-Type" to "application/json"),
                interceptor = streamInterceptor,
                timeout = 15
            ).parsedSafe<Map<String, String>>()
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Decryption request failed: ${e.message}")
            return false
        }
        val decryptedPath = decryptResponse?.get("decrypted") ?: return false.also {
            Log.e("StreamedMediaExtractor", "Decryption failed or no 'decrypted' key")
        }
        Log.d("StreamedMediaExtractor", "Decrypted path: $decryptedPath")

        // Construct and test M3U8 URL with fallback domains
        val m3u8Url = "https://rr.buytommy.top$decryptedPath"
        val m3u8Headers = baseHeaders + mapOf(
            "Referer" to embedReferer,
            "Origin" to "https://embedstreams.top",
            "Cookie" to combinedCookies
        )

        for (domain in fallbackDomains) {
            try {
                val testUrl = m3u8Url.replace("rr.buytommy.top", domain)
                val testResponse = app.get(
                    testUrl,
                    headers = m3u8Headers,
                    timeout = 15,
                    interceptor = streamInterceptor
                )
                if (testResponse.code == 200) {
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
                    Log.w("StreamedMediaExtractor", "M3U8 test failed for $domain with code: ${testResponse.code}")
                }
            } catch (e: Exception) {
                Log.e("StreamedMediaExtractor", "M3U8 test failed for $domain: ${e.message}")
            }
        }

        Log.w("StreamedMediaExtractor", "All M3U8 tests failed for $m3u8Url")
        return false
    }

    private suspend fun fetchWithFallback(
        url: String,
        headers: Map<String, String>,
        postData: Map<String, String>,
        retries: Int = 3
    ): String? {
        for (domain in fallbackDomains) {
            val domainUrl = url.replace("embedstreams.top", domain)
            repeat(retries) { attempt ->
                try {
                    val response = app.post(
                        domainUrl,
                        headers = headers,
                        json = postData,
                        timeout = 15,
                        interceptor = streamInterceptor
                    )
                    Log.d("StreamedMediaExtractor", "Fetch attempt ${attempt + 1} for $domainUrl: code=${response.code}")
                    if (response.code == 200 && response.text.isNotEmpty()) {
                        return response.text
                    }
                } catch (e: Exception) {
                    Log.e("StreamedMediaExtractor", "Fetch attempt ${attempt + 1} failed for $domainUrl: ${e.message}")
                }
                delay(1000) // Wait before retrying
            }
        }
        return null
    }

    private suspend fun fetchEventCookies(pageUrl: String, referrer: String): String {
        cookieCache[pageUrl]?.let { return it }

        val payload = """{"n":"pageview","u":"$pageUrl","d":"streamed.su","r":"$referrer"}"""
        try {
            val response = app.post(
                cookieUrl,
                headers = mapOf("Content-Type" to "text/plain"),
                requestBody = payload.toRequestBody("text/plain".toMediaType()),
                timeout = 15,
                interceptor = streamInterceptor
            )
            val cookies = response.headers.filter { it.first == "Set-Cookie" }
                .map { it.second.split(";")[0] }
            val formattedCookies = listOf("_ddg8_", "_ddg10_", "_ddg9_", "_ddg1_", "__cf_bm", "cf_clearance")
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