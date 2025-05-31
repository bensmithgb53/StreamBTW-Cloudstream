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
import kotlinx.coroutines.delay

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed"
    override var supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

    private val fetchUrl = "https://embedstreams.top/fetch"
    private val cookieUrl = "https://fishy.streamed.su/api/event"
    private val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.5 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Content-Type" to "application/json",
        "Accept" to "application/vnd.apple.mpegurl, */*",
        "Origin" to "https://embedstreams.top"
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
        val rawList = app.get(request.data).text
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

        return newHomePageResponse(
            list = listOf(HomePageList(request.name, list, isHorizontalImages = true)),
            hasNext = false
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val matchId = url.substringAfterLast("/")
        val title = matchId.replace("-", " ")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            .replace(Regex("-\\d+$"), "")
        val posterUrl = "$mainUrl/api/images/poster/$matchId.webp"
        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = url
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
        val matchId = data.substringAfterLast("/")
        val extractor = StreamedMediaExtractor()
        var linksFound = false // Renamed to linksFound to be more descriptive

        // Step 1: Fetch match details to get reported sources
        val matchDetails = try {
            app.get("$mainUrl/api/matches/live/$matchId", timeout = StreamedMediaExtractor.EXTRACTOR_TIMEOUT_MILLIS).parsedSafe<Match>()
        } catch (e: Exception) {
            Log.e("StreamedProvider", "LOAD_LINKS_ERROR: Failed to fetch match details for $matchId. Error: ${e.message}. No sources can be identified.")
            return false // Critical failure: cannot proceed without match details.
        }

        val matchSources = matchDetails?.matchSources ?: emptyList()
        if (matchSources.isEmpty()) {
            Log.w("StreamedProvider", "LOAD_LINKS_INFO: Match details for $matchId returned no reported sources. No links will be added.")
            return false // No sources listed, so no links can be added.
        }

        // Step 2: Iterate through each reported source
        for (matchSource in matchSources) {
            val sourceName = matchSource.sourceName
            val initialApiStreamId = matchSource.id // The ID provided by the /api/matches/live endpoint

            // Try fetching stream info using the initialApiStreamId
            var streamInfos = try {
                val response = app.get("$mainUrl/api/stream/$sourceName/$initialApiStreamId", timeout = StreamedMediaExtractor.EXTRACTOR_TIMEOUT_MILLIS).text
                parseJson<List<StreamInfo>>(response).filter { it.embedUrl.isNotBlank() }
            } catch (e: Exception) {
                Log.w("StreamedProvider", "LOAD_LINKS_WARNING: Failed to get active stream info from API for source '$sourceName' using ID '$initialApiStreamId'. Error: ${e.message}. Attempting fallback ID.")
                emptyList() // Treat as empty list if API call fails
            }

            // Fallback: If initial attempt failed or returned empty, try with the main matchId
            if (streamInfos.isEmpty() && initialApiStreamId != matchId) {
                Log.w("StreamedProvider", "LOAD_LINKS_WARNING: Trying main matchId '$matchId' as fallback for source '$sourceName' as initial ID '$initialApiStreamId' failed.")
                streamInfos = try {
                    val response = app.get("$mainUrl/api/stream/$sourceName/$matchId", timeout = StreamedMediaExtractor.EXTRACTOR_TIMEOUT_MILLIS).text
                    parseJson<List<StreamInfo>>(response).filter { it.embedUrl.isNotBlank() }
                } catch (e: Exception) {
                    Log.w("StreamedProvider", "LOAD_LINKS_WARNING: Fallback with main matchId '$matchId' also failed for source '$sourceName'. Error: ${e.message}. Skipping this source.")
                    emptyList()
                }
            }
            
            // Step 3: Only process and add links if StreamInfo was successfully retrieved
            if (streamInfos.isNotEmpty()) {
                Log.d("StreamedProvider", "LOAD_LINKS_SUCCESS: Found ${streamInfos.size} active streams for source '$sourceName'. Adding links to Cloudstream.")
                streamInfos.forEach { stream ->
                    val streamIdForExtractor = stream.id // The ID to use for the actual embed/decryption
                    val streamNo = stream.streamNo
                    val language = stream.language
                    val isHd = stream.hd
                    val streamUrl = "$mainUrl/watch/$matchId/$sourceName/$streamNo" // URL for the viewer page
                    
                    Log.d("StreamedProvider", "LOAD_LINKS_DEBUG: Calling extractor for StreamInfo ID: $streamIdForExtractor, Source: $sourceName, StreamNo: $streamNo")
                    if (extractor.getUrl(streamUrl, streamIdForExtractor, sourceName, streamNo, language, isHd, subtitleCallback, callback)) {
                        linksFound = true // Mark as true if at least one link is added by the extractor
                    }
                }
            } else {
                Log.d("StreamedProvider", "LOAD_LINKS_INFO: Source '$sourceName' was reported in match details but returned NO active streams from /api/stream with either ID. Not adding links for this source.")
            }
        }

        if (!linksFound) {
            Log.e("StreamedProvider", "LOAD_LINKS_FINAL: No working links found for $matchId after checking all API-reported sources. This could mean no streams are currently active, or all extraction attempts failed.")
        }
        return linksFound
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
        @JsonProperty("id") val id: String // This is the source-specific ID to use in the /api/stream/{source}/{id} URL
    )

    data class StreamInfo(
        @JsonProperty("id") val id: String, // This is the ID specific to the stream within that source
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
        "User-Agent" to "Mozilla/5.5 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Content-Type" to "application/json",
        "Accept" to "application/vnd.apple.mpegurl, */*",
        "Origin" to "https://embedstreams.top"
    )
    private val fallbackDomains = listOf("p2-panel.streamed.su", "streamed.su")
    private val cookieCache = mutableMapOf<String, String>()

    companion object {
        const val EXTRACTOR_TIMEOUT_SECONDS = 30
        const val EXTRACTOR_TIMEOUT_MILLIS = EXTRACTOR_TIMEOUT_SECONDS * 1000L
    }

    suspend fun getUrl(
        streamUrl: String,
        streamId: String, // This is the ID to be used in the POST data for decryption
        source: String,
        streamNo: Int,
        language: String,
        isHd: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StreamedMediaExtractor", "EXTRACTOR_START: Starting extraction for: $streamUrl (Stream ID for extraction: $streamId)")

        val streamResponse = try {
            app.get(streamUrl, headers = baseHeaders, timeout = EXTRACTOR_TIMEOUT_MILLIS)
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "EXTRACTOR_ERROR: Stream page fetch failed for $source/$streamNo: ${e.message}")
            return false
        }
        val streamCookies = streamResponse.cookies
        Log.d("StreamedMediaExtractor", "EXTRACTOR_DEBUG: Stream cookies for $source/$streamNo: $streamCookies")

        val eventCookies = fetchEventCookies(streamUrl, streamUrl)
        Log.d("StreamedMediaExtractor", "EXTRACTOR_DEBUG: Event cookies for $source/$streamNo: $eventCookies")

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
            Log.e("StreamedMediaExtractor", "EXTRACTOR_ERROR: No cookies obtained for $source/$streamNo")
            return false
        }
        Log.d("StreamedMediaExtractor", "EXTRACTOR_DEBUG: Combined cookies for $source/$streamNo: $combinedCookies")

        val postData = mapOf(
            "source" to source,
            "id" to streamId, // Crucial: Use the specific streamId for this source in the POST request
            "streamNo" to streamNo.toString()
        )
        val embedReferer = "https://embedstreams.top/embed/$source/$streamId/$streamNo" // Referer also uses this ID
        val fetchHeaders = baseHeaders + mapOf(
            "Referer" to streamUrl,
            "Cookie" to combinedCookies
        )
        Log.d("StreamedMediaExtractor", "EXTRACTOR_DEBUG: Fetching encrypted string with data: $postData and headers: $fetchHeaders")

        val encryptedResponse = try {
            val response = app.post(fetchUrl, headers = fetchHeaders, json = postData, timeout = EXTRACTOR_TIMEOUT_MILLIS)
            Log.d("StreamedMediaExtractor", "EXTRACTOR_DEBUG: Fetch response code for $source/$streamNo: ${response.code}")
            if (response.code != 200) {
                Log.e("StreamedMediaExtractor", "EXTRACTOR_ERROR: Fetch failed for $source/$streamNo with code: ${response.code}")
                return false
            }
            response.text.takeIf { it.isNotBlank() } ?: return false.also {
                Log.e("StreamedMediaExtractor", "EXTRACTOR_ERROR: Empty encrypted response for $source/$streamNo")
            }
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "EXTRACTOR_ERROR: Fetch failed for $source/$streamNo: ${e.message}")
            return false
        }
        Log.d("StreamedMediaExtractor", "EXTRACTOR_DEBUG: Encrypted response for $source/$streamNo: $encryptedResponse")

        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        val decryptResponse = try {
            app.post(decryptUrl, json = decryptPostData, headers = mapOf("Content-Type" to "application/json"), timeout = EXTRACTOR_TIMEOUT_MILLIS)
                .parsedSafe<Map<String, String>>()
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "EXTRACTOR_ERROR: Decryption request failed for $source/$streamNo: ${e.message}")
            return false
        }
        val decryptedPath = decryptResponse?.get("decrypted")?.takeIf { it.isNotBlank() } ?: return false.also {
            Log.e("StreamedMediaExtractor", "EXTRACTOR_ERROR: Decryption failed or no 'decrypted' key for $source/$streamNo")
        }
        Log.d("StreamedMediaExtractor", "EXTRACTOR_SUCCESS: Decrypted path for $source/$streamNo: $decryptedPath")

        val m3u8Url = "https://rr.buytommy.top$decryptedPath"
        val m3u8Headers = baseHeaders + mapOf(
            "Referer" to embedReferer,
            "Cookie" to combinedCookies
        )

        // Always add the link if decryption was successful, regardless of our internal M3U8 test.
        // Cloudstream's player might still play it.
        callback.invoke(
            newExtractorLink(
                source = "Streamed",
                name = "$source Stream $streamNo ($language${if (isHd) ", HD" else ""})",
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = embedReferer
                this.quality = if (isHd) Qualities.P1080.value else Qualities.Unknown.value
                this.headers = m3u8Headers
            }
        )
        Log.d("StreamedMediaExtractor", "EXTRACTOR_LINK_ADDED: Added decrypted URL: $m3u8Url for $source/$streamNo")
        
        // This loop is now purely for logging if alternative domains also work, not for adding new links.
        for (domain in fallbackDomains) {
            if (m3u8Url.contains(domain)) continue // Skip if the URL already uses this domain
            try {
                val testUrl = m3u8Url.replace("rr.buytommy.top", domain)
                val testResponse = app.get(testUrl, headers = m3u8Headers, timeout = EXTRACTOR_TIMEOUT_MILLIS)
                if (testResponse.code == 200) {
                     Log.d("StreamedMediaExtractor", "EXTRACTOR_DEBUG: Fallback domain $domain also works for $source/$streamNo: $testUrl")
                } else {
                    Log.w("StreamedMediaExtractor", "EXTRACTOR_DEBUG: M3U8 test failed for fallback domain $domain with code: ${testResponse.code}")
                }
            } catch (e: Exception) {
                Log.e("StreamedMediaExtractor", "EXTRACTOR_DEBUG: M3U8 test failed for fallback domain $domain: ${e.message}")
            }
        }
        
        return true // Return true because we successfully decrypted and added a link
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
                timeout = EXTRACTOR_TIMEOUT_MILLIS
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
            Log.e("StreamedMediaExtractor", "EXTRACTOR_ERROR: Failed to fetch event cookies: ${e.message}")
        }
        return ""
    }
}
