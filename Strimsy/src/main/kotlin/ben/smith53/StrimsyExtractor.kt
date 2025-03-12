package ben.smith53

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.Jsoup

class StrimsyExtractor : ExtractorApi() {
    override val mainUrl = "https://strimsy.top"
    override val name = "Strimsy"
    override val requiresReferer = true

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

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
            val streamLink = extractStream(eventUrl)
            if (streamLink != null) {
                println("Successfully extracted stream: ${streamLink.url}")
                callback(streamLink)
            } else {
                println("No stream found for $eventUrl")
            }
        }
    }

    private suspend fun extractStream(eventUrl: String): ExtractorLink? {
        val headers = mapOf("User-Agent" to userAgent, "Referer" to mainUrl)
        println("Fetching event page: $eventUrl")
        val eventResponse = try {
            app.get(eventUrl, headers = headers).text
        } catch (e: Exception) {
            println("Failed to fetch event page $eventUrl: ${e.message}")
            return null
        }
        val eventDoc = Jsoup.parse(eventResponse)
        println("Event page snippet: ${eventResponse.take(500)}...")
        val liveIframe = eventDoc.selectFirst("iframe#rk")?.attr("src") ?: run {
            println("No live iframe found in $eventUrl")
            return null
        }
        val liveUrl = if (liveIframe.startsWith("/")) "$mainUrl$liveIframe" else liveIframe
        println("Live URL: $liveUrl")

        val liveHeaders = mapOf("User-Agent" to userAgent, "Referer" to eventUrl)
        println("Fetching live page: $liveUrl")
        val liveResponse = try {
            app.get(liveUrl, headers = liveHeaders).text
        } catch (e: Exception) {
            println("Failed to fetch live page $liveUrl: ${e.message}")
            return null
        }
        val liveDoc = Jsoup.parse(liveResponse)
        println("Live page snippet: ${liveResponse.take(500)}...")
        val embedUrl = liveDoc.selectFirst("iframe#rk")?.attr("src") ?: run {
            println("No embed iframe found in $liveUrl")
            return null
        }
        val finalEmbedUrl = if (embedUrl.startsWith("//")) "https:$embedUrl" else embedUrl
        println("Embed URL: $finalEmbedUrl")

        val embedHeaders = mapOf("User-Agent" to userAgent, "Referer" to liveUrl)
        println("Fetching embed page: $finalEmbedUrl")
        val embedResponse = try {
            app.get(finalEmbedUrl, headers = embedHeaders).text
        } catch (e: Exception) {
            println("Failed to fetch embed URL $finalEmbedUrl: ${e.message}")
            return null
        }
        println("Embed Response Snippet: ${embedResponse.take(1000)}...")

        // Search for .m3u8 with broad patterns
        val streamUrl = Regex("hls\\.loadSource\\(['\"]?(https?://[^'\"]+\\.m3u8[^'\"]*)['\"]?\\)")
            .find(embedResponse)?.groupValues?.get(1)
            ?: Regex("video\\.src\\s*=\\s*['\"]?(https?://[^'\"]+\\.m3u8[^'\"]*)['\"]?")
                .find(embedResponse)?.groupValues?.get(1)
            ?: Regex("['\"]?(https?://[^'\"\\s]+\\.m3u8(?:\\?[^'\"\\s]*)?)['\"]?)")
                .find(embedResponse)?.groupValues?.get(1)
            ?: Regex("['\"]?(https?://[^'\"\\s]+/global/[^'\"\\s]+/index\\.m3u8(?:\\?[^'\"\\s]*)?)['\"]?)")
                .find(embedResponse)?.groupValues?.get(1)
            ?: run {
                println("No .m3u8 URL found in embed response")
                return null
            }
        println("Stream URL: $streamUrl")

        val finalStreamUrl = if (streamUrl.startsWith("//")) "https:$streamUrl" else streamUrl
        return ExtractorLink(
            source = this.name,
            name = this.name,
            url = finalStreamUrl,
            referer = finalEmbedUrl,
            quality = Qualities.Unknown.value,
            isM3u8 = finalStreamUrl.endsWith(".m3u8"),
            headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to finalEmbedUrl,
                "Origin" to finalEmbedUrl.substringBefore("/").substringAfter("//")
            )
        )
    }
}
