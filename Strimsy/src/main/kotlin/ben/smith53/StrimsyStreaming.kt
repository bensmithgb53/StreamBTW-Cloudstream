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
            return newHomePageResponse(emptyList())
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
        return newHomePageResponse(homePageLists)
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
                        isM3u8 = fixedIframe.contains("m3u8")
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

// Assuming StrimsyExtractor is a separate file, here's its updated version
class StrimsyExtractor : ExtractorApi() {
    override val name = "Strimsy"
    override val mainUrl = "https://strimsy.top"
    override val requiresReferer = true
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    private val cookie = "PHPSESSID=sr2ufaf1h3ha63dge62ahob"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("Strimsy", "Extractor called with URL: $url, Referer: $referer")
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to (referer ?: url),
            "Cookie" to cookie
        )
        Log.d("Strimsy", "Headers: $headers")

        try {
            Log.d("Strimsy", "Fetching stream page: $url")
            val response = app.get(url, headers = headers).text
            Log.d("Strimsy", "Stream page response length: ${response.length}")

            // Extract stream ID
            val streamIdMatch = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                .find(response)
            if (streamIdMatch == null) {
                Log.w("Strimsy", "No stream ID found in $url")
                return
            }
            val streamId = streamIdMatch.groupValues[0]
            Log.d("Strimsy", "Found stream ID: $streamId")

            // Hypothesize API call
            val apiUrl = "$url?type=live"
            Log.d("Strimsy", "Fetching API URL: $apiUrl")
            val apiResponse = app.get(apiUrl, headers = headers).text
            Log.d("Strimsy", "API response length: ${apiResponse.length}")

            // Extract m3u8 path
            val m3u8PathMatch = Regex("/[a-zA-Z0-9\\-]+\\.m3u8").find(apiResponse)
            if (m3u8PathMatch == null) {
                Log.w("Strimsy", "No m3u8 path found in API response for $url")
                return
            }
            val m3u8Path = m3u8PathMatch.groupValues[0]
            Log.d("Strimsy", "Found m3u8 path: $m3u8Path")

            // Construct final m3u8 URL
            val baseCdn = "https://ve16o28z6o6dszgkty3jni6ulo3ba9jt.global.ssl.fastly.net"
            val finalUrl = "$baseCdn/v3/fragment/$streamId/tracks-v1a1/$m3u8Path"
            Log.d("Strimsy", "Constructed m3u8 URL: $finalUrl")

            // Verify and callback
            callback.invoke(
                ExtractorLink(
                    name,
                    name,
                    finalUrl,
                    referer ?: url,
                    true,
                    Qualities.Unknown.value,
                    headers = headers
                )
            )
            Log.d("Strimsy", "ExtractorLink callback invoked for $finalUrl")
        } catch (e: Exception) {
            Log.e("Strimsy", "Error extracting stream from $url: ${e.message}", e)
        }
    }
}
