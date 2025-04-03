package ben.smith53

import com.lagradost.cloudstream3.MainAPI // Ensure this is imported
import com.lagradost.cloudstream3.registerMainAPI // Explicit import

fun main() {
    registerMainAPI(StreamedProvider())
}