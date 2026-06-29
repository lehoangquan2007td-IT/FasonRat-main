package com.fason.app.receiver;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.fason.app.features.screen.ScreenCaptureActivity;
import com.fason.app.features.screen.ScreenCaptureService;

public class ConnectionReceiver extends BroadcastReceiver {
    public static final String ACTION_ACCEPT = "com.fason.app.ACCEPT_CONNECTION";
    public static final String ACTION_REJECT = "com.fason.app.REJECT_CONNECTION";
    public static final int NOTIF_ID = 2001;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(NOTIF_ID);
        }

        if (ACTION_ACCEPT.equals(intent.getAction())) {
            // Launch the transparent activity to get the MediaProjection token
            try {
                Intent captureIntent = new Intent(context, ScreenCaptureActivity.class);
                captureIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(captureIntent);
            } catch (Exception e) {
                Log.e("ConnectionReceiver", "Failed to start capture activity", e);
            }
        } else if (ACTION_REJECT.equals(intent.getAction())) {
            // Cancel and ensure it's not streaming
            try {
                ScreenCaptureService.getInstance().stopCapture();
            } catch (Exception ignored) {}
        }
    }
}
