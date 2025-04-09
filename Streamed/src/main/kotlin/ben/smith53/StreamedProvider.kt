package ben.smith53

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import android.util.Log
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
    private val baseUrl = "https://rr.buytommy.top"
    private val serverUrl = "https://bensmithgb53-decrypt-13.deno.dev"
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
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

        // Step 3: Decrypt the path to get M3U8 URL
        val m3u8Url = try {
            val decryptResponse = app.post(
                "$serverUrl/decrypt",
                json = mapOf("encrypted" to encryptedResponse, "referer" to embedReferer),
                headers = mapOf("Content-Type" to "application/json"),
                timeout = 15
            ).parsedSafe<Map<String, String>>()
            val decryptedPath = decryptResponse?.get("decrypted") ?: throw Exception("No 'decrypted' key in response")
            "$baseUrl$decryptedPath"
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Decryption failed: ${e.message}")
            return false
        }
        Log.d("StreamedExtractor", "Constructed M3U8 URL: $m3u8Url")

        // Step 4: Fetch M3U8 content via Deno server
        val m3u8Headers = baseHeaders + mapOf(
            "Referer" to embedReferer,
            "Cookie" to cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
            "Accept-Encoding" to "br" // Match Deno's request
        )
        val m3u8Content = try {
            val fetchResponse = app.post(
                "$serverUrl/fetch-m3u8",
                json = mapOf(
                    "m3u8Url" to m3u8Url,
                    "cookies" to cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
                    "referer" to embedReferer
                ),
                headers = mapOf("Content-Type" to "application/json"),
                timeout = 15
            )
            val responseText = fetchResponse.text
            Log.d("StreamedExtractor", "Raw response from Deno: $responseText")
            val jsonResponse = fetchResponse.parsedSafe<Map<String, String>>()
            jsonResponse?.get("m3u8") ?: throw Exception("No 'm3u8' key in response")
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "M3U8 fetch from Deno failed: ${e.message}")
            return false
        }
        Log.d("StreamedExtractor", "M3U8 content:\n$m3u8Content")

        // Step 5: Verify and pass to ExoPlayer
        if (!m3u8Content.contains("#EXT-X-KEY")) {
            Log.e("StreamedExtractor", "No #EXT-X-KEY found in M3U8")
            return false
        }

        callback.invoke(
            ExtractorLink(
                "Streamed",
                "$source Stream $streamNo",
                m3u8Url,
                embedReferer,
                Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8,
                headers = m3u8Headers
            )
        )
        Log.d("StreamedExtractor", "ExtractorLink added: $m3u8Url")
        return true
    }
}