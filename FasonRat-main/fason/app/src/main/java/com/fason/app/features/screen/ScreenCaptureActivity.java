package com.fason.app.features.screen;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/**
 * Transparent activity that requests MediaProjection permission.
 * Launches system dialog, captures result, and passes it to ScreenCaptureService.
 * Auto-finishes immediately after receiving the result.
 */
public class ScreenCaptureActivity extends Activity {

    private static final int REQUEST_CODE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            MediaProjectionManager mpm = (MediaProjectionManager)
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm != null) {
                startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CODE);
            } else {
                finish();
            }
        } catch (Exception e) {
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                ScreenCaptureService.getInstance().startCapture(resultCode, data);
            }
        }

        finish();
    }
}
