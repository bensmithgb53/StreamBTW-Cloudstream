package ben.smith53

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.util.zip.GZIPInputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed Sports"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"
    override val hasMainPage = true
    override val vpnStatus = VPNStatus.MightBeNeeded // Kept as "MightBeNeeded" for now
    override val hasDownloadSupport = false
    override val instantLinkLoading = true

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        "Referer" to "https://streamed.su/",
        "Accept-Encoding" to "gzip, deflate, br, zstd",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
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
                    listOf(newLiveSearchResponse("No live matches available", "$mainUrl|alpha|default")),
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
                newLiveSearchResponse(match.title, "${match.id}|${source.source}|${source.id}") {
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
        println("Stream API response for $streamUrl: $text")

        val streams: List<Stream> = try {
            if (!response.isSuccessful || text.contains("Not Found")) emptyList() else mapper.readValue(text)
        } catch (e: Exception) {
            println("Failed to parse streams: ${e.message}")
            emptyList()
        }
        if (streams.isEmpty()) {
            println("No streams available for $streamUrl, falling back to default")
            return newLiveStreamLoadResponse(
                name = "Stream Unavailable - $matchId",
                url = correctedUrl,
                dataUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
            ) {
                this.apiName = this@StreamedProvider.name
                this.plot = "The requested stream could not be found."
            }
        }

        val streamData = mapper.writeValueAsString(streams.map { stream ->
            val m3u8Url = fetchM3u8Url(sourceType, matchId, stream.streamNo) ?: "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
            mapOf(
                "streamNo" to stream.streamNo,
                "language" to stream.language,
                "hd" to stream.hd,
                "source" to stream.source,
                "m3u8Url" to m3u8Url
            )
        })
        println("Generated stream data: $streamData")

        return newLiveStreamLoadResponse(
            name = streams.first().source.capitalize(),
            url = correctedUrl,
            dataUrl = streamData
        ) {
            this.apiName = this@StreamedProvider.name
            this.plot = "Live stream from Streamed Sports with ${streams.size} options."
        }
    }

    private suspend fun fetchM3u8Url(sourceType: String, matchId: String, streamNo: Int): String? {
        val fetchHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            "Referer" to "https://embedme.top/",
            "Content-Type" to "application/json",
            "Origin" to "https://embedme.top",
            "Accept" to "*/*",
            "Accept-Encoding" to "gzip, deflate, br, zstd"
        )
        val fetchBody = """{"source":"$sourceType","id":"$matchId","streamNo":"$streamNo"}""".toRequestBody("application/json".toMediaType())

        println("Fetching M3U8: POST https://embedme.top/fetch, Headers=$fetchHeaders, Body=$fetchBody")
        val fetchResponse = app.post("https://embedme.top/fetch", headers = fetchHeaders, requestBody = fetchBody, timeout = 30)
        val fetchBytes = fetchResponse.body.bytes()
        val fetchText = Base64.getEncoder().encodeToString(fetchBytes)
        println("Fetch response: Status=${fetchResponse.code}, Headers=${fetchResponse.headers}, Text (Base64)=$fetchText")

        return if (fetchResponse.isSuccessful && fetchBytes.isNotEmpty()) {
            if (fetchText.contains(".m3u8")) {
                println("Direct M3U8 URL from fetch: $fetchText")
                fetchText
            } else {
                println("Encrypted response from fetch (Base64): $fetchText")
                val decryptedPath = try {
                    decrypt(fetchBytes)
                } catch (e: Exception) {
                    println("Decryption failed: ${e.message}, falling back to scraping")
                    null
                }
                val m3u8Url = decryptedPath?.let { "https://rr.vipstreams.in$it" }
                if (m3u8Url != null) {
                    println("Decrypted M3U8 URL: $m3u8Url")
                    val testResponse = app.get(m3u8Url, headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
                        "Referer" to "https://embedme.top/",
                        "Accept" to "*/*"
                    ), timeout = 10)
                    if (testResponse.isSuccessful) {
                        println("M3U8 URL test: Status=${testResponse.code}, Body=${testResponse.text.take(200)}")
                        m3u8Url
                    } else {
                        println("M3U8 URL blocked: Status=${testResponse.code}, Body=${testResponse.text}, falling back to scraping")
                        scrapeEmbedPage(sourceType, matchId, streamNo)
                    }
                } else {
                    scrapeEmbedPage(sourceType, matchId, streamNo)
                }
            }
        } else {
            println("Fetch failed (status=${fetchResponse.code}, body=${fetchResponse.text}), falling back to scraping")
            scrapeEmbedPage(sourceType, matchId, streamNo)
        }
    }

    private suspend fun scrapeEmbedPage(sourceType: String, matchId: String, streamNo: Int): String? {
        val embedUrl = "https://embedme.top/embed/$sourceType/$matchId/$streamNo"
        val embedHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            "Referer" to "https://embedme.top/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Encoding" to "gzip, deflate, br, zstd"
        )
        val embedResponse = app.get(embedUrl, headers = embedHeaders, timeout = 30)
        val embedText = if (embedResponse.headers["Content-Encoding"] == "gzip") {
            GZIPInputStream(embedResponse.body.byteStream()).bufferedReader().use { it.readText() }
        } else {
            embedResponse.text
        }
        println("Embed page response: Status=${embedResponse.code}, Text=$embedText")

        val m3u8Regex = Regex("https://rr\\.vipstreams\\.in/[^\"'\\s]+\\.m3u8(?:\\?[^\"'\\s]*)?")
        val m3u8Match = m3u8Regex.find(embedText)
        if (m3u8Match != null) {
            println("Found M3U8 in embed page: ${m3u8Match.value}")
            return m3u8Match.value
        }

        println("No direct M3U8 found, trying to extract encrypted string")
        val encryptedRegex = Regex("[A-Za-z0-9+/=]{100,}")
        val encryptedMatch = encryptedRegex.find(embedText)
        return encryptedMatch?.value?.let { encrypted ->
            println("Found potential encrypted string in embed page: $encrypted")
            try {
                val decryptedPath = decrypt(Base64.getDecoder().decode(encrypted))
                val m3u8Url = "https://rr.vipstreams.in$decryptedPath"
                println("Decrypted M3U8 from embed page: $m3u8Url")
                m3u8Url
            } catch (e: Exception) {
                println("Fallback decryption failed: ${e.message}")
                null
            }
        } ?: run {
            println("No M3U8 or encrypted string found, using test stream")
            "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        }
    }

    private fun decrypt(encryptedBytes: ByteArray): String {
        try {
            val key = "embedmetopsecret".toByteArray()
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            val secretKey = SecretKeySpec(key, "AES")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            return String(decryptedBytes)
        } catch (e: Exception) {
            println("AES decryption failed: ${e.message}")
            throw e
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streamHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
            "Referer" to "https://embedme.top/",
            "Accept" to "*/*"
        )

        println("Loading links from data: $data")
        val streams: List<Map<String, Any>> = try {
            mapper.readValue(data)
        } catch (e: Exception) {
            println("Failed to parse stream data: ${e.message}")
            return false
        }

        streams.forEach { stream ->
            val streamNo = stream["streamNo"] as? Int ?: 1
            val language = stream["language"] as? String ?: "Unknown"
            val hd = stream["hd"] as? Boolean ?: false
            val source = stream["source"] as? String ?: "Streamed"
            val m3u8Url = stream["m3u8Url"] as? String ?: return@forEach

            println("Processing stream: No=$streamNo, Lang=$language, HD=$hd, URL=$m3u8Url")
            val testResponse = app.get(m3u8Url, headers = streamHeaders, timeout = 10)
            println("Test fetch result: Status=${testResponse.code}, Headers=${testResponse.headers}, Body=${testResponse.text.take(200)}")
            if (!testResponse.isSuccessful) {
                println("Test fetch failed: ${testResponse.code} - ${testResponse.text}")
            } else {
                println("Test fetch succeeded: M3U8 content starts with ${testResponse.text.take(50)}")
            }

            callback(
                ExtractorLink(
                    source = this.name,
                    name = "$source - Stream $streamNo (${if (hd) "HD" else "SD"}, $language)",
                    url = m3u8Url,
                    referer = "https://embedme.top/",
                    quality = if (hd) 1080 else 720,
                    isM3u8 = true,
                    headers = streamHeaders
                )
            )
        }
        return true
    }
}

private fun String.capitalize(): String {
    return replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}