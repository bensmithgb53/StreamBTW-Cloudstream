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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed"
    override var supportedaptpes = setOf(TvType.Live)
    override val hasMainPage = true

    private val sources = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")
    private val maxStreams = 4
    private val logTag = "StreamedProvider"
    private val logFile = File("/data/data/com.termux/files/home/streamed_logs.txt")

    init {
        if (!logFile.exists()) logFile.createNewFile()
    }

    private fun logToFile(message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
        val logMessage = "[$timestamp] $message\n"
        try {
            logFile.appendText(logMessage)
        } catch (e: Exception) {
            Log.e(logTag, "Failed to write to log file: ${e.message}")
        }
    }

    // Explicitly use Pair<String, String> to resolve ambiguity
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
        logToFile("Fetching main page: ${request.data}")
        Log.d(logTag, "Fetching main page: ${request.data}")
        try {
            val rawList = app.get(request.data).text
            logToFile("Received response for ${request.data}, length: ${rawList.length}")
            val listJson = parseJson<List<Match>>(rawList)
            logToFile("Parsed ${listJson.size} matches")

            val list = listJson.filter { it.matchSources.isNotEmpty() }.mapNotNull { match ->
                match.id?.let { id ->
                    val url = "$mainUrl/watch/$id"
                    logToFile("Processing match: ${match.title}, URL: $url")
                    newLiveSearchResponse(
                        name = match.title,
                        url = url,
                        type = TvType.Live
                    ) {
                        this.posterUrl = "$mainUrl${match.posterPath ?: "/api/images/poster/fallback.webp"}"
                    }
                }
            }

            logToFile("Returning ${list.size} items for ${request.name}")
            return newHomePageResponse(
                list = listOf(HomePageList(request.name, list, isHorizontalImages = true)),
                hasNext = false
            )
        } catch (e: Exception) {
            logToFile("Error in getMainPage: ${e.message}")
            Log.e(logTag, "Error in getMainPage: ${e.message}", e)
            throw e
        }
    }

    override suspend fun load(url: String): LoadResponse {
        logToFile("Loading URL: $url")
        Log.d(logTag, "Loading URL: $url")
        try {
            val matchId = url.substringAfterLast("/")
            val title = matchId.replace("-", " ")
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                .replace(Regex("-\\d+$"), "")
            val posterUrl = "$mainUrl/api/images/poster/$matchId.webp"
            logToFile("Loaded title: $title, poster: $posterUrl")
            return newLiveStreamLoadResponse(
                name = title,
                url = url,
                dataUrl = url
            ) {
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            logToFile("Error in load: ${e.message}")
            Log.e(logTag, "Error in load: ${e.message}", e)
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
        logToFile("Loading links for matchId: $matchId")
        Log.d(logTag, "Loading links for matchId: $matchId")
        val extractor = StreamedMediaExtractor(logFile)
        var success = false

        sources.forEach { source ->
            for (streamNo in 1..maxStreams) {
                val streamUrl = "$mainUrl/watch/$matchId/$source/$streamNo"
                logToFile("Processing stream URL: $streamUrl")
                Log.d(logTag, "Processing stream URL: $streamUrl")
                if (extractor.getUrl(streamUrl, matchId, source, streamNo, subtitleCallback, callback)) {
                    success = true
                    logToFile("Successfully extracted stream: $streamUrl")
                } else {
                    logToFile("Failed to extract stream: $streamUrl")
                }
            }
        }
        logToFile("loadLinks completed, success: $success")
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

class StreamedMediaExtractor(private val logFile: File) {
    // ... (unchanged from your previous version, included for completeness)
    private val fetchUrl = "https://embedstreams.top/fetch"
    private val cookieUrl = "https://fishy.streamed.su/api/event"
    private val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        "Content-Type" to "application/json"
    )
    private val fallbackDomains = listOf("p2-panel.streamed.su", "streamed.su")
    private val cookieCache = mutableMapOf<String, String>()
    private val logTag = "StreamedMediaExtractor"

    private fun logToFile(message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
        val logMessage = "[$timestamp] $message\n"
        try {
            logFile.appendText(logMessage)
        } catch (e: Exception) {
            Log.e(logTag, "Failed to write to log file: ${e.message}")
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
        logToFile("Starting extraction for: $streamUrl")
        Log.d(logTag, "Starting extraction for: $streamUrl")

        val streamResponse = try {
            app.get(streamUrl, headers = baseHeaders, timeout = 15)
        } catch (e: Exception) {
            logToFile("Stream page fetch failed: ${e.message}")
            Log.e(logTag, "Stream page fetch failed: ${e.message}", e)
            return false
        }
        val streamCookies = streamResponse.cookies
        logToFile("Stream cookies: $streamCookies")
        Log.d(logTag, "Stream cookies: $streamCookies")

        val eventCookies = fetchEventCookies(streamUrl, streamUrl)
        logToFile("Event cookies: $eventCookies")
        Log.d(logTag, "Event cookies: $eventCookies")

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
            logToFile("No cookies obtained")
            Log.e(logTag, "No cookies obtained")
            return false
        }
        logToFile("Combined cookies: $combinedCookies")
        Log.d(logTag, "Combined cookies: $combinedCookies")

        val postData = mapOf(
            "source" to source,
            "id" to matchId,
            "streamNo" to streamNo.toString()
        )
        val embedReferer = "https://embedstreams.top/embed/$source/$matchId/$streamNo"
        val fetchHeaders = baseHeaders + mapOf(
            "Referer" to streamUrl,
            "Cookie" to combinedCookies
        )
        logToFile("Fetching with data: $postData")
        Log.d(logTag, "Fetching with data: $postData")

        val encryptedResponse = try {
            val response = app.post(fetchUrl, headers = fetchHeaders, json = postData, timeout = 15)
            logToFile("Fetch response code: ${response.code}")
            Log.d(logTag, "Fetch response code: ${response.code}")
            response.text
        } catch (e: Exception) {
            logToFile("Fetch failed: ${e.message}")
            Log.e(logTag, "Fetch failed: ${e.message}", e)
            return false
        }
        logToFile("Encrypted response: $encryptedResponse")
        Log.d(logTag, "Encrypted response: $encryptedResponse")

        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        val decryptResponse = try {
            app.post(decryptUrl, json = decryptPostData, headers = mapOf("Content-Type" to "application/json"))
                .parsedSafe<Map<String, String>>()
        } catch (e: Exception) {
            logToFile("Decryption request failed: ${e.message}")
            Log.e(logTag, "Decryption request failed: ${e.message}", e)
            return false
        }
        val decryptedPath = decryptResponse?.get("decrypted") ?: return false.also {
            logToFile("Decryption failed or no 'decrypted' key")
            Log.e(logTag, "Decryption failed or no 'decrypted' key")
        }
        logToFile("Decrypted path: $decryptedPath")
        Log.d(logTag, "Decrypted path: $decryptedPath")

        val m3u8Url = "https://rr.buytommy.top$decryptedPath"
        val m3u8Headers = baseHeaders + mapOf(
            "Referer" to embedReferer,
            "Cookie" to combinedCookies
        )

        for (domain in listOf("rr.buytommy.top") + fallbackDomains) {
            try {
                val testUrl = m3u8Url.replace("rr.buytommy.top", domain)
                logToFile("Testing M3U8 URL: $testUrl")
                Log.d(logTag, "Testing M3U8 URL: $testUrl")
                val testResponse = app.get(testUrl, headers = m3u8Headers, timeout = 15)
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
                    logToFile("M3U8 URL added: $testUrl")
                    Log.d(logTag, "M3U8 URL added: $testUrl")
                    return true
                } else {
                    logToFile("M3U8 test failed for $domain with code: ${testResponse.code}")
                    Log.w(logTag, "M3U8 test failed for $domain with code: ${testResponse.code}")
                }
            } catch (e: Exception) {
                logToFile("M3U8 test failed for $domain: ${e.message}")
                Log.e(logTag, "M3U8 test failed for $domain: ${e.message}", e)
            }
        }

        callback.invoke(
            newExtractorLink(
                source = "Streamed",
                name = "$source Stream $streamNo",
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = embedReferer
                this.quality = Qualities.Unknown.value
                this.headers = m3u8Headers
            }
        )
        logToFile("M3U8 test failed but added anyway: $m3u8Url")
        Log.d(logTag, "M3U8 test failed but added anyway: $m3u8Url")
        return true
    }

    private suspend fun fetchEventCookies(pageUrl: String, referrer: String): String {
        cookieCache[pageUrl]?.let { return it }

        val payload = """{"n":"pageview","u":"$pageUrl","d":"streamed.su","r":"$referrer"}"""
        logToFile("Fetching event cookies for: $pageUrl")
        Log.d(logTag, "Fetching event cookies for: $pageUrl")
        try {
            val response = app.post(
                cookieUrl,
                data = mapOf(),
                headers = mapOf("Content-Type" to "text/plain"),
                requestBody = payload.toRequestBody("text/plain".toMediaType()),
                timeout = 15
            )
            val cookies = response.headers.filter { it.first == "Set-Cookie" }
                .map { it.second.split(";")[0] }
            val formattedCookies = listOf("_ddg8_", "_ddg10_", "_ddg9_", "_ddg1_")
                .mapNotNull { key -> cookies.find { it.startsWith(key) } }
                .joinToString("; ")
            if (formattedCookies.isNotEmpty()) {
                cookieCache[pageUrl] = formattedCookies
                logToFile("Event cookies fetched: $formattedCookies")
                Log.d(logTag, "Event cookies fetched: $formattedCookies")
                return formattedCookies
            }
        } catch (e: Exception) {
            logToFile("Failed to fetch event cookies: ${e.message}")
            Log.e(logTag, "Failed to fetch event cookies: ${e.message}", e)
        }
        logToFile("No event cookies fetched")
        return ""
    }
}