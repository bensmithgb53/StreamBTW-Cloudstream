package ben.smith53

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document

class StrimsyStreaming : MainAPI() {
    override var lang = "en"
    override var mainUrl = "https://strimsy.top"
    override var name = "StrimsyStreaming"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)
    private val cfKiller = CloudflareKiller()

    private val dayTranslation = mapOf(
        "PONIEDZIAŁEK" to "Monday",
        "WTOREK" to "Tuesday",
        "ŚRODA" to "Wednesday",
        "CZWARTEK" to "Thursday",
        "PIĄTEK" to "Friday",
        "SOBOTA" to "Saturday",
        "NIEDZIELA" to "Sunday"
    )

    private val eventTranslation = mapOf(
        "pilkanozna" to "Football",
        "koszykowka" to "Basketball",
        "kosz" to "Basketball",
        "wyscigi" to "Racing",
        "walki" to "Fighting",
        "pilkareczna" to "Handball",
        "skoki" to "Skiing",
        "siatkowka" to "Volleyball",
        "magazyn" to "Magazine",
        "tenis" to "Tennis",
        "hokej" to "Hockey",
        "hhokej" to "Hockey",
        "f1" to "Formula 1",
        "bilard" to "Billiards",
        "krykiet" to "Cricket",
        "golf" to "Golf",
        "kolarstwo" to "Cycling",
        "motor" to "Motorcycle Racing",
        "nfl" to "American Football",
        "nnfl" to "American Football",
        "mlb" to "Baseball",
        "dart" to "Darts",
        "rugby" to "Rugby",
        "Ekstraklasa po godzinach" to "Ekstraklasa After Hours",
        "Magazyn 1. ligi piłkarskiej" to "1st League Football Magazine",
        "GOL - magazyn Ekstraklasy" to "GOAL - Ekstraklasa Magazine",
        "Liga Mistrzów: studio" to "Champions League: Studio",
        "Multi Liga Mistrzów" to "Multi Champions League",
        "Liga Mistrzów: studio/skróty" to "Champions League: Studio/Highlights",
        "Polsat SiatCast" to "Polsat Volleyball Cast",
        "Piłkarski Młyn" to "Football Mill",
        "Champions Club" to "Champions Club",
        "Polsat Futbol Cast" to "Polsat Football Cast",
        "WWE Monday Night Raw" to "WWE Monday Night Raw",
        "WTA Indian Wells" to "WTA Indian Wells",
        "Avalanche" to "Colorado Avalanche"
    )

    private fun translateEventName(name: String, className: String?): String {
        eventTranslation[name]?.let { return it }
        className?.let { cls ->
            eventTranslation[cls]?.let { type ->
                return "$type: $name"
            }
        }
        return name
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val tabs = document.select("div.tabcontent")

        if (tabs.isEmpty()) throw ErrorLoadingException("No schedule found")

        val homePageLists = tabs.mapIndexed { index, tab ->
            val polishDayName = document.select("button.tablinks")[index].text().uppercase()
            val englishDayName = dayTranslation[polishDayName] ?: polishDayName
            val events = tab.select("td").mapNotNull { td ->
                val linkElement = td.selectFirst("a") ?: return@mapNotNull null
                val href = fixUrl(linkElement.attr("href"))
                val rawName = linkElement.text().trim()
                val className = linkElement.className().takeIf { it.isNotEmpty() }
                val translatedName = translateEventName(rawName, className)
                val time = td.text().substringBefore(" ").trim()
                newLiveSearchResponse( // Updated from LiveSearchResponse
                    name = "$time - $translatedName",
                    url = href,
                    apiName = this@StrimsyStreaming.name,
                    type = TvType.Live,
                    posterUrl = null
                )
            }
            HomePageList(englishDayName, events, isHorizontalImages = false)
        }
        return newHomePageResponse(homePageLists) // Updated from HomePageResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val rawTitle = document.selectFirst("title")?.text()?.substringBefore(" - STRIMS")?.trim()
            ?: url.substringAfterLast("/").substringBefore(".php").replace("-", " ")
        val className = document.selectFirst("iframe")?.parent()?.selectFirst("a")?.className()
        val translatedTitle = translateEventName(rawTitle, className)

        return newLiveStreamLoadResponse( // Updated from LiveStreamLoadResponse
            name = translatedTitle,
            url = url,
            apiName = this.name,
            dataUrl = url,
            posterUrl = null,
            plot = "Live stream from Strimsy"
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            StrimsyExtractor().getUrl(data, data, subtitleCallback, callback)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                return cfKiller.intercept(chain)
            }
        }
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("//")) "https:$url"
        else if (url.startsWith("/")) "$mainUrl$url"
        else if (!url.startsWith("http")) "$mainUrl/$url"
        else url
    }
}
