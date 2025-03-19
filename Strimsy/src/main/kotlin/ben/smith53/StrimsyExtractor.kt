package ben.smith53

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink

class StrimsyExtractor : ExtractorApi() {
    override val name = "StrimsyExtractor"
    override val mainUrl = "https://strimsy.top"
    override val requiresReferer = true

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    var cookies: Map<String, String> = emptyMap()

    private suspend fun followIframeChain(url: String, referer: String?, depth: Int = 0, maxDepth: Int = 3): String? {
        println("StrimsyExtractor: Following iframe chain for $url at depth $depth")
        if (depth >= maxDepth) {
            println("StrimsyExtractor: Reached max iframe depth ($maxDepth) at $url")
            return url
        }
        val headersWithCookies = baseHeaders + mapOf("Cookie" to cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        val response = app.get(url, headers = headersWithCookies, referer = referer)
        val document = response.document
        val iframe = document.selectFirst("iframe[src]")
        val iframeSrc = iframe?.attr("src")?.trim()
        if (iframeSrc.isNullOrEmpty()) {
            println("StrimsyExtractor: No iframe found at $url")
            return url
        }

        val fixedIframeSrc = if (iframeSrc.startsWith("/")) {
            "$mainUrl$iframeSrc"
        } else if (!iframeSrc.startsWith("http")) {
            val baseUrl = url.substringBeforeLast("/") + "/"
            "$baseUrl$iframeSrc"
        } else {
            iframeSrc
        }

        println("StrimsyExtractor: Following iframe from $url to $fixedIframeSrc")
        return followIframeChain(fixedIframeSrc, url, depth + 1, maxDepth)
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        println("StrimsyExtractor: getUrl called with url=$url, referer=$referer")
        val finalUrl = followIframeChain(url, referer) ?: url
        val headersWithCookies = baseHeaders + mapOf("Cookie" to cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        val response = app.get(finalUrl, headers = headersWithCookies, referer = referer)
        val document = response.document

        println("StrimsyExtractor: Final URL: $finalUrl")
        println("StrimsyExtractor: Response body: ${response.text.take(500)}...")

        val scriptText = document.select("script").joinToString("\n") { it.html() }
        println("StrimsyExtractor: Script text: ${scriptText.take(500)}...")
        val playbackUrlRegex = Regex("(?:var\\s+playbackURL|hlsUrl)\\s*=\\s*\"(https?://[^\"']+\\.(?:m3u8|mpd)[^\"']*)\"")
        val match = playbackUrlRegex.find(scriptText)
        val streamUrl = match?.groupValues?.get(1)

        if (streamUrl != null) {
            println("StrimsyExtractor: Found stream URL: $streamUrl")
            val isM3u8 = streamUrl.endsWith(".m3u8")
            val isDash = streamUrl.endsWith(".mpd")
            if (isM3u8 || isDash) {
                if (isM3u8) {
                    val m3u8Response = app.get(streamUrl, headers = headersWithCookies, referer = finalUrl).text
                    println("StrimsyExtractor: Master playlist: ${m3u8Response.take(500)}...")
                    val variantRegex = Regex("https?://[^\\s\"']+\\.m3u8(?:\\?[^\\s\"']+)?")
                    val variantUrls = variantRegex.findAll(m3u8Response).map { it.value }.toList()

                    if (variantUrls.isNotEmpty()) {
                        val selectedVariant = variantUrls.firstOrNull { it.contains("mono.m3u8") } ?: variantUrls.first()
                        println("StrimsyExtractor: Selected variant: $selectedVariant")
                        return listOf(
                            ExtractorLink(
                                source = name,
                                name = "$name (Live)",
                                url = selectedVariant,
                                referer = finalUrl,
                                quality = -1,
                                isM3u8 = true,
                                headers = headersWithCookies
                            )
                        )
                    }
                }

                println("StrimsyExtractor: Using stream URL directly: $streamUrl")
                return listOf(
                    ExtractorLink(
                        source = name,
                        name = "$name (Live)",
                        url = streamUrl,
                        referer = finalUrl,
                        quality = -1,
                        isM3u8 = isM3u8,
                        headers = headersWithCookies
                    )
                )
            }
        }

        val streamRegex = Regex("https?://[^\\s\"']+\\.(?:m3u8|mpd)(?:\\?[^\\s\"']+)?")
        val streamUrls = streamRegex.findAll(response.text).map { it.value }.toList()

        if (streamUrls.isNotEmpty()) {
            println("StrimsyExtractor: Found fallback stream URLs: $streamUrls")
            return streamUrls.map { streamUrl ->
                val isM3u8 = streamUrl.endsWith(".m3u8")
                ExtractorLink(
                    source = name,
                    name = "$name (Live)",
                    url = streamUrl,
                    referer = finalUrl,
                    quality = -1,
                    isM3u8 = isM3u8,
                    headers = headersWithCookies
                )
            }
        }

        println("StrimsyExtractor: No stream URLs found in $finalUrl")
        return null
    }
}
