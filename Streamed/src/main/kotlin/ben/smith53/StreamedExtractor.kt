package ben.smith53.extractors

import android.content.Context
import android.util.Log
import ben.smith53.proxy.ProxyManager
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class StreamedExtractor : ExtractorApi() {
    override val name = "StreamedExtractor"
    override val mainUrl = "https://streamed.pk"
    override val requiresReferer = true

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/vnd.apple.mpegurl, */*",
        "Accept-Language" to "en-GB,en-US;q=0.9,en;q=0.8"
    )
    
    private var context: Context? = null
    private var proxyAddress: String? = null
    
    fun initialize(context: Context) {
        this.context = context
        // Start proxy if not already running
        if (!ProxyManager.isProxyRunning()) {
            proxyAddress = ProxyManager.startProxy(context)
            Log.d("StreamedExtractor", "Proxy initialized: $proxyAddress")
        } else {
            proxyAddress = ProxyManager.getProxyAddress()
            Log.d("StreamedExtractor", "Using existing proxy: $proxyAddress")
        }
    }
    
    private fun ensureProxyStarted() {
        if (proxyAddress == null) {
            // Try to start proxy without context (fallback mode)
            Log.w("StreamedExtractor", "Context not available, trying fallback proxy mode")
            // For now, we'll skip proxy and use direct requests
            // This is a fallback - ideally context should be provided
        }
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            Log.d("StreamedExtractor", "Extracting from URL: $url")
            
            // Ensure proxy is started if possible
            ensureProxyStarted()
            
            // If proxy is not available, fall back to direct requests
            val useProxy = proxyAddress != null
            if (!useProxy) {
                Log.w("StreamedExtractor", "Proxy not available, using direct requests (may have token issues)")
            }
            
            // Extract match ID from URL
            val matchId = url.substringAfterLast("/")
            if (matchId.isBlank()) {
                Log.e("StreamedExtractor", "Invalid matchId: $matchId")
                return emptyList()
            }
            
            // Get all matches to find available sources
            val allMatchesUrl = "$mainUrl/api/matches/all"
            val allMatchesResponse = if (useProxy) {
                val proxyUrl = ProxyManager.convertToProxyUrl(allMatchesUrl, baseHeaders)
                if (proxyUrl == null) {
                    Log.e("StreamedExtractor", "Failed to convert URL to proxy URL")
                    return emptyList()
                }
                app.get(proxyUrl, timeout = 10000)
            } else {
                app.get(allMatchesUrl, headers = baseHeaders, timeout = 10000)
            }
            if (!allMatchesResponse.isSuccessful) {
                Log.e("StreamedExtractor", "Failed to get matches: HTTP ${allMatchesResponse.code}")
                return emptyList()
            }
            
            val allMatches = parseJson<List<Match>>(allMatchesResponse.text)
            val matchDetails = allMatches.find { it.id == matchId }
            
            if (matchDetails == null) {
                Log.e("StreamedExtractor", "Match not found: $matchId")
                return emptyList()
            }
            
            val availableSources = matchDetails.matchSources.map { it.sourceName }
            Log.d("StreamedExtractor", "Available sources for $matchId: $availableSources")
            
            val extractorLinks = mutableListOf<ExtractorLink>()
            
            // Try each available source
            for (source in availableSources) {
                try {
                    Log.d("StreamedExtractor", "Trying source: $source")
                    
                    // Get stream info from the API
                    val streamApiUrl = "$mainUrl/api/stream/$source/$matchId"
                    val streamResponse = if (useProxy) {
                        val streamProxyUrl = ProxyManager.convertToProxyUrl(streamApiUrl, baseHeaders)
                        if (streamProxyUrl == null) {
                            Log.w("StreamedExtractor", "Failed to convert stream API URL to proxy URL")
                            continue
                        }
                        app.get(streamProxyUrl, timeout = 10000)
                    } else {
                        app.get(streamApiUrl, headers = baseHeaders, timeout = 10000)
                    }
                    
                    if (!streamResponse.isSuccessful) {
                        Log.w("StreamedExtractor", "Stream API failed for $source: HTTP ${streamResponse.code}")
                        continue
                    }
                    
                    val streamInfos = parseJson<List<StreamInfo>>(streamResponse.text)
                    Log.d("StreamedExtractor", "Found ${streamInfos.size} streams for $source")
                    
                    // Try each stream
                    for (streamInfo in streamInfos) {
                        try {
                            Log.d("StreamedExtractor", "Trying stream ${streamInfo.streamNo} from $source")
                            
                            // Try direct API m3u8 URL first
                            val directApiUrl = "$mainUrl/api/stream/$source/$matchId/${streamInfo.streamNo}.m3u8"
                            val (testUrl, testHeaders) = if (useProxy) {
                                val directProxyUrl = ProxyManager.convertToProxyUrl(directApiUrl, baseHeaders)
                                Log.d("StreamedExtractor", "Trying direct API m3u8 URL: $directApiUrl -> $directProxyUrl")
                                Pair(directProxyUrl, emptyMap<String, String>())
                            } else {
                                Log.d("StreamedExtractor", "Trying direct API m3u8 URL: $directApiUrl")
                                Pair(directApiUrl, baseHeaders)
                            }
                            
                            if (testUrl != null) {
                                val testResponse = app.head(testUrl, headers = testHeaders, timeout = 10000)
                                if (testResponse.isSuccessful) {
                                    Log.d("StreamedExtractor", "Found working direct m3u8 URL")
                                    
                                    extractorLinks.add(
                                        newExtractorLink(
                                            source = "Streamed",
                                            name = "${source} Stream ${streamInfo.streamNo} (${streamInfo.language}${if (streamInfo.hd) ", HD" else ""})",
                                            url = testUrl,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.referer = streamInfo.embedUrl
                                            this.quality = if (streamInfo.hd) Qualities.P1080.value else Qualities.Unknown.value
                                            this.headers = testHeaders
                                        }
                                    )
                                    continue
                                }
                            }
                            
                            // Try generated m3u8 URLs as fallback
                            val generatedUrls = generateM3u8Urls(source, matchId, streamInfo.streamNo)
                            for (m3u8Url in generatedUrls) {
                                try {
                                    val (testUrl, testHeaders) = if (useProxy) {
                                        val proxyUrl = ProxyManager.convertToProxyUrl(m3u8Url, baseHeaders)
                                        Log.d("StreamedExtractor", "Testing generated URL: $m3u8Url -> $proxyUrl")
                                        Pair(proxyUrl, emptyMap<String, String>())
                                    } else {
                                        Log.d("StreamedExtractor", "Testing generated URL: $m3u8Url")
                                        Pair(m3u8Url, baseHeaders)
                                    }
                                    
                                    if (testUrl != null) {
                                        val testResponse = app.head(testUrl, headers = testHeaders, timeout = 10000)
                                        if (testResponse.isSuccessful) {
                                            Log.d("StreamedExtractor", "Found working generated m3u8 URL")
                                            
                                            extractorLinks.add(
                                                newExtractorLink(
                                                    source = "Streamed",
                                                    name = "${source} Stream ${streamInfo.streamNo} (${streamInfo.language}${if (streamInfo.hd) ", HD" else ""})",
                                                    url = testUrl,
                                                    type = ExtractorLinkType.M3U8
                                                ) {
                                                    this.referer = streamInfo.embedUrl
                                                    this.quality = if (streamInfo.hd) Qualities.P1080.value else Qualities.Unknown.value
                                                    this.headers = testHeaders
                                                }
                                            )
                                            break
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.d("StreamedExtractor", "URL failed: $m3u8Url - ${e.message}")
                                }
                            }
                            
        } catch (e: Exception) {
                            Log.e("StreamedExtractor", "Error processing stream ${streamInfo.streamNo}: ${e.message}")
                        }
                    }
                    
                } catch (e: Exception) {
                    Log.e("StreamedExtractor", "Error processing source $source: ${e.message}")
                }
            }
            
            Log.d("StreamedExtractor", "Extraction complete: found ${extractorLinks.size} links")
            return extractorLinks
            
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Extraction failed: ${e.message}")
            return emptyList()
        }
    }
    
    private fun generateM3u8Urls(source: String, matchId: String, streamNo: Int): List<String> {
        val baseUrls = listOf(
            "https://lb6.strmd.top",
            "https://lb1.strmd.top",
            "https://lb2.strmd.top",
            "https://lb3.strmd.top",
            "https://lb4.strmd.top",
            "https://rr.buytommy.top"
        )
        
        val patterns = listOf(
            "/secure/iCrHEMPgOmYrZtaFHAufNCHorGUslKKw/$source/stream/$matchId/$streamNo/playlist.m3u8",
            "/$source/stream/$matchId/$streamNo/playlist.m3u8",
            "/secure/*/$source/stream/$matchId/$streamNo/playlist.m3u8",
            "/stream/$source/$matchId/$streamNo/playlist.m3u8",
            "/$source/$matchId/$streamNo/playlist.m3u8"
        )
        
        return baseUrls.flatMap { baseUrl ->
            patterns.map { pattern ->
                baseUrl + pattern
            }
        }
    }
    
    data class Match(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("category") val category: String,
        @JsonProperty("date") val date: Long,
        @JsonProperty("poster") val posterPath: String? = null,
        @JsonProperty("popular") val popular: Boolean = false,
        @JsonProperty("sources") val matchSources: List<MatchSource> = emptyList(),
        @JsonProperty("teams") val teams: Teams? = null,
        @JsonProperty("finished") val finished: Boolean = false
    )

    data class Teams(
        @JsonProperty("home") val home: Team,
        @JsonProperty("away") val away: Team
    )

    data class Team(
        @JsonProperty("name") val name: String,
        @JsonProperty("badge") val badge: String? = null
    )

    data class MatchSource(
        @JsonProperty("source") val sourceName: String,
        @JsonProperty("id") val id: String
    )

    data class StreamInfo(
        @JsonProperty("id") val id: String,
        @JsonProperty("streamNo") val streamNo: Int,
        @JsonProperty("language") val language: String,
        @JsonProperty("hd") val hd: Boolean,
        @JsonProperty("embedUrl") val embedUrl: String,
        @JsonProperty("source") val source: String
    )
}