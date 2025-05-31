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

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed"
    override var supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

    companion object {
        // Changed visibility from implicit private to internal for access from StreamedMediaExtractor
        internal const val TAG = "StreamedProvider"
        internal const val MAX_FALLBACK_STREAMS = 4
        internal const val EMBED_STREAMS_TOP_URL = "https://embedstreams.top"
        internal const val FETCH_URL = "$EMBED_STREAMS_TOP_URL/fetch"
        internal const val COOKIE_API_URL = "https://fishy.streamed.su/api/event"
        internal const val DECRYPT_API_URL = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
        internal const val DEFAULT_POSTER_PATH = "/api/images/poster/fallback.webp"
        internal const val PRIMARY_M3U8_DOMAIN = "rr.buytommy.top"

        internal val BASE_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.5 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
            "Content-Type" to "application/json",
            "Accept" to "application/vnd.apple.mpegurl, */*",
            "Origin" to EMBED_STREAMS_TOP_URL
        )

        internal const val NETWORK_TIMEOUT_MILLIS = 20 * 1000L
    }

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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = withContext(Dispatchers.IO) {
        val rawList = app.get(request.data).text
        val listJson = parseJson<List<Match>>(rawList)

        val list = listJson.filter { match -> match.matchSources.isNotEmpty() }.mapNotNull { match ->
            val url = "$mainUrl/watch/${match.id}"
            newLiveSearchResponse(
                name = match.title,
                url = url,
                type = TvType.Live
            ) {
                this.posterUrl = "$mainUrl${match.posterPath ?: DEFAULT_POSTER_PATH}"
            }
        }

        return@withContext newHomePageResponse(
            list = listOf(HomePageList(request.name, list, isHorizontalImages = true)),
            hasNext = false
        )
    }

    override suspend fun load(url: String): LoadResponse = withContext(Dispatchers.IO) {
        val matchId = url.substringAfterLast("/")
        val title = matchId.replace("-", " ")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            .replace(Regex("-\\d+$"), "")
        val posterUrl = "$mainUrl/api/images/poster/$matchId.webp"

        return@withContext newLiveStreamLoadResponse(
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
    ): Boolean = withContext(Dispatchers.IO) {
        val matchId = data.substringAfterLast("/")
        val extractor = StreamedMediaExtractor() // Initialize without mainUrl, as it's not used in its methods
        var linksFound = false

        Log.d(TAG, "Attempting to load links for match ID: $matchId")

        // Crucial: Add Cloudflare bypass cookie for embedstreams.top first
        // This sets the necessary cf_clearance cookie in the global AppUtils.app.baseClient
        extractor.addCloudflareBypassCookie()

        val matchDetails = try {
            app.get("$mainUrl/api/matches/live/$matchId", timeout = NETWORK_TIMEOUT_MILLIS).parsedSafe<Match>()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch match details for $matchId: ${e.message}", e)
            null
        }

        val availableSources = matchDetails?.matchSources?.map { it.sourceName }?.toSet() ?: emptySet()
        Log.d(TAG, "Available sources from API for $matchId: $availableSources")

        val sourcesToProcess = if (availableSources.isNotEmpty()) {
            availableSources.toList()
        } else {
            Log.w(TAG, "No specific sources found from API for $matchId, using general fallback list.")
            listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")
        }

        for (source in sourcesToProcess) {
            val streamInfos = try {
                app.get("$mainUrl/api/stream/$source/$matchId", timeout = NETWORK_TIMEOUT_MILLIS).parsedSafe<List<StreamInfo>>()
                    ?.filter { it.embedUrl.isNotBlank() } ?: emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch stream info for source '$source' ($matchId): ${e.message}")
                emptyList()
            }

            if (streamInfos.isNotEmpty()) {
                streamInfos.forEach { stream ->
                    val streamUrl = "$mainUrl/watch/$matchId/${stream.source}/${stream.streamNo}"
                    Log.d(TAG, "Processing stream URL: $streamUrl (Source: ${stream.source}, StreamNo: ${stream.streamNo}, Lang: ${stream.language}, HD: ${stream.hd})")
                    if (extractor.extract(streamUrl, stream.id, stream.source, stream.streamNo, stream.language, stream.hd, callback)) {
                        linksFound = true
                    }
                }
            } else if (availableSources.isEmpty()) {
                Log.w(TAG, "No stream info from API for '$source' ($matchId), trying fallback stream numbers.")
                for (streamNo in 1..MAX_FALLBACK_STREAMS) {
                    val streamUrl = "$mainUrl/watch/$matchId/$source/$streamNo"
                    Log.d(TAG, "Processing fallback stream URL: $streamUrl (Source: $source, StreamNo: $streamNo)")
                    if (extractor.extract(streamUrl, matchId, source, streamNo, "Unknown", false, callback)) {
                        linksFound = true
                    }
                }
            } else {
                Log.d(TAG, "Source '$source' is reported as available, but no stream info found from API for $matchId (no fallback streamNo attempts).")
            }
        }

        if (!linksFound) {
            Log.e(TAG, "No links found for $matchId after all extraction attempts.")
        }
        return@withContext linksFound
    }

    data class Match(
        @JsonProperty("id") val id: String?,
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

// No longer needs mainUrl in constructor if all URLs come from StreamedProvider.Companion
class StreamedMediaExtractor {

    // Access constants via StreamedProvider.Companion (now internal)
    companion object {
        private val TAG = StreamedProvider.TAG
        private val EMBED_STREAMS_TOP_URL = StreamedProvider.EMBED_STREAMS_TOP_URL
        private val FETCH_URL = StreamedProvider.FETCH_URL
        private val COOKIE_API_URL = StreamedProvider.COOKIE_API_URL
        private val DECRYPT_API_URL = StreamedProvider.DECRYPT_API_URL
        private val PRIMARY_M3U8_DOMAIN = StreamedProvider.PRIMARY_M3U8_DOMAIN
        private val BASE_HEADERS = StreamedProvider.BASE_HEADERS
        private val NETWORK_TIMEOUT_MILLIS = StreamedProvider.NETWORK_TIMEOUT_MILLIS
        private val FALLBACK_M3U8_DOMAINS = listOf("p2-panel.streamed.su", "streamed.su")
    }

    private val cookieCache = mutableMapOf<String, String>()

    suspend fun addCloudflareBypassCookie() = withContext(Dispatchers.IO) {
        Log.d(TAG, "Attempting to acquire Cloudflare bypass cookie for $EMBED_STREAMS_TOP_URL")
        try {
            app.get(EMBED_STREAMS_TOP_URL, timeout = NETWORK_TIMEOUT_MILLIS)
            Log.d(TAG, "Cloudflare bypass attempt for $EMBED_STREAMS_TOP_URL completed. Cookies should be in global client.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire Cloudflare bypass cookie for $EMBED_STREAMS_TOP_URL: ${e.message}", e)
        }
    }

    suspend fun extract(
        streamUrl: String,
        streamId: String,
        source: String,
        streamNo: Int,
        language: String,
        isHd: Boolean,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting extraction for streamUrl: $streamUrl (ID: $streamId, Source: $source, StreamNo: $streamNo)")

        val embedReferer = "$EMBED_STREAMS_TOP_URL/embed/$source/$streamId/$streamNo"

        // 1. Fetch stream page cookies
        val streamResponse = try {
            app.get(streamUrl, headers = BASE_HEADERS, timeout = NETWORK_TIMEOUT_MILLIS)
        } catch (e: Exception) {
            Log.e(TAG, "Stream page fetch failed for $source/$streamNo: ${e.message}", e)
            return@withContext false
        }
        val streamCookiesMap = streamResponse.cookies
        Log.d(TAG, "Stream cookies from stream page for $source/$streamNo: $streamCookiesMap")

        // 2. Fetch event cookies (fishy.streamed.su) - _ddg cookies
        val eventCookiesString = fetchEventCookies(streamUrl, streamUrl)
        Log.d(TAG, "Event cookies fetched for $source/$streamNo: $eventCookiesString")

        val combinedCookiesForHeader = buildString {
            if (streamCookiesMap.isNotEmpty()) {
                append(streamCookiesMap.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
            if (eventCookiesString.isNotEmpty()) {
                if (isNotEmpty()) append("; ")
                append(eventCookiesString)
            }
        }

        val fetchHeaders = BASE_HEADERS.toMutableMap().apply {
            this["Referer"] = embedReferer
            if (combinedCookiesForHeader.isNotEmpty()) {
                this["Cookie"] = combinedCookiesForHeader
            }
        }.toMap()

        // 3. POST to fetch encrypted string
        val postData = mapOf(
            "source" to source,
            "id" to streamId,
            "streamNo" to streamNo.toString()
        )

        Log.d(TAG, "Fetching encrypted string with data: $postData and headers (partial): $fetchHeaders")

        val encryptedResponse = try {
            val response = app.post(FETCH_URL, headers = fetchHeaders, json = postData, timeout = NETWORK_TIMEOUT_MILLIS)
            Log.d(TAG, "Fetch response code for $source/$streamNo: ${response.code}")
            if (response.code != 200) {
                Log.e(TAG, "Fetch request failed for $source/$streamNo with code: ${response.code}, body: ${response.text}")
                return@withContext false
            }
            response.text.takeIf { it.isNotBlank() } ?: run {
                Log.e(TAG, "Empty encrypted response received for $source/$streamNo")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fetch request failed for $source/$streamNo: ${e.message}", e)
            return@withContext false
        }
        Log.d(TAG, "Encrypted response for $source/$streamNo: $encryptedResponse")

        // 4. Decrypt using Deno API
        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        val decryptHeaders = mapOf("Content-Type" to "application/json")

        val decryptedPath = try {
            app.post(DECRYPT_API_URL, json = decryptPostData, headers = decryptHeaders, timeout = NETWORK_TIMEOUT_MILLIS)
                .parsedSafe<Map<String, String>>()
                ?.get("decrypted")
                ?.takeIf { it.isNotBlank() } ?: run {
                    Log.e(TAG, "Decryption failed or 'decrypted' key is missing/empty for $source/$streamNo")
                    return@withContext false
                }
        } catch (e: Exception) {
            Log.e(TAG, "Decryption request failed for $source/$streamNo: ${e.message}", e)
            return@withContext false
        }
        Log.d(TAG, "Decrypted path for $source/$streamNo: $decryptedPath")

        // 5. Construct M3U8 URL and FETCH ITS CONTENT
        val baseM3u8Url = "https://$PRIMARY_M3U8_DOMAIN$decryptedPath"
        val m3u8Headers = BASE_HEADERS.toMutableMap().apply {
            this["Referer"] = embedReferer
            if (combinedCookiesForHeader.isNotEmpty()) {
                this["Cookie"] = combinedCookiesForHeader
            }
        }.toMap()

        var m3u8Content: String? = null
        var finalM3u8UrlUsed: String? = null

        val domainsToTest = listOf(PRIMARY_M3U8_DOMAIN) + FALLBACK_M3U8_DOMAINS
        for (domain in domainsToTest) {
            val currentM3u8Url = baseM3u8Url.replace(PRIMARY_M3U8_DOMAIN, domain)
            Log.d(TAG, "Attempting to fetch M3U8 content from: $currentM3u8Url")
            try {
                val response = app.get(currentM3u8Url, headers = m3u8Headers, timeout = NETWORK_TIMEOUT_MILLIS)
                if (response.code == 200) {
                    m3u8Content = response.text
                    finalM3u8UrlUsed = currentM3u8Url
                    Log.d(TAG, "Successfully fetched M3U8 content from $domain.")
                    break
                } else {
                    Log.w(TAG, "Failed to fetch M3U8 content from $domain with code: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception fetching M3U8 content from $domain: ${e.message}", e)
            }
        }

        if (m3u8Content == null || finalM3u8UrlUsed == null) {
            Log.e(TAG, "Failed to fetch M3U8 content from any domain for $source/$streamNo.")
            return@withContext false
        }

        // 6. Parse M3U8 content to find key URI and fetch the key
        var keyUrl: String? = null
        val keyRegex = """#EXT-X-KEY:METHOD=(AES-128),URI="([^"]+)"""".toRegex()
        val keyMatch = keyRegex.find(m3u8Content)

        if (keyMatch != null) {
            val encryptionMethod = keyMatch.groupValues[1]
            val keyUri = keyMatch.groupValues[2]

            keyUrl = if (keyUri.startsWith("/")) {
                "https://$PRIMARY_M3U8_DOMAIN$keyUri"
            } else {
                keyUri
            }

            Log.d(TAG, "Detected HLS encryption. Key URI: $keyUri, Full Key URL: $keyUrl")

            val keyBytes: ByteArray? = try {
                val keyHeaders = BASE_HEADERS.toMutableMap().apply {
                    this["Referer"] = embedReferer
                }.toMap()

                val keyResponse = app.get(keyUrl, headers = keyHeaders, timeout = NETWORK_TIMEOUT_MILLIS)
                if (keyResponse.code == 200) {
                    keyResponse.body.bytes()
                } else {
                    Log.e(TAG, "Failed to fetch HLS key from $keyUrl with code: ${keyResponse.code}")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception fetching HLS key from $keyUrl: ${e.message}", e)
                null
            }

            if (keyBytes != null) {
                // 7. Add ExtractorLink with key data - FIXED: Use lambda for quality and key
                callback.invoke(
                    newExtractorLink(
                        source = "Streamed",
                        name = "$source Stream $streamNo (${language.replaceFirstChar { it.uppercase(Locale.ROOT) }}${if (isHd) ", HD" else ""})",
                        url = finalM3u8UrlUsed,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = embedReferer
                        this.headers = m3u8Headers.toMutableMap()
                        this.key = keyBytes // FIXED: Set key inside lambda
                        this.quality = if (isHd) Qualities.P1080.value else Qualities.Unknown.value // FIXED: Set quality inside lambda
                    }
                )
                Log.d(TAG, "Successfully added M3U8 URL with HLS key for $source/$streamNo.")
                return@withContext true
            } else {
                Log.e(TAG, "Failed to obtain HLS key data. Cannot provide playable link.")
                return@withContext false
            }
        } else {
            Log.d(TAG, "No HLS encryption key found in M3U8. Adding direct link.")
            callback.invoke(
                newExtractorLink(
                    source = "Streamed",
                    name = "$source Stream $streamNo (${language.replaceFirstChar { it.uppercase(Locale.ROOT) }}${if (isHd) ", HD" else ""})",
                    url = finalM3u8UrlUsed,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = embedReferer
                    this.headers = m3u8Headers.toMutableMap()
                    this.quality = if (isHd) Qualities.P1080.value else Qualities.Unknown.value // FIXED: Set quality inside lambda
                }
            )
            return@withContext true
        }
    }

    private suspend fun fetchEventCookies(pageUrl: String, referrer: String): String = withContext(Dispatchers.IO) {
        cookieCache[pageUrl]?.let {
            Log.d(TAG, "Using cached event cookies for $pageUrl")
            return@withContext it
        }

        val payload = """{"n":"pageview","u":"$pageUrl","d":"streamed.su","r":"$referrer"}"""
        val requestBody = payload.toRequestBody("text/plain".toMediaType())

        try {
            val response = app.post(
                COOKIE_API_URL,
                headers = mapOf("Content-Type" to "text/plain"),
                requestBody = requestBody,
                timeout = NETWORK_TIMEOUT_MILLIS
            )

            if (response.code != 200) {
                Log.e(TAG, "Failed to fetch event cookies from $COOKIE_API_URL with code: ${response.code}, body: ${response.text}")
                return@withContext ""
            }

            val cookies = response.headers.filter { it.first.equals("Set-Cookie", ignoreCase = true) }
                .map { it.second.split(";")[0].trim() }

            val formattedCookies = listOf("_ddg8_", "_ddg10_", "_ddg9_", "_ddg1_")
                .mapNotNull { key -> cookies.find { it.startsWith(key) } }
                .joinToString("; ")

            if (formattedCookies.isNotEmpty()) {
                cookieCache[pageUrl] = formattedCookies
                Log.d(TAG, "Successfully fetched and cached event cookies for $pageUrl")
                return@withContext formattedCookies
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception while fetching event cookies from $COOKIE_API_URL: ${e.message}", e)
        }
        return@withContext ""
    }
}
