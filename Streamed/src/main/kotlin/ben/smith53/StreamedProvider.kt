package ben.smith53

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log
import java.util.Locale

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed"
    override var supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

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
        Log.d("StreamedProvider", "MainPage ${request.name}: ${rawList.take(200)}...")
        val listJson = parseJson<List<Match>>(rawList)
        Log.d("StreamedProvider", "Parsed ${request.name}: ${listJson.size} matches")

        val list = listJson.filter {
            if (it.matchSources.isEmpty()) {
                Log.w("StreamedProvider", "Match ${it.title} has no sources")
                false
            } else {
                true
            }
        }.map {
            val url = "$mainUrl/watch/${it.id}"
            newLiveSearchResponse(it.title, url, TvType.Live) {
                posterUrl = "$mainUrl${it.posterPath ?: "/api/images/poster/fallback.webp"}"
            }
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, list, isHorizontalImages = true)),
            hasNext = false
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val matchId = url.substringAfterLast("/")
        val title = matchId.replace("-", " ")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            .replace(Regex("-\\d+$"), "")
        val posterUrl = "$mainUrl/api/images/poster/$matchId.webp"
        return newLiveStreamLoadResponse(title, url, url) {
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
        val matchData = try {
            app.get("$mainUrl/api/matches/id/$matchId").parsedSafe<Match>()
        } catch (e: Exception) {
            Log.e("StreamedProvider", "Failed to fetch match data for $matchId: ${e.message}")
            return false
        }
        if (matchData == null || matchData.matchSources.isEmpty()) {
            Log.e("StreamedProvider", "No sources for matchId: $matchId")
            return false
        }
        Log.d("StreamedProvider", "Match sources for $matchId: ${matchData.matchSources.map { it.sourceName }}")

        val extractor = StreamedMediaExtractor()
        var success = false
        matchData.matchSources.forEach { source ->
            for (streamNo in 1..maxStreams) {
                val streamUrl = "$mainUrl/watch/$matchId/${source.sourceName}/$streamNo"
                Log.d("StreamedProvider", "Trying stream: $streamUrl")
                if (extractor.getUrl(streamUrl, matchId, source.sourceName, streamNo, subtitleCallback, callback)) {
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
    private val cookieCache = mutableMapOf<String, String>()

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Content-Type" to "application/json"
    )

    suspend fun getUrl(
        streamUrl: String,
        matchId: String,
        source: String,
        streamNo: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StreamedExtractor", "Extracting: $streamUrl (matchId: $matchId, source: $source, streamNo: $streamNo)")

        // Fetch the stream page
        val streamResponse = try {
            app.get(streamUrl, headers = baseHeaders, timeout = 15)
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Failed to fetch stream page $streamUrl: ${e.message}")
            return false
        }
        Log.d("StreamedExtractor", "Stream page response code: ${streamResponse.code}")

        val streamCookies = streamResponse.cookies
        val eventCookies = fetchEventCookies(streamUrl, streamUrl)
        val combinedCookies = buildString {
            if (streamCookies.isNotEmpty())
                append(streamCookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            if (eventCookies.isNotEmpty()) {
                if (isNotEmpty()) append("; ")
                append(eventCookies)
            }
        }
        Log.d("StreamedExtractor", "Cookies: $combinedCookies")
        if (combinedCookies.isEmpty()) return false

        val embedReferer = "https://embedstreams.top/embed/$source/$matchId/$streamNo"
        val postData = mapOf("source" to source, "id" to matchId, "streamNo" to streamNo.toString())

        // Fetch encrypted response
        val encryptedResponse = try {
            app.post(fetchUrl, headers = baseHeaders + mapOf(
                "Referer" to streamUrl,
                "Cookie" to combinedCookies
            ), json = postData).text
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Failed to fetch encrypted stream for $streamUrl: ${e.message}")
            return false
        }
        Log.d("StreamedExtractor", "Encrypted response: ${encryptedResponse.take(200)}...")

        // Decrypt response
        val decrypted = try {
            app.post(decryptUrl, json = mapOf("encrypted" to encryptedResponse)).parsedSafe<Map<String, String>>()
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Decryption failed for $streamUrl: ${e.message}")
            null
        } ?: return false
        Log.d("StreamedExtractor", "Decrypted response: $decrypted")

        val path = decrypted["decrypted"] ?: return false
        val finalUrl = if (decrypted.containsKey("md5") && decrypted.containsKey("expiry")) {
            "https://rr.buytommy.top$path?md5=${decrypted["md5"]}&expiry=${decrypted["expiry"]}"
        } else {
            "https://rr.buytommy.top$path"
        }
        Log.d("StreamedExtractor", "Final URL: $finalUrl")

        // Fetch M3U8 playlist
        val m3u8Response = try {
            app.get(finalUrl, headers = baseHeaders + mapOf(
                "Referer" to embedReferer,
                "Cookie" to combinedCookies
            ), timeout = 15)
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Failed to fetch M3U8 $finalUrl: ${e.message}")
            return false
        }
        Log.d("StreamedExtractor", "M3U8 response code: ${m3u8Response.code}, body: ${m3u8Response.text.take(200)}...")

        if (m3u8Response.code != 200) return false

        // Parse M3U8 for key URL
        val keyUrlMatch = Regex("#EXT-X-KEY:METHOD=AES-128,URI=\"([^\"]+)\"").find(m3u8Response.text)
        val keyUrl = keyUrlMatch?.groups?.get(1)?.value?.let {
            if (it.startsWith("/")) "https://rr.buytommy.top$it" else it
        }
        Log.d("StreamedExtractor", "Key URL: $keyUrl")

        // Fetch decryption key
        if (keyUrl != null) {
            try {
                val keyResponse = app.get(keyUrl, headers = baseHeaders + mapOf(
                    "Referer" to embedReferer,
                    "Cookie" to combinedCookies
                ), timeout = 15)
                Log.d("StreamedExtractor", "Key response code: ${keyResponse.code}, length: ${keyResponse.text.length}")
                if (keyResponse.code != 200 || keyResponse.text.length != 16) {
                    Log.w("StreamedExtractor", "Invalid key response for $keyUrl: code=${keyResponse.code}, length=${keyResponse.text.length}")
                    return false
                }
            } catch (e: Exception) {
                Log.e("StreamedExtractor", "Failed to fetch key $keyUrl: ${e.message}")
                return false
            }
        } else {
            Log.w("StreamedExtractor", "No key URL found in M3U8")
            return false
        }

        // Parse M3U8 for segment URLs (for validation)
        val segmentUrls = Regex("https://[a-z0-9]+\\.eu\\.r2\\.cloudflarestorage\\.com/IMG_[0-9]+\\.png\\?X-Amz-Algorithm=AWS4-HMAC-SHA256[^\\n]+")
            .findAll(m3u8Response.text).map { it.value }.toList()
        Log.d("StreamedExtractor", "Segment URLs: $segmentUrls")

        // Test one segment URL to ensure accessibility
        if (segmentUrls.isNotEmpty()) {
            try {
                val segmentResponse = app.get(segmentUrls.first(), headers = baseHeaders + mapOf(
                    "Referer" to embedReferer,
                    "Cookie" to combinedCookies
                ), timeout = 15)
                Log.d("StreamedExtractor", "Segment response code: ${segmentResponse.code}")
                if (segmentResponse.code != 200 && segmentResponse.code != 206) {
                    Log.w("StreamedExtractor", "Invalid segment response for ${segmentUrls.first()}: code=${segmentResponse.code}")
                    return false
                }
            } catch (e: Exception) {
                Log.e("StreamedExtractor", "Failed to fetch segment ${segmentUrls.first()}: ${e.message}")
                return false
            }
        } else {
            Log.w("StreamedExtractor", "No segment URLs found in M3U8")
            return false
        }

        // Return the M3U8 URL as the extractor link
        callback.invoke(
            newExtractorLink(
                source = "Streamed",
                name = "$source Stream $streamNo",
                url = finalUrl,
                type = ExtractorLinkType.M3U8
            ) {
                referer = embedReferer
                quality = Qualities.Unknown.value
                headers = baseHeaders + mapOf("Referer" to embedReferer, "Cookie" to combinedCookies)
            }
        )
        return true
    }

    private suspend fun fetchEventCookies(pageUrl: String, referrer: String): String {
        cookieCache.remove(pageUrl) // Clear cache to avoid stale cookies
        val payload = """{"n":"pageview","u":"$pageUrl","d":"streamed.su","r":"$referrer"}"""
        return try {
            val response = app.post(
                cookieUrl,
                headers = mapOf("Content-Type" to "text/plain"),
                requestBody = payload.toRequestBody("text/plain".toMediaType()),
                timeout = 15
            )
            val cookies = response.headers.filter { it.first == "Set-Cookie" }
                .map { it.second.split(";")[0] }
            val formatted = listOf("_ddg8_", "_ddg10_", "_ddg9_", "_ddg1_")
                .mapNotNull { key -> cookies.find { it.startsWith(key) } }
                .joinToString("; ")
            if (formatted.isNotEmpty()) {
                cookieCache[pageUrl] = formatted
                Log.d("StreamedExtractor", "Event cookies for $pageUrl: $formatted")
                formatted
            } else {
                Log.w("StreamedExtractor", "No valid cookies received for $pageUrl")
                ""
            }
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Event cookie fetch failed for $pageUrl: ${e.message}")
            ""
        }
    }
}