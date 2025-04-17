private suspend fun fetchCookies(): String? {
    try {
        val response = app.post(cookieUrl, headers = baseHeaders, json = emptyMap(), timeout = 15)
        val cookies = response.headers.values("set-cookie") ?: return null
        val cookieDict = mutableMapOf(
            "ddg8_" to null,
            "ddg10_" to null,
            "ddg9_" to null,
            "ddg1_" to null
        )
        cookies.forEach { cookie ->
            cookie.split(";").forEach { part ->
                if ("=" in part) {
                    val split = part.split("=", limit = 2)
                    val name = split[0].trim()
                    val value = split.getOrNull(1)?.trim() ?: ""
                    val normalizedName = if (name.startsWith("__ddg")) name.substring(2) else name
                    if (normalizedName in cookieDict) {
                        cookieDict[normalizedName] = value
                    }
                }
            }
        }
        val cookieList = mutableListOf<String>()
        listOf("ddg8_", "ddg10_", "ddg9_", "ddg1_").forEach { key ->
            cookieDict[key]?.let { cookieList.add("$key=$it") }
        }
        val cookieString = cookieList.joinToString("; ")
        Log.d("StreamedExtractor", "Fetched cookies: $cookieString")
        return if (cookieString.isNotEmpty()) cookieString else null
    } catch (e: Exception) {
        Log.e("StreamedExtractor", "Failed to fetch cookies: ${e.message}")
        return null
    }
}

suspend fun getUrl(
    streamUrl: String,
    matchId: String,
    source: String,
    streamNo: Int,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    Log.d("StreamedExtractor", "Starting extraction for: $streamUrl")

    // Fetch cookies from fishy.streamed.su/api/event
    val cookies = fetchCookies() ?: run {
        Log.e("StreamedExtractor", "No cookies fetched")
        return false
    }

    // POST to fetch encrypted string
    val postData = mapOf(
        "source" to source,
        "id" to matchId,
        "streamNo" to streamNo.toString()
    )
    val embedReferer = "https://embedstreams.top/embed/$source/$matchId/$streamNo"
    val fetchHeaders = baseHeaders + mapOf(
        "Referer" to streamUrl,
        "Cookie" to cookies
    )
    Log.d("StreamedExtractor", "Fetching with data: $postData and headers: $fetchHeaders")

    val encryptedResponse = try {
        val response = app.post(fetchUrl, headers = fetchHeaders, json = postData, timeout = 15)
        Log.d("StreamedExtractor", "Fetch response code: ${response.code}")
        response.text
    } catch (e: Exception) {
        Log.e("StreamedExtractor", "Fetch failed: ${e.message}")
        return false
    }
    Log.d("StreamedExtractor", "Encrypted response: $encryptedResponse")

    // Decrypt using Deno
    val decryptUrl = "https://bensmithgb53-decrypt-13.deno.dev/decrypt"
    val decryptPostData = mapOf("encrypted" to encryptedResponse)
    val decryptResponse = try {
        app.post(decryptUrl, json = decryptPostData, headers = mapOf("Content-Type" to "application/json"))
            .parsedSafe<Map<String, String>>()
    } catch (e: Exception) {
        Log.e("StreamedExtractor", "Decryption request failed: ${e.message}")
        return false
    }
    val decryptedPath = decryptResponse?.get("decrypted") ?: return false.also {
        Log.e("StreamedExtractor", "Decryption failed or no 'decrypted' key")
    }
    Log.d("StreamedExtractor", "Decrypted path: $decryptedPath")

    // Construct M3U8 URL and proxy URL with cookies
    val m3u8Url = "https://rr.buytommy.top$decryptedPath"
    val encodedM3u8Url = URLEncoder.encode(m3u8Url, "UTF-8")
    val encodedCookies = URLEncoder.encode(cookies, "UTF-8")
    val proxiedUrl = "$proxyUrl?url=$encodedM3u8Url&cookies=$encodedCookies"
    try {
        callback.invoke(
            newExtractorLink(
                source = "Streamed",
                name = "$source Stream $streamNo",
                url = proxiedUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = embedReferer
                this.quality = Qualities.Unknown.value
                this.headers = baseHeaders
            }
        )
        Log.d("StreamedExtractor", "Proxied M3U8 URL added: $proxiedUrl")
        return true
    } catch (e: Exception) {
        Log.e("StreamedExtractor", "Failed to add proxied URL: ${e.message}")
        return false
    }
}