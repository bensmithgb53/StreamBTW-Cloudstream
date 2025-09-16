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
import org.json.JSONArray
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
    private val headers = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "*/*",
        "Connection" to "keep-alive",
        "Accept-Language" to "en-US,en;q=0.5",
        "X-FS-Client" to "FS WebClient 1.0"
        // cookie intentionally omitted here; you can add if required
    )

    companion object {
        private const val posterUrl = "https://ppv.land/assets/img/ppvland.png"
    }

    private suspend fun decompressIfNeeded(bodyBytes: okio.BufferedSource, encodingHeader: String?): String {
        return try {
            if (encodingHeader?.contains("gzip", ignoreCase = true) == true) {
                GZIPInputStream(bodyBytes.inputStream()).bufferedReader().use { it.readText() }
            } else {
                bodyBytes.buffer.clone().readUtf8()
            }
        } catch (e: Exception) {
            bodyBytes.buffer.clone().readUtf8()
        }
    }

    private suspend fun fetchEvents(): List<HomePageList> {
        val apiUrl = "$mainUrl/api/streams"
        println("Fetching all streams from: $apiUrl")
        try {
            val response = app.get(apiUrl, headers = headers, timeout = 15)
            println("Main API Status Code: ${response.code}")

            val decompressedText = if (response.headers["Content-Encoding"] == "gzip") {
                GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
            } else {
                response.text
            }
            println("Main API Response (truncated): ${decompressedText.take(800)}")

            if (response.code != 200) {
                println("API Error: Received status code ${response.code}")
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

            // The site may return either:
            // 1) { streams: [ { category: "...", streams: [...] }, ... ] }
            // 2) or an array of streams / different structure.
            val jsonRoot = JSONObject(decompressedText)
            val homeLists = mutableListOf<HomePageList>()
            val categoryMap = mutableMapOf<String, MutableList<LiveSearchResponse>>()

            // If the server returns a top-level "streams" array of categories:
            if (jsonRoot.has("streams")) {
                val streamsArray = jsonRoot.getJSONArray("streams")
                for (i in 0 until streamsArray.length()) {
                    val categoryObj = streamsArray.getJSONObject(i)
                    // If it's the category wrapper
                    if (categoryObj.has("category") && categoryObj.has("streams")) {
                        val categoryName = categoryObj.optString("category", "Live")
                        val streams = categoryObj.getJSONArray("streams")
                        for (j in 0 until streams.length()) {
                            try {
                                val s = streams.getJSONObject(j)
                                val eventName = s.optString("name", "Unknown")
                                val streamId = s.optString("id", s.optString("uuid", ""))
                                val poster = s.optString("poster", posterUrl).replace("\\/", "/")
                                if (poster.contains("data:image")) continue
                                val ev = LiveSearchResponse(
                                    name = eventName,
                                    url = streamId,
                                    apiName = this.name,
                                    posterUrl = poster
                                )
                                categoryMap.getOrPut(categoryName) { mutableListOf() }.add(ev)
                            } catch (ie: Exception) {
                                // skip broken entry
                            }
                        }
                    } else {
                        // Fallback: treat each element as a stream
                        val possibleStreams = if (categoryObj.has("streams")) categoryObj.getJSONArray("streams") else JSONArray().put(categoryObj)
                        for (k in 0 until possibleStreams.length()) {
                            val s = possibleStreams.getJSONObject(k)
                            val eventName = s.optString("name", "Unknown")
                            val streamId = s.optString("id", s.optString("uuid", ""))
                            val poster = s.optString("poster", posterUrl).replace("\\/", "/")
                            if (poster.contains("data:image")) continue
                            val ev = LiveSearchResponse(
                                name = eventName,
                                url = streamId,
                                apiName = this.name,
                                posterUrl = poster
                            )
                            categoryMap.getOrPut("Live") { mutableListOf() }.add(ev)
                        }
                    }
                }
            } else if (jsonRoot.has("data") && jsonRoot.get("data") is JSONArray) {
                val arr = jsonRoot.getJSONArray("data")
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    val eventName = s.optString("name", "Unknown")
                    val streamId = s.optString("id", s.optString("uuid", ""))
                    val poster = s.optString("poster", posterUrl).replace("\\/", "/")
                    if (poster.contains("data:image")) continue
                    categoryMap.getOrPut("Live") { mutableListOf() }.add(
                        LiveSearchResponse(
                            name = eventName,
                            url = streamId,
                            apiName = this.name,
                            posterUrl = poster
                        )
                    )
                }
            } else {
                // If unexpected structure, attempt to treat the root as a single stream object
                if (jsonRoot.has("data") && jsonRoot.get("data") is JSONObject) {
                    val s = jsonRoot.getJSONObject("data")
                    val eventName = s.optString("name", "Unknown")
                    val streamId = s.optString("id", s.optString("uuid", ""))
                    val poster = s.optString("poster", posterUrl).replace("\\/", "/")
                    if (!poster.contains("data:image")) {
                        categoryMap.getOrPut("Live") { mutableListOf() }.add(
                            LiveSearchResponse(
                                name = eventName,
                                url = streamId,
                                apiName = this.name,
                                posterUrl = poster
                            )
                        )
                    }
                } else {
                    // give up: return empty
                }
            }

            categoryMap.forEach { (cat, events) ->
                homeLists.add(HomePageList(name = cat, list = events, isHorizontalImages = false))
            }

            if (homeLists.isEmpty()) {
                homeLists.add(
                    HomePageList(
                        name = "Live",
                        list = listOf(newLiveSearchResponse("No events found", mainUrl)),
                        isHorizontalImages = false
                    )
                )
            }

            println("Found categories: ${homeLists.size}")
            return homeLists
        } catch (e: Exception) {
            println("fetchEvents error: ${e.message}")
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
        println("Returning ${homePageLists.size} categories to Cloudstream")
        return newHomePageResponse(homePageLists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val homePageLists = fetchEvents()
        return homePageLists.flatMap { it.list }.filter {
            query.lowercase().replace(" ", "") in it.name.lowercase().replace(" ", "")
        }
    }

    override suspend fun load(url: String): LoadResponse {
        // url previously passed as id only; accept either id or full path
        val streamId = url.substringAfterLast("/").substringAfterLast(":")
        val apiUrl = "$mainUrl/api/streams/$streamId"
        println("Fetching stream details for $streamId -> $apiUrl")
        val response = app.get(apiUrl, headers = headers, timeout = 15)
        println("Stream API Status Code: ${response.code}")

        val decompressedText = if (response.headers["Content-Encoding"] == "gzip") {
            GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
        } else {
            response.text
        }
        println("Stream API Response (truncated): ${decompressedText.take(800)}")

        if (response.code != 200) {
            throw Exception("Failed to load stream details: HTTP ${response.code}")
        }

        val json = JSONObject(decompressedText)
        if (json.has("success") && !json.optBoolean("success", true)) {
            throw Exception("API Error: ${json.optString("error", "Unknown error")}")
        }

        // Try common places for m3u8:
        var m3u8Url: String? = null
        val dataObj = when {
            json.has("data") && json.get("data") is JSONObject -> json.getJSONObject("data")
            else -> null
        }

        if (dataObj != null) {
            m3u8Url = dataObj.optString("m3u8", null)
        }
        if (m3u8Url.isNullOrBlank()) {
            m3u8Url = json.optString("m3u8", null)
        }

        // If m3u8 is still blank, check "sources" array and follow iframe / embed entries
        if (m3u8Url.isNullOrBlank()) {
            val sourcesArray = dataObj?.optJSONArray("sources") ?: json.optJSONArray("sources")
            if (sourcesArray != null) {
                for (i in 0 until sourcesArray.length()) {
                    try {
                        val s = sourcesArray.getJSONObject(i)
                        val stype = s.optString("type", "")
                        val sdata = s.optString("data", "")
                        // If the source is an iframe/embed, fetch it and extract m3u8
                        if (stype.equals("iframe", ignoreCase = true) || stype.equals("embed", ignoreCase = true)) {
                            if (sdata.isNotBlank()) {
                                try {
                                    println("Following iframe/embed source: $sdata")
                                    val embedResp = app.get(sdata, headers = headers, referer = apiUrl, timeout = 15)
                                    val embedText = if (embedResp.headers["Content-Encoding"]?.contains("gzip", true) == true) {
                                        GZIPInputStream(embedResp.body.byteStream()).bufferedReader().use { it.readText() }
                                    } else {
                                        embedResp.text
                                    }
                                    // Try to extract any .m3u8 URL from the embed HTML/JS
                                    val regex = Regex("""https?:\/\/[^\s'"]+\.m3u8[^\s'"]*""")
                                    val found = regex.find(embedText)
                                    if (found != null) {
                                        m3u8Url = found.value
                                        println("Extracted m3u8 from embed: $m3u8Url")
                                        break
                                    }
                                    // JW player playlist possibility: playlist: [{ file: "URL" }]
                                    val jwRegex = Regex("""playlist\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
                                    val jwFound = jwRegex.find(embedText)
                                    if (jwFound != null) {
                                        m3u8Url = jwFound.groupValues[1]
                                        println("Found JW playlist m3u8: $m3u8Url")
                                        break
                                    }
                                } catch (e: Exception) {
                                    println("Failed to fetch/parse embed $sdata: ${e.message}")
                                }
                            }
                        } else {
                            // other types: 'playlist', 'hls', or direct source
                            val candidate = s.optString("file", s.optString("url", s.optString("data", "")))
                            if (candidate.contains(".m3u8")) {
                                m3u8Url = candidate
                                break
                            }
                        }
                    } catch (e: Exception) {
                        // ignore and continue
                    }
                }
            }
        }

        val streamName = dataObj?.optString("name") ?: json.optString("name", "PPV Stream $streamId")

        if (m3u8Url.isNullOrBlank()) {
            throw Exception("No m3u8 URL found in response or embed pages")
        }

        println("Final m3u8: $m3u8Url")
        return newLiveStreamLoadResponse(streamName, m3u8Url, m3u8Url)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
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
        println("Provided m3u8 link: $data")
        return true
    }
}