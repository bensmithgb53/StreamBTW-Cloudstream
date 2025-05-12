package ben.smith53

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.network.CloudflareKiller
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log

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
        val apiUrl = request.data
        Log.d("StreamedProvider", "Fetching main page: $apiUrl")
        val matches = app.get(apiUrl, headers = CloudflareKiller().getCookieHeaders(apiUrl).toMap()).parsedSafe<List<Match>>()
        Log.d("StreamedProvider", "Parsed ${matches?.size ?: 0} matches")
        val home = matches?.mapNotNull {
            newLiveSearchResponse(
                name = it.title,
                url = "$mainUrl/watch/${it.id}",
                type = TvType.Live
            ) {
                this.posterUrl = it.thumbnail
            }
        } ?: emptyList()
        return newHomePageResponse(request.name, home, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val apiUrl = "$mainUrl/api/matches?search=$query"
        Log.d("StreamedProvider", "Searching with: $apiUrl")
        val matches = app.get(apiUrl, headers = CloudflareKiller().getCookieHeaders(apiUrl).toMap()).parsedSafe<List<Match>>()
        Log.d("StreamedProvider", "Found ${matches?.size ?: 0} search results")
        return matches?.mapNotNull {
            newLiveSearchResponse(
                name = it.title,
                url = "$mainUrl/watch/${it.id}",
                type = TvType.Live
            ) {
                this.posterUrl = it.thumbnail
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val matchId = url.substringAfterLast("/")
        val apiUrl = "$mainUrl/api/matches/by-id/$matchId"
        Log.d("StreamedProvider", "Loading match: $apiUrl")
        val match = app.get(apiUrl).parsedSafe<List<Match>>()?.firstOrNull()
            ?: throw ErrorLoadingException("Failed to load match data")
        Log.d("StreamedProvider", "Loaded match: ${match.title}")
        return newLiveStreamLoadResponse(
            name = match.title,
            url = url,
            dataUrl = match.id
        ) {
            this.posterUrl = match.thumbnail
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
        Log.d("StreamedProvider", "Loading links for matchId: $matchId")

        // Optional: Dynamic source filtering (uncomment if logs confirm need)
        /*
        val matchData = app.get("$mainUrl/api/matches/by-id/$matchId").parsedSafe<List<Match>>()?.firstOrNull()
        val validSources = matchData?.matchSources?.map { it.sourceName } ?: sources
        Log.d("StreamedProvider", "Valid sources: $validSources")
        */

        sources.forEach { source ->
            for (streamNo in 1..maxStreams) {
                val streamUrl = "$mainUrl/watch/$matchId/$source/$streamNo"
                Log.d("StreamedProvider", "Processing stream URL: $streamUrl")
                if (extractor.getUrl(streamUrl, matchId, source, streamNo, subtitleCallback, callback)) {
                    success = true
                }
            }
        }
        Log.d("StreamedProvider", "Load links success: $success")
        return success
    }
}

@Serializable
data class Match(
    val id: String,
    val title: String,
    val thumbnail: String? = null,
    val matchSources: List<MatchSource> = emptyList()
)

@Serializable
data class MatchSource(
    val sourceName: String
)

class StreamedMediaExtractor {
    private val fetchUrl = "https://embedstreams.top/fetch"
    private val cookieUrl = "https://fishy.streamed.su/api/event"
    private val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        "Content-Type" to "application/json"
    )
    private val fallbackDomains = listOf("p2-panel.streamed.su", "streamed.su")
    private val cookieCache = mutableMapOf<String, String>()

    suspend fun getUrl(
        streamUrl: String,
        matchId: String,
        source: String,
        streamNo: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StreamedMediaExtractor", "Starting extraction for: $streamUrl, source: $source, streamNo: $streamNo")

        // Fetch stream page cookies
        val streamResponse = try {
            app.get(streamUrl, headers = baseHeaders, timeout = 15)
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Stream page fetch failed for $streamUrl: ${e.message}")
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
            Log.e("StreamedMediaExtractor", "No cookies obtained for $streamUrl")
            return false
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
            "Referer" to streamUrl,
            "Cookie" to combinedCookies
        )
        Log.d("StreamedMediaExtractor", "Fetching with data: $postData and headers: $fetchHeaders")

        val encryptedResponse = try {
            val response = app.post(fetchUrl, headers = fetchHeaders, json = postData.toJson(), timeout = 15)
            Log.d("StreamedMediaExtractor", "Fetch response code: ${response.code} for source: $source, streamNo: $streamNo")
            response.text.also {
                Log.d("StreamedMediaExtractor", "Encrypted response length: ${it.length}, first 100 chars: ${it.take(100)}")
            }
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Fetch failed for source: $source, streamNo: $streamNo: ${e.message}")
            return false
        }
        Log.d("StreamedMediaExtractor", "Encrypted response: $encryptedResponse")

        // Decrypt using Deno
        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        val decryptResponse = try {
            app.post(decryptUrl, json = decryptPostData.toJson(), headers = mapOf("Content-Type" to "application/json"))
                .parsedSafe<Map<String, String>>()
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Decryption request failed for source: $source, streamNo: $streamNo: ${e.message}")
            return false
        }
        val decryptedPath = decryptResponse?.get("decrypted") ?: return false.also {
            Log.e("StreamedMediaExtractor", "Decryption failed or no 'decrypted' key for source: $source, streamNo: $streamNo")
        }
        Log.d("StreamedMediaExtractor", "Decrypted path: $decryptedPath")

        // Construct M3U8 URL
        val m3u8Url = "https://rr.buytommy.top$decryptedPath"
        val m3u8Headers = baseHeaders + mapOf(
            "Referer" to embedReferer,
            "Cookie" to combinedCookies
        )

        // Test M3U8 with fallbacks
        var success = false
        for (domain in listOf("rr.buytommy.top") + fallbackDomains) {
            try {
                val testUrl = m3u8Url.replace("rr.buytommy.top", domain)
                val testResponse = app.get(testUrl, headers = m3u8Headers, timeout = 15)
                Log.d("StreamedMediaExtractor", "M3U8 test response code: ${testResponse.code} for $testUrl")
                if (testResponse.code == 200) {
                    callback.invoke(
                        newExtractorLink(
                            source = "Streamed",
                            name = "$source Stream $streamNo",
                            url = testUrl,
                            referer = embedReferer,
                            quality = Qualities.Unknown.value,
                            isM3u8 = true,
                            headers = m3u8Headers
                        )
                    )
                    Log.d("StreamedMediaExtractor", "M3U8 URL added: $testUrl")
                    success = true
                    break
                } else {
                    Log.w("StreamedMediaExtractor", "M3U8 test failed for $domain with code: ${testResponse.code}")
                }
            } catch (e: Exception) {
                Log.e("StreamedMediaExtractor", "M3U8 test failed for $domain: ${e.message}")
            }
        }

        return success
    }

    private suspend fun fetchEventCookies(pageUrl: String, referrer: String): String {
        cookieCache[pageUrl]?.let { return it }

        val payload = """{"n":"pageview","u":"$pageUrl","d":"streamed.su","r":"$referrer"}"""
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
                return formattedCookies
            }
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Failed to fetch event cookies: ${e.message}")
        }
        return ""
    }
}