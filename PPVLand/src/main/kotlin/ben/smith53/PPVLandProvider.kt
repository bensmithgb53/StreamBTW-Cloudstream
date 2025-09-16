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
    override var mainUrl = "https://ppv.to"
    override var name = "PPV Land"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"
    override val hasMainPage = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val hasDownloadSupport = false
    override val instantLinkLoading = true

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"

    private fun generateXCID(): String {
        // Generate a more realistic client ID based on browser fingerprinting
        val timestamp = System.currentTimeMillis()
        val random = (Math.random() * 1000000).toInt()
        val userAgentHash = userAgent.hashCode().toString(16)
        return "${userAgentHash}${timestamp}${random}".take(64).padEnd(64, '0')
    }

    private fun getHeaders(): Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "*/*",
        "Connection" to "keep-alive",
        "Accept-Language" to "en-US,en;q=0.5",
        "X-FS-Client" to "FS WebClient 1.0",
        "X-CID" to generateXCID()
    )

    companion object {
        private const val posterUrl = "https://ppv.land/assets/img/ppvland.png"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePageLists = fetchEvents()
        println("Returning ${homePageLists.size} categories to Cloudstream")
        return newHomePageResponse(homePageLists)
    }

    private suspend fun fetchEvents(): List<HomePageList> {
        val apiUrl = "$mainUrl/api/streams"
        println("Fetching all streams from: $apiUrl")
        println("Request Headers: ${getHeaders()}")
        try {
            val response = app.get(apiUrl, headers = getHeaders(), timeout = 15)
            println("Main API Status Code: ${response.code}")
            // Decompress gzip response
            val decompressedText = if (response.headers["Content-Encoding"] == "gzip") {
                GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
            } else {
                response.text
            }
            println("Main API Response Body: $decompressedText")
            if (response.code != 200) {
                println("API Error: Received status code ${response.code}")
                return listOf(
                    HomePageList(
                        name = "API Error",
                        list = listOf(
                            newLiveSearchResponse(
                                name = "API Failed",
                                url = mainUrl,
                                type = TvType.Live
                            ) {
                                posterUrl = posterUrl
                            }
                        ),
                        isHorizontalImages = false
                    )
                )
            }
            val json = JSONObject(decompressedText)
            val streamsArray = json.getJSONArray("streams")
            println("Found ${streamsArray.length()} categories")
            val categoryMap = mutableMapOf<String, MutableList<LiveSearchResponse>>()
            for (i in 0 until streamsArray.length()) {
                val categoryData = streamsArray.getJSONObject(i)
                val categoryName = categoryData.getString("category")
                val streams = categoryData.getJSONArray("streams")
                println("Processing Category: $categoryName with ${streams.length()} streams")
                val categoryEvents = categoryMap.getOrPut(categoryName) { mutableListOf() }
                for (j in 0 until streams.length()) {
                    val stream = streams.getJSONObject(j)
                    val eventName = stream.getString("name")
                    val streamId = stream.getString("id")
                    val poster = stream.getString("poster")
                    val startsAt = stream.getLong("starts_at")
                    println("Stream: $eventName, ID: $streamId, Starts At: $startsAt")
                    if (!poster.contains("data:image")) {
                        val event = newLiveSearchResponse(
                            name = eventName,
                            url = streamId,
                            type = TvType.Live
                        ) {
                            posterUrl = poster
                        }
                        categoryEvents.add(event)
                    }
                }
            }
            val homePageLists = categoryMap.map { (name, events) ->
                println("Adding category: $name with ${events.size} events")
                HomePageList(
                    name = name,
                    list = events,
                    isHorizontalImages = false
                )
            }.toMutableList()
            println("Total categories: ${homePageLists.size}")
            return homePageLists
        } catch (e: Exception) {
            println("fetchEvents failed: ${e.message}")
            return listOf(
                HomePageList(
                    name = "Error",
                    list = listOf(
                        newLiveSearchResponse("Failed to load events: ${e.message}", mainUrl, TvType.Live)
                    ),
                    isHorizontalImages = false
                )
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val homePageLists = fetchEvents()
        return homePageLists.flatMap { it.list }.filter { query.lowercase().replace(" ", "") in it.name.lowercase().replace(" ", "") }
    }

    override suspend fun load(url: String): LoadResponse {
        val streamId = url.substringAfterLast("/").substringAfterLast(":")
        val apiUrl = "$mainUrl/api/streams/$streamId"
        println("Fetching stream details for stream ID $streamId: $apiUrl")
        println("Request Headers: ${getHeaders()}")
        val response = app.get(apiUrl, headers = getHeaders(), timeout = 15)
        println("Stream API Status Code: ${response.code}")
        // Decompress gzip response
        val decompressedText = if (response.headers["Content-Encoding"] == "gzip") {
            GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
        } else {
            response.text
        }
        println("Stream API Response Body: $decompressedText")
        if (response.code != 200) {
            throw Exception("Failed to load stream details: HTTP ${response.code}")
        }
        val json = JSONObject(decompressedText)
        if (!json.optBoolean("success", true)) {
            throw Exception("API Error: ${json.optString("error", "Unknown error")}")
        }
        val data = json.optJSONObject("data") ?: throw Exception("No data field in response")
        val streamName = data.optString("name", "Stream $streamId")
        
        // Check for direct m3u8 URL first
        val m3u8Url = data.optString("m3u8")
        if (m3u8Url.isNotEmpty()) {
            println("Found direct m3u8 URL: $m3u8Url")
            return newLiveStreamLoadResponse(
                name = streamName,
                url = m3u8Url,
                dataUrl = m3u8Url
            )
        }
        
        // Check for sources array with iframe embeds
        val sources = data.optJSONArray("sources")
        if (sources != null && sources.length() > 0) {
            for (i in 0 until sources.length()) {
                val source = sources.getJSONObject(i)
                val sourceType = source.optString("type", "")
                val sourceData = source.optString("data", "")
                
                if (sourceType == "iframe" && sourceData.isNotEmpty()) {
                    println("Found iframe source: $sourceData")
                    // Use the iframe URL as the data URL - the extractor will handle resolving it
                    return newLiveStreamLoadResponse(
                        name = streamName,
                        url = sourceData,
                        dataUrl = sourceData
                    )
                }
            }
        }
        
        throw Exception("No valid stream source found in response")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Check if it's a direct m3u8 URL
        if (data.endsWith(".m3u8") || (data.contains("m3u8") && !data.contains("embed"))) {
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
            println("Provided direct m3u8 link: $data")
            return true
        }
        
        // If it's an iframe URL, extract the m3u8 URL directly
        if (data.contains("ppvs.su") || data.contains("/embed/")) {
            println("Extracting m3u8 from iframe URL: $data")
            try {
                val m3u8Url = extractM3u8FromIframe(data)
                if (m3u8Url != null) {
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            "PPVLand",
                            url = m3u8Url,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = data
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    println("Successfully extracted m3u8 URL: $m3u8Url")
                    return true
                }
            } catch (e: Exception) {
                println("Failed to extract m3u8 from iframe: ${e.message}")
            }
        }
        
        // Fallback: return the original URL
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
        println("Provided fallback link: $data")
        return true
    }
    
    private suspend fun extractM3u8FromIframe(iframeUrl: String): String? {
        try {
            println("Fetching iframe content from: $iframeUrl")
            val response = app.get(iframeUrl, headers = getHeaders(), referer = mainUrl)
            val html = response.text
            println("Iframe response length: ${html.length}")
            
            // Look for m3u8 URLs in JavaScript playlist configurations
            val playlistPatterns = listOf(
                Regex("""playlist:\s*\[\s*\{\s*file:\s*['"]([^'"]*\.m3u8[^'"]*)['"]"""),
                Regex("""file:\s*['"]([^'"]*\.m3u8[^'"]*)['"]"""),
                Regex("""['"]([^'"]*\.m3u8[^'"]*)['"]""")
            )
            
            for (pattern in playlistPatterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val m3u8Url = match.groupValues[1]
                    if (m3u8Url.isNotEmpty() && !m3u8Url.contains("preset-") && m3u8Url.startsWith("http")) {
                        println("Found m3u8 URL in iframe: $m3u8Url")
                        return m3u8Url
                    }
                }
            }
            
            // Look for general m3u8 URLs
            val m3u8Pattern = Regex("""["']([^"']*\.m3u8[^"']*)["']""")
            val m3u8Matches = m3u8Pattern.findAll(html)
            
            for (match in m3u8Matches) {
                val m3u8Url = match.groupValues[1]
                if (m3u8Url.isNotEmpty() && !m3u8Url.contains("preset-") && m3u8Url.startsWith("http")) {
                    println("Found m3u8 URL in iframe: $m3u8Url")
                    return m3u8Url
                }
            }
            
            println("No m3u8 URL found in iframe content")
            return null
        } catch (e: Exception) {
            println("Error extracting m3u8 from iframe: ${e.message}")
            return null
        }
    }
}
