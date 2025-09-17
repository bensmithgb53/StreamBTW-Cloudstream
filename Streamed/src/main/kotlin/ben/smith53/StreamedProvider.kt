package ben.smith53

import android.util.Log
import ben.smith53.extractors.StreamedExtractor
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.util.Locale

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed"
    override var supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

    private val maxStreams = 4
    private val defaultSources = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Content-Type" to "application/json",
        "Accept" to "application/vnd.apple.mpegurl, */*",
        "Origin" to "https://embedstreams.top",
        "Accept-Language" to "en-GB,en-US;q=0.9,en;q=0.8",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Connection" to "keep-alive",
        "Cache-Control" to "no-cache"
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
        var lastException: Exception? = null
        
        // First, test basic connectivity
        try {
            val testResponse = app.head("$mainUrl/api/matches/live/popular", headers = baseHeaders, timeout = 10000)
            Log.d("StreamedProvider", "Connectivity test: HTTP ${testResponse.code}")
        } catch (e: Exception) {
            Log.w("StreamedProvider", "Connectivity test failed: ${e.message}")
        }
        
        // Try up to 3 times with different approaches
        for (attempt in 1..3) {
            try {
                Log.d("StreamedProvider", "Attempt $attempt: Loading main page ${request.data}")
                
                val response = app.get(
                    request.data,
                    headers = baseHeaders,
                    timeout = 30000, // 30 second timeout
                    allowRedirects = true
                )
                
                if (!response.isSuccessful) {
                    Log.w("StreamedProvider", "HTTP ${response.code} for ${request.data}")
                    if (attempt < 3) continue
                }
                
                val rawList = response.text
                if (rawList.isBlank()) {
                    Log.w("StreamedProvider", "Empty response for ${request.data}")
                    if (attempt < 3) continue
                }
                
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
                
                Log.d("StreamedProvider", "Successfully loaded ${list.size} items for ${request.name}")
                return newHomePageResponse(
                    list = listOf(HomePageList(request.name, list, isHorizontalImages = true)),
                    hasNext = false
                )
                
            } catch (e: Exception) {
                lastException = e
                Log.w("StreamedProvider", "Attempt $attempt failed for ${request.data}: ${e.message}")
                
                // Wait before retry (exponential backoff)
                if (attempt < 3) {
                    kotlinx.coroutines.delay(1000L * attempt)
                }
            }
        }
        
        // If individual category failed, try the "all matches" endpoint as fallback
        if (request.data != "$mainUrl/api/matches/all") {
            try {
                Log.d("StreamedProvider", "Trying fallback endpoint for ${request.name}")
                val fallbackResponse = app.get(
                    "$mainUrl/api/matches/all",
                    headers = baseHeaders,
                    timeout = 30000,
                    allowRedirects = true
                )
                
                if (fallbackResponse.isSuccessful) {
                    val fallbackData = fallbackResponse.parsedSafe<Map<String, List<Match>>>()
                    val categoryMatches = fallbackData?.get(request.name.lowercase()) ?: emptyList()
                    
                    val list = categoryMatches.filter { match -> match.matchSources.isNotEmpty() }.map { match ->
                        val url = "$mainUrl/watch/${match.id}"
                        newLiveSearchResponse(
                            name = match.title,
                            url = url,
                            type = TvType.Live
                        ) {
                            this.posterUrl = "$mainUrl${match.posterPath ?: "/api/images/poster/fallback.webp"}"
                        }
                    }.filterNotNull()
                    
                    if (list.isNotEmpty()) {
                        Log.d("StreamedProvider", "Fallback successful: loaded ${list.size} items for ${request.name}")
                        return newHomePageResponse(
                            list = listOf(HomePageList(request.name, list, isHorizontalImages = true)),
                            hasNext = false
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w("StreamedProvider", "Fallback also failed for ${request.name}: ${e.message}")
            }
        }
        
        Log.e("StreamedProvider", "All attempts failed for ${request.data}: ${lastException?.message}")
        return newHomePageResponse(list = emptyList(), hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse {
        try {
            val matchId = url.substringAfterLast("/")
            val title = matchId.replace("-", " ")
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                .replace(Regex("-\\d+$"), "")
            val posterUrl = "$mainUrl/api/images/poster/$matchId.webp"
            val validPosterUrl = try {
                app.head(posterUrl).isSuccessful.let { if (it) posterUrl else "$mainUrl/api/images/poster/fallback.webp" }
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
        val extractor = StreamedExtractor()
        var success = false

        val matchDetails = try {
            app.get("$mainUrl/api/matches/live/$matchId", timeout = StreamedExtractor.EXTRACTOR_TIMEOUT_MILLIS).parsedSafe<Match>()
        } catch (e: Exception) {
            Log.w("StreamedProvider", "Failed to fetch match details for $matchId: ${e.message}")
            null
        }
        val availableSources = matchDetails?.matchSources?.map { it.sourceName }?.toSet() ?: emptySet()
        Log.d("StreamedProvider", "Available sources for $matchId: $availableSources")

        val sourcesToProcess = if (availableSources.isNotEmpty()) availableSources.toList() else defaultSources
        for (source in sourcesToProcess) {
            val streamInfos = try {
                val response = app.get("$mainUrl/api/stream/$source/$matchId", timeout = StreamedExtractor.EXTRACTOR_TIMEOUT_MILLIS).text
                parseJson<List<StreamInfo>>(response).filter { it.embedUrl.isNotBlank() }
            } catch (e: Exception) {
                Log.w("StreamedProvider", "No stream info from API for $source ($matchId): ${e.message}")
                emptyList()
            }

            if (streamInfos.isNotEmpty()) {
                streamInfos.forEach { stream ->
                    repeat(2) { attempt ->
                        try {
                            val streamUrl = "$mainUrl/watch/$matchId/$source/${stream.streamNo}"
                            Log.d("StreamedProvider", "Attempt ${attempt + 1} for $streamUrl")
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