package ben.smith53

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class TmdbVidsrcPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TmdbVidsrcProvider())
    }
}
