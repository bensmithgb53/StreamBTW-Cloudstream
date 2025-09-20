package ben.smith53

import android.content.Context
import ben.smith53.extractors.StreamedExtractor
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StreamedPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(StreamedProvider(context))
        registerExtractorAPI(StreamedExtractor(context))
    }
}