package ben.smith53

import android.util.Log
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
        Log.d("StrimsyStreaming", "Fetching main page, page: $page")
        val document = try {
            app.get(mainUrl, timeout = 30).document
        } catch (e: Exception) {
            Log.e("StrimsyStreaming", "Error loading main page: ${e.message}", e)
            throw ErrorLoadingException("Could not load schedule from $mainUrl: ${e.message}")
        }
        Log.d("StrimsyStreaming", "Main page document size: ${document.html().length}")

        val tabs = document.select("div.tabcontent")
        if (tabs.isEmpty()) {
            Log.w("StrimsyStreaming", "No tabcontent found")
            return newHomePageResponse(listOf(), hasNext = false)
        }

        val homePageLists = tabs.mapIndexedNotNull { index, tab ->
            val tabButton = document.select("button.tablinks").getOrNull(index)
            if (tabButton == null) {
                Log.w("StrimsyStreaming", "No matching tab button for index $index")
                return@mapIndexedNotNull null
            }
            val polishDayName = tabButton.text().uppercase()
            val englishDayName = dayTranslation[polishDayName] ?: polishDayName
            Log.d("StrimsyStreaming", "Processing day: $englishDayName")
            val events = tab.select("td").mapNotNull { td ->
                val linkElement = td.selectFirst("a") ?: run {
                    Log.w("StrimsyStreaming", "No link element in td")
                    return@mapNotNull null
                }
                val href = fixUrl(linkElement.attr("href"))
                val rawName = linkElement.text().trim()
                val className = linkElement.className().takeIf { it.isNotEmpty() }
                val translatedName = translateEventName(rawName, className)
                val time = td.text().substringBefore(" ").trim().takeIf { it.isNotEmpty() } ?: "Unknown Time"
                Log.d("StrimsyStreaming", "Found event: $time - $translatedName ($href)")
                newLiveSearchResponse(
                    name = "$time - $translatedName",
                    url = href,
                    type = TvType.Live
                )
            }
            HomePageList(englishDayName, events, isHorizontalImages = false)
        }
        Log.d("StrimsyStreaming", "Returning ${homePageLists.size} home page lists")
        return newHomePageResponse(homePageLists, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d("StrimsyStreaming", "Loading URL: $url")
        val document = try {
            app.get(url, timeout = 30).document
        } catch (e: Exception) {
            Log.e("StrimsyStreaming", "Error loading URL: ${e.message}", e)
            throw ErrorLoadingException("Could not load event page: ${e.message}")
        }
        Log.d("StrimsyStreaming", "Document size: ${document.html().length}")

        val rawTitle = document.selectFirst("title")?.text()?.substringBefore(" - STRIMS")?.trim()
            ?: url.substringAfterLast("/").substringBefore(".php").replace("-", " ").ifEmpty { "Unknown Event" }
        val className = document.selectFirst("iframe")?.parent()?.selectFirst("a")?.className()
        val translatedTitle = translateEventName(rawTitle, className)
        Log.d("StrimsyStreaming", "Loaded title: $translatedTitle")

        return newLiveStreamLoadResponse(
            name = translatedTitle,
            url = url,
            dataUrl = url
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StrimsyStreaming", "Loading links for data: $data, isCasting: $isCasting")
        return try {
            val document = app.get(data, timeout = 30).document
            Log.d("StrimsyStreaming", "Document size for links: ${document.html().length}")

            // Try iframe fallback
            val iframe = document.selectFirst("iframe")?.attr("src")
            if (iframe != null) {
                val fixedIframe = fixUrl(iframe)
                Log.d("StrimsyStreaming", "Found iframe URL: $fixedIframe")
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = "Strimsy Direct",
                        url = fixedIframe,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = fixedIframe.contains(".m3u8")
                    )
                )
                return true
            }

            // Fallback to StrimsyExtractor
            Log.d("StrimsyStreaming", "No iframe, delegating to StrimsyExtractor")
            val extractor = StrimsyExtractor()
            extractor.getUrl(data, mainUrl, subtitleCallback, callback)
            Log.d("StrimsyStreaming", "StrimsyExtractor call completed")
            true
        } catch (e: Exception) {
            Log.e("StrimsyStreaming", "Error in loadLinks: ${e.message}", e)
            false
        }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        Log.d("StrimsyStreaming", "Setting up video interceptor for ${extractorLink.url}")
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                return try {
                    Log.d("StrimsyStreaming", "Applying Cloudflare bypass")
                    cfKiller.intercept(chain)
                } catch (e: Exception) {
                    Log.w("StrimsyStreaming", "Cloudflare bypass failed: ${e.message}", e)
                    chain.proceed(chain.request())
                }
            }
        }
    }

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            !url.startsWith("http") -> "$mainUrl/$url"
            else -> url
        }
    }
}
