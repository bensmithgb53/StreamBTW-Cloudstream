package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import java.net.URI

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.pk"
    override var name = "Streamed"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"

    override val mainPage = mainPageOf(
        "$mainUrl/api/matches/live/popular.json" to "Popular Live",
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
            val response = app.get(request.data).parsedSafe<List<StreamedMatch>>()
            response?.let { matches ->
                val homePageList = matches.mapNotNull { match ->
                    try {
                        val posterUrl = when {
                            match.poster?.startsWith("/api/images/") == true -> "$mainUrl${match.poster}"
                            match.poster?.startsWith("http") == true -> match.poster
                            else -> null
                        }
                        
                        val title = match.title ?: "Unknown Event"
                        val category = match.category?.replaceFirstChar { it.uppercase() } ?: "Live"
                        
                        newLiveSearchResponse(
                            name = title,
                            url = "$mainUrl/watch/${match.id}",
                            apiName = this.name,
                            type = TvType.Live,
                            posterUrl = posterUrl,
                            plot = "Live $category event"
                        )
                    } catch (e: Exception) {
                        currentLogger().d(e.stackTraceToString())
                        null
                    }
                }
                
                if (homePageList.isNotEmpty()) {
                    items.add(HomePageList(request.name, homePageList))
                }
            }
        } catch (e: Exception) {
            currentLogger().d(e.stackTraceToString())
        }
        
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResults = arrayListOf<SearchResponse>()
        
        try {
            // Search through all categories
            val categories = listOf("live", "football", "basketball", "american-football", "hockey", "baseball", "fight", "motor-sports")
            
            for (category in categories) {
                try {
                    val response = app.get("$mainUrl/api/matches/$category/popular.json").parsedSafe<List<StreamedMatch>>()
                    response?.let { matches ->
                        matches.filter { 
                            it.title?.contains(query, ignoreCase = true) == true 
                        }.forEach { match ->
                            val posterUrl = when {
                                match.poster?.startsWith("/api/images/") == true -> "$mainUrl${match.poster}"
                                match.poster?.startsWith("http") == true -> match.poster
                                else -> null
                            }
                            
                            searchResults.add(
                                LiveSearchResponse(
                                    name = match.title ?: "Unknown Event",
                                    url = "$mainUrl/watch/${match.id}",
                                    apiName = this.name,
                                    type = TvType.Live,
                                    posterUrl = posterUrl,
                                    plot = "Live ${match.category?.replaceFirstChar { it.uppercase() }} event"
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    currentLogger().d(e.stackTraceToString())
                }
            }
        } catch (e: Exception) {
            currentLogger().d(e.stackTraceToString())
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
            var eventDetails: StreamedMatch? = null
            val categories = listOf("live", "football", "basketball", "american-football", "hockey", "baseball", "fight", "motor-sports")
            
            for (category in categories) {
                try {
                    val response = app.get("$mainUrl/api/matches/$category/popular.json").parsedSafe<List<StreamedMatch>>()
                    eventDetails = response?.find { it.id == eventId }
                    if (eventDetails != null) break
                } catch (e: Exception) {
                    currentLogger().d(e.stackTraceToString())
                }
            }
            
            val posterUrl = when {
                eventDetails?.poster?.startsWith("/api/images/") == true -> "$mainUrl${eventDetails.poster}"
                eventDetails?.poster?.startsWith("http") == true -> eventDetails.poster
                else -> null
            }
            
            val plot = buildString {
                eventDetails?.let { event ->
                    append("Live ${event.category?.replaceFirstChar { it.uppercase() }} event")
                    event.teams?.let { teams ->
                        append("\n${teams.home?.name} vs ${teams.away?.name}")
                    }
                    event.date?.let { date ->
                        append("\nScheduled: ${java.util.Date(date)}")
                    }
                }
            }
            
            return newLiveStreamLoadResponse(
                name = title,
                url = url,
                apiName = this.name,
                dataUrl = url,
                posterUrl = posterUrl,
                plot = plot.ifEmpty { "Live streaming event" }
            )
        } catch (e: Exception) {
            currentLogger().d(e.stackTraceToString())
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
            val doc = app.get(data).document
            
            // Find all available stream sources
            val sources = mutableListOf<StreamSource>()
            
            // Look for sources in the page structure
            doc.select("a[href*='/watch/'][href*='/']").forEach { element ->
                val href = element.attr("href")
                if (href.contains("/$eventId/")) {
                    val sourceType = href.substringAfterLast("/$eventId/").substringBefore("/")
                    val streamNumber = href.substringAfterLast("/")
                    val quality = element.text().let { text ->
                        when {
                            text.contains("HD", ignoreCase = true) -> "HD"
                            text.contains("4K", ignoreCase = true) -> "4K"
                            else -> "SD"
                        }
                    }
                    val language = element.text().let { text ->
                        when {
                            text.contains("English", ignoreCase = true) -> "English"
                            text.contains("Español", ignoreCase = true) -> "Spanish"
                            text.contains("Français", ignoreCase = true) -> "French"
                            text.contains("Deutsch", ignoreCase = true) -> "German"
                            text.contains("Italiano", ignoreCase = true) -> "Italian"
                            else -> "Unknown"
                        }
                    }
                    
                    sources.add(StreamSource(sourceType, streamNumber, quality, language))
                }
            }
            
            // If no sources found in DOM, try common source patterns
            if (sources.isEmpty()) {
                val commonSources = listOf("admin", "alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "hotel")
                commonSources.forEach { sourceType ->
                    for (i in 1..3) {
                        sources.add(StreamSource(sourceType, i.toString(), "HD", "English"))
                    }
                }
            }
            
            // Extract streams from each source
            sources.forEach { source ->
                try {
                    val streamUrl = "$mainUrl/watch/$eventId/${source.type}/${source.number}"
                    val streamDoc = app.get(streamUrl).document
                    
                    // Look for embed code button and extract iframe src
                    val embedButton = streamDoc.selectFirst("button[data-*='embed'], button:contains(Embed)")
                    if (embedButton != null) {
                        // Try to find iframe src in the page
                        val iframeSrc = extractIframeSrc(streamDoc, streamUrl)
                        if (iframeSrc != null) {
                            // Use StreamedExtractor to extract the actual stream
                            val extractor = StreamedExtractor()
                            extractor.getSafeUrl(iframeSrc, "", subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) {
                    currentLogger().d(e.stackTraceToString())
                }
            }
            
            return true
        } catch (e: Exception) {
            currentLogger().d(e.stackTraceToString())
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
            currentLogger().d(e.stackTraceToString())
            return null
        }
    }

    data class StreamedMatch(
        val id: String?,
        val title: String?,
        val category: String?,
        val date: Long?,
        val poster: String?,
        val popular: Boolean?,
        val teams: Teams?,
        val sources: List<Source>?
    )

    data class Teams(
        val home: Team?,
        val away: Team?
    )

    data class Team(
        val name: String?,
        val badge: String?
    )

    data class Source(
        val source: String?,
        val id: String?
    )
    
    data class StreamSource(
        val type: String,
        val number: String,
        val quality: String,
        val language: String
    )
}