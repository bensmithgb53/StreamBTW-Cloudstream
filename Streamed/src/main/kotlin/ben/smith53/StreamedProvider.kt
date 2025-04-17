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
    private val proxyUrl = "http://localhost:8000/playlist.m3u8"
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
        "Content-Type" to "application/json"
    )

    private suspend fun fetchCookies(): String? {
        try {
            val response = app.post(cookieUrl, headers = baseHeaders, json = emptyMap(), timeout = 15)
            val cookies = response.headers.values("set-cookie") ?: return null
            val cookieDict = mutableMapOf(
                "ddg8_" to null,
                "ddg10_" to null,
                "ddg9_" to null,
                "ddg1_" to null
            )
            cookies.forEach { cookie ->
                cookie.split(";").forEach { part ->
                    if ("=" in part) {
                        val split = part.split("=", limit = 2)
                        val name = split[0].trim()
                        val value = split.getOrNull(1)?.trim() ?: ""
                        val normalizedName = if (name.startsWith("__ddg")) name.substring(2) else name
                        if (normalizedName in cookieDict) {
                            cookieDict[normalizedName] = value
                        }
                    }
                }
            }
            val cookieList = mutableListOf<String>()
            listOf("ddg8_", "ddg10_", "ddg9_", "ddg1_").forEach { key ->
                cookieDict[key]?.let { cookieList.add("$key=$it") }
            }
            val cookieString = cookieList.joinToString("; ")
            Log.d("StreamedExtractor", "Fetched cookies: $cookieString")
            return if (cookieString.isNotEmpty()) cookieString else null
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

        // Fetch cookies from fishy.streamed.su/api/event for this specific M3U8
        val cookies = fetchCookies() ?: run {
            Log.e("StreamedExtractor", "No cookies fetched")
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

        // Construct M3U8 URL and proxy URL with fresh cookies
        val m3u8Url = "https://rr.buytommy.top$decryptedPath"
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
                    this.headers = baseHeaders
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