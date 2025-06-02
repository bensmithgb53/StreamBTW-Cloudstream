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

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed"
    override var supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

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
            Log.e("StreamedProvider", "Failed to load main page ${request.data}: ${e.message}")
            return newHomePageResponse(emptyList(), hasNext = false)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        try {
            val matchId = url.substringAfterLast("/")
            val title = matchId.replace("-", " ")
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                .replace(Regex("-\\d+$"), "")
            val posterUrl = "$mainUrl/api/images/poster/$matchId.webp"
            val validPosterUrl = if (app.head(posterUrl, headers = baseHeaders, interceptor = cloudflareKiller, timeout = 10).isSuccessful) {
                posterUrl
            } else {
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
        val fetchId = if (matchId.length > 50) matchId.take(50) else matchId

        val matchDetails = try {
            app.get("$mainUrl/api/matches/live/$matchId", headers = baseHeaders, interceptor = cloudflareKiller, timeout = 15).parsedSafe<Match>()
        } catch (e: Exception) {
            Log.w("StreamedProvider", "Failed to fetch match details for $matchId: ${e.message}")
            null
        }
        val availableSources = matchDetails?.matchSources?.map { it.sourceName }?.toSet() ?: emptySet()
        val sourcesToProcess = if (availableSources.isNotEmpty()) availableSources.toList() else defaultSources

        for (source in sourcesToProcess) {
            val streamInfos = try {
                val response = app.get("$mainUrl/api/stream/$source/$matchId", headers = baseHeaders, interceptor = cloudflareKiller, timeout = 15).text
                parseJson<List<StreamInfo>>(response).filter { it.embedUrl.isNotBlank() }
            } catch (e: Exception) {
                Log.w("StreamedProvider", "No stream info from API for $source ($matchId): ${e.message}")
                emptyList()
            }

            if (streamInfos.isNotEmpty()) {
                streamInfos.forEach { stream ->
                    repeat(3) { attempt ->
                        try {
                            val streamUrl = "$mainUrl/watch/$matchId/$source/${stream.streamNo}"
                            if (extractor.getUrl(streamUrl, fetchId, source, stream.streamNo, stream.language, stream.hd, subtitleCallback, callback)) {
                                success = true
                                return@repeat
                            }
                        } catch (e: Exception) {
                            Log.e("StreamedProvider", "Attempt ${attempt + 1} failed for $source stream ${stream.streamNo}: ${e.message}")
                            if (attempt < 2) delay(2000)
                        }
                    }
                }
            } else if (availableSources.isEmpty() && matchDetails != null) {
                val maxFallbackStreams = streamInfos.maxOfOrNull { it.streamNo }?.coerceAtMost(3) ?: 3
                for (streamNo in 1..maxFallbackStreams) {
                    repeat(3) { attempt ->
                        try {
                            val streamUrl = "$mainUrl/watch/$matchId/$source/$streamNo"
                            if (extractor.getUrl(streamUrl, fetchId, source, streamNo, "Unknown", false, subtitleCallback, callback)) {
                                success = true
                                return@repeat
                            }
                        } catch (e: Exception) {
                            Log.e("StreamedProvider", "Attempt ${attempt + 1} failed for $source stream $streamNo: ${e.message}")
                            if (attempt < 2) delay(2000)
                        }
                    }
                }
            }
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
    private val fallbackDomains = listOf("rr.buytommy.top", "p2-panel.streamed.su", "streamed.su", "embedstreams.top", "ann.embedstreams.top")

    companion object {
        const val TIMEOUT_SECONDS = 30
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

        // Check server availability
        try {
            if (!app.head("https://streamed.su", headers = baseHeaders, interceptor = cloudflareKiller, timeout = 10).isSuccessful) {
                Log.e("StreamedMediaExtractor", "streamed.su is unreachable")
                return false
            }
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Server check failed: ${e.message}")
            return false
        }

        // Fetch stream page
        val streamResponse = try {
            app.get(streamUrl, headers = baseHeaders, interceptor = cloudflareKiller, timeout = TIMEOUT_MILLIS)
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Failed to fetch stream page $streamUrl: ${e.message}")
            return false
        }
        val cookieString = streamResponse.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        Log.d("StreamedMediaExtractor", "Cookies: ${cookieString.take(200)}")

        // POST to fetch encrypted string
        val postData = mapOf("source" to source, "id" to streamId, "streamNo" to streamNo.toString())
        val embedReferer = "https://embedstreams.top/embed/$source/$streamId/$streamNo"
        val fetchHeaders = baseHeaders + mapOf("Referer" to streamUrl, "Cookie" to cookieString)
        val encryptedResponse = try {
            val response = app.post(fetchUrl, headers = fetchHeaders, json = postData, interceptor = cloudflareKiller, timeout = TIMEOUT_MILLIS)
            if (response.code != 200) {
                Log.e("StreamedMediaExtractor", "Fetch failed for $fetchUrl: code=${response.code}, body=${response.text.take(100)}")
                return false
            }
            response.text.takeIf { it.isNotBlank() } ?: return false.also {
                Log.e("StreamedMediaExtractor", "Empty encrypted response")
            }
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Fetch failed: ${e.message}")
            return false
        }

        // Decrypt response
        val decryptResponse = try {
            app.post(
                decryptUrl,
                json = mapOf("encrypted" to encryptedResponse),
                headers = mapOf("Content-Type" to "application/json"),
                timeout = TIMEOUT_MILLIS
            ).parsedSafe<Map<String, String>>()
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Decryption failed: ${e.message}")
            return false
        }
        val decryptedPath = decryptResponse?.get("decrypted")?.takeIf { it.isNotBlank() } ?: return false.also {
            Log.e("StreamedMediaExtractor", "No decrypted path")
        }

        // Construct M3U8 URL
        val urlParts = decryptedPath.split("?")
        val basePath = urlParts[0]
        val queryParams = if (urlParts.size > 1) "?${urlParts[1]}" else ""
        val keySuffix = if (source == "bravo" && app.head("https://rr.buytommy.top$basePath/b.key", headers = baseHeaders, interceptor = cloudflareKiller, timeout = 5).isSuccessful) {
            "/b.key"
        } else {
            ""
        }
        val m3u8BaseUrl = "https://rr.buytommy.top$basePath$keySuffix"
        val m3u8Headers = baseHeaders + mapOf("Referer" to embedReferer, "Cookie" to cookieString, "Origin" to "https://embedstreams.top")

        // Test M3U8 URLs
        var linkFound = false
        for (domain in fallbackDomains) {
            val testUrl = m3u8BaseUrl.replace("rr.buytommy.top", domain) + queryParams
            try {
                val response = app.get(testUrl, headers = m3u8Headers, interceptor = cloudflareKiller, timeout = TIMEOUT_MILLIS)
                if (response.code == 200 && response.text.contains("#EXTM3U")) {
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
                    linkFound = true
                    break
                } else {
                    Log.w("StreamedMediaExtractor", "Invalid M3U8 for $testUrl: code=${response.code}")
                }
            } catch (e: Exception) {
                Log.e("StreamedMediaExtractor", "M3U8 test failed for $domain: ${e.message}")
            }
        }

        // Fallback if no valid M3U8
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
            linkFound = true
        }

        return linkFound
    }
}