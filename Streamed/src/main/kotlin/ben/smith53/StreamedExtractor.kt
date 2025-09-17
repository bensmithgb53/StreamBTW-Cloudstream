package ben.smith53.extractors

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class StreamedExtractor : ExtractorApi() {
    override val name = "StreamedExtractor"
    override val mainUrl = "https://streamed.su"
    override val requiresReferer = true
    private val baseApiUrl = "https://streamed.su"
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/vnd.apple.mpegurl, */*",
        "Accept-Language" to "en-GB,en-US;q=0.9,en;q=0.8"
    )

    companion object {
        const val EXTRACTOR_TIMEOUT_SECONDS = 30
        const val EXTRACTOR_TIMEOUT_MILLIS = EXTRACTOR_TIMEOUT_SECONDS * 1000L
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        // This is the standard Cloudstream3 extractor interface
        // For now, return empty list as the provider handles extraction internally
        return emptyList()
    }

    suspend fun getUrl(
        streamUrl: String,
        streamId: String,
        source: String,
        streamNo: Int,
        language: String,
        isHd: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StreamedExtractor", "Starting extraction for: $streamUrl (ID: $streamId)")

        try {
            // Get stream info from the API
            val streamApiUrl = "$baseApiUrl/api/stream/$source/$streamId"
            Log.d("StreamedExtractor", "Fetching stream info from: $streamApiUrl")
            
            val response = app.get(streamApiUrl, headers = baseHeaders, timeout = EXTRACTOR_TIMEOUT_MILLIS)
            if (!response.isSuccessful) {
                Log.e("StreamedExtractor", "API request failed with code: ${response.code}")
                return false
            }
            
            val streamData = response.parsedSafe<List<Map<String, Any>>>()
            if (streamData.isNullOrEmpty()) {
                Log.e("StreamedExtractor", "No stream data received")
                return false
            }
            
            // Find the specific stream number
            val streamInfo = streamData.find { 
                (it["streamNo"] as? Number)?.toInt() == streamNo 
            }
            
            if (streamInfo == null) {
                Log.e("StreamedExtractor", "Stream $streamNo not found in API response")
                return false
            }
            
            val embedUrl = streamInfo["embedUrl"] as? String
            if (embedUrl.isNullOrBlank()) {
                Log.e("StreamedExtractor", "No embed URL found for stream $streamNo")
                return false
            }
            
            Log.d("StreamedExtractor", "Found embed URL: $embedUrl")
            
            // Extract the actual stream URL from the embed page
            val embedResponse = app.get(embedUrl, headers = baseHeaders, timeout = EXTRACTOR_TIMEOUT_MILLIS)
            if (!embedResponse.isSuccessful) {
                Log.e("StreamedExtractor", "Embed page request failed with code: ${embedResponse.code}")
                return false
            }
            
            val embedDoc = embedResponse.document
            
            // Extract JavaScript variables from the embed page
            val scripts = embedDoc.select("script")
            var sourceVar = ""
            var idVar = ""
            var streamVar = ""
            
            for (script in scripts) {
                val scriptContent = script.html()
                if (scriptContent.contains("var k =") && scriptContent.contains("var i =") && scriptContent.contains("var s =")) {
                    val kMatch = Regex("""var k = "([^"]+)"""").find(scriptContent)
                    val iMatch = Regex("""var i = "([^"]+)"""").find(scriptContent)
                    val sMatch = Regex("""var s = "([^"]+)"""").find(scriptContent)
                    
                    sourceVar = kMatch?.groupValues?.get(1) ?: source
                    idVar = iMatch?.groupValues?.get(1) ?: streamId
                    streamVar = sMatch?.groupValues?.get(1) ?: streamNo.toString()
                    
                    Log.d("StreamedExtractor", "Found JS variables: k=$sourceVar, i=$idVar, s=$streamVar")
                    break
                }
            }
            
            // Try to construct m3u8 URL based on common patterns
            val possibleM3u8Urls = mutableListOf<String>()
            
            // Pattern 1: Direct API call to get m3u8
            val directApiUrl = "$baseApiUrl/api/stream/$sourceVar/$idVar/$streamVar.m3u8"
            possibleM3u8Urls.add(directApiUrl)
            
            // Pattern 2: Common m3u8 patterns
            val baseUrls = listOf(
                "https://rr.buytommy.top",
                "https://lb1.strmd.top",
                "https://lb2.strmd.top", 
                "https://lb3.strmd.top",
                "https://lb4.strmd.top"
            )
            
            for (baseUrl in baseUrls) {
                possibleM3u8Urls.add("$baseUrl/stream/$sourceVar/$idVar/$streamVar/playlist.m3u8")
                possibleM3u8Urls.add("$baseUrl/secure/*/stream/$sourceVar/$idVar/$streamVar/playlist.m3u8")
            }
            
            // Test each possible URL
            var m3u8Url = ""
            for (url in possibleM3u8Urls) {
                try {
                    Log.d("StreamedExtractor", "Testing URL: $url")
                    val testResponse = app.head(url, headers = baseHeaders, timeout = 10000)
                    if (testResponse.isSuccessful) {
                        m3u8Url = url
                        Log.d("StreamedExtractor", "Found working m3u8 URL: $url")
                        break
                    }
                } catch (e: Exception) {
                    Log.d("StreamedExtractor", "URL failed: $url - ${e.message}")
                }
            }
            
            if (m3u8Url.isEmpty()) {
                Log.e("StreamedExtractor", "No working m3u8 URLs found")
                return false
            }
            
            // Create the extractor link
            callback.invoke(
                newExtractorLink(
                    source = "Streamed",
                    name = "$source Stream $streamNo ($language${if (isHd) ", HD" else ""})",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = embedUrl
                    this.quality = if (isHd) Qualities.P1080.value else Qualities.Unknown.value
                    this.headers = baseHeaders
                }
            )
            
            Log.d("StreamedExtractor", "Successfully created extractor link for: $m3u8Url")
            return true
            
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Extraction failed: ${e.message}")
            return false
        }
    }

}