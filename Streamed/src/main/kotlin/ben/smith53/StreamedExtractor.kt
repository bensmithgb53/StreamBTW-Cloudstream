package ben.smith53.extractors

import android.util.Log
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

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            Log.d("StreamedExtractor", "Extracting from URL: $url")
            
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

                                // Find the source-specific ID for this source
                                val sourceSpecificId = matchDetails.matchSources.find { it.sourceName == source }?.id
                                if (sourceSpecificId == null) {
                                    Log.w("StreamedExtractor", "No source-specific ID found for $source")
                                    continue
                                }

                                Log.d("StreamedExtractor", "Using source-specific ID: $sourceSpecificId for source: $source")

                                // Get stream info from the API using the source-specific ID
                                val streamResponse = app.get(
                                    "$mainUrl/api/stream/$source/$sourceSpecificId",
                                    headers = baseHeaders,
                                    timeout = 30000
                                )
                    
                    if (!streamResponse.isSuccessful) {
                        Log.w("StreamedExtractor", "Stream API failed for $source: HTTP ${streamResponse.code}")
                        continue
                    }
                    
                    val streamResponseText = streamResponse.text
                    Log.d("StreamedExtractor", "Stream API response for $source: $streamResponseText")
                    
                    val streamInfos = parseJson<List<StreamInfo>>(streamResponseText)
                    Log.d("StreamedExtractor", "Found ${streamInfos.size} streams for $source")
                    
                    // Process each stream by fetching the embed page and extracting the m3u8 URL
                    for (streamInfo in streamInfos) {
                        try {
                            Log.d("StreamedExtractor", "Processing stream ${streamInfo.streamNo} from $source")
                            
                            // Fetch the embed page to get the actual m3u8 URL
                            val embedResponse = app.get(
                                streamInfo.embedUrl,
                                headers = baseHeaders + mapOf(
                                    "Referer" to "$mainUrl/watch/$matchId",
                                    "Origin" to mainUrl
                                ),
                                timeout = 30000
                            )
                            
                            if (!embedResponse.isSuccessful) {
                                Log.w("StreamedExtractor", "Embed page failed for ${streamInfo.embedUrl}: HTTP ${embedResponse.code}")
                                continue
                            }
                            
                            val embedHtml = embedResponse.text
                            Log.d("StreamedExtractor", "Fetched embed page for ${streamInfo.embedUrl}")
                            
                            // Extract the m3u8 URL from the 'o' variable in the embed page
                            val m3u8Url = extractM3u8UrlFromEmbed(embedHtml)
                            if (m3u8Url != null) {
                                Log.d("StreamedExtractor", "Found m3u8 URL: $m3u8Url")
                                
                                // Test if the m3u8 URL is accessible
                                val testResponse = app.head(m3u8Url, headers = baseHeaders, timeout = 10000)
                                if (testResponse.isSuccessful) {
                                    Log.d("StreamedExtractor", "M3u8 URL is accessible")
                                    
                                    extractorLinks.add(
            newExtractorLink(
                                            source = "Streamed",
                                            name = "${source} Stream ${streamInfo.streamNo} (${streamInfo.language}${if (streamInfo.hd) ", HD" else ""})",
                                            url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                                            this.referer = streamInfo.embedUrl
                                            this.quality = if (streamInfo.hd) Qualities.P1080.value else Qualities.Unknown.value
                this.headers = baseHeaders
            }
        )
                                } else {
                                    Log.w("StreamedExtractor", "M3u8 URL not accessible: $m3u8Url - HTTP ${testResponse.code}")
                                }
                            } else {
                                Log.w("StreamedExtractor", "No m3u8 URL found in embed page")
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
    
    private fun extractM3u8UrlFromEmbed(html: String): String? {
        try {
            // Look for the 'o' variable that contains the m3u8 URL
            val oVarPattern = Regex("var\\s+o\\s*=\\s*[\"']([^\"']+)[\"']")
            val match = oVarPattern.find(html)
            if (match != null) {
                val m3u8Url = match.groupValues[1]
                Log.d("StreamedExtractor", "Extracted m3u8 URL from embed: $m3u8Url")
                return m3u8Url
            }
            
            // Fallback: look for any m3u8 URL in the HTML
            val m3u8Pattern = Regex("https?://[^\"'\\s]+\\.m3u8")
            val m3u8Match = m3u8Pattern.find(html)
            if (m3u8Match != null) {
                val m3u8Url = m3u8Match.value
                Log.d("StreamedExtractor", "Found m3u8 URL in HTML: $m3u8Url")
                return m3u8Url
            }
            
            Log.w("StreamedExtractor", "No m3u8 URL found in embed HTML")
            return null
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Error extracting m3u8 URL from embed: ${e.message}")
            return null
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