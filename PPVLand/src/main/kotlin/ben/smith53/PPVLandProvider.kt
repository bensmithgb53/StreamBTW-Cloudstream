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
import java.io.IOException
import java.net.UnknownHostException
import java.util.zip.GZIPInputStream

class PPVLandProvider : MainAPI() {
    // Try known domains in order. If one is down, the provider will try the next.
    private val candidateHosts = listOf(
        "https://ppv.wtf",
        "https://ppv.to",
        "https://ppv.land"
    )

    override var mainUrl = candidateHosts.first()
    override var name = "PPV Land"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"
    override val hasMainPage = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val hasDownloadSupport = false
    override val instantLinkLoading = true

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"
    private val headers = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "*/*",
        "Connection" to "keep-alive",
        "Accept-Language" to "en-US,en;q=0.5",
        "X-FS-Client" to "FS WebClient 1.0"
    )

    companion object {
        private const val posterUrl = "https://ppv.land/assets/img/ppvland.png"
    }

    private suspend fun fetchEvents(): List<HomePageList> {
        var decompressedText: String? = null
        var usedHost: String? = null

        // Try each host until one returns (or until all fail)
        for (host in candidateHosts) {
            val apiUrl = "$host/api/streams"
            println("PPV provider: trying $apiUrl")
            try {
                val resp = app.get(apiUrl, headers = headers, timeout = 15)
                println("Response code from $apiUrl: ${resp.code}")
                usedHost = host

                decompressedText = if (resp.headers["Content-Encoding"]?.contains("gzip", true) == true) {
                    GZIPInputStream(resp.body.byteStream()).bufferedReader().use { it.readText() }
                } else {
                    resp.text
                }
                // we got something — break and parse it (even if not 200, we'll parse to show a proper error)
                break
            } catch (e: UnknownHostException) {
                println("Host not resolvable: $host — ${e.message}")
            } catch (e: IOException) {
                println("IO error fetching $apiUrl: ${e.message}")
            } catch (e: Exception) {
                println("Error fetching $apiUrl: ${e.message}")
            }
        }

        if (decompressedText.isNullOrBlank() || usedHost == null) {
            return listOf(
                HomePageList(
                    name = "Offline / Host resolution error",
                    list = listOf(
                        newLiveSearchResponse(
                            "Cannot reach PPV servers. Tried: ${candidateHosts.joinToString(", ")}",
                            mainUrl
                        )
                    ),
                    isHorizontalImages = false
                )
            )
        }

        try {
            val jsonRoot = JSONObject(decompressedText!!)
            val categoryMap = mutableMapOf<String, MutableList<LiveSearchResponse>>()

            // Several possible JSON shapes — handle common ones
            if (jsonRoot.has("streams")) {
                val streamsArray = jsonRoot.getJSONArray("streams")
                for (i in 0 until streamsArray.length()) {
                    val categoryObj = streamsArray.getJSONObject(i)
                    if (categoryObj.has("category") && categoryObj.has("streams")) {
                        val categoryName = categoryObj.optString("category", "Live")
                        val streams = categoryObj.getJSONArray("streams")
                        for (j in 0 until streams.length()) {
                            val s = streams.getJSONObject(j)
                            val eventName = s.optString("name", "Unknown")
                            val streamId = s.optString("id", s.optString("uuid", ""))
                            val poster = s.optString("poster", posterUrl).replace("\\/", "/")
                            if (poster.contains("data:image")) continue
                            categoryMap.getOrPut(categoryName) { mutableListOf() }.add(
                                LiveSearchResponse(
                                    name = eventName,
                                    url = streamId,
                                    apiName = this.name,
                                    posterUrl = poster
                                )
                            )
                        }
                    } else {
                        // fallback: treat item itself as stream
                        val eventName = categoryObj.optString("name", "Unknown")
                        val streamId = categoryObj.optString("id", categoryObj.optString("uuid", ""))
                        val poster = categoryObj.optString("poster", posterUrl).replace("\\/", "/")
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
                    }
                }
            } else if (jsonRoot.has("data") && jsonRoot.get("data") is JSONArray) {
                val arr = jsonRoot.getJSONArray("data")
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
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
                }
            } else if (jsonRoot.has("data") && jsonRoot.get("data") is JSONObject) {
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
                // if unknown structure, provide a single message entry
            }

            val homeLists = categoryMap.map { (name, events) ->
                HomePageList(name = name, list = events, isHorizontalImages = false)
            }.toMutableList()

            if (homeLists.isEmpty()) {
                homeLists.add(
                    HomePageList(
                        name = "Live",
                        list = listOf(newLiveSearchResponse("No events found", mainUrl)),
                        isHorizontalImages = false
                    )
                )
            }

            println("PPV provider: found ${homeLists.size} categories via $usedHost")
            return homeLists
        } catch (e: Exception) {
            println("Error parsing streams JSON: ${e.message}")
            return listOf(
                HomePageList(
                    name = "Error",
                    list = listOf(newLiveSearchResponse("Failed to parse PPV API: ${e.message}", mainUrl)),
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
        // normalize to id
        val streamId = url.substringAfterLast("/").substringAfterLast(":")
        var decompressedText: String? = null
        var usedHost: String? = null

        for (host in candidateHosts) {
            val apiUrl = "$host/api/streams/$streamId"
            println("PPV provider: trying $apiUrl")
            try {
                val resp = app.get(apiUrl, headers = headers, timeout = 15)
                println("Response code from $apiUrl: ${resp.code}")
                usedHost = host

                decompressedText = if (resp.headers["Content-Encoding"]?.contains("gzip", true) == true) {
                    GZIPInputStream(resp.body.byteStream()).bufferedReader().use { it.readText() }
                } else {
                    resp.text
                }
                break
            } catch (e: UnknownHostException) {
                println("Host not resolvable: $host — ${e.message}")
            } catch (e: IOException) {
                println("IO error fetching $apiUrl: ${e.message}")
            } catch (e: Exception) {
                println("Error fetching $apiUrl: ${e.message}")
            }
        }

        if (decompressedText.isNullOrBlank() || usedHost == null) {
            throw Exception("Unable to reach PPV servers. Tried: ${candidateHosts.joinToString(", ")}")
        }

        val json = JSONObject(decompressedText!!)
        if (json.has("success") && !json.optBoolean("success", true)) {
            throw Exception("API Error: ${json.optString("error", "Unknown error")}")
        }

        var m3u8Url: String? = null
        val dataObj = if (json.has("data") && json.get("data") is JSONObject) json.getJSONObject("data") else null

        if (dataObj != null) {
            m3u8Url = dataObj.optString("m3u8", null)
        }
        if (m3u8Url.isNullOrBlank()) {
            m3u8Url = json.optString("m3u8", null)
        }

        if (m3u8Url.isNullOrBlank()) {
            val sourcesArray = dataObj?.optJSONArray("sources") ?: json.optJSONArray("sources")
            if (sourcesArray != null) {
                for (i in 0 until sourcesArray.length()) {
                    try {
                        val s = sourcesArray.getJSONObject(i)
                        val stype = s.optString("type", "")
                        val sdata = s.optString("data", "")
                        if (stype.equals("iframe", true) || stype.equals("embed", true)) {
                            if (sdata.isNotBlank()) {
                                try {
                                    val embedResp = app.get(sdata, headers = headers, referer = "$usedHost/", timeout = 15)
                                    val embedText = if (embedResp.headers["Content-Encoding"]?.contains("gzip", true) == true) {
                                        GZIPInputStream(embedResp.body.byteStream()).bufferedReader().use { it.readText() }
                                    } else {
                                        embedResp.text
                                    }
                                    val regex = Regex("""https?:\/\/[^\s'"]+\.m3u8[^\s'"]*""")
                                    val found = regex.find(embedText)
                                    if (found != null) {
                                        m3u8Url = found.value
                                        break
                                    }
                                    val jwRegex = Regex("""playlist\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
                                    val jwFound = jwRegex.find(embedText)
                                    if (jwFound != null) {
                                        m3u8Url = jwFound.groupValues[1]
                                        break
                                    }
                                } catch (e: Exception) {
                                    println("Failed to fetch/parse embed $sdata: ${e.message}")
                                }
                            }
                        } else {
                            val candidate = s.optString("file", s.optString("url", s.optString("data", "")))
                            if (candidate.contains(".m3u8")) {
                                m3u8Url = candidate
                                break
                            }
                        }
                    } catch (e: Exception) {
                        // continue
                    }
                }
            }
        }

        val streamName = dataObj?.optString("name") ?: json.optString("name", "PPV Stream $streamId")

        if (m3u8Url.isNullOrBlank()) {
            throw Exception("No m3u8 URL found in response or embed pages")
        }

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