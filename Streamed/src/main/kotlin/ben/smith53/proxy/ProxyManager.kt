package ben.smith53.proxy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class ProxyManager(private val context: Context) {
    
    private var proxyService: ProxyService? = null
    private var isServiceBound = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d("ProxyManager", "onServiceConnected called")
            try {
                val binder = service as ProxyService.LocalBinder
                proxyService = binder.getService()
                isServiceBound = true
                Log.d("ProxyManager", "Proxy service connected successfully")
                Log.d("ProxyManager", "ProxyService instance: $proxyService")
            } catch (e: Exception) {
                Log.e("ProxyManager", "Error in onServiceConnected", e)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d("ProxyManager", "onServiceDisconnected called")
            proxyService = null
            isServiceBound = false
            Log.d("ProxyManager", "Proxy service disconnected")
        }
    }
    
    suspend fun startProxy(): String? = suspendCancellableCoroutine { continuation ->
        try {
            Log.d("ProxyManager", "Starting proxy...")
            val intent = Intent(context, ProxyService::class.java)
            
            // Start service (like StreamBrowser)
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                    Log.d("ProxyManager", "Started foreground service")
                } else {
                    context.startService(intent)
                    Log.d("ProxyManager", "Started regular service")
                }
            } catch (e: SecurityException) {
                Log.e("ProxyManager", "Security exception starting service: ${e.message}")
                continuation.resume(null)
                return@suspendCancellableCoroutine
            } catch (e: Exception) {
                Log.e("ProxyManager", "Failed to start service", e)
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            
            // Bind to service
            val bound = context.bindService(
                intent,
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )

            Log.d("ProxyManager", "Service binding result: $bound")

            if (bound) {
                Log.d("ProxyManager", "Service binding successful, waiting for connection...")
                // Wait for service to be ready (simpler approach like StreamBrowser)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    Log.d("ProxyManager", "Checking proxy server status...")
                    Log.d("ProxyManager", "isServiceBound: $isServiceBound")
                    Log.d("ProxyManager", "proxyService: $proxyService")
                    
                    val proxyServer = proxyService?.getProxyServer()
                    Log.d("ProxyManager", "proxyServer from service: $proxyServer")
                    
                    if (proxyServer != null) {
                        val isStarted = proxyServer.isStarted()
                        Log.d("ProxyManager", "proxyServer.isStarted(): $isStarted")
                        
                        if (isStarted) {
                            val address = proxyServer.getHttpAddress()
                            Log.d("ProxyManager", "Proxy started at: $address")
                            continuation.resume(address)
                        } else {
                            Log.e("ProxyManager", "Proxy server not started yet")
                            continuation.resume(null)
                        }
                    } else {
                        Log.e("ProxyManager", "Failed to get proxy server from service")
                        continuation.resume(null)
                    }
                }, 2000) // Simpler timing like StreamBrowser
            } else {
                Log.e("ProxyManager", "Failed to bind to proxy service")
                continuation.resume(null)
            }
        } catch (e: Exception) {
            Log.e("ProxyManager", "Error starting proxy", e)
            continuation.resume(null)
        }
    }
    
    fun convertToProxyUrl(url: String, headers: Map<String, String>): String? {
        return proxyService?.getProxyServer()?.convertToProxyUrl(url, headers)
    }
    
    fun stopProxy() {
        try {
            if (isServiceBound) {
                context.unbindService(serviceConnection)
                isServiceBound = false
            }
            
            val intent = Intent(context, ProxyService::class.java)
            context.stopService(intent)
            
            Log.d("ProxyManager", "Proxy stopped")
        } catch (e: Exception) {
            Log.e("ProxyManager", "Error stopping proxy", e)
        }
    }
    
    fun isProxyRunning(): Boolean {
        val isRunning = isServiceBound && proxyService?.getProxyServer()?.isStarted() == true
        Log.d("ProxyManager", "isProxyRunning: $isRunning (bound: $isServiceBound, server: ${proxyService?.getProxyServer() != null}, started: ${proxyService?.getProxyServer()?.isStarted()})")
        return isRunning
    }
    
    fun getProxyAddress(): String? {
        return proxyService?.getProxyServer()?.getHttpAddress()
    }
}
