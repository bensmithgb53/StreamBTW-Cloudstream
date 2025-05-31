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

    private val maxStreams = 4 // This will be used as a fallback if API doesn't provide explicit streamNos
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
        var success = false

        val matchDetails = try {
            app.get("$mainUrl/api/matches/live/$matchId", timeout = StreamedMediaExtractor.EXTRACTOR_TIMEOUT_MILLIS).parsedSafe<Match>()
        } catch (e: Exception) {
            Log.e("StreamedProvider", "Failed to fetch match details for $matchId: ${e.message}")
            null
        }

        val matchSources = matchDetails?.matchSources ?: emptyList()

        // If specific sources are reported by the API, process only those.
        // If not, we'll try a common set of source names as a fallback.
        val sourcesToTry = if (matchSources.isNotEmpty()) {
            matchSources.map { it.sourceName to (it.id ?: matchId) } // Pair (sourceName, specificId)
        } else {
            Log.w("StreamedProvider", "No specific sources reported by API for $matchId. Trying general sources.")
            listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot").map { it to matchId } // Pair (sourceName, matchId as fallback)
        }

        for ((sourceName, initialApiId) in sourcesToTry) {
            // First attempt: use the specific ID for this source to get detailed stream info
            val streamInfos = try {
                val response = app.get("$mainUrl/api/stream/$sourceName/$initialApiId", timeout = StreamedMediaExtractor.EXTRACTOR_TIMEOUT_MILLIS).text
                parseJson<List<StreamInfo>>(response).filter { it.embedUrl.isNotBlank() }
            } catch (e: Exception) {
                Log.w("StreamedProvider", "API call for specific stream info failed for source '$sourceName' (ID: $initialApiId): ${e.message}")
                emptyList()
            }

            if (streamInfos.isNotEmpty()) {
                // If API returned stream infos, process them
                streamInfos.forEach { stream ->
                    val streamIdForExtractor = stream.id // Use the ID from StreamInfo for the extractor
                    val streamNo = stream.streamNo
                    val language = stream.language
                    val isHd = stream.hd
                    val linkName = "$sourceName Stream $streamNo (${language}${if (isHd) ", HD" else ""})"
                    val streamUrl = "$mainUrl/watch/$matchId/$sourceName/$streamNo" // URL for the viewer page
                    
                    Log.d("StreamedProvider", "Processing API-provided stream: $linkName at $streamUrl (StreamInfo ID: $streamIdForExtractor)")
                    if (extractor.getUrl(streamUrl, streamIdForExtractor, sourceName, streamNo, language, isHd, subtitleCallback, callback, linkName)) {
                        success = true
                    }
                }
            } else {
                // If API did not return specific stream infos for this source, try fallback stream numbers
                Log.w("StreamedProvider", "No explicit stream info found for source '$sourceName' (ID: $initialApiId). Attempting common stream numbers.")
                for (streamNo in 1..maxStreams) {
                    // For fallback, we'll try with the initialApiId (which could be matchId or source-specific ID)
                    val streamIdForExtractor = initialApiId
                    val language = "Unknown" // Can't determine from API in fallback
                    val isHd = false // Assume not HD in fallback
                    val linkName = "$sourceName Stream $streamNo (Guessed)"
                    val streamUrl = "$mainUrl/watch/$matchId/$sourceName/$streamNo" // URL for the viewer page

                    Log.d("StreamedProvider", "Attempting fallback stream: $linkName at $streamUrl (Extractor ID: $streamIdForExtractor)")
                    if (extractor.getUrl(streamUrl, streamIdForExtractor, sourceName, streamNo, language, isHd, subtitleCallback, callback, linkName)) {
                        success = true
                        // If a fallback link is found, we can potentially break and move to the next source.
                        // However, keep iterating maxStreams for this source to ensure all are added.
                    }
                }
            }
        }

        if (!success) {
            Log.e("StreamedProvider", "No working links found for $matchId after all attempts.")
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
        @JsonProperty("id") val id: String // This is the source-specific ID to use in the /api/stream/{source}/{id} URL
    )

    data class StreamInfo(
        @JsonProperty("id") val id: String, // This is the ID specific to the stream within that source (for embedstreams.top post)
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
        streamId: String, // This is now consistently the 'id' required for the embedstreams.top POST payload
        source: String,
        streamNo: Int,
        language: String,
        isHd: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        linkNameOverride: String? = null // Added a parameter for a custom link name
    ): Boolean {
        Log.d("StreamedMediaExtractor", "Starting extraction for: $streamUrl (Stream ID for extractor POST: $streamId)")

        val streamResponse = try {
            app.get(streamUrl, headers = baseHeaders, timeout = EXTRACTOR_TIMEOUT_MILLIS)
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Stream page fetch failed for $source/$streamNo: ${e.message}")
            return false
        }
        val streamCookies = streamResponse.cookies
        Log.d("StreamedMediaExtractor", "Stream cookies for $source/$streamNo: $streamCookies")

        val eventCookies = fetchEventCookies(streamUrl, streamUrl)
        Log.d("StreamedMediaExtractor", "Event cookies for $source/$streamNo: $eventCookies")

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
            Log.e("StreamedMediaExtractor", "No cookies obtained for $source/$streamNo")
            return false
        }
        Log.d("StreamedMediaExtractor", "Combined cookies for $source/$streamNo: $combinedCookies")

        val postData = mapOf(
            "source" to source,
            "id" to streamId, // Use the provided streamId for the POST request
            "streamNo" to streamNo.toString()
        )
        val embedReferer = "https://embedstreams.top/embed/$source/$streamId/$streamNo"
        val fetchHeaders = baseHeaders + mapOf(
            "Referer" to streamUrl,
            "Cookie" to combinedCookies
        )
        Log.d("StreamedMediaExtractor", "Fetching with data: $postData and headers: $fetchHeaders")

        val encryptedResponse = try {
            val response = app.post(fetchUrl, headers = fetchHeaders, json = postData, timeout = EXTRACTOR_TIMEOUT_MILLIS)
            Log.d("StreamedMediaExtractor", "Fetch response code for $source/$streamNo: ${response.code}")
            if (response.code != 200) {
                Log.e("StreamedMediaExtractor", "Fetch failed for $source/$streamNo with code: ${response.code}")
                return false
            }
            response.text.takeIf { it.isNotBlank() } ?: return false.also {
                Log.e("StreamedMediaExtractor", "Empty encrypted response for $source/$streamNo")
            }
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Fetch failed for $source/$streamNo: ${e.message}")
            return false
        }
        Log.d("StreamedMediaExtractor", "Encrypted response for $source/$streamNo: $encryptedResponse")

        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        val decryptResponse = try {
            app.post(decryptUrl, json = decryptPostData, headers = mapOf("Content-Type" to "application/json"), timeout = EXTRACTOR_TIMEOUT_MILLIS)
                .parsedSafe<Map<String, String>>()
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Decryption request failed for $source/$streamNo: ${e.message}")
            return false
        }
        val decryptedPath = decryptResponse?.get("decrypted")?.takeIf { it.isNotBlank() } ?: return false.also {
            Log.e("StreamedMediaExtractor", "Decryption failed or no 'decrypted' key for $source/$streamNo")
        }
        Log.d("StreamedMediaExtractor", "Decrypted path for $source/$streamNo: $decryptedPath")

        val m3u8Url = "https://rr.buytommy.top$decryptedPath"
        val m3u8Headers = baseHeaders + mapOf(
            "Referer" to embedReferer,
            "Cookie" to combinedCookies
        )

        var linkFound = false
        // Removed the check for 200 code here before adding to callback,
        // as the actual playback will handle buffering/errors.
        // Adding the link to callback will make it appear in the UI.
        // The previous attempt to validate the m3u8Url here might be too strict
        // and cause some valid links to be filtered out prematurely.

        val finalLinkName = linkNameOverride ?: "$source Stream $streamNo ($language${if (isHd) ", HD" else ""})"

        // Add the primary link first
        callback.invoke(
            newExtractorLink(
                source = "Streamed",
                name = finalLinkName,
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = embedReferer
                this.quality = if (isHd) Qualities.P1080.value else Qualities.Unknown.value
                this.headers = m3u8Headers
            }
        )
        Log.d("StreamedMediaExtractor", "Added primary M3U8 URL for $source/$streamNo: $m3u8Url")
        linkFound = true

        // Optionally, add fallback domains as separate links if desired, or rely on player to try them.
        // For now, let's keep it to the primary decrypted URL to avoid link clutter.
        // If issues persist with playback, we can revisit adding multiple domains as separate links for player to try.
        // The player usually handles retrying with other domains if the primary fails.

        return linkFound
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
            Log.e("StreamedMediaExtractor", "Failed to fetch event cookies: ${e.message}")
        }
        return ""
    }
}