package ben.smith53

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class StrimsyExtractor : ExtractorApi() {
    override val mainUrl = "https://strimsy.top"
    override val name = "Strimsy"
    override val requiresReferer = true
    private val streamApi = "https://streamtp3.com/global1.php"
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Referer" to (referer ?: "https://streamtp3.com/"),
            "User-Agent" to userAgent
        )

        val sourceMatch = Regex("source=(\\d+)").find(url)?.groupValues?.get(1)
        if (sourceMatch != null) {
            val apiUrl = "$streamApi?stream=ufc_$sourceMatch"
            val apiResponse = app.get(apiUrl, headers = headers).text
            val m3u8Pattern = Regex("""https?://[^'"\s]+?\.m3u8\?token=[a-f0-9]+-\w+-\d+-\d+""")
            val m3u8Match = m3u8Pattern.find(apiResponse)

            if (m3u8Match != null) {
                val m3u8Url = m3u8Match.value
                callback(
                    ExtractorLink(
                        source = name,
                        name = name,
                        url = m3u8Url,
                        referer = "https://streamtp3.com/",
                        quality = Qualities.Unknown.value,
                        isM3u8 = true,
                        headers = headers
                    )
                )
            }
        }
    }
}