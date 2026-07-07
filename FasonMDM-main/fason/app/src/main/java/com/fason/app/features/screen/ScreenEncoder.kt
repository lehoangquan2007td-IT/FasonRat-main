package com.fason.app.features.screen

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log

class ScreenEncoder(
    private val mediaProjection: MediaProjection,
    private val onFrameEncoded: (ByteArray, Int, Int) -> Unit
) {
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var isEncoding = false
    private var encoderThread: Thread? = null

    private val videoWidth = 720
    private val videoHeight = 1280
    private val videoDpi = 320
    private val bitRate = 1000000
    private val frameRate = 30
    private val iFrameInterval = 1

    fun start() {
        if (isEncoding) return

        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                videoWidth,
                videoHeight
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = mediaCodec?.createInputSurface()
            mediaCodec?.start()

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                videoWidth,
                videoHeight,
                videoDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
            )

            isEncoding = true
            startEncoderThread()

        } catch (e: Exception) {
            Log.e("ScreenEncoder", "Error starting encoder", e)
            stop()
        }
    }

    private fun startEncoderThread() {
        encoderThread = Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            while (isEncoding) {
                try {
                    val outputBufferId = mediaCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
                    if (outputBufferId >= 0) {
                        val outputBuffer = mediaCodec?.getOutputBuffer(outputBufferId)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val data = ByteArray(bufferInfo.size)
                            outputBuffer.get(data)
                            
                            onFrameEncoded(data, videoWidth, videoHeight)
                        }
                        mediaCodec?.releaseOutputBuffer(outputBufferId, false)
                    }
                } catch (e: Exception) {
                    if (isEncoding) {
                        Log.e("ScreenEncoder", "Error in encoder thread", e)
                    }
                }
            }
        }
        encoderThread?.start()
    }

    fun stop() {
        isEncoding = false
        encoderThread?.interrupt()
        encoderThread = null

        try {
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (e: Exception) {
            Log.e("ScreenEncoder", "Error releasing virtual display", e)
        }

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
        } catch (e: Exception) {
            Log.e("ScreenEncoder", "Error releasing media codec", e)
        }
    }
}
