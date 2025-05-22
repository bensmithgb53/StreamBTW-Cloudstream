package ben.smith53

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import android.util.Log
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.Locale
import kotlinx.coroutines.delay
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed"
    override var supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override val hasChromecastSupport = true

    private val sources = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")
    private val maxStreams = 4
    private val fetchUrl = "https://embedstreams.top/fetch"
    private val cookieUrl = "https://fishy.streamed.su/api/event"
    private val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
    private val defaultTimeout = 15L
    private val cfKiller = CloudflareKiller()
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Content-Type" to "application/json",
        "Accept" to "application/vnd.apple.mpegurl, */*",
        "Accept-Encoding" to "gzip, deflate, br",
        "Origin" to "https://embedstreams.top",
        "Sec-Fetch-Site" to "cross-site",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Dest" to "empty",
        "Accept-Language" to "en-GB,en-US;q=0.9,en;q=0.8"
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

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                return cfKiller.intercept(chain)
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get(request.data, headers = baseHeaders, interceptor = cfKiller, timeout = defaultTimeout)
        val listJson = parseJson<List<Match>>(response.text)

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
        val normalizedMatchId = matchId.replace(Regex("^\\d+-"), "")
        val title = normalizedMatchId
            .replace("-", " ")
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
        val normalizedMatchId = matchId.replace(Regex("^\\d+-"), "")
        val extractor = StreamedMediaExtractor()
        var success = false

        sources.forEach { source ->
            val streamInfos = try {
                val apiUrl = "$mainUrl/api/stream/$source/$normalizedMatchId"
                val response = app.get(apiUrl, headers = baseHeaders, interceptor = cfKiller, timeout = defaultTimeout)
                Log.d("StreamedProvider", "Raw API response for $source ($normalizedMatchId): ${response.text}")
                if (response.code == 200 && response.text.isNotBlank()) {
                    parseJson<List<StreamInfo>>(response.text).filter { it.embedUrl.isNotBlank() && it.id.isNotBlank() }
                } else {
                    Log.w("StreamedProvider", "Invalid response for $source ($normalizedMatchId): Code ${response.code}")
                    emptyList()
                }
            } catch (e: Exception) {
                Log.w("StreamedProvider", "No streams for $source ($normalizedMatchId): ${e.message}")
                emptyList()
            }

            if (streamInfos.isNotEmpty()) {
                streamInfos.forEach { stream ->
                    val streamId = stream.id
                    val streamNo = stream.streamNo
                    val language = stream.language ?: "Unknown"
                    val isHd = stream.hd ?: false
                    val streamUrl = "$mainUrl/watch/$matchId/$source/$streamNo"
                    Log.d("StreamedProvider", "Processing stream URL: $streamUrl (ID: $streamId, Language: $language, HD: $isHd)")
                    if (extractor.getUrl(streamUrl, streamId, source, streamNo, language, isHd, subtitleCallback, callback)) {
                        success = true
                    }
                }
            } else {
                for (streamNo in 1..maxStreams) {
                    val streamUrl = "$mainUrl/watch/$matchId/$source/$streamNo"
                    val pageResponse = try {
                        app.get(streamUrl, headers = baseHeaders, interceptor = cfKiller, timeout = defaultTimeout).text
                    } catch (e: Exception) {
                        Log.w("StreamedProvider", "Failed to fetch stream page $streamUrl: ${e.message}")
                        ""
                    }
                    val inferredLanguage = when {
                        pageResponse.contains("English", ignoreCase = true) -> "English"
                        pageResponse.contains("Spanish", ignoreCase = true) -> "Spanish"
                        pageResponse.contains("Polish", ignoreCase = true) -> "Polish"
                        else -> "Unknown"
                    }
                    val inferredHd = pageResponse.contains("1080p", ignoreCase = true) || pageResponse.contains("HD", ignoreCase = true)
                    Log.d("StreamedProvider", "Inferred language: $inferredLanguage, HD: $inferredHd for $streamUrl")
                    if (extractor.getUrl(streamUrl, matchId, source, streamNo, inferredLanguage, inferredHd, subtitleCallback, callback)) {
                        success = true
                    }
                }
            }
        }

        if (!success) {
            Log.e("StreamedProvider", "No links found for $matchId")
            throw ErrorLoadingException("No valid streams found for this event")
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

    data class StreamInfo(
        @JsonProperty("id") val id: String,
        @JsonProperty("streamNo") val streamNo: Int,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("hd") val hd: Boolean? = null,
        @JsonProperty("embedUrl") val embedUrl: String,
        @JsonProperty("source") val source: String
    )
}

class StreamedMediaExtractor {
    private val fetchUrl = "https://embedstreams.top/fetch"
    private val cookieUrl = "https://fishy.streamed.su/api/event"
    private val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
    private val defaultTimeout = 15L
    private val cfKiller = CloudflareKiller()
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Content-Type" to "application/json",
        "Accept" to "application/vnd.apple.mpegurl, */*",
        "Accept-Encoding" to "gzip, deflate, br",
        "Origin" to "https://embedstreams.top",
        "Sec-Fetch-Site" to "cross-site",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Dest" to "empty",
        "Accept-Language" to "en-GB,en-US;q=0.9,en;q=0.8"
    )
    private val fallbackDomains = listOf("p2-panel.streamed.su", "streamed.su")
    private val cookieCache = mutableMapOf<String, Pair<String, Long>>()

    suspend fun getUrl(
        streamUrl: String,
        streamId: String,
        source: String,
        streamNo: Int,
        language: String,
        isHd: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StreamedMediaExtractor", "Starting extraction for: $streamUrl (ID: $streamId)")

        val streamResponse = try {
            app.get(streamUrl, headers = baseHeaders, interceptor = cfKiller, timeout = defaultTimeout)
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Stream page fetch failed for $source/$streamNo: ${e.message}")
            return false
        }
        val streamCookies = streamResponse.cookies
        Log.d("StreamedMediaExtractor", "Stream cookies for $source/$streamNo: $streamCookies")

        val eventCookies = fetchEventCookies(streamUrl, streamUrl)
        Log.d("StreamedMediaExtractor", "Event cookies for $source/$streamNo: $eventCookies")

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
            Log.e("StreamedMediaExtractor", "No cookies obtained for $source/$streamNo")
            return false
        }
        Log.d("StreamedMediaExtractor", "Combined cookies for $source/$streamNo: $combinedCookies")

        val postData = mapOf(
            "source" to source,
            "id" to streamId,
            "streamNo" to streamNo.toString()
        )
        val embedReferer = "https://embedstreams.top/embed/$source/$streamId/$streamNo"
        val fetchHeaders = baseHeaders + mapOf(
            "Referer" to streamUrl,
            "Cookie" to combinedCookies
        )
        Log.d("StreamedMediaExtractor", "Fetching with data: $postData and headers: $fetchHeaders")

        val encryptedResponse = try {
            val response = app.post(fetchUrl, headers = fetchHeaders, json = postData, interceptor = cfKiller, timeout = defaultTimeout)
            Log.d("StreamedMediaExtractor", "Fetch response code for $source/$streamNo: ${response.code}")
            if (response.code != 200) {
                Log.e("StreamedMediaExtractor", "Fetch failed for $source/$streamNo with code: ${response.code}")
                return false
            }
            response.text.takeIf { it.isNotBlank() } ?: return false.also {
                Log.e("StreamedMediaExtractor", "Empty encrypted response for $source/$streamNo")
            }
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Fetch failed for $source/$streamNo: ${e.message}")
            return false
        }
        Log.d("StreamedMediaExtractor", "Encrypted response for $source/$streamNo: $encryptedResponse")

        val decryptedPath = decryptWithRetry(encryptedResponse) ?: return false.also {
            Log.e("StreamedMediaExtractor", "Decryption failed for $source/$streamNo")
        }
        Log.d("StreamedMediaExtractor", "Decrypted path for $source/$streamNo: $decryptedPath")

        val m3u8Url = "https://rr.buytommy.top$decryptedPath"
        val keyUrl = "https://rr.buytommy.top/$source/key/$source-$streamId-$streamNo/f.key"
        val m3u8Headers = baseHeaders + mapOf(
            "Referer" to embedReferer,
            "Cookie" to combinedCookies
        )

        for (domain in listOf("rr.buytommy.top") + fallbackDomains) {
            try {
                val testUrl = m3u8Url.replace("rr.buytommy.top", domain)
                val testResponse = app.get(testUrl, headers = m3u8Headers, interceptor = cfKiller, timeout = defaultTimeout)
                if (testResponse.code == 200 && testResponse.text.contains("#EXTM3U")) {
                    val keyAvailable = try {
                        val keyResponse = app.get(keyUrl, headers = m3u8Headers, interceptor = cfKiller, timeout = defaultTimeout)
                        keyResponse.code == 200 && keyResponse.text.isNotBlank()
                    } catch (e: Exception) {
                        Log.w("StreamedMediaExtractor", "Key fetch failed for $keyUrl: ${e.message}")
                        false
                    }
                    if (keyAvailable) {
                        callback.invoke(
                            newExtractorLink(
                                source = "Streamed",
                                name = "$source Stream $streamNo ($language${if (isHd) ", HD" else ""})",
                                url = testUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = embedReferer
                                this.quality = if (isHd) Qualities.P1080.value else Qualities.Unknown.value
                                this.headers = m3u8Headers
                                this.extractorData = keyUrl
                            }
                        )
                        Log.d("StreamedMediaExtractor", "Valid M3U8 URL added for $source/$streamNo: $testUrl")
                        return true
                    } else {
                        Log.w("StreamedMediaExtractor", "Invalid or missing key for $source/$streamNo")
                    }
                } else {
                    Log.w("StreamedMediaExtractor", "Invalid M3U8 for $domain: Code ${testResponse.code}")
                }
            } catch (e: Exception) {
                Log.e("StreamedMediaExtractor", "M3U8 test failed for $domain: ${e.message}")
            }
        }

        callback.invoke(
            newExtractorLink(
                source = "Streamed",
                name = "$source Stream $streamNo ($language${if (isHd) ", HD" else ""})",
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = embedReferer
                this.quality = if (isHd) Qualities.P1080.value else Qualities.Unknown.value
                this.headers = m3u8Headers
                this.extractorData = keyUrl
            }
        )
        Log.d("StreamedMediaExtractor", "M3U8 test failed but added anyway for $source/$streamNo: $m3u8Url")
        return true
    }

    private suspend fun decryptWithRetry(encrypted: String, maxRetries: Int = 3): String? {
        repeat(maxRetries) { attempt ->
            try {
                val decryptPostData = mapOf("encrypted" to encrypted)
                val response = app.post(
                    decryptUrl,
                    json = decryptPostData,
                    headers = mapOf("Content-Type" to "application/json"),
                    interceptor = cfKiller,
                    timeout = defaultTimeout
                )
                val decrypted = response.parsedSafe<Map<String, String>>()?.get("decrypted")
                if (!decrypted.isNullOrBlank()) {
                    Log.d("StreamedMediaExtractor", "Decryption successful on attempt ${attempt + 1}")
                    return decrypted
                }
            } catch (e: Exception) {
                Log.w("StreamedMediaExtractor", "Decryption attempt ${attempt + 1} failed: ${e.message}")
            }
            if (attempt < maxRetries - 1) delay(1000L * (1 shl attempt))
        }
        Log.e("StreamedMediaExtractor", "All decryption attempts failed")
        return null
    }

    private suspend fun fetchEventCookies(pageUrl: String, referrer: String): String {
        cookieCache[pageUrl]?.let { (cookies, timestamp) ->
            if (System.currentTimeMillis() - timestamp < 3600_000) {
                try {
                    val testResponse = app.get(pageUrl, headers = baseHeaders + mapOf("Cookie" to cookies), interceptor = cfKiller, timeout = 5)
                    if (testResponse.code == 200) return cookies
                    Log.d("StreamedMediaExtractor", "Cached cookies invalid for $pageUrl")
                } catch (e: Exception) {
                    Log.w("StreamedMediaExtractor", "Cached cookie test failed: ${e.message}")
                }
            }
        }

        val payload = """{"n":"pageview","u":"$pageUrl","d":"streamed.su","r":"$referrer"}"""
        try {
            val response = app.post(
                cookieUrl,
                data = mapOf(),
                headers = mapOf("Content-Type" to "text/plain"),
                requestBody = payload.toRequestBody("text/plain".toMediaType()),
                interceptor = cfKiller,
                timeout = defaultTimeout
            )
            val cookies = response.headers.filter { it.first == "Set-Cookie" }
                .map { it.second.split(";")[0] }
            val formattedCookies = listOf("_ddg8_", "_ddg10_", "_ddg9_", "_ddg1_", "cf_clearance")
                .mapNotNull { key -> cookies.find { it.startsWith(key) } }
                .joinToString("; ")
            if (formattedCookies.isNotEmpty()) {
                cookieCache[pageUrl] = formattedCookies to System.currentTimeMillis()
                Log.d("StreamedMediaExtractor", "Fetched new cookies for $pageUrl: $formattedCookies")
                return formattedCookies
            }
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Failed to fetch event cookies: ${e.message}")
        }
        return ""
    }
}