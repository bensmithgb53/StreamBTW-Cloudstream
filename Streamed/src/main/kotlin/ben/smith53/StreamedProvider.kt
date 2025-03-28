package ben.smith53

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.util.zip.GZIPInputStream
import java.io.IOException

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed Sports"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"
    override val hasMainPage = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val hasDownloadSupport = false
    override val instantLinkLoading = true

    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        "Referer" to "https://streamed.su/",
        "Accept-Encoding" to "gzip, deflate, br, zstd",
        "Accept" to "application/json, text/plain, */*"
    )

    private val streamHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Referer" to "https://embedme.top/"
    )

    companion object {
        private const val posterBase = "https://streamed.su/api/images/poster"
        private const val badgeBase = "https://streamed.su/api/images/badge"
        private const val streamsUrl = "https://raw.githubusercontent.com/bensmithgb53/streamed-links/refs/heads/main/streams.json"
        private val mapper = jacksonObjectMapper()
        private val allowedCategories = setOf("football", "darts", "fight")
    }

    data class APIMatch(
        val id: String,
        val title: String,
        val category: String,
        val date: Long,
        val poster: String? = null,
        val popular: Boolean,
        val teams: Teams? = null,
        val sources: List<Source>
    ) {
        data class Teams(
            val home: Team? = null,
            val away: Team? = null
        )
        data class Team(
            val name: String,
            val badge: String? = null
        )
        data class Source(
            val source: String,
            val id: String
        )
    }

    data class StreamLink(
        val matchId: String,
        val source: String,
        val m3u8_url: String
    )

    private suspend fun fetchLiveMatches(): List<HomePageList> {
        val response = app.get("$mainUrl/api/matches/all", headers = apiHeaders, timeout = 30)
        val text = try {
            if (response.headers["Content-Encoding"] == "gzip") {
                GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
            } else {
                response.text
            }
        } catch (e: Exception) {
            return listOf(HomePageList("Error", listOf(newLiveSearchResponse("Failed to load matches", "$mainUrl|error", TvType.Live))))
        }

        val matches: List<APIMatch> = try {
            mapper.readValue(text)
        } catch (e: Exception) {
            return listOf(HomePageList("Error", listOf(newLiveSearchResponse("Failed to parse matches", "$mainUrl|error", TvType.Live))))
        }

        val currentTime = System.currentTimeMillis() / 1000
        val liveMatches = matches
            .filter { it.category in allowedCategories && it.date / 1000 >= currentTime - 86400 }
            .distinctBy { 
                it.teams?.let { teams -> "${teams.home?.name}-${teams.away?.name}" } ?: it.id 
            }

        if (liveMatches.isEmpty()) {
            return listOf(
                HomePageList(
                    "No Live Matches",
                    listOf(newLiveSearchResponse("No live matches available", "$mainUrl|default", TvType.Live)),
                    isHorizontalImages = false
                )
            )
        }

        val groupedMatches = liveMatches.groupBy { it.category.capitalize() }
        return groupedMatches.map { (category, categoryMatches) ->
            val eventList = categoryMatches.map { match ->
                val title = match.teams?.let { teams ->
                    "${teams.home?.name ?: "Team 1"} vs ${teams.away?.name ?: "Team 2"}"
                } ?: match.title
                
                val posterUrl = match.teams?.let { teams ->
                    teams.home?.badge?.let { homeBadge ->
                        teams.away?.badge?.let { awayBadge ->
                            "$posterBase/$homeBadge/$awayBadge.webp"
                        } ?: "$badgeBase/$homeBadge.webp"
                    }
                } ?: match.poster?.let { 
                    val cleanPoster = it.removeSuffix(".webp")
                    if (cleanPoster.startsWith("/")) "$mainUrl/api/images/proxy$cleanPoster.webp" 
                    else "$mainUrl/api/images/proxy/$cleanPoster.webp" 
                }
                
                newLiveSearchResponse(title, match.id, TvType.Live) {
                    this.posterUrl = posterUrl?.takeIf { it.isNotBlank() }
                }
            }
            HomePageList(category, eventList, isHorizontalImages = false)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return newHomePageResponse(fetchLiveMatches())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return fetchLiveMatches().flatMap { it.list }.filter {
            query.lowercase().replace(" ", "") in it.name.lowercase().replace(" ", "")
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val matchId = url.split("|").first().split("/").last()
        val response = app.get("$mainUrl/api/matches/all", headers = apiHeaders, timeout = 30)
        val text = try {
            if (response.headers["Content-Encoding"] == "gzip") {
                GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
            } else {
                response.text
            }
        } catch (e: Exception) {
            throw ErrorLoadingException("Failed to load match data: ${e.message}")
        }

        val matches: List<APIMatch> = try {
            mapper.readValue(text)
        } catch (e: Exception) {
            throw ErrorLoadingException("Failed to parse match data: ${e.message}")
        }

        val match = matches.find { it.id == matchId } ?: throw ErrorLoadingException("Match not found")

        val title = match.teams?.let { teams ->
            "${teams.home?.name ?: "Team 1"} vs ${teams.away?.name ?: "Team 2"}"
        } ?: match.title

        val posterUrl = match.teams?.let { teams ->
            teams.home?.badge?.let { homeBadge ->
                teams.away?.badge?.let { awayBadge ->
                    "$posterBase/$homeBadge/$awayBadge.webp"
                } ?: "$badgeBase/$homeBadge.webp"
            }
        } ?: match.poster?.let { 
            val cleanPoster = it.removeSuffix(".webp")
            if (cleanPoster.startsWith("/")) "$mainUrl/api/images/proxy$cleanPoster.webp" 
            else "$mainUrl/api/images/proxy/$cleanPoster.webp" 
        }

        val streamsJson = try {
            app.get(streamsUrl, headers = apiHeaders, timeout = 30).text
        } catch (e: Exception) {
            "" // Fallback to empty string if stream fetch fails
        }

        val streamLinks: Map<String, StreamLink> = try {
            if (streamsJson.isNotBlank()) mapper.readValue(streamsJson) else emptyMap()
        } catch (e: Exception) {
            emptyMap() // Fallback to empty map if parsing fails
        }

        val defaultStream = streamLinks.values.firstOrNull { it.matchId == matchId && it.m3u8_url.isNotBlank() }?.m3u8_url ?: ""

        return newLiveStreamLoadResponse(title, url, defaultStream) {
            this.apiName = this@StreamedProvider.name
            this.posterUrl = posterUrl?.takeIf { it.isNotBlank() }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val matchId = data.split("|").first().split("/").last()
        val streamsJson = try {
            app.get(streamsUrl, headers = apiHeaders, timeout = 30).text
        } catch (e: Exception) {
            return false
        }

        val streamLinks: Map<String, StreamLink> = try {
            mapper.readValue(streamsJson)
        } catch (e: Exception) {
            return false
        }

        val matchStreams = streamLinks.values.filter { it.matchId == matchId && it.m3u8_url.isNotBlank() }
        if (matchStreams.isEmpty()) return false

        matchStreams.forEachIndexed { index, streamLink ->
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "Source ${index + 1} (${streamLink.source})",
                    url = streamLink.m3u8_url,
                    referer = "https://embedme.top/",
                    quality = if (streamLink.m3u8_url.contains("hd", true)) 720 else -1,
                    isM3u8 = true,
                    headers = streamHeaders
                )
            )
        }
        return true
    }

    private fun String.capitalize(): String {
        return replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
