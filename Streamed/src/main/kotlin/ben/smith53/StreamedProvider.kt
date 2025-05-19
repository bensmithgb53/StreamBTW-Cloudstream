package ben.smith53

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import android.util.Log
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.Locale
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.regex.Pattern

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed"
    override var supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

    private val sources = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")
    private val maxStreams = 4

    override val mainPage = mainPageOf(
        "$mainUrl/api/matches/live/popular" to "Popular",
        "$mainUrl/api/matches/football" to "Football",
        "$mainUrl/api/matches/baseball" to "Baseball",
        "$mainUrl/api/matches/american-football" to "American Football",
        "$mainUrl/api/matches/hockey" to "Hockey",
        "$mainUrl/api/matches/basketball" to "Basketball",
        "$mainUrl/api/matches/motor-sports" to "Motor Sports",
        "$mainUrl/api/matches/fight" to "Fight",
        "$mainUrl/api/matches/tennis" to "Tennis",
        "$mainUrl/api/matches/rugby" to "Rugby",
        "$mainUrl/api/matches/golf" to "Golf",
        "$mainUrl/api/matches/billiards" to "Billiards",
        "$mainUrl/api/matches/afl" to "AFL",
        "$mainUrl/api/matches/darts" to "Darts",
        "$mainUrl/api/matches/cricket" to "Cricket",
        "$mainUrl/api/matches/other" to "Other"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val rawList = app.get(request.data).text
        val listJson = parseJson<List<Match>>(rawList)

        val list = listJson.filter { match -> match.matchSources.isNotEmpty() }.map { match ->
            val url = "$mainUrl/watch/${match.id}"
            newLiveSearchResponse(
                name = match.title,
                url = url,
                type = TvType.Live
            ) {
                this.posterUrl = "$mainUrl${match.posterPath ?: "/api/images/poster/fallback.webp"}"
            }
        }.filterNotNull()

        return newHomePageResponse(
            list = listOf(HomePageList(request.name, list, isHorizontalImages = true)),
            hasNext = false
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val matchId = url.substringAfterLast("/")
        val title = matchId.replace("-", " ")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            .replace(Regex("-\\d+$"), "")
        val posterUrl = "$mainUrl/api/images/poster/$matchId.webp"
        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val matchId = data.substringAfterLast("/")
        val extractor = StreamedMediaExtractor()
        var success = false

        sources.forEach { source ->
            for (streamNo in 1..maxStreams) {
                val streamUrl = "$mainUrl/watch/$matchId/$source/$streamNo"
                Log.d("StreamedProvider", "Processing stream URL: $streamUrl")
                if (extractor.getUrl(streamUrl, matchId, source, streamNo, subtitleCallback, callback)) {
                    success = true
                }
            }
        }
        return success
    }

    data class Match(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String,
        @JsonProperty("poster") val posterPath: String? = null,
        @JsonProperty("popular") val popular: Boolean = false,
        @JsonProperty("sources") val matchSources: ArrayList<MatchSource> = arrayListOf()
    )

    data class MatchSource(
        @JsonProperty("source") val sourceName: String,
        @JsonProperty("id") val id: String
    )
}

