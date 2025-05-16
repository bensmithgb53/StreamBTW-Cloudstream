package ben.smith53

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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

        sources.forEach { source ->
            for (streamNo in 1..maxStreams) {
                val streamUrl = "$mainUrl/watch/$matchId/$source/$streamNo"
                Log.d("StreamedProvider", "Processing stream URL: $streamUrl")
                if (extractor.getUrl(streamUrl, matchId, source, streamNo, subtitleCallback, callback)) {
                    success = true
                }
            }
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
    private val cookieCache = mutableMapOf<String, String>()
    private val keyCache = mutableMapOf<String, ByteArray>()

    // Hardcoded IV from JavaScript
    private val defaultIvHex = "507dcd1eb7f04bb6983b19be56b89020"
    private val defaultIv: ByteArray = defaultIvHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    suspend fun getUrl(
        streamUrl: String,
        matchId: String,
        source: String,
        streamNo: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
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
            Log.d("StreamedMediaExtractor", "Fetch response code: ${response.code}")
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

        // Fetch M3U8 to extract key URL
        val m3u8Content = try {
            app.get(m3u8Url, headers = m3u8Headers, timeout = 15).text
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Failed to fetch M3U8: ${e.message}")
            return false
        }

        // Parse #EXT-X-KEY
        var keyUrl: String? = null
        m3u8Content.lines().forEach { line ->
            if (line.startsWith("#EXT-X-KEY")) {
                val uriMatch = Regex("URI=\"([^\"]+)\"").find(line)
                keyUrl = uriMatch?.groups?.get(1)?.value
            }
        }

        // If no key URL in M3U8, construct it
        if (keyUrl == null) {
            keyUrl = "https://rr.buytommy.top/$source/key/$matchId/$streamNo"
        }

        // Fetch and cache the key
        val key = keyCache[keyUrl] ?: run {
            try {
                val keyResponse = app.get(
                    keyUrl!!,
                    headers = m3u8Headers + mapOf("Referer" to "https://embedstreams.top/"),
                    timeout = 15
                )
                if (keyResponse.code != 200) {
                    Log.e("StreamedMediaExtractor", "Failed to fetch key: ${keyResponse.code}")
                    return false
                }
                val keyBytes = keyResponse.body.bytes()
                keyCache[keyUrl!!] = keyBytes
                keyBytes
            } catch (e: Exception) {
                Log.e("StreamedMediaExtractor", "Key fetch failed: ${e.message}")
                return false
            }
        }

        // Proxy M3U8: Rewrite .ts URLs and remove #EXT-X-KEY
        val proxyUrl = "http://localhost:8080/m3u8/$matchId/$source/$streamNo"
        val modifiedM3u8 = m3u8Content.lines()
            .filterNot { it.startsWith("#EXT-X-KEY") }
            .map { line ->
                if (line.endsWith(".ts")) {
                    "http://localhost:8080/decrypt/$matchId/$source/$streamNo/$line"
                } else {
                    line
                }
            }.joinToString("\n")

        // Note: Local server setup is required (see below)
        // For now, we'll add the original M3U8 as a fallback
        for (domain in listOf("rr.buytommy.top") + fallbackDomains) {
            try {
                val testUrl = m3u8Url.replace("rr.buytommy.top", domain)
                val testResponse = app.get(testUrl, headers = m3u8Headers, timeout = 15)
                if (testResponse.code == 200) {
                    callback.invoke(
                        newExtractorLink(
                            source = "Streamed",
                            name = "$source Stream $streamNo",
                            url = testUrl, // Use proxyUrl if local server is implemented
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = embedReferer
                            this.quality = Qualities.Unknown.value
                            this.headers = m3u8Headers
                        }
                    )
                    Log.d("StreamedMediaExtractor", "M3U8 URL added: $testUrl")
                    return true
                } else {
                    Log.w("StreamedMediaExtractor", "M3U8 test failed for $domain with code: ${testResponse.code}")
                }
            } catch (e: Exception) {
                Log.e("StreamedMediaExtractor", "M3U8 test failed for $domain: ${e.message}")
            }
        }

        // Fallback: Add link anyway
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
        Log.d("StreamedMediaExtractor", "M3U8 test failed but added anyway: $m3u8Url")
        return true
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

    private fun decryptSegment(encryptedData: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKey = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            return cipher.doFinal(encryptedData)
        } catch (e: Exception) {
            throw Exception("AES decryption failed: ${e.message}")
        }
    }
}