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
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed"
    override var supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

    private val sources = listOf("alpha", "bravo", "charlie", "delta")
    private val maxStreams = 3
    private val logFile = File("/sdcard/streamed_provider.log") // Adjust path for your device

    private fun logToFile(message: String) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
            FileWriter(logFile, true).use { writer ->
                writer.append("[$timestamp] $message\n")
            }
        } catch (e: Exception) {
            Log.e("StreamedProvider", "Failed to write to log file: ${e.message}")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val logMessage = "Fetching main page: ${request.data}"
        Log.d("StreamedProvider", logMessage)
        logToFile(logMessage)
        try {
            val rawList = app.get(request.data).text
            val logResponse = "Main page response for ${request.data}: ${rawList.take(200)}..."
            Log.d("StreamedProvider", logResponse)
            logToFile(logResponse)
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

            val logSuccess = "Main page loaded with ${list.size} items"
            Log.d("StreamedProvider", logSuccess)
            logToFile(logSuccess)
            return newHomePageResponse(
                list = listOf(HomePageList(request.name, list, isHorizontalImages = true)),
                hasNext = false
            )
        } catch (e: Exception) {
            val logError = "Error fetching main page: ${e.message}"
            Log.e("StreamedProvider", logError)
            logToFile(logError)
            throw e
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val logMessage = "Loading URL: $url"
        Log.d("StreamedProvider", logMessage)
        logToFile(logMessage)
        try {
            val matchId = url.substringAfterLast("/")
            val logId = "Extracted matchId: $matchId"
            Log.d("StreamedProvider", logId)
            logToFile(logId)
            val title = matchId.replace("-", " ")
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                .replace(Regex("-\\d+$"), "")
            val posterUrl = "$mainUrl/api/images/poster/$matchId.webp"
            val logResponse = "Load response: title=$title, poster=$posterUrl"
            Log.d("StreamedProvider", logResponse)
            logToFile(logResponse)
            return newLiveStreamLoadResponse(
                name = title,
                url = url,
                dataUrl = url
            ) {
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            val logError = "Error loading URL: ${e.message}"
            Log.e("StreamedProvider", logError)
            logToFile(logError)
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
        val logMessage = "Loading links for matchId: $matchId"
        Log.d("StreamedProvider", logMessage)
        logToFile(logMessage)
        val extractor = StreamedExtractor()
        var success = false

        sources.forEach { source ->
            for (streamNo in 1..maxStreams) {
                val streamUrl = "$mainUrl/watch/$matchId/$source/$streamNo"
                val logAttempt = "Attempting stream: matchId=$matchId, source=$source, streamNo=$streamNo, url=$streamUrl"
                Log.d("StreamedProvider", logAttempt)
                logToFile(logAttempt)
                try {
                    if (extractor.getUrl(streamUrl, matchId, source, streamNo, subtitleCallback, callback)) {
                        val logSuccess = "Success for stream: matchId=$matchId, source=$source, streamNo=$streamNo"
                        Log.d("StreamedProvider", logSuccess)
                        logToFile(logSuccess)
                        success = true
                    } else {
                        val logFail = "Failed for stream: matchId=$matchId, source=$source, streamNo=$streamNo"
                        Log.w("StreamedProvider", logFail)
                        logToFile(logFail)
                    }
                } catch (e: Exception) {
                    val logError = "Exception in getUrl for $streamUrl: ${e.message}"
                    Log.e("StreamedProvider", logError)
                    logToFile(logError)
                }
            }
        }
        val logResult = "loadLinks result: $success"
        Log.d("StreamedProvider", logResult)
        logToFile(logResult)
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
    private val logFile = File("/sdcard/streamed_extractor.log") // Adjust path for your device

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

    private fun logToFile(message: String) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
            FileWriter(logFile, true).use { writer ->
                writer.append("[$timestamp] $message\n")
            }
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Failed to write to log file: ${e.message}")
        }
    }

    suspend fun getCookies(): String? {
        val logMessage = "Fetching cookies from $cookieUrl"
        Log.d("StreamedExtractor", logMessage)
        logToFile(logMessage)
        repeat(3) { attempt ->
            try {
                val sessionId = UUID.randomUUID().toString()
                val postData = mapOf(
                    "event" to "pageview",
                    "referrer" to "https://fishy.streamed.su",
                    "url" to "https://fishy.streamed.su/",
                    "domain" to "fishy.streamed.su",
                    "screen" to "1080x1920",
                    "userAgent" to baseHeaders["User-Agent"],
                    "sessionId" to sessionId,
                    "timestamp" to System.currentTimeMillis().toString(),
                    "clientId" to "cloudstream3-$sessionId"
                )
                val headers = baseHeaders + mapOf(
                    "Host" to "fishy.streamed.su",
                    "Cookie" to "_ga=GA1.1.1234567890.1234567890; _dd_s=logs=1"
                )
                val response = app.post(
                    url = cookieUrl,
                    headers = headers,
                    json = postData,
                    timeout = 15
                )
                val logResponse = "Cookie response code: ${response.code}, headers: ${response.headers}, body: ${response.text}"
                Log.d("StreamedExtractor", logResponse)
                logToFile(logResponse)
                if (response.code != 200) {
                    val logError = "Cookie request failed with code: ${response.code} (attempt ${attempt + 1})"
                    Log.e("StreamedExtractor", logError)
                    logToFile(logError)
                    return@repeat
                }
                val cookies = response.headers["Set-Cookie"] ?: run {
                    val logError = "No cookies received in response (attempt ${attempt + 1})"
                    Log.e("StreamedExtractor", logError)
                    logToFile(logError)
                    return@repeat
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
                    val logError = "No required cookies found: $cookieMap (attempt ${attempt + 1})"
                    Log.e("StreamedExtractor", logError)
                    logToFile(logError)
                    return@repeat
                }
                val logSuccess = "Formatted cookies: $formattedCookies"
                Log.d("StreamedExtractor", logSuccess)
                logToFile(logSuccess)
                return formattedCookies
            } catch (e: Exception) {
                val logError = "Error fetching cookies: ${e.message} (attempt ${attempt + 1})"
                Log.e("StreamedExtractor", logError)
                logToFile(logError)
            }
        }
        val logFail = "All attempts to fetch cookies failed"
        Log.e("StreamedExtractor", logFail)
        logToFile(logFail)
        return null
    }

    suspend fun getUrl(
        streamUrl: String,
        matchId: String,
        source: String,
        streamNo: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val logMessage = "Starting extraction for: $streamUrl, matchId=$matchId, source=$source, streamNo=$streamNo"
        Log.d("StreamedExtractor", logMessage)
        logToFile(logMessage)

        // Fetch cookies
        val cookies = getCookies() ?: run {
            val logError = "No cookies retrieved, cannot proceed"
            Log.e("StreamedExtractor", logError)
            logToFile(logError)
            return false
        }
        val logCookies = "Using cookies: $cookies"
        Log.d("StreamedExtractor", logCookies)
        logToFile(logCookies)

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
        val logFetch = "Fetching with data: $postData and headers: $fetchHeaders"
        Log.d("StreamedExtractor", logFetch)
        logToFile(logFetch)

        val encryptedResponse = try {
            val response = app.post(fetchUrl, headers = fetchHeaders, json = postData, timeout = 15)
            val logResponse = "Fetch response code: ${response.code}, body: ${response.text}"
            Log.d("StreamedExtractor", logResponse)
            logToFile(logResponse)
            if (response.code != 200) {
                val logError = "Fetch failed with code: ${response.code}"
                Log.e("StreamedExtractor", logError)
                logToFile(logError)
                return false
            }
            response.text
        } catch (e: Exception) {
            val logError = "Fetch failed: ${e.message}"
            Log.e("StreamedExtractor", logError)
            logToFile(logError)
            return false
        }

        // Decrypt using Deno
        val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        val logDecrypt = "Decrypting with data: $decryptPostData"
        Log.d("StreamedExtractor", logDecrypt)
        logToFile(logDecrypt)
        val decryptResponse = try {
            val response = app.post(decryptUrl, json = decryptPostData, headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json"
            ))
            val logResponse = "Decryption response code: ${response.code}, body: ${response.text}"
            Log.d("StreamedExtractor", logResponse)
            logToFile(logResponse)
            response.parsedSafe<Map<String, String>>()
        } catch (e: Exception) {
            val logError = "Decryption request failed: ${e.message}"
            Log.e("StreamedExtractor", logError)
            logToFile(logError)
            return false
        }
        val decryptedPath = decryptResponse?.get("decrypted") ?: run {
            val logError = "Decryption failed or no 'decrypted' key"
            Log.e("StreamedExtractor", logError)
            logToFile(logError)
            return false
        }
        val logPath = "Decrypted path: $decryptedPath"
        Log.d("StreamedExtractor", logPath)
        logToFile(logPath)

        // Construct M3U8 URL and proxy URL with cookies
        val m3u8Url = "https://rr.buytommy.top$decryptedPath"
        val logM3u8 = "Constructed M3U8 URL: $m3u8Url"
        Log.d("StreamedExtractor", logM3u8)
        logToFile(logM3u8)
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
                    val logLink = "ExtractorLink created with URL: $proxiedUrl, Headers: ${this.headers}"
                    Log.d("StreamedExtractor", logLink)
                    logToFile(logLink)
                }
            )
            val logSuccess = "Proxied M3U8 URL added: $proxiedUrl"
            Log.d("StreamedExtractor", logSuccess)
            logToFile(logSuccess)
            return true
        } catch (e: Exception) {
            val logError = "Failed to add proxied URL: ${e.message}"
            Log.e("StreamedExtractor", logError)
            logToFile(logError)
            return false
        }
    }
}