package ben.smith53

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class StrimsyExtractor : ExtractorApi() {
    override val name = "Strimsy"
    override val mainUrl = "https://strimsy.top"
    override val requiresReferer = true
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    private val cookie = "PHPSESSID=sr2ufaf1h3ha63dge62ahob"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to (referer ?: url),
            "Cookie" to cookie
        )
        val response = app.get(url, headers = headers).text
        val streamId = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").find(response)?.groupValues?.get(0)
            ?: return
        val apiUrl = "$url?type=live"
        val apiResponse = app.get(apiUrl, headers = headers).text
        val m3u8Path = Regex("/[a-zA-Z0-9\\-]+\\.m3u8").find(apiResponse)?.groupValues?.get(0)
            ?: return
        val baseCdn = "https://ve16o28z6o6dszgkty3jni6ulo3ba9jt.global.ssl.fastly.net"
        val finalUrl = "$baseCdn/v3/fragment/$streamId/tracks-v1a1/$m3u8Path"

        callback.invoke(
            ExtractorLink(
                name,
                name,
                finalUrl,
                referer ?: url,
                true,
                Qualities.Unknown.value,
                headers = headers
            )
        )
    }
}
