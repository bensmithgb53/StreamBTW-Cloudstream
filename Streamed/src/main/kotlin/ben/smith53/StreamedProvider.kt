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
import org.jsoup.Jsoup
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed"
    override var supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

    private val sources = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")
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

class StreamedMediaExtractor {
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        "Accept" to "*/*",
        "Accept-Encoding" to "gzip, deflate, br",
        "Accept-Language" to "en-US,en;q=0.9",
        "Content-Type" to "application/json"
    )
    private val cookieCache = ConcurrentHashMap<String, String>()
    private val endpointCache = ConcurrentHashMap<String, String>()

    suspend fun getUrl(
        streamUrl: String,
        matchId: String,
        source: String,
        streamNo: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StreamedMediaExtractor", "Starting extraction for: $streamUrl")

        // Discover endpoints dynamically
        val endpoints = discoverEndpoints(streamUrl) ?: return false.also {
            Log.e("StreamedMediaExtractor", "Failed to discover endpoints for $streamUrl")
        }
        val (fetchUrl, cookieUrl, decryptUrl, streamDomains) = endpoints

        // Fetch stream page cookies
        val streamResponse = try {
            app.get(streamUrl, headers = baseHeaders, timeout = 15)
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Stream page fetch failed: ${e.message}")
            return false
        }
        val streamCookies = streamResponse.cookies
        Log.d("StreamedMediaExtractor", "Stream cookies: $streamCookies")

        // Fetch event cookies
        val eventCookies = fetchEventCookies(streamUrl, streamUrl, cookieUrl)
        Log.d("StreamedMediaExtractor", "Event cookies: $eventCookies")

        // Combine cookies
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

        // POST to fetch encrypted string
        val postData = mapOf(
            "source" to source,
            "id" to matchId,
            "streamNo" to streamNo.toString()
        )
        val embedReferer = streamUrl.replace("streamed.su/watch", "${streamDomains.firstOrNull() ?: "embedstreams.top"}/embed")
        val fetchHeaders = baseHeaders + mapOf(
            "Referer" to streamUrl,
            "Cookie" to combinedCookies
        )
        Log.d("StreamedMediaExtractor", "Fetching with data: $postData and headers: $fetchHeaders")

        val encryptedResponse = try {
            val response = app.post(fetchUrl, headers = fetchHeaders, json = postData, timeout = 15)
            Log.d("StreamedMediaExtractor", "Fetch response code: ${response.code}")
            response.text
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Fetch failed: ${e.message}")
            return false
        }
        Log.d("StreamedMediaExtractor", "Encrypted response: $encryptedResponse")

        // Decrypt using discovered endpoint
        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        val decryptResponse = try {
            app.post(decryptUrl, json = decryptPostData, headers = mapOf("Content-Type" to "application/json"))
                .parsedSafe<Map<String, String>>()
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Decryption request failed: ${e.message}")
            return false
        }
        val decryptedPath = decryptResponse?.get("decrypted") ?: return false.also {
            Log.e("StreamedMediaExtractor", "Decryption failed or no 'decrypted' key")
        }
        Log.d("StreamedMediaExtractor", "Decrypted path: $decryptedPath")

        // Construct and test M3U8 URLs
        val m3u8Headers = baseHeaders + mapOf(
            "Referer" to embedReferer,
            "Origin" to embedReferer.substringBeforeLast("/embed/"),
            "Cookie" to combinedCookies
        )

        var subtitleFound = false
        for (domain in streamDomains) {
            val m3u8Url = "https://$domain$decryptedPath"
            try {
                val testResponse = app.get(m3u8Url, headers = m3u8Headers, timeout = 15)
                if (testResponse.code == 200 && testResponse.text.contains("#EXTM3U")) {
                    // Extract subtitles
                    val subtitleRegex = Regex("#EXT-X-MEDIA:TYPE=SUBTITLES.*?URI=\"(.*?)\".*?LANGUAGE=\"(.*?)\"", RegexOption.IGNORE_CASE)
                    subtitleRegex.findAll(testResponse.text).forEach { match ->
                        val subtitleUrl = match.groupValues[1]
                        val language = match.groupValues[2].ifEmpty { "English" }
                        val absoluteSubtitleUrl = normalizeUrl(m3u8Url, subtitleUrl)
                        subtitleCallback.invoke(SubtitleFile(language, absoluteSubtitleUrl))
                        subtitleFound = true
                    }

                    // Add the M3U8 link
                    val streamName = if (testResponse.text.contains("#EXT-X-PROGRAM-DATE-TIME")) {
                        "$source Stream $streamNo (Live)"
                    } else {
                        "$source Stream $streamNo"
                    }
                    callback.invoke(
                        newExtractorLink(
                            source = "Streamed",
                            name = streamName,
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = embedReferer
                            this.quality = Qualities.Unknown.value
                            this.headers = m3u8Headers
                        }
                    )
                    Log.d("StreamedMediaExtractor", "M3U8 URL added: $m3u8Url${if (subtitleFound) " with subtitles" else ""}")
                    return true
                } else {
                    Log.w("StreamedMediaExtractor", "M3U8 test failed for $domain with code: ${testResponse.code}")
                }
            } catch (e: Exception) {
                Log.e("StreamedMediaExtractor", "M3U8 test failed for $domain: ${e.message}")
            }
        }

        // If tests fail, add link anyway (as in original)
        val primaryDomain = streamDomains.firstOrNull() ?: "streamed.su"
        val m3u8Url = "https://$primaryDomain$decryptedPath"
        callback.invoke(
            newExtractorLink(
                source = "Streamed",
                name = "$source Stream $streamNo",
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = embedReferer
                this.quality = Qualities.Unknown.value
                this.headers = m3u8Headers
            }
        )
        Log.d("StreamedMediaExtractor", "M3U8 test failed but added anyway: $m3u8Url")
        return true
    }

    private suspend fun discoverEndpoints(streamUrl: String): Endpoints? {
        val cacheKey = "endpoints-$streamUrl"
        val cached = endpointCache[cacheKey]
        if (cached != null) {
            return cached.toEndpoints()
        }

        try {
            val response = app.get(streamUrl, headers = baseHeaders, timeout = 15)
            val doc = Jsoup.parse(response.text)

            // Extract embed domain and fetch URL
            val embedScript = doc.select("script[src*='embed']").firstOrNull()
            val embedDomain = embedScript?.attr("src")?.let { URL(it).host } ?: "embedstreams.top"
            val fetchUrl = "https://$embedDomain/fetch"

            // Extract cookie URL
            val cookieRegex = Regex("""https?://[^/]+\.streamed\.su/api/event""")
            val cookieUrl = cookieRegex.find(response.text)?.value ?: "https://fishy.streamed.su/api/event"

            // Extract decryption URL
            val decryptRegex = Regex("""https?://[^\s"]+/decrypt""")
            val decryptUrl = decryptRegex.find(response.text)?.value
                ?: run {
                    val embedUrl = streamUrl.replace("streamed.su/watch", "$embedDomain/embed")
                    val embedResponse = app.get(embedUrl, headers = baseHeaders, timeout = 15)
                    decryptRegex.find(embedResponse.text)?.value
                        ?: "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
                }

            // Extract stream domains
            val domainRegex = Regex("""https?://([^\s/"]+\.(?:top|su))/hls/""")
            val streamDomains = domainRegex.findAll(response.text).map { it.groupValues[1] }.toList()
                .ifEmpty { listOf(embedDomain, "streamed.su") }

            val endpoints = Endpoints(fetchUrl, cookieUrl, decryptUrl, streamDomains)
            endpointCache[cacheKey] = endpoints.toString()
            return endpoints
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Failed to discover endpoints: ${e.message}", e)
            return null
        }
    }

    private data class Endpoints(
        val fetchUrl: String,
        val cookieUrl: String,
        val decryptUrl: String,
        val streamDomains: List<String>
    ) {
        override fun toString(): String = "$fetchUrl|$cookieUrl|$decryptUrl|${streamDomains.joinToString(",")}"
        fun String.toEndpoints(): Endpoints? {
            val parts = split("|")
            return if (parts.size >= 4) {
                Endpoints(
                    parts[0],
                    parts[1],
                    parts[2],
                    parts[3].split(",")
                )
            } else null
        }
    }

    private suspend fun fetchEventCookies(pageUrl: String, referrer: String, cookieUrl: String): String {
        cookieCache[pageUrl]?.let { return it }

        val payload = """{"n":"pageview","u":"$pageUrl","d":"streamed.su","r":"$referrer"}"""
        try {
            val response = app.post(
                cookieUrl,
                data = mapOf(),
                headers = mapOf("Content-Type" to "text/plain"),
                requestBody = payload.toRequestBody("text/plain".toMediaType()),
                timeout = 15
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
            Log.e("StreamedMediaExtractor", "Failed to fetch event cookies: ${e.message}")
        }
        return ""
    }

    private fun normalizeUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.startsWith("http")) return relativeUrl
        try {
            val base = URL(baseUrl)
            val basePath = base.path.substring(0, base.path.lastIndexOf('/') + 1)
            val normalizedPath = if (relativeUrl.startsWith("/")) {
                relativeUrl
            } else {
                "$basePath$relativeUrl"
            }
            return URL(base.protocol, base.host, base.port, normalizedPath).toString()
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Failed to normalize URL: $relativeUrl with base $baseUrl", e)
            return relativeUrl
        }
    }
}