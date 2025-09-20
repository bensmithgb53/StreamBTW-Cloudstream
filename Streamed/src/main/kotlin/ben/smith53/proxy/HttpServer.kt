package ben.smith53.proxy

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

abstract class HttpServer(private val port: Int) {
    
    private val TIMEOUT_CLIENT_CONNECT = 15000
    private val TIMEOUT_CLIENT_FULL = 25000
    
    private var serverSocket: ServerSocket? = null
    private var startListener: () -> Unit = {}
    private var updateListener: (() -> Unit)? = null
    private var stopListener: () -> Unit = {}
    
    private var idCounter = 0
    
    fun getPort(): Int = port
    
    fun isStarted(): Boolean = serverSocket != null
    
    fun start() {
        if (isStarted()) {
            startListener()
            return
        }
        Log.d("HttpServer", "starting server at port $port")
        CoroutineScope(Dispatchers.IO).launch {
            handleServer()
        }
    }
    
    fun stop() {
        if (!isStarted()) return
        Log.d("HttpServer", "stopping server")
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.e("HttpServer", "stop()", e)
        }
    }
    
    private suspend fun handleServer() {
        try {
            ServerSocket(port).use { server ->
                serverSocket = server
                startListener()
                
                while (true) {
                    val client = server.accept()
                    client.soTimeout = TIMEOUT_CLIENT_CONNECT
                    
                    val id = idCounter++
                    
                    CoroutineScope(Dispatchers.IO).launch {
                        withTimeout(TIMEOUT_CLIENT_FULL.toLong()) {
                            handleClient(client, id)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HttpServer", "handleServer()", e)
            serverSocket = null
            stopListener()
        }
    }
    
    private suspend fun handleClient(client: Socket, id: Int) {
        try {
            client.use { socket ->
                socket.getInputStream().use { inputStream ->
                    socket.getOutputStream().use { outputStream ->
                        val startTime1 = System.currentTimeMillis()
                        
                        val firstLine = try {
                            readRequestFirstLine(inputStream)
                        } catch (e: Exception) {
                            Log.e("HttpServer", "read first line ($id)", e)
                            return
                        }
                        
                        Log.d("HttpServer", "$id read first line took ${System.currentTimeMillis() - startTime1}")
                        
                        val requestLine = firstLine.split(" ", limit = 3)
                        val path = requestLine[1].substring(1)
                        
                        val response = handleRequest(path, id)
                        if (response == null) {
                            Log.e("HttpServer", "response = null")
                            return
                        }
                        
                        val startTime2 = System.currentTimeMillis()
                        writeResponse(outputStream, response)
                        Log.d("HttpServer", "$id writing took ${System.currentTimeMillis() - startTime2}")
                        
                        Log.d("HttpServer", "$id done, whole thing took ${System.currentTimeMillis() - startTime1}")
                        
                        updateListener?.invoke()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HttpServer", "handleClient ($id)", e)
        }
    }
    
    abstract suspend fun handleRequest(path: String, id: Int): HttpResponse?
    
    private fun readRequestFirstLine(inputStream: InputStream): String {
        val reader = BufferedReader(InputStreamReader(inputStream))
        
        val firstLine = reader.readLine() ?: throw Exception("request is empty")
        
        // Need to read the whole request for some reason
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            if (line!!.isEmpty()) break
        }
        if (inputStream.available() > 0) throw Exception("request contains body")
        
        return firstLine
    }
    
    private fun writeResponse(outputStream: OutputStream, response: HttpResponse) {
        if (response.length != null) {
            response.headers.clear() // todo
            response.headers["Content-Length"] = response.length.toString()
        }
        
        response.headers["Access-Control-Allow-Origin"] = "*"
        
        val headersBuilder = StringBuilder()
        headersBuilder.append(response.status.getResponseLine()).append("\r\n")
        for ((key, value) in response.headers) {
            headersBuilder.append("$key: $value\r\n")
        }
        headersBuilder.append("\r\n")
        val headers = headersBuilder.toString().toByteArray()
        
        outputStream.write(headers)
        copyTo(response.content, outputStream)
        outputStream.flush()
    }
    
    private fun copyTo(inputStream: InputStream, outputStream: OutputStream) {
        val buffer = ByteArray(8192)
        var read: Int
        while (inputStream.read(buffer, 0, 8192).also { read = it } >= 0) {
            outputStream.write(buffer, 0, read)
        }
    }
    
    fun setStartListener(listener: () -> Unit) {
        startListener = listener
    }
    
    fun setUpdateListener(listener: (() -> Unit)?) {
        updateListener = listener
    }
    
    fun setStopListener(listener: () -> Unit) {
        stopListener = listener
    }
}
