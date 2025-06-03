package ben.smith53

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.Locale
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// Main provider class for streamed.su live sports streams
class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed"
    override var supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

    // List of default sources to try if match details are unavailable
    private val defaultSources = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/vnd.apple.mpegurl, */*",
        "Origin" to "https://embedstreams.top",
        "Accept-Language" to "en-GB,en-US;q=0.9,en;q=0.8",
        "Sec-Ch-Ua" to "\"Not A(Brand\";v=\"8\", \"Chromium\";v=\"132\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\""
    )

    // Define main page categories for sports
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

    // Fetch main page with live matches
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        try {
            val response = app.get(request.data, headers = baseHeaders, interceptor = cloudflareKiller, timeout = 15)
            val listJson = parseJson<List<Match>>(response.text)
            val list = listJson.filter { it.matchSources.isNotEmpty() }.mapNotNull { match ->
                match.id?.let { id ->
                    newLiveSearchResponse(
                        name = match.title,
                        url = "$mainUrl/watch/$id",
                        type = TvType.Live
                    ) {
                        this.posterUrl = "$mainUrl${match.posterPath ?: "/api/images/poster/fallback.webp"}"
                    }
                }
            }
            return newHomePageResponse(
                list = HomePageList(request.name, list, isHorizontalImages = true),
                hasNext = false
            )
        } catch (e: Exception) {
            Log.e("StreamedProvider", "Failed to load main page ${request.data}: ${e.message}", e)
            return newHomePageResponse(emptyList(), hasNext = false)
        }
    }

    // Load stream metadata for a given URL
    override suspend fun load(url: String): LoadResponse {
        try {
            val matchId = url.substringAfterLast("/")
            val title = matchId.replace("-", " ")
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                .replace(Regex("-\\d+$"), "")
            val posterUrl = "$mainUrl/api/images/poster/$matchId.webp"
            val validPosterUrl = withTimeoutOrNull(15000) {
                if (app.head(posterUrl, headers = baseHeaders, interceptor = cloudflareKiller, timeout = 15).isSuccessful) {
                    posterUrl
                } else {
                    "$mainUrl/api/images/poster/fallback.webp"
                }
            } ?: "$mainUrl/api/images/poster/fallback.webp"
            return newLiveStreamLoadResponse(
                name = title,
                url = url,
                dataUrl = url
            ) {
                this.posterUrl = validPosterUrl
                // Support resume by storing matchId and timestamp
                this.syncData = mutableMapOf("matchId" to matchId, "timestamp" to System.currentTimeMillis().toString())
            }
        } catch (e: Exception) {
            Log.e("StreamedProvider", "Failed to load URL $url: ${e.message}", e)
            throw e
        }
    }

    // Fetch stream links for playback
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
        val fetchId = if (matchId.length > 50) matchId.take(50) else matchId

        // Pre-fetch match details to reduce delays
        val matchDetails = try {
            app.get("$mainUrl/api/matches/live/$matchId", headers = baseHeaders, interceptor = cloudflareKiller, timeout = 15).parsedSafe<Match>()
        } catch (e: Exception) {
            Log.w("StreamedProvider", "Failed to fetch match details for $matchId: ${e.message}", e)
            null
        }
        val availableSources = matchDetails?.matchSources?.map { it.sourceName }?.toSet() ?: emptySet()
        val sourcesToProcess = if (availableSources.isNotEmpty()) availableSources.toList() else defaultSources

        // Parallel fetch to reduce spinning
        coroutineScope {
            val deferredLinks = sourcesToProcess.map { source ->
                async {
                    val streamInfos = try {
                        val response = app.get("$mainUrl/api/stream/$source/$matchId", headers = baseHeaders, interceptor = cloudflareKiller, timeout = 15).text
                        val streams = parseJson<List<StreamInfo>>(response).filter { it.embedUrl.isNotBlank() }
                        Log.d("StreamedProvider", "Found ${streams.size} streams for $source/$matchId: $streams")
                        streams
                    } catch (e: Exception) {
                        Log.w("StreamedProvider", "No stream info for $source ($matchId): ${e.message}", e)
                        emptyList()
                    }

                    if (streamInfos.isNotEmpty()) {
                        streamInfos.forEach { stream ->
                            repeat(5) { attempt ->
                                try {
                                    val streamUrl = "$mainUrl/watch/$matchId/$source/${stream.streamNo}"
                                    Log.d("StreamedProvider", "Attempt ${attempt + 1} for $streamUrl (ID: ${stream.id}, Language: ${stream.language}, HD: ${stream.hd})")
                                    if (extractor.getUrl(streamUrl, fetchId, source, stream.streamNo, stream.language, stream.hd, subtitleCallback, callback)) {
                                        success = true
                                    }
                                } catch (e: Exception) {
                                    Log.e("StreamedProvider", "Attempt ${attempt + 1} failed for $source stream ${stream.streamNo}: ${e.message}", e)
                                    if (attempt < 4) delay(1000L * (attempt + 1))
                                }
                            }
                        }
                    } else if (availableSources.isEmpty() && matchDetails != null) {
                        for (streamNo in 1..5) {
                            repeat(5) { attempt ->
                                try {
                                    val streamUrl = "$mainUrl/watch/$matchId/$source/$streamNo"
                                    Log.d("StreamedProvider", "Attempt ${attempt + 1} for fallback $streamUrl")
                                    if (extractor.getUrl(streamUrl, fetchId, source, streamNo, "Unknown", false, subtitleCallback, callback)) {
                                        success = true
                                    }
                                } catch (e: Exception) {
                                    Log.e("StreamedProvider", "Attempt ${attempt + 1} failed for $source stream $streamNo: ${e.message}", e)
                                    if (attempt < 4) delay(1000L * (attempt + 1))
                                }
                            }
                        }
                    }
                }
            }
            deferredLinks.awaitAll()
        }

        Log.d("StreamedProvider", "Load links result for $matchId: success=$success")
        return success
    }

    // Data classes for API responses
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

// Extractor for M3U8 stream links
class StreamedMediaExtractor {
    private val fetchUrl = "https://embedstreams.top/fetch"
    private val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/vnd.apple.mpegurl, */*",
        "Origin" to "https://embedstreams.top",
        "Accept-Language" to "en-GB,en-US;q=0.9,en;q=0.8",
        "Sec-Ch-Ua" to "\"Not A(Brand\";v=\"8\", \"Chromium\";v=\"132\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\""
    )
    private val fallbackDomains = listOf("streamed.su", "embedstreams.top", "rr.buytommy.top", "p2-panel.streamed.su", "ann.embedstreams.top")
    private val linkCache = mutableMapOf<String, Pair<ExtractorLink, Long>>()
    private val cacheTTL = 30 * 60 * 1000L // 30 minutes

    companion object {
        const val TIMEOUT_SECONDS = 15
        const val TIMEOUT_MILLIS = TIMEOUT_SECONDS * 1000L
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
        Log.d("StreamedMediaExtractor", "Extracting: $streamUrl (ID: $streamId, Source: $source, StreamNo: $streamNo)")

        // Use cached link if valid
        linkCache[streamUrl]?.let { (link, timestamp) ->
            if (System.currentTimeMillis() - timestamp < cacheTTL) {
                callback(link)
                Log.d("StreamedMediaExtractor", "Using cached link for $streamUrl")
                return true
            } else {
                linkCache.remove(streamUrl)
                Log.d("StreamedMediaExtractor", "Removed expired cached link for $streamUrl")
            }
        }

        // Check streamed.su availability
        var serverAvailable = false
        repeat(3) { attempt ->
            try {
                if (app.head("https://streamed.su", headers = baseHeaders, interceptor = cloudflareKiller, timeout = 5).isSuccessful) {
                    serverAvailable = true
                    return@repeat
                }
            } catch (e: Exception) {
                Log.w("StreamedMediaExtractor", "Server check attempt ${attempt + 1} failed: ${e.message}")
                if (attempt < 2) delay(1000)
            }
        }
        if (!serverAvailable) {
            Log.e("StreamedMediaExtractor", "streamed.su is unreachable after retries")
            linkCache[streamUrl]?.let { (link, _) ->
                callback(link)
                Log.d("StreamedMediaExtractor", "Using expired cached link as fallback for $streamUrl")
                return true
            }
            return false
        }

        // Fetch stream page for cookies
        val streamResponse = try {
            app.get(streamUrl, headers = baseHeaders, interceptor = cloudflareKiller, timeout = TIMEOUT_MILLIS)
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Failed to fetch stream page $streamUrl: ${e.message}", e)
            return false
        }
        val cookieString = streamResponse.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        Log.d("StreamedMediaExtractor", "Cookies: ${cookieString.take(200)}")

        // Fetch encrypted stream data
        val postData = mapOf("source" to source, "id" to streamId, "streamNo" to streamNo.toString())
        val embedReferer = "https://embedstreams.top/embed/$source/$streamId/$streamNo"
        val fetchHeaders = baseHeaders + mapOf("Referer" to streamUrl, "Cookie" to cookieString)
        val encryptedResponse = try {
            val response = app.post(fetchUrl, headers = fetchHeaders, json = postData, interceptor = cloudflareKiller, timeout = TIMEOUT_MILLIS)
            Log.d("StreamedMediaExtractor", "Fetch response code: ${response.code}")
            if (response.code != 200) {
                Log.e("StreamedMediaExtractor", "Fetch failed for $fetchUrl: code=${response.code}, body=${response.text.take(100)}")
                return false
            }
            response.text.takeIf { it.isNotBlank() } ?: return false.also {
                Log.e("StreamedMediaExtractor", "Empty encrypted response")
            }
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Fetch failed: ${e.message}", e)
            return false
        }
        Log.d("StreamedMediaExtractor", "Encrypted response: ${encryptedResponse.take(100)}")

        // Decrypt stream data with retries
        val decryptResponse = try {
            var decryptResult: Map<String, String>? = null
            repeat(3) { attempt ->
                try {
                    val response = app.post(
                        decryptUrl,
                        json = mapOf("encrypted" to encryptedResponse),
                        headers = mapOf("Content-Type" to "application/json"),
                        timeout = TIMEOUT_MILLIS
                    )
                    decryptResult = response.parsedSafe<Map<String, String>>()
                    if (decryptResult != null) return@repeat
                } catch (e: Exception) {
                    Log.w("StreamedMediaExtractor", "Decryption attempt ${attempt + 1} failed: ${e.message}")
                    if (attempt < 2) delay(1000L * (attempt + 1))
                }
            }
            decryptResult ?: throw Exception("Decryption failed after retries")
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Decryption failed: ${e.message}", e)
            linkCache[streamUrl]?.let { (link, _) ->
                callback(link)
                Log.d("StreamedMediaExtractor", "Using expired cached link as decryption fallback for $streamUrl")
                return true
            }
            return false
        }
        var decryptedPath = decryptResponse["decrypted"]?.takeIf { it.isNotBlank() } ?: return false.also {
            Log.e("StreamedMediaExtractor", "No decrypted path")
        }
        Log.d("StreamedMediaExtractor", "Decrypted path: $decryptedPath")

        // Normalize decrypted path to use /secure/ (fixes rr.buytommy.top/s/ to /secure/)
        decryptedPath = decryptedPath
            .replace("/s/", "/secure/")
            .let { if (!it.startsWith("/secure/") && !it.startsWith("/s/")) "/secure/$it" else it }
        Log.d("StreamedMediaExtractor", "Normalized decrypted path: $decryptedPath")

        // Construct M3U8 URL
        val urlParts = decryptedPath.split("?")
        val basePath = urlParts[0]
        val queryParams = if (urlParts.size > 1) "?${urlParts[1]}" else ""
        val keySuffix = if (source == "bravo") {
            try {
                val keyUrl = "https://rr.buytommy.top$basePath/b.key".replace("/s/", "/secure/")
                if (app.head(keyUrl, headers = baseHeaders, interceptor = cloudflareKiller, timeout = 5).isSuccessful) "/b.key" else ""
            } catch (e: Exception) {
                ""
            }
        } else {
            ""
        }
        val m3u8BaseUrl = "https://rr.buytommy.top$basePath$keySuffix".replace("/s/", "/secure/")
        val m3u8Headers = baseHeaders + mapOf("Referer" to embedReferer, "Cookie" to cookieString, "Origin" to "https://embedstreams.top")

        // Try fallback domains for M3U8
        var linkFound = false
        for (domain in fallbackDomains) {
            val testUrl = m3u8BaseUrl.replace("rr.buytommy.top", domain) + queryParams
            try {
                val response = withTimeoutOrNull(TIMEOUT_MILLIS) {
                    app.get(testUrl, headers = m3u8Headers, interceptor = cloudflareKiller, timeout = TIMEOUT_MILLIS)
                }
                if (response?.code == 200 && response.text.contains("#EXTM3U")) {
                    val link = newExtractorLink(
                        source = "Streamed",
                        name = "$source Stream $streamNo ($language${if (isHd) ", HD" else ""})",
                        url = testUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = embedReferer
                        this.quality = if (isHd) Qualities.P1080.value else Qualities.Unknown.value
                        this.headers = m3u8Headers
                        // Metadata for resume and SurfaceView recovery
                        this.extractorData = mapOf(
                            "streamId" to streamId,
                            "source" to source,
                            "streamNo" to streamNo.toString(),
                            "timestamp" to System.currentTimeMillis().toString(),
                            "retryOnInvalidSurface" to "true"
                        ).toString()
                    }
                    callback(link)
                    linkCache[streamUrl] = link to System.currentTimeMillis()
                    Log.d("StreamedMediaExtractor", "M3U8 added: $testUrl")
                    linkFound = true
                    break
                } else {
                    Log.w("StreamedMediaExtractor", "Invalid M3U8 for $testUrl: code=${response?.code}, body=${response?.text?.take(100)}")
                }
            } catch (e: Exception) {
                Log.e("StreamedMediaExtractor", "M3U8 test failed for $domain: ${e.message}", e)
            }
        }

        // Fallback to default M3U8 if no valid link found
        if (!linkFound) {
            val fallbackUrl = m3u8BaseUrl + queryParams
            val link = newExtractorLink(
                source = "Streamed",
                name = "$source Stream $streamNo ($language${if (isHd) ", HD" else ""})",
                url = fallbackUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = embedReferer
                this.quality = if (isHd) Qualities.P1080.value else Qualities.Unknown.value
                this.headers = m3u8Headers
                this.extractorData = mapOf(
                    "streamId" to streamId,
                    "source" to source,
                    "streamNo" to streamNo.toString(),
                    "timestamp" to System.currentTimeMillis().toString(),
                    "retryOnInvalidSurface" to "true"
                ).toString()
            }
            callback(link)
            linkCache[streamUrl] = link to System.currentTimeMillis()
            Log.w("StreamedMediaExtractor", "Added fallback M3U8: $fallbackUrl")
            linkFound = true
        }

        return linkFound
    }
}