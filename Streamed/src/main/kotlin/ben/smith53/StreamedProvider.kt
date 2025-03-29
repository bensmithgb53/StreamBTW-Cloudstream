package ben.smith53

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.util.zip.GZIPInputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

const val TAG = "StreamedExtractor"

class StreamedExtractor : ExtractorApi() {
    override val mainUrl = "https://streamed.su"
    override val name = "Streamed Sports Extractor"
    override val requiresReferer = true

    private val embedBaseUrl = "https://embedme.top"
    private val streamBaseUrl = "https://rr.vipstreams.in"

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
        "Referer" to "$embedBaseUrl/",
        "Accept" to "*/*",
        "Accept-Encoding" to "gzip, deflate, br",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    data class Source(
        val id: String,
        val streamNumber: Int,
        val language: String,
        val isHD: Boolean,
        val source: String
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("$TAG: Extracting from URL: $url, Referer: $referer")
        if (url.isNotEmpty()) {
            // Fetch stream metadata
            val response = app.get(url, headers = defaultHeaders, timeout = 30)
            val rawSource = if (response.headers["Content-Encoding"] == "gzip") {
                GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
            } else {
                response.text
            }
            println("$TAG: Raw response: $rawSource")

            val sources = try {
                parseJson<List<Source>>(rawSource)
            } catch (e: Exception) {
                println("$TAG: Failed to parse sources: ${e.message}")
                emptyList()
            }

            if (sources.isNotEmpty()) {
                sources.forEach { source ->
                    val m3u8Url = getM3u8Url(source.source, source.id, source.streamNumber)
                    if (m3u8Url != null) {
                        val isHdString = if (source.isHD) "HD" else "SD"
                        val sourceName = "${source.streamNumber}. ${source.source.capitalize()} $isHdString ${source.language}"

                        println("$TAG: Generated link: $sourceName -> $m3u8Url")
                        callback(
                            ExtractorLink(
                                source = name,
                                name = sourceName,
                                url = m3u8Url,
                                referer = "$embedBaseUrl/",
                                quality = if (source.isHD) Qualities.P1080.value else Qualities.P720.value,
                                isM3u8 = true,
                                headers = defaultHeaders
                            )
                        )
                    } else {
                        println("$TAG: No M3U8 URL found for ${source.source}/${source.id}/${source.streamNumber}")
                    }
                }
            } else {
                println("$TAG: No sources found in response")
            }
        } else {
            println("$TAG: URL is empty")
        }
    }

    private suspend fun getM3u8Url(sourceType: String, matchId: String, streamNo: Int): String? {
        // Try scraping embed page first
        val embedUrl = "$embedBaseUrl/embed/$sourceType/$matchId/$streamNo"
        println("$TAG: Scraping embed page: $embedUrl")
        val embedResponse = app.get(embedUrl, headers = defaultHeaders, timeout = 30)
        val embedText = if (embedResponse.headers["Content-Encoding"] == "gzip") {
            GZIPInputStream(embedResponse.body.byteStream()).bufferedReader().use { it.readText() }
        } else {
            embedResponse.text
        }
        println("$TAG: Embed response: Status=${embedResponse.code}, Text=$embedText")

        val m3u8Regex = Regex("https://rr\\.vipstreams\\.in/[^\"'\\s]+\\.m3u8(?:\\?[^\"'\\s]*)?")
        val m3u8Match = m3u8Regex.find(embedText)
        if (m3u8Match != null) {
            println("$TAG: Found M3U8 in embed page: ${m3u8Match.value}")
            val testResponse = app.get(m3u8Match.value, headers = defaultHeaders, timeout = 10)
            if (testResponse.isSuccessful) {
                println("$TAG: M3U8 test: Status=${testResponse.code}, Body=${testResponse.text.take(200)}")
                return m3u8Match.value
            } else {
                println("$TAG: M3U8 test failed: Status=${testResponse.code}, Body=${testResponse.text}")
            }
        }

        // Fallback to fetch
        println("$TAG: No M3U8 in embed page, falling back to fetch")
        val fetchHeaders = defaultHeaders + mapOf(
            "Content-Type" to "application/json",
            "Origin" to embedBaseUrl
        )
        val fetchBody = """{"source":"$sourceType","id":"$matchId","streamNo":"$streamNo"}""".toRequestBody("application/json".toMediaType())

        println("$TAG: Fetching M3U8: POST $embedBaseUrl/fetch, Headers=$fetchHeaders, Body=$fetchBody")
        val fetchResponse = app.post("$embedBaseUrl/fetch", headers = fetchHeaders, requestBody = fetchBody, timeout = 30)
        val fetchText = fetchResponse.text
        println("$TAG: Fetch response: Status=${fetchResponse.code}, Text=$fetchText")

        return if (fetchResponse.isSuccessful && fetchText.isNotEmpty()) {
            if (fetchText.contains(".m3u8")) {
                println("$TAG: Direct M3U8 from fetch: $fetchText")
                fetchText
            } else {
                println("$TAG: Encrypted response from fetch: $fetchText")
                val encryptedBytes = try {
                    Base64.getDecoder().decode(fetchText)
                } catch (e: Exception) {
                    println("$TAG: Base64 decode failed: ${e.message}")
                    null
                }
                val decryptedPath = encryptedBytes?.let {
                    try {
                        decrypt(it)
                    } catch (e: Exception) {
                        println("$TAG: Decryption failed: ${e.message}")
                        null
                    }
                }
                val m3u8Url = decryptedPath?.let { "$streamBaseUrl$it" }
                if (m3u8Url != null) {
                    println("$TAG: Decrypted M3U8 URL: $m3u8Url")
                    val testResponse = app.get(m3u8Url, headers = defaultHeaders, timeout = 10)
                    if (testResponse.isSuccessful) {
                        println("$TAG: M3U8 test: Status=${testResponse.code}, Body=${testResponse.text.take(200)}")
                        m3u8Url
                    } else {
                        println("$TAG: M3U8 blocked: Status=${testResponse.code}, Body=${testResponse.text}")
                        null
                    }
                } else {
                    println("$TAG: No valid M3U8 from fetch")
                    null
                }
            }
        } else {
            println("$TAG: Fetch failed: Status=${fetchResponse.code}")
            null
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
            println("$TAG: AES decryption failed: ${e.message}")
            throw e
        }
    }

    private fun String.capitalize(): String {
        return replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}