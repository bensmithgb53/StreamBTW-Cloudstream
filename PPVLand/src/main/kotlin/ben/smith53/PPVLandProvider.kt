package ben.smith53

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.logging.Logger

class PPVLandProvider : MainAPI() {
    override var mainUrl = "https://ppv.land"
    override var name = "PPVLand"
    override val hasMainPage = true
    override val hasSearch = false
    override val supportedTypes = setOf(TvType.Live)

    private val apiUrl = "$mainUrl/api/streams"
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"
    private val timeout = 15_000L // 15 seconds in milliseconds
    private val logger = Logger.getLogger(PPVLandProvider::class.java.name)

    private val baseHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "*/*",
        "Accept-Encoding" to "gzip, deflate, br, zstd",
        "Connection" to "keep-alive",
        "Accept-Language" to "en-US,en;q=0.5",
        "X-FS-Client" to "FS WebClient 1.0",
        "Cookie" to "cf_clearance=Spt9tCB2G5.prpsED77vIRRv_7DXvw__Jw_Esqm53yw-1742505249-1.2.1.1-VXaRZXapXOenQsbIVYelJXCR2YFju.WlikuWSiXF2DNtDyxt5gjuRRhQq6hznJ"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        logger.info("Fetching all streams from: $apiUrl")
        val events = fetchEvents()
        if (events.isEmpty()) {
            logger.severe("No events found")
            return HomePageResponse(emptyList())
        }

        val homePageList = events.groupBy { it.category }.map { (category, streams) ->
            val streamItems = streams.map { event ->
                LiveSearchResponse(
                    name = event.name,
                    url = "$mainUrl/api/streams/${event.id}",
                    apiName = this.name,
                    posterUrl = event.poster,
                    type = TvType.Live
                )
            }
            HomePageList(category, streamItems)
        }

        return HomePageResponse(homePageList)
    }

    private suspend fun fetchEvents(): List<Event> = withContext(Dispatchers.IO) {
        try {
            val response = app.get(apiUrl, headers = baseHeaders, timeout = timeout).text
            logger.info("API fetched, raw length: ${response.length} bytes")
            val jsonData = JSONObject(response)
            val streamsArray = jsonData.getJSONArray("streams")
            logger.info("Found ${streamsArray.length()} categories")

            val events = mutableListOf<Event>()
            for (i in 0 until streamsArray.length()) {
                val categoryData = streamsArray.getJSONObject(i)
                val categoryName = categoryData.optString("category", "Unknown")
                val streams = categoryData.getJSONArray("streams")
                logger.info("Processing category: $categoryName with ${streams.length()} streams")

                for (j in 0 until streams.length()) {
                    val stream = streams.getJSONObject(j)
                    val poster = stream.optString("poster")
                    if ("data:image" !in poster) {
                        val event = Event(
                            id = stream.optString("id"),
                            name = stream.optString("name"),
                            uriName = stream.optString("uri_name"),
                            poster = poster,
                            startsAt = stream.optString("starts_at"),
                            iframe = stream.optString("iframe", null),
                            category = categoryName
                        )
                        events.add(event)
                        logger.info("Added stream: ${event.name}, ID: ${event.id}, URI: ${event.uriName}")
                    }
                }
            }
            events
        } catch (e: Exception) {
            logger.severe("Failed to fetch events - ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse = withContext(Dispatchers.IO) {
        val streamId = url.split("/").last()
        logger.info("Loading stream ID: $streamId from $url")
        val event = fetchEvents().find { it.id == streamId }
            ?: throw ErrorLoadingException("Stream not found")

        return@withContext LiveStreamLoadResponse(
            name = event.name,
            url = url,
            apiName = this@PPVLandProvider.name,
            dataUrl = url,
            posterUrl = event.poster
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val streamId = data.split("/").last()
        val m3u8Url = fetchM3u8Url(streamId) ?: return@withContext false

        callback.invoke(
            ExtractorLink(
                source = this@PPVLandProvider.name,
                name = "PPVLand Stream",
                url = m3u8Url,
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                isM3u8 = true
            )
        )
        true
    }

    private suspend fun fetchM3u8Url(streamId: String): String? = withContext(Dispatchers.IO) {
        val apiUrl = "$mainUrl/api/streams/$streamId"
        try {
            logger.info("Fetching m3u8 for stream ID $streamId: $apiUrl")
            val response = app.get(apiUrl, headers = baseHeaders, timeout = timeout).text
            logger.info("API fetched, raw length: ${response.length} bytes")
            val jsonData = JSONObject(response)
            val data = jsonData.getJSONObject("data")
            val m3u8Url = data.optString("m3u8")
            if (m3u8Url.isEmpty()) {
                logger.severe("No m3u8 URL found in JSON for stream ID $streamId")
                return@withContext null
            }
            logger.info("Found m3u8 URL: $m3u8Url")
            m3u8Url
        } catch (e: Exception) {
            logger.severe("Failed to fetch m3u8 for stream ID $streamId - ${e.message}")
            null
        }
    }

    data class Event(
        val id: String,
        val name: String,
        val uriName: String,
        val poster: String,
        val startsAt: String,
        val iframe: String?,
        val category: String
    )
}
