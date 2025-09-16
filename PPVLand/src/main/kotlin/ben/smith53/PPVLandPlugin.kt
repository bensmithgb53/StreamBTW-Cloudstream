package ben.smith53

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import ben.smith53.extractors.PPVLandExtractor

@CloudstreamPlugin
class PPVLandPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PPVLandProvider())
        registerExtractorAPI(PPVLandExtractor())
    }
}
