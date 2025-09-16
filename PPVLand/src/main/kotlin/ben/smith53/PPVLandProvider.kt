Nice — thanks for the details. I analyzed the files you uploaded (the api/streams/11432.html JSON and the embed/.../2886-wsh.html embed page) and updated your Kotlin provider + extractor to handle the site changes:

The API no longer always returns an m3u8; instead data.sources commonly contains an iframe (an embed URL) which in turn contains the real m3u8 (inside JavaScript such as a JWPlayer config or playlist: [{ file: "https://...index.m3u8" }]).

Poster image URLs are escaped (e.g. https:\/\/ppv.to\/assets\/...) and sometimes relative. The provider now unescapes and normalizes poster URLs.

The provider no longer fails when m3u8 is empty: it falls back to sources -> iframe or data fields and returns that embed URL as the stream target so the extractor can fetch the actual .m3u8.

The extractor now supports both: (A) calling the /api/streams/{id} endpoint and extracting data.m3u8 or data.sources[*].data (iframe), and (B) fetching embed pages (iframe URLs like https://ppvs.su/embed/...) and extracting .m3u8 links from the embed HTML/JS using robust regexes that look for jwplayer / file: / playlist / direct .m3u8.

Added safe unescaping and small resilience improvements (try-catch and blank-checks).


Below are the updated Kotlin sources. Replace your existing files with these versions.


---

1) PPVLandProvider.kt (updated)

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
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.util.zip.GZIPInputStream

