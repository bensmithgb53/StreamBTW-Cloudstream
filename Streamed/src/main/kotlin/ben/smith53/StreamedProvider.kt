package ben.smith53

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import java.net.URI
import ben.smith53.extractors.StreamedExtractor
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.pk"
    override var name = "Streamed"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"

    override val mainPage = mainPageOf(
        "$mainUrl/api/matches/live/popular.json" to "Live Now",
        "$mainUrl/api/matches/football/popular.json" to "Football",
        "$mainUrl/api/matches/basketball/popular.json" to "Basketball",
        "$mainUrl/api/matches/american-football/popular.json" to "American Football",
        "$mainUrl/api/matches/hockey/popular.json" to "Hockey",
        "$mainUrl/api/matches/baseball/popular.json" to "Baseball",
        "$mainUrl/api/matches/fight/popular.json" to "Fight",
        "$mainUrl/api/matches/motor-sports/popular.json" to "Motor Sports"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = arrayListOf<HomePageList>()
        
        try {
            val response = app.get(request.data).text
            val matches = response.let { 
                try {
                    jacksonObjectMapper().readValue<List<Map<String, Any>>>(it)
                } catch (e: Exception) {
                    println("JSON parse error: ${e.message}")
                    emptyList()
                }
            }
            
            if (matches.isNotEmpty()) {
                val categoryList = matches.mapNotNull { match ->
                    try {
                        val title = match["title"] as? String ?: "Unknown Event"
                        val id = match["id"] as? String ?: ""
                        
                        newLiveSearchResponse(
                            name = title,
                            url = "$mainUrl/watch/$id",
                            type = TvType.Live
                        )
                    } catch (e: Exception) {
                        println("StreamedProvider error: ${e.message}")
                        null
                    }
                }
                
                if (categoryList.isNotEmpty()) {
                    items.add(HomePageList(request.name, categoryList))
                }
            }
        } catch (e: Exception) {
            println("StreamedProvider error: ${e.message}")
        }
        
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResults = arrayListOf<SearchResponse>()
        
        try {
            // Search through all categories
            val categories = listOf("live", "football", "basketball", "american-football", "hockey", "baseball", "fight", "motor-sports")
            
            for (category in categories) {
                try {
                    val response = app.get("$mainUrl/api/matches/$category/popular.json").text
                    val matches = response.let { 
                        try {
                            jacksonObjectMapper().readValue<List<Map<String, Any>>>(it)
                        } catch (e: Exception) {
                            println("JSON parse error: ${e.message}")
                            emptyList()
                        }
                    }
                    
                    matches.forEach { match ->
                        try {
                            val title = match["title"] as? String ?: ""
                            val id = match["id"] as? String ?: ""
                            
                            if (title.contains(query, ignoreCase = true)) {
                                searchResults.add(
                                    newLiveSearchResponse(
                                        name = title,
                                        url = "$mainUrl/watch/$id",
                                        type = TvType.Live
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            println("StreamedProvider error: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    println("StreamedProvider error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            println("StreamedProvider error: ${e.message}")
        }
        
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val eventId = url.substringAfterLast("/")
            val doc = app.get(url).document
            
            // Extract event information from the page
            val title = doc.selectFirst("title")?.text()?.substringBefore(" - Streamed") ?: "Live Event"
            
            // Try to get event details from API
            var eventDetails: Map<String, Any>? = null
            val categories = listOf("live", "football", "basketball", "american-football", "hockey", "baseball", "fight", "motor-sports")
            
            for (category in categories) {
                try {
                    val response = app.get("$mainUrl/api/matches/$category/popular.json").text
                    val matches = response.let { 
                        try {
                            jacksonObjectMapper().readValue<List<Map<String, Any>>>(it)
                        } catch (e: Exception) {
                            println("JSON parse error: ${e.message}")
                            emptyList()
                        }
                    }
                    eventDetails = matches.find { it["id"] == eventId }
                    if (eventDetails != null) break
                } catch (e: Exception) {
                    println("StreamedProvider error: ${e.message}")
                }
            }
            
            return newLiveStreamLoadResponse(
                name = title,
                url = url,
                dataUrl = url
            )
        } catch (e: Exception) {
            println("StreamedProvider error: ${e.message}")
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val eventId = data.substringAfterLast("/")
            
            // Get available streams from API
            val response = app.get("$mainUrl/api/stream/$eventId").text
            val streams = response.let { 
                try {
                    jacksonObjectMapper().readValue<List<Map<String, Any>>>(it)
                } catch (e: Exception) {
                    println("JSON parse error: ${e.message}")
                    emptyList()
                }
            }
            
            // Process each stream
            streams.forEach { stream ->
                try {
                    val source = stream["source"] as? String ?: "alpha"
                    val streamNo = stream["streamNo"] as? Int ?: 1
                    val embedUrl = stream["embedUrl"] as? String
                    
                    if (embedUrl != null) {
                        // Use StreamedExtractor to extract the actual stream
                        val extractor = StreamedExtractor()
                        val links = extractor.getUrl(embedUrl, "")
                        links?.forEach { link ->
                            callback(link)
                        }
                    }
                } catch (e: Exception) {
                    println("StreamedProvider error: ${e.message}")
                }
            }
            
            return streams.isNotEmpty()
        } catch (e: Exception) {
            println("StreamedProvider error: ${e.message}")
            return false
        }
    }
    
    private suspend fun extractIframeSrc(doc: org.jsoup.nodes.Document, pageUrl: String): String? {
        try {
            // Look for iframe in the page
            val iframe = doc.selectFirst("iframe[src]")
            if (iframe != null) {
                return iframe.attr("src")
            }
            
            // Look for embed code in JavaScript
            val scripts = doc.select("script")
            for (script in scripts) {
                val scriptContent = script.html()
                if (scriptContent.contains("embedsports.top") || scriptContent.contains("iframe")) {
                    val iframeRegex = """src=["']([^"']*embedsports\.top[^"']*)["']""".toRegex()
                    val match = iframeRegex.find(scriptContent)
                    if (match != null) {
                        return match.groupValues[1]
                    }
                }
            }
            
            // Try to construct iframe URL based on page structure
            val eventId = pageUrl.substringAfterLast("/watch/").substringBefore("/")
            val sourceType = pageUrl.substringAfterLast("/$eventId/").substringBefore("/")
            val streamNumber = pageUrl.substringAfterLast("/")
            
            return "https://embedsports.top/embed/$sourceType/$eventId/$streamNumber"
        } catch (e: Exception) {
            println("StreamedProvider error: ${e.message}")
            return null
        }
    }

}