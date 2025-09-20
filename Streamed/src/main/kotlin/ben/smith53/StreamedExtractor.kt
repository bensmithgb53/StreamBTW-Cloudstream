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
import kotlinx.coroutines.runBlocking

class StreamedExtractor(private val context: Context) : ExtractorApi() {
    override val name = "StreamedExtractor"
    override val mainUrl = "https://streamed.pk"
    override val requiresReferer = true

    private val proxyManager = ProxyManager(context)
    private var proxyAddress: String? = null

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/vnd.apple.mpegurl, */*",
        "Accept-Language" to "en-GB,en-US;q=0.9,en;q=0.8"
    )

    init {
        // Start proxy in background
        runBlocking {
            try {
                proxyAddress = proxyManager.startProxy()
                if (proxyAddress != null) {
                    Log.d("StreamedExtractor", "Proxy started at: $proxyAddress")
                } else {
                    Log.w("StreamedExtractor", "Failed to start proxy")
                }
            } catch (e: Exception) {
                Log.e("StreamedExtractor", "Error starting proxy", e)
            }
        }
    }

    fun cleanup() {
        try {
            proxyManager.stopProxy()
            Log.d("StreamedExtractor", "Proxy stopped")
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Error stopping proxy", e)
        }
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            Log.d("StreamedExtractor", "Extracting from URL: $url")
            
            // Check if proxy is running
            if (proxyAddress == null || !proxyManager.isProxyRunning()) {
                Log.e("StreamedExtractor", "Proxy not running, cannot extract")
                return emptyList()
            }
            
            // Extract match ID from URL
            val matchId = url.substringAfterLast("/")
            if (matchId.isBlank()) {
                Log.e("StreamedExtractor", "Invalid matchId: $matchId")
                return emptyList()
            }
            
            // Get all matches to find available sources
            val allMatchesResponse = app.get("$mainUrl/api/matches/all", headers = baseHeaders, timeout = 30000)
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
                    val streamResponse = app.get(
                        "$mainUrl/api/stream/$source/$matchId",
                        headers = baseHeaders,
                        timeout = 30000
                    )
                    
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
                            
                            // Use proxy to get the stream URL - this handles all the HTML changes and token hiding
                            val streamUrl = "$mainUrl/api/stream/$source/$matchId/${streamInfo.streamNo}.m3u8"
                            val proxyUrl = proxyManager.convertToProxyUrl(streamUrl, baseHeaders)
                            
                            if (proxyUrl != null) {
                                Log.d("StreamedExtractor", "Using proxy URL: $proxyUrl")
                                
                                extractorLinks.add(
                                    newExtractorLink(
                                        source = "Streamed",
                                        name = "${source} Stream ${streamInfo.streamNo} (${streamInfo.language}${if (streamInfo.hd) ", HD" else ""})",
                                        url = proxyUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = streamInfo.embedUrl
                                        this.quality = if (streamInfo.hd) Qualities.P1080.value else Qualities.Unknown.value
                                        this.headers = baseHeaders
                                    }
                                )
                            } else {
                                Log.w("StreamedExtractor", "Failed to convert URL to proxy URL: $streamUrl")
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