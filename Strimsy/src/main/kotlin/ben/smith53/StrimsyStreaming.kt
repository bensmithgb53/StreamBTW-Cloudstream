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
        return eventTranslation[name] ?: className?.let { cls ->
            eventTranslation[cls]?.let { type -> "$type: $name" }
        } ?: name
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = try {
            app.get(mainUrl, timeout = 30).document
        } catch (e: Exception) {
            throw ErrorLoadingException("Could not load schedule from $mainUrl: ${e.message}")
        }

        val tabs = document.select("div.tabcontent")
        if (tabs.isEmpty()) {
            return newHomePageResponse(emptyList()) // Return empty response instead of throwing
        }

        val homePageLists = tabs.mapIndexedNotNull { index, tab ->
            val tabButton = document.select("button.tablinks").getOrNull(index)
            if (tabButton == null) {
                return@mapIndexedNotNull null
            }
            val polishDayName = tabButton.text().uppercase()
            val englishDayName = dayTranslation[polishDayName] ?: polishDayName
            val events = tab.select("td").mapNotNull { td ->
                val linkElement = td.selectFirst("a") ?: return@mapNotNull null
                val href = fixUrl(linkElement.attr("href"))
                val rawName = linkElement.text().trim()
                val className = linkElement.className().takeIf { it.isNotEmpty() }
                val translatedName = translateEventName(rawName, className)
                val time = td.text().substringBefore(" ").trim().takeIf { it.isNotEmpty() } ?: "Unknown Time"
                newLiveSearchResponse(
                    name = "$time - $translatedName",
                    url = href,
                    type = TvType.Live
                )
            }
            HomePageList(englishDayName, events, isHorizontalImages = false)
        }
        return newHomePageResponse(homePageLists)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = try {
            app.get(url, timeout = 30).document
        } catch (e: Exception) {
            throw ErrorLoadingException("Could not load event page: ${e.message}")
        }

        val rawTitle = document.selectFirst("title")?.text()?.substringBefore(" - STRIMS")?.trim()
            ?: url.substringAfterLast("/").substringBefore(".php").replace("-", " ").ifEmpty { "Unknown Event" }
        val className = document.selectFirst("iframe")?.parent()?.selectFirst("a")?.className()
        val translatedTitle = translateEventName(rawTitle, className)

        return newLiveStreamLoadResponse(
            name = translatedTitle,
            dataUrl = url,
            url = url
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Fallback if StrimsyExtractor is missing or fails
            val document = app.get(data, timeout = 30).document
            val iframe = document.selectFirst("iframe")?.attr("src")
            if (iframe != null) {
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = "Strimsy Direct",
                        url = fixUrl(iframe),
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = iframe.contains("m3u8")
                    )
                )
                true
            } else {
                StrimsyExtractor().getUrl(data, mainUrl, subtitleCallback, callback)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                return try {
                    cfKiller.intercept(chain)
                } catch (e: Exception) {
                    chain.proceed(chain.request()) // Proceed without bypass if it fails
                }
            }
        }
    }

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            !url.startsWith("http") -> "$mainUrl/$url"
            else -> url // Added else branch to make when exhaustive
        }
    }
}
