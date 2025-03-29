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
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

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

    private val streamHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Referer" to "https://embedme.top/"
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
        val text = if (response.headers["Content-Encoding"]?.equals("gzip") == true) {
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
        val matchesText = if (matchResponse.headers["Content-Encoding"]?.equals("gzip") == true) {
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
        val streamApiHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            "Referer" to "https://streamed.su/"
        )
        val response = app.get(streamUrl, headers = streamApiHeaders, timeout = 30)
        val text = if (response.headers["Content-Encoding"]?.equals("gzip") == true) {
            GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
        } else {
            response.text
        }
        println("Stream API response for $streamUrl: $text")

        if (!response.isSuccessful || text.contains("Not Found")) {
            println("Stream not found for $streamUrl, status=${response.code}")
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
            ?: throw ErrorLoadingException("Failed to fetch encrypted stream data")
        val decrypted = decryptWithWebView(encrypted, sourceType, teamSlug, firstStream.streamNo.toString())
        val defaultM3u8Url = decrypted?.let { "https://rr.vipstreams.in$it" } ?: "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        println("Default M3U8 URL: $defaultM3u8Url")

        return newLiveStreamLoadResponse(
            "${firstStream.source} - ${if (firstStream.hd) "HD" else "SD"}",
            correctedUrl,
            defaultM3u8Url
        ) {
            this.apiName = this@StreamedProvider.name
            if (decrypted == null) {
                this.plot = "Failed to decrypt stream URL. Using a test stream instead."
            }
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
        val matchesText = if (matchResponse.headers["Content-Encoding"]?.equals("gzip") == true) {
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
        val streamApiHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            "Referer" to "https://streamed.su/"
        )
        val response = app.get(streamUrl, headers = streamApiHeaders, timeout = 30)
        val text = if (response.headers["Content-Encoding"]?.equals("gzip") == true) {
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
                    headers = streamHeaders
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
            val responseBody = if (response.isSuccessful) response.body?.string() else null
            println("Fetch response from embedme.top: status=${response.code}, body=$responseBody")
            responseBody
        }
    }

    private suspend fun decryptWithWebView(encrypted: String, sourceType: String, teamSlug: String, streamNo: String): String? {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                println("Initializing WebView for decryption")
                val webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                }
                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        println("WebView console: ${consoleMessage?.message()} (line ${consoleMessage?.lineNumber()})")
                        return true
                    }
                }
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        println("WebView started loading: $url")
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        println("WebView finished loading: $url")
                        val js = """
                            if (typeof window.decrypt === 'function') {
                                window.decrypt('$encrypted').then(result => {
                                    console.log('Decryption result: ' + result);
                                    window.androidCallback(result);
                                }).catch(err => {
                                    console.error('Decryption error: ' + err);
                                    window.androidCallback(null);
                                });
                            } else {
                                console.error('window.decrypt is not a function');
                                window.androidCallback(null);
                            }
                        """
                        println("Executing JavaScript: $js")
                        webView.evaluateJavascript(js) { value ->
                            println("JavaScript callback received: $value")
                            if (value != null && value != "null") {
                                println("Decrypted result: $value")
                                continuation.resume(value.trim('"'))
                            } else {
                                println("Decryption failed: $value")
                                continuation.resume(null)
                            }
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        println("WebView error: ${error?.description} (code: ${error?.errorCode})")
                        continuation.resume(null)
                    }
                }
                val embedUrl = "https://embedme.top/embed/$sourceType/$teamSlug/$streamNo"
                println("Loading WebView with URL: $embedUrl")
                webView.loadUrl(embedUrl)

                continuation.invokeOnCancellation {
                    println("WebView cancelled, destroying")
                    webView.destroy()
                }
            }
        }
    }

    private fun String.capitalize(): String {
        return replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}