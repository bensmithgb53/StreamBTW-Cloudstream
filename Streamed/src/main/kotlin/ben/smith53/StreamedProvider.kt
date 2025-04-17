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

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed"
    override var supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

    private val sources = listOf("admin", "alpha", "bravo", "charlie", "delta")
    private val maxStreams = 3
    private val proxyUrl = "http://localhost:8000/fetch"

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
    private val cookieUrl = "https://fishy.streamed.su/api/event"
    private val proxyUrl = "http://localhost:8000/fetch"
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Content-Type" to "application/json",
        "Origin" to "https://embedstreams.top",
        "Referer" to "https://embedstreams.top/"
    )

    suspend fun getCookies(): String? {
        try {
            val response = app.post(
                cookieUrl,
                headers = baseHeaders + mapOf("Accept" to "text/plain"),
                json = mapOf("event" to "pageview")
            )
            val cookies = response.cookies
            Log.d("StreamedExtractor", "Raw cookies: $cookies")
            
            // Extract specific cookies in the required order
            val cookieMap = cookies.entries.associate { it.key to it.value }
            val requiredCookies = listOf("ddg8_", "ddg10_", "ddg9_", "ddg1_")
            val formattedCookies = requiredCookies.mapNotNull { key ->
                cookieMap[key]?.let { value -> "${key.removePrefix("__")}=$value" }
            }.joinToString("; ")
            
            Log.d("StreamedExtractor", "Formatted cookies: $formattedCookies")
            return if (formattedCookies.isNotEmpty()) formattedCookies else null
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Failed to fetch cookies: ${e.message}")
            return null
        }
    }

    suspend fun getUrl(
        streamUrl: String,
        matchId: String,
        source: String,
        streamNo: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StreamedExtractor", "Starting extraction for: $streamUrl")

        // Fetch cookies
        val cookies = getCookies() ?: run {
            Log.e("StreamedExtractor", "No cookies retrieved")
            return false
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
            "Cookie" to cookies
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
        val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        val decryptResponse = try {
            app.post(decryptUrl, json = decryptPostData, headers = mapOf("Content-Type" to "application/json"))
                .parsedSafe<Map<String, String>>()
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Decryption request failed: ${e.message}")
            return false
        }
        val decryptedPath = decryptResponse?.get("decrypted") ?: return false.also { Log.e("StreamedExtractor", "Decryption failed or no 'decrypted' key") }
        Log.d("StreamedExtractor", "Decrypted path: $decryptedPath")

        // Construct M3U8 URL
        val m3u8Url = "https://rr.buytommy.top$decryptedPath"

        // Send to localhost proxy
        val proxyPostData = mapOf(
            "m3u8Url" to m3u8Url,
            "cookies" to cookies
        )
        try {
            val proxyResponse = app.post(
                proxyUrl,
                headers = baseHeaders,
                json = proxyPostData,
                timeout = 15
            )
            if (proxyResponse.code == 200) {
                callback.invoke(
                    newExtractorLink(
                        source = "Streamed",
                        name = "$source Stream $streamNo",
                        url = "http://localhost:8000/playlist.m3u8",
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = embedReferer
                        this.quality = Qualities.Unknown.value
                        this.headers = baseHeaders
                    }
                )
                Log.d("StreamedExtractor", "Proxy M3U8 URL added: http://localhost:8000/playlist.m3u8")
                return true
            } else {
                Log.e("StreamedExtractor", "Proxy request failed with code: ${proxyResponse.code}")
                return false
            }
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Proxy request failed: ${e.message}")
            return false
        }
    }
}