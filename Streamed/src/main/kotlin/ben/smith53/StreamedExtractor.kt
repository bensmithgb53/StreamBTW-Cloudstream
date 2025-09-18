package ben.smith53.extractors

import android.util.Base64
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
        "Accept-Language" to "en-GB,en-US;q=0.9,en;q=0.8",
        "Origin" to "https://streamed.pk",
        "Referer" to "https://streamed.pk/"
    )

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            Log.d("StreamedExtractor", "Extracting from URL: $url")
            val matchId = url.substringAfterLast("/")
            if (matchId.isBlank()) {
                Log.e("StreamedExtractor", "Invalid matchId: $matchId")
                return emptyList()
            }

            // Fetch all matches to get source information
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
            val sourcePriority = listOf("admin", "alpha", "bravo", "charlie", "echo", "foxtrot", "delta", "golf", "hotel", "intel")

            // Sort sources by priority
            val sortedSources = availableSources.sortedBy { sourcePriority.indexOf(it) }

            for (source in sortedSources) {
                try {
                    Log.d("StreamedExtractor", "Trying source: $source")
                    val sourceSpecificId = matchDetails.matchSources.find { it.sourceName == source }?.id
                    if (sourceSpecificId == null) {
                        Log.w("StreamedExtractor", "No source-specific ID for $source")
                        continue
                    }

                    // Fetch stream info
                    val streamResponse = app.get(
                        "$mainUrl/api/stream/$source/$sourceSpecificId",
                        headers = baseHeaders,
                        timeout = 10000
                    )

                    if (!streamResponse.isSuccessful) {
                        Log.w("StreamedExtractor", "Stream API failed for $source: HTTP ${streamResponse.code}")
                        continue
                    }

                    val streamResponseText = streamResponse.text
                    Log.d("StreamedExtractor", "Stream API response for $source: $streamResponseText")

                    // Try to parse stream info, handling potential base64 encoding
                    val streamInfos = try {
                        parseJson<List<StreamInfo>>(streamResponseText)
                    } catch (e: Exception) {
                        Log.w("StreamedExtractor", "Failed to parse JSON directly, trying base64 decode: ${e.message}")
                        val decodedText = try {
                            String(Base64.decode(streamResponseText, Base64.DEFAULT))
                        } catch (decodeException: Exception) {
                            Log.e("StreamedExtractor", "Base64 decode failed: ${decodeException.message}")
                            continue
                        }
                        parseJson<List<StreamInfo>>(decodedText)
                    }

                    for (streamInfo in streamInfos) {
                        try {
                            // Check if m3u8 URL is provided directly
                            val m3u8Url = streamInfo.m3u8?.let {
                                if (it.startsWith("http")) it else "$mainUrl$it"
                            } ?: run {
                                // Try direct M3U8 URL
                                val directApiUrl = "$mainUrl/api/stream/$source/$sourceSpecificId/${streamInfo.streamNo}.m3u8"
                                try {
                                    val testResponse = app.head(directApiUrl, headers = baseHeaders, timeout = 10000)
                                    if (testResponse.isSuccessful) directApiUrl else null
                                } catch (e: Exception) {
                                    Log.d("StreamedExtractor", "Direct M3U8 failed: $directApiUrl - ${e.message}")
                                    null
                                }
                            }

                            if (m3u8Url != null) {
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
                                continue
                            }

                            // Fallback to generated URLs
                            val generatedUrls = generateM3u8Urls(source, sourceSpecificId, streamInfo.streamNo)
                            for (generatedUrl in generatedUrls) {
                                try {
                                    val testResponse = app.head(generatedUrl, headers = baseHeaders, timeout = 10000)
                                    if (testResponse.isSuccessful) {
                                        extractorLinks.add(
                                            newExtractorLink(
                                                source = "Streamed",
                                                name = "${source} Stream ${streamInfo.streamNo} (${streamInfo.language}${if (streamInfo.hd) ", HD" else ""})",
                                                url = generatedUrl,
                                                type = ExtractorLinkType.M3U8
                                            ) {
                                                this.referer = streamInfo.embedUrl
                                                this.quality = if (streamInfo.hd) Qualities.P1080.value else Qualities.Unknown.value
                                                this.headers = baseHeaders
                                            }
                                        )
                                        break
                                    }
                                } catch (e: Exception) {
                                    Log.d("StreamedExtractor", "Generated URL failed: $generatedUrl - ${e.message}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("StreamedExtractor", "Error processing stream ${streamInfo.streamNo}: ${e.message}")
                        }
                    }

                    // If admin source fails, try other sources
                    if (source == "admin" && extractorLinks.isEmpty()) {
                        Log.w("StreamedExtractor", "Admin source failed, trying other sources")
                    }
                } catch (e: Exception) {
                    Log.e("StreamedExtractor", "Error processing source $source: ${e.message}")
                }
            }

            Log.d("StreamedExtractor", "Extraction complete: found ${extractorLinks.size} links")
            return extractorLinks.ifEmpty { null }
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Extraction failed: ${e.message}")
            return null
        }
    }

    private fun generateM3u8Urls(source: String, matchId: String, streamNo: Int): List<String> {
        val baseUrls = listOf(
            "https://lb6.strmd.top",
            "https://lb1.strmd.top",
            "https://lb2.strmd.top",
            "https://lb3.strmd.top",
            "https://lb4.strmd.top",
            "https://rr.buytommy.top",
            "https://streamed.pk"
        )

        val patterns = listOf(
            "/secure/iCrHEMPgOmYrZtaFHAufNCHorGUslKKw/$source/stream/$matchId/$streamNo/playlist.m3u8",
            "/$source/stream/$matchId/$streamNo/playlist.m3u8",
            "/secure/*/$source/stream/$matchId/$streamNo/playlist.m3u8",
            "/stream/$source/$matchId/$streamNo/playlist.m3u8",
            "/$source/$matchId/$streamNo/playlist.m3u8",
            "/api/stream/$source/$matchId/$streamNo.m3u8"
        )

        return baseUrls.flatMap { baseUrl ->
            patterns.map { pattern -> baseUrl + pattern }
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
        @JsonProperty("source") val source: String,
        @JsonProperty("m3u8") val m3u8: String? = null
    )
}
