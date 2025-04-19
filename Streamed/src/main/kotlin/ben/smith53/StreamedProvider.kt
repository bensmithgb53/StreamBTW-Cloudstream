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
import java.util.UUID

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
        try {
            val rawList = app.get(request.data).text
            Log.d("StreamedProvider", "Main page response for ${request.data}: ${rawList.take(200)}...")
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
        } catch (e: Exception) {
            Log.e("StreamedProvider", "Error fetching main page: ${e.message}")
            throw e
        }
    }

    override suspend fun load(url: String): LoadResponse {
        try {
            val matchId = url.substringAfterLast("/")
            Log.d("StreamedProvider", "Loading URL: $url, matchId: $matchId")
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
        } catch (e: Exception) {
            Log.e("StreamedProvider", "Error loading URL: ${e.message}")
            throw e
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val matchId = data.substringAfterLast("/")
        Log.d("StreamedProvider", "Loading links for matchId: $matchId")
        val extractor = StreamedExtractor()
        var success = false

        sources.forEach { source ->
            for (streamNo in 1..maxStreams) {
                val streamUrl = "$mainUrl/watch/$matchId/$source/$streamNo"
                Log.d("StreamedProvider", "Attempting stream: matchId=$matchId, source=$source, streamNo=$streamNo, url=$streamUrl")
                try {
                    if (extractor.getUrl(streamUrl, matchId, source, streamNo, subtitleCallback, callback)) {
                        Log.d("StreamedProvider", "Success for stream: matchId=$matchId, source=$source, streamNo=$streamNo")
                        success = true
                    } else {
                        Log.w("StreamedProvider", "Failed for stream: matchId=$matchId, source=$source, streamNo=$streamNo")
                    }
                } catch (e: Exception) {
                    Log.e("StreamedProvider", "Exception in getUrl for $streamUrl: ${e.message}")
                }
            }
        }
        Log.d("StreamedProvider", "loadLinks result: $success")
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
    private val proxyUrl = "http://localhost:8000/playlist.m3u8"
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Referer" to "https://fishy.streamed.su/",
        "Origin" to "https://fishy.streamed.su",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Encoding" to "gzip, deflate, br",
        "Accept-Language" to "en-GB,en-US;q=0.9,en;q=0.8",
        "Sec-Ch-Ua" to "\"Not A(Brand\";v=\"8\", \"Chromium\";v=\"132\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin",
        "Content-Type" to "application/json",
        "X-Requested-With" to "XMLHttpRequest",
        "Connection" to "keep-alive"
    )

    suspend fun getCookies(): String? {
        Log.d("StreamedExtractor", "Fetching cookies from $cookieUrl")
        try {
            // Generate a unique session ID or use a static one for testing
            val sessionId = UUID.randomUUID().toString()
            val postData = mapOf(
                "event" to "pageview",
                "referrer" to "https://fishy.streamed.su",
                "url" to "https://fishy.streamed.su/",
                "domain" to "fishy.streamed.su",
                "screen" to "1080x1920",
                "userAgent" to baseHeaders["User-Agent"],
                "sessionId" to sessionId,
                "timestamp" to System.currentTimeMillis().toString()
            )
            val headers = baseHeaders + mapOf(
                "Host" to "fishy.streamed.su",
                "Cookie" to "_ga=GA1.1.1234567890.1234567890" // Placeholder for analytics cookie
            )
            val response = app.post(
                url = cookieUrl,
                headers = headers,
                json = postData,
                timeout = 15
            )
            Log.d("StreamedExtractor", "Cookie response code: ${response.code}, headers: ${response.headers}, body: ${response.text}")
            if (response.code != 200) {
                Log.e("StreamedExtractor", "Cookie request failed with code: ${response.code}")
                return null
            }
            val cookies = response.headers["Set-Cookie"] ?: return null.also {
                Log.e("StreamedExtractor", "No cookies received in response")
            }
            val cookieMap = mutableMapOf<String, String>()
            cookies.split(",").forEach { cookie ->
                val parts = cookie.split(";")[0].trim().split("=")
                if (parts.size == 2) {
                    val name = parts[0].trim().removePrefix("_")
                    val value = parts[1].trim()
                    cookieMap[name] = value
                }
            }
            val requiredCookies = listOf("ddg8_", "ddg10_", "ddg9_", "ddg1_")
            val formattedCookies = requiredCookies.mapNotNull { key ->
                cookieMap[key]?.let { "$key=$it" }
            }.joinToString("; ")
            if (formattedCookies.isEmpty()) {
                Log.e("StreamedExtractor", "No required cookies found: $cookieMap")
                return null
            }
            Log.d("StreamedExtractor", "Formatted cookies: $formattedCookies")
            return formattedCookies
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Error fetching cookies: ${e.message}")
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
        Log.d("StreamedExtractor", "Starting extraction for: $streamUrl, matchId=$matchId, source=$source, streamNo=$streamNo")

        // Fetch cookies
        val cookies = getCookies() ?: run {
            Log.e("StreamedExtractor", "No cookies retrieved, cannot proceed")
            return false
        }
        Log.d("StreamedExtractor", "Using cookies: $cookies")

        // POST to fetch encrypted string
        val postData = mapOf(
            "source" to source,
            "id" to matchId,
            "streamNo" to streamNo.toString()
        )
        val embedReferer = "https://embedstreams.top/embed/$source/$matchId/$streamNo"
        val fetchHeaders = baseHeaders + mapOf(
            "Referer" to streamUrl,
            "Cookie" to cookies,
            "Accept" to "application/json",
            "Origin" to "https://embedstreams.top",
            "Host" to "embedstreams.top"
        )
        Log.d("StreamedExtractor", "Fetching with data: $postData and headers: $fetchHeaders")

        val encryptedResponse = try {
            val response = app.post(fetchUrl, headers = fetchHeaders, json = postData, timeout = 15)
            Log.d("StreamedExtractor", "Fetch response code: ${response.code}, body: ${response.text}")
            if (response.code != 200) {
                Log.e("StreamedExtractor", "Fetch failed with code: ${response.code}")
                return false
            }
            response.text
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Fetch failed: ${e.message}")
            return false
        }

        // Decrypt using Deno
        val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        val decryptResponse = try {
            val response = app.post(decryptUrl, json = decryptPostData, headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json"
            ))
            Log.d("StreamedExtractor", "Decryption response code: ${response.code}, body: ${response.text}")
            response.parsedSafe<Map<String, String>>()
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Decryption request failed: ${e.message}")
            return false
        }
        val decryptedPath = decryptResponse?.get("decrypted") ?: return false.also {
            Log.e("StreamedExtractor", "Decryption failed or no 'decrypted' key")
        }
        Log.d("StreamedExtractor", "Decrypted path: $decryptedPath")

        // Construct M3U8 URL and proxy URL with cookies
        val m3u8Url = "https://rr.buytommy.top$decryptedPath"
        Log.d("StreamedExtractor", "Constructed M3U8 URL: $m3u8Url")
        val encodedM3u8Url = URLEncoder.encode(m3u8Url, "UTF-8")
        val encodedCookies = URLEncoder.encode(cookies, "UTF-8")
        val proxiedUrl = "$proxyUrl?url=$encodedM3u8Url&cookies=$encodedCookies"
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
                        "Range" to "bytes=0-"
                    )
                    Log.d("StreamedExtractor", "ExtractorLink created with URL: $proxiedUrl, Headers: ${this.headers}")
                }
            )
            Log.d("StreamedExtractor", "Proxied M3U8 URL added: $proxiedUrl")
            return true
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Failed to add proxied URL: ${e.message}")
            return false
        }
    }
}