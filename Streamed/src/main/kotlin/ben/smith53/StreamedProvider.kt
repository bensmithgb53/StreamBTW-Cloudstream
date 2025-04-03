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

    data class APIMatch(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("category") val category: String,
        @JsonProperty("date") val date: Long,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("popular") val popular: Boolean,
        @JsonProperty("teams") val teams: Teams?,
        @JsonProperty("sources") val sources: List<Source>
    )

    data class Teams(
        @JsonProperty("home") val home: Team?,
        @JsonProperty("away") val away: Team?
    )

    data class Team(
        @JsonProperty("name") val name: String,
        @JsonProperty("badge") val badge: String
    )

    data class Source(
        @JsonProperty("source") val source: String,
        @JsonProperty("id") val id: String
    )

    data class Stream(
        @JsonProperty("id") val id: String,
        @JsonProperty("streamNo") val streamNo: Int,
        @JsonProperty("language") val language: String,
        @JsonProperty("hd") val hd: Boolean,
        @JsonProperty("embedUrl") val embedUrl: String,
        @JsonProperty("source") val source: String
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d("StreamedProvider", "Fetching main page via API: $mainUrl/api/matches/live")
        Log.d("StreamedProvider", "DEBUG: Starting getMainPage - Version April 03, 2025")
        
        val response = try {
            app.get("$mainUrl/api/matches/live", headers = headers, interceptor = cloudflareKiller)
        } catch (e: Exception) {
            Log.e("StreamedProvider", "Failed to fetch main page: ${e.message}")
            return newHomePageResponse(emptyList())
        }
        
        Log.d("StreamedProvider", "DEBUG: Response received")
        
        // Initialize matchGroups directly without reassignment
        val matchGroups = response.parsedSafe<List<APIMatch>>()?.let { matches ->
            matches.groupBy { it.category }.map { entry ->
                HomePageList(
                    name = entry.key,
                    list = entry.value.map { match ->
                        newLiveSearchResponse(
                            name = match.teams?.let { "${it.home?.name ?: ""} vs ${it.away?.name ?: ""}" } ?: match.title,
                            url = "$mainUrl/match/${match.id}",
                            type = TvType.Live
                        ) {
                            this.apiName = this@StreamedProvider.name
                            this.posterUrl = match.poster?.let { "$mainUrl$it" }
                        }
                    },
                    isHorizontalImages = false
                )
            }
        } ?: run {
            Log.w("StreamedProvider", "No matches found from API")
            emptyList()
        }
        
        return newHomePageResponse(matchGroups).also {
            Log.d("StreamedProvider", "Found ${matchGroups.size} categories with ${response.parsedSafe<List<APIMatch>>()?.size ?: 0} total matches")
        }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d("StreamedProvider", "Loading stream: $url")
        val matchId = url.split("/").last()
        val apiUrl = "$mainUrl/api/matches/all"
        
        val matches = try {
            app.get(apiUrl, headers = headers, interceptor = cloudflareKiller)
                .parsedSafe<List<APIMatch>>()
        } catch (e: Exception) {
            Log.e("StreamedProvider", "Failed to fetch matches: ${e.message}")
            throw ErrorLoadingException("Failed to load matches: ${e.message}")
        }
        
        val match = matches?.find { it.id == matchId } ?: throw ErrorLoadingException("Match not found")
        val title = match.teams?.let { "${it.home?.name ?: ""} vs ${it.away?.name ?: ""}" } ?: match.title
        
        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = url
        ) {
            this.apiName = this@StreamedProvider.name
            this.posterUrl = match.poster?.let { "$mainUrl$it" }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StreamedProvider", "Starting loadLinks for: $data")
        val matchId = data.split("/").last()
        
        val match = try {
            val matchResponse = app.get("$mainUrl/api/matches/all", headers = headers, interceptor = cloudflareKiller)
            matchResponse.parsedSafe<List<APIMatch>>()?.find { it.id == matchId }
        } catch (e: Exception) {
            Log.e("StreamedProvider", "Failed to fetch match: ${e.message}")
            return false
        } ?: run {
            Log.e("StreamedProvider", "Match not found for ID: $matchId")
            return false
        }

        val source = match.sources.firstOrNull() ?: run {
            Log.e("StreamedProvider", "No sources found for match: $matchId")
            return false
        }

        val streamResponse = try {
            app.get("$mainUrl/api/stream/${source.source}/${source.id}", 
                headers = headers, 
                interceptor = cloudflareKiller
            ).parsedSafe<Stream>()
        } catch (e: Exception) {
            Log.e("StreamedProvider", "Failed to fetch stream: ${e.message}")
            return false
        } ?: run {
            Log.e("StreamedProvider", "Failed to parse stream response")
            return false
        }

        val iframeUrl = streamResponse.embedUrl
        val iframeResponse = try {
            app.get(iframeUrl, headers = headers, interceptor = cloudflareKiller).text
        } catch (e: Exception) {
            Log.e("StreamedProvider", "Failed to fetch iframe: ${e.message}")
            return false
        }

        val varPairs = Regex("""(\w+)\s*=\s*["']([^"']+)["']""").findAll(iframeResponse)
            .associate { it.groupValues[1] to it.groupValues[2] }

        val (k, i, s) = try {
            Triple(
                varPairs["k"] ?: throw IllegalStateException("Variable 'k' not found"),
                varPairs["i"] ?: throw IllegalStateException("Variable 'i' not found"),
                varPairs["s"] ?: throw IllegalStateException("Variable 's' not found")
            )
        } catch (e: IllegalStateException) {
            Log.e("StreamedProvider", "${e.message} in iframe")
            return false
        }
        
        Log.d("StreamedProvider", "Variables: k=$k, i=$i, s=$s")

        val fetchUrl = "https://embedstreams.top/fetch"
        val postData = mapOf("source" to k, "id" to i, "streamNo" to s)
        val fetchHeaders = headers + mapOf(
            "Content-Type" to "application/json",
            "Referer" to iframeUrl
        )
        
        val encryptedResponse = try {
            app.post(fetchUrl, headers = fetchHeaders, json = postData, interceptor = cloudflareKiller).text
        } catch (e: Exception) {
            Log.e("StreamedProvider", "Failed to fetch encrypted response: ${e.message}")
            return false
        }
        
        Log.d("StreamedProvider", "Encrypted response: $encryptedResponse")

        val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        
        val decryptedPath = try {
            app.post(decryptUrl, json = decryptPostData, headers = mapOf("Content-Type" to "application/json"))
                .parsedSafe<Map<String, String>>()
                ?.get("decrypted") ?: throw IllegalStateException("No decrypted path in response")
        } catch (e: Exception) {
            Log.e("StreamedProvider", "Failed to decrypt: ${e.message}")
            return false
        }
        
        Log.d("StreamedProvider", "Decrypted path: $decryptedPath")

        val m3u8Url = "https://rr.buytommy.top$decryptedPath"
        return try {
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
                Log.d("StreamedProvider", "M3U8 URL added: $m3u8Url")
                true
            } else {
                Log.e("StreamedProvider", "M3U8 test failed with code: ${testResponse.code}")
                false
            }
        } catch (e: Exception) {
            Log.e("StreamedProvider", "M3U8 test failed: ${e.message}")
            false
        }
    }
}