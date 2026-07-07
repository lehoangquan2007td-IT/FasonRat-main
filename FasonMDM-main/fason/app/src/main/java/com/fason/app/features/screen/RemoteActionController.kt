package com.fason.app.features.screen

import android.util.Log
import org.json.JSONObject

class RemoteActionController {

    fun handleAction(message: String) {
        try {
            val json = JSONObject(message)
            when (json.optString("action")) {
                "tap" -> {
                    val x = json.optDouble("x").toFloat()
                    val y = json.optDouble("y").toFloat()
                    RemoteControlService.instance?.performTap(x, y)
                }
                "swipe" -> {
                    val startX = json.optDouble("startX").toFloat()
                    val startY = json.optDouble("startY").toFloat()
                    val endX = json.optDouble("endX").toFloat()
                    val endY = json.optDouble("endY").toFloat()
                    val duration = json.optLong("duration", 300)
                    RemoteControlService.instance?.performSwipe(startX, startY, endX, endY, duration)
                }
                else -> {
                    Log.w("RemoteActionController", "Unknown action type: ${json.optString("action")}")
                }
            }
        } catch (e: Exception) {
            Log.e("RemoteActionController", "Error parsing remote action", e)
        }
    }
}
