package ben.smith53

import android.content.Context
import android.widget.Toast
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class Streamed : MainAPI() {
    override var mainUrl = MAIN_URL
    override var name = NAME
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "uni"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    private val sharedPref by lazy { activity?.getSharedPreferences("Streamed", Context.MODE_PRIVATE) }

    init {
        sharedPref?.edit()?.clear()?.apply()
    }

    companion object {
        var canShowToast = true
        const val MAIN_URL = "https://streamed.su"
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
        "$mainUrl/api/matches/other" to "Other"
    )

    override val mainPage = sectionNamesList

    private suspend fun searchResponseBuilder(
        listJson: List<Match>,
        filter: (Match) -> Boolean
    ): List<LiveSearchResponse> {
        return listJson.filter(filter).amap { match ->
            val url = if (match.matchSources.isNotEmpty()) {
                val sourceName = match.matchSources[0].sourceName
                val id = match.matchSources[0].id
                "$mainUrl/api/stream/$sourceName/$id/${match.id}"
            } else ""
            newLiveSearchResponse(
                name = match.title,
                url = url,
                type = TvType.Live
            ) {
                this.apiName = this@Streamed.name
                this.posterUrl = "$mainUrl${match.posterPath ?: "/api/images/poster/fallback.webp"}"
            }
        }.filter { it.url.count { char -> char == '/' } > 1 }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val rawList = app.get(request.data).text
        val listJson = parseJson<List<Match>>(rawList)
        listJson.forEach { match ->
            sharedPref?.edit()?.putString(match.id ?: "", match.toJson())?.apply()
        }

        val filteredList = searchResponseBuilder(listJson) { match ->
            match.matchSources.isNotEmpty() && (match.popular || request.data.contains("popular"))
        }

        return newHomePageResponse(
            listOf(HomePageList(
                name = request.name,
                list = filteredList,
                isHorizontalImages = true
            )), false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val allMatches = app.get("$mainUrl/api/matches/all").text
        val allMatchesJson = parseJson<List<Match>>(allMatches)
        return searchResponseBuilder(allMatchesJson) { match ->
            match.matchSources.isNotEmpty() && match.title.contains(query, ignoreCase = true)
        }
    }

    private suspend fun Source.getMatch(id: String): Match? {
        val allMatches = app.get("$mainUrl/api/matches/all").text
        val allMatchesJson = parseJson<List<Match>>(allMatches)
        return allMatchesJson.find { match ->
            match.matchSources.any { it.id == id && it.sourceName == this.source }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val matchId = url.substringAfterLast('/')
        val trueUrl = url.substringBeforeLast('/')

        if (trueUrl.toHttpUrlOrNull() == null) throw ErrorLoadingException("The stream is not available")

        val match = sharedPref?.getString(matchId, null)?.let { parseJson<Match>(it) }
            ?: throw ErrorLoadingException("Error loading match from cache")

        val elementTags = arrayListOf(match.category.capitalize())
        match.isoDateTime?.let {
            val formatter = SimpleDateFormat("dd MMM yyyy 'at' HH:mm", Locale.getDefault())
            elementTags.add(formatter.format(Date(it)))
        }
        match.teams?.values?.mapNotNull { it?.name }?.let { elementTags.addAll(it) }

        var comingSoon = true
        try {
            val response = app.get(trueUrl)
            val data = parseJson<List<Source>>(response.text)
            match.isoDateTime?.let {
                val calendar = Calendar.getInstance().apply {
                    time = Date(it)
                    add(Calendar.MINUTE, -15)
                }
                if (calendar.time.time <= Date().time && data.isNotEmpty()) comingSoon = false
            } ?: if (data.isNotEmpty()) comingSoon = false
        } catch (e: Exception) {
            Log.e("Streamed", "Failed to load sources: $e")
        }

        return newLiveStreamLoadResponse(
            name = match.title,
            url = trueUrl,
            dataUrl = trueUrl
        ) {
            this.apiName = this@Streamed.name
            this.posterUrl = "$mainUrl${match.posterPath ?: "/api/images/poster/fallback.webp"}"
            this.plot = match.title
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
        val rawJson = app.get(data).text
        val source = parseJson<List<Source>>(rawJson).firstOrNull() ?: return false

        val matchId = data.substringAfterLast("/")
        val match = source.getMatch(matchId) ?: return false

        match.matchSources.forEach { matchSource ->
            val url = "$mainUrl/api/stream/${matchSource.sourceName}/${matchSource.id}"
            // Assuming StreamedExtractor is from it.dogior.hadEnough
            StreamedExtractor().getUrl(
                url = url,
                referer = "https://embedme.top/",
                subtitleCallback = subtitleCallback,
                callback = callback
            )
        }
        return true
    }

    data class Match(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String,
        @JsonProperty("category") val category: String,
        @JsonProperty("date") val isoDateTime: Long? = null,
        @JsonProperty("poster") val posterPath: String? = null,
        @JsonProperty("popular") val popular: Boolean = false,
        @JsonProperty("teams") val teams: LinkedHashMap<String, Team?>? = null,
        @JsonProperty("sources") val matchSources: ArrayList<MatchSource> = arrayListOf()
    )

    data class Team(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("badge") val badge: String? = null
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