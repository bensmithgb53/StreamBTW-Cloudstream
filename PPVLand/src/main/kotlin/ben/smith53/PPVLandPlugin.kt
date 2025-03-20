package ben.smith53

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class PPVLandPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PPVLandProvider())
        // Removed registerExtractorAPI(PPVLandExtractor())
    }
}
