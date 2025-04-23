package ben.smith53 // Ensure this matches your package structure

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import android.util.Log
import com.lagradost.cloudstream3.network.POST // Make sure POST is imported if you use app.post directly
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.newHomePageResponse
import com.lagradost.cloudstream3.utils.newLiveSearchResponse
import com.lagradost.cloudstream3.utils.newLiveStreamLoadResponse
import java.net.URLEncoder
import java.util.Locale
import java.util.regex.Pattern // For cookie parsing (though less used now)

class StreamedProvider : MainAPI() {
    // Provider Metadata
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed (Proxy)" // Indicate proxy usage
    override var supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

    // Configuration
    private val sources = listOf("alpha", "bravo", "charlie", "delta") // Add more if needed
    private val maxStreams = 3 // Max streams per source to check

    // Main Page Categories
    override val mainPage = mainPageOf(
        "$mainUrl/api/matches/live/popular" to "Popular Live",
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

    // Fetch and parse main page listings
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Basic headers, adjust if needed for the API endpoint
        val headers = mapOf("Referer" to "$mainUrl/")
        val response = try {
             app.get(request.data, headers = headers)
        } catch (e: Exception) {
             Log.e("StreamedProvider", "Failed to fetch main page GET request: ${request.data} - Error: ${e.message}", e)
             return newHomePageResponse(request.name, emptyList())
        }

        if (response.code != 200) {
            Log.e("StreamedProvider", "Failed to fetch main page: ${request.data} - Status: ${response.code}")
            return newHomePageResponse(request.name, emptyList())
        }

        val listJson = try {
            parseJson<List<Match>>(response.text)
        } catch (e: Exception) {
            Log.e("StreamedProvider", "Failed to parse main page JSON: ${e.message}", e)
            emptyList()
        }

        val list = listJson.filter { match -> match.matchSources.isNotEmpty() }.mapNotNull { match ->
            // Ensure match ID exists
            val matchId = match.id ?: return@mapNotNull null
            val url = "$mainUrl/watch/$matchId" // Internal URL used for loading details later
            newLiveSearchResponse(
                name = match.title,
                url = url,
                type = TvType.Live
            ) {
                // Construct poster URL safely
                this.posterUrl = match.posterPath?.let { if (it.startsWith("/")) "$mainUrl$it" else it }
                    ?: "$mainUrl/api/images/poster/fallback.webp"
            }
        }

        return newHomePageResponse(
            list = listOf(HomePageList(request.name, list, isHorizontalImages = true)),
            hasNext = false
        )
    }

    // Load details for a selected stream (mainly gets poster and confirms URL)
    override suspend fun load(url: String): LoadResponse {
        val matchId = url.substringAfterLast("/")
        // Basic title formatting
        val title = matchId.split("-").joinToString(" ") { word ->
             if (word.all { it.isDigit() }) word else word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }.trim()

        // Assuming poster structure based on previous observation
        val posterUrl = "$mainUrl/api/images/poster/$matchId.webp"

        return newLiveStreamLoadResponse(
            name = title,
            url = url, // Keep the internal URL
            apiName = this.name // Use the provider name
        ) {
            this.posterUrl = posterUrl
            // dataUrl = url // Pass the URL to loadLinks if needed, but it's already the 'data' parameter
        }
    }

    // Fetch the actual stream links (this is where the core logic happens)
    override suspend fun loadLinks(
        data: String, // This is the 'url' from the load function, e.g., "$mainUrl/watch/match-id"
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val matchId = data.substringAfterLast("/")
        Log.d("StreamedProvider", "Loading links for match ID: $matchId from URL: $data")

        val extractor = StreamedExtractor() // Instantiate the extractor logic class
        var success = false

        // Iterate through defined sources and stream numbers
        sources.forEach { source ->
            for (streamNo in 1..maxStreams) {
                val streamIdentifier = "$source/$matchId/$streamNo" // Unique identifier for logging
                Log.d("StreamedProvider", "Attempting to extract stream: $streamIdentifier")
                try {
                    // Call the extractor's getUrl function
                    // Pass the original watchUrl (data) needed for cookie referer
                    if (extractor.getUrl(data, matchId, source, streamNo, subtitleCallback, callback)) {
                        Log.i("StreamedProvider", "Successfully added link for stream: $streamIdentifier")
                        success = true
                        // Optional: Stop after finding the first working link per source or overall
                        // break // Uncomment to stop after first stream per source
                    } else {
                         Log.w("StreamedProvider", "Extractor failed for stream: $streamIdentifier")
                    }
                } catch (e: Exception) {
                     Log.e("StreamedProvider", "Error during extraction for $streamIdentifier: ${e.message}", e)
                }
            }
            // if (success) break // Uncomment to stop after the first source yields any link
        }

        if (!success) {
             Log.e("StreamedProvider", "No working stream links found for match ID: $matchId")
        }
        return success // Return true if at least one link was added
    }

    // Data class for parsing the JSON response from the API
    data class Match(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String,
        @JsonProperty("poster") val posterPath: String? = null,
        @JsonProperty("popular") val popular: Boolean = false, // Might be useful later
        @JsonProperty("sources") val matchSources: List<MatchSource> = emptyList() // Use List for safety
    )

    // Data class for nested source information within a Match
    data class MatchSource(
        @JsonProperty("source") val sourceName: String,
        @JsonProperty("id") val id: String // ID within the source system, might be useful
    )
}


// --- Extractor Logic ---
class StreamedExtractor {
    // URLs used by the extractor
    private val fetchUrl = "https://embedstreams.top/fetch"
    private val cookieUrl = "https://fishy.streamed.su/api/event"
    private val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
    private val proxyUrl = "http://127.0.0.1:8000/playlist.m3u8" // Local Python proxy address

