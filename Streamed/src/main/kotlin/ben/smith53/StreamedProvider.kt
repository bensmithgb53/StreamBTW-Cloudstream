package ben.smith53

import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.Gson
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
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import java.security.MessageDigest // For SHA-256
import java.util.TimeZone // For timezone info

// For the dummy WebGL info, we'll need to mock some types or use simple data classes
// No need to create actual canvas/GL context
data class WebGLInfo(
    val vendor: String,
    val renderer: String,
    val params: Map<String, Any>, // Params can be varied, using Any
    val exts: List<String>
)

// Data class to represent the client fingerprint data for JSON stringification
data class ClientFingerprintData(
    val timezone: String,
    val timezoneOffset: Int,
    val userAgent: String,
    val screenSize: String,
    val pixelDepth: Int,
    val touch: Boolean,
    val deviceMemory: String, // Can be "none" or actual value
    val platform: String,
    val touchPoints: Int,
    val hardwareConcurrency: Int,
    val intlDisplayNames: String,
    val mimeTypes: List<String>,
    val plugins: List<String>,
    val webgl: WebGLInfo
)

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed"
    override var supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

    companion object {
        internal const val TAG = "StreamedProvider"
        internal const val MAX_FALLBACK_STREAMS = 4
        internal const val EMBED_STREAMS_TOP_URL = "https://embedstreams.top"
        internal const val FETCH_URL = "$EMBED_STREAMS_TOP_URL/fetch"
        internal const val COOKIE_API_URL = "https://fishy.streamed.su/api/event"
        internal const val DECRYPT_API_URL = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
        internal const val PRIMARY_M3U8_DOMAIN = "rr.buytommy.top"
        internal val FALLBACK_M3U8_DOMAINS = listOf("p2-panel.streamed.su", "streamed.su")
        internal const val DEFAULT_POSTER_PATH = "/api/images/poster/fallback.webp"

        internal val BASE_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
            "Content-Type" to "application/json",
            "Accept" to "application/vnd.apple.mpegurl, */*",
            "Origin" to EMBED_STREAMS_TOP_URL,
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Fetch-Mode" to "cors"
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
        val extractor = StreamedMediaExtractor()
        var success = false

        Log.d(TAG, "Attempting to load links for match ID: $matchId")

        val matchDetails = try {
            app.get("$mainUrl/api/matches/live/$matchId", timeout = NETWORK_TIMEOUT_MILLIS).parsedSafe<Match>()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch match details for $matchId: ${e.message}")
            null
        }

        val availableSources = matchDetails?.matchSources?.map { it.sourceName }?.toSet() ?: emptySet()
        Log.d(TAG, "Available sources for $matchId: $availableSources")

        val sourcesToProcess = if (availableSources.isNotEmpty()) {
            availableSources.toList()
        } else {
            Log.w(TAG, "No specific sources found from API for $matchId, using general list.")
            listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")
        }

        for (source in sourcesToProcess) {
            val streamInfos = try {
                val response = app.get("$mainUrl/api/stream/$source/$matchId", timeout = NETWORK_TIMEOUT_MILLIS).text
                parseJson<List<StreamInfo>>(response).filter { it.embedUrl.isNotBlank() }
            } catch (e: Exception) {
                Log.w(TAG, "No stream info from API for $source ($matchId): ${e.message}")
                emptyList()
            }

            if (streamInfos.isNotEmpty()) {
                streamInfos.forEach { stream ->
                    val streamId = stream.id
                    val streamNo = stream.streamNo
                    val language = stream.language
                    val isHd = stream.hd
                    val streamUrl = "$mainUrl/watch/$matchId/$source/$streamNo"
                    Log.d(TAG, "Processing stream URL: $streamUrl (ID: $streamId, Language: $language, HD: $isHd)")
                    if (extractor.getUrl(streamUrl, streamId, source, streamNo, language, isHd, subtitleCallback, callback)) {
                        success = true
                    }
                }
            } else if (availableSources.isEmpty()) {
                Log.w(TAG, "No stream info from API for $source ($matchId), trying fallback streamNo.")
                for (streamNo in 1..MAX_FALLBACK_STREAMS) {
                    val streamUrl = "$mainUrl/watch/$matchId/$source/$streamNo"
                    Log.d(TAG, "Processing fallback stream URL: $streamUrl (ID: $matchId)")
                    if (extractor.getUrl(streamUrl, matchId, source, streamNo, "Unknown", false, subtitleCallback, callback)) {
                        success = true
                    }
                }
            } else {
                Log.d(TAG, "Source '$source' is reported as available but no stream info found from API for $matchId.")
            }
        }

        if (!success) {
            Log.e(TAG, "No links found for $matchId after all attempts.")
        }
        return@withContext success
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

    data class StreamInfo(
        @JsonProperty("id") val id: String,
        @JsonProperty("streamNo") val streamNo: Int,
        @JsonProperty("language") val language: String,
        @JsonProperty("hd") val hd: Boolean,
        @JsonProperty("embedUrl") val embedUrl: String,
        @JsonProperty("source") val source: String
    )
}

class StreamedMediaExtractor {
    companion object {
        private val TAG = StreamedProvider.TAG
        private val EMBED_STREAMS_TOP_URL = StreamedProvider.EMBED_STREAMS_TOP_URL
        private val FETCH_URL = StreamedProvider.FETCH_URL
        private val COOKIE_API_URL = StreamedProvider.COOKIE_API_URL
        private val DECRYPT_API_URL = StreamedProvider.DECRYPT_API_URL
        private val PRIMARY_M3U8_DOMAIN = StreamedProvider.PRIMARY_M3U8_DOMAIN
        private val FALLBACK_M3U8_DOMAINS = StreamedProvider.FALLBACK_M3U8_DOMAINS
        internal val BASE_HEADERS = StreamedProvider.BASE_HEADERS // Make internal for X-TOK generation
        private val NETWORK_TIMEOUT_MILLIS = StreamedProvider.NETWORK_TIMEOUT_MILLIS

        const val EXTRACTOR_TIMEOUT_SECONDS = 30

        // Instantiate CloudflareKiller in companion object to make it a singleton for this class
        private val cloudflareKiller = CloudflareKiller()

        // Flag to ensure the interceptor is added only once globally
        @Volatile
        private var isInterceptorAdded = false

        // Gson instance for JSON serialization
        private val gson = Gson()
    }

    private val cookieCache = mutableMapOf<String, String>()

    init {
        if (!isInterceptorAdded) {
            synchronized(this) {
                if (!isInterceptorAdded) {
                    Log.d(TAG, "Adding CloudflareKiller interceptor for $EMBED_STREAMS_TOP_URL")
                    app.baseClient = app.baseClient.newBuilder()
                        .addInterceptor(cloudflareKiller.interceptor)
                        .build()
                    isInterceptorAdded = true
                    Log.d(TAG, "CloudflareKiller interceptor added successfully.")
                }
            }
        }
    }

    // --- NEW: Replicating generateClient() for X-TOK ---
    private fun getHash(message: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hashBytes = md.digest(message.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    // Helper for safe execution with fallback
    private inline fun <T> safe(crossinline fn: () -> T, fallback: T): T {
        return try {
            fn() ?: fallback // Use ?: for null safety
        } catch (e: Exception) {
            Log.w(TAG, "Safe function failed: ${e.message}")
            fallback
        }
    }

    // Mocking WebGL Info - we can't get actual WebGL info on Android like a browser
    private fun getWebGLInfo(): WebGLInfo {
        return WebGLInfo(
            vendor = safe({ (BASE_HEADERS["User-Agent"] ?: "none").contains("Chrome") then "Google Inc." else "none" }, "none"), // Simple guess
            renderer = safe({ (BASE_HEADERS["User-Agent"] ?: "none").contains("Mobile") then "ANGLE (Google Inc., Google Play Services for AR, OpenGL ES 3.2)" else "none" }, "none"), // Common mobile renderer
            params = mapOf(
                "ALIASED_LINE_WIDTH_RANGE" to listOf(1.0, 1.0),
                "ALIASED_POINT_SIZE_RANGE" to listOf(1.0, 100.0),
                "ALPHA_BITS" to 8,
                "BLUE_BITS" to 8,
                "DEPTH_BITS" to 24,
                "MAX_COMBINED_TEXTURE_IMAGE_UNITS" to 32,
                "MAX_CUBE_MAP_TEXTURE_SIZE" to 16384,
                "MAX_FRAGMENT_UNIFORM_VECTORS" to 1024,
                "MAX_RENDERBUFFER_SIZE" to 16384,
                "MAX_TEXTURE_IMAGE_UNITS" to 16,
                "MAX_TEXTURE_SIZE" to 16384,
                "MAX_VARYING_VECTORS" to 15,
                "MAX_VERTEX_ATTRIBS" to 16,
                "MAX_VERTEX_TEXTURE_IMAGE_UNITS" to 16,
                "MAX_VERTEX_UNIFORM_VECTORS" to 1024,
                "RED_BITS" to 8,
                "RENDERER" to "Google Inc.", // Default Android renderer
                "SHADING_LANGUAGE_VERSION" to "WebGL GLSL ES 3.00 (OpenGL ES GLSL ES 3.20)",
                "STENCIL_BITS" to 0,
                "VENDOR" to "Google Inc.",
                "VERSION" to "WebGL 2.0 (OpenGL ES 3.20)",
            ),
            exts = listOf(
                "ANGLE_instanced_arrays", "EXT_blend_minmax", "EXT_color_buffer_half_float",
                "EXT_disjoint_timer_query", "EXT_float_blend", "EXT_frag_depth",
                "EXT_shader_texture_lod", "EXT_sRGB", "EXT_texture_filter_anisotropic",
                "WEBGL_compressed_texture_astc", "WEBGL_compressed_texture_etc", "WEBGL_compressed_texture_s3tc",
                "WEBGL_debug_renderer_info", "WEBGL_debug_shaders", "WEBGL_lose_context",
                "WEBGL_multi_draw", "OES_element_index_uint", "OES_fbo_render_mipmap",
                "OES_standard_derivatives", "OES_texture_float", "OES_texture_float_linear",
                "OES_texture_half_float", "OES_texture_half_float_linear", "OES_vertex_array_object"
            )
        )
    }

    // Mocking MimeTypes and Plugins - these are browser-specific and often dynamic
    // We'll provide some common Android/mobile browser-like values
    private fun getMimeTypes(): List<String> {
        return listOf(
            "application/pdf", "application/x-google-chrome-pdf",
            "application/x-shockwave-flash", "application/x-java-applet",
            "application/x-webkit-webarchive", "image/webp"
        )
    }

    private fun getPlugins(): List<String> {
        return listOf(
            "Chrome PDF Viewer", "Chromium PDF Viewer",
            "Widevine Content Decryption Module", "Adobe Flash Player"
        )
    }

    private suspend fun generateXTok(): String = withContext(Dispatchers.Default) {
        val currentLocale = Locale.getDefault()
        val defaultTimezone = TimeZone.getDefault()
        val timezoneOffsetMinutes = defaultTimezone.rawOffset / (1000 * 60) + defaultTimezone.dstSavings / (1000 * 60)

        val userAgentString = BASE_HEADERS["User-Agent"] ?: "none" // Use our defined User-Agent

        // These values are mocked to represent a typical Android device.
        val data = ClientFingerprintData(
            timezone = safe({ defaultTimezone.id }, "none"),
            timezoneOffset = safe({ timezoneOffsetMinutes }, 0),
            userAgent = userAgentString,
            screenSize = safe({ "${AppUtils.get ; ; }px${AppUtils.get; ;}" }, "1920x1080"), // Placeholder for screen width/height, replace with actual if available
            pixelDepth = safe({ 24 }, 24), // Common pixel depth
            touch = safe({ true }, false), // Most Android devices have touch
            deviceMemory = safe({ "8" }, "none"), // Common device memory value
            platform = safe({ "Android" }, "none"), // Android platform
            touchPoints = safe({ 5 }, 0), // Common touch points
            hardwareConcurrency = safe({ 4 }, 2), // Common CPU cores
            intlDisplayNames = safe({ "supported" }, "none"), // Assume modern Android supports this
            mimeTypes = getMimeTypes(),
            plugins = getPlugins(),
            webgl = getWebGLInfo()
        )

        val jsonStr = gson.toJson(data)
        Log.d(TAG, "X-TOK fingerprint JSON: $jsonStr")
        return@withContext getHash(jsonStr)
    }
    // --- END NEW: Replicating generateClient() ---

    suspend fun getUrl(
        streamUrl: String,
        streamId: String,
        source: String,
        streamNo: Int,
        language: String,
        isHd: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting extraction for: $streamUrl (ID: $streamId)")

        val embedReferer = "$EMBED_STREAMS_TOP_URL/embed/$source/$streamId/$streamNo"

        // 1. Fetch stream page cookies (from streamed.su)
        val streamResponse = try {
            app.get(streamUrl, headers = BASE_HEADERS, timeout = NETWORK_TIMEOUT_MILLIS)
        } catch (e: Exception) {
            Log.e(TAG, "Stream page fetch failed for $source/$streamNo: ${e.message}")
            return@withContext false
        }
        val streamCookiesMap = streamResponse.cookies
        Log.d(TAG, "Stream cookies from stream page for $source/$streamNo: $streamCookiesMap")

        // 2. Fetch event cookies (from fishy.streamed.su)
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
        Log.d(TAG, "Combined explicit cookies for $source/$streamNo: $combinedCookiesForHeader")


        // --- NEW: Generate the X-TOK header using replicated logic ---
        val xTok = generateXTok()
        Log.d(TAG, "Generated X-TOK: $xTok")
        // --- END NEW ---


        // 3. POST to fetch encrypted string from embedstreams.top/fetch
        val postData = mapOf(
            "source" to source,
            "id" to streamId,
            "streamNo" to streamNo.toString()
        )
        // Headers for the /fetch POST request. Add X-Tok.
        val fetchHeaders = BASE_HEADERS.toMutableMap().apply {
            this["Referer"] = embedReferer
            this["Cookie"] = combinedCookiesForHeader // Always send combined cookies
            this["X-TOK"] = xTok // Add the generated X-Tok header
            this["Content-Type"] = "application/json"
        }.toMap()

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

        // Try to fetch M3U8 content from primary and fallback domains
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

            Log.d(TAG, "Detected HLS encryption ($encryptionMethod). Key URI: $keyUri, Full Key URL: $keyUrl")

            val keyBytes: ByteArray? = try {
                val keyHeaders = BASE_HEADERS.toMutableMap().apply {
                    this["Referer"] = embedReferer
                    if (combinedCookiesForHeader.isNotEmpty()) {
                        this["Cookie"] = combinedCookiesForHeader
                    }
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
                callback.invoke(
                    newExtractorLink(
                        source = "Streamed",
                        name = "$source Stream $streamNo (${language.replaceFirstChar { it.uppercase(Locale.ROOT) }}${if (isHd) ", HD" else ""})",
                        url = finalM3u8UrlUsed,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = embedReferer
                        this.headers = m3u8Headers.toMutableMap()
                        this.key = keyBytes
                        this.quality = if (isHd) Qualities.P1080.value else Qualities.Unknown.value
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
                    name = "$source Stream $streamNo (${language.replaceFirstChar { it.uppercase(Locale.ROOT) }}${if (isHd) ", HD" else ""})") {
                    url = finalM3u8UrlUsed!!
                    type = ExtractorLinkType.M3U8
                    referer = embedReferer
                    headers = m3u8Headers.toMutableMap()
                    quality = if (isHd) Qualities.P1080.value else Qualities.Unknown.value
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
        try {
            val response = app.post(
                COOKIE_API_URL,
                headers = mapOf("Content-Type" to "text/plain"),
                requestBody = payload.toRequestBody("text/plain".toMediaType()),
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
