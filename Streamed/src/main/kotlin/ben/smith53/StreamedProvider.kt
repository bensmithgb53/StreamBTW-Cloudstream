package ben.smith53

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import android.util.Log
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import java.util.Locale

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed"
    override var supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

    private val sources = listOf("alpha", "bravo", "charlie", "delta")
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
        Log.d("StreamedProvider", "Fetching main page: ${request.data}")
        try {
            val rawList = app.get(request.data).text
            val listJson = parseJson<List<Match>>(rawList)
            Log.d("StreamedProvider", "Parsed ${listJson.size} matches")

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
            Log.d("StreamedProvider", "Returning ${list.size} filtered matches")

            return newHomePageResponse(
                list = listOf(HomePageList(request.name, list, isHorizontalImages = true)),
                hasNext = false
            )
        } catch (e: Exception) {
            Log.e("StreamedProvider", "Main page fetch failed: ${e.stackTraceToString()}")
            throw e
        }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d("StreamedProvider", "Loading URL: $url")
        try {
            val matchId = url.substringAfterLast("/")
            val title = matchId.replace("-", " ")
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                .replace(Regex("-\\d+$"), "")
            val posterUrl = "$mainUrl/api/images/poster/$matchId.webp"
            Log.d("StreamedProvider", "Loaded title: $title, poster: $posterUrl")
            return newLiveStreamLoadResponse(
                name = title,
                url = url,
                dataUrl = url
            ) {
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            Log.e("StreamedProvider", "Load failed: ${e.stackTraceToString()}")
            throw e
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StreamedProvider", "Starting loadLinks for data: $data")
        try {
            val matchId = data.substringAfterLast("/")
            Log.d("StreamedProvider", "Match ID: $matchId")
            val extractor = StreamedExtractor()
            var success = false

            sources.forEach { source ->
                for (streamNo in 1..maxStreams) {
                    val streamUrl = "$mainUrl/watch/$matchId/$source/$streamNo"
                    Log.d("StreamedProvider", "Processing stream URL: $streamUrl")
                    try {
                        if (extractor.getUrl(streamUrl, matchId, source, streamNo, subtitleCallback, callback)) {
                            success = true
                            Log.d("StreamedProvider", "Successfully processed stream: $streamUrl")
                        } else {
                            Log.w("StreamedProvider", "Failed to process stream: $streamUrl")
                        }
                    } catch (e: Exception) {
                        Log.e("StreamedProvider", "Error processing stream $streamUrl: ${e.stackTraceToString()}")
                    }
                }
            }
            Log.d("StreamedProvider", "loadLinks completed, success: $success")
            return success
        } catch (e: Exception) {
            Log.e("StreamedProvider", "loadLinks failed: ${e.stackTraceToString()}")
            return false
        }
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
    private val proxyUrl = "https://mersin-proxy.onrender.com/playlist.m3u8"
    private val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Referer" to "https://embedstreams.top/",
        "Origin" to "https://embedstreams.top",
        "Accept" to "*/*",
        "Accept-Encoding" to "identity",
        "Accept-Language" to "en-GB,en-US;q=0.9,en;q=0.8",
        "Sec-Ch-Ua" to "\"Not A(Brand\";v=\"8\", \"Chromium\";v=\"132\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
        "Content-Type" to "application/json",
        "X-Requested-With" to "XMLHttpRequest",
        "Connection" to "keep-alive",
        "X-Forwarded-For" to "127.0.0.1"
    )

    suspend fun getCookies(): String? {
        Log.d("StreamedExtractor", "Step 1: Attempting to fetch cookies from: $cookieUrl")
        try {
            val response = app.post(cookieUrl, json = mapOf("event" to "pageview"), headers = baseHeaders, timeout = 15)
            Log.d("StreamedExtractor", "Cookie response code: ${response.code}, headers: ${response.headers}")
            val cookies = response.headers["set-cookie"]?.split(",")?.joinToString("; ") {
                it.split(";")[0].trim()
            }
            if (cookies.isNullOrEmpty()) {
                Log.w("StreamedExtractor", "No cookies received, using hardcoded fallback")
                return "ddg8_=c16XuMgCExmUPpzo; ddg10_=1744925426; ddg9_=82.46.16.114; ddg1_=dl3M1u9zODCU65fvl7YM"
            }
            Log.d("StreamedExtractor", "Fetched cookies: $cookies")
            return cookies
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Cookie fetch failed: ${e.stackTraceToString()}")
            return "ddg8_=c16XuMgCExmUPpzo; ddg10_=1744925426; ddg9_=82.46.16.114; ddg1_=dl3M1u9zODCU65fvl7YM"
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
        Log.d("StreamedExtractor", "Starting extraction for: $streamUrl, matchId: $matchId, source: $source, streamNo: $streamNo")
        
        // Step 1: Fetch cookies
        Log.d("StreamedExtractor", "Step 1: Fetching cookies")
        val cookies = getCookies() ?: run {
            Log.e("StreamedExtractor", "Failed to retrieve cookies")
            return false
        }
        Log.d("StreamedExtractor", "Cookies: $cookies")

        // Step 2: POST to fetch encrypted string
        Log.d("StreamedExtractor", "Step 2: Fetching encrypted string")
        val postData = mapOf(
            "source" to source,
            "id" to matchId,
            "streamNo" to streamNo.toString()
        )
        val embedReferer = "https://embedstreams.top/embed/$source/$matchId/$streamNo"
        val fetchHeaders = baseHeaders + mapOf(
            "Referer" to streamUrl,
            "Cookie" to cookies,
            "Accept" to "application/json"
        )
        Log.d("StreamedExtractor", "Fetching with data: $postData and headers: $fetchHeaders")

        val encryptedResponse = try {
            val response = app.post(fetchUrl, headers = fetchHeaders, json = postData, timeout = 30)
            Log.d("StreamedExtractor", "Fetch response code: ${response.code}, body: ${response.text}")
            if (response.code != 200) {
                Log.e("StreamedExtractor", "Fetch failed with code: ${response.code}, message: ${response.message}")
                return false
            }
            response.text
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Fetch failed: ${e.stackTraceToString()}")
            return false
        }
        if (encryptedResponse.isEmpty()) {
            Log.e("StreamedExtractor", "Empty encrypted response")
            return false
        }
        Log.d("StreamedExtractor", "Encrypted response: $encryptedResponse")

        // Step 3: Decrypt using Deno
        Log.d("StreamedExtractor", "Step 3: Decrypting response")
        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        val decryptResponse = try {
            val response = app.post(decryptUrl, json = decryptPostData, headers = mapOf("Content-Type" to "application/json"), timeout = 30)
            Log.d("StreamedExtractor", "Decrypt response code: ${response.code}, body: ${response.text}")
            if (response.code != 200 || response.text.isEmpty()) {
                Log.e("StreamedExtractor", "Invalid decryption response: code=${response.code}, body=${response.text}")
                return false
            }
            response.parsedSafe<Map<String, String>>()
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Decryption request failed: ${e.stackTraceToString()}")
            return false
        }
        val decryptedPath = decryptResponse?.get("decrypted") ?: run {
            Log.e("StreamedExtractor", "Decryption failed or no 'decrypted' key: $decryptResponse")
            return false
        }
        Log.d("StreamedExtractor", "Decrypted path: $decryptedPath")

        // Step 4: Construct and invoke proxy URL
        Log.d("StreamedExtractor", "Step 4: Constructing proxy URL")
        val m3u8Url = "https://rr.buytommy.top$decryptedPath"
        val encodedM3u8Url = URLEncoder.encode(m3u8Url, "UTF-8")
        val encodedCookies = URLEncoder.encode(cookies, "UTF-8")
        val proxiedUrl = "$proxyUrl?url=$encodedM3u8Url&cookies=$encodedCookies"
        Log.d("StreamedExtractor", "Proxied URL: $proxiedUrl")

        try {
            callback.invoke(
                newExtractorLink(
                    source = "Streamed",
                    name = "$source Stream $streamNo",
                    url = proxiedUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = embedReferer
                    this.quality = Qualities.Unknown.value
                    this.headers = baseHeaders + mapOf(
                        "Cookie" to cookies,
                        "Accept" to "application/vnd.apple.mpegurl,video/mp2t",
                        "Range" to "bytes=0-",
                        "Origin" to "https://embedstreams.top",
                        "Access-Control-Request-Method" to "GET"
                    )
                    Log.d("StreamedExtractor", "ExtractorLink created with URL: $proxiedUrl, Headers: ${this.headers}")
                }
            )
            Log.d("StreamedExtractor", "Proxied M3U8 URL added: $proxiedUrl")
            return true
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Failed to add proxied URL: ${e.stackTraceToString()}")
            return false
        }
    }
}