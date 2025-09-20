package ben.smith53.extractors

// import ben.smith53.proxy.ProxyCallback
import android.content.Context
import android.util.Log
import ben.smith53.proxy.ProxyServer
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

// Local interface to avoid import issues
interface ProxyCallback {
    fun onProxyReady(proxy: ProxyServer)
}

class StreamedExtractor(private val context: Context) : ExtractorApi() {
    override val name = "StreamedExtractor"
    override val mainUrl = "https://streamed.pk"
    override val requiresReferer = true

    private var proxyServer: ProxyServer? = null
    private var proxyInitialized = false

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/vnd.apple.mpegurl, */*",
        "Accept-Language" to "en-GB,en-US;q=0.9,en;q=0.8"
    )

    private fun initializeProxy() {
        if (proxyInitialized) return
        proxyInitialized = true
        
        // Try to start proxy, but handle case where Java classes are not available
        try {
            Log.d("StreamedExtractor", "Attempting to start proxy...")
            proxyServer = ProxyServer(context, 1111)
            proxyServer?.start()
            Log.d("StreamedExtractor", "Proxy started successfully at: ${proxyServer?.getHttpAddress()}")
            // Test the proxy
            testProxy()
        } catch (e: ClassNotFoundException) {
            Log.w("StreamedExtractor", "Proxy classes not available, using direct extraction: ${e.message}")
            proxyServer = null
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Error starting proxy: ${e.message}")
            proxyServer = null
        }
    }
    
    private fun testProxy() {
        try {
            val testUrl = "http://httpbin.org/get"
            val proxyUrl = proxyServer?.convertToProxyUrl(testUrl, emptyMap())
            if (proxyUrl != null) {
                Log.d("StreamedExtractor", "Proxy test URL created: $proxyUrl")
            } else {
                Log.w("StreamedExtractor", "Proxy test failed - could not create proxy URL")
            }
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Proxy test error", e)
        }
    }

    fun cleanup() {
        try {
            proxyServer?.stop()
            Log.d("StreamedExtractor", "Proxy stopped")
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Error stopping proxy", e)
        }
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            Log.d("StreamedExtractor", "Extracting from URL: $url")
            
            // Initialize proxy if not already done
            initializeProxy()
            
            // Check if proxy is running
            val isProxyRunning = proxyServer != null
            val currentAddress = proxyServer?.getHttpAddress()
            Log.d("StreamedExtractor", "Proxy status - running: $isProxyRunning, address: $currentAddress")
            
            if (proxyServer == null) {
                Log.w("StreamedExtractor", "Proxy not running, trying direct extraction")
                return extractDirectly(url)
            }
            
            // Check if this is an embed URL (like https://embedsports.top/embed/alpha/bangladesh-vs-sri-lanka/1)
            if (url.contains("embedsports.top/embed/")) {
                Log.d("StreamedExtractor", "Processing embed URL: $url")
                
                // Extract the actual stream URL from the embed page
                val embedResponse = app.get(url, headers = baseHeaders, timeout = 30000)
                if (!embedResponse.isSuccessful) {
                    Log.e("StreamedExtractor", "Failed to get embed page: HTTP ${embedResponse.code}")
                    return emptyList()
                }
                
                val embedContent = embedResponse.text
                Log.d("StreamedExtractor", "Embed page content length: ${embedContent.length}")
                
                // Look for M3U8 URLs in the embed page
                val m3u8Pattern = """https?://[^"'\s]+\.m3u8[^"'\s]*""".toRegex()
                val m3u8Urls = m3u8Pattern.findAll(embedContent).map { it.value }.toList()
                
                Log.d("StreamedExtractor", "Found ${m3u8Urls.size} M3U8 URLs in embed page")
                
                val extractorLinks = mutableListOf<ExtractorLink>()
                
                for (m3u8Url in m3u8Urls) {
                    try {
                        Log.d("StreamedExtractor", "Found M3U8 URL: $m3u8Url")
                        
                        // Use proxy to convert the M3U8 URL
                        val proxyUrl = proxyServer?.convertToProxyUrl(m3u8Url, baseHeaders)
                        
                        if (proxyUrl != null) {
                            Log.d("StreamedExtractor", "Using proxy URL: $proxyUrl")
                            
                            extractorLinks.add(
                                newExtractorLink(
                                    source = "Streamed",
                                    name = "Stream",
                                    url = proxyUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = url
                                    this.quality = Qualities.Unknown.value
                                    this.headers = baseHeaders
                                }
                            )
                        } else {
                            Log.w("StreamedExtractor", "Failed to convert M3U8 URL to proxy URL: $m3u8Url")
                        }
                        
                    } catch (e: Exception) {
                        Log.e("StreamedExtractor", "Error processing M3U8 URL: ${e.message}")
                    }
                }
                
                Log.d("StreamedExtractor", "Extraction complete: found ${extractorLinks.size} links")
                return extractorLinks
                
            } else {
                Log.e("StreamedExtractor", "Unsupported URL format: $url")
                return emptyList()
            }
            
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Extraction failed: ${e.message}")
            return emptyList()
        }
    }
    
    private suspend fun extractDirectly(url: String): List<ExtractorLink>? {
        try {
            Log.d("StreamedExtractor", "Direct extraction from: $url")
            
            if (url.contains("embedsports.top/embed/")) {
                // Extract the actual stream URL from the embed page
                val embedResponse = app.get(url, headers = baseHeaders, timeout = 30000)
                if (!embedResponse.isSuccessful) {
                    Log.e("StreamedExtractor", "Failed to get embed page: HTTP ${embedResponse.code}")
                    return emptyList()
                }
                
                val embedContent = embedResponse.text
                Log.d("StreamedExtractor", "Embed page content length: ${embedContent.length}")
                
                // Look for M3U8 URLs in the embed page
                val m3u8Pattern = """https?://[^"'\s]+\.m3u8[^"'\s]*""".toRegex()
                val m3u8Urls = m3u8Pattern.findAll(embedContent).map { it.value }.toList()
                
                Log.d("StreamedExtractor", "Found ${m3u8Urls.size} M3U8 URLs in embed page")
                
                val extractorLinks = mutableListOf<ExtractorLink>()
                
                for (m3u8Url in m3u8Urls) {
                    try {
                        Log.d("StreamedExtractor", "Found M3U8 URL: $m3u8Url")
                        
                        extractorLinks.add(
                            newExtractorLink(
                                source = "Streamed",
                                name = "Stream",
                                url = m3u8Url,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = url
                                this.quality = Qualities.Unknown.value
                                this.headers = baseHeaders
                            }
                        )
                        
                    } catch (e: Exception) {
                        Log.e("StreamedExtractor", "Error processing M3U8 URL: ${e.message}")
                    }
                }
                
                Log.d("StreamedExtractor", "Direct extraction complete: found ${extractorLinks.size} links")
                return extractorLinks
                
            } else {
                Log.e("StreamedExtractor", "Unsupported URL format for direct extraction: $url")
                return emptyList()
            }
            
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Direct extraction failed: ${e.message}")
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