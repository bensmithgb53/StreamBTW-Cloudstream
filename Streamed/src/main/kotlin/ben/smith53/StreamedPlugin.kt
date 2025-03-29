package ben.smith53

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StreamedPlugin: Plugin() {
    override fun load() {
        registerMainAPI(StreamedProvider()) // No arguments
    }
}