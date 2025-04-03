package ben.smith53

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import android.util.Log

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed"
    override var supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

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
            val source = match.matchSources.firstOrNull() ?: return@map null
            val url = "$mainUrl/api/stream/${source.sourceName}/${match.id}"
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
        val title = url.substringAfterLast("/").replace("-", " ").capitalize()
        val streamList = app.get(url).parsedSafe<List<StreamOption>>()
        val posterUrl = if (streamList.isNullOrEmpty()) {
            "$mainUrl/api/images/poster/fallback.webp"
        } else {
            "$mainUrl/api/images/poster/${streamList[0].id}.webp"
        }

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
        val streamList = app.get(data).parsedSafe<List<StreamOption>>()
        if (streamList.isNullOrEmpty()) {
            Log.e("StreamedProvider", "No stream options found at $data")
            return false
        }

        val extractor = StreamedExtractor()
        var success = false
        streamList.forEach { stream ->
            Log.d("StreamedProvider", "Processing stream: ${stream.embedUrl}")
            if (extractor.getUrl(stream.embedUrl, stream.hd, stream.streamNo, stream.source, stream.id, subtitleCallback, callback)) {
                success = true
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

    data class StreamOption(
        @JsonProperty("id") val id: String,
        @JsonProperty("streamNo") val streamNo: Int,
        @JsonProperty("language") val language: String,
        @JsonProperty("hd") val hd: Boolean,
        @JsonProperty("embedUrl") val embedUrl: String,
        @JsonProperty("source") val source: String
    )
}

class StreamedExtractor {
    private val mainUrl = "https://embedstreams.top"
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        "Referer" to "https://streamed.su/" // Base referer, overridden in fetch
    )
    private val cloudflareKiller = CloudflareKiller()

    suspend fun getUrl(
        embedUrl: String,
        isHd: Boolean,
        streamNo: Int,
        source: String,
        id: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StreamedExtractor", "Starting extraction for: $embedUrl")

        // Use StreamOption data directly
        val k = source // e.g., "alpha"
        val i = id    // e.g., "mlb-2025-season-live"
        val s = streamNo.toString() // e.g., "1"

        Log.d("StreamedExtractor", "Using variables: k=$k, i=$i, s=$s")

        // Fetch encrypted string with embedUrl as referer
        val fetchUrl = "$mainUrl/fetch"
        val postData = mapOf("source" to k, "id" to i, "streamNo" to s)
        val fetchHeaders = headers + mapOf(
            "Content-Type" to "application/json",
            "Referer" to embedUrl // Match the iframe URL
        )
        val encryptedResponse = try {
            val response = app.post(fetchUrl, headers = fetchHeaders, json = postData, interceptor = cloudflareKiller, timeout = 15)
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
        val decryptedPath = decryptResponse?.get("decrypted") ?: return false.also { Log.e("StreamedExtractor", "Decryption failed or no 'decrypted' key") }
        Log.d("StreamedExtractor", "Decrypted path: $decryptedPath")

        // Construct and verify M3U8 URL
        val m3u8Url = "https://rr.buytommy.top$decryptedPath"
        try {
            val testResponse = app.get(m3u8Url, headers = headers + mapOf("Referer" to embedUrl), interceptor = cloudflareKiller, timeout = 15)
            if (testResponse.code == 200) {
                callback(
                    ExtractorLink(
                        source = "Streamed",
                        name = "Stream $streamNo (${if (isHd) "HD" else "SD"})",
                        url = m3u8Url,
                        referer = embedUrl,
                        quality = if (isHd) Qualities.P1080.value else Qualities.Unknown.value,
                        isM3u8 = true,
                        headers = headers
                    )
                )
                Log.d("StreamedExtractor", "M3U8 URL added: $m3u8Url")
                return true
            } else {
                Log.e("StreamedExtractor", "M3U8 test failed with code: ${testResponse.code}")
            }
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "M3U8 test failed: ${e.message}")
        }
        return false
    }
}