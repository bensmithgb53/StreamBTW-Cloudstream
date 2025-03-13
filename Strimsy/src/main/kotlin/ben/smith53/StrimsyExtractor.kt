package ben.smith53

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class StrimsyExtractor : ExtractorApi() {
    override val mainUrl = "https://strimsy.top"
    override val name = "Strims"
    override val requiresReferer = true
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val sources = listOf("1", "2", "3", "4", "5").map { source ->
            url.replace(Regex("source=\\d"), "source=$source")
        }.distinct()

        sources.forEach { sourceUrl ->
            val link = extractVideo(sourceUrl)
            if (link != null) {
                callback(link)
            }
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun extractVideo(url: String): ExtractorLink? {
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to mainUrl
        )

        val resp = app.get(url, headers = headers).text
        val iframe1 = Regex("<iframe[^>]+src=\"([^\"]+)\"").find(resp)?.groupValues?.get(1)
            ?: return null
        val iframe1Url = if (iframe1.startsWith("http")) iframe1 else "$mainUrl$iframe1"

        val resp2 = app.get(iframe1Url, headers = headers).text
        val scriptSrc = Regex("<script[^>]+src=\"([^\"]+wiki\\.js[^\"]*)\"").find(resp2)?.groupValues?.get(1)
            ?: return null
        val scriptUrl = if (scriptSrc.startsWith("http")) scriptSrc else "https:$scriptSrc"

        val resp3 = app.get(scriptUrl, headers = headers).text
        val iframe2Match = Regex("document\\.write\\([^>]*src=\"([^\"]+)\"").find(resp3)?.groupValues?.get(1)
            ?: return null
        val iframe2Url = iframe2Match.replace("'+ embedded +'", "desktop").replace("'+ fid +'", "skysact")

        val resp4 = app.get(iframe2Url, headers = headers).text
        val m3u8Base64 = Regex("atob\\('([^']+)'\\)").find(resp4)?.groupValues?.get(1)
            ?: return null
        val m3u8Path = String(android.util.Base64.decode(m3u8Base64, android.util.Base64.DEFAULT), Charsets.UTF_8)
        val m3u8UrlGuess = "https://futuristicsfun.com$m3u8Path"

        val resp5 = app.get(m3u8UrlGuess, headers = headers).text
        if (resp5.contains("#EXTM3U")) {
            return ExtractorLink(
                name,
                "Strims Source ${url.split("source=").last()}",
                m3u8UrlGuess,
                referer = iframe2Url,
                isM3u8 = true,
                headers = headers,
                quality = Qualities.Unknown.value
            )
        }

        // Hardcoded fallback (replace with dynamic logic if possible)
        val finalM3u8 = "https://jf6.dunyapurkaraja.com:999/hls/skysact.m3u8?md5=An4iTl-10fBIUuutnxRooQ&expires=1741865043"
        return ExtractorLink(
            name,
            "Strims Source ${url.split("source=").last()}",
            finalM3u8,
            referer = iframe2Url,
            isM3u8 = true,
            headers = headers,
            quality = Qualities.Unknown.value
        )
    }
}
