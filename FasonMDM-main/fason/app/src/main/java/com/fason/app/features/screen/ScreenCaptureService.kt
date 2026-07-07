package com.fason.app.features.screen

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
import com.fason.app.R
import com.fason.app.core.network.SocketClient
import com.fason.app.core.Protocol
import org.json.JSONObject
import android.util.Base64

class ScreenCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var screenEncoder: ScreenEncoder? = null
    private var actionController: RemoteActionController? = null

    companion object {
        var isStreaming = false
        var screenWidth = 0
        var screenHeight = 0
    }

    private val socketListener = io.socket.emitter.Emitter.Listener { args ->
        if (args.isNotEmpty() && args[0] is String) {
            actionController?.handleAction(args[0] as String)
        } else if (args.isNotEmpty() && args[0] is JSONObject) {
            actionController?.handleAction((args[0] as JSONObject).toString())
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1, 
                createNotification(), 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(1, createNotification())
        }
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
        actionController = RemoteActionController()
        
        val socket = SocketClient.getInstance().socket
        socket?.on(Protocol.SCREEN_CTRL, socketListener)

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        mediaProjection?.let { projection ->
            screenEncoder = ScreenEncoder(projection) { frameData, width, height ->
                screenWidth = width
                screenHeight = height
                socket?.let { s ->
                    val json = JSONObject()
                    json.put("type", "frame")
                    json.put("frame", Base64.encodeToString(frameData, Base64.NO_WRAP))
                    json.put("screenWidth", width)
                    json.put("screenHeight", height)
                    s.emit(Protocol.SCREEN, json)
                }
            }
            screenEncoder?.start()
            
            socket?.let { s ->
                val statusJson = JSONObject()
                statusJson.put("streaming", true)
                s.emit(Protocol.SCREEN_CTRL, statusJson)
            }
            isStreaming = true
        }
    }

    private fun stopStreaming() {
        screenEncoder?.stop()
        mediaProjection?.stop()
        
        SocketClient.getInstance().socket?.let { s ->
            val statusJson = JSONObject()
            statusJson.put("streaming", false)
            s.emit(Protocol.SCREEN_CTRL, statusJson)
            s.off(Protocol.SCREEN_CTRL, socketListener)
        }
        isStreaming = false
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
