package com.example.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.R
import java.net.URI

class ScreenCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var screenEncoder: ScreenEncoder? = null
    private var webSocketClient: RemoteWebSocketClient? = null
    private var actionController: RemoteActionController? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "START") {
            val resultCode = intent.getIntExtra("RESULT_CODE", 0)
            val data = intent.getParcelableExtra<Intent>("DATA")
            if (resultCode != 0 && data != null) {
                startStreaming(resultCode, data)
            }
        } else if (intent?.action == "STOP") {
            stopStreaming()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startStreaming(resultCode: Int, data: Intent) {
        val config = RemoteConfig()
        actionController = RemoteActionController()
        
        webSocketClient = RemoteWebSocketClient(URI(config.relayServerUrl)) { message ->
            actionController?.handleAction(message)
        }
        webSocketClient?.connect()

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        mediaProjection?.let { projection ->
            screenEncoder = ScreenEncoder(config, projection) { frameData ->
                webSocketClient?.sendVideoFrame(frameData)
            }
            screenEncoder?.start()
        }
    }

    private fun stopStreaming() {
        screenEncoder?.stop()
        mediaProjection?.stop()
        
        try {
            webSocketClient?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "ScreenCaptureChannel",
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = "STOP"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "ScreenCaptureChannel")
            .setContentTitle("RemoteStream Active")
            .setContentText("Streaming screen to Web Panel...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .addAction(0, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }
}
