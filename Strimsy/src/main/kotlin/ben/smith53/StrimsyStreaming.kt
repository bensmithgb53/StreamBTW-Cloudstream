package ben.smith53

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newHomePageResponse
import com.lagradost.cloudstream3.utils.newLiveSearchResponse
import com.lagradost.cloudstream3.utils.newLiveStreamLoadResponse
import org.jsoup.Jsoup
import java.util.*

class StrimsyStreaming : MainAPI() {
    override var mainUrl = "https://strimsy.top"
    override var name = "Strimsy"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"
    override val hasMainPage = true

    private val eventTranslation = mapOf(
        "pilkanozna" to "Football", "koszykowka" to "Basketball", "kosz" to "Basketball",
        "nba" to "NBA", "hhokej" to "Hockey", "walki" to "Fighting", "kolarstwo" to "Cycling",
        "siatkowka" to "Volleyball", "pilkareczna" to "Handball", "bilard" to "Snooker",
        "tenis" to "Tennis", "skoki" to "Ski Jumping", "magazyn" to "Magazine"
    )

    private val dayTranslation = mapOf(
        "Poniedziałek" to "Monday", "Wtorek" to "Tuesday", "Środa" to "Wednesday",
        "Czwartek" to "Thursday", "Piątek" to "Friday", "Sobota" to "Saturday",
        "Niedziela" to "Sunday"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = Jsoup.connect(mainUrl).get()
        val tabs = doc.select(".tab button.tablinks")
        val contents = doc.select(".tabcontent")
        val homePages = mutableListOf<HomePageList>()

        tabs.zip(contents).forEach { (button, content) ->
            val day = dayTranslation[button.text()] ?: button.text()
            val events = content.select("table.ramowka td").mapNotNull { row ->
                val text = row.text().trim()
                val link = row.selectFirst("a[href]") ?: return@mapNotNull null
                val match = Regex("""(\d{2}:\d{2})\s*(.*)""").find(text) ?: return@mapNotNull null
                val (time, nameRaw) = match.destructured
                val className = link.className()
                val name = eventTranslation[nameRaw.lowercase()] ?: if (className.isNotEmpty() && className.lowercase() in eventTranslation) {
                    "${eventTranslation[className.lowercase()]}: $nameRaw"
                } else nameRaw
                newLiveSearchResponse(
                    name = "$time - $name",
                    url = fixUrl(link.attr("href")),
                    apiName = this.name
                )
            }
            if (events.isNotEmpty()) {
                homePages.add(HomePageList(day, events))
            }
        }
        return newHomePageResponse(homePages)
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("http")) url else "$mainUrl/$url"
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = Jsoup.connect(url).get()
        val sources = doc.select("a[href*=\"?source=\"]").map {
            fixUrl(it.attr("href"))
        }.ifEmpty { listOf(url) }

        val streams = sources.flatMap { sourceUrl ->
            val sourceDoc = Jsoup.connect(sourceUrl).get()
            sourceDoc.select("iframe[src]").filter { !it.attr("src").contains("/layout/chat", ignoreCase = true) }
                .map { fixUrl(it.attr("src")) }
        }
        return newLiveStreamLoadResponse(
            name = url.split("/").last().removeSuffix(".php"),
            url = url,
            apiName = this.name,
            dataUrl = streams.first() // Pass first iframe URL to extractor
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        StrimsyExtractor().getUrl(data, referer = mainUrl).forEach(callback)
    }
}
