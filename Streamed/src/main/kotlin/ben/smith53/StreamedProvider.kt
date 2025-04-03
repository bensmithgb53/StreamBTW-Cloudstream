package ben.smith53

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document
import android.util.Log

class StreamedProvider : MainAPI() {
    override var name = "StreamedSU" // Set once, no reassignment
    override var mainUrl = "https://streamed.su" // Set once, no reassignment
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
    )
    private val cloudflareKiller = CloudflareKiller()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d("StreamedProvider", "Fetching main page: $mainUrl")
        val doc = app.get(mainUrl, headers = headers, interceptor = cloudflareKiller).document

        // Scrape categories and streams
        val categories = doc.select("div.category, section.category, div#categories > div")
            .mapNotNull { category ->
                val catName = category.selectFirst("h2, .category-title, span")?.text() ?: return@mapNotNull null
                val streams = category.select("a[href*='/category/'], div.match-card, li.stream-item")
                    .mapNotNull { stream ->
                        val title = stream.selectFirst(".title, h3, span")?.text() ?: return@mapNotNull null
                        val url = stream.attr("href").let { if (it.startsWith("/")) "$mainUrl$it" else it }
                        newLiveSearchResponse(
                            name = title,
                            url = url,
                            type = TvType.Live
                        ) { this.apiName = this@StreamedProvider.name }
                    }
                HomePageList(catName, streams, isHorizontalImages = false)
            }

        return if (categories.isNotEmpty()) {
            Log.d("StreamedProvider", "Found ${categories.size} categories")
            newHomePageResponse(categories)
        } else {
            Log.w("StreamedProvider", "No categories found, using flat list")
            val streams = doc.select("a[href*='/category/'], div.match-card, li.stream-item")
                .mapNotNull { stream ->
                    val title = stream.selectFirst(".title, h3, span")?.text() ?: return@mapNotNull null
                    val url = stream.attr("href").let { if (it.startsWith("/")) "$mainUrl$it" else it }
                    newLiveSearchResponse(
                        name = title,
                        url = url,
                        type = TvType.Live
                    ) { this.apiName = this@StreamedProvider.name }
                }
            newHomePageResponse(listOf(HomePageList("Live Streams", streams)))
        }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d("StreamedProvider", "Loading stream: $url")
        val doc = app.get(url, headers = headers, interceptor = cloudflareKiller).document
        val title = doc.selectFirst("h1, .stream-title, title")?.text() ?: url.split("/").last().replace("-", " ").capitalize()
        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = url
        ) { this.apiName = this@StreamedProvider.name }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StreamedProvider", "Starting loadLinks for: $data")

        val doc = app.get(data, headers = headers, interceptor = cloudflareKiller).document
        val iframeUrl = doc.selectFirst("iframe[src*='embedstreams.top']")?.attr("src") ?: run {
            Log.e("StreamedProvider", "No iframe found on $data")
            return false
        }
        Log.d("StreamedProvider", "Iframe URL: $iframeUrl")

        val iframeResponse = app.get(iframeUrl, headers = headers, interceptor = cloudflareKiller).text
        val varPairs = Regex("""(\w+)\s*=\s*["']([^"']+)["']""").findAll(iframeResponse)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val k = varPairs["k"] ?: run {
            Log.e("StreamedProvider", "Variable 'k' not found")
            return false
        }
        val i = varPairs["i"] ?: run {
            Log.e("StreamedProvider", "Variable 'i' not found")
            return false
        }
        val s = varPairs["s"] ?: run {
            Log.e("StreamedProvider", "Variable 's' not found")
            return false
        }
        Log.d("StreamedProvider", "Variables: k=$k, i=$i, s=$s")

        val fetchUrl = "https://embedstreams.top/fetch"
        val postData = mapOf("source" to k, "id" to i, "streamNo" to s)
        val fetchHeaders = headers + mapOf(
            "Content-Type" to "application/json",
            "Referer" to iframeUrl
        )
        val encryptedResponse = app.post(fetchUrl, headers = fetchHeaders, json = postData, interceptor = cloudflareKiller).text
        Log.d("StreamedProvider", "Encrypted response: $encryptedResponse")

        val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        val decryptResponse = app.post(decryptUrl, json = decryptPostData, headers = mapOf("Content-Type" to "application/json"))
            .parsedSafe<Map<String, String>>()
        val decryptedPath = decryptResponse?.get("decrypted") ?: run {
            Log.e("StreamedProvider", "Failed to decrypt: $decryptResponse")
            return false
        }
        Log.d("StreamedProvider", "Decrypted path: $decryptedPath")

        val m3u8Url = "https://rr.buytommy.top$decryptedPath"
        try {
            val testResponse = app.get(m3u8Url, headers = headers + mapOf("Referer" to iframeUrl), interceptor = cloudflareKiller)
            if (testResponse.code == 200) {
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = "Live Stream",
                        url = m3u8Url,
                        referer = iframeUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = true,
                        headers = headers
                    )
                )
                Log.d("StreamedProvider", "M3U8 URL added: $m3u8Url")
                return true
            } else {
                Log.e("StreamedProvider", "M3U8 test failed with code: ${testResponse.code}")
                return false
            }
        } catch (e: Exception) {
            Log.e("StreamedProvider", "M3U8 test failed: ${e.message}")
            return false
        }
    }
}