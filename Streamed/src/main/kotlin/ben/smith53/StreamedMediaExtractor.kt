package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import java.net.URI

open class StreamedExtractor : ExtractorApi() {
    override val name = "StreamedExtractor"
    override val mainUrl = "https://embedsports.top"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
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
            
            streamInfo.forEach { info ->
                callback.invoke(
                    ExtractorLink(
                        name = this.name,
                        source = this.name,
                        url = info.url,
                        referer = embedUrl,
                        quality = info.quality,
                        isM3u8 = info.isM3u8,
                        headers = headers
                    )
                )
            }
        } catch (e: Exception) {
            logError(e)
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
            logError(e)
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
            logError(e)
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
            logError(e)
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
            logError(e)
        }
        
        return streamInfoList
    }
    
    private fun getQualityFromUrl(url: String): Int {
        return when {
            url.contains("4k", ignoreCase = true) || url.contains("2160", ignoreCase = true) -> Qualities.P2160.value
            url.contains("1080", ignoreCase = true) || url.contains("fhd", ignoreCase = true) -> Qualities.P1080.value
            url.contains("720", ignoreCase = true) || url.contains("hd", ignoreCase = true) -> Qualities.P720.value
            url.contains("480", ignoreCase = true) -> Qualities.P480.value
            url.contains("360", ignoreCase = true) -> Qualities.P360.value
            url.contains("240", ignoreCase = true) -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }
    
    private fun getQualityFromM3u8(content: String): Int {
        try {
            // Parse m3u8 content to find the highest quality
            val lines = content.split("\n")
            var maxBandwidth = 0
            
            for (line in lines) {
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    val bandwidthRegex = """BANDWIDTH=(\d+)""".toRegex()
                    val match = bandwidthRegex.find(line)
                    if (match != null) {
                        val bandwidth = match.groupValues[1].toIntOrNull() ?: 0
                        if (bandwidth > maxBandwidth) {
                            maxBandwidth = bandwidth
                        }
                    }
                }
            }
            
            // Convert bandwidth to quality
            return when {
                maxBandwidth >= 8000000 -> Qualities.P1080.value
                maxBandwidth >= 5000000 -> Qualities.P720.value
                maxBandwidth >= 2500000 -> Qualities.P480.value
                maxBandwidth >= 1000000 -> Qualities.P360.value
                else -> Qualities.P240.value
            }
        } catch (e: Exception) {
            return Qualities.Unknown.value
        }
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