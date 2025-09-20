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
            val binder = service as ProxyService.LocalBinder
            proxyService = binder.getService()
            isServiceBound = true
            Log.d("ProxyManager", "Proxy service connected")
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            proxyService = null
            isServiceBound = false
            Log.d("ProxyManager", "Proxy service disconnected")
        }
    }
    
    suspend fun startProxy(): String? = suspendCancellableCoroutine { continuation ->
        try {
            val intent = Intent(context, ProxyService::class.java)
            
            // Try to start the service
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: SecurityException) {
                Log.e("ProxyManager", "Security exception starting service: ${e.message}")
                continuation.resume(null)
                return@suspendCancellableCoroutine
            } catch (e: Exception) {
                Log.w("ProxyManager", "startForegroundService failed, trying startService: ${e.message}")
                try {
                    context.startService(intent)
                } catch (e2: Exception) {
                    Log.e("ProxyManager", "Both startForegroundService and startService failed", e2)
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }
            }
            
            val bound = context.bindService(
                intent,
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
            
            if (bound) {
                // Wait a bit for service to start
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val proxyServer = proxyService?.getProxyServer()
                    if (proxyServer != null) {
                        val address = proxyServer.getHttpAddress()
                        Log.d("ProxyManager", "Proxy started at: $address")
                        continuation.resume(address)
                    } else {
                        Log.e("ProxyManager", "Failed to get proxy server")
                        continuation.resume(null)
                    }
                }, 1000)
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
        return isServiceBound && proxyService?.getProxyServer() != null
    }
}