class PPVLandProvider : MainAPI() {
    override var mainUrl = "https://ppv.wtf"
    override var name = "PPV Land"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"
    override val hasMainPage = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val hasDownloadSupport = false
    override val instantLinkLoading = true

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"
    private val headers = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "*/*",
        "Connection" to "keep-alive",
        "Accept-Language" to "en-US,en;q=0.5",
        "X-FS-Client" to "FS WebClient 1.0",
        // Keep cookie if you want, but not strictly required for many endpoints:
        "Cookie" to "cf_clearance=..."
    )

    companion object {
        private const val posterUrl = "https://ppv.land/assets/img/ppvland.png"
    }

    private fun normalizePoster(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        // Unescape JSON-escaped slashes and fix relative paths
        var p = raw.replace("\\/", "/")
        if (p.startsWith("/")) {
            p = "$mainUrl$p"
        }
        return p
    }

    private suspend fun fetchEvents(): List<HomePageList> {
        val apiUrl = "$mainUrl/api/streams"
        try {
            val response = app.get(apiUrl, headers = headers, timeout = 15)
            val decompressedText = if (response.headers["Content-Encoding"] == "gzip") {
                GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
            } else {
                response.text
            }

            if (response.code != 200) {
                return listOf(
                    HomePageList(
                        name = "API Error",
                        list = listOf(
                            LiveSearchResponse(
                                name = "API Failed",
                                url = mainUrl,
                                apiName = this.name,
                                posterUrl = posterUrl
                            )
                        ),
                        isHorizontalImages = false
                    )
                )
            }

            val json = JSONObject(decompressedText)
            val streamsArray = json.optJSONArray("streams") ?: return emptyList()

            val categoryMap = mutableMapOf<String, MutableList<LiveSearchResponse>>()

            for (i in 0 until streamsArray.length()) {
                val categoryData = streamsArray.getJSONObject(i)
                val categoryName = categoryData.optString("category", "Other")
                val streams = categoryData.optJSONArray("streams") ?: continue

                val categoryEvents = categoryMap.getOrPut(categoryName) { mutableListOf() }

                for (j in 0 until streams.length()) {
                    val stream = streams.getJSONObject(j)
                    val eventName = stream.optString("name", "Unknown")
                    val streamId = stream.optString("id", "")
                    val posterRaw = stream.optString("poster", "")
                    val poster = normalizePoster(posterRaw) ?: posterUrl
                    val startsAt = stream.optLong("starts_at", 0L)

                    // Accept events even when poster is a data URI (some entries use inline images),
                    // but prefer external images if available.
                    val event = LiveSearchResponse(
                        name = eventName,
                        url = streamId, // keep the stream ID; extractor will resolve API/embed
                        apiName = this.name,
                        posterUrl = poster
                    )
                    categoryEvents.add(event)
                }
            }

            val homePageLists = categoryMap.map { (name, events) ->
                HomePageList(
                    name = name,
                    list = events,
                    isHorizontalImages = false
                )
            }.toMutableList()

            return homePageLists
        } catch (e: Exception) {
            return listOf(
                HomePageList(
                    name = "Error",
                    list = listOf(
                        newLiveSearchResponse("Failed to load events: ${e.message}", mainUrl)
                    ),
                    isHorizontalImages = false
                )
            )
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePageLists = fetchEvents()
        return newHomePageResponse(homePageLists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val homePageLists = fetchEvents()
        return homePageLists.flatMap { it.list }.filter {
            query.lowercase().replace(" ", "") in it.name.lowercase().replace(" ", "")
        }
    }

    override suspend fun load(url: String): LoadResponse {
        // url comes in as stream id or something similar; normalize
        val streamId = url.substringAfterLast("/").substringAfterLast(":").trim()
        val apiUrl = "$mainUrl/api/streams/$streamId"
        val response = app.get(apiUrl, headers = headers, timeout = 15)

        val decompressedText = if (response.headers["Content-Encoding"] == "gzip") {
            GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
        } else {
            response.text
        }

        if (response.code != 200) {
            throw Exception("Failed to load stream details: HTTP ${response.code}")
        }

        val json = JSONObject(decompressedText)
        val dataObj = json.optJSONObject("data")
        val streamName = dataObj?.optString("name") ?: json.optString("name", "Stream $streamId")

        // try direct m3u8 first
        var m3u8Url: String? = dataObj?.optString("m3u8")?.takeIf { it.isNotBlank() }
        if (m3u8Url.isNullOrBlank()) {
            // check top-level
            m3u8Url = json.optString("m3u8").takeIf { it.isNotBlank() }
        }

        // If still missing, check sources[] -> prefer iframe/data types
        if (m3u8Url.isNullOrBlank()) {
            val sources = dataObj?.optJSONArray("sources")
            if (sources != null) {
                for (i in 0 until sources.length()) {
                    val s = sources.getJSONObject(i)
                    val stype = s.optString("type", "")
                    val sdata = s.optString("data", "")
                    // If data contains a direct m3u8, use it; otherwise if it's an embed iframe URL, use that URL
                    if (sdata.contains(".m3u8")) {
                        m3u8Url = sdata
                        break
                    } else if (stype.equals("iframe", true) || sdata.contains("/embed/")) {
                        m3u8Url = sdata
                        break
                    }
                }
            }
        }

        if (m3u8Url.isNullOrBlank()) {
            // last attempt: check "sources" top-level
            val sourcesTop = json.optJSONArray("sources")
            if (sourcesTop != null) {
                for (i in 0 until sourcesTop.length()) {
                    val s = sourcesTop.getJSONObject(i)
                    val sdata = s.optString("data", "")
                    if (sdata.contains(".m3u8")) {
                        m3u8Url = sdata
                        break
                    } else if (sdata.contains("/embed/")) {
                        m3u8Url = sdata
                        break
                    }
                }
            }
        }

        if (m3u8Url.isNullOrBlank()) {
            throw Exception("No m3u8 or embed source found for stream $streamId")
        }

        // Unescape any escaped slashes
        m3u8Url = m3u8Url.replace("\\/", "/")

        return newLiveStreamLoadResponse(streamName, m3u8Url, m3u8Url)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data is expected to be an m3u8 or embed URL; return as M3U8 link and allow extractor to refine if needed
        callback.invoke(
            newExtractorLink(
                this.name,
                "PPVLand",
                url = data,
                ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}


---

2) PPVLandExtractor.kt (updated)

package ben.smith53.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class PPVLandExtractor : ExtractorApi() {
    override val name = "PPVLandExtractor"
    override val mainUrl = "https://ppv.to"
    override val requiresReferer = true

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"
    private val HEADERS = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Encoding" to "gzip, deflate, br, zstd",
        "Connection" to "keep-alive",
        "Accept-Language" to "en-US,en;q=0.5",
        "X-FS-Client" to "FS WebClient 1.0",
        "Cookie" to "cf_clearance=..."
    )

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            // If user passed an API stream URL (api/streams/{id}), fetch the JSON and try to find a usable URL
            if (url.contains("/api/streams/")) {
                val jsonText = app.get(url, headers = HEADERS, referer = referer ?: "$mainUrl/").text
                // Attempt basic JSON parse for data.m3u8 or sources[*].data
                val mapper = jacksonObjectMapper()
                val jsonData: Map<String, Any> = mapper.readValue(jsonText)
                val data = (jsonData["data"] as? Map<String, Any>) ?: (jsonData as? Map<String, Any>)
                // Try data["m3u8"]
                val m3u8 = (data?.get("m3u8") as? String)?.takeIf { it.isNotBlank() }
                if (!m3u8.isNullOrBlank()) {
                    return listOf(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = m3u8,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
                // Check for sources[*].data (iframe or playlist)
                val sourcesAny = data?.get("sources")
                if (sourcesAny is List<*>) {
                    for (s in sourcesAny) {
                        if (s is Map<*, *>) {
                            val sdata = s["data"] as? String
                            val stype = (s["type"] as? String) ?: ""
                            if (!sdata.isNullOrBlank()) {
                                // If sdata looks like .m3u8, return that; else treat as embed URL to be fetched
                                if (sdata.contains(".m3u8")) {
                                    return listOf(
                                        newExtractorLink(
                                            source = this.name,
                                            name = this.name,
                                            url = sdata,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.referer = "$mainUrl/"
                                            this.quality = Qualities.Unknown.value
                                        }
                                    )
                                } else if (stype.equals("iframe", true) || sdata.contains("/embed/")) {
                                    // Fall through: fetch embed page below by setting url to sdata
                                    return extractFromEmbed(sdata, referer)
                                }
                            }
                        }
                    }
                }
            }

            // If the provided url looks like an embed URL (contains "/embed/") — fetch that page and locate m3u8
            if (url.contains("/embed/") || url.contains(".m3u8").not()) {
                return extractFromEmbed(url, referer)
            }

            // If it's already an m3u8, return that
            if (url.contains(".m3u8")) {
                return listOf(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer ?: "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }

            return null
        } catch (e: Exception) {
            return null
        }
    }

    // Helper: fetch embed page and search for .m3u8 occurrences in JS / jwplayer config
    private suspend fun extractFromEmbed(embedUrl: String, referer: String?): List<ExtractorLink>? {
        try {
            val fixed = embedUrl.replace("\\/", "/")
            val resp = app.get(fixed, headers = HEADERS, referer = referer ?: "$mainUrl/").text

            // Try several regex patterns to find an .m3u8 url inside javascript configuration
            // 1) search for file: "https://...m3u8"
            val patterns = listOf(
                "\"(https?://[^\"]+?\\.m3u8[^\"]*)\"",
                "'(https?://[^']+?\\.m3u8[^']*)'",
                "file\\s*:\\s*\"(https?://[^\"]+?\\.m3u8[^\"]*)\"",
                "file\\s*:\\s*'(https?://[^']+?\\.m3u8[^']*)'",
                "playlist\\

