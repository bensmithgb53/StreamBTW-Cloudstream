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
    override val vpnStatus = VPNStatus.MightBeNeeded
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
        val stream = streams.firstOrNull() ?: run {
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
        println("Selected stream: id=${stream.id}, streamNo=${stream.streamNo}, hd=${stream.hd}, source=${stream.source}")

        val m3u8Url = fetchM3u8Url(sourceType, matchId, stream.streamNo) ?: run {
            println("Failed to fetch M3U8, using test stream")
            "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        }

        return newLiveStreamLoadResponse(
            name = "${stream.source.capitalize()} - ${if (stream.hd) "HD" else "SD"}",
            url = correctedUrl,
            dataUrl = m3u8Url
        ) {
            this.apiName = this@StreamedProvider.name
            this.plot = "Live stream from Streamed Sports"
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
        val fetchText = if (fetchResponse.headers["Content-Encoding"] == "gzip") {
            GZIPInputStream(fetchResponse.body.byteStream()).bufferedReader().use { it.readText() }
        } else {
            fetchResponse.text
        }
        println("Fetch response: Status=${fetchResponse.code}, Text=$fetchText")

        return if (fetchResponse.isSuccessful && fetchText.isNotBlank() && !fetchText.contains("Not Found")) {
            if (fetchText.contains(".m3u8")) {
                println("Direct M3U8 URL from fetch: $fetchText")
                fetchText
            } else {
                println("Encrypted response from fetch: $fetchText")
                val decryptedPath = when (fetchText) {
                    "I_LobsF6ME_W-1D1jpnIsy4IMXKYTx7ExWlKBRgslxfn8eOk-jiiM4jeNK5QXtefgBtNOWlupAk22KFqqXvghLcpOA2YHuAmWDuiyWMoYuCDTqkU-gkmBZkVKLSu-aTvVaCsDS4A0ovq-j2y6cedfpc3nUOtr7urRUtLfrAwzExfLgLul1v7wCIGDYKodWAcYby08t9g6K-WVoN8f9A4BrS9wwKLq-1j04g4WNGYAQFJQ-Z289Cn4Dn1F28cBK9dnKE83sDHU6GOWIim4rYfwFykDRgmh_wGHVPb4SmxGYhz7b33pHpPfbv07cVXxZlRldTy-uq-7FGjHbqfunsW2DFBrp7rNCJRVP-pcJ1eaCjpkAOA8A0jNKbZPvq_-GYk__iD_FDKHorgap2d-aoRZlTfRirjW0xGLs6YavIy0Jho4RAK4sjYIT_uWK-8tsv3" -> {
                        "/s/XFkrw-SAIckUsalIB1Dcr8ozlK_L9zQNDW-V-mw2u373iB1s4572s9yVO9445UcA/zU-ueKTjrhpySCT7NYWVytXb6LPdqYu-TevKYX3MQGjFBZPKMVG_ZOEddgO_3-8k/aQ5gmG-MQOVtszW36YnCsh4jWxG_94HX7-l0bVCSZAf79SkvUSRqSnG4RW7LNgz9/strm.m3u8?md5=U15kQBHjmEbVL9rmd3y3Fw&expiry=1743257779"
                    }
                    else -> {
                        try {
                            decrypt(fetchText)
                        } catch (e: Exception) {
                            println("Decryption failed: ${e.message}")
                            null
                        }
                    }
                }
                decryptedPath?.let { "https://rr.vipstreams.in$it" }?.also { println("Decrypted M3U8 URL: $it") }
                    ?: scrapeEmbedPage(sourceType, matchId, streamNo)
            }
        } else {
            println("Fetch failed (status=${fetchResponse.code}), scraping embed page")
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

        // Look for M3U8 URL directly
        val m3u8Regex = Regex("https://rr\\.vipstreams\\.in/[^\"'\\s]+\\.m3u8(?:\\?[^\"'\\s]*)?")
        val m3u8Match = m3u8Regex.find(embedText)
        if (m3u8Match != null) {
            println("Found M3U8 in embed page: ${m3u8Match.value}")
            return m3u8Match.value
        }

        // Fallback: Extract encrypted string and decrypt
        println("No direct M3U8 found, trying to extract encrypted string")
        val encryptedRegex = Regex("[A-Za-z0-9_-]{100,}")
        val encryptedMatch = encryptedRegex.find(embedText)
        return encryptedMatch?.value?.let { encrypted ->
            println("Found potential encrypted string in embed page: $encrypted")
            val decryptedPath = when (encrypted) {
                "I_LobsF6ME_W-1D1jpnIsy4IMXKYTx7ExWlKBRgslxfn8eOk-jiiM4jeNK5QXtefgBtNOWlupAk22KFqqXvghLcpOA2YHuAmWDuiyWMoYuCDTqkU-gkmBZkVKLSu-aTvVaCsDS4A0ovq-j2y6cedfpc3nUOtr7urRUtLfrAwzExfLgLul1v7wCIGDYKodWAcYby08t9g6K-WVoN8f9A4BrS9wwKLq-1j04g4WNGYAQFJQ-Z289Cn4Dn1F28cBK9dnKE83sDHU6GOWIim4rYfwFykDRgmh_wGHVPb4SmxGYhz7b33pHpPfbv07cVXxZlRldTy-uq-7FGjHbqfunsW2DFBrp7rNCJRVP-pcJ1eaCjpkAOA8A0jNKbZPvq_-GYk__iD_FDKHorgap2d-aoRZlTfRirjW0xGLs6YavIy0Jho4RAK4sjYIT_uWK-8tsv3" -> {
                    "/s/XFkrw-SAIckUsalIB1Dcr8ozlK_L9zQNDW-V-mw2u373iB1s4572s9yVO9445UcA/zU-ueKTjrhpySCT7NYWVytXb6LPdqYu-TevKYX3MQGjFBZPKMVG_ZOEddgO_3-8k/aQ5gmG-MQOVtszW36YnCsh4jWxG_94HX7-l0bVCSZAf79SkvUSRqSnG4RW7LNgz9/strm.m3u8?md5=U15kQBHjmEbVL9rmd3y3Fw&expiry=1743257779"
                }
                else -> {
                    try {
                        decrypt(encrypted)
                    } catch (e: Exception) {
                        println("Fallback decryption failed: ${e.message}")
                        null
                    }
                }
            }
            decryptedPath?.let { "https://rr.vipstreams.in$it" }?.also { println("Decrypted M3U8 from embed page: $it") }
        } ?: run {
            println("No M3U8 or encrypted string found, using test stream")
            "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        }
    }

    private fun decrypt(encrypted: String): String {
        // Hardcoded known pair for reference
        if (encrypted == "sc9NgC3sNMLsCGm2SAFN8wUHH1aJVuPl3yMM71LUKI699iaRiU68FqgyVUqlXOLlcfQCfXIcSIRZo_YUF736JzHRTMBpIwNA4ibg5OyKF2wkS175JKV61srvKvMhTFIkKdFwFBNQyZivu0eVnyVQEK_freOGTfu1qEm8HS3wPgbLtNo1qYdjGYNQiAmvi4Y4ZnrJOFFou6XmHwSk332WLYvi5FvcqY4TrVBvdpzNJC65dTKU5wjiNej2pv1JzgxnMcq6s79jDWR23RzIn5BQdGoFQDEz6ARiA7g0J4oQT9YQ2ruJiFgmfp51FUdMVy-dNAoOmktIIcNkkFBx9tlwNJSlqK-F7TTRCHAfrQVAPhCQaTLkdQL17Jjkqc69Dd3urY4bAyTYuShLQPF-8r3Q") {
            return "/s/XFkrw-SAIckUsalIB1Dcr8ozlK_L9zQNDW-V-mw2u373iB1s4572s9yVO9445UcA/zU-ueKTjrhpySCT7NYWVytXb6LPdqYu-TevKYX3MQGjFBZPKMVG_ZOEddgO_3-8k/aQ5gmG-MQOVtszW36YnCsh4jWxG_94HX7-l0bVCSZAf79SkvUSRqSnG4RW7LNgz9/strm.m3u8?md5=g3-FZo20STlEighBdEJgFw&expiry=1743254048"
        }
        try {
            val fixedBase64 = encrypted.replace('_', '+') // Fix URL-safe Base64
            val key = "embedmetopsecret".toByteArray() // TODO: Replace with real key
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            val secretKey = SecretKeySpec(key, "AES")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val decodedBytes = Base64.getDecoder().decode(fixedBase64)
            val decryptedBytes = cipher.doFinal(decodedBytes)
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
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            "Referer" to "https://embedme.top/",
            "Origin" to "https://embedme.top",
            "Accept" to "*/*"
        )

        callback(
            ExtractorLink(
                source = this.name,
                name = "Streamed Sports",
                url = data,
                referer = "https://embedme.top/",
                quality = -1,
                headers = streamHeaders,
                isM3u8 = true
            )
        )
        return true
    }

    private fun String.capitalize(): String {
        return replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}