package ben.smith53

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty

class StreamedProvider : MainAPI() {
    override var name = "StreamedSU"
    override var mainUrl = "https://streamed.su"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
    )
    private val cloudflareKiller = CloudflareKiller()

    // Data classes for API responses
    data class APIMatch(
        val id: String,
        val title: String,
        val category: String,
        val date: Long,
        val poster: String?,
        val popular: Boolean,
        val teams: Teams?,
        val sources: List<Source>
    )

    data class Teams(
        val home: Team?,
        val away: Team?
    )

    data class Team(
        val name: String,
        val badge: String
    )

    data class Source(
        val source: String,
        val id: String
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d("StreamedProvider", "Fetching main page via API: $mainUrl/api/matches/live")
        val response = app.get("$mainUrl/api/matches/live", headers = headers, interceptor = cloudflareKiller)
        val matches = response.parsedSafe<List<APIMatch>>()
        
        if (matches.isNullOrEmpty()) {
            Log.w("StreamedProvider", "No matches found from API")
            return HomePageResponse(emptyList())
        }

        // Group matches by category
        val categories = matches.groupBy { it.category }.map { (category, matchList) ->
            val streams = matchList.map { match ->
                val title = match.teams?.let { "${it.home?.name ?: ""} vs ${it.away?.name ?: ""}" } ?: match.title
                LiveSearchResponse(
                    name = title,
                    url = "$mainUrl/match/${match.id}", // Construct a unique URL
                    apiName = this.name,
                    type = TvType.Live,
                    posterUrl = match.poster?.let { "$mainUrl$it" }
                )
            }
            HomePageList(category, streams, isHorizontalImages = false)
        }

        Log.d("StreamedProvider", "Found ${categories.size} categories with ${matches.size} total matches")
        return HomePageResponse(categories)
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d("StreamedProvider", "Loading stream: $url")
        val matchId = url.split("/").last()
        val apiUrl = "$mainUrl/api/matches/all" // Could optimize by using a specific match endpoint if available
        val matches = app.get(apiUrl, headers = headers, interceptor = cloudflareKiller)
            .parsedSafe<List<APIMatch>>()
        val match = matches?.find { it.id == matchId } ?: throw ErrorLoadingException("Match not found")

        val title = match.teams?.let { "${it.home?.name ?: ""} vs ${it.away?.name ?: ""}" } ?: match.title
        return LiveStreamLoadResponse(
            name = title,
            url = url,
            apiName = this.name,
            dataUrl = url,
            posterUrl = match.poster?.let { "$mainUrl$it" }
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StreamedProvider", "Starting loadLinks for: $data")
        val matchId = data.split("/").last()
        val matchResponse = app.get("$mainUrl/api/matches/all", headers = headers, interceptor = cloudflareKiller)
        val match = matchResponse.parsedSafe<List<APIMatch>>()?.find { it.id == matchId } ?: return false
        
        val source = match.sources.firstOrNull() ?: return false
        val streamResponse = app.get("$mainUrl/api/stream/${source.source}/${source.id}", 
            headers = headers, 
            interceptor = cloudflareKiller
        ).parsedSafe<Stream>() ?: return false

        // Rest of the link extraction logic remains similar
        val iframeUrl = streamResponse.embedUrl
        val iframeResponse = app.get(iframeUrl, headers = headers, interceptor = cloudflareKiller).text
        val varPairs = Regex("""(\w+)\s*=\s*["']([^"']+)["']""").findAll(iframeResponse)
            .associate { it.groupValues[1] to it.groupValues[2] }
        
        val k = varPairs["k"] ?: return false
        val i = varPairs["i"] ?: return false
        val s = varPairs["s"] ?: return false

        val fetchUrl = "https://embedstreams.top/fetch"
        val postData = mapOf("source" to k, "id" to i, "streamNo" to s)
        val fetchHeaders = headers + mapOf(
            "Content-Type" to "application/json",
            "Referer" to iframeUrl
        )
        val encryptedResponse = app.post(fetchUrl, headers = fetchHeaders, json = postData, interceptor = cloudflareKiller).text
        
        val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        val decryptResponse = app.post(decryptUrl, json = decryptPostData, headers = mapOf("Content-Type" to "application/json"))
            .parsedSafe<Map<String, String>>()
        val decryptedPath = decryptResponse?.get("decrypted") ?: return false

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
                        quality = if (streamResponse.hd) Qualities.P1080.value else Qualities.Unknown.value,
                        isM3u8 = true,
                        headers = headers
                    )
                )
                return true
            }
        } catch (e: Exception) {
            Log.e("StreamedProvider", "M3U8 test failed: ${e.message}")
            return false
        }
        return false
    }

    // Data class for stream response
    data class Stream(
        val id: String,
        val streamNo: Int,
        val language: String,
        val hd: Boolean,
        val embedUrl: String,
        val source: String
    )
}