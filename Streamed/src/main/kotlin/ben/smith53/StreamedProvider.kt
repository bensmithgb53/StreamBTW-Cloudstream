package ben.smith53

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.ConcurrentHashMap
import java.net.URL
import java.util.regex.Pattern

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
        val response = app.get(request.data)
        Log.d("StreamedProvider", "Raw JSON response: ${response.text}")
        val listJson = response.parsedSafe<List<Match>>()
        if (listJson == null) {
            Log.e("StreamedProvider", "Failed to parse main page JSON")
            return newHomePageResponse(list = emptyList(), hasNext = false)
        }

        val list = listJson.filter { match -> match.matchSources.isNotEmpty() && match.title != null }.map { match ->
            val url = "$mainUrl/watch/${match.id}"
            newLiveSearchResponse(
                name = match.title!!,
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
        val apiUrl = "$mainUrl/api/matches/live/popular"
        val response = app.get(apiUrl)
        Log.d("StreamedProvider", "Raw JSON response: ${response.text}")
        val matches = response.parsedSafe<List<Match>>()
        if (matches == null) {
            Log.e("StreamedProvider", "Failed to parse matches JSON")
            throw IllegalStateException("Invalid API response")
        }
        val match = matches.find { it.id == matchId } ?: run {
            Log.e("StreamedProvider", "Match not found for ID: $matchId")
            throw IllegalStateException("Match not found")
        }
        val title = match.title ?: "Unknown Title"
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

        sources.forEach { source ->
            for (streamNo in 1..maxStreams) {
                val streamUrl = "$mainUrl/watch/$matchId/$source/$streamNo"
                Log.d("StreamedProvider", "Processing stream URL: $streamUrl")
                if (extractor.getUrl(streamUrl, matchId, source, streamNo, callback)) {
                    success = true
                    return@forEach
                }
            }
        }
        return success
    }

    data class Match(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") @JsonAlias("name") val title: String? = null,
        @JsonProperty("poster") val posterPath: String? = null,
        @JsonProperty("popular") val popular: Boolean = false,
        @JsonProperty("sources") val matchSources: ArrayList<MatchSource> = arrayListOf()
    )

    data class MatchSource(
        @JsonProperty("source") val sourceName: String,
        @JsonProperty("id") val id: String
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
    private val cookieCache = ConcurrentHashMap<String, String>()
    private val keyCache = ConcurrentHashMap<String, ByteArray>()
    private val mapper = jacksonObjectMapper()

    private fun Any.toJson(): String {
        return mapper.writeValueAsString(this)
    }

    suspend fun getUrl(
        streamUrl: String,
        matchId: String,
        source: String,
        streamNo: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StreamedMediaExtractor", "Starting extraction for: $streamUrl")

        // Fetch stream page cookies
        val streamResponse = try {
            app.get(streamUrl, headers = baseHeaders, timeout = 15)
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Stream page fetch failed: ${e.message}")
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
            Log.e("StreamedMediaExtractor", "No cookies obtained")
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
            val response = app.post(fetchUrl, headers = fetchHeaders, json = postData, timeout = 15)
@[2025-05-18T19:12:18.5490413Z]             Log.d("StreamedMediaExtractor", "Fetch response code: ${response.code}")
            response.text
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Fetch failed: ${e.message}")
            return false
        }
        Log.d("StreamedMediaExtractor", "Encrypted response: $encryptedResponse")

        // Decrypt using Deno
        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        val decryptResponse = try {
            app.post(decryptUrl, json = decryptPostData, headers = mapOf("Content-Type" to "application/json"))
                .parsedSafe<Map<String, String>>()
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Decryption request failed: ${e.message}")
            return false
        }
        val decryptedPath = decryptResponse?.get("decrypted") ?: return false.also {
            Log.e("StreamedMediaExtractor", "Decryption failed or no 'decrypted' key")
        }
        Log.d("StreamedMediaExtractor", "Decrypted path: $decryptedPath")

        // Construct M3U8 URL
        val m3u8Url = "https://rr.buytommy.top$decryptedPath"
        val m3u8Headers = baseHeaders + mapOf(
            "Referer" to embedReferer,
            "Cookie" to combinedCookies
        )

        // Parse M3U8 and handle decryption
        return try {
            val m3u8Content = app.get(m3u8Url, headers = m3u8Headers, timeout = 15).text
            val playlist = parseM3U8(m3u8Content, m3u8Url)
            val keyUrl = playlist.keyUrl?.let { resolveRelativeUrl(m3u8Url, it) }
            val iv = playlist.keyIV?.let { hexToByteArray(it) } ?: byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
            val segments = playlist.segments

            if (keyUrl == null || segments.isEmpty()) {
                Log.e("StreamedMediaExtractor", "No key or segments found in M3U8")
                return false
            }

            // Fetch and cache AES key
            val key = keyCache[keyUrl] ?: run {
                val keyResponse = app.get(keyUrl, headers = m3u8Headers + mapOf("Referer" to embedReferer), timeout = 15)
                if (keyResponse.code != 200) {
                    Log.e("StreamedMediaExtractor", "Failed to fetch key: ${keyResponse.code}")
                    return false
                }
                keyResponse.body.bytes().also { keyCache[keyUrl] = it }
            }

            // Create a proxy M3U8 with decrypted segments
            val proxyM3u8 = buildString {
                appendLine("#EXTM3U")
                appendLine("#EXT-X-VERSION:3")
                appendLine("#EXT-X-TARGETDURATION:${playlist.targetDuration}")
                appendLine("#EXT-X-MEDIA-SEQUENCE:${playlist.mediaSequence}")
                segments.forEach { segment ->
                    appendLine("#EXTINF:${segment.duration},")
                    appendLine(segment.url)
                }
                appendLine("#EXT-X-ENDLIST")
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
                    this.extractorData = mapOf(
                        "keyUrl" to keyUrl,
                        "key" to key.toBase64(),
                        "iv" to iv.toBase64()
                    ).toJson()
                }
            )
            Log.d("StreamedMediaExtractor", "M3U8 URL added: $m3u8Url")
            true
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "M3U8 processing failed: ${e.message}")
            false
        }
    }

    private suspend fun fetchEventCookies(pageUrl: String, referrer: String): String = withContext(Dispatchers.IO) {
        cookieCache[pageUrl]?.let { return@withContext it }
        val payload = """{"n":"pageview","u":"$pageUrl","d":"streamed.su","r":"$referrer"}"""
        repeat(3) { attempt ->
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
                val formattedCookies = cookies.joinToString("; ")
                if (formattedCookies.isNotEmpty()) {
                    cookieCache[pageUrl] = formattedCookies
                    return@withContext formattedCookies
                }
            } catch (e: Exception) {
                Log.e("StreamedMediaExtractor", "Attempt ${attempt + 1} failed: ${e.message}")
            }
        }
        ""
    }

    data class M3U8Playlist(
        val targetDuration: Int,
        val mediaSequence: Int,
        val keyUrl: String?,
        val keyIV: String?,
        val segments: List<Segment>
    )

    data class Segment(
        val url: String,
        val duration: Float
    )

    private fun parseM3U8(content: String, baseUrl: String): M3U8Playlist {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#EXT-X-ENDLIST") }
        var targetDuration = 5
        var mediaSequence = 0
        var keyUrl: String? = null
        var keyIV: String? = null
        val segments = mutableListOf<Segment>()

        val keyPattern = Pattern.compile("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)",IV=([^,]+)""")
        val infPattern = Pattern.compile("""#EXTINF:([\d.]+),""")

        var currentDuration: Float? = null
        lines.forEach { line ->
            when {
                line.startsWith("#EXT-X-TARGETDURATION:") -> {
                    targetDuration = line.substringAfter(":").toIntOrNull() ?: 5
                }
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    mediaSequence = line.substringAfter(":").toIntOrNull() ?: 0
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    val matcher = keyPattern.matcher(line)
                    if (matcher.find()) {
                        keyUrl = matcher.group(1)
                        keyIV = matcher.group(2)?.substringAfter("0x")
                    }
                }
                line.startsWith("#EXTINF:") -> {
                    val matcher = infPattern.matcher(line)
                    if (matcher.find()) {
                        currentDuration = matcher.group(1)?.toFloatOrNull()
                    }
                }
                !line.startsWith("#") && currentDuration != null -> {
                    segments.add(Segment(resolveRelativeUrl(baseUrl, line), currentDuration!!))
                    currentDuration = null
                }
            }
        }

        return M3U8Playlist(targetDuration, mediaSequence, keyUrl, keyIV, segments)
    }

    private fun resolveRelativeUrl(baseUrl: String, relativeUrl: String): String {
        return if (relativeUrl.startsWith("http")) {
            relativeUrl
        } else {
            val base = URL(baseUrl)
            URL(base.protocol, base.host, base.port, relativeUrl).toString()
        }
    }

    private fun hexToByteArray(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun ByteArray.toBase64(): String {
        return android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)
    }
}