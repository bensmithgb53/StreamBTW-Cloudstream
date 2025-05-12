package ben.smith53

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradira3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import android.util.Log
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed"
    override var supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

    private val maxStreams = 4

    override val mainPage = mainPageOf(
        "$mainUrl/api/matches/live" to "Live Matches",
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

        // Log available sources for each match
        listJson.forEach { match ->
            Log.d("StreamedProvider", "Match: ${match.title}, Sources: ${match.matchSources.map { it.sourceName }}")
        }

        val list = listJson.filter { match -> match.matchSources.isNotEmpty() }.map { match ->
            val url = "$mainUrl/watch/${match.id}?sources=${match.matchSources.joinToString(",") { it.sourceName }}"
            newLiveSearchResponse(
                name = match.title,
                url = url,
                type = TvType.Live
            ) {
                this.posterUrl = "$mainUrl${match.posterPath ?: "/api/images/poster/fallback.webp"}"
            }
        }.filterNotNull()

        return newHomePageResponse(
            name = request.name,
            list = list,
            isHorizontalImages = true
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val matchId = url.substringAfterLast("/").substringBefore("?")
        val title = matchId.replace("-", " ")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            .replace(Regex("-\\d+$"), "")
        val posterUrl = "$mainUrl/api/images/poster/$matchId.webp"
        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = url,
            contentRating = null // Add contentRating as required by new API
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
        val matchId = data.substringAfterLast("/").substringBefore("?")
        val sourcesParam = data.substringAfter("sources=", "").split(",").filter { it.isNotBlank() }
        val extractor = StreamedMediaExtractor()
        var success = false

        // If sources are provided in the URL, use them; otherwise, fetch from API
        val sources = if (sourcesParam.isNotEmpty()) {
            sourcesParam
        } else {
            val matchDetailsUrl = "$mainUrl/api/matches/live"
            try {
                val response = app.get(matchDetailsUrl).text
                val matchDetails = parseJson<List<Match>>(response).find { it.id == matchId }
                matchDetails?.matchSources?.map { it.sourceName }?.distinct() ?: emptyList()
            } catch (e: Exception) {
                Log.e("StreamedProvider", "Failed to fetch match details for $matchId: ${e.message}")
                return false
            }
        }

        Log.d("StreamedProvider", "Available sources for $matchId: $sources")

        sources.forEach { source ->
            // Try different match ID formats
            val possibleMatchIds = listOf(
                matchId,
                "1747069200000-$matchId", // Bravo format
                "$matchId-football-1238157" // Delta format
            )

            possibleMatchIds.forEach { currentMatchId ->
                Log.d("StreamedProvider", "Trying source: $source with matchId: $currentMatchId")
                // Check if source is available
                val apiUrl = "$mainUrl/api/stream/$source/$currentMatchId"
                try {
                    val response = app.get(apiUrl, timeout = 15)
                    Log.d("StreamedProvider", "API response for $source/$currentMatchId: ${response.text}")
                    if (response.code == 200) {
                        val streams = parseJson<List<Stream>>(response.text)
                        streams.forEach { stream ->
                            val streamUrl = "$mainUrl/watch/$currentMatchId/$source/${stream.streamNo}"
                            Log.d("StreamedProvider", "Processing $source stream ${stream.streamNo}: $streamUrl")
                            if (extractor.getUrl(streamUrl, currentMatchId, source, stream.streamNo, subtitleCallback, callback)) {
                                success = true
                                Log.d("StreamedProvider", "Success for $source stream ${stream.streamNo}")
                            } else {
                                Log.w("StreamedProvider", "Failed for $source stream ${stream.streamNo}")
                            }
                        }
                    } else {
                        Log.w("StreamedProvider", "Source $source not available for $currentMatchId, API returned ${response.code}")
                    }
                } catch (e: Exception) {
                    Log.e("StreamedProvider", "Failed to check source $source for $currentMatchId: ${e.message}")
                }
                // Add delay to avoid rate limits
                delay(500)
            }
        }
        Log.d("StreamedProvider", "LoadLinks completed with success=$success")
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

    data class Stream(
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
        Log.d("StreamedMediaExtractor", "Starting extraction for source: $source, stream: $streamNo, matchId: $matchId, URL: $streamUrl")

        // Fetch stream page cookies
        val streamResponse = try {
            app.get(streamUrl, headers = baseHeaders, timeout = 15)
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Stream page fetch failed for $source/$matchId: ${e.message}")
            return false
        }
        val streamCookies = streamResponse.cookies
        Log.d("StreamedMediaExtractor", "Stream cookies for $source/$matchId: $streamCookies")

        // Fetch event cookies
        val eventCookies = fetchEventCookies(streamUrl, streamUrl)
        Log.d("StreamedMediaExtractor", "Event cookies for $source/$matchId: $eventCookies")

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
            Log.e("StreamedMediaExtractor", "No cookies obtained for $source/$matchId")
            return false
        }
        Log.d("StreamedMediaExtractor", "Combined cookies for $source/$matchId: $combinedCookies")

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
        Log.d("StreamedMediaExtractor", "Fetching for $source/$matchId with data: $postData")

        val encryptedResponse = try {
            val response = app.post(fetchUrl, headers = fetchHeaders, json = postData, timeout = 15)
            Log.d("StreamedMediaExtractor", "Fetch response code for $source/$matchId: ${response.code}")
            response.text
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Fetch failed for $source/$matchId: ${e.message}")
            return false
        }
        Log.d("StreamedMediaExtractor", "Encrypted response for $source/$matchId: $encryptedResponse")

        // Decrypt using Deno
        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        val decryptResponse = try {
            app.post(
                decryptUrl,
                json = decryptPostData,
                headers = mapOf("Content-Type" to "application/json")
            ).parsedSafe<Map<String, String>>()
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Decryption request failed for $source/$matchId: ${e.message}")
            return false
        }
        val decryptedPath = decryptResponse?.get("decrypted") ?: run {
            Log.e("StreamedMediaExtractor", "Decryption failed for $source/$matchId: no 'decrypted' key")
            callback.invoke(
                newExtractorLink(
                    source = "Streamed",
                    name = "$source Stream $streamNo (Decryption Failed)",
                    url = "about:blank",
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = embedReferer
                    this.quality = Qualities.Unknown.value
                    this.headers = fetchHeaders
                }
            )
            return false
        }
        Log.d("StreamedMediaExtractor", "Decrypted path for $source/$matchId: $decryptedPath")

        // Construct M3U8 URL
        val m3u8Url = "https://rr.buytommy.top$decryptedPath"
        val m3u8Headers = baseHeaders + mapOf(
            "Referer" to embedReferer,
            "Cookie" to combinedCookies
        )

        // Test M3U8 with fallbacks
        for (domain in listOf("rr.buytommy.top") + fallbackDomains) {
            try {
                val testUrl = m3u8Url.replace("rr.buytommy.top", domain)
                val testResponse = app.get(testUrl, headers = m3u8Headers, timeout = 15)
                Log.d("StreamedMediaExtractor", "M3U8 test for $source/$matchId, domain: $domain, code: ${testResponse.code}")
                if (testResponse.code == 200) {
                    callback.invoke(
                        newExtractorLink(
                            source = "Streamed",
                            name = "$source Stream $streamNo (${if (decryptedPath.contains("hd", ignoreCase = true)) "HD" else "SD"})",
                            url = testUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = embedReferer
                            this.quality = if (decryptedPath.contains("hd", ignoreCase = true)) Qualities.P1080.value else Qualities.Unknown.value
                            this.headers = m3u8Headers
                        }
                    )
                    Log.d("StreamedMediaExtractor", "M3U8 URL added for $source/$matchId: $testUrl")
                    return true
                }
            } catch (e: Exception) {
                Log.e("StreamedMediaExtractor", "M3U8 test failed for $source/$matchId, domain: $domain: ${e.message}")
            }
        }

        // Add link anyway if tests fail
        callback.invoke(
            newExtractorLink(
                source = "Streamed",
                name = "$source Stream $streamNo (Fallback)",
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = embedReferer
                this.quality = Qualities.Unknown.value
                this.headers = m3u8Headers
            }
        )
        Log.d("StreamedMediaExtractor", "M3U8 test failed but added anyway for $source/$matchId: $m3u8Url")
        return true
    }

    private suspend fun fetchEventCookies(pageUrl: String, referrer: String): String {
        cookieCache.remove(pageUrl) // Clear cache for fresh cookies
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
                Log.d("StreamedMediaExtractor", "Fetched cookies for $pageUrl: $formattedCookies")
                return formattedCookies
            }
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Failed to fetch event cookies for $pageUrl: ${e.message}")
        }
        return ""
    }
}