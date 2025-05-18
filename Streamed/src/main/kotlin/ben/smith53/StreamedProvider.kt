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

    private val sources = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")
    private val maxStreams = 4
    private val maxRetries = 3
    private val timeoutSeconds = 20L

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
        val rawList = app.get(request.data, timeout = timeoutSeconds).text
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

        sources.map { source ->
            withContext(Dispatchers.IO) {
                for (streamNo in 1..maxStreams) {
                    val streamUrl = "$mainUrl/watch/$matchId/$source/$streamNo"
                    Log.d("StreamedProvider", "Processing stream URL: $streamUrl")
                    if (extractor.getUrl(streamUrl, matchId, source, streamNo, subtitleCallback, callback)) {
                        success = true
                    }
                }
            }
        }.forEach { it.join() }

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
    private val fallbackDecryptUrl = "https://backup-decrypt.deno.dev/decrypt"
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        "Content-Type" to "application/json",
        "Accept" to "*/*",
        "Origin" to "https://streamed.su"
    )
    private val fallbackDomains = listOf("p2-panel.streamed.su", "streamed.su", "cdn.streamed.su")
    private val cookieCache = mutableMapOf<String, String>()

    suspend fun getUrl(
        streamUrl: String,
        matchId: String,
        source: String,
        streamNo: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StreamedMediaExtractor", "Starting extraction for: $streamUrl")

        var streamCookies = ""
        repeat(maxRetries) { attempt ->
            try {
                val streamResponse = app.get(streamUrl, headers = baseHeaders, timeout = timeoutSeconds)
                streamCookies = streamResponse.cookies
                Log.d("StreamedMediaExtractor", "Stream cookies: $streamCookies")
                if (streamCookies.isNotEmpty()) return@repeat
            } catch (e: Exception) {
                Log.w("StreamedMediaExtractor", "Stream page fetch attempt ${attempt + 1} failed: ${e.message}")
                if (attempt == maxRetries - 1) return false
                kotlinx.coroutines.delay(1000L)
            }
        }

        val eventCookies = fetchEventCookies(streamUrl, streamUrl)
        Log.d("StreamedMediaExtractor", "Event cookies: $eventCookies")

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
        Log.d("StreamedMediaExtractor", "Fetching with data: $postData")

        var encryptedResponse: String? = null
        repeat(maxRetries) { attempt ->
            try {
                val response = app.post(fetchUrl, headers = fetchHeaders, json = postData, timeout = timeoutSeconds)
                Log.d("StreamedMediaExtractor", "Fetch response code: ${response.code}")
                if (response.code == 200) {
                    encryptedResponse = response.text
                    return@repeat
                }
            } catch (e: Exception) {
                Log.w("StreamedMediaExtractor", "Fetch attempt ${attempt + 1} failed: ${e.message}")
            }
            if (attempt < maxRetries - 1) kotlinx.coroutines.delay(1000L)
        }

        if (encryptedResponse == null) {
            Log.e("StreamedMediaExtractor", "All fetch attempts failed")
            return false
        }
        Log.d("StreamedMediaExtractor", "Encrypted response: $encryptedResponse")

        val decryptUrls = listOf(decryptUrl, fallbackDecryptUrl)
        var decryptedPath: String? = null
        for (decryptEndpoint in decryptUrls) {
            repeat(maxRetries) { attempt ->
                try {
                    val decryptPostData = mapOf("encrypted" to encryptedResponse)
                    val decryptResponse = app.post(
                        decryptEndpoint,
                        json = decryptPostData,
                        headers = mapOf("Content-Type" to "application/json"),
                        timeout = timeoutSeconds
                    ).parsedSafe<Map<String, String>>()
                    decryptedPath = decryptResponse?.get("decrypted")
                    if (decryptedPath != null) return@repeat
                } catch (e: Exception) {
                    Log.w("StreamedMediaExtractor", "Decryption attempt ${attempt + 1} failed with $decryptEndpoint: ${e.message}")
                }
                if (attempt < maxRetries - 1) kotlinx.coroutines.delay(1000L)
            }
            if (decryptedPath != null) break
        }

        if (decryptedPath == null) {
            Log.e("StreamedMediaExtractor", "Decryption failed across all endpoints")
            return false
        }

        val m3u8BaseUrl = "https://rr.buytommy.top$decryptedPath"
        val m3u8Headers = baseHeaders + mapOf(
            "Referer" to embedReferer,
            "Cookie" to combinedCookies
        )

        for (domain in listOf("rr.buytommy.top") + fallbackDomains) {
            try {
                val testUrl = m3u8BaseUrl.replace("rr.buytommy.top", domain)
                val testResponse = app.get(testUrl, headers = m3u8Headers, timeout = timeoutSeconds)
                if (testResponse.code == 200 && testResponse.text.contains("#EXTM3U")) {
                    callback.invoke(
                        newExtractorLink(
                            source = "Streamed",
                            name = "$source Stream $streamNo",
                            url = testUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = embedReferer
                            this.quality = Qualities.Unknown.value
                            this.headers = m3u8Headers
                        }
                    )
                    Log.d("StreamedMediaExtractor", "M3U8 URL added: $testUrl")
                    return true
                }
            } catch (e: Exception) {
                Log.e("StreamedMediaExtractor", "M3U8 test failed for $domain: ${e.message}")
            }
        }

        Log.e("StreamedMediaExtractor", "All M3U8 tests failed for $m3u8BaseUrl")
        return false
    }

    private suspend fun fetchEventCookies(pageUrl: String, referrer: String): String {
        cookieCache[pageUrl]?.let { return it }

        val payload = """{"n":"pageview","u":"$pageUrl","d":"streamed.su","r":"$referrer"}"""
        repeat(maxRetries) { attempt ->
            try {
                val response = app.post(
                    cookieUrl,
                    data = mapOf(),
                    headers = mapOf("Content-Type" to "text/plain"),
                    requestBody = payload.toRequestBody("text/plain".toMediaType()),
                    timeout = timeoutSeconds
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
                Log.w("StreamedMediaExtractor", "Event cookies fetch attempt ${attempt + 1} failed: ${e.message}")
            }
            if (attempt < maxRetries - 1) kotlinx.coroutines.delay(1000L)
        }
        return ""
    }
}