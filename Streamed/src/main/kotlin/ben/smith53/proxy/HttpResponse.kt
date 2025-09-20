package ben.smith53.proxy

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.HashMap

class HttpResponse(
    val status: HttpStatus,
    val content: InputStream,
    val headers: MutableMap<String, String> = HashMap()
) {
    var length: Int? = null

    constructor(status: HttpStatus, content: ByteArray, headers: MutableMap<String, String> = HashMap()) : this(
        status,
        ByteArrayInputStream(content),
        headers
    ) {
        this.length = content.size
    }

    constructor(status: HttpStatus, content: String, headers: MutableMap<String, String> = HashMap()) : this(
        status,
        content.toByteArray(),
        headers
    )
}
