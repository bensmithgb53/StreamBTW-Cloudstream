package ben.smith53

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.Jsoup
import java.net.URL
import java.net.URLEncoder

class StrimsyExtractor : ExtractorApi() {
    override val mainUrl = "https://strimsy.top"
    override val name = "Strimsy"
    override val requiresReferer = true

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("User-Agent" to userAgent, "Referer" to mainUrl)
        println("Fetching main page: $mainUrl")
        val mainResponse = try {
            app.get(mainUrl, headers = headers).text
        } catch (e: Exception) {
            println("Failed to fetch main page: ${e.message}")
            return
        }
        val mainDoc = Jsoup.parse(mainResponse)
        val eventLinks = mainDoc.select("table.ramowka td a[href]")
            .map { link -> link.attr("href") }
            .filter { it.startsWith("/") }
            .map { "$mainUrl$it" }
            .distinct()
        println("Found ${eventLinks.size} event links: $eventLinks")

        eventLinks.forEach { eventUrl ->
            val streamLink = extractVideo(eventUrl)
            if (streamLink != null) {
                println("Successfully extracted stream: ${streamLink.url}")
                callback(streamLink)
            } else {
                println("No stream found for $eventUrl")
            }
        }
    }

    private suspend fun extractVideo(url: String, sourceName: String = this.name): ExtractorLink? {
        val headers = mapOf("User-Agent" to userAgent, "Referer" to mainUrl)
        println("Fetching event page: $url")
        val eventResponse = try {
            app.get(url, headers = headers).text
        } catch (e: Exception) {
            println("Failed to fetch event page $url: ${e.message}")
            return null
        }
        println("Event page snippet: ${eventResponse.take(1000)}...")

        val eventDoc = Jsoup.parse(eventResponse)
        val iframeUrl = eventDoc.select("iframe").first()?.attr("src") ?: run {
            println("No iframe found in $url")
            return null
        }
        val resolvedIframeUrl = when {
            iframeUrl.startsWith("/") -> "$mainUrl$iframeUrl"
            iframeUrl.startsWith("//") -> "https:$iframeUrl"
            else -> iframeUrl
        }
        println("Iframe URL: $resolvedIframeUrl")

        val parsedUrl = URL(resolvedIframeUrl)
        val refererBase = "${parsedUrl.protocol}://${parsedUrl.host}"
        val ref = URLEncoder.encode(refererBase, "UTF-8")
        val iframeHeaders = mapOf(
            "User-Agent" to userAgent,
            "Referer" to url,
            "Accept" to "*/*",
            "Origin" to mainUrl
        )

        println("Fetching iframe page: $resolvedIframeUrl")
        val iframeResponse = try {
            val response = app.get(resolvedIframeUrl, headers = iframeHeaders)
            println("Iframe response code: ${response.code}")
            response.text
        } catch (e: Exception) {
            println("Failed to fetch iframe $resolvedIframeUrl: ${e.message}")
            return null
        }
        println("Iframe snippet: ${iframeResponse.take(2000)}...") // More chars for JS visibility

        // Static .m3u8 check
        val staticStream = Regex("hls\\.loadSource\\(['\"]?(https?://[^'\"]+\\.m3u8[^'\"]*)['\"]?\\)")
            .find(iframeResponse)?.groupValues?.get(1)
            ?: Regex("playerInstance\\.load\\(['\"]?(https?://[^'\"]+\\.m3u8[^'\"]*)['\"]?\\)")
                .find(iframeResponse)?.groupValues?.get(1)
            ?: Regex("file:\\s*['\"]?(https?://[^'\"]+\\.m3u8[^'\"]*)['\"]?")
                .find(iframeResponse)?.groupValues?.get(1)
            ?: Regex("['\"]?(https?://[^'\"\\s]+\\.m3u8(?:\\?[^'\"\\s]*)?)['\"]?)")
                .find(iframeResponse)?.groupValues?.get(1)
        if (staticStream != null) {
            println("Static Stream URL: $staticStream")
            val finalStreamUrl = if (staticStream.startsWith("//")) "https:$staticStream" else staticStream
            return ExtractorLink(
                source = sourceName,
                name = sourceName,
                url = finalStreamUrl,
                referer = resolvedIframeUrl,
                quality = Qualities.Unknown.value,
                isM3u8 = true,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to ref,
                    "Origin" to ref
                )
            )
        } else {
            println("No static .m3u8 found in iframe response")
        }

        // JW Player JS pattern extraction
        val fetchUrl = Regex("fetch\\(['\"]?(https?://[^'\"]+)['\"]?\\)")
            .find(iframeResponse)?.groupValues?.get(1)
        val streamBase = Regex("var\\s+stream\\s*=\\s*['\"]?(https?://[^'\"\\s]+)['\"]?")
            .find(iframeResponse)?.groupValues?.get(1)
            ?: Regex("const\\s+url\\s*=\\s*['\"]?(https?://[^'\"\\s]+)['\"]?")
                .find(iframeResponse)?.groupValues?.get(1)
        val streamPath = Regex("['\"]?(/(?:global|stream)/[^'\"\\s]+/index\\.m3u8)['\"]?")
            .find(iframeResponse)?.groupValues?.get(1)
        val token = Regex("\\?token=([a-f0-9-]{40,})")
            .find(iframeResponse)?.groupValues?.get(1)

        if (streamBase != null || streamPath != null || token != null) {
            println("Found JS patterns - Base: $streamBase, Path: $streamPath, Token: $token")
            if (streamBase != null && streamPath != null) {
                val finalLink = "$streamBase$streamPath" + (token?.let { "?token=$it" } ?: "")
                println("Constructed Stream URL: $finalLink")
                return ExtractorLink(
                    source = sourceName,
                    name = sourceName,
                    url = finalLink,
                    referer = resolvedIframeUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to ref,
                        "Origin" to ref
                    )
                )
            }
        } else {
            println("No JS patterns (fetch, stream, path, token) found in iframe")
        }

        // Check API fetch
        if (fetchUrl != null) {
            println("Found API fetch URL: $fetchUrl")
            val fetchResponse = try {
                app.get(fetchUrl, headers = iframeHeaders).text
            } catch (e: Exception) {
                println("Failed to fetch $fetchUrl: ${e.message}")
                return null
            }
            println("API fetch response: $fetchResponse")
            val apiStream = Regex("['\"]?(https?://[^'\"\\s]+\\.m3u8(?:\\?[^'\"\\s]*)?)['\"]?)")
                .find(fetchResponse)?.groupValues?.get(1)
            if (apiStream != null) {
                println("API Stream URL: $apiStream")
                return Extract VictimsorLink(
                    source = sourceName,
                    name = sourceName,
                    url = apiStream,
                    referer = resolvedIframeUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to ref,
                        "Origin" to ref
                    )
                )
            }
        }

        // Fetch jwpsrv.js or other scripts
        val doc = Jsoup.parse(iframeResponse)
        val scripts = doc.select("script[src]")
        for (script in scripts) {
            val scriptUrl = script.attr("src").let {
                when {
                    it.startsWith("/") -> "$refererBase$it"
                    it.startsWith("//") -> "https:$it"
                    else -> it
                }
            }
            println("Fetching script: $scriptUrl")
            val scriptResponse = try {
                app.get(scriptUrl, headers = iframeHeaders).text
            } catch (e: Exception) {
                println("Failed to fetch script $scriptUrl: ${e.message}")
                continue
            }
            println("Script snippet: ${scriptResponse.take(2000)}...")
            val jwStream = Regex("['\"]?(https?://[^'\"\\s]+\\.m3u8(?:\\?[^'\"\\s]*)?)['\"]?)")
                .find(scriptResponse)?.groupValues?.get(1)
            if (jwStream != null) {
                println("Script Stream URL: $jwStream")
                return ExtractorLink(
                    source = sourceName,
                    name = sourceName,
                    url = jwStream,
                    referer = resolvedIframeUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to ref,
                        "Origin" to ref
                    )
                )
            }
        }

        println("No stream found in $resolvedIframeUrl after all checks")
        return null
    }
}
