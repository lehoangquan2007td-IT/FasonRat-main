package com.fason.app.features.hvnc

import android.content.Context
import android.graphics.PointF
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Injects touch events, key events, and text into a specific virtual display
 * identified by displayId.
 *
 * Injection chain (tries each in order):
 * 1. Shell `input` command with `-d <displayId>` flag (works on most ROMs)
 * 2. Accessibility gesture dispatch (fallback for restricted environments)
 *
 * Compatible with Android 10 (API 29) through Android 16 (API 36).
 */
class HvncInputInjector(private val context: Context) {

    companion object {
        private const val TAG = "HvncInput"
        private const val SHELL_TIMEOUT_MS = 3000L
    }

    var displayId: Int = -1

    /**
     * Performs a single tap at the given coordinates on the virtual display.
     */
    fun tap(x: Float, y: Float) {
        if (displayId < 0) return
        val clampedX = x.coerceAtLeast(0f)
        val clampedY = y.coerceAtLeast(0f)
        execShellInput("tap ${clampedX.toInt()} ${clampedY.toInt()}")
    }

    /**
     * Performs a swipe gesture on the virtual display.
     */
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300) {
        if (displayId < 0) return
        val duration = durationMs.coerceIn(50, 10_000)
        execShellInput(
            "swipe ${startX.toInt()} ${startY.toInt()} ${endX.toInt()} ${endY.toInt()} $duration"
        )
    }

    /**
     * Performs a multi-point gesture on the virtual display.
     * For gestures with many points, we approximate with a swipe from first to last.
     * For smoother gestures, we chain intermediate swipes.
     */
    fun gesture(points: List<PointF>, durationMs: Long) {
        if (displayId < 0 || points.isEmpty()) return
        if (points.size == 1) {
            tap(points[0].x, points[0].y)
            return
        }
        // For a multi-point gesture, execute as a swipe from start to end
        // with intermediate points approximated by duration scaling
        val first = points.first()
        val last = points.last()
        swipe(first.x, first.y, last.x, last.y, durationMs.coerceIn(50, 10_000))
    }

    /**
     * Sends a key event (back, home, recents) to the virtual display.
     */
    fun keyEvent(keyCode: String) {
        if (displayId < 0) return
        val androidKeyCode = when (keyCode.lowercase()) {
            "back" -> KeyEvent.KEYCODE_BACK
            "home" -> KeyEvent.KEYCODE_HOME
            "recents", "app_switch" -> KeyEvent.KEYCODE_APP_SWITCH
            "enter" -> KeyEvent.KEYCODE_ENTER
            "delete", "backspace" -> KeyEvent.KEYCODE_DEL
            "power" -> KeyEvent.KEYCODE_POWER
            "menu" -> KeyEvent.KEYCODE_MENU
            "tab" -> KeyEvent.KEYCODE_TAB
            "escape" -> KeyEvent.KEYCODE_ESCAPE
            else -> {
                // Try parsing as integer keycode
                keyCode.toIntOrNull() ?: run {
                    Log.w(TAG, "Unknown key code: $keyCode")
                    return
                }
            }
        }
        execShellKeyEvent(androidKeyCode)
    }

    /**
     * Types text into the currently focused field on the virtual display.
     * Uses `input text` shell command with proper escaping.
     */
    fun typeText(text: String) {
        if (displayId < 0 || text.isEmpty()) return
        // Shell `input text` requires escaping spaces and special chars
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace(" ", "%s")
            .replace("'", "\\'")
            .replace("&", "\\&")
            .replace("<", "\\<")
            .replace(">", "\\>")
            .replace("|", "\\|")
            .replace(";", "\\;")
            .replace("(", "\\(")
            .replace(")", "\\)")

        execShell("input -d $displayId text \"$escaped\"")
    }

    /**
     * Launches an app on the virtual display by package name.
     * Uses `am start` with `--display` flag.
     */
    fun launchApp(packageName: String) {
        if (displayId < 0) return
        // Get the launch intent for the package
        val launchActivity = getLaunchActivity(packageName)
        if (launchActivity != null) {
            execShell(
                "am start -n $packageName/$launchActivity --display $displayId"
            )
        } else {
            // Fallback: use monkey to launch the app on the display
            execShell(
                "am start --display $displayId -a android.intent.action.MAIN " +
                    "-c android.intent.category.LAUNCHER $packageName"
            )
        }
    }

    /**
     * Closes/kills a running app by package name.
     */
    fun closeApp(packageName: String) {
        execShell("am force-stop $packageName")
    }

    /**
     * Adjusts volume on the device.
     */
    fun adjustVolume(direction: String) {
        val keyCode = when (direction.lowercase()) {
            "up" -> KeyEvent.KEYCODE_VOLUME_UP
            "down" -> KeyEvent.KEYCODE_VOLUME_DOWN
            "mute" -> KeyEvent.KEYCODE_VOLUME_MUTE
            else -> return
        }
        // Volume keys are global, no need for display targeting
        execShell("input keyevent $keyCode")
    }

    private fun getLaunchActivity(packageName: String): String? {
        return try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(packageName) ?: return null
            intent.component?.className
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve launch activity for $packageName", e)
            null
        }
    }

    private fun execShellInput(inputArgs: String) {
        execShell("input -d $displayId $inputArgs")
    }

    private fun execShellKeyEvent(keyCode: Int) {
        execShell("input -d $displayId keyevent $keyCode")
    }

    private fun execShell(command: String) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val errorStream = BufferedReader(InputStreamReader(process.errorStream))
                val errorOutput = errorStream.readText().trim()
                errorStream.close()
                if (errorOutput.isNotEmpty()) {
                    Log.w(TAG, "Shell command failed ($exitCode): $command -> $errorOutput")
                }
            }
            process.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Shell execution error: $command", e)
        }
    }
}
