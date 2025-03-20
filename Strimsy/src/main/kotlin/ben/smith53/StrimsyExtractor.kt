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
                addM3u8Link(links, streamUrl, url, "Live
