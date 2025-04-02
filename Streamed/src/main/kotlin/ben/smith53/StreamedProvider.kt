package ben.smith53

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONArray

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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val apiUrl = "$mainUrl/api/matches/all"
        val response = app.get(apiUrl, headers = headers, interceptor = cloudflareKiller)
        val jsonArray = JSONArray(response.text)

        val teams = (0 until jsonArray.length()).flatMap { i ->
            val game = jsonArray.getJSONObject(i)
            val gameId = game.getString("id")
            val gameTitle = game.getString("title")
            val sources = game.getJSONArray("sources")

            (0 until sources.length()).map { j ->
                val sourceObj = sources.getJSONObject(j)
                val source = sourceObj.getString("source")
                val streamUrl = "$mainUrl/watch/$gameId/$source/1"

                newLiveSearchResponse(
                    name = "$gameTitle ($source)",
                    url = streamUrl,
                    type = TvType.Live
                )
            }
        }

        return newHomePageResponse("Live Matches", teams)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers, interceptor = cloudflareKiller).document
        val title = doc.select("title").text().ifEmpty { url.split("/")[4] }
        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = url
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, headers = headers, interceptor = cloudflareKiller)
        val doc = response.document
        val text = response.text

        // Look for script tags or external JS files
        val scriptContent = doc.select("script").map { it.html() }.joinToString("\n")
        val externalScripts = doc.select("script[src]").map { it.attr("src") }

        var m3u8Url: String? = null
        var key: String? = null
        var iv: String? = null

        // Step 1: Search inline scripts for m3u8 or key/IV
        val m3u8Regex = Regex("https?://[\\S]+\\.m3u8(?:\\?[^\"']*)?")
        m3u8Url = m3u8Regex.find(scriptContent)?.value
        if (m3u8Url == null) {
            m3u8Url = m3u8Regex.find(text)?.value // Fallback to full page text
        }

        // Look for encryption key/IV (common patterns)
        val keyRegex = Regex("key\\s*[:=]\\s*['\"]([a-fA-F0-9]+)['\"]")
        val ivRegex = Regex("iv\\s*[:=]\\s*['\"]([a-fA-F0-9]+)['\"]")
        key = keyRegex.find(scriptContent)?.groupValues?.get(1)
        iv = ivRegex.find(scriptContent)?.groupValues?.get(1)

        // Step 2: Check external scripts if no m3u8 found
        if (m3u8Url == null && externalScripts.isNotEmpty()) {
            for (scriptSrc in externalScripts) {
                val fullScriptUrl = if (scriptSrc.startsWith("http")) scriptSrc else "$mainUrl$scriptSrc"
                val scriptResponse = app.get(fullScriptUrl, headers = headers, interceptor = cloudflareKiller).text
                m3u8Url = m3u8Regex.find(scriptResponse)?.value
                if (m3u8Url != null) {
                    key = keyRegex.find(scriptResponse)?.groupValues?.get(1)
                    iv = ivRegex.find(scriptResponse)?.groupValues?.get(1)
                    break
                }
            }
        }

        // Step 3: Fallback to iframe or constructed URL
        if (m3u8Url == null) {
            val embedUrl = doc.select("iframe").attr("src")
            if (embedUrl.isNotEmpty()) {
                val embedResponse = app.get(embedUrl, headers = headers, interceptor = cloudflareKiller).text
                m3u8Url = m3u8Regex.find(embedResponse)?.value
                key = keyRegex.find(embedResponse)?.groupValues?.get(1)
                iv = ivRegex.find(embedResponse)?.groupValues?.get(1)
            }
            if (m3u8Url == null && data.split("/").size >= 7) {
                val parts = data.split("/")
                val id = parts[4]
                val source = parts[5]
                val streamNo = parts[6]
                val fallbackUrl = "https://embedstreams.top/embed/$source/$id/$streamNo"
                val fallbackResponse = app.get(fallbackUrl, headers = headers, interceptor = cloudflareKiller).text
                m3u8Url = m3u8Regex.find(fallbackResponse)?.value
                key = keyRegex.find(fallbackResponse)?.groupValues?.get(1)
                iv = ivRegex.find(fallbackResponse)?.groupValues?.get(1)
            }
        }

        // Step 4: If m3u8 URL is found, build ExtractorLink
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
            // TODO: Add key/IV support if Cloudstream supports it (currently not directly supported)
            callback(extractorLink)
            return true
        }

        // Step 5: Final fallback to loadExtractor
        val embedUrl = doc.select("iframe").attr("src")
        if (embedUrl.isNotEmpty()) {
            return loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
        }

        return false // No links found
    }
}