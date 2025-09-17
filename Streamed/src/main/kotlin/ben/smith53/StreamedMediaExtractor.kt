package ben.smith53.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import java.net.URI

open class StreamedExtractor : ExtractorApi() {
    override val name = "StreamedExtractor"
    override val mainUrl = "https://embedsports.top"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            val embedUrl = if (url.startsWith("http")) url else "$mainUrl$url"
            val headers = mapOf(
                "Referer" to (referer ?: "https://streamed.pk/"),
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
            )
            
            // Get the embed page
            val embedDoc = app.get(embedUrl, headers = headers).document
            
            // Extract stream information from the embed page
            val streamInfo = extractStreamInfo(embedDoc, embedUrl, headers)
            
            return streamInfo.map { info ->
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = info.url,
                    type = if (info.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = embedUrl
                    this.quality = info.quality
                }
            }
        } catch (e: Exception) {
            println("StreamedExtractor error: ${e.message}")
            return null
        }
    }
    
    private suspend fun extractStreamInfo(
        doc: org.jsoup.nodes.Document, 
        embedUrl: String, 
        headers: Map<String, String>
    ): List<StreamInfo> {
        val streamInfoList = mutableListOf<StreamInfo>()
        
        try {
            // Method 1: Look for m3u8 URLs in script tags
            val scripts = doc.select("script")
            for (script in scripts) {
                val scriptContent = script.html()
                
                // Look for m3u8 URLs
                val m3u8Regex = """(https?://[^"'\s]+\.m3u8[^"'\s]*)""".toRegex()
                val m3u8Matches = m3u8Regex.findAll(scriptContent)
                
                m3u8Matches.forEach { match ->
                    val m3u8Url = match.groupValues[1]
                    streamInfoList.add(
                        StreamInfo(
                            url = m3u8Url,
                            quality = getQualityFromUrl(m3u8Url),
                            isM3u8 = true
                        )
                    )
                }
                
                // Look for direct video URLs
                val videoRegex = """(https?://[^"'\s]+\.(mp4|webm|avi|mkv)[^"'\s]*)""".toRegex()
                val videoMatches = videoRegex.findAll(scriptContent)
                
                videoMatches.forEach { match ->
                    val videoUrl = match.groupValues[1]
                    streamInfoList.add(
                        StreamInfo(
                            url = videoUrl,
                            quality = getQualityFromUrl(videoUrl),
                            isM3u8 = false
                        )
                    )
                }
            }
            
            // Method 2: Monitor network requests for m3u8 files
            if (streamInfoList.isEmpty()) {
                streamInfoList.addAll(extractFromNetworkRequests(embedUrl, headers))
            }
            
            // Method 3: Try common m3u8 URL patterns
            if (streamInfoList.isEmpty()) {
                streamInfoList.addAll(tryCommonPatterns(embedUrl, headers))
            }
            
        } catch (e: Exception) {
            println("StreamedExtractor error: ${e.message}")
        }
        
        return streamInfoList.distinctBy { it.url }
    }
    
    private suspend fun extractFromNetworkRequests(
        embedUrl: String, 
        headers: Map<String, String>
    ): List<StreamInfo> {
        val streamInfoList = mutableListOf<StreamInfo>()
        
        try {
            // Parse the embed URL to extract components
            val uri = URI(embedUrl)
            val pathParts = uri.path.split("/").filter { it.isNotEmpty() }
            
            if (pathParts.size >= 4) {
                val sourceType = pathParts[1] // alpha, bravo, etc.
                val eventId = pathParts[2]
                val streamNumber = pathParts[3]
                
                // Try common m3u8 URL patterns based on the analysis
                val possibleUrls = listOf(
                    "https://lb4.strmd.top/secure/*/alpha/stream/$eventId/$streamNumber/playlist.m3u8",
                    "https://lb1.strmd.top/secure/*/alpha/stream/$eventId/$streamNumber/playlist.m3u8",
                    "https://lb2.strmd.top/secure/*/alpha/stream/$eventId/$streamNumber/playlist.m3u8",
                    "https://lb3.strmd.top/secure/*/alpha/stream/$eventId/$streamNumber/playlist.m3u8"
                )
                
                // Try to find the actual secure token
                val secureToken = extractSecureToken(embedUrl, headers)
                
                possibleUrls.forEach { pattern ->
                    val actualUrl = if (secureToken != null) {
                        pattern.replace("*", secureToken).replace("alpha", sourceType)
                    } else {
                        // Try without secure token or with common patterns
                        pattern.replace("/secure/*/", "/").replace("alpha", sourceType)
                    }
                    
                    try {
                        val response = app.get(actualUrl, headers = headers)
                        if (response.isSuccessful) {
                            streamInfoList.add(
                                StreamInfo(
                                    url = actualUrl,
                                    quality = getQualityFromM3u8(response.text),
                                    isM3u8 = true
                                )
                            )
                        }
                    } catch (e: Exception) {
                        // Continue trying other URLs
                    }
                }
            }
        } catch (e: Exception) {
            println("StreamedExtractor error: ${e.message}")
        }
        
        return streamInfoList
    }
    
    private suspend fun extractSecureToken(embedUrl: String, headers: Map<String, String>): String? {
        try {
            val response = app.get(embedUrl, headers = headers)
            val content = response.text
            
            // Look for secure token patterns in the response
            val tokenRegex = """secure/([A-Za-z0-9]+)/""".toRegex()
            val match = tokenRegex.find(content)
            return match?.groupValues?.get(1)
        } catch (e: Exception) {
            println("StreamedExtractor error: ${e.message}")
            return null
        }
    }
    
    private suspend fun tryCommonPatterns(
        embedUrl: String, 
        headers: Map<String, String>
    ): List<StreamInfo> {
        val streamInfoList = mutableListOf<StreamInfo>()
        
        try {
            val uri = URI(embedUrl)
            val pathParts = uri.path.split("/").filter { it.isNotEmpty() }
            
            if (pathParts.size >= 4) {
                val sourceType = pathParts[1]
                val eventId = pathParts[2]
                val streamNumber = pathParts[3]
                
                // Common patterns observed
                val patterns = listOf(
                    "https://embedsports.top/stream/$sourceType/$eventId/$streamNumber.m3u8",
                    "https://embedsports.top/api/stream/$sourceType/$eventId/$streamNumber.m3u8",
                    "https://strmd.top/stream/$sourceType/$eventId/$streamNumber/playlist.m3u8",
                    "https://lb4.strmd.top/stream/$sourceType/$eventId/$streamNumber/playlist.m3u8"
                )
                
                patterns.forEach { url ->
                    try {
                        val response = app.get(url, headers = headers)
                        if (response.isSuccessful && response.text.contains("#EXTM3U")) {
                            streamInfoList.add(
                                StreamInfo(
                                    url = url,
                                    quality = getQualityFromM3u8(response.text),
                                    isM3u8 = true
                                )
                            )
                        }
                    } catch (e: Exception) {
                        // Continue trying other patterns
                    }
                }
            }
        } catch (e: Exception) {
            println("StreamedExtractor error: ${e.message}")
        }
        
        return streamInfoList
    }
    
    private fun getQualityFromUrl(url: String): Int {
        return Qualities.Unknown.value
    }
    
    private fun getQualityFromM3u8(content: String): Int {
        return Qualities.Unknown.value
    }
    
    data class StreamInfo(
        val url: String,
        val quality: Int,
        val isM3u8: Boolean
    )
}

// Alternative extractor for different embed domains
class StreamedExtractor2 : StreamedExtractor() {
    override val name = "StreamedExtractor2"
    override val mainUrl = "https://strmd.top"
}

class StreamedExtractor3 : StreamedExtractor() {
    override val name = "StreamedExtractor3" 
    override val mainUrl = "https://lb4.strmd.top"
}