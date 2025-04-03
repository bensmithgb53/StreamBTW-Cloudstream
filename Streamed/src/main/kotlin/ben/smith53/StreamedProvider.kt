package ben.smith53

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document
import android.util.Log

class StreamProvider : MainAPI() {
    override val name = "StreamedSU"
    override val mainUrl = "https://streamed.su" // Adjust if different
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
    )
    private val cloudflareKiller = CloudflareKiller()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d("StreamProvider", "Fetching main page: $mainUrl")
        val doc = app.get(mainUrl, headers = headers, interceptor = cloudflareKiller).document

        // Scrape categories and teams
        val categories = doc.select("div.category, section.category") // Adjust selector based on site structure
            .mapNotNull { category ->
                val catName = category.selectFirst("h2, .category-title")?.text() ?: return@mapNotNull null
                val streams = category.select("a.stream-link, div.stream-item") // Adjust selector
                    .mapNotNull { stream ->
                        val title = stream.selectFirst(".stream-title, h3")?.text() ?: return@mapNotNull null
                        val url = stream.attr("href")?.let { if (it.startsWith("/")) "$mainUrl$it" else it } ?: return@mapNotNull null
                        LiveSearchResponse(
                            name = title,
                            url = url,
                            apiName = this.name,
                            type = TvType.Live
                        )
                    }
                HomePageList(catName, streams, isHorizontalImages = false)
            }

        if (categories.isEmpty()) {
            Log.w("StreamProvider", "No categories found, falling back to flat list")
            val streams = doc.select("a.stream-link, div.stream-item")
                .mapNotNull { stream ->
                    val title = stream.selectFirst(".stream-title, h3")?.text() ?: return@mapNotNull null
                    val url = stream.attr("href")?.let { if (it.startsWith("/")) "$mainUrl$it" else it } ?: return@mapNotNull null
                    LiveSearchResponse(
                        name = title,
                        url = url,
                        apiName = this.name,
                        type = TvType.Live
                    )
                }
            return HomePageResponse(listOf(HomePageList("Live Streams", streams, isHorizontalImages = false)))
        }

        Log.d("StreamProvider", "Found ${categories.size} categories")
        return HomePageResponse(categories)
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d("StreamProvider", "Loading stream: $url")
        val doc = app.get(url, headers = headers, interceptor = cloudflareKiller).document
        val title = doc.selectFirst("h1, .stream-title")?.text() ?: url.split("/").last().replace("-", " ").capitalize()
        return LiveStreamLoadResponse(
            name = title,
            url = url,
            apiName = this.name,
            dataUrl = url,
            isM3u8 = true
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StreamProvider", "Starting loadLinks for: $data")

        // Step 1: Fetch stream page and extract iframe
        val doc = app.get(data, headers = headers, interceptor = cloudflareKiller).document
        val iframeUrl = doc.selectFirst("iframe[src]")?.attr("src") ?: run {
            Log.e("StreamProvider", "No iframe found on $data")
            return false
        }
        Log.d("StreamProvider", "Iframe URL: $iframeUrl")

        // Step 2: Fetch iframe content and extract variables
        val iframeResponse = app.get(iframeUrl, headers = headers, interceptor = cloudflareKiller).text
        val varPairs = Regex("""(\w+)\s*=\s*["']([^"']+)["']""").findAll(iframeResponse)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val k = varPairs["k"] ?: run {
            Log.e("StreamProvider", "Variable 'k' not found in iframe")
            return false
        }
        val i = varPairs["i"] ?: run {
            Log.e("StreamProvider", "Variable 'i' not found in iframe")
            return false
        }
        val s = varPairs["s"] ?: run {
            Log.e("StreamProvider", "Variable 's' not found in iframe")
            return false
        }
        Log.d("StreamProvider", "Variables: k=$k, i=$i, s=$s")

        // Step 3: Fetch encrypted string from /fetch
        val fetchUrl = "https://embedstreams.top/fetch" // Adjust if hosted on streamed.su
        val postData = mapOf("source" to k, "id" to i, "streamNo" to s)
        val fetchHeaders = headers + mapOf(
            "Content-Type" to "application/json",
            "Referer" to iframeUrl
        )
        val encryptedResponse = app.post(fetchUrl, headers = fetchHeaders, json = postData, interceptor = cloudflareKiller).text
        Log.d("StreamProvider", "Encrypted response: $encryptedResponse")

        // Step 4: Decrypt using Deno Deploy endpoint
        val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        val decryptResponse = app.post(decryptUrl, json = decryptPostData, headers = mapOf("Content-Type" to "application/json"))
            .parsedSafe<Map<String, String>>()
        val decryptedPath = decryptResponse?.get("decrypted") ?: run {
            Log.e("StreamProvider", "Failed to decrypt: $decryptResponse")
            return false
        }
        Log.d("StreamProvider", "Decrypted path: $decryptedPath")

        // Step 5: Construct and add M3U8 URL
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
                Log.d("StreamProvider", "M3U8 URL added: $m3u8Url")
                return true
            } else {
                Log.e("StreamProvider", "M3U8 test failed with code: ${testResponse.code}")
                return false
            }
        } catch (e: Exception) {
            Log.e("StreamProvider", "M3U8 test failed: ${e.message}")
            return false
        }
    }
}