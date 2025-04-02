package ben.smith53

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONArray
import android.util.Log

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "StreamedSU"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    private val cloudflareKiller = CloudflareKiller()
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    // ... (getMainPage and load functions remain unchanged) ...

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StreamedSU", "Starting loadLinks for: $data")
        
        // Initial request with Cloudflare bypass
        val response = try {
            app.get(data, headers = headers, interceptor = cloudflareKiller, timeout = 30)
        } catch (e: Exception) {
            Log.e("StreamedSU", "Failed to fetch page: ${e.message}")
            return false
        }
        val doc = response.document
        val text = response.text
        Log.d("StreamedSU", "Page response length: ${text.length}")
        Log.d("StreamedSU", "Page response preview: ${text.take(500)}")

        // Extract inline and external scripts
        val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")
        val externalScripts = doc.select("script[src]").map { it.attr("src") }
        Log.d("StreamedSU", "Inline script content length: ${scriptContent.length}")
        Log.d("StreamedSU", "External scripts: $externalScripts")

        var m3u8Url: String? = null
        var key: String? = null
        var iv: String? = null

        // Regex patterns for m3u8, key, and IV
        val m3u8Regex = Regex("https?://[\\S]+\\.m3u8(?:\\?[^\"']*)?")
        val keyRegex = Regex("key\\s*[:=]\\s*['\"]([a-fA-F0-9]+)['\"]")
        val ivRegex = Regex("iv\\s*[:=]\\s*['\"]([a-fA-F0-9]+)['\"]")

        // Step 1: Search inline scripts and page content
        m3u8Url = m3u8Regex.find(scriptContent)?.value ?: m3u8Regex.find(text)?.value
        key = keyRegex.find(scriptContent)?.groupValues?.get(1) ?: keyRegex.find(text)?.groupValues?.get(1)
        iv = ivRegex.find(scriptContent)?.groupValues?.get(1) ?: ivRegex.find(text)?.groupValues?.get(1)

        // Step 2: Check external scripts if no m3u8 found
        if (m3u8Url == null && externalScripts.isNotEmpty()) {
            for (scriptSrc in externalScripts) {
                val fullScriptUrl = if (scriptSrc.startsWith("http")) scriptSrc else "$mainUrl$scriptSrc"
                Log.d("StreamedSU", "Fetching external script: $fullScriptUrl")
                try {
                    val scriptResponse = app.get(fullScriptUrl, headers = headers, interceptor = cloudflareKiller, timeout = 15).text
                    Log.d("StreamedSU", "External script preview: ${scriptResponse.take(500)}")
                    m3u8Url = m3u8Regex.find(scriptResponse)?.value
                    if (m3u8Url != null) {
                        key = keyRegex.find(scriptResponse)?.groupValues?.get(1)
                        iv = ivRegex.find(scriptResponse)?.groupValues?.get(1)
                        break
                    }
                } catch (e: Exception) {
                    Log.e("StreamedSU", "Failed to fetch script $fullScriptUrl: ${e.message}")
                }
            }
        }

        // Step 3: Check iframe if still no m3u8
        if (m3u8Url == null) {
            val embedUrl = doc.select("iframe").attr("src")
            Log.d("StreamedSU", "Iframe URL: $embedUrl")
            if (embedUrl.isNotEmpty()) {
                try {
                    val embedResponse = app.get(embedUrl, headers = headers, interceptor = cloudflareKiller, timeout = 15).text
                    Log.d("StreamedSU", "Iframe response preview: ${embedResponse.take(500)}")
                    m3u8Url = m3u8Regex.find(embedResponse)?.value
                    key = keyRegex.find(embedResponse)?.groupValues?.get(1)
                    iv = ivRegex.find(embedResponse)?.groupValues?.get(1)
                } catch (e: Exception) {
                    Log.e("StreamedSU", "Failed to fetch iframe $embedUrl: ${e.message}")
                }
            }
        }

        // Step 4: Fallback URL construction
        if (m3u8Url == null && data.split("/").size >= 7) {
            val parts = data.split("/")
            val id = parts[4]
            val source = parts[5]
            val streamNo = parts[6]
            val fallbackUrl = "https://embedstreams.top/embed/$source/$id/$streamNo"
            Log.d("StreamedSU", "Trying fallback URL: $fallbackUrl")
            try {
                val fallbackResponse = app.get(fallbackUrl, headers = headers, interceptor = cloudflareKiller, timeout = 15).text
                Log.d("StreamedSU", "Fallback response preview: ${fallbackResponse.take(500)}")
                m3u8Url = m3u8Regex.find(fallbackResponse)?.value
                key = keyRegex.find(fallbackResponse)?.groupValues?.get(1)
                iv = ivRegex.find(fallbackResponse)?.groupValues?.get(1)
            } catch (e: Exception) {
                Log.e("StreamedSU", "Failed to fetch fallback $fallbackUrl: ${e.message}")
            }
        }

        // Log results
        Log.d("StreamedSU", "m3u8Url: $m3u8Url, key: $key, iv: $iv")

        // Step 5: Create ExtractorLink if m3u8 found
        if (m3u8Url != null) {
            val extractorLink = ExtractorLink(
                source = this.name,
                name = "Live Stream",
                url = m3u8Url,
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                isM3u8 = true,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
                    "Referer" to "https://embedme.top/"
                )
            )
            callback(extractorLink)
            Log.d("StreamedSU", "Link extracted successfully: $m3u8Url")
            return true
        }

        // Step 6: Final fallback to loadExtractor
        val embedUrl = doc.select("iframe").attr("src")
        if (embedUrl.isNotEmpty()) {
            Log.d("StreamedSU", "Falling back to loadExtractor with: $embedUrl")
            return loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
        }

        Log.d("StreamedSU", "No links found")
        return false
    }
}