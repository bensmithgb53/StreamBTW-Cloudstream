package ben.smith53

import android.content.Context
import android.util.Log
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
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.Locale

class StreamedProvider(private val context: Context) : MainAPI() {
    override var mainUrl = "https://streamed.pk"
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

    // Force IPv4 to avoid IPv6 connectivity issues
    private val ipv4Headers = baseHeaders + mapOf(
        "Host" to "streamed.pk"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/api/matches/all" to "All Matches"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        try {
            Log.d("StreamedProvider", "Loading all matches from ${request.data}")

            // First test basic connectivity
            try {
                val testResponse = app.head("$mainUrl/", headers = mapOf("User-Agent" to "Mozilla/5.0"), timeout = 10000)
                Log.d("StreamedProvider", "Connectivity test: HTTP ${testResponse.code}")
            } catch (e: Exception) {
                Log.w("StreamedProvider", "Connectivity test failed: ${e.message}")
                // Try a different approach - test with a simple GET
                try {
                    val simpleTest = app.get("$mainUrl/", headers = mapOf("User-Agent" to "Mozilla/5.0"), timeout = 5000)
                    Log.d("StreamedProvider", "Simple GET test: HTTP ${simpleTest.code}")
                } catch (e2: Exception) {
                    Log.w("StreamedProvider", "Simple GET test also failed: ${e2.message}")
                }
            }

            // Try multiple approaches to handle IPv6 connectivity issues
            val response = try {
                // First try with IPv4 headers
                app.get(
                    request.data,
                    headers = ipv4Headers,
                    timeout = 30000,
                    allowRedirects = true
                )
            } catch (e: Exception) {
                Log.w("StreamedProvider", "IPv4 request failed: ${e.message}")
                try {
                    // Try with base headers
                    app.get(
                        request.data,
                        headers = baseHeaders,
                        timeout = 30000,
                        allowRedirects = true
                    )
                } catch (e2: Exception) {
                    Log.w("StreamedProvider", "Base headers also failed: ${e2.message}")
                    // Try with minimal headers
                    app.get(
                        request.data,
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                            "Accept" to "*/*"
                        ),
                        timeout = 30000,
                        allowRedirects = true
                    )
                }
            }

            if (!response.isSuccessful) {
                Log.e("StreamedProvider", "HTTP ${response.code} for ${request.data}")
                return newHomePageResponse(list = emptyList(), hasNext = false)
            }

            val rawData = response.text
            if (rawData.isBlank()) {
                Log.e("StreamedProvider", "Empty response for ${request.data}")
                return newHomePageResponse(list = emptyList(), hasNext = false)
            }

            // Parse the all matches data
            val allMatches = parseJson<List<Match>>(rawData)
            Log.d("StreamedProvider", "Loaded ${allMatches.size} total matches")

            // Group matches by category
            val categoryGroups = allMatches.groupBy { match ->
                when (match.category.lowercase()) {
                    "football" -> "Football"
                    "basketball" -> "Basketball"
                    "baseball" -> "Baseball"
                    "american-football" -> "American Football"
                    "hockey" -> "Hockey"
                    "tennis" -> "Tennis"
                    "rugby" -> "Rugby"
                    "golf" -> "Golf"
                    "billiards" -> "Billiards"
                    "afl" -> "AFL"
                    "darts" -> "Darts"
                    "cricket" -> "Cricket"
                    "motor-sports" -> "Motor Sports"
                    "fight" -> "Fight"
                    else -> "Other"
                }
            }

            // Create home page lists for each category
            val homePageLists = mutableListOf<HomePageList>()

            // Add popular matches as a separate category first
            val popularMatches = allMatches.filter { it.popular && it.matchSources.isNotEmpty() }.map { match ->
                val url = "$mainUrl/watch/${match.id}"
                newLiveSearchResponse(
                    name = match.title,
                    url = url,
                    type = TvType.Live
                ) {
                    this.posterUrl = "$mainUrl${match.posterPath ?: "/api/images/poster/fallback.webp"}"
                }
            }.filterNotNull()

            if (popularMatches.isNotEmpty()) {
                homePageLists.add(HomePageList("Popular", popularMatches, isHorizontalImages = true))
            }

            // Add other categories
            homePageLists.addAll(categoryGroups.map { (categoryName, matches) ->
                val categoryMatches = matches.filter { match -> match.matchSources.isNotEmpty() }.map { match ->
                    val url = "$mainUrl/watch/${match.id}"
                    newLiveSearchResponse(
                        name = match.title,
                        url = url,
                        type = TvType.Live
                    ) {
                        this.posterUrl = "$mainUrl${match.posterPath ?: "/api/images/poster/fallback.webp"}"
                    }
                }.filterNotNull()

                HomePageList(categoryName, categoryMatches, isHorizontalImages = true)
            })

            Log.d("StreamedProvider", "Successfully created ${homePageLists.size} categories with ${homePageLists.sumOf { it.list.size }} total matches")
            return newHomePageResponse(
                list = homePageLists,
                hasNext = false
            )

        } catch (e: Exception) {
            Log.e("StreamedProvider", "Failed to load main page: ${e.message}")

            // If all else fails, try to create a minimal response with a test match
            try {
                Log.d("StreamedProvider", "Attempting fallback with test data")

                // Try one more time with a simple GET request
                try {
                    val simpleResponse = app.get("$mainUrl/api/matches/all", timeout = 15000)
                    if (simpleResponse.isSuccessful) {
                        Log.d("StreamedProvider", "Simple request succeeded, retrying main logic")
                        val allMatches = parseJson<List<Match>>(simpleResponse.text)
                        Log.d("StreamedProvider", "Fallback loaded ${allMatches.size} matches")

                        // Group matches by category
                        val categoryGroups = allMatches.groupBy { match ->
                            when (match.category.lowercase()) {
                                "football" -> "Football"
                                "basketball" -> "Basketball"
                                "baseball" -> "Baseball"
                                "american-football" -> "American Football"
                                "hockey" -> "Hockey"
                                "tennis" -> "Tennis"
                                "rugby" -> "Rugby"
                                "golf" -> "Golf"
                                "billiards" -> "Billiards"
                                "afl" -> "AFL"
                                "darts" -> "Darts"
                                "cricket" -> "Cricket"
                                "motor-sports" -> "Motor Sports"
                                "fight" -> "Fight"
                                else -> "Other"
                            }
                        }

                        // Create home page lists for each category
                        val homePageLists = mutableListOf<HomePageList>()

                        // Add popular matches as a separate category first
                        val popularMatches = allMatches.filter { it.popular && it.matchSources.isNotEmpty() }.map { match ->
                            val url = "$mainUrl/watch/${match.id}"
                            newLiveSearchResponse(
                                name = match.title,
                                url = url,
                                type = TvType.Live
                            ) {
                                this.posterUrl = "$mainUrl${match.posterPath ?: "/api/images/poster/fallback.webp"}"
                            }
                        }.filterNotNull()

                        if (popularMatches.isNotEmpty()) {
                            homePageLists.add(HomePageList("Popular", popularMatches, isHorizontalImages = true))
                        }

                        // Add other categories
                        homePageLists.addAll(categoryGroups.map { (categoryName, matches) ->
                            val categoryMatches = matches.filter { match -> match.matchSources.isNotEmpty() }.map { match ->
                                val url = "$mainUrl/watch/${match.id}"
                                newLiveSearchResponse(
                                    name = match.title,
                                    url = url,
                                    type = TvType.Live
                                ) {
                                    this.posterUrl = "$mainUrl${match.posterPath ?: "/api/images/poster/fallback.webp"}"
                                }
                            }.filterNotNull()

                            HomePageList(categoryName, categoryMatches, isHorizontalImages = true)
                        })

                        return newHomePageResponse(
                            list = homePageLists,
                            hasNext = false
                        )
                    }
                } catch (e: Exception) {
                    Log.w("StreamedProvider", "Simple fallback also failed: ${e.message}")
                }

                // If everything fails, show test match
                val testMatch = Match(
                    id = "test-match",
                    title = "Test Match - Check Connection",
                    category = "other",
                    date = System.currentTimeMillis(),
                    posterPath = "/api/images/poster/fallback.webp",
                    popular = true,
                    matchSources = listOf(
                        MatchSource("alpha", "test-id")
                    )
                )

                val testList = listOf(
                    newLiveSearchResponse(
                        name = testMatch.title,
                        url = "$mainUrl/watch/${testMatch.id}",
                        type = TvType.Live
                    ) {
                        this.posterUrl = "$mainUrl${testMatch.posterPath}"
                    }
                )

                return newHomePageResponse(
                    list = listOf(HomePageList("Connection Test", testList, isHorizontalImages = true)),
                    hasNext = false
                )
            } catch (fallbackException: Exception) {
                Log.e("StreamedProvider", "Fallback also failed: ${fallbackException.message}")
                return newHomePageResponse(list = emptyList(), hasNext = false)
            }
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
        Log.d("StreamedProvider", "Loading links for: $data")

        try {
            // Extract match ID from URL
            val matchId = data.substringAfterLast("/")
            if (matchId.isBlank()) {
                Log.e("StreamedProvider", "Invalid matchId: $matchId")
                return false
            }

            // Get all matches to find available sources
            val allMatchesResponse = app.get("$mainUrl/api/matches/all", headers = baseHeaders, timeout = 30000)
            if (!allMatchesResponse.isSuccessful) {
                Log.e("StreamedProvider", "Failed to get matches: HTTP ${allMatchesResponse.code}")
                return false
            }

            val allMatches = parseJson<List<Match>>(allMatchesResponse.text)
            val matchDetails = allMatches.find { it.id == matchId }

            if (matchDetails == null) {
                Log.e("StreamedProvider", "Match not found: $matchId")
                return false
            }

            val availableSources = matchDetails.matchSources.map { it.sourceName }
            Log.d("StreamedProvider", "Available sources for $matchId: $availableSources")

            val extractor = ben.smith53.extractors.StreamedExtractor(context)
            val allStreamLinks = mutableListOf<ExtractorLink>()
            var foundAnyLinks = false

            // Collect all embed URLs first
            val allEmbedUrls = mutableListOf<Pair<String, StreamInfo>>()
            
            // Try each available source
            for (source in availableSources) {
                try {
                    Log.d("StreamedProvider", "Trying source: $source")
                    
                    // Get stream info from the API
                    val streamResponse = app.get(
                        "$mainUrl/api/stream/$source/$matchId",
                        headers = baseHeaders,
                        timeout = 30000
                    )
                    
                    if (!streamResponse.isSuccessful) {
                        Log.w("StreamedProvider", "Stream API failed for $source: HTTP ${streamResponse.code}")
                        continue
                    }
                    
                    val streamInfos = parseJson<List<StreamInfo>>(streamResponse.text)
                    Log.d("StreamedProvider", "Found ${streamInfos.size} streams for $source")
                    
                    // Collect all embed URLs
                    for (streamInfo in streamInfos) {
                        val embedUrl = streamInfo.embedUrl
                        allEmbedUrls.add(Pair(source, streamInfo))
                        Log.d("StreamedProvider", "Collected embed URL: $embedUrl")
                    }
                    
                } catch (e: Exception) {
                    Log.e("StreamedProvider", "Error processing source $source: ${e.message}")
                }
            }

            Log.d("StreamedProvider", "Total embed URLs collected: ${allEmbedUrls.size}")

            // Now extract all streams at once
            for ((source, streamInfo) in allEmbedUrls) {
                try {
                    Log.d("StreamedProvider", "Extracting from embed URL: ${streamInfo.embedUrl}")
                    
                    val links = extractor.getUrl(streamInfo.embedUrl, null)
                    if (!links.isNullOrEmpty()) {
                        Log.d("StreamedProvider", "Found ${links.size} links for ${source} Stream ${streamInfo.streamNo}")
                        links.forEach { link ->
                            // Create a new link with updated name
                            val updatedLink = newExtractorLink(
                                source = link.source,
                                name = "${source} Stream ${streamInfo.streamNo} (${streamInfo.language}${if (streamInfo.hd) ", HD" else ""})",
                                url = link.url,
                                type = link.type
                            ) {
                                this.referer = link.referer
                                this.quality = if (streamInfo.hd) Qualities.P1080.value else Qualities.Unknown.value
                                this.headers = link.headers
                            }
                            allStreamLinks.add(updatedLink)
                        }
                    } else {
                        Log.w("StreamedProvider", "No links found for ${source} Stream ${streamInfo.streamNo}")
                    }
                    
                } catch (e: Exception) {
                    Log.e("StreamedProvider", "Error processing ${source} Stream ${streamInfo.streamNo}: ${e.message}")
                }
            }

            // Send all links to CloudStream at once
            if (allStreamLinks.isNotEmpty()) {
                Log.d("StreamedProvider", "Sending ${allStreamLinks.size} total links to CloudStream")
                allStreamLinks.forEach { link ->
                    callback(link)
                }
                foundAnyLinks = true
            }

            if (!foundAnyLinks) {
                Log.e("StreamedProvider", "No links found by extractor")
                return false
            }

            Log.d("StreamedProvider", "Successfully loaded links")
            return true

        } catch (e: Exception) {
            Log.e("StreamedProvider", "Error in loadLinks: ${e.message}")
            return false
        }
    }


    data class Match(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("category") val category: String,
        @JsonProperty("date") val date: Long,
        @JsonProperty("poster") val posterPath: String? = null,
        @JsonProperty("popular") val popular: Boolean = false,
        @JsonProperty("sources") val matchSources: List<MatchSource> = emptyList(),
        @JsonProperty("teams") val teams: Teams? = null,
        @JsonProperty("finished") val finished: Boolean = false
    )

    data class Teams(
        @JsonProperty("home") val home: Team,
        @JsonProperty("away") val away: Team
    )

    data class Team(
        @JsonProperty("name") val name: String,
        @JsonProperty("badge") val badge: String? = null
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