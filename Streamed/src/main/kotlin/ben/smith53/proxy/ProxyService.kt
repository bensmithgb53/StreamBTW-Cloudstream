package ben.smith53.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log

class ProxyService : Service() {
    
    private val proxyPort = 1111
    private var proxyServer: ProxyServer? = null
    
    fun getProxyServer(): ProxyServer? = proxyServer
    
    override fun onCreate() {
        super.onCreate()
        Log.d("ProxyService", "onCreate")
        
        try {
            proxyServer = ProxyServer(applicationContext, proxyPort)
            proxyServer?.start()
            Log.d("ProxyService", "Proxy server start requested on port $proxyPort")
        } catch (e: Exception) {
            Log.e("ProxyService", "Error creating proxy server", e)
        }
        
        initNotificationManager()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("ProxyService", "onStartCommand")
        
        // Create notification if not already created
        if (notification == null) {
            notification = createNotification()
        }
        
        // Start as foreground service (like StreamBrowser)
        try {
            startForeground(NOTIFICATION_ID, notification)
            Log.d("ProxyService", "Started as foreground service")
        } catch (e: Exception) {
            Log.e("ProxyService", "Failed to start foreground service", e)
        }
        
        return START_STICKY
    }
    
    
    override fun onDestroy() {
        Log.d("ProxyService", "onDestroy")
        proxyServer?.stop()
        super.onDestroy()
    }
    
    // Binder
    override fun onBind(intent: Intent?): IBinder = binder
    
    private val binder = LocalBinder()
    
    inner class LocalBinder : Binder() {
        fun getService(): ProxyService = this@ProxyService
    }
    
    // Notification
    private val CHANNEL_ID = "PROXY_SERVER"
    private val CHANNEL_NAME = "Proxy Server"
    private val NOTIFICATION_ID = 11
    
    private var notification: Notification? = null
    
    private fun initNotificationManager() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val httpAddress = proxyServer?.getHttpAddress() ?: "Starting..."
        val notificationText = "Proxy running @ $httpAddress"
        
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("StreamBTW Proxy")
                    .setContentText(notificationText)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setOngoing(true)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setContentTitle("StreamBTW Proxy")
                    .setContentText(notificationText)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setOngoing(true)
                    .build()
            }
        } catch (e: Exception) {
            Log.e("ProxyService", "Error creating notification, using fallback", e)
            createFallbackNotification() ?: createLastResortNotification() ?: throw IllegalStateException("Cannot create any notification")
        }
    }
    
    private fun createFallbackNotification(): Notification? {
        return try {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("StreamBTW Proxy")
                .setContentText("Proxy service running")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
        } catch (e: Exception) {
            Log.e("ProxyService", "Error creating fallback notification", e)
            createLastResortNotification()
        }
    }
    
    private fun createLastResortNotification(): Notification? {
        return try {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Proxy")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
        } catch (e: Exception) {
            Log.e("ProxyService", "Error creating last resort notification", e)
            null
        }
    }
    
    private fun createBasicNotification(): Notification {
        @Suppress("DEPRECATION")
        return Notification.Builder(this)
            .setContentTitle("StreamBTW")
            .setContentText("Service")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }
    
    private fun createEmergencyNotification(): Notification {
        @Suppress("DEPRECATION")
        return Notification.Builder(this)
            .setContentTitle("Proxy")
            .setContentText("Active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }
}
