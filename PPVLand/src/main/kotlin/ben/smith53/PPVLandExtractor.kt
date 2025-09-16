package ben.smith53.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class PPVLandExtractor : ExtractorApi() {
    override val name = "PPVLandExtractor"
    override val mainUrl = "https://ppv.to"
    override val requiresReferer = true

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"
    private val HEADERS = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Encoding" to "gzip, deflate, br, zstd",
        "Connection" to "keep-alive",
        "Accept-Language" to "en-US,en;q=0.5",
        "X-FS-Client" to "FS WebClient 1.0"
    )

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            val apiUrl = if (url.startsWith("$mainUrl/api/streams/")) url else {
                // attempt to extract numeric id if user passed just id
                val idCandidate = url.split("/").firstOrNull { it.matches(Regex("\\d+")) }
                if (idCandidate != null) "$mainUrl/api/streams/$idCandidate" else url
            }

            // If api URL, parse JSON first
            if (apiUrl.contains("/api/streams/")) {
                val resp = app.get(apiUrl, headers = HEADERS, referer = referer ?: "$mainUrl/").text
                val mapper = jacksonObjectMapper()
                val jsonData: Map<String, Any> = mapper.readValue(resp)
                val data = jsonData["data"] as? Map<String, Any> ?: jsonData

                val m3u8 = (data["m3u8"] as? String)?.takeIf { it.isNotBlank() }
                if (!m3u8.isNullOrBlank()) {
                    return listOf(
                        newExtractorLink(source = this.name, name = this.name, url = m3u8, type = ExtractorLinkType.M3U8) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }

                val sourcesAny = data["sources"]
                if (sourcesAny is List<*>) {
                    for (s in sourcesAny) {
                        if (s is Map<*, *>) {
                            val sdata = s["data"] as? String ?: continue
                            val stype = s["type"] as? String ?: ""
                            if (sdata.contains(".m3u8")) {
                                return listOf(
                                    newExtractorLink(source = this.name, name = this.name, url = sdata, type = ExtractorLinkType.M3U8) {
                                        this.referer = "$mainUrl/"
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                            } else if (stype.equals("iframe", true) || sdata.contains("/embed/")) {
                                return extractFromEmbed(sdata, referer)
                            }
                        }
                    }
                }
            }

            // If url looks like embed or HTML page, attempt to parse page
            if (url.contains("/embed/") || url.endsWith(".html") || !url.contains(".m3u8")) {
                return extractFromEmbed(url, referer)
            }

            // If it is already an m3u8
            if (url.contains(".m3u8")) {
                return listOf(
                    newExtractorLink(source = this.name, name = this.name, url = url, type = ExtractorLinkType.M3U8) {
                        this.referer = referer ?: "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    private suspend fun extractFromEmbed(embedUrl: String, referer: String?): List<ExtractorLink>? {
        try {
            val fixed = embedUrl.replace("\\/", "/")
            val resp = app.get(fixed, headers = HEADERS, referer = referer ?: "$mainUrl/").text

            val patterns = listOf(
                "\"(https?://[^\"]+?\\.m3u8[^\"]*)\"",
                "'(https?://[^']+?\\.m3u8[^']*)'",
                "file\\s*:\\s*\"(https?://[^\"]+?\\.m3u8[^\"]*)\"",
                "file\\s*:\\s*'(https?://[^']+?\\.m3u8[^']*)'",
                "playlist\\s*:\\s*\\[\\s*\\{[^}]*file\\s*:\\s*\"(https?://[^\"]+?\\.m3u8[^\"]*)\"",
                "src\\s*=\\s*\"(https?://[^\"]+?\\.m3u8[^\"]*)\"",
                "(https?://[\\w\\d\\.\\-:/_%]+\\.m3u8[\\w\\d\\-\\?=&_%]*)"
            )

            for (p in patterns) {
                val regex = Regex(p)
                val match = regex.find(resp)
                if (match != null && match.groupValues.size >= 2) {
                    val found = match.groupValues[1].replace("\\/", "/")
                    return listOf(
                        newExtractorLink(source = this.name, name = this.name, url = found, type = ExtractorLinkType.M3U8) {
                            this.referer = fixed
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }

            // Follow nested iframe if present
            val iframeRegex = Regex("<iframe[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            val iframeMatch = iframeRegex.find(resp)
            if (iframeMatch != null) {
                val iframeSrc = iframeMatch.groupValues[1].replace("\\/", "/")
                if (iframeSrc != fixed && iframeSrc.contains("http")) {
                    return extractFromEmbed(iframeSrc, fixed)
                }
            }

            return null
        } catch (e: Exception) {
            return null
        }
    }
}