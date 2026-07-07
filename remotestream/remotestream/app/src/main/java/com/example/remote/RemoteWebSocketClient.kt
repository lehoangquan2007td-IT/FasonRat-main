package com.example.remote

import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

class RemoteWebSocketClient(
    serverUri: URI,
    private val onMessageReceived: (String) -> Unit
) : WebSocketClient(serverUri) {

    override fun onOpen(handshakedata: ServerHandshake?) {
        Log.d("RemoteWebSocketClient", "Connected to relay server: $uri")
    }

    override fun onMessage(message: String?) {
        if (message != null) {
            onMessageReceived(message)
        }
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        Log.d("RemoteWebSocketClient", "Closed connection to relay server. Code: $code, Reason: $reason")
    }

    override fun onError(ex: Exception?) {
        Log.e("RemoteWebSocketClient", "Error in WebSocket client", ex)
    }

    fun sendVideoFrame(frameData: ByteArray) {
        if (isOpen) {
            send(frameData)
        }
    }
}