    // Base headers used for network requests
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
        "Accept" to "*/*",
        "Accept-Encoding" to "identity",
        "Accept-Language" to "en-GB,en-US;q=0.9,en;q=0.8",
        "Sec-Ch-Ua" to "\"Not A(Brand\";v=\"8\", \"Chromium\";v=\"132\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "X-Requested-With" to "XMLHttpRequest"
        // Referer and Origin are context-dependent
    )

    // --- MODIFIED: Function to fetch and format cookies in a SPECIFIC order ---
    private suspend fun fetchAndFormatCookies(referer: String): String? {
        Log.d("StreamedExtractor", "Attempting to fetch cookies from $cookieUrl with referer $referer")
        // Define the EXACT required cookie names in the desired order
        val requiredCookieOrder = listOf("ddg8_", "ddg10_", "ddg9_", "ddg1_")
        Log.d("StreamedExtractor", "Required cookie order: $requiredCookieOrder")

        try {
            // Headers specific to the cookie request
            val cookieHeaders = baseHeaders + mapOf(
                "Referer" to referer, // Use the main site page (watchUrl) as referer
                "Origin" to "https://streamed.su", // Origin for fishy.streamed.su
                "Content-Type" to "application/json",
                "Sec-Fetch-Site" to "same-site" // fishy is same-site to streamed.su
            )
            val payload = mapOf("event" to "pageview") // Payload required by the endpoint

            // Perform the POST request
            val response = app.post(
                cookieUrl,
                headers = cookieHeaders,
                json = payload,
                allowRedirects = true,
                timeout = 20 // 20 seconds timeout
            )

            Log.d("StreamedExtractor", "Cookie fetch response code: ${response.code}")
            if (response.code != 200 && response.code != 204) { // Check for successful status
                 Log.e("StreamedExtractor", "Cookie fetch failed with status code: ${response.code}")
                 return null
            }

            // --- Cookie Parsing and Ordering Logic ---
            // 1. Extract all Set-Cookie headers
            val setCookieHeaders = response.headers.filter { it.first.equals("Set-Cookie", ignoreCase = true) }
                                     .map { it.second } // Get the full header value strings

            if (setCookieHeaders.isEmpty()) {
                Log.e("StreamedExtractor", "No Set-Cookie headers received from $cookieUrl")
                return null
            }
            Log.d("StreamedExtractor", "Received Set-Cookie headers: $setCookieHeaders")

            // 2. Parse into a Map (name -> value), handling potential duplicates (last one wins)
            val parsedCookiesMap = mutableMapOf<String, String>()
            setCookieHeaders.forEach { headerValue ->
                // Get the first part (name=value) before any attributes like Path, Expires, etc.
                val cookiePart = headerValue.split(";", limit = 2)[0]
                val parts = cookiePart.split("=", limit = 2)
                if (parts.size == 2) {
                    val name = parts[0].trim()
                    val value = parts[1].trim()
                    if (name.isNotEmpty()) { // Ensure name is not empty
                       parsedCookiesMap[name] = value // Add or overwrite in the map
                       // Log.d("StreamedExtractor", "Parsed cookie: $name = $value") // Verbose
                    }
                }
            }

            if (parsedCookiesMap.isEmpty()){
                Log.e("StreamedExtractor", "Failed to parse any name=value pairs from Set-Cookie headers.")
                return null
            }
            Log.d("StreamedExtractor", "Parsed cookie map: $parsedCookiesMap")

            // 3. Build the final cookie string IN THE REQUIRED ORDER
            val orderedCookieParts = mutableListOf<String>()
            var allRequiredFound = true
            for (cookieName in requiredCookieOrder) {
                // Handle potential leading underscore if server sends "_ddgX_" but required is "ddgX_"
                val cookieValue = parsedCookiesMap[cookieName] ?: parsedCookiesMap[cookieName.removePrefix("_")] ?: parsedCookiesMap["_${cookieName}"]

                if (cookieValue != null) {
                    orderedCookieParts.add("$cookieName=$cookieValue") // Add with the required name
                } else {
                    // A required cookie was NOT found in the server response!
                    Log.w("StreamedExtractor", "Required cookie '$cookieName' was NOT found in the response headers.")
                    allRequiredFound = false
                    // Depending on strictness, you might want to fail here:
                    // break // Uncomment this to fail immediately if one is missing
                }
            }

            // Check if all required cookies were actually found
            if (!allRequiredFound) {
                 Log.e("StreamedExtractor", "FAILED: Not all required cookies ($requiredCookieOrder) were found in the server response. Cannot proceed.")
                 return null // Fail because the exact required set wasn't available
            }

            // 4. Join the parts in the specified order
            val finalCookieString = orderedCookieParts.joinToString("; ")

            Log.i("StreamedExtractor", "Successfully formatted cookies in required order: $finalCookieString")
            return finalCookieString

        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Error during cookie fetching or processing: ${e.message}", e)
            return null
        }
    }


    // Main function to get the stream URL for a specific source/stream number
    suspend fun getUrl(
        watchUrl: String, // e.g., https://streamed.su/watch/match-id (used as referer for cookie fetch)
        matchId: String,
        source: String,
        streamNo: Int,
        subtitleCallback: (SubtitleFile) -> Unit, // Keep for future use
        callback: (ExtractorLink) -> Unit // Callback to add the found link
    ): Boolean {
        Log.d("StreamedExtractor", "Processing stream: Source=$source, MatchID=$matchId, StreamNo=$streamNo")

        // 1. Fetch Cookies (using the main watchUrl as the referer context)
        val cookies = fetchAndFormatCookies(referer = watchUrl) ?: run {
            Log.e("StreamedExtractor", "Failed to retrieve cookies in required order for $source/$streamNo.")
            return false
        }

        // 2. Fetch Encrypted Path
        val postData = mapOf(
            "source" to source,
            "id" to matchId,
            "streamNo" to streamNo.toString()
        )
        val embedReferer = "https://embedstreams.top/embed/$source/$matchId/$streamNo"
        // Headers for the fetch request to embedstreams.top
        val fetchHeaders = baseHeaders + mapOf(
            "Referer" to embedReferer, // Referer is the embed page itself
            "Origin" to "https://embedstreams.top", // Origin matching the referer
            "Cookie" to cookies, // Use the specifically ordered cookies
            "Content-Type" to "application/json",
            "Sec-Fetch-Site" to "same-origin" // Fetch is same-origin to embed referer
        )
        Log.d("StreamedExtractor", "Fetching encrypted path from $fetchUrl")
        // Logd("StreamedExtractor", "Fetch Headers: $fetchHeaders") // Uncomment carefully (contains cookies)
        // Logd("StreamedExtractor", "Fetch Data: $postData")

        val encryptedResponse = try {
            val response = app.post(fetchUrl, headers = fetchHeaders, json = postData, timeout = 25)
            Log.d("StreamedExtractor", "Fetch response code: ${response.code}")
            if (response.code != 200) {
                 Log.e("StreamedExtractor", "Fetch failed: Status ${response.code}, Body: ${response.text}")
                 return false
            }
            response.text
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Fetch request failed: ${e.message}", e)
            return false
        }

        if (encryptedResponse.isBlank() || encryptedResponse.length < 10) { // Basic sanity check
             Log.e("StreamedExtractor", "Received empty or invalid encrypted response: '$encryptedResponse'")
             return false
        }
        Log.d("StreamedExtractor", "Received encrypted response (length ${encryptedResponse.length})")

        // 3. Decrypt Path
        val decryptPostData = mapOf("encrypted" to encryptedResponse)
        Log.d("StreamedExtractor", "Sending to decryption service: $decryptUrl")
        val decryptResponse = try {
            app.post(decryptUrl, json = decryptPostData, headers = mapOf("Content-Type" to "application/json"), timeout = 20)
                .parsedSafe<Map<String, String>>() // Expecting {"decrypted": "..."}
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Decryption request failed: ${e.message}", e)
            return false
        }
        val decryptedPath = decryptResponse?.get("decrypted") ?: return false.also {
            Log.e("StreamedExtractor", "Decryption failed or no 'decrypted' key in response: $decryptResponse")
        }
        if (decryptedPath.isBlank() || !decryptedPath.startsWith("/")) {
             Log.e("StreamedExtractor", "Invalid decrypted path received: '$decryptedPath'")
             return false
        }
        Log.d("StreamedExtractor", "Decrypted path: $decryptedPath")

        // 4. Construct M3U8 URL and Proxy URL
        val m3u8Url = "https://rr.buytommy.top$decryptedPath" // Assuming this domain is correct
        val encodedM3u8Url = URLEncoder.encode(m3u8Url, "UTF-8")
        val encodedCookies = URLEncoder.encode(cookies, "UTF-8") // Encode the ordered cookies

        // Final URL pointing to the local Python proxy
        val proxiedUrl = "$proxyUrl?url=$encodedM3u8Url&cookies=$encodedCookies"
        Log.i("StreamedExtractor", "Constructed Proxy URL: $proxiedUrl") // Log the final URL

        // 5. Invoke Callback with the Proxy Link
        try {
            callback.invoke(
                newExtractorLink(
                    source = "Streamed", // Use provider name or a custom source name
                    name = "$source Stream $streamNo", // Descriptive name for the link
                    url = proxiedUrl, // The URL pointing to the Python proxy
                    referer = embedReferer, // Important: Player should use this when requesting proxiedUrl
                    type = ExtractorLinkType.M3U8 // Type hint for the player
                ) {
                    this.quality = Qualities.Unknown.value // Quality is usually unknown for live streams initially
                    // No headers needed here for the proxy itself, auth is in the URL.
                    // Headers might be needed if the *player* requires specific headers to talk to the proxy,
                    // but usually User-Agent and Referer passed via newExtractorLink are sufficient.
                }
            )
            Log.i("StreamedExtractor", "Successfully added ExtractorLink for $source/$streamNo")
            return true // Indicate success
        } catch (e: Exception) {
            Log.e("StreamedExtractor", "Failed to invoke callback for proxied URL: ${e.message}", e)
            return false // Indicate failure
        }
    }
}

// Helper function for creating main page sections easily
fun mainPageOf(vararg pairs: Pair<String, String>) =
    pairs.map { MainPageRequest(it.second, it.first) }