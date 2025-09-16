package ben.smith53.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class PPVLandExtractor : ExtractorApi() {
    override val name = "PPVLandExtractor"
    override val mainUrl = "https://ppv.to"
    override val requiresReferer = true

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"
    
    private fun generateXCID(): String {
        // Generate a more realistic client ID based on browser fingerprinting
        val timestamp = System.currentTimeMillis()
        val random = (Math.random() * 1000000).toInt()
        val userAgentHash = USER_AGENT.hashCode().toString(16)
        return "${userAgentHash}${timestamp}${random}".take(64).padEnd(64, '0')
    }
    
    private val HEADERS = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Encoding" to "gzip, deflate, br, zstd",
        "Connection" to "keep-alive",
        "Accept-Language" to "en-US,en;q=0.5",
        "X-FS-Client" to "FS WebClient 1.0",
        "X-CID" to generateXCID()
    )

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            println("PPVLandExtractor: Processing URL: $url")
            // Check if it's an iframe URL (ppvs.su domain)
            if (url.contains("ppvs.su") || url.contains("/embed/")) {
                println("PPVLandExtractor: Detected iframe URL, processing...")
                return handleIframeUrl(url, referer)
            }
            
            // Handle API URLs
            val apiUrl = if (url.startsWith("$mainUrl/api/streams/")) {
                url
            } else {
                val streamId = url.split("/").firstOrNull { it.matches(Regex("\\d+")) } ?: throw Exception("No numeric stream ID found in URL: $url")
                "$mainUrl/api/streams/$streamId"
            }
            
            val response = app.get(apiUrl, headers = HEADERS, referer = referer ?: "$mainUrl/").text
            val mapper = jacksonObjectMapper()
            val jsonData = mapper.readValue<Map<String, Any>>(response)
            @Suppress("UNCHECKED_CAST")
            val data = jsonData["data"] as? Map<String, Any> ?: throw Exception("No 'data' field in JSON")
            
            // Check for direct m3u8 URL first
            val m3u8Url = data["m3u8"] as? String
            if (!m3u8Url.isNullOrEmpty()) {
                return listOf(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            
            // Check for sources array with iframe embeds
            @Suppress("UNCHECKED_CAST")
            val sources = data["sources"] as? List<Map<String, Any>>
            if (sources != null && sources.isNotEmpty()) {
                for (source in sources) {
                    val sourceType = source["type"] as? String
                    val sourceData = source["data"] as? String
                    
                    if (sourceType == "iframe" && !sourceData.isNullOrEmpty()) {
                        return handleIframeUrl(sourceData, referer)
                    }
                }
            }
            
            throw Exception("No valid stream source found in API response")
        } catch (e: Exception) {
            println("PPVLandExtractor error: ${e.message}")
            return null
        }
    }
    
    private suspend fun handleIframeUrl(iframeUrl: String, referer: String?): List<ExtractorLink>? {
        try {
            println("Handling iframe URL: $iframeUrl")
            
            // Try to extract the stream from the iframe page
            val response = app.get(iframeUrl, headers = HEADERS, referer = referer ?: "$mainUrl/")
            val html = response.text
            
            // Look for m3u8 URLs in the HTML
            val m3u8Pattern = Regex("""["']([^"']*\.m3u8[^"']*)["']""")
            val m3u8Matches = m3u8Pattern.findAll(html)
            
            for (match in m3u8Matches) {
                val m3u8Url = match.groupValues[1]
                if (m3u8Url.isNotEmpty() && !m3u8Url.contains("preset-")) {
                    println("Found m3u8 URL in iframe: $m3u8Url")
                    return listOf(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = iframeUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
            
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
                        println("Found m3u8 URL in JavaScript config: $m3u8Url")
                        return listOf(
                            newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = m3u8Url,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = iframeUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            }
            
            // Look for JSON data with stream information
            val jsonPattern = Regex("""var\s+data\s*=\s*({.*?});""")
            val jsonMatch = jsonPattern.find(html)
            if (jsonMatch != null) {
                val jsonStr = jsonMatch.groupValues[1]
                try {
                    val mapper = jacksonObjectMapper()
                    val jsonData = mapper.readValue<Map<String, Any>>(jsonStr)
                    val m3u8Url = jsonData["m3u8"] as? String
                    if (!m3u8Url.isNullOrEmpty()) {
                        println("Found m3u8 URL in JSON data: $m3u8Url")
                        return listOf(
                            newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = m3u8Url,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = iframeUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                } catch (e: Exception) {
                    println("Failed to parse JSON data: ${e.message}")
                }
            }
            
            // If no m3u8 found, return the iframe URL as a fallback
            println("No m3u8 URL found, using iframe URL as fallback")
            return listOf(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = iframeUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = iframeUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            println("Error handling iframe URL: ${e.message}")
            return null
        }
    }
}
