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
                            val m3u8Urls = extractM3u8UrlsFromEmbed(embedHtml, source, sourceSpecificId, streamInfo.streamNo)
                            
                            var foundWorkingUrl = false
                            for (m3u8Url in m3u8Urls) {
                                try {
                                    Log.d("StreamedExtractor", "Testing m3u8 URL: $m3u8Url")
                                    
                                    // Test if the m3u8 URL is accessible
                                    val testResponse = app.head(m3u8Url, headers = baseHeaders, timeout = 10000)
                                    if (testResponse.isSuccessful) {
                                        Log.d("StreamedExtractor", "Found working m3u8 URL: $m3u8Url")
                                        
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
                                        foundWorkingUrl = true
                                        break
                                    } else {
                                        Log.d("StreamedExtractor", "URL not accessible: $m3u8Url - HTTP ${testResponse.code}")
                                    }
                                } catch (e: Exception) {
                                    Log.d("StreamedExtractor", "Error testing URL $m3u8Url: ${e.message}")
                                }
                            }
                            
                            if (!foundWorkingUrl) {
                                Log.w("StreamedExtractor", "No working m3u8 URL found for ${source} stream ${streamInfo.streamNo}")
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
    
    private fun extractM3u8UrlsFromEmbed(html: String, source: String, sourceId: String, streamNo: Int): List<String> {
        try {
            // Look for the 'o' variable that contains the m3u8 URL (admin sources)
            val oVarPattern = Regex("var\\s+o\\s*=\\s*[\"']([^\"']+)[\"']")
            val match = oVarPattern.find(html)
            if (match != null) {
                val m3u8Url = match.groupValues[1]
                Log.d("StreamedExtractor", "Extracted m3u8 URL from embed: $m3u8Url")
                return listOf(m3u8Url)
            }
            
            // For non-admin sources, generate the m3u8 URL based on the pattern
            Log.d("StreamedExtractor", "No 'o' variable found, generating URL for $source source")
            
            // Extract k, i, s variables from the embed page
            val kPattern = Regex("var\\s+k\\s*=\\s*[\"']([^\"']+)[\"']")
            val iPattern = Regex("var\\s+i\\s*=\\s*[\"']([^\"']+)[\"']")
            val sPattern = Regex("var\\s+s\\s*=\\s*[\"']([^\"']+)[\"']")
            
            val kMatch = kPattern.find(html)
            val iMatch = iPattern.find(html)
            val sMatch = sPattern.find(html)
            
            Log.d("StreamedExtractor", "Variable extraction results: kMatch=${kMatch != null}, iMatch=${iMatch != null}, sMatch=${sMatch != null}")
            
            if (kMatch != null && iMatch != null && sMatch != null) {
                val k = kMatch.groupValues[1]  // source
                val i = iMatch.groupValues[1]  // source-specific ID
                val s = sMatch.groupValues[1]  // stream number
                
                Log.d("StreamedExtractor", "Extracted variables: k=$k, i=$i, s=$s")
                
                // Generate m3u8 URLs based on source type
                val m3u8Urls = generateM3u8UrlsForSource(k, i, s)
                if (m3u8Urls.isNotEmpty()) {
                    Log.d("StreamedExtractor", "Generated ${m3u8Urls.size} m3u8 URLs")
                    return m3u8Urls
                } else {
                    Log.w("StreamedExtractor", "No URLs generated for source $k")
                }
            } else {
                Log.w("StreamedExtractor", "Could not extract all variables from embed page")
                Log.d("StreamedExtractor", "HTML snippet: ${html.substring(0, minOf(500, html.length))}")
                
                // Fallback: use the parameters passed to the function
                Log.d("StreamedExtractor", "Using fallback parameters: source=$source, sourceId=$sourceId, streamNo=$streamNo")
                val m3u8Urls = generateM3u8UrlsForSource(source, sourceId, streamNo.toString())
                if (m3u8Urls.isNotEmpty()) {
                    Log.d("StreamedExtractor", "Generated ${m3u8Urls.size} m3u8 URLs using fallback parameters")
                    return m3u8Urls
                }
            }
            
            // Fallback: look for any m3u8 URL in the HTML
            val m3u8Pattern = Regex("https?://[^\"'\\s]+\\.m3u8")
            val m3u8Match = m3u8Pattern.find(html)
            if (m3u8Match != null) {
                val m3u8Url = m3u8Match.value
                Log.d("StreamedExtractor", "Found m3u8 URL in HTML: $m3u8Url")
                return listOf(m3u8Url)
            }
            
            Log.w("StreamedExtractor", "No m3u8 URL found in embed HTML")
            return emptyList()
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Error extracting m3u8 URL from embed: ${e.message}")
            return emptyList()
        }
    }
    
    private fun generateM3u8UrlsForSource(source: String, sourceId: String, streamNo: String): List<String> {
        try {
            Log.d("StreamedExtractor", "Generating URLs for source=$source, sourceId=$sourceId, streamNo=$streamNo")
            
            // Different base URLs for different sources
            val baseUrls = when (source) {
                "alpha" -> listOf(
                    "https://lb1.strmd.top",
                    "https://lb2.strmd.top", 
                    "https://lb3.strmd.top",
                    "https://lb4.strmd.top",
                    "https://lb6.strmd.top"
                )
                "charlie" -> listOf(
                    "https://lb1.strmd.top",
                    "https://lb2.strmd.top",
                    "https://lb3.strmd.top", 
                    "https://lb4.strmd.top",
                    "https://lb6.strmd.top"
                )
                "delta" -> listOf(
                    "https://lb1.strmd.top",
                    "https://lb2.strmd.top",
                    "https://lb3.strmd.top",
                    "https://lb4.strmd.top", 
                    "https://lb6.strmd.top"
                )
                "echo" -> listOf(
                    "https://lb1.strmd.top",
                    "https://lb2.strmd.top",
                    "https://lb3.strmd.top",
                    "https://lb4.strmd.top",
                    "https://lb6.strmd.top"
                )
                "foxtrot" -> listOf(
                    "https://lb1.strmd.top",
                    "https://lb2.strmd.top",
                    "https://lb3.strmd.top",
                    "https://lb4.strmd.top",
                    "https://lb6.strmd.top"
                )
                else -> emptyList()
            }
            
            // Common encryption keys (these might need to be updated based on current events)
            val encryptionKeys = listOf(
                "hYOeUTfQyHEyWeTszoOhqBCQvpCaYdHb",
                "iCrHEMPgOmYrZtaFHAufNCHorGUslKKw",
                "aBcDeFgHiJkLmNoPqRsTuVwXyZ123456"
            )
            
            // Generate possible URLs
            val possibleUrls = mutableListOf<String>()
            
            for (baseUrl in baseUrls) {
                for (key in encryptionKeys) {
                    // Pattern: /secure/{key}/{source}/stream/{sourceId}/{streamNo}/playlist.m3u8
                    possibleUrls.add("$baseUrl/secure/$key/$source/stream/$sourceId/$streamNo/playlist.m3u8")
                    
                    // Alternative pattern: /secure/{key}/{source}/{sourceId}/{streamNo}/playlist.m3u8
                    possibleUrls.add("$baseUrl/secure/$key/$source/$sourceId/$streamNo/playlist.m3u8")
                }
            }
            
            Log.d("StreamedExtractor", "Generated ${possibleUrls.size} possible URLs for $source")
            if (possibleUrls.isNotEmpty()) {
                Log.d("StreamedExtractor", "First few URLs: ${possibleUrls.take(3)}")
            }
            return possibleUrls
            
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Error generating m3u8 URL: ${e.message}")
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