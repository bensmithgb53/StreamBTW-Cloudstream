package ben.smith53

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import java.util.Locale

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
    private val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
    private val eventUrl = "https://fishy.streamed.su/api/event"
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Accept" to "*/*",
        "Accept-Encoding" to "gzip, deflate, br",
        "Accept-Language" to "en-GB,en-US;q=0.9,en;q=0.8",
        "Origin" to "https://streamed.su",
        "Referer" to "https://streamed.su/",
        "Sec-Ch-Ua" to "\"Not A(Brand\";v=\"8\", \"Chromium\";v=\"132\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-site"
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

        // Fetch cookies from stream page
        val streamResponse = try {
            app.get(streamUrl, headers = baseHeaders, timeout = 15)
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Stream page fetch failed: ${e.message}")
            return false
        }
        val streamCookies = streamResponse.cookies
        Log.d("StreamedExtractor", "Stream page cookies: $streamCookies")

        // Fetch cookies from fishy.streamed.su/api/event
        val fishyCookies = try {
            val eventPayload = mapOf(
                "n" to "pageview",
                "u" to streamUrl,
                "d" to "streamed.su",
                "r" to "https://streamed.su/watch/$matchId"
            )
            val eventHeaders = baseHeaders + mapOf("Content-Type" to "text/plain")
            val response = app.post(
                eventUrl,
                headers = eventHeaders,
                json = eventPayload,
                timeout = 15
            )
            response.cookies.also {
                Log.d("StreamedExtractor", "Fishy.streamed.su cookies: $it")
            }
        } catch (e: Exception) {
            Log.w("StreamedExtractor", "Failed to fetch cookies from fishy.streamed.su: ${e.message}")
            emptyMap()
        }

        // Combine cookies
        val allCookies = streamCookies + fishyCookies
        val cookieHeader = allCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        Log.d("StreamedExtractor", "Combined cookies: $cookieHeader")

        // Verify required cookies
        if (!allCookies.keys.containsAll(setOf("_ddg8_", "_ddg9_", "_ddg10_", "_ddg1_"))) {
            Log.w("StreamedExtractor", "Missing required cookies: ${setOf("_ddg8_", "_ddg9_", "_ddg10_", "_ddg1_").minus(allCookies.keys)}")
        }

        // POST to fetch encrypted string
        val postData = mapOf(
            "source" to source,
            "id" to matchId,
            "streamNo" to streamNo.toString()
        )
        val embedReferer = "https://embedstreams.top/embed/$source/$matchId/$streamNo"
        val fetchHeaders = baseHeaders + mapOf(
            "Referer" to streamUrl,
            "Cookie" to cookieHeader,
            "Content-Type" to "application/json"
        )
        Log.d("StreamedExtractor", "Fetching with data: $postData and headers: $fetchHeaders")

        val encryptedResponse = try {
            val response = app.post(fetchUrl, headers = fetchHeaders, json = postData, timeout = 15)
            Log.d("StreamedExtractor", "Fetch response code: ${response.code}")
            response.text
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Fetch failed: ${e.message}")
            return false
        }
        Log.d("StreamedExtractor", "Encrypted response: $encryptedResponse")

        // Decrypt using Deno
        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        val decryptResponse = try {
            app.post(decryptUrl, json = decryptPostData, headers = mapOf("Content-Type" to "application/json"))
                .parsedSafe<Map<String, String>>()
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Decryption request failed: ${e.message}")
            return false
        }
        val decryptedPath = decryptResponse?.get("decrypted") ?: return false.also {
            Log.e("StreamedExtractor", "Decryption failed or no 'decrypted' key")
        }
        Log.d("StreamedExtractor", "Decrypted path: $decryptedPath")

        // Construct M3U8 URL and try fallback domains
        val baseM3u8Url = "https://rr.buytommy.top$decryptedPath"
        val fallbackUrls = listOf(
            baseM3u8Url,
            baseM3u8Url.replace("rr.buytommy.top", "p2-panel.streamed.su"),
            baseM3u8Url.replace("rr.buytommy.top", "streamed.su")
        )
        var m3u8Content: String? = null

        for (m3u8Url in fallbackUrls) {
            Log.d("StreamedExtractor", "Attempting to fetch M3U8: $m3u8Url")
            val m3u8Headers = baseHeaders + mapOf(
                "Referer" to embedReferer,
                "Cookie" to cookieHeader
                // Optional: "Range" to "bytes=0-" (enable if SIDX detected)
            )
            var retryDelay = 1000L
            for (attempt in 1..5) {
                try {
                    val response = app.get(m3u8Url, headers = m3u8Headers, timeout = 15)
                    if (response.code == 200) {
                        m3u8Content = response.text
                        Log.d("StreamedExtractor", "M3U8 fetched successfully from $m3u8Url")
                        break
                    } else {
                        Log.w("StreamedExtractor", "M3U8 fetch attempt $attempt failed with code: ${response.code}")
                    }
                } catch (e: Exception) {
                    Log.w("StreamedExtractor", "M3U8 fetch attempt $attempt failed: ${e.message}")
                    if (attempt < 5) {
                        delay(retryDelay)
                        retryDelay = (retryDelay * 2).coerceAtMost(4000L)
                    }
                }
            }
            if (m3u8Content != null) break
        }

        if (m3u8Content == null) {
            Log.e("StreamedExtractor", "Failed to fetch M3U8 from all sources")
            return false
        }
        Log.d("StreamedExtractor", "M3U8 content (first 200 chars): ${m3u8Content.take(200)}")

        // Validate M3U8
        if (!m3u8Content.contains("#EXTM3U") || !m3u8Content.contains("#EXTINF") || !m3u8Content.contains("#EXT-X-VERSION")) {
            Log.e("StreamedExtractor", "Invalid M3U8 content: missing #EXTM3U, #EXTINF, or #EXT-X-VERSION")
            return false
        }
        if (m3u8Content.lines().count { it.startsWith("#EXTINF") } < 1) {
            Log.e("StreamedExtractor", "No fragments found in M3U8 (empty playlist)")
            return false
        }

        // Rewrite M3U8 content
        val rewrittenM3u8 = m3u8Content.lines().map { line ->
            when {
                line.startsWith("#") -> line // Keep comments and metadata
                line.startsWith("https://") && line.endsWith((".js", ".ts")) -> {
                    // Rewrite .js to .ts
                    val newUrl = line.replace(".js", ".ts")
                    Log.d("StreamedExtractor", "Rewrote segment: $line -> $newUrl")
                    newUrl
                }
                line.startsWith("https://") && !line.endsWith((".ts", ".m3u8")) -> {
                    Log.w("StreamedExtractor", "Skipping invalid segment: $line")
                    "" // Skip non-.ts/.m3u8 URLs (e.g., .png)
                }
                else -> line
            }
        }.joinToString("\n").trim()

        // Validate rewritten M3U8
        if (!rewrittenM3u8.lines().any { it.startsWith("https://") && it.endsWith(".ts") }) {
            Log.e("StreamedExtractor", "No valid .ts segments in rewritten M3U8 (empty playlist)")
            return false
        }

        // Encode M3U8 as data URI
        val encodedM3u8 = Base64.encodeToString(rewrittenM3u8.toByteArray(), Base64.NO_WRAP)
        val dataUri = "data:application/vnd.apple.mpegurl;base64,$encodedM3u8"
        Log.d("StreamedExtractor", "Encoded M3U8 as data URI: ${dataUri.take(100)}...")

        // Create ExtractorLink
        callback.invoke(
            newExtractorLink(
                source = "Streamed",
                name = "$source Stream $streamNo",
                url = dataUri,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = embedReferer
                this.quality = Qualities.Unknown.value
                this.headers = baseHeaders + mapOf("Cookie" to cookieHeader)
            }
        )
        Log.d("StreamedExtractor", "ExtractorLink added for: $dataUri")

        // Optional: Subtitle support (CEA-608 or TTML)
        if (m3u8Content.contains("CLOSED-CAPTIONS") || m3u8Content.contains("SUBTITLES") || m3u8Content.contains("stpp.ttml.im1t")) {
            Log.d("StreamedExtractor", "Subtitles detected (CEA-608 or TTML), parsing not implemented yet")
            // subtitleCallback.invoke(SubtitleFile("en", "data:text/vtt;base64,..."))
        }

        return true
    }
}