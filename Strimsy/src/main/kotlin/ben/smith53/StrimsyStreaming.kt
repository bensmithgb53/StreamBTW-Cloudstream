package ben.smith53

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.network.requests.MainPageRequest
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.HomePageList
import java.util.Calendar
import java.util.Locale

class StrimsyStreaming : MainAPI() {
    override var name = "StrimsyStreaming"
    override var mainUrl = "https://strimsy.top"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

    // Day translation dictionary (same as Python)
    private val DAY_TRANSLATION = mapOf(
        "Poniedziałek" to "Monday",
        "Wtorek" to "Tuesday",
        "Środa" to "Wednesday",
        "Czwartek" to "Thursday",
        "Piątek" to "Friday",
        "Sobota" to "Saturday",
        "Niedziela" to "Sunday"
    )

    // Event translation dictionary (same as Python)
    private val EVENT_TRANSLATION = mapOf(
        "pilkanozna" to "Football",
        "koszykowka" to "Basketball",
        "kosz" to "Basketball",
        "nba" to "NBA",
        "hhokej" to "Hockey",
        "walki" to "Fighting",
        "kolarstwo" to "Cycling",
        "siatkowka" to "Volleyball",
        "pilkareczna" to "Handball",
        "bilard" to "Snooker",
        "tenis" to "Tennis",
        "skoki" to "Ski Jumping",
        "magazyn" to "Magazine"
    )

    // Hardcoded schedule for March 19, 2025 (from Python's get_main_page())
    private val scheduleJson = """
    [
        {
            "day": "Wednesday",
            "events": [
                {"time": "16:00", "name": "Tennis: ATP Miami", "url": "https://strimsy.top/Tenis/ATPMiami.php"},
                {"time": "16:00", "name": "Tennis: WTA Miami", "url": "https://strimsy.top/Tenis/WTAMiami.php"},
                {"time": "16:00", "name": "Cycling: Nokere Koerse, Belgia", "url": "https://strimsy.top/Kolarstwo.php"},
                {"time": "17:00", "name": "Volleyball: Ziraat Bankasi - Trentino", "url": "https://strimsy.top/ZiraatBankasiTrentino.php"},
                {"time": "17:00", "name": "Hockey: JKH GKS - GKS Tychy", "url": "https://strimsy.top/JKHGKSGKSTychy.php"},
                {"time": "18:00", "name": "Cross-country skiing: Biegi narciarskie", "url": "https://strimsy.top/BiegiNarciarskie.php"},
                {"time": "18:00", "name": "Powerlifting: Trójbój siłowy", "url": "https://strimsy.top/TrojbojSilowy.php"},
                {"time": "18:00", "name": "Volleyball: Novara K - Chieri 76 K", "url": "https://strimsy.top/NovaraKChieri76K.php"},
                {"time": "18:00", "name": "Volleyball: Projekt Warszawa - Halkbank", "url": "https://strimsy.top/ProjektWarszawaHalkbank.php"},
                {"time": "18:00", "name": "Handball: Kielce - Śląsk Wrocław", "url": "https://strimsy.top/KielceSlaskWroclaw.php"},
                {"time": "18:00", "name": "Handball: Ostrów Wielkopolski - MMTS Kwidzyn", "url": "https://strimsy.top/WielkopolskiMMTSKwidzyn.php"},
                {"time": "18:00", "name": "Handball: Wisła Płock - Gwardia Opole", "url": "https://strimsy.top/WislaPlockGwardiaOpole.php"},
                {"time": "18:00", "name": "Basketball: AZS Poznań K - Sosnowiec K", "url": "https://strimsy.top/AZSPoznanKSosnowiecK.php"},
                {"time": "18:00", "name": "Basketball: Gorzów K - AZS UMCS Lublin K", "url": "https://strimsy.top/GorzowKAZSUMCSLublinK.php"},
                {"time": "18:30", "name": "Basketball: Petkim Spor - Tenerife", "url": "https://strimsy.top/PetkimSporTenerife.php"},
                {"time": "18:30", "name": "Basketball: Wurzburg - Promitheas", "url": "https://strimsy.top/WurzburgPromitheas.php"},
                {"time": "18:45", "name": "Football: Wolfsburg K - Barcelona K", "url": "https://strimsy.top/WolfsburgKBarcelonaK.php"},
                {"time": "19:00", "name": "Basketball: Starogard Gdański - Śląsk Wrocław II", "url": "https://strimsy.top/StarogardGdanskiSlaskWroclawII.php"},
                {"time": "20:00", "name": "Basketball: Nanterre - Murcia", "url": "https://strimsy.top/NanterreMurcia.php"},
                {"time": "20:00", "name": "Snooker: Players Championship", "url": "https://strimsy.top/Snooker2.php"},
                {"time": "20:00", "name": "Handball: Azoty-Puławy - Wybrzeże Gdańsk", "url": "https://strimsy.top/AzotyPulawyWybrzezeGdansk.php"},
                {"time": "20:00", "name": "Volleyball: Tours - Resovia Rzeszów", "url": "https://strimsy.top/ToursResoviaRzeszow.php"},
                {"time": "20:00", "name": "Basketball: Bamberg - Dziki Warszawa", "url": "https://strimsy.top/BambergDzikiWarszawa.php"},
                {"time": "20:00", "name": "Basketball: Katarzynki Toruń K - VBW Gdynia K", "url": "https://strimsy.top/KatarzynkiTorunKVBWGdyniaK.php"},
                {"time": "20:00", "name": "Basketball: Polkowice K - Ślęza Wrocław K", "url": "https://strimsy.top/PolkowiceKSlezaWroclawK.php"},
                {"time": "20:30", "name": "Volleyball: Jastrzębski Węgiel - Olympiakos", "url": "https://strimsy.top/JastrzebskiWegielOlympiakos.php"},
                {"time": "20:30", "name": "Volleyball: Lube Civitanova - Luk Lublin", "url": "https://strimsy.top/LubeCivitanovaLukLublin.php"},
                {"time": "20:30", "name": "Handball: Legionowo - MKS Kalisz", "url": "https://strimsy.top/LegionowoMKSKalisz.php"},
                {"time": "20:30", "name": "Basketball: Tortona - AEK Athens", "url": "https://strimsy.top/TortonaAEKAthens.php"},
                {"time": "21:00", "name": "Football: Manchester City K - Chelsea K", "url": "https://strimsy.top/ManchesterCityKChelseaK.php"},
                {"time": "00:00", "name": "NBA: WSZYSTKIE MECZE 🏀", "url": "https://strimsy.top/NBA/"},
                {"time": "00:00", "name": "Hockey: NHL: WSZYSTKIE MECZE 🏒", "url": "https://strimsy.top/NHL/"},
                {"time": "01:00", "name": "Fighting: AEW Dynamite", "url": "https://strimsy.top/fight/AEWDynamite.php"}
            ]
        }
    ]
    """

    data class Schedule(
        val day: String,
        val events: List<Event>
    )

    data class Event(
        val time: String,
        val name: String,
        val url: String
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Parse the hardcoded schedule
        val schedules = parseJson<List<Schedule>>(scheduleJson)

        // Get the current day to filter the schedule (e.g., show only today's events)
        val today = Calendar.getInstance().getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.ENGLISH)

        val homePageLists = schedules.mapNotNull { schedule ->
            // Translate the day if needed (though the hardcoded schedule is already in English)
            val translatedDay = DAY_TRANSLATION[schedule.day.split(" ")[1]] ?: schedule.day
            // Only show events for the current day (optional filtering)
            // Comment out the following line if you want to show all days
            if (translatedDay != today) return@mapNotNull null

            HomePageList(
                name = translatedDay,
                schedule.events.map { event ->
                    newLiveSearchResponse(
                        name = "${event.time} - ${event.name}",
                        url = event.url,
                        apiName = this.name,
                        type = TvType.Live,
                        posterUrl = null
                    )
                }
            )
        }
        return newHomePageResponse(homePageLists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Parse the hardcoded schedule for search
        val schedules = parseJson<List<Schedule>>(scheduleJson)

        val events = schedules.flatMap { it.events }
        return events.filter { event ->
            event.name.contains(query, ignoreCase = true) || event.time.contains(query, ignoreCase = true)
        }.map { event ->
            newLiveSearchResponse(
                name = "${event.time} - ${event.name}",
                url = event.url,
                apiName = this.name,
                type = TvType.Live,
                posterUrl = null
            )
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return newLiveStreamLoadResponse(
            name = url.substringAfterLast('/').substringBefore('.php'),
            url = url,
            apiName = this.name,
            dataUrl = url,
            posterUrl = null,
            year = 2025,
            plot = "Live sports event",
            contentRating = null
        )
    }
}
