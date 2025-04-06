package ben.smith53

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document

class StreamBTW : MainAPI() {
    override var lang = "en"
    override var mainUrl = "https://streambtw.com"
    override var name = "StreamBTW"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)
    private val cfKiller = CloudflareKiller()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val cards = document.select(".card")

        if (cards.isEmpty()) throw ErrorLoadingException("No streams found")

        val streams = cards.mapNotNull { card ->
            val titleElem = card.selectFirst(".card-title") ?: card.selectFirst("h5")
            val title = titleElem?.text()?.trim() ?: "Unknown Event"

            val teamsElem = card.selectFirst(".card-text") ?: card.selectFirst("p")
            val teams = teamsElem?.text()?.trim() ?: ""
            val fullTitle = if (teams.isNotEmpty()) "$title - $teams" else title

            val imgElem = card.selectFirst("img.league-logo") ?: card.selectFirst("img")
            val posterUrl = imgElem?.attr("src")?.let { 
                if (it.startsWith("http")) it else "$mainUrl$it"
            }

            val linkElem = card.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val url = if (linkElem.startsWith("http")) linkElem else "$mainUrl$linkElem"

            LiveSearchResponse(
                name = fullTitle,
                url = url,
                apiName = this@StreamBTW.name,
                type = TvType.Live,
                posterUrl = posterUrl
            )
        }

        return HomePageResponse(listOf(
            HomePageList(
                name = "Live Streams",
                list = streams,
                isHorizontalImages = true
            )
        ))
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val titleElem = document.selectFirst(".card-title") ?: document.selectFirst("h5")
        val title = titleElem?.text()?.trim() ?: "Unknown Event"

        val teamsElem = document.selectFirst(".card-text") ?: document.selectFirst("p")
        val teams = teamsElem?.text()?.trim() ?: ""
        val fullTitle = if (teams.isNotEmpty()) "$title - $teams" else title

        val imgElem = document.selectFirst("img.league-logo") ?: document.selectFirst("img")
        val posterUrl = imgElem?.attr("src")?.let { 
            if (it.startsWith("http")) it else "$mainUrl$it"
        }

        return LiveStreamLoadResponse(
            name = fullTitle,
            url = url,
            apiName = this.name,
            dataUrl = url,
            posterUrl = posterUrl,
            plot = teams
        )
    }

    private suspend fun getStreamUrl(document: Document): String? {
        val m3u8Pattern = "https?://[^\\s]+?\\.m3u8".toRegex()
        return document.html().let { html ->
            m3u8Pattern.find(html)?.value
        }
    }

    private suspend fun extractVideoLinks(
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url).document
        val streamUrl = getStreamUrl(document) ?: return

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "StreamBTW",
                url = streamUrl
            ) {
                referer = url
                quality = Qualities.Unknown.value
                isM3u8 = true
            }
        )

        document.select("iframe[src]").forEach { iframe ->
            val iframeUrl = iframe.attr("src").let { 
                if (it.startsWith("http")) it else "$mainUrl$it"
            }
            val iframeDoc = app.get(iframeUrl, referer = url).document
            getStreamUrl(iframeDoc)?.let { iframeStream ->
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "StreamBTW (iframe)",
                        url = iframeStream
                    ) {
                        referer = iframeUrl
                        quality = Qualities.Unknown.value
                        type=ExtractorLinkType.M3u8 = true
                    }
                )
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        extractVideoLinks(data, callback)
        return true
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                return cfKiller.intercept(chain)
            }
        }
    }
}