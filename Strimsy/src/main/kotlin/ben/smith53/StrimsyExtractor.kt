package ben.smith53

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.Jsoup
import java.util.regex.Pattern
import android.util.Base64

class StrimsyExtractor : ExtractorApi() {
    override val name = "StrimsyExtractor"
    override val mainUrl = "https://strimsy.top"
    override val requiresReferer = true

    private val cloudflareKiller = CloudflareKiller()

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36",
            "Referer" to (referer ?: mainUrl)
        )
        val response = app.get(url, headers = headers, interceptor = cloudflareKiller).text
        val doc = Jsoup.parse(response)
        val scriptText = doc.select("script").joinToString("\n") { it.html() }

        val links = mutableListOf<ExtractorLink>()

        // Playback URL
        val playbackPattern = Pattern.compile("""(?:var\s+playbackURL|hlsUrl)\s*=\s*"([^"]+\.m3u8[^"]*)"""")
        playbackPattern.matcher(scriptText).let { matcher ->
            while (matcher.find()) {
                val streamUrl = matcher.group(1)
                addM3u8Link(links, streamUrl, url, "Live")
            }
        }

        // Base64
        val base64Pattern = Pattern.compile("""atob\('([^']+)'\)""")
        base64Pattern.matcher(scriptText).let { matcher ->
            while (matcher.find()) {
                val decoded = String(Base64.decode(matcher.group(1), Base64.DEFAULT))
                val fullUrl = if (decoded.startsWith("http")) decoded else "${url.substringBeforeLast('/')}/$decoded"
                if (fullUrl.endsWith(".m3u8")) {
                    addM3u8Link(links, fullUrl, url, "Live (Base64)")
                }
            }
        }

        // Fallback
        val streamPattern = Pattern.compile("""https?://[^\s"']+\.m3u8(?:\?[^"']+)?""")
        streamPattern.matcher(response).let { matcher ->
            while (matcher.find()) {
                val streamUrl = matcher.group()
                if (streamUrl !in links.map { it.url }) {
                    addM3u8Link(links, streamUrl, url, "Live (Fallback)")
                }
            }
        }

        return links
    }

    private fun addM3u8Link(links: MutableList<ExtractorLink>, url: String, referer: String, nameSuffix: String) {
        try {
            val m3u8Response = app.get(url, headers = mapOf("Referer" to referer)).text
            val qualityMatch = Pattern.compile("""RESOLUTION=(\d+x\d+)""").matcher(m3u8Response)
            val quality = if (qualityMatch.find()) {
                qualityMatch.group(1).split("x")[1].toInt()
            } else {
                getQualityFromName("720p")
            }
            M3u8Helper.generateM3u8(
                source = name,
                streamUrl = url,
                referer = referer,
                quality = quality,
                name = "$name - $nameSuffix"
            ).forEach { links.add(it) }
        } catch (e: Exception) {
            links.add(
                ExtractorLink(
                    source = name,
                    name = "$name - $nameSuffix",
                    url = url,
                    referer = referer,
                    quality = getQualityFromName("720p"),
                    isM3u8 = true
                )
            )
        }
    }
                                              }
