package ben.smith53

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup

class PPVLandProvider : MainAPI() {
    override var mainUrl = "https://ppv.land"
    override var name = "PPV Land"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"  // English only
    override val hasMainPage = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val hasDownloadSupport = false
    override val instantLinkLoading = true

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    companion object {
        private const val posterUrl = "https://ppv.land/assets/img/ppvland.png"
    }

    private suspend fun fetchEvents(): List<LiveSearchResponse> {
        val response = app.get(mainUrl, headers = mapOf("User-Agent" to userAgent)).text
        val doc = Jsoup.parse(response)

        val events = mutableListOf<LiveSearchResponse>()

        // Live now section
        val liveSection = doc.select("#livecards .px-2.py-2")
        liveSection.forEach { card ->
            val linkTag = card.selectFirst("a.item-card")
            val imgTag = card.selectFirst("img.card-img-top")
            val titleTag = card.selectFirst("h5.card-title")

            if (linkTag != null && imgTag != null && titleTag != null) {
                val eventLink = mainUrl + linkTag.attr("href")
                val imgSrc = imgTag.attr("src")
                val eventName = titleTag.text().trim()

                if ("english" in eventLink.lowercase()) {
                    events.add(
                        LiveSearchResponse(
                            name = eventName,
                            url = eventLink,
                            apiName = this.name,
                            posterUrl = imgSrc
                        )
                    )
                }
            }
        }

        // Categories section
        val categoriesSection = doc.select("#categories .mt-5")
        categoriesSection.forEach { container ->
            val cards = container.select(".px-2.py-2")
            cards.forEach { card ->
                val linkTag = card.selectFirst("a.item-card")
                val imgTag = card.selectFirst("img.card-img-top")
                val titleTag = card.selectFirst("h5.card-title")

                if (linkTag != null && imgTag != null && titleTag != null) {
                    val eventLink = mainUrl + linkTag.attr("href")
                    val imgSrc = imgTag.attr("src")
                    val eventName = titleTag.text().trim()

                    if ("english" in eventLink.lowercase()) {
                        events.add(
                            LiveSearchResponse(
                                name = eventName,
                                url = eventLink,
                                apiName = this.name,
                                posterUrl = imgSrc
                            )
                        )
                    }
                }
            }
        }

        return events
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val events = fetchEvents()
        val homePageList = HomePageList(
            name = "Live Events",
            list = events,
            isHorizontalImages = false
        )
        return newHomePageResponse(listOf(homePageList), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val events = fetchEvents()
        return events.filter {
            query.lowercase().replace(" ", "") in it.name.lowercase().replace(" ", "")
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = Jsoup.parse(app.get(url, headers = mapOf("User-Agent" to userAgent)).text)
        val embedCode = doc.selectFirst("#embedcode")?.text()
        val embedUrl = embedCode?.let {
            Regex("src=\"([^\"]+)\"").find(it)?.groupValues?.get(1)
        } ?: throw Exception("Embed URL not found")

        return LiveStreamLoadResponse(
            name = url.substringAfterLast("/").replace("-", " ").capitalize(),
            url = url,
            apiName = this.name,
            dataUrl = embedUrl,  // Pass VidEmbed URL to extractor
            posterUrl = posterUrl
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return loadExtractor(data, mainUrl, subtitleCallback, callback)
    }
}
