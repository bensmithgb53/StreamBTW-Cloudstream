package ben.smith53

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import java.util.zip.GZIPInputStream

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed"
    override val supportedTypes = setOf(TvType.Live) // Fixed: Use TvType.Live
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = false

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "*/*"
    )

    private val mapper = ObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    data class APIMatch(
        val id: String,
        val title: String,
        val teams: Teams? = null
    )

    data class Teams(
        val home: Team? = null,
        val away: Team? = null
    )

    data class Team(
        val name: String
    )

    data class Stream(
        val id: String,
        val streamNo: Int,
        val hd: Boolean,
        val source: String
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? { // Fixed: Added ? and correct signature
        val response = app.get("$mainUrl/api/matches/all", headers = headers, timeout = 30)
        val matchesText = if (response.headers["Content-Encoding"]?.equals("gzip") == true) {
            GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
        } else {
            response.text
        }
        println("Fetched matches from API: $matchesText")

        val matches = mapper.readValue(matchesText, object : TypeReference<List<APIMatch>>() {}) // Fixed: Use TypeReference
        println("Parsed ${matches.size} matches")

        val homePageList = matches.mapNotNull { match ->
            val teamSlug = match.teams?.let { teams ->
                "${teams.home?.name?.lowercase()?.replace(" ", "-")}-vs-${teams.away?.name?.lowercase()?.replace(" ", "-")}"
            } ?: match.title.lowercase().replace(" ", "-")
            val url = "$teamSlug|alpha|$match.id"
            HomePageList(
                match.title,
                listOf(
                    newLiveSearchResponse(match.title, url, this.name) {
                        this.type = TvType.Live
                    }
                )
            )
        }
        return newHomePageResponse(homePageList)
    }

    private suspend fun fetchEncryptedData(sourceType: String, matchId: String, streamNo: String): String? {
        return try {
            val response = app.post(
                "https://embedme.top/fetch",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
                    "Referer" to "https://embedme.top/",
                    "Origin" to "https://embedme.top",
                    "Content-Type" to "application/json",
                    "Accept" to "*/*"
                ),
                json = mapOf(
                    "source" to sourceType,
                    "id" to matchId,
                    "streamNo" to streamNo
                )
            )
            if (response.isSuccessful) response.text else null
        } catch (e: Exception) {
            println("Failed to fetch encrypted data: ${e.message}")
            null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        println("Original URL received: $url")
        val correctedUrl = if (url.startsWith("$mainUrl/watch/")) {
            val parts = url.split("/")
            val matchId = parts[parts.indexOf("watch") + 1].split("-").last()
            val sourceType = parts[parts.size - 2]
            val sourceId = parts.last()
            "$matchId|$sourceType|$sourceId"
        } else {
            url
        }
        val parts = correctedUrl.split("|")
        val matchId = parts[0].split("/").last()
        val sourceType = if (parts.size > 1) parts[1] else "alpha"
        val sourceId = if (parts.size > 2) parts[2] else matchId

        println("Loading stream with corrected URL: $correctedUrl")
        println("Parsed: matchId=$matchId, sourceType=$sourceType, sourceId=$sourceId")

        val matchResponse = app.get("$mainUrl/api/matches/all", headers = headers, timeout = 30)
        val matchesText = if (matchResponse.headers["Content-Encoding"]?.equals("gzip") == true) {
            GZIPInputStream(matchResponse.body.byteStream()).bufferedReader().use { it.readText() }
        } else {
            matchResponse.text
        }
        val matches = mapper.readValue(matchesText, object : TypeReference<List<APIMatch>>() {}) // Fixed: Use TypeReference
        val match = matches.find { it.id == matchId } ?: throw ErrorLoadingException("Match not found: $matchId")
        val teamSlug = match.teams?.let { teams ->
            "${teams.home?.name?.lowercase()?.replace(" ", "-")}-vs-${teams.away?.name?.lowercase()?.replace(" ", "-")}"
        } ?: match.title.lowercase().replace(" ", "-")
        println("Team slug: $teamSlug")

        val streamUrl = "$mainUrl/api/stream/$sourceType/$sourceId"
        val streamApiHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
            "Referer" to "https://streamed.su/"
        )
        val response = app.get(streamUrl, headers = streamApiHeaders, timeout = 30)
        val text = if (response.headers["Content-Encoding"]?.equals("gzip") == true) {
            GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
        } else {
            response.text
        }
        println("Stream API response for $streamUrl: $text")

        if (!response.isSuccessful || text.contains("Not Found")) {
            println("Stream not found for $streamUrl, status=${response.code}")
            return newLiveStreamLoadResponse("Stream Unavailable - $matchId", correctedUrl, "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8") {
                this.plot = "The requested stream could not be found. Using a test stream instead."
            }
        }

        val streams = mapper.readValue(text, object : TypeReference<List<Stream>>() {}) // Fixed: Use TypeReference
        if (streams.isEmpty()) {
            println("No streams available for $streamUrl")
            return newLiveStreamLoadResponse("No Streams Available - $matchId", correctedUrl, "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8") {
                this.plot = "No streams were returned for this match. Using a test stream instead."
            }
        }

        val firstStream = streams.first()
        println("Selected default stream: id=${firstStream.id}, streamNo=${firstStream.streamNo}, hd=${firstStream.hd}, source=${firstStream.source}")
        val encrypted = fetchEncryptedData(sourceType, matchId, firstStream.streamNo.toString())
            ?: throw ErrorLoadingException("Failed to fetch encrypted stream data")
        println("Encrypted stream data: $encrypted")

        // Static M3U8 fetch (to be refined with Network data)
        val m3u8Url = try {
            val m3u8Response = app.get(
                "https://rr.vipstreams.in/s/S5sTu7faadwl9LhcLRFyyPX7XEULMSD_PTPKC2cKyCPdatz8HTuQecYh3Et3g8OD/0vTGmyGsnDSU99wFeaGAR_X0irbFxOQ92t3IihtLAO8qttPQm1XhIlPPt-T54YNZi4o4abRCScehK5vrDFqKtPukx_ZBCKDbGSDfEnVU85I/8KsQiiYtSHdESxeBDxS05hnZEEpT8nyWTTW4fA68hVXIyNvjSKPwASFVCTp8aF1d/strm.m3u8",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
                    "Referer" to "https://embedme.top/"
                ),
                params = mapOf(
                    "md5" to "JJzSw2ibXOxltAhIifLpOA", // Static, needs dynamic fetch
                    "expiry" to "1743247843" // Static, needs dynamic fetch
                )
            )
            if (m3u8Response.isSuccessful) m3u8Response.url.toString() else throw Exception("M3U8 fetch failed: ${m3u8Response.code}")
        } catch (e: Exception) {
            println("Failed to fetch M3U8: ${e.message}")
            "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8" // Fallback
        }
        println("Default M3U8 URL: $m3u8Url")

        return newLiveStreamLoadResponse("${firstStream.source} - ${if (firstStream.hd) "HD" else "SD"}", correctedUrl, m3u8Url) {
            if (m3u8Url.contains("test-streams")) {
                this.plot = "Failed to fetch live stream URL. Using a test stream instead."
            }
        }
    }
}