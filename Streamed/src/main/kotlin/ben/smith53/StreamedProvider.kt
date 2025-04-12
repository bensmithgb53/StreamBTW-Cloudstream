package ben.smith53

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.newExtractorLink
import android.util.Log
import java.util.Locale
import java.net.URLEncoder

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed"
    override var supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

    private val sources = listOf("alpha", "bravo", "charlie", "delta")
    private val maxStreams = 3
    private val fetchUrl = "https://embedstreams.top/fetch"
    private val baseUrl = "https://rr.buytommy.top"
    private val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
    private val proxyServerUrl = "https://owen-decrypt-79.deno.dev"

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Accept" to "*/*",
        "Origin" to "https://embedstreams.top",
        "Referer" to "https://embedstreams.top/",
        "Accept-Encoding" to "identity"
    )

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
            val status = when {
                match.isLive == true -> "LIVE"
                match.startTime != null -> match.startTime
                else -> null
            }
            val posterUrl = if (status != null) {
                "$proxyServerUrl/poster?url=${URLEncoder.encode("$mainUrl${match.posterPath ?: "/api/images/poster/fallback.webp"}", "UTF-8")}&status=${URLEncoder.encode(status, "UTF-8")}"
            } else {
                "$mainUrl${match.posterPath ?: "/api/images/poster/fallback.webp"}"
            }
            newLiveSearchResponse(
                name = match.title,
                url = url,
                type = TvType.Live
            ) {
                this.posterUrl = posterUrl
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
        var hasValidSource = false

        sources.forEach { source ->
            for (streamNo in 1..maxStreams) {
                val streamUrl = "$mainUrl/watch/$matchId/$source/$streamNo"
                Log.d("StreamedProvider", "Processing stream URL: $streamUrl")
                if (extractor.getUrl(streamUrl, matchId, source, streamNo, subtitleCallback, callback)) {
                    hasValidSource = true
                }
            }
        }
        Log.d("StreamedProvider", "Overall success: $hasValidSource")
        return hasValidSource
    }

    data class Match(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String,
        @JsonProperty("poster") val posterPath: String? = null,
        @JsonProperty("popular") val popular: Boolean = false,
        @JsonProperty("sources") val matchSources: ArrayList<MatchSource> = arrayListOf(),
        @JsonProperty("isLive") val isLive: Boolean? = null,
        @JsonProperty("startTime") val startTime: String? = null
    )

    data class MatchSource(
        @JsonProperty("source") val sourceName: String,
        @JsonProperty("id") val id: String
    )

    inner class StreamedExtractor {
        suspend fun getUrl(
            streamUrl: String,
            matchId: String,
            source: String,
            streamNo: Int,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            Log.d("StreamedExtractor", "Starting extraction for: $streamUrl")

            // Fetch stream page to get cookies
            val streamResponse = try {
                app.get(streamUrl, headers = baseHeaders, timeout = 15)
            } catch (e: Exception) {
                Log.e("StreamedExtractor", "Stream page fetch failed: ${e.message}")
                return false
            }
            val cookies = streamResponse.cookies
            Log.d("StreamedExtractor", "Stream cookies: $cookies")

            // Infer stream type from title
            val streamType = try {
                val matchApiUrl = "$mainUrl/api/matches/$matchId"
                val matchData = app.get(matchApiUrl).parsedSafe<List<Match>>()?.firstOrNull()
                if (matchData?.title?.contains("24/7", ignoreCase = true) == true) "24/7" else "game"
            } catch (e: Exception) {
                Log.e("StreamedExtractor", "Failed to fetch stream type: ${e.message}")
                "game"
            }
            Log.d("StreamedExtractor", "Stream type: $streamType")

            // Fetch encrypted string
            val postData = mapOf(
                "source" to source,
                "id" to matchId,
                "streamNo" to streamNo.toString(),
                "streamType" to streamType
            )
            val embedReferer = "https://embedstreams.top/embed/$source/$matchId/$streamNo"
            val fetchHeaders = baseHeaders + mapOf(
                "Referer" to streamUrl,
                "Cookie" to cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
                "Content-Type" to "application/json",
                "X-Stream-Type" to streamType,
                "X-Stream-ID" to "$matchId-$source-$streamNo"
            )
            val encryptedResponse = try {
                val response = app.post(fetchUrl, headers = fetchHeaders, json = postData, timeout = 15)
                Log.d("StreamedExtractor", "Fetch response code: ${response.code}, headers: ${response.headers}, body: ${response.text.take(100)}")
                if (response.code != 200) throw Exception("Fetch failed with code ${response.code}")
                response.text
            } catch (e: Exception) {
                Log.e("StreamedExtractor", "Fetch failed: ${e.message}")
                return false
            }
            Log.d("StreamedExtractor", "Encrypted response: $encryptedResponse")

            // Decrypt with fallbacks
            val decryptPostData = mapOf(
                "encrypted" to encryptedResponse,
                "streamType" to streamType,
                "matchId" to matchId,
                "source" to source,
                "streamNo" to streamNo.toString()
            )
            var decryptedPath: String? = null
            val decryptAttempts = listOf(
                decryptPostData,
                decryptPostData + mapOf("fallback" to "true"),
                mapOf("encrypted" to encryptedResponse, "streamType" to if (streamType == "game") "24/7" else "game")
            )

            for (attempt in decryptAttempts) {
                try {
                    val response = app.post(decryptUrl, json = attempt, headers = mapOf("Content-Type" to "application/json"))
                    Log.d("StreamedExtractor", "Decrypt attempt with data: $attempt, code: ${response.code}, body: ${response.text}")
                    val decryptResponse = response.parsedSafe<Map<String, String>>()
                    decryptedPath = decryptResponse?.get("decrypted")
                    if (decryptedPath != null) break
                } catch (e: Exception) {
                    Log.e("StreamedExtractor", "Decrypt attempt failed: ${e.message}")
                }
            }

            if (decryptedPath == null) {
                Log.e("StreamedExtractor", "No valid decrypted path after all attempts")
                return false
            }
            Log.d("StreamedExtractor", "Decrypted path: $decryptedPath")

            // Construct proxy M3U8 URL
            val m3u8Url = "$baseUrl$decryptedPath"
            val encodedM3u8Url = URLEncoder.encode(m3u8Url, "UTF-8")
            val encodedCookies = URLEncoder.encode(cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }, "UTF-8")
            val segmentPrefix = if (streamType == "24/7") "bucket-44677-gjnru5ktoa" else "live-$matchId"
            val proxyM3u8Url = "$proxyServerUrl/playlist.m3u8?url=$encodedM3u8Url&cookies=$encodedCookies&streamType=$streamType&matchId=$matchId&source=$source&streamNo=$streamNo&segmentPrefix=$segmentPrefix"
            val m3u8Headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
                "Referer" to embedReferer,
                "X-Stream-Type" to streamType,
                "X-Stream-ID" to "$matchId-$source-$streamNo"
            )
            Log.d("StreamedExtractor", "Proxy M3U8 URL: $proxyM3u8Url")

            // Fetch M3U8 from proxy
            val m3u8Response = try {
                val response = app.get(proxyM3u8Url, headers = m3u8Headers, timeout = 15)
                Log.d("StreamedExtractor", "Proxy M3U8 fetch: Code ${response.code}, Content: ${response.text.take(100)}")
                if (response.code == 200 && response.text.startsWith("#EXTM3U")) response else throw Exception("Invalid M3U8: ${response.text.take(100)}")
            } catch (e: Exception) {
                Log.e("StreamedExtractor", "Proxy M3U8 fetch failed: ${e.message}")
                return false
            }

            val m3u8Content = m3u8Response.text
            Log.d("StreamedExtractor", "Proxy M3U8:\n$m3u8Content")

            // Pass to Cloudstream
            callback.invoke(
                newExtractorLink(
                    source = "Streamed",
                    name = "$source Stream $streamNo",
                    url = proxyM3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = embedReferer
                    this.quality = Qualities.Unknown.value
                    this.headers = m3u8Headers
                }
            )
            Log.d("StreamedExtractor", "Proxy M3U8 URL added: $proxyM3u8Url")
            return true
        }
    }
}