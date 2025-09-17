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
            
            // Look for m3u8 URLs in the embed page
            val m3u8Urls = mutableListOf<String>()
            
            // Method 1: Look in script tags
            val scripts = embedDoc.select("script")
            for (script in scripts) {
                val scriptContent = script.html()
                val m3u8Regex = """(https?://[^"'\s]+\.m3u8[^"'\s]*)""".toRegex()
                val matches = m3u8Regex.findAll(scriptContent)
                matches.forEach { match ->
                    m3u8Urls.add(match.groupValues[1])
                }
            }
            
            // Method 2: Look for iframe sources
            val iframes = embedDoc.select("iframe[src]")
            for (iframe in iframes) {
                val iframeSrc = iframe.attr("src")
                if (iframeSrc.contains("m3u8")) {
                    m3u8Urls.add(iframeSrc)
                }
            }
            
            if (m3u8Urls.isEmpty()) {
                Log.e("StreamedExtractor", "No m3u8 URLs found in embed page")
                return false
            }
            
            // Use the first m3u8 URL found
            val m3u8Url = m3u8Urls.first()
            Log.d("StreamedExtractor", "Found m3u8 URL: $m3u8Url")
            
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