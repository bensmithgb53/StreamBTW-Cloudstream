package ben.smith53

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.*

class StreamedProvider : MainAPI() {
    override var mainUrl = MAIN_URL
    override var name = NAME
    override var supportedTypes = setOf(TvType.Live)
    override var lang = "uni"
    override val hasMainPage = true
    override val hasDownloadSupport = false

    // Use lazy initialization to get the context safely
    private val sharedPref by lazy {
        activity?.getSharedPreferences("Streamed", Context.MODE_PRIVATE)
            ?: throw IllegalStateException("Activity context is null")
    }
    private val cloudflareKiller = CloudflareKiller()

    init {
        val editor = sharedPref.edit()
        editor.clear()
        editor.apply()
    }

    companion object {
        var canShowToast = true
        const val MAIN_URL = "https://streamed.su"
        const val EMBED_URL = "https://embedstreams.top"
        const val NAME = "Streamed"
    }

    private val sectionNamesList = mainPageOf(
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
        "$mainUrl/api/matches/other" to "Other",
    )

    override val mainPage = sectionNamesList

    private suspend fun searchResponseBuilder(
        listJson: List<Match>,
        filter: (Match) -> Boolean
    ): List<LiveSearchResponse> {
        return listJson.filter(filter).amap { match ->
            var url = ""
            if (match.matchSources.isNotEmpty()) {
                val sourceName = match.matchSources[0].sourceName
                val id = match.matchSources[0].id
                url = "$mainUrl/api/stream/$sourceName/$id"
            }
            url += "/${match.id}"
            newLiveSearchResponse(
                name = match.title,
                url = url,
                apiName = this@StreamedProvider.name
            ) {
                this.posterUrl = "$mainUrl${match.posterPath ?: "/api/images/poster/fallback.webp"}"
            }
        }.filter { it.url.count { char -> char == '/' } > 1 }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val rawList = app.get(request.data).text
        val listJson = parseJson<List<Match>>(rawList)
        listJson.amap {
            with(sharedPref.edit()) {
                putString(it.id ?: return@amap, it.toJson())
                apply()
            }
        }

        val list = searchResponseBuilder(listJson) { match ->
            match.matchSources.isNotEmpty() && match.popular
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, list, isHorizontalImages = true)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val allMatches = app.get("$mainUrl/api/matches/all").body.string()
        val allMatchesJson = parseJson<List<Match>>(allMatches)
        return searchResponseBuilder(allMatchesJson) { match ->
            match.matchSources.isNotEmpty() && match.title.contains(query, ignoreCase = true)
        }
    }

    private suspend fun Source.getMatch(id: String): Match? {
        val allMatches = app.get("$mainUrl/api/matches/all").body.string()
        val allMatchesJson = parseJson<List<Match>>(allMatches)
        val matchesList = allMatchesJson.filter { match ->
            match.matchSources.isNotEmpty() &&
                    match.matchSources.any { it.id == id && it.sourceName == this.source }
        }
        return if (matchesList.isEmpty()) null else matchesList[0]
    }

    override suspend fun load(url: String): LoadResponse {
        val matchId = url.substringAfterLast('/')
        val trueUrl = url.substringBeforeLast('/')

        var comingSoon = true

        if (trueUrl.toHttpUrlOrNull() == null) {
            throw ErrorLoadingException("The stream is not available")
        }

        val match = sharedPref.getString(matchId, null)?.let { parseJson<Match>(it) }
            ?: throw ErrorLoadingException("Error loading match from cache")

        val elementName = match.title
        val elementPlot = match.title
        val elementPoster = "$mainUrl${match.posterPath ?: "/api/images/poster/fallback.webp"}"
        val elementTags = arrayListOf(match.category.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() })

        try {
            val response = app.get(trueUrl)
            val rawJson = response.body.string()
            val data = parseJson<List<Source>>(rawJson)

            match.isoDateTime?.let {
                val calendar = Calendar.getInstance()
                calendar.time = Date(it)
                calendar.add(Calendar.MINUTE, -15)
                val matchTimeMinus15 = calendar.time.time

                if (matchTimeMinus15 <= Date().time && data.isNotEmpty()) {
                    comingSoon = false
                }
            }
            if (match.isoDateTime == null && data.isNotEmpty()) {
                comingSoon = false
            }
        } catch (e: Exception) {
            Log.e("STREAMED:Item", "Failed to load sources: $e")
        }

        match.isoDateTime?.let {
            val formatter = SimpleDateFormat("dd MMM yyyy 'at' HH:mm", Locale.getDefault())
            val date = formatter.format(Date(it))
            elementTags.add(date)
        }
        match.teams?.values?.mapNotNull { it?.name }?.let { elementTags.addAll(it) }

        return newLiveStreamLoadResponse(
            name = elementName,
            url = trueUrl,
            apiName = this.name,
            dataUrl = trueUrl
        ) {
            this.plot = elementPlot
            this.posterUrl = elementPoster
            this.tags = elementTags
            this.comingSoon = comingSoon
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rawJson = app.get(data).body.string()
        val source = parseJson<List<Source>>(rawJson)[0]
        val sourceUrlID = data.substringAfterLast("/")
        val match = source.getMatch(sourceUrlID)

        var success = false
        match?.matchSources?.forEach { matchSource ->
            val url = "$mainUrl/api/stream/${matchSource.sourceName}/${matchSource.id}"
            if (extractStreamedSU(url, subtitleCallback, callback)) {
                success = true
            }
        }
        return success
    }

    private suspend fun extractStreamedSU(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        )

        Log.d("StreamedSU", "Starting loadLinks for: $data")

        // Step 1: Fetch the page and extract iframe URL
        val response = app.get(data, headers = headers, interceptor = cloudflareKiller, timeout = 30)
        val doc: Document = response.document
        val iframeUrl = doc.selectFirst("iframe[src]")?.attr("src") ?: return false
        Log.d("StreamedSU", "Iframe URL: $iframeUrl")

        // Step 2: Fetch iframe content and extract variables
        val iframeResponse = app.get(iframeUrl, headers = headers, interceptor = cloudflareKiller, timeout = 15).text
        val varPairs = Regex("""(\w+)\s*=\s*["']([^"']+)["']""").findAll(iframeResponse)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val k = varPairs["k"] ?: return false
        val i = varPairs["i"] ?: return false
        val s = varPairs["s"] ?: return false
        Log.d("StreamedSU", "Variables: k=$k, i=$i, s=$s")

        // Step 3: Fetch encrypted string from /fetch
        val fetchUrl = "$EMBED_URL/fetch"
        val postData = mapOf("source" to k, "id" to i, "streamNo" to s)
        val fetchHeaders = headers + mapOf(
            "Content-Type" to "application/json",
            "Referer" to iframeUrl
        )
        val encryptedResponse = app.post(fetchUrl, headers = fetchHeaders, json = postData, interceptor = cloudflareKiller, timeout = 15).text
        Log.d("StreamedSU", "Encrypted response: $encryptedResponse")

        // Step 4: Decrypt using Deno Deploy endpoint
        val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        val decryptResponse = app.post(decryptUrl, json = decryptPostData, headers = mapOf("Content-Type" to "application/json"))
            .parsedSafe<Map<String, String>>()
        val decryptedPath = decryptResponse?.get("decrypted") ?: return false
        Log.d("StreamedSU", "Decrypted path: $decryptedPath")

        // Step 5: Construct and test the M3U8 URL
        val m3u8Url = "https://rr.buytommy.top$decryptedPath"
        try {
            val testResponse = app.get(m3u8Url, headers = headers + mapOf("Referer" to iframeUrl), interceptor = cloudflareKiller, timeout = 15)
            if (testResponse.code == 200) {
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = "Live Stream",
                        url = m3u8Url,
                        referer = iframeUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = true,
                        headers = headers
                    )
                )
                Log.d("StreamedSU", "M3U8 URL added: $m3u8Url")
                return true
            } else {
                Log.e("StreamedSU", "M3U8 test failed with code: ${testResponse.code}")
                return false
            }
        } catch (e: Exception) {
            Log.e("StreamedSU", "M3U8 test failed: ${e.message}")
            return false
        }
    }

    fun showToastOnce(message: String) {
        if (canShowToast) {
            activity?.showToast(message, Toast.LENGTH_LONG)
            canShowToast = false
        }
    }

    data class SecurityResponse(
        @JsonProperty("expiry") val expiry: Long,
        @JsonProperty("id") val id: String
    )

    data class Match(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String,
        @JsonProperty("category") val category: String,
        @JsonProperty("date") val isoDateTime: Long? = null,
        @JsonProperty("poster") val posterPath: String? = null,
        @JsonProperty("popular") val popular: Boolean = false,
        @JsonProperty("teams") val teams: LinkedHashMap<String, Team?>? = null,
        @JsonProperty("sources") val matchSources: ArrayList<MatchSource> = arrayListOf(),
    )

    data class Team(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("badge") val badge: String? = null,
    )

    data class MatchSource(
        @JsonProperty("source") val sourceName: String,
        @JsonProperty("id") val id: String
    )

    data class Source(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("streamNo") val streamNumber: Int? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("hd") val isHD: Boolean = false,
        @JsonProperty("embedUrl") val embedUrl: String? = null,
        @JsonProperty("source") val source: String? = null
    )
}