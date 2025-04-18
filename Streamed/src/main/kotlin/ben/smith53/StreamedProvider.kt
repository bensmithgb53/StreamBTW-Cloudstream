package ben.smith53

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import android.util.Log
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.regex.Pattern

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
    private val baseM3u8Url = "https://rr.buytommy.top"
    private val segmentBaseUrl = "https://p2-panel.streamed.su"

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
        "Connection" to "keep-alive"
    )

    // Custom OkHttp client with interceptor
    private val client = OkHttpClient.Builder()
        .addInterceptor(M3U8Interceptor())
        .build()

    suspend fun getCookies(): String? {
        // Hardcode cookies for testing
        return "ddg8_=c16XuMgCExmUPpzo; ddg10_=1744925426; ddg9_=82.46.16.114; ddg1_=dl3M1u9zODCU65fvl7YM"
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
        val decryptedPath = decryptResponse?.get("decrypted") ?: return false.also {
            Log.e("StreamedExtractor", "Decryption failed or no 'decrypted' key")
        }
        Log.d("StreamedExtractor", "Decrypted path: $decryptedPath")

        // Fetch and rewrite M3U8
        val m3u8Url = "https://rr.buytommy.top$decryptedPath"
        val request = Request.Builder()
            .url(m3u8Url)
            .addHeader("Cookie", cookies)
            .addHeader("User-Agent", baseHeaders["User-Agent"]!!)
            .addHeader("Referer", baseHeaders["Referer"]!!)
            .addHeader("Accept", "application/vnd.apple.mpegurl")
            .build()
        val m3u8Response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Failed to fetch M3U8: ${e.message}")
            return false
        }
        if (!m3u8Response.isSuccessful) {
            Log.e("StreamedExtractor", "M3U8 fetch failed: ${m3u8Response.code}")
            return false
        }
        val m3u8Content = m3u8Response.body?.string() ?: return false
        Log.d("StreamedExtractor", "Fetched M3U8:\n$m3u8Content")

        // Save rewritten M3U8 to a temporary file
        val tempFile = File.createTempFile("streamed", ".m3u8")
        tempFile.writeText(m3u8Content) // Interceptor rewrites content
        val tempM3u8Url = tempFile.toURI().toString()

        // Create ExtractorLink
        try {
            val extractorLink = newExtractorLink(
                source = "Streamed",
                name = "$source Stream $streamNo",
                url = tempM3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = embedReferer
                this.quality = Qualities.Unknown.value
                this.headers = baseHeaders + mapOf(
                    "Cookie" to cookies,
                    "Accept" to "application/vnd.apple.mpegurl,video/mp2t",
                    "Range" to "bytes=0-"
                )
                Log.d("StreamedExtractor", "ExtractorLink created with URL: $tempM3u8Url, Headers: ${this.headers}")
            }
            callback.invoke(extractorLink)
            Log.d("StreamedExtractor", "M3U8 URL added: $tempM3u8Url")
            return true
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Failed to add M3U8 URL: ${e.message}")
            tempFile.delete()
            return false
        } finally {
            tempFile.deleteOnExit()
        }
    }
}

// OkHttp Interceptor to rewrite M3U8 and fix Content-Type
class M3U8Interceptor : Interceptor {
    private val segmentPattern = Pattern.compile("https://corsproxy\\.io/\\?url=https://p2-panel\\.streamed\\.su/[^\\s]+\\.js")
    private val keyPattern = Pattern.compile("/alpha/key/[^\"]+")

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        Log.d("M3U8Interceptor", "Intercepting request: $url")

        // Add headers to bypass CORS
        val newRequest = request.newBuilder()
            .addHeader("Origin", "https://embedstreams.top")
            .addHeader("Referer", "https://embedstreams.top/")
            .build()

        val response = chain.proceed(newRequest)
        val contentType = response.header("Content-Type") ?: "application/octet-stream"

        // Handle M3U8 responses
        if (url.endsWith(".m3u8") && contentType.contains("application/vnd.apple.mpegurl")) {
            val body = response.body?.string() ?: return response
            Log.d("M3U8Interceptor", "Original M3U8:\n$body")

            // Rewrite M3U8
            val rewrittenBody = rewriteM3u8(body)
            Log.d("M3U8Interceptor", "Rewritten M3U8:\n$rewrittenBody")

            // Return modified response
            return response.newBuilder()
                .body(rewrittenBody.toResponseBody("application/vnd.apple.mpegurl".toMediaType()))
                .build()
        }

        // Fix Content-Type for segments
        if (url.endsWith(".ts") || url.contains("p2-panel.streamed.su")) {
            Log.d("M3U8Interceptor", "Fixing Content-Type for segment: $url")
            return response.newBuilder()
                .header("Content-Type", "video/mp2t")
                .build()
        }

        return response
    }

    private fun rewriteM3u8(m3u8: String): String {
        val lines = m3u8.split("\n")
        val rewrittenLines = lines.map { line ->
            var modifiedLine = line

            // Rewrite key URLs
            if (line.startsWith("#EXT-X-KEY")) {
                val matcher = keyPattern.matcher(line)
                if (matcher.find()) {
                    val keyUrl = matcher.group()
                    val newKeyUrl = "https://rr.buytommy.top$keyUrl"
                    modifiedLine = line.replace(keyUrl, newKeyUrl)
                    Log.d("M3U8Interceptor", "Rewrote key: $keyUrl -> $newKeyUrl")
                }
            }

            // Rewrite segment URLs
            val matcher = segmentPattern.matcher(line)
            if (matcher.find()) {
                val segmentUrl = matcher.group()
                val newSegmentUrl = segmentUrl.replace("https://corsproxy.io/?url=", "")
                    .replace(".js", ".ts")
                modifiedLine = newSegmentUrl
                Log.d("M3U8Interceptor", "Rewrote segment: $segmentUrl -> $newSegmentUrl")
            }

            modifiedLine
        }
        return rewrittenLines.joinToString("\n")
    }
}