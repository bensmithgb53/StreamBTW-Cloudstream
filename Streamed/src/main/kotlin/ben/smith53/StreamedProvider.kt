package com.example.cloudstream3.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONArray

class StreamedSuProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "StreamedSU"
    override val hasMainPage = true
    override val hasSearch = false
    override val supportedTypes = setOf(TvType.Live)

    private val cloudflareKiller = CloudflareKiller()
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    // Fetch teams/matches from the API and display on the main page
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
                val streamUrl = "$mainUrl/watch/$gameId/$source/1" // StreamNo fixed to 1 as in original

                SearchResponse(
                    name = "$gameTitle ($source)",
                    url = streamUrl,
                    apiName = this.name,
                    type = TvType.Live
                )
            }
        }

        return HomePageResponse(listOf(HomePageList("Live Matches", teams)))
    }

    // Load the stream details when a team/source is clicked
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers, interceptor = cloudflareKiller).document
        val title = doc.select("title").text().ifEmpty { url.split("/")[4] } // Fallback to ID from URL
        return StreamResponse(
            name = title,
            url = url,
            apiName = this.name,
            type = TvType.Live,
            sources = emptyList() // Sources fetched in loadLinks
        )
    }

    // Extract m3u8 URLs from the stream page or fallback embed
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = headers, interceptor = cloudflareKiller).document
        var m3u8Url: String? = null

        // Try to find m3u8 in the initial page
        val embedUrl = doc.select("iframe").attr("src")
        if (embedUrl.isNotEmpty()) {
            val embedResponse = app.get(embedUrl, headers = headers, interceptor = cloudflareKiller).text
            m3u8Url = Regex("https?://[^\"']+\\.m3u8").find(embedResponse)?.value
        }

        // Fallback to embedstreams.top if no m3u8 found
        if (m3u8Url == null) {
            val parts = data.split("/")
            val id = parts[4]
            val source = parts[5]
            val streamNo = parts[6]
            val fallbackUrl = "https://embedstreams.top/embed/$source/$id/$streamNo"
            val fallbackResponse = app.get(fallbackUrl, headers = headers, interceptor = cloudflareKiller).text
            m3u8Url = Regex("https?://[^\"']+\\.m3u8").find(fallbackResponse)?.value
        }

        // If m3u8 is found, add it as a source
        if (m3u8Url != null) {
            callback.invoke(
                ExtractorLink(
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
            )
            return true
        }

        // Fallback to CloudStream's extractor if manual extraction fails
        embedUrl?.let { loadExtractor(it, mainUrl, callback) }
        return true
    }
}