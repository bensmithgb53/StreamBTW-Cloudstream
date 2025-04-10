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
import java.util.Base64
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
                if (extractor.getUrl(streamUrl, match诸多Id, source, streamNo, subtitleCallback, callback)) {
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
    private val proxyBase = "https://corsproxy.io/?" // Free CORS proxy
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Referer" to "https://embedstreams.top/",
        "Accept" to "*/*",
        "Origin" to "https://embedstreams.top",
        "Accept-Encoding" to "identity" // Avoid compression issues
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

        // Step 1: Fetch stream page for cookies
        val streamResponse = try {
            app.get(streamUrl, headers = baseHeaders, timeout = 15)
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Stream page fetch failed: ${e.message}")
            return false
        }
        val cookies = streamResponse.cookies
        Log.d("StreamedExtractor", "Stream cookies: $cookies")

        // Step 2: POST to fetch encrypted string
        val postData = mapOf(
            "source" to source,
            "id" to matchId,
            "streamNo" to streamNo.toString()
        )
        val embedReferer = "https://embedstreams.top/embed/$source/$matchId/$streamNo"
        val fetchHeaders = baseHeaders + mapOf(
            "Referer" to streamUrl,
            "Cookie" to cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        )
        Log.d("StreamedExtractor", "Fetching with data: $postData and headers: $fetchHeaders")

        val encryptedResponse = try {
            val response = app.post(fetchUrl, headers = fetchHeaders, json = postData, timeout = 15)
            Log.d("StreamedExtractor", "Fetch response code: ${response.code}")
            if (response.code != 200) return false
            response.text
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Fetch failed: ${e.message}")
            return false
        }
        Log.d("StreamedExtractor", "Encrypted response: $encryptedResponse")

        // Step 3: Decrypt using Deno service
        val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        val decryptResponse = try {
            app.post(decryptUrl, json = decryptPostData, headers = mapOf("Content-Type" to "application/json"), timeout = 15)
                .parsedSafe<Map<String, String>>()
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Decryption request failed: ${e.message}")
            return false
        }
        val decryptedPath = decryptResponse?.get("decrypted") ?: return false.also { Log.e("StreamedExtractor", "Decryption failed or no 'decrypted' key") }
        Log.d("StreamedExtractor", "Decrypted path: $decryptedPath")

        // Step 4: Construct and fetch M3U8 URL through proxy
        val m3u8Url = "https://rr.buytommy.top$decryptedPath"
        val proxiedM3u8Url = "$proxyBase${URLEncoder.encode(m3u8Url, "UTF-8")}"
        val m3u8Headers = baseHeaders + mapOf("Referer" to embedReferer)

        val m3u8Response = try {
            app.get(proxiedM3u8Url, headers = m3u8Headers, timeout = 15)
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Proxied M3U8 fetch failed: ${e.message}")
            return false
        }

        if (m3u8Response.code != 200) {
            Log.e("StreamedExtractor", "Proxied M3U8 fetch failed with code: ${m3u8Response.code}")
            return false
        }

        val m3u8Content = m3u8Response.text
        Log.d("StreamedExtractor", "Original M3U8 content: $m3u8Content")

        // Step 5: Rewrite M3U8 content
        val rewrittenLines = m3u8Content.split("\n").map { line ->
            when {
                line.startsWith("#EXT-X-KEY") && "URI=" in line -> {
                    val originalKeyUri = line.split("URI=\"")[1].split("\"")[0]
                    val proxiedKeyUri = "$proxyBase${URLEncoder.encode(originalKeyUri, "UTF-8")}"
                    line.replace(originalKeyUri, proxiedKeyUri)
                }
                line.startsWith("https://") -> {
                    val originalUrl = line.trim()
                    val proxiedUrl = if (originalUrl.contains("rr.buytommy.top")) {
                        "$proxyBase${URLEncoder.encode(originalUrl.replace(".js", ".ts"), "UTF-8")}"
                    } else {
                        "$proxyBase${URLEncoder.encode(originalUrl.replace(".ts", ".js"), "UTF-8")}"
                    }
                    proxiedUrl
                }
                else -> line
            }
        }
        val rewrittenM3u8Content = rewrittenLines.joinToString("\n")
        Log.d("StreamedExtractor", "Rewritten M3U8 content: $rewrittenM3u8Content")

        // Step 6: Encode as data URI and provide to Cloudstream3
        val dataUri = "data:application/vnd.apple.mpegurl;base64,${Base64.getEncoder().encodeToString(rewrittenM3u8Content.toByteArray())}"
        callback.invoke(
            newExtractorLink(
                source = "Streamed",
                name = "$source Stream $streamNo",
                url = dataUri,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = embedReferer
                this.quality = Qualities.Unknown.value
                this.headers = m3u8Headers
            }
        )
        Log.d("StreamedExtractor", "M3U8 data URI added: $dataUri")
        return true
    }
}