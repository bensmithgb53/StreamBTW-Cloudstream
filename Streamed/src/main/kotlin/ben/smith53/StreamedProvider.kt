package ben.smith53

import android.util.Base64
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
import java.util.Locale

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.pk"
    override var name = "Streamed"
    override var supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

    private val maxStreams = 4
    private val defaultSources = listOf("admin", "alpha", "bravo", "charlie", "echo", "foxtrot", "delta", "golf", "hotel", "intel")
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Content-Type" to "application/json",
        "Accept" to "application/vnd.apple.mpegurl, */*",
        "Origin" to "https://streamed.pk",
        "Accept-Language" to "en-GB,en-US;q=0.9,en;q=0.8",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Connection" to "keep-alive",
        "Cache-Control" to "no-cache",
        "Referer" to "https://streamed.pk/"
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

            // Test connectivity with a HEAD request
            try {
                val testResponse = app.head("$mainUrl/", headers = baseHeaders, timeout = 10000)
                Log.d("StreamedProvider", "Connectivity test: HTTP ${testResponse.code}")
            } catch (e: Exception) {
                Log.w("StreamedProvider", "Connectivity test failed: ${e.message}")
            }

            // Fetch matches with retry logic
            val response = try {
                app.get(request.data, headers = ipv4Headers, timeout = 30000, allowRedirects = true)
            } catch (e: Exception) {
                Log.w("StreamedProvider", "IPv4 request failed: ${e.message}")
                try {
                    app.get(request.data, headers = baseHeaders, timeout = 30000, allowRedirects = true)
                } catch (e2: Exception) {
                    Log.w("StreamedProvider", "Base headers failed: ${e2.message}")
                    app.get(request.data, headers = mapOf("User-Agent" to baseHeaders["User-Agent"]!!, "Accept" to "*/*"), timeout = 30000, allowRedirects = true)
                }
            }

            if (!response.isSuccessful || response.text.isBlank()) {
                Log.e("StreamedProvider", "Failed to load matches: HTTP ${response.code}, Response: ${response.text}")
                return newHomePageResponse(list = emptyList(), hasNext = false)
            }

            // Parse matches
            val allMatches = parseJson<List<Match>>(response.text)
            Log.d("StreamedProvider", "Loaded ${allMatches.size} matches")

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

            // Create home page lists
            val homePageLists = mutableListOf<HomePageList>()
            val popularMatches = allMatches
                .filter { it.popular && it.matchSources.isNotEmpty() }
                .mapNotNull { match ->
                    val url = "$mainUrl/watch/${match.id}"
                    newLiveSearchResponse(
                        name = match.title,
                        url = url,
                        type = TvType.Live
                    ) {
                        this.posterUrl = match.posterPath?.let {
                            if (it.startsWith("http")) it else "$mainUrl$it"
                        } ?: "$mainUrl/api/images/poster/fallback.webp"
                    }
                }

            if (popularMatches.isNotEmpty()) {
                homePageLists.add(HomePageList("Popular", popularMatches, isHorizontalImages = true))
            }

            homePageLists.addAll(categoryGroups.map { (categoryName, matches) ->
                val categoryMatches = matches
                    .filter { it.matchSources.isNotEmpty() }
                    .mapNotNull { match ->
                        val url = "$mainUrl/watch/${match.id}"
                        newLiveSearchResponse(
                            name = match.title,
                            url = url,
                            type = TvType.Live
                        ) {
                            this.posterUrl = match.posterPath?.let {
                                if (it.startsWith("http")) it else "$mainUrl$it"
                            } ?: "$mainUrl/api/images/poster/fallback.webp"
                        }
                    }
                HomePageList(categoryName, categoryMatches, isHorizontalImages = true)
            })

            Log.d("StreamedProvider", "Created ${homePageLists.size} categories with ${homePageLists.sumOf { it.list.size }} matches")
            return newHomePageResponse(list = homePageLists, hasNext = false)
        } catch (e: Exception) {
            Log.e("StreamedProvider", "Failed to load main page: ${e.message}")
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
                app.head(posterUrl, headers = baseHeaders).isSuccessful.let { if (it) posterUrl else "$mainUrl/api/images/poster/fallback.webp" }
            } catch (e: Exception) {
                "$mainUrl/api/images/poster/fallback.webp"
            }
            return newLiveStreamLoadResponse(name = title, url = url, dataUrl = url) {
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
        val extractor = StreamedExtractor()
        val links = extractor.getUrl(data, null)

        if (links.isNullOrEmpty()) {
            Log.e("StreamedProvider", "No links found")
            return false
        }

        links.forEach { callback(it) }
        Log.d("StreamedProvider", "Loaded ${links.size} links")
        return true
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
        @JsonProperty("source") val source: String,
        @JsonProperty("m3u8") val m3u8: String? = null // Added for decoded M3U8 URL
    )
}