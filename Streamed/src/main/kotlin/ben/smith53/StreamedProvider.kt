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
import java.util.zip.GZIPInputStream

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed"
    override var supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

    private val sources = listOf("admin", "alpha", "bravo", "charlie", "delta")
    private val maxStreams = 3

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
        val extractor = StreamedExtractor()
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

class StreamedExtractor {
    private val fetchUrl = "https://embedstreams.top/fetch"
    private val baseUrl = "https://rr.buytommy.top"
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        "Accept" to "*/*",
        "Origin" to "https://embedstreams.top"
    )

    suspend fun getUrl(
        streamUrl: String,
        matchId: String,
        source: String,
        streamNo: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StreamedExtractor", "Starting extraction for: $streamUrl")

        // Step 1: Fetch cookies from the stream page
        val streamResponse = try {
            app.get(streamUrl, headers = baseHeaders, timeout = 15)
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Failed to fetch stream page: ${e.message}")
            return false
        }
        val cookies = streamResponse.cookies
        Log.d("StreamedExtractor", "Cookies: $cookies")

        // Step 2: Fetch encrypted path
        val embedReferer = "https://embedstreams.top/embed/$source/$matchId/$streamNo"
        val fetchHeaders = baseHeaders + mapOf(
            "Referer" to streamUrl,
            "Cookie" to cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
            "Content-Type" to "application/json"
        )
        val encryptedResponse = try {
            val postData = mapOf(
                "source" to source,
                "id" to matchId,
                "streamNo" to streamNo.toString()
            )
            app.post(fetchUrl, headers = fetchHeaders, json = postData, timeout = 15).text
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Failed to fetch encrypted path: ${e.message}")
            return false
        }
        Log.d("StreamedExtractor", "Encrypted path: $encryptedResponse")

        // Step 3: Decrypt the path
        val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
        val decryptedPath = try {
            val decryptResponse = app.post(
                decryptUrl,
                json = mapOf("encrypted" to encryptedResponse),
                headers = mapOf("Content-Type" to "application/json")
            ).parsedSafe<Map<String, String>>()
            decryptResponse?.get("decrypted") ?: throw Exception("No 'decrypted' key in response")
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Decryption failed: ${e.message}")
            return false
        }
        Log.d("StreamedExtractor", "Decrypted path: $decryptedPath")

        // Step 4: Fetch M3U8 with detailed response logging
        val m3u8Url = "$baseUrl$decryptedPath"
        Log.d("StreamedExtractor", "Constructed M3U8 URL: $m3u8Url")
        val m3u8Headers = baseHeaders + mapOf(
            "Referer" to embedReferer,
            "Cookie" to cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
            "Accept-Encoding" to "gzip, deflate, br"
        )
        val m3u8Content = try {
            val response = app.get(m3u8Url, headers = m3u8Headers, timeout = 15)
            val headersLog = response.headers.entries.joinToString("; ") { "${it.key}=${it.value}" }
            Log.d("StreamedExtractor", "M3U8 response headers: $headersLog")
            val rawBytes = response.body?.bytes() ?: throw Exception("No response body")
            Log.d("StreamedExtractor", "Raw M3U8 bytes (first 100): ${rawBytes.take(100).joinToString("") { "%02x".format(it) }}")

            // Check for gzip compression
            val contentEncoding = response.headers["Content-Encoding"]?.lowercase()
            val text = if (contentEncoding == "gzip") {
                Log.d("StreamedExtractor", "Detected gzip encoding, decompressing...")
                GZIPInputStream(rawBytes.inputStream()).bufferedReader().use { it.readText() }
            } else {
                rawBytes.toString(Charsets.UTF_8)
            }
            if (text.contains("403 token mismatch")) throw Exception("403 token mismatch received")
            text
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "M3U8 fetch failed: ${e.message}")
            // Fallback: Try fetching from embed page
            return tryEmbedFallback(embedReferer, m3u8Url, m3u8Headers, callback, source, streamNo)
        }
        Log.d("StreamedExtractor", "M3U8 content:\n$m3u8Content")

        // Step 5: Extract and fetch the key
        val keyUrlMatch = Regex("#EXT-X-KEY:METHOD=AES-128,URI=\"([^\"]+)\"").find(m3u8Content)
        val keyUrl = keyUrlMatch?.groupValues?.get(1)?.let {
            if (it.startsWith("http")) it else "$baseUrl$it"
        } ?: run {
            Log.e("StreamedExtractor", "No #EXT-X-KEY found in M3U8")
            return false
        }
        Log.d("StreamedExtractor", "Key URL: $keyUrl")

        // Fetch the key
        val keyBytes = try {
            val keyResponse = app.get(keyUrl, headers = m3u8Headers, timeout = 15)
            keyResponse.body?.bytes()?.also {
                Log.d("StreamedExtractor", "Key fetched: ${it.size} bytes, hex: ${it.joinToString("") { "%02x".format(it) }}")
                if (it.size != 16) Log.w("StreamedExtractor", "Key length is ${it.size}, expected 16 bytes for AES-128")
            } ?: throw Exception("No key content received")
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Key fetch failed: ${e.message}")
        }

        // Step 6: Pass the M3U8 to ExoPlayer
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
        Log.d("StreamedExtractor", "ExtractorLink added: $m3u8Url")
        return true
    }

    private suspend fun tryEmbedFallback(
        embedUrl: String,
        originalM3u8Url: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit,
        source: String,
        streamNo: Int
    ): Boolean {
        Log.d("StreamedExtractor", "Falling back to embed page: $embedUrl")
        val embedResponse = try {
            app.get(embedUrl, headers = headers, timeout = 15).text
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Embed fetch failed: ${e.message}")
            return false
        }
        Log.d("StreamedExtractor", "Embed page content (first 500 chars):\n${embedResponse.take(500)}")

        // Look for an M3U8 URL in the embed page
        val m3u8Match = Regex("https?://[^\"']+\\.m3u8[^\"']*").find(embedResponse)
        val fallbackM3u8Url = m3u8Match?.value ?: run {
            Log.e("StreamedExtractor", "No M3U8 URL found in embed page")
            return false
        }
        Log.d("StreamedExtractor", "Found M3U8 in embed: $fallbackM3u8Url")

        val m3u8Content = try {
            val response = app.get(fallbackM3u8Url, headers = headers, timeout = 15)
            response.text
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Fallback M3U8 fetch failed: ${e.message}")
            return false
        }
        Log.d("StreamedExtractor", "Fallback M3U8 content:\n$m3u8Content")

        // Extract key from fallback M3U8
        val keyUrlMatch = Regex("#EXT-X-KEY:METHOD=AES-128,URI=\"([^\"]+)\"").find(m3u8Content)
        val keyUrl = keyUrlMatch?.groupValues?.get(1)?.let {
            if (it.startsWith("http")) it else "$baseUrl$it"
        } ?: run {
            Log.e("StreamedExtractor", "No #EXT-X-KEY found in fallback M3U8")
            return false
        }
        Log.d("StreamedExtractor", "Fallback Key URL: $keyUrl")

        // Fetch the key
        try {
            val keyResponse = app.get(keyUrl, headers = headers, timeout = 15)
            keyResponse.body?.bytes()?.also {
                Log.d("StreamedExtractor", "Fallback Key fetched: ${it.size} bytes, hex: ${it.joinToString("") { "%02x".format(it) }}")
            }
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Fallback Key fetch failed: ${e.message}")
        }

        callback.invoke(
            newExtractorLink(
                source = "Streamed",
                name = "$source Stream $streamNo (Fallback)",
                url = fallbackM3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = embedUrl
                this.quality = Qualities.Unknown.value
                this.headers = headers
            }
        )
        Log.d("StreamedExtractor", "Fallback ExtractorLink added: $fallbackM3u8Url")
        return true
    }
}