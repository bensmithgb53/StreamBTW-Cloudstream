package ben.smith53.proxy

enum class HttpStatus(val code: Int, val message: String) {
    OK(200, "OK"),
    BAD_REQUEST(400, "Bad Request"),
    INTERNAL_SERVER_ERROR(500, "Internal Server Error");

    fun getResponseLine(): String {
        return "HTTP/1.1 $code $message"
    }
}
