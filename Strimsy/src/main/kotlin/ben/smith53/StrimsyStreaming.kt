package ben.smith53

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.*

class StrimsyStreaming : MainAPI() {
    override var mainUrl = "https://strimsy.top"
    override var name = "StrimsyStreaming"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(
        TvType.Live
    )

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

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
        "nba" to "NBA",
        "hhokej" to "Hockey",
        "walki" to "Fighting"
    )

    private fun fixUrl(url: String, baseUrl: String = mainUrl): String {
        println("StrimsyStreaming: Fixing URL: $url with baseUrl: $baseUrl")
        val fixedUrl = if (url.startsWith("//")) {
            "https:$url"
        } else if (url.startsWith("/")) {
            "$baseUrl$url"
        } else if (url.startsWith("?team=")) {
            "$baseUrl/NBA/$url"
        } else if (url.startsWith("?source=")) {
            "$baseUrl/f1/$url"
        } else if (!url.startsWith("http")) {
            "$baseUrl/$url"
        } else {
            url
        }
        println("StrimsyStreaming: Fixed URL: $fixedUrl")
        return fixedUrl
    }

    private fun translateEventName(name: String, className: String?): String {
        println("StrimsyStreaming: Translating event name: $name with class: $className")
        val lowerName = name.lowercase(Locale.getDefault())
        val translated = if (eventTranslation.containsKey(lowerName)) {
            eventTranslation[lowerName]!!
        } else if (className != null && eventTranslation.containsKey(className.lowercase(Locale.getDefault()))) {
            "${eventTranslation[className.lowercase(Locale.getDefault())]}: $name"
        } else {
            name
        }
        println("StrimsyStreaming: Translated event name: $translated")
        return translated
    }

    private fun isChatIframe(iframeUrl: String): Boolean {
        val isChat = iframeUrl.contains("/layout/chat") || iframeUrl.contains("/chatWalki2.php") || iframeUrl.contains("/chatWalki1.php")
        println("StrimsyStreaming: Checking if iframe is chat: $iframeUrl -> $isChat")
        return isChat
    }

    private suspend fun getTeamPages(sportUrl: String, cookies: Map<String, String>): List<Pair<String, String>> {
        println("StrimsyStreaming: Fetching team pages for $sportUrl")
        val headersWithCookies = baseHeaders + mapOf("Cookie" to cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        val doc = app.get(sportUrl, headers = headersWithCookies).document
        val teamLinks = mutableListOf<Pair<String, String>>()
        doc.select("a[href^='?team=']").forEach { aTag ->
            val href = fixUrl(aTag.attr("href"))
            val teamName = aTag.previousElementSibling()?.text() ?: aTag.text()
            println("StrimsyStreaming: Found team link: $teamName -> $href")
            teamLinks.add(Pair(teamName, href))
        }
        println("StrimsyStreaming: Found ${teamLinks.size} team links for $sportUrl")
        return teamLinks
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("StrimsyStreaming: getMainPage called with page=$page, request=$request")
        // Fetch the main page and capture cookies
        val response = app.get(mainUrl, headers = baseHeaders)
        val cookies = response.cookies
        println("StrimsyStreaming: Cookies fetched: $cookies")
        val headersWithCookies = baseHeaders + mapOf("Cookie" to cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        val document = response.document
        println("StrimsyStreaming: Main page response: ${document.html().take(500)}...")

        val tabs = document.select("div.tabcontent")
        if (tabs.isEmpty()) throw ErrorLoadingException("No tabcontent found")

        val homePageLists = mutableListOf<HomePageList>()
        tabs.forEachIndexed { index, tab ->
            val tabButton = document.select("button.tablinks")[index]
            val polishDayName = tabButton.text().trim().uppercase(Locale.getDefault())
            val englishDayName = dayTranslation[polishDayName] ?: polishDayName
            println("StrimsyStreaming: Processing tab: $englishDayName ($polishDayName)")

            val events = mutableListOf<SearchResponse>()
            tab.select("td").forEach { td ->
                val linkElement = td.selectFirst("a") ?: return@forEach
                val href = fixUrl(linkElement.attr("href"))
                val rawName = linkElement.text().trim()
                val className = linkElement.attr("class").ifEmpty { null }
                val translatedName = translateEventName(rawName, className)
                val time = td.text().split(" ")[0].trim().ifEmpty { "Unknown Time" }
                val eventName = "$time - $translatedName"
                println("StrimsyStreaming: Found event: $eventName -> $href")

                if (href.contains("/NBA/") || href.contains("/NHL/")) {
                    val teamPages = getTeamPages(href, cookies)
                    teamPages.forEach { (teamName, teamUrl) ->
                        println("StrimsyStreaming: Adding team event: $eventName - $teamName -> $teamUrl")
                        events.add(
                            newLiveSearchResponse(
                                name = "$eventName - $teamName",
                                url = teamUrl,
                                type = TvType.Live
                            ) {
                                this.posterUrl = null
                            }
                        )
                    }
                } else {
                    println("StrimsyStreaming: Adding event: $eventName -> $href")
                    events.add(
                        newLiveSearchResponse(
                            name = eventName,
                            url = href,
                            type = TvType.Live
                        ) {
                            this.posterUrl = null
                        }
                    )
                }
            }

            if (events.isNotEmpty()) {
                println("StrimsyStreaming: Adding HomePageList for $englishDayName with ${events.size} events")
                homePageLists.add(HomePageList(englishDayName, events, true))
            }
        }

        println("StrimsyStreaming: Returning HomePageResponse with ${homePageLists.size} lists")
        return newHomePageResponse(homePageLists)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("StrimsyStreaming: loadLinks called with data=$data, isCasting=$isCasting")
        // Fetch the page and capture cookies
        val response = app.get(data, headers = baseHeaders)
        val cookies = response.cookies
        println("StrimsyStreaming: Cookies fetched: $cookies")
        val headersWithCookies = baseHeaders + mapOf("Cookie" to cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        val doc = response.document
        println("StrimsyStreaming: Page response: ${doc.html().take(500)}...")

        val iframes = doc.select("iframe")
        if (iframes.isEmpty()) {
            println("StrimsyStreaming: No iframes found on page $data")
            return false
        }

        var linksFound = false
        iframes.forEach { iframe ->
            val iframeUrl = iframe.attr("src").trim()
            if (iframeUrl.isEmpty() || isChatIframe(iframeUrl)) {
                println("StrimsyStreaming: Skipping iframe $iframeUrl (empty or chat)")
                return@forEach
            }

            val fixedIframeUrl = fixUrl(iframeUrl)
            println("StrimsyStreaming: Processing iframe $fixedIframeUrl")
            val extractor = StrimsyExtractor()
            extractor.cookies = cookies // Set the cookies on the extractor
            val links = extractor.getUrl(fixedIframeUrl, data)
            links?.forEach { link ->
                println("StrimsyStreaming: Found link ${link.url}")
                callback(link)
                linksFound = true
            } ?: println("StrimsyStreaming: No links found for iframe $fixedIframeUrl")
        }

        println("StrimsyStreaming: loadLinks returning $linksFound")
        return linksFound
    }
}
