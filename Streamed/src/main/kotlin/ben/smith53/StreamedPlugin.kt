package ben.smith53

import android.content.Context
import ben.smith53.extractors.StreamedExtractor
import ben.smith53.extractors.StreamedExtractor2
import ben.smith53.extractors.StreamedExtractor3
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StreamedPlugin : Plugin() {
    override fun load(context: Context) {
        // Register the main provider
        registerMainAPI(StreamedProvider())
        // Register the extractors
        registerExtractorAPI(StreamedExtractor())
        registerExtractorAPI(StreamedExtractor2())
        registerExtractorAPI(StreamedExtractor3())
    }
}