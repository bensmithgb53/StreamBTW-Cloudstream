package ben.smith53

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.util.Base64

class StrimsyExtractor : ExtractorApi() {
    override val name = "StrimsyExtractor"
    override val mainUrl = "https://strimsy.top"
    override val requiresReferer = true

    private val BASE_HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val sources = extractStreamSources(url)
        sources.forEach { source ->
            val sourceName = source["name"] ?: "Default"
            val sourceUrl = source["url"] ?: return@forEach

            println("Processing stream source: $sourceName -> $sourceUrl")
            val response = app.get(sourceUrl, headers = BASE_HEADERS).text
            println("Source page response body: ${response.take(500)}...")

            val doc = Jsoup.parse(response)
            val iframes = doc.select("iframe[src]")

            iframes.forEach { iframe ->
                val iframeSrc = iframe.attr("src").trim()
                if (iframeSrc.isEmpty() || isChatIframe(iframeSrc)) return@forEach

                val fixedIframeSrc = fixUrl(iframeSrc, mainUrl)
                println("Processing iframe $fixedIframeSrc")
                val links = extractStreamUrl(fixedIframeSrc, sourceUrl)
                links.forEach { link ->
                    callback(
                        ExtractorLink(
                            source = link["source"] ?: "StrimsyExtractor",
                            name = "${link["name"]} (${sourceName})",
                            url = link["url"] ?: return@forEach,
                            referer = link["referer"] ?: sourceUrl,
                            quality = Qualities.Unknown.value,
                            isM3u8 = link["is_m3u8"] == true
                        )
                    )
                }
            }
        }
    }

    private suspend fun extractStreamSources(eventUrl: String): List<Map<String, String>> {
        println("Extracting stream sources from $eventUrl")
        val response = app.get(eventUrl, headers = BASE_HEADERS).text
        println("Event page response body: ${response.take(500)}...")

        val doc = Jsoup.parse(response)
        val sources = mutableListOf<Map<String, String>>()

        val sourceLinks = doc.select("a[href*=\"?source=\"]")
        if (sourceLinks.isNotEmpty()) {
            sourceLinks.forEach { link ->
                val href = link.attr("href")
                val sourceName = link.text().trim()
                val sourceUrl = fixUrl(href, eventUrl.split("?")[0])
                sources.add(mapOf("name" to sourceName, "url" to sourceUrl))
            }
        } else {
            sources.add(mapOf("name" to "Default", "url" to eventUrl))
        }

        println("Found stream sources: ${sources.map { it["url"] }}")
        return sources
    }

    private suspend fun extractStreamUrl(url: String, referer: String): List<Map<String, Any>> {
        println("getUrl called with url=$url, referer=$referer")
        val finalUrl = followIframeChain(url, referer)
        val headers = BASE_HEADERS.toMutableMap()
        headers["Referer"] = referer

        val response = app.get(finalUrl, headers = headers).text
        println("Final URL: $finalUrl")
        println("Response body: ${response.take(500)}...")

        val doc = Jsoup.parse(response)
        val scriptText = doc.select("script").joinToString("\n") { it.html() }
        println("Script text: ${scriptText.take(500)}...")

        val links = mutableListOf<Map<String, Any>>()

        // Look for playbackURL or hlsUrl
        val playbackUrlPattern = Regex("""(?:var\s+playbackURL|hlsUrl)\s*=\s*"([^"']+\.(?:m3u8|mpd)[^"']*)"""")
        val match = playbackUrlPattern.find(scriptText)
        val streamUrl = match?.groupValues?.get(1)

        if (streamUrl != null) {
            println("Found stream URL: $streamUrl")
            val isM3u8 = streamUrl.endsWith(".m3u8")
            if (isM3u8) {
                try {
                    val m3u8Response = app.get(streamUrl, headers = headers).text
                    println("Master playlist: ${m3u8Response.take(500)}...")
                    val variantPattern = Regex("""https?://[^\s"']+\.m3u8(?:\?[^"']+)?""")
                    val variantUrls = variantPattern.findAll(m3u8Response).map { it.value }.toList()
                    if (variantUrls.isNotEmpty()) {
                        println("Found variant URLs: $variantUrls")
                        variantUrls.forEach { variantUrl ->
                            links.add(
                                mapOf(
                                    "source" to "StrimsyExtractor",
                                    "name" to "StrimsyExtractor (Live) - ${variantUrl.split('/').last()}",
                                    "url" to variantUrl,
                                    "referer" to finalUrl,
                                    "is_m3u8" to true
                                )
                            )
                        }
                    } else {
                        println("No variants found, using master URL: $streamUrl")
                        links.add(
                            mapOf(
                                "source" to "StrimsyExtractor",
                                "name" to "StrimsyExtractor (Live)",
                                "url" to streamUrl,
                                "referer" to finalUrl,
                                "is_m3u8" to true
                            )
                        )
                    }
                } catch (e: Exception) {
                    println("Error fetching master playlist: $e")
                    links.add(
                        mapOf(
                            "source" to "StrimsyExtractor",
                            "name" to "StrimsyExtractor (Live)",
                            "url" to streamUrl,
                            "referer" to finalUrl,
                            "is_m3u8" to true
                        )
                    )
                }
            } else {
                println("Using stream URL directly: $streamUrl")
                links.add(
                    mapOf(
                        "source" to "StrimsyExtractor",
                        "name" to "StrimsyExtractor (Live)",
                        "url" to streamUrl,
                        "referer" to finalUrl,
                        "is_m3u8" to isM3u8
                    )
                )
            }
        }

        // Look for base64-encoded URLs (e.g., atob('L2hscy9zdHJlYW0ubTN1OD9jaD1zcG4='))
        val base64Pattern = Regex("""atob\('([^']+)'\)""")
        val base64Matches = base64Pattern.findAll(scriptText).map { it.groupValues[1] }.toList()
        base64Matches.forEach { encodedUrl ->
            try {
                val decodedUrl = String(Base64.getDecoder().decode(encodedUrl))
                println("Decoded base64 URL: $decodedUrl")
                val baseUrl = finalUrl.substringBeforeLast('/')
                val fullUrl = fixUrl(decodedUrl, baseUrl)
                println("Full decoded URL: $fullUrl")
                if (fullUrl.endsWith(".m3u8")) {
                    try {
                        val m3u8Response = app.get(fullUrl, headers = headers).text
                        println("Master playlist (decoded): ${m3u8Response.take(500)}...")
                        val variantPattern = Regex("""https?://[^\s"']+\.m3u8(?:\?[^"']+)?""")
                        val variantUrls = variantPattern.findAll(m3u8Response).map { it.value }.toList()
                        if (variantUrls.isNotEmpty()) {
                            println("Found variant URLs (decoded): $variantUrls")
                            variantUrls.forEach { variantUrl ->
                                links.add(
                                    mapOf(
                                        "source" to "StrimsyExtractor",
                                        "name" to "StrimsyExtractor (Live) - ${variantUrl.split('/').last()}",
                                        "url" to variantUrl,
                                        "referer" to finalUrl,
                                        "is_m3u8" to true
                                    )
                                )
                            }
                        } else {
                            println("No variants found, using decoded URL: $fullUrl")
                            links.add(
                                mapOf(
                                    "source" to "StrimsyExtractor",
                                    "name" to "StrimsyExtractor (Live)",
                                    "url" to fullUrl,
                                    "referer" to finalUrl,
                                    "is_m3u8" to true
                                )
                            )
                        }
                    } catch (e: Exception) {
                        println("Error fetching decoded master playlist: $e")
                        links.add(
                            mapOf(
                                "source" to "StrimsyExtractor",
                                "name" to "StrimsyExtractor (Live)",
                                "url" to fullUrl,
                                "referer" to finalUrl,
                                "is_m3u8" to true
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                println("Error decoding base64 URL $encodedUrl: $e")
            }
        }

        // Fallback: Look for any m3u8 or mpd URLs in the response
        val streamPattern = Regex("""https?://[^\s"']+\.(?:m3u8|mpd)(?:\?[^"']+)?""")
        val streamUrls = streamPattern.findAll(response).map { it.value }.toList()
        if (streamUrls.isNotEmpty()) {
            println("Found fallback stream URLs: $streamUrls")
            streamUrls.forEach { streamUrl ->
                links.add(
                    mapOf(
                        "source" to "StrimsyExtractor",
                        "name" to "StrimsyExtractor (Live)",
                        "url" to streamUrl,
                        "referer" to finalUrl,
                        "is_m3u8" to streamUrl.endsWith(".m3u8")
                    )
                )
            }
        }

        if (links.isEmpty()) {
            println("No stream URLs found in $finalUrl")
        }
        return links
    }

    private suspend fun followIframeChain(url: String, referer: String, depth: Int = 0, maxDepth: Int = 5): String {
        println("Following iframe chain for $url at depth $depth")
        if (depth >= maxDepth) {
            println("Reached max iframe depth ($maxDepth) at $url")
            return url
        }

        val headers = BASE_HEADERS.toMutableMap()
        headers["Referer"] = referer
        val response = app.get(url, headers = headers).text
        println("Iframe response body: ${response.take(500)}...")

        val doc = Jsoup.parse(response)
        val iframe = doc.selectFirst("iframe[src]")
        if (iframe == null) {
            println("No iframe found at $url")
            return url
        }

        val iframeSrc = iframe.attr("src").trim()
        if (iframeSrc.isEmpty()) {
            println("Empty iframe src at $url")
            return url
        }

        val fixedIframeSrc = fixUrl(iframeSrc, if (url.contains(mainUrl)) mainUrl else url.substringBeforeLast('/'))
        println("Following iframe from $url to $fixedIframeSrc")
        return followIframeChain(fixedIframeSrc, url, depth + 1, maxDepth)
    }

    private fun fixUrl(url: String, baseUrl: String): String {
        println("Fixing URL: $url with baseUrl: $baseUrl")
        val fixedUrl = if (url.startsWith("http")) {
            url
        } else {
            if (url.startsWith("/")) "$baseUrl$url" else "$baseUrl/$url"
        }.replace("//", "/").replace("https:/", "https://")
        println("Fixed URL: $fixedUrl")
        return fixedUrl
    }

    private fun isChatIframe(url: String): Boolean {
        val result = "/layout/chat" in url.lowercase()
        println("Checking if iframe is chat: $url -> $result")
        return result
    }
                                       }
