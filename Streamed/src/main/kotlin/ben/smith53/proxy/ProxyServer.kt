package ben.smith53.proxy

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.util.Pair
import com.lagradost.cloudstream3.app
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.*

class ProxyServer(private val context: Context, port: Int) : HttpServer(port) {
    
    private val ip: String = loadLocalIp()
    
    private fun loadLocalIp(): String {
        val ips = mutableListOf<String>()
        
        try {
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            if (networkInterfaces != null) {
                while (networkInterfaces.hasMoreElements()) {
                    try {
                        val networkInterface = networkInterfaces.nextElement()
                        val inetAddresses = networkInterface.inetAddresses
                        while (inetAddresses.hasMoreElements()) {
                            try {
                                val address = inetAddresses.nextElement()
                                if (address.isSiteLocalAddress) {
                                    address.hostAddress?.let { ips.add(it) }
                                }
                            } catch (e: Exception) {
                                Log.w("ProxyServer", "Error processing address: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("ProxyServer", "Error processing network interface: ${e.message}")
                    }
                }
            } else {
                Log.w("ProxyServer", "NetworkInterface.getNetworkInterfaces() returned null")
            }
        } catch (e: SocketException) {
            Log.e("ProxyServer", "NetworkInterface.getNetworkInterfaces", e)
        } catch (e: Exception) {
            Log.e("ProxyServer", "Unexpected error getting network interfaces", e)
        }
        
        Log.d("ProxyServer", "local ips: ${ips.joinToString(", ")}")
        
        // https://www.rfc-editor.org/rfc/rfc1918
        // for some reason 10. ips don't work, haven't tested 172.
        val localIp = ips.find { it.startsWith("192.168.") }
        if (localIp == null) {
            Log.w("ProxyServer", "localIp == null: using localhost address (won't work)")
            return "127.0.0.1"
        }
        return localIp
    }
    
    fun getHttpAddress(): String {
        return "http://$ip:${getPort()}/"
    }
    
    override suspend fun handleRequest(path: String, id: Int): HttpResponse? {
        if (path.isEmpty()) {
            return HttpResponse(HttpStatus.OK, "proxy server is running", mutableMapOf())
        }
        
        val fileName = getFileNameFromUrl(getHttpAddress() + path)
        val query = path.replace(fileName, "")
        
        val decodedQuery = getDecodedQuery(query)
        if (decodedQuery == null) {
            Log.e("ProxyServer", "$id query decode error")
            return null
        }
        
        val startTime = System.currentTimeMillis()
        
        val remoteUrl = decodedQuery.first
        val remoteRequestHeaders = decodedQuery.second
        Log.d("ProxyServer", "$id remote url is $remoteUrl")
        
        val responseHeaders = mutableMapOf<String, String>()
        val responseContent: InputStream
        
        if (remoteUrl.startsWith("content://")) { // local file
            responseContent = context.contentResolver.openInputStream(Uri.parse(remoteUrl))!!
            Log.d("ProxyServer", "$id file read took ${System.currentTimeMillis() - startTime}")
        } else { // remote url
            val connection = connectToUrl(remoteUrl, remoteRequestHeaders)
            
            if (fileName.contains(".m3u8")) {
                val hostName = getAddressWithoutFileName(remoteUrl)
                val playlist = convertHlsPlayList(connection.inputStream, remoteRequestHeaders, hostName)
                responseContent = ByteArrayInputStream(playlist.toByteArray())
            } else {
                responseContent = connection.inputStream
            }
            
            Log.d("ProxyServer", "$id remote read took ${System.currentTimeMillis() - startTime}")
        }
        
        return HttpResponse(HttpStatus.OK, responseContent, responseHeaders)
    }
    
    fun convertToProxyUrl(url: String, headers: Map<String, String>): String {
        return getHttpAddress() + getFileNameFromUrl(url) + getEncodedQuery(url, headers)
    }
    
    private fun convertHlsPlayList(
        inputStream: InputStream,
        requestHeaders: Map<String, String>,
        host: String
    ): String {
        inputStream.use { stream ->
            val reader = BufferedReader(InputStreamReader(stream))
            val stringBuilder = StringBuilder()
            
            var line = reader.readLine()
            if (line == null || line != "#EXTM3U") {
                throw Exception("hls playlist not valid")
            }
            stringBuilder.append(line).append("\r\n")
            
            while (reader.readLine().also { line = it } != null) {
                if (line.isNotEmpty() && !line.startsWith("#")) {
                    val remoteUrl = if (line.startsWith("http")) {
                        line
                    } else {
                        host + line
                    }
                    val query = getEncodedQuery(remoteUrl, requestHeaders)
                    val fileName = getFileNameFromUrl(remoteUrl)
                    val proxyHost = fileName + query
                    stringBuilder.append(proxyHost).append("\r\n")
                } else {
                    stringBuilder.append(line).append("\r\n")
                }
            }
            
            return stringBuilder.toString()
        }
    }
    
    private fun getEncodedQuery(url: String, headers: Map<String, String>): String {
        return try {
            val castStreamInfoJson = JSONObject()
            castStreamInfoJson.put("u", url)
            castStreamInfoJson.put("h", mapToJson(headers))
            val queryEncoded = encodeBase64(castStreamInfoJson.toString())
            "?q=$queryEncoded"
        } catch (e: Exception) {
            Log.e("ProxyServer", "getEncodedQuery()", e)
            ""
        }
    }
    
    private fun getDecodedQuery(query: String): Pair<String, Map<String, String>>? {
        return try {
            val queryDecoded = decodeBase64(query.replace("?q=", ""))
            val castStreamInfoJson = JSONObject(queryDecoded)
            val url = castStreamInfoJson.getString("u")
            val headers = jsonToMap(castStreamInfoJson.getJSONObject("h"))
            Pair.create(url, headers)
        } catch (e: Exception) {
            Log.e("ProxyServer", "getDecodedQuery()", e)
            null
        }
    }
    
    private fun encodeBase64(input: String): String {
        return String(
            Base64.encode(input.toByteArray(StandardCharsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP),
            StandardCharsets.UTF_8
        )
    }
    
    private fun decodeBase64(input: String): String {
        return String(
            Base64.decode(input.toByteArray(StandardCharsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP),
            StandardCharsets.UTF_8
        )
    }
    
    private fun mapToJson(map: Map<String, String>): JSONObject {
        val json = JSONObject()
        for ((key, value) in map) {
            json.put(key, value)
        }
        return json
    }
    
    private fun jsonToMap(json: JSONObject): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = json.getString(key)
        }
        return map
    }
    
    private fun connectToUrl(url: String, headers: Map<String, String>): HttpURLConnection {
        val connection = java.net.URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 30000
        connection.readTimeout = 30000
        
        for ((key, value) in headers) {
            connection.setRequestProperty(key, value)
        }
        
        connection.connect()
        return connection
    }
    
    private fun getFileNameFromUrl(url: String): String {
        return url.substringAfterLast("/")
    }
    
    private fun getAddressWithoutFileName(url: String): String {
        val lastSlashIndex = url.lastIndexOf("/")
        return if (lastSlashIndex != -1) {
            url.substring(0, lastSlashIndex + 1)
        } else {
            url + "/"
        }
    }
}
