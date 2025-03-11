package ben.smith53

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document

class StrimsyStreaming : MainAPI() {
    override var lang = "en" // English language
    override var mainUrl = "https://strimsy.top" // Primary URL
    override var name = "StrimsyStreaming"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)
    private val cfKiller = CloudflareKiller()

    // English day names mapping
    private val dayTranslation = mapOf(
        "PONIEDZIAŁEK" to "Monday",
        "WTOREK" to "Tuesday",
        "ŚRODA" to "Wednesday",
        "CZWARTEK" to "Thursday",
        "PIĄTEK" to "Friday",
        "SOBOTA" to "Saturday",
        "NIEDZIELA" to "Sunday"
    )

    // Translation map for event types and common terms
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

    // Function to translate event names
    private fun translateEventName(name: String, className: String?): String {
        eventTranslation[name]?.let { return it }
        className?.let { cls ->
            eventTranslation[cls]?.let { type ->
                return "$type: $name"
            }
        }
        return name
    }

    // Load the main page with daily event tabs
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val tabs = document.select("div.tabcontent")

        if (tabs.isEmpty()) throw ErrorLoadingException("No schedule found")

        return HomePageResponse(tabs.mapIndexed { index, tab ->
            val polishDayName = document.select("button.tablinks")[index].text().uppercase()
            val englishDayName = dayTranslation[polishDayName] ?: polishDayName
            val events = tab.select("td").mapNotNull { td ->
                val linkElement = td.selectFirst("a") ?: return@mapNotNull null
                val href = fixUrl(linkElement.attr("href"))
                val rawName = linkElement.text().trim()
                val className = linkElement.className().takeIf { it.isNotEmpty() }
                val translatedName = translateEventName(rawName, className)
                val time = td.text().substringBefore(" ").trim()
                LiveSearchResponse(
                    name = "$time - $translatedName",
                    url = href,
                    apiName = this@StrimsyStreaming.name,
                    type = TvType.Live,
                    posterUrl = null
                )
            }
            HomePageList(englishDayName, events, isHorizontalImages = false)
        })
    }

    // Load event details
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val rawTitle = document.selectFirst("title")?.text()?.substringBefore(" - STRIMS")?.trim()
            ?: url.substringAfterLast("/").substringBefore(".php").replace("-", " ")
        val className = document.selectFirst("iframe")?.parent()?.selectFirst("a")?.className()
        val translatedTitle = translateEventName(rawTitle, className)

        return LiveStreamLoadResponse(
            name = translatedTitle,
            url = url,
            apiName = this.name,
            dataUrl = url,
            posterUrl = null,
            plot = "Live stream from Strimsy"
        )
    }

    // Extract multiple stream URLs from the event page
    private suspend fun extractVideoLinks(
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url).document
        val iframeBase = document.selectFirst("iframe")?.attr("src")?.let { fixUrl(it) }
            ?: return

        // Quality options from the page
        val qualityLinks = document.select("font a").mapIndexed { index, link ->
            val qualityName = when (index) {
                0 -> "HD"
                1 -> "Full HD 1"
                2 -> "Full HD 2"
                3 -> "Full HD 3"
                else -> "Stream ${index + 1}"
            }
            val sourceParam = link.attr("href").substringAfter("?source=").takeIf { it.isNotEmpty() } ?: (index + 1).toString()
            Pair(qualityName, "$url?source=$sourceParam")
        }

        // Headers to mimic browser
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "en-GB,en-US;q=0.9,en;q=0.8",
            "Referer" to url,
            "Origin" to "https://strimsy.top"
        )

        // Process each quality option
        qualityLinks.forEach { (qualityName, qualityUrl) ->
            try {
                // Follow the quality URL
                val qualityResponse = app.get(qualityUrl, referer = url, headers = headers)
                val qualityIframe = qualityResponse.document.selectFirst("iframe")?.attr("src")?.let { fixUrl(it) } ?: iframeBase

                // Follow the iframe URL with proper headers
                val streamResponse = app.get(
                    qualityIframe,
                    referer = qualityUrl,
                    headers = headers,
                    allowRedirects = true
                )

                // Check for .m3u8 in redirect or content
                var finalStreamUrl = streamResponse.url.takeIf { it.contains(".m3u8") }
                    ?: extractStreamUrlFromScript(streamResponse.document)

                // Retry with specific Accept header if no .m3u8 found
                if (finalStreamUrl == null || !finalStreamUrl.contains(".m3u8")) {
                    val deeperResponse = app.get(
                        qualityIframe,
                        referer = qualityUrl,
                        headers = headers.plus("Accept" to "application/vnd.apple.mpegurl")
                    )
                    finalStreamUrl = deeperResponse.url.takeIf { it.contains(".m3u8") }
                        ?: extractStreamUrlFromScript(deeperResponse.document)
                }

                if (finalStreamUrl?.contains(".m3u8") == true) {
                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = qualityName,
                            url = finalStreamUrl,
                            referer = qualityIframe,
                            quality = when {
                                qualityName.contains("Full HD") -> Qualities.FHD.value
                                qualityName.contains("HD") -> Qualities.HD.value
                                else -> Qualities.Unknown.value
                            },
                            isM3u8 = true,
                            headers = headers
                        )
                    )
                }
            } catch (e: Exception) {
                // Skip failed quality options silently
            }
        }
    }

    // Extract stream URL from JavaScript
    private fun extractStreamUrlFromScript(document: Document): String? {
        val scripts = document.select("script")
        val streamScript = scripts.find { it.data().contains("src=") || it.data().contains("file:") || it.data().contains(".m3u8") }
            ?: return null

        val scriptData = streamScript.data()
        return when {
            scriptData.contains("eval(") -> {
                val unpacked = getAndUnpack(scriptData)
                unpacked.substringAfter("src=\"")?.substringBefore("\"")
                    ?: unpacked.substringAfter("file: \"")?.substringBefore("\"")
                    ?: unpacked.findM3u8Url()
            }
            else -> scriptData.substringAfter("src=\"")?.substringBefore("\"")
                ?: scriptData.substringAfter("file: \"")?.substringBefore("\"")
                ?: scriptData.findM3u8Url()
        } ?: document.selectFirst("source")?.attr("src")
    }

    // Helper to find .m3u8 in text
    private fun String.findM3u8Url(): String? {
        val regex = Regex("https?://[^\\s\"']+\\.m3u8(?:\\?[^\\s\"']*)?")
        return regex.find(this)?.value
    }

    // Load all links for an event
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        extractVideoLinks(data, callback)
        return true
    }

    // Handle Cloudflare protection
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                return cfKiller.intercept(chain)
            }
        }
    }
}