class StreamedMediaExtractor {
    private val fetchUrl = "https://embedstreams.top/fetch"
    private val cookieUrl = "https://fishy.streamed.su/api/event"
    private val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        "Content-Type" to "application/json"
    )
    private val fallbackDomains = listOf("p2-panel.streamed.su", "streamed.su")
    private val cookieCache = mutableMapOf<String, String>()
    private val ivBytes = byteArrayOf(
        0x18, 0x31, 0xF9.toByte(), 0x89.toByte(), 0x74, 0x21, 0x91.toByte(), 0xF8.toByte(),
        0xD3.toByte(), 0xB4.toByte(), 0x50.toByte(), 0xC2.toByte(), 0x75.toByte(), 0xB1.toByte(), 0xB3.toByte(), 0x5A
    )
    private val pollInterval = 5000L // 5 seconds
    private val maxSegments = 100 // Keep last 100 segments

    suspend fun getUrl(
        streamUrl: String,
        matchId: String,
        source: String,
        streamNo: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StreamedMediaExtractor", "Starting extraction for: $streamUrl")

        // Fetch stream page cookies
        val streamResponse = try {
            app.get(streamUrl, headers = baseHeaders, timeout = 15)
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Stream page fetch failed: ${e.message}")
            return false
        }
        val streamCookies = streamResponse.cookies
        Log.d("StreamedMediaExtractor", "Stream cookies: $streamCookies")

        // Fetch event cookies
        val eventCookies = fetchEventCookies(streamUrl, streamUrl)
        Log.d("StreamedMediaExtractor", "Event cookies: $eventCookies")

        // Combine cookies
        val combinedCookies = buildString {
            if (streamCookies.isNotEmpty()) {
                append(streamCookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
            if (eventCookies.isNotEmpty()) {
                if (isNotEmpty()) append("; ")
                append(eventCookies)
            }
        }
        if (combinedCookies.isEmpty()) {
            Log.e("StreamedMediaExtractor", "No cookies obtained")
            return false
        }
        Log.d("StreamedMediaExtractor", "Combined cookies: $combinedCookies")

        // POST to fetch encrypted string
        val postData = mapOf(
            "source" to source,
            "id" to matchId,
            "streamNo" to streamNo.toString()
        )
        val embedReferer = "https://embedstreams.top/embed/$source/$matchId/$streamNo"
        val fetchHeaders = baseHeaders + mapOf(
            "Referer" to streamUrl,
            "Cookie" to combinedCookies
        )
        Log.d("StreamedMediaExtractor", "Fetching with data: $postData and headers: $fetchHeaders")

        val encryptedResponse = try {
            val response = app.post(fetchUrl, headers = fetchHeaders, json = postData, timeout = 15)
            Log.d("StreamedMediaExtractor", "Fetch response code: ${response.code}")
            response.text
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Fetch failed: ${e.message}")
            return false
        }
        Log.d("StreamedMediaExtractor", "Encrypted response: $encryptedResponse")

        // Decrypt using Deno
        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        val decryptResponse = try {
            app.post(decryptUrl, json = decryptPostData, headers = mapOf("Content-Type" to "application/json"))
                .parsedSafe<Map<String, String>>()
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Decryption request failed: ${e.message}")
            return false
        }
        val decryptedPath = decryptResponse?.get("decrypted") ?: return false.also {
            Log.e("StreamedMediaExtractor", "Decryption failed or no 'decrypted' key")
        }
        Log.d("StreamedMediaExtractor", "Decrypted path: $decryptedPath")

        // Construct M3U8 URL
        val m3u8Url = "https://rr.buytommy.top$decryptedPath"
        val m3u8Headers = baseHeaders + mapOf(
            "Referer" to embedReferer,
            "Cookie" to combinedCookies
        )

        // Setup local storage
        val tempDir = System.getProperty("java.io.tmpdir") ?: return false.also {
            Log.e("StreamedMediaExtractor", "Temporary directory not available")
        }
        val cacheDir = File(tempDir, "streamed_$matchId_$source_$streamNo") // Fixed: uses matchId, source
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val localM3u8File = File(cacheDir, "playlist.m3u8")
        val seenUrls = mutableSetOf<String>()
        var lastSequence = 31207 // From latest Python run

        // Start live streaming coroutine
        val job = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val success = fetchAndProcessM3u8(
                        m3u8Url, m3u8Headers, cacheDir, localM3u8File,
                        seenUrls, lastSequence
                    )
                    if (success) {
                        lastSequence += seenUrls.size // Update sequence
                    } else {
                        Log.w("StreamedMediaExtractor", "M3U8 fetch failed, retrying...")
                    }
                } catch (e: Exception) {
                    Log.e("StreamedMediaExtractor", "M3U8 processing error: ${e.message}")
                }
                delay(pollInterval)
            }
        }

        // Wait briefly to ensure initial segments are fetched
        delay(1000)

        // Return ExtractorLink with local M3U8
        if (localM3u8File.exists()) {
            callback.invoke(
                newExtractorLink(
                    source = "Streamed",
                    name = "$source Stream $streamNo",
                    url = localM3u8File.toURI().toString(),
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = embedReferer
                    this.quality = Qualities.Unknown.value
                    this.headers = m3u8Headers
                }
            )
            Log.d("StreamedMediaExtractor", "Local M3U8 added: ${localM3u8File.toURI()}")
            // Cleanup on scope cancellation
            CoroutineScope(Dispatchers.IO).launch {
                job.join()
                cacheDir.deleteRecursively()
                Log.d("StreamedMediaExtractor", "Cleaned up cache directory: $cacheDir")
            }
            return true
        } else {
            Log.e("StreamedMediaExtractor", "Local M3U8 not created")
            job.cancel()
            return false
        }
    }

    private suspend fun fetchAndProcessM3u8(
        m3u8Url: String,
        headers: Map<String, String>,
        cacheDir: File,
        localM3u8File: File,
        seenUrls: MutableSet<String>,
        lastSequence: Int
    ): Boolean = withContext(Dispatchers.IO) {
        // Fetch M3U8
        val m3u8Response = try {
            app.get(m3u8Url, headers = headers, timeout = 15)
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "M3U8 fetch failed: ${e.message}")
            return@withContext false
        }
        if (m3u8Response.code != 200) {
            Log.e("StreamedMediaExtractor", "M3U8 fetch failed with code: ${m3u8Response.code}")
            return@withContext false
        }
        val m3u8Content = m3u8Response.text
        Log.d("StreamedMediaExtractor", "M3U8 content:\n$m3u8Content")

        // Parse M3U8
        val lines = m3u8Content.lines()
        val segmentUrls = lines.filter { it.startsWith("https://") && (".ts?" in it || ".png?" in it) }
        val sequenceMatch = Pattern.compile("#EXT-X-MEDIA-SEQUENCE:(\\d+)").matcher(m3u8Content)
        val sequenceNumber = if (sequenceMatch.find()) sequenceMatch.group(1).toInt() else lastSequence

        // Extract key URL
        var keyUrl: String? = null
        for (line in lines) {
            if (line.startsWith("#EXT-X-KEY")) {
                val match = Pattern.compile("""URI="([^"]+)"""").matcher(line)
                if (match.find()) {
                    keyUrl = match.group(1)
                    if (keyUrl?.startsWith("/alpha/key") == true) {
                        keyUrl = "https://rr.buytommy.top$keyUrl"
                    }
                }
                break
            }
        }

        // Download key
        val keyBytes = keyUrl?.let {
            try {
                val response = app.get(it, headers = headers, timeout = 15)
                if (response.code == 200) response.body.bytes() else null
            } catch (e: Exception) {
                Log.e("StreamedMediaExtractor", "Key fetch failed: ${e.message}")
                null
            }
        } ?: return@withContext false.also {
            Log.e("StreamedMediaExtractor", "No key found or fetch failed")
        }

        // Filter new segments
        val newSegments = segmentUrls.mapIndexedNotNull { i, url ->
            val seq = sequenceNumber + i
            if (seq > lastSequence && url !in seenUrls) url to seq else null
        }
        if (newSegments.isEmpty()) {
            Log.d("StreamedMediaExtractor", "No new segments found")
            return@withContext true
        }

        // Download and decrypt segments
        val segmentFiles = mutableListOf<String>()
        newSegments.forEachIndexed { index, (url, seq) ->
            val segmentFile = File(cacheDir, "segment_${seq}_decrypted.ts")
            try {
                val segmentResponse = app.get(url, headers = headers, timeout = 15)
                if (segmentResponse.code == 200) {
                    val encryptedData = segmentResponse.body.bytes()
                    val decryptedData = decryptSegment(encryptedData, keyBytes, ivBytes)
                    if (decryptedData != null) {
                        segmentFile.writeBytes(decryptedData)
                        segmentFiles.add(segmentFile.absolutePath)
                        seenUrls.add(url)
                        Log.d("StreamedMediaExtractor", "Decrypted segment saved: ${segmentFile.absolutePath}")
                    } else {
                        Log.w("StreamedMediaExtractor", "Decryption failed for: $url")
                    }
                } else {
                    Log.w("StreamedMediaExtractor", "Segment fetch failed for $url: ${segmentResponse.code}")
                }
            } catch (e: Exception) {
                Log.e("StreamedMediaExtractor", "Segment processing failed for $url: ${e.message}")
            }
        }

        // Update local M3U8
        val m3u8Lines = mutableListOf(
            "#EXTM3U",
            "#EXT-X-VERSION:4",
            "#EXT-X-TARGETDURATION:4",
            "#EXT-X-MEDIA-SEQUENCE:${lastSequence + 1}"
        )
        newSegments.forEachIndexed { index, (url, _) ->
            val segmentFile = File(cacheDir, "segment_${lastSequence + 1 + index}_decrypted.ts")
            if (segmentFile.exists()) {
                val duration = lines.find { line ->
                    lines.indexOf(line) + 1 == lines.indexOf(url) && line.startsWith("#EXTINF:")
                }?.substringAfter("#EXTINF:")?.substringBefore(",") ?: "3.0"
                m3u8Lines.add("#EXTINF:$duration,Video")
                m3u8Lines.add(segmentFile.absolutePath)
            }
        }
        localM3u8File.writeText(m3u8Lines.joinToString("\n") + "\n")
        Log.d("StreamedMediaExtractor", "Updated local M3U8: ${localM3u8File.absolutePath}")

        // Cleanup old segments
        val allSegments = cacheDir.listFiles { _, name -> name.endsWith("_decrypted.ts") }?.sortedBy { it.name }
        if (allSegments != null && allSegments.size > maxSegments) {
            allSegments.take(allSegments.size - maxSegments).forEach { it.delete() }
            Log.d("StreamedMediaExtractor", "Cleaned up old segments")
        }

        return@withContext true
    }

    private fun decryptSegment(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decrypted = cipher.doFinal(data)
            decrypted // No manual padding removal needed with PKCS5Padding
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Decryption failed: ${e.message}")
            null
        }
    }

    private suspend fun fetchEventCookies(pageUrl: String, referrer: String): String {
        cookieCache[pageUrl]?.let { return it }

        val payload = """{"n":"pageview","u":"$pageUrl","d":"streamed.su","r":"$referrer"}"""
        try {
            val response = app.post(
                cookieUrl,
                data = mapOf(),
                headers = mapOf("Content-Type" to "text/plain"),
                requestBody = payload.toRequestBody("text/plain".toMediaType()),
                timeout = 15
            )
            val cookies = response.headers.filter { it.first == "Set-Cookie" }
                .map { it.second.split(";")[0] }
            val formattedCookies = listOf("_ddg8_", "_ddg10_", "_ddg9_", "_ddg1_")
                .mapNotNull { key -> cookies.find { it.startsWith(key) } }
                .joinToString("; ")
            if (formattedCookies.isNotEmpty()) {
                cookieCache[pageUrl] = formattedCookies
                return formattedCookies
            }
        } catch (e: Exception) {
            Log.e("StreamedMediaExtractor", "Failed to fetch event cookies: ${e.message}")
        }
        return ""
    }
}