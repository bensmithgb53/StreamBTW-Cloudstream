package ben.smith53

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.util.zip.GZIPInputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class StreamedProvider(private val context: Context) : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed Sports"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"
    override val hasMainPage = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val hasDownloadSupport = false
    override val instantLinkLoading = true

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        "Referer" to "https://streamed.su/",
        "Accept-Encoding" to "gzip, deflate, br, zstd",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
    )

    private val embedHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Referer" to "https://embedme.top/",
        "Origin" to "https://embedme.top",
        "Content-Type" to "application/json",
        "Accept" to "*/*"
    )

    companion object {
        private const val posterBase = "https://streamed.su/api/images/poster"
        private const val badgeBase = "https://streamed.su/api/images/badge"
        private val mapper = jacksonObjectMapper()
    }

    data class APIMatch(
        val id: String,
        val title: String,
        val category: String,
        val date: Long,
        val poster: String? = null,
        val popular: Boolean,
        val teams: Teams? = null,
        val sources: List<Source>
    ) {
        data class Teams(
            val home: Team? = null,
            val away: Team? = null
        )
        data class Team(
            val name: String,
            val badge: String? = null
        )
        data class Source(
            val source: String,
            val id: String
        )
    }

    data class Stream(
        val id: String,
        val streamNo: Int,
        val language: String,
        val hd: Boolean,
        val embedUrl: String,
        val source: String
    )

    private suspend fun fetchLiveMatches(): List<HomePageList> {
        val response = app.get("$mainUrl/api/matches/all", headers = headers, timeout = 30)
        val text = if (response.headers["Content-Encoding"] == "gzip") {
            GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
        } else {
            response.text
        }
        println("Matches API response: $text")
        val matches: List<APIMatch> = mapper.readValue(text)
        val currentTime = System.currentTimeMillis() / 1000
        println("Current time: $currentTime, Filter threshold: ${currentTime - 24 * 60 * 60}")
        val liveMatches = matches.filter { it.date / 1000 >= (currentTime - 24 * 60 * 60) }

        if (liveMatches.isEmpty()) {
            return listOf(
                HomePageList(
                    "No Live Matches",
                    listOf(newLiveSearchResponse("No live matches available", "$mainUrl|alpha|default", TvType.Live)),
                    isHorizontalImages = false
                )
            )
        }

        val groupedMatches = liveMatches.groupBy { it.category.capitalize() }
        return groupedMatches.map { (category, categoryMatches) ->
            val eventList = categoryMatches.mapNotNull { match ->
                val source = match.sources.firstOrNull() ?: return@mapNotNull null
                println("Match: ${match.title}, Source: ${source.source}, ID: ${source.id}")
                val posterUrl = match.poster?.let { 
                    val cleanPoster = it.removeSuffix(".webp")
                    if (cleanPoster.startsWith("/")) "$mainUrl/api/images/proxy$cleanPoster.webp" 
                    else "$mainUrl/api/images/proxy/$cleanPoster.webp" 
                } ?: match.teams?.let { teams ->
                    teams.home?.badge?.let { homeBadge ->
                        teams.away?.badge?.let { awayBadge ->
                            "$posterBase/$homeBadge/$awayBadge.webp"
                        }
                    }
                }
                val homeBadge = match.teams?.home?.badge?.let { "$badgeBase/$it.webp" }
                newLiveSearchResponse(match.title, "${match.id}|${source.source}|${source.id}", TvType.Live) {
                    this.posterUrl = posterUrl ?: homeBadge
                }
            }
            HomePageList(category, eventList, isHorizontalImages = false)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return newHomePageResponse(fetchLiveMatches())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return fetchLiveMatches().flatMap { it.list }.filter {
            query.lowercase().replace(" ", "") in it.name.lowercase().replace(" ", "")
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val correctedUrl = if (url.startsWith("$mainUrl/watch/")) {
            val parts = url.split("/")
            val matchId = parts[parts.indexOf("watch") + 1].split("-").last()
            val sourceType = parts[parts.size - 2]
            val sourceId = parts.last()
            "$matchId|$sourceType|$sourceId"
        } else {
            url
        }
        val parts = correctedUrl.split("|")
        val matchId = parts[0].split("/").last()
        val sourceType = if (parts.size > 1) parts[1] else "alpha"
        val sourceId = if (parts.size > 2) parts[2] else matchId

        println("Loading stream with corrected URL: $correctedUrl")
        println("Parsed: matchId=$matchId, sourceType=$sourceType, sourceId=$sourceId")

        val matchResponse = app.get("$mainUrl/api/matches/all", headers = headers, timeout = 30)
        val matchesText = if (matchResponse.headers["Content-Encoding"] == "gzip") {
            GZIPInputStream(matchResponse.body.byteStream()).bufferedReader().use { it.readText() }
        } else {
            matchResponse.text
        }
        val matches: List<APIMatch> = mapper.readValue(matchesText)
        val match = matches.find { it.id == matchId } ?: throw ErrorLoadingException("Match not found")
        val teamSlug = match.teams?.let { teams ->
            "${teams.home?.name?.lowercase()?.replace(" ", "-")}-vs-${teams.away?.name?.lowercase()?.replace(" ", "-")}"
        } ?: match.title.lowercase().replace(" ", "-")

        val streamUrl = "$mainUrl/api/stream/$sourceType/$sourceId"
        val streamHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            "Referer" to "https://streamed.su/"
        )
        val response = app.get(streamUrl, headers = streamHeaders, timeout = 30)
        println("Stream API request: URL=$streamUrl, Headers=$streamHeaders, Status=${response.code}")
        val text = if (response.headers["Content-Encoding"] == "gzip") {
            GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
        } else {
            response.text
        }
        println("Stream API response for $streamUrl: $text")

        if (!response.isSuccessful || text.contains("Not Found")) {
            println("Stream not found for $streamUrl, status=${response.code}, attempting fallback with matchId")
            return newLiveStreamLoadResponse(
                "Stream Unavailable - $matchId",
                correctedUrl,
                "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
            ) {
                this.apiName = this@StreamedProvider.name
                this.plot = "The requested stream could not be found. Using a test stream instead."
            }
        }

        val streams: List<Stream> = try {
            mapper.readValue(text)
        } catch (e: Exception) {
            println("Failed to parse streams: ${e.message}")
            emptyList()
        }
        if (streams.isEmpty()) {
            println("No streams available for $streamUrl")
            return newLiveStreamLoadResponse(
                "No Streams Available - $matchId",
                correctedUrl,
                "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
            ) {
                this.apiName = this@StreamedProvider.name
                this.plot = "No streams were returned for this match. Using a test stream instead."
            }
        }

        val firstStream = streams.first()
        println("Selected default stream: id=${firstStream.id}, streamNo=${firstStream.streamNo}, hd=${firstStream.hd}, source=${firstStream.source}")
        val encrypted = fetchEncryptedData(sourceType, matchId, firstStream.streamNo.toString())
        val decrypted = decryptWithWebView(encrypted ?: throw ErrorLoadingException("Failed to fetch encrypted stream data"), sourceType, teamSlug, firstStream.streamNo.toString())
        val defaultM3u8Url = "https://rr.vipstreams.in$decrypted"
        println("Default M3U8 URL: $defaultM3u8Url")

        return newLiveStreamLoadResponse(
            "${firstStream.source} - ${if (firstStream.hd) "HD" else "SD"}",
            correctedUrl,
            defaultM3u8Url
        ) {
            this.apiName = this@StreamedProvider.name
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val matchId = parts[0].split("/").last()
        val sourceType = if (parts.size > 1) parts[1] else "alpha"
        val sourceId = if (parts.size > 2) parts[2] else matchId

        val matchResponse = app.get("$mainUrl/api/matches/all", headers = headers, timeout = 30)
        val matchesText = if (matchResponse.headers["Content-Encoding"] == "gzip") {
            GZIPInputStream(matchResponse.body.byteStream()).bufferedReader().use { it.readText() }
        } else {
            matchResponse.text
        }
        val matches: List<APIMatch> = mapper.readValue(matchesText)
        val match = matches.find { it.id == matchId } ?: return false
        val teamSlug = match.teams?.let { teams ->
            "${teams.home?.name?.lowercase()?.replace(" ", "-")}-vs-${teams.away?.name?.lowercase()?.replace(" ", "-")}"
        } ?: match.title.lowercase().replace(" ", "-")

        val streamUrl = "$mainUrl/api/stream/$sourceType/$sourceId"
        val streamHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            "Referer" to "https://streamed.su/"
        )
        val response = app.get(streamUrl, headers = streamHeaders, timeout = 30)
        val text = if (response.headers["Content-Encoding"] == "gzip") {
            GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
        } else {
            response.text
        }
        val streams: List<Stream> = try {
            mapper.readValue(text)
        } catch (e: Exception) {
            println("Failed to parse streams in loadLinks: ${e.message}")
            return false
        }
        if (streams.isEmpty()) {
            println("No streams available in loadLinks for $streamUrl")
            return false
        }

        streams.forEach { stream ->
            println("Processing stream: id=${stream.id}, streamNo=${stream.streamNo}, hd=${stream.hd}, source=${stream.source}")
            val encrypted = fetchEncryptedData(sourceType, matchId, stream.streamNo.toString())
            val decrypted = decryptWithWebView(encrypted ?: return@forEach, sourceType, teamSlug, stream.streamNo.toString()) ?: return false
            val m3u8Url = "https://rr.vipstreams.in$decrypted"

            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "${stream.source} - ${if (stream.hd) "HD" else "SD"} (${stream.language})",
                    url = m3u8Url,
                    referer = "https://embedme.top/",
                    quality = if (stream.hd) 720 else -1,
                    isM3u8 = true,
                    headers = embedHeaders
                )
            )
        }
        return true
    }

    private fun fetchEncryptedData(source: String, id: String, streamNo: String): String? {
        val client = OkHttpClient()
        val json = """{"source": "$source", "id": "$id", "streamNo": "$streamNo"}"""
        val body = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://embedme.top/fetch")
            .post(body)
            .headers(okhttp3.Headers.Builder().apply { embedHeaders.forEach { add(it.key, it.value) } }.build())
            .build()

        return client.newCall(request).execute().use { response ->
            println("Fetch response from embedme.top: status=${response.code}, body=${response.body?.string()}")
            if (response.isSuccessful) response.body?.string() else null
        }
    }

    private suspend fun decryptWithWebView(encrypted: String, sourceType: String, teamSlug: String, streamNo: String): String = suspendCancellableCoroutine<String> { cont ->
        val webView = WebView(context)
        webView.settings.javaScriptEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val js = """
                    window.decrypt('$encrypted').then(result => {
                        window.androidCallback(result);
                    });
                """
                webView.evaluateJavascript(js) { result ->
                    if (result != null && result != "null") {
                        println("Decrypted result: $result")
                        cont.resume(result.trim('"'))
                    } else {
                        println("Decryption failed: $result")
                        cont.resumeWithException(Exception("Decryption failed"))
                    }
                }
            }
        }
        val embedUrl = "https://embedme.top/embed/$sourceType/$teamSlug/$streamNo"
        println("Loading WebView with URL: $embedUrl")
        webView.loadUrl(embedUrl)
    }

    private fun String.capitalize(): String {
        return replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}