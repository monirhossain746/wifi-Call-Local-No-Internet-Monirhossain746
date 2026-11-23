package com.android.wificalllocal.webrtc;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.android.wificalllocal.network.NetworkUtils;
import com.android.wificalllocal.network.PeerConnectionManager;
import com.android.wificalllocal.network.model.NetPacket;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Native Android WebRTC engine configuring JavaAudioDeviceModule, AudioManager communication
 * routing, Camera2+Camera1 video capture, per-peer ICE buffering, glare protection, ICE restart,
 * and complete resource disposal for 1-to-1 and up to 6-participant mesh calls.
 */
public class WebRtcCallManager {

    private static final long ICE_RESTART_DELAY_MS = 2_500L;
    private static final AtomicBoolean FACTORY_INITIALIZED = new AtomicBoolean(false);

    public interface CallEvents {
        void onRemoteVideoTrack(String peerId, VideoTrack track);
        void onRemoteAudioTrack(String peerId, boolean available);
        void onPeerConnecting(String peerId);
        void onPeerConnected(String peerId);
        void onPeerReconnecting(String peerId);
        void onPeerFailed(String peerId, String reason);
        void onPeerDisconnected(String peerId);
        void onMediaError(String message);
    }

    private static final class PeerSession {
        final String peerId;
        PeerConnection peerConnection;
        final List<IceCandidate> pendingRemoteIce = new ArrayList<>();
        boolean remoteDescriptionSet = false;
        boolean makingOffer = false;
        boolean isConnected = false;
        AudioTrack remoteAudioTrack;
        VideoTrack remoteVideoTrack;
        Runnable iceRestartRunnable;

        PeerSession(String peerId) {
            this.peerId = peerId;
        }
    }

    private final Context context;
    private final PeerConnectionManager signaling;
    private final CallAudioRouter audioRouter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final String myDeviceId;

    private EglBase eglBase;
    private JavaAudioDeviceModule audioDeviceModule;
    private PeerConnectionFactory factory;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;
    private VideoSource videoSource;
    private VideoTrack localVideoTrack;
    private CameraVideoCapturer videoCapturer;
    private SurfaceTextureHelper surfaceTextureHelper;
    private SurfaceViewRenderer boundLocalRenderer;

    private final Map<String, PeerSession> peerSessions = new ConcurrentHashMap<>();
    private CallEvents events;
    private String activeCallId;
    private String activeGroupId;
    private String activeCallType;
    private boolean audioMuted = false;
    private boolean videoEnabled = true;
    private volatile boolean isFrontCamera = true;
    private volatile boolean capturerRunning = false;
    private volatile boolean released = false;

    public WebRtcCallManager(@NonNull Context context, @NonNull PeerConnectionManager signaling, @NonNull String myDeviceId) {
        this.context = context.getApplicationContext();
        this.signaling = signaling;
        this.myDeviceId = myDeviceId;
        this.audioRouter = new CallAudioRouter(this.context);
    }

    public CallAudioRouter getAudioRouter() {
        return audioRouter;
    }

    public boolean isFrontCamera() {
        return isFrontCamera;
    }

    public void setGroupContext(String callId, String groupId, String callType) {
        this.activeCallId = callId;
        this.activeGroupId = groupId;
        this.activeCallType = callType;
    }

    public boolean hasPeer(String peerId) {
        if (peerId == null) return false;
        PeerSession s = peerSessions.get(peerId);
        return s != null && s.peerConnection != null;
    }

    public void removePeer(String peerId) {
        if (peerId == null) return;
        PeerSession session = peerSessions.remove(peerId);
        if (session == null) return;
        if (session.iceRestartRunnable != null) {
            mainHandler.removeCallbacks(session.iceRestartRunnable);
            session.iceRestartRunnable = null;
        }
        if (session.peerConnection != null) {
            try {
                session.peerConnection.close();
                session.peerConnection.dispose();
            } catch (Exception ignored) {
            }
            session.peerConnection = null;
        }
    }

    public EglBase.Context getEglContext() {
        if (eglBase == null) eglBase = EglBase.create();
        return eglBase.getEglBaseContext();
    }

    public boolean initEngine(boolean isVideo, @Nullable SurfaceViewRenderer localRenderer, @NonNull CallEvents events) {
        this.events = events;
        this.boundLocalRenderer = localRenderer;
        this.videoEnabled = isVideo;
        this.released = false;

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            notifyMediaError("Microphone permission is required for WebRTC audio");
            return false;
        }

        if (eglBase == null) eglBase = EglBase.create();

        if (FACTORY_INITIALIZED.compareAndSet(false, true)) {
            PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                            .setEnableInternalTracer(false)
                            .createInitializationOptions()
            );
        }

        audioRouter.start(isVideo);

        AudioAttributes commAttrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();

        audioDeviceModule = JavaAudioDeviceModule.builder(context)
                .setAudioAttributes(commAttrs)
                .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
                .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
                .setAudioRecordErrorCallback(new JavaAudioDeviceModule.AudioRecordErrorCallback() {
                    @Override
                    public void onWebRtcAudioRecordInitError(String errorMessage) {
                        notifyMediaError("Microphone init failed: " + errorMessage);
                    }

                    @Override
                    public void onWebRtcAudioRecordStartError(JavaAudioDeviceModule.AudioRecordStartErrorCode errorCode, String errorMessage) {
                        notifyMediaError("Microphone capture failed: " + errorMessage);
                    }

                    @Override
                    public void onWebRtcAudioRecordError(String errorMessage) {
                        notifyMediaError("Microphone error: " + errorMessage);
                    }
                })
                .setAudioTrackErrorCallback(new JavaAudioDeviceModule.AudioTrackErrorCallback() {
                    @Override
                    public void onWebRtcAudioTrackInitError(String errorMessage) {
                        notifyMediaError("Speaker init failed: " + errorMessage);
                    }

                    @Override
                    public void onWebRtcAudioTrackStartError(JavaAudioDeviceModule.AudioTrackStartErrorCode errorCode, String errorMessage) {
                        notifyMediaError("Audio playback failed: " + errorMessage);
                    }

                    @Override
                    public void onWebRtcAudioTrackError(String errorMessage) {
                        notifyMediaError("Audio playback error: " + errorMessage);
                    }
                })
                .createAudioDeviceModule();

        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        DefaultVideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(
                eglBase.getEglBaseContext(), true, true);
        DefaultVideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());

        factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setAudioDeviceModule(audioDeviceModule)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory();

        MediaConstraints audioConstraints = new MediaConstraints();
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googHighpassFilter", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));

        audioSource = factory.createAudioSource(audioConstraints);
        localAudioTrack = factory.createAudioTrack("ARDAMSa0", audioSource);
        localAudioTrack.setEnabled(!audioMuted);

        if (isVideo) {
            boolean hasCamPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED;
            if (!hasCamPerm) {
                this.videoEnabled = false;
                notifyMediaError("Camera permission not granted — audio only");
                return true;
            }
            initVideoPipeline(localRenderer);
        }
        return true;
    }

    private void initVideoPipeline(@Nullable SurfaceViewRenderer localRenderer) {
        videoCapturer = createCameraCapturer();
        if (videoCapturer == null) {
            videoEnabled = false;
            notifyMediaError("No camera available on this device");
            return;
        }
        try {
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
            videoSource = factory.createVideoSource(videoCapturer.isScreencast());
            videoCapturer.initialize(surfaceTextureHelper, context, videoSource.getCapturerObserver());
            videoCapturer.startCapture(640, 480, 24);
            capturerRunning = true;
            localVideoTrack = factory.createVideoTrack("ARDAMSv0", videoSource);
            localVideoTrack.setEnabled(videoEnabled);
            if (localRenderer != null) {
                localRenderer.setMirror(isFrontCamera);
                localVideoTrack.addSink(localRenderer);
            }
        } catch (Exception e) {
            videoEnabled = false;
            capturerRunning = false;
            notifyMediaError("Unable to start camera: " + e.getMessage());
        }
    }

    public void startOutgoingCall(String callId, String peerId, String callType) {
        if (released || peerId == null || peerId.isEmpty()) return;
        this.activeCallId = callId;
        this.activeCallType = callType;
        PeerSession session = getOrCreatePeerSession(peerId);
        if (session == null || session.peerConnection == null) return;

        notifyPeerConnecting(peerId);
        MediaConstraints constraints = createSdpConstraints(callType, false);
        session.makingOffer = true;

        session.peerConnection.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                if (released || session.peerConnection == null) {
                    session.makingOffer = false;
                    return;
                }
                session.peerConnection.setLocalDescription(new SimpleSdpObserver() {
                    @Override
                    public void onSetSuccess() {
                        session.makingOffer = false;
                        NetPacket offer = signaling.buildBasePacket(NetPacket.TYPE_CALL_OFFER);
                        offer.targetId = peerId;
                        offer.callId = callId;
                        offer.groupId = activeGroupId;
                        offer.callType = callType;
                        offer.sdp = sdp.description;
                        signaling.sendCallPacketToPeer(peerId, offer);
                    }

                    @Override
                    public void onSetFailure(String error) {
                        session.makingOffer = false;
                        notifyMediaError("Failed to set local offer: " + error);
                    }
                }, sdp);
            }

            @Override
            public void onCreateFailure(String error) {
                session.makingOffer = false;
                notifyMediaError("Failed to create call offer: " + error);
            }
        }, constraints);
    }

    public void acceptIncomingCall(String callId, String peerId, String callType, String remoteSdp) {
        if (released || peerId == null || peerId.isEmpty() || remoteSdp == null || remoteSdp.isEmpty()) {
            return;
        }
        this.activeCallId = callId;
        this.activeCallType = callType;
        PeerSession session = getOrCreatePeerSession(peerId);
        if (session == null || session.peerConnection == null) return;

        notifyPeerConnecting(peerId);

        boolean offerCollision = session.makingOffer
                || session.peerConnection.signalingState() != PeerConnection.SignalingState.STABLE;
        boolean polite = myDeviceId.compareTo(peerId) > 0;
        if (offerCollision && !polite) {
            return;
        }

        Runnable applyOfferAndAnswer = () -> {
            if (released || session.peerConnection == null) return;
            SessionDescription offerSdp = new SessionDescription(SessionDescription.Type.OFFER, remoteSdp);
            session.peerConnection.setRemoteDescription(new SimpleSdpObserver() {
                @Override
                public void onSetSuccess() {
                    session.remoteDescriptionSet = true;
                    drainPendingRemoteIce(session);

                    MediaConstraints constraints = createSdpConstraints(callType, false);
                    session.peerConnection.createAnswer(new SimpleSdpObserver() {
                        @Override
                        public void onCreateSuccess(SessionDescription answerSdp) {
                            if (released || session.peerConnection == null) return;
                            session.peerConnection.setLocalDescription(new SimpleSdpObserver() {
                                @Override
                                public void onSetSuccess() {
                                    NetPacket ans = signaling.buildBasePacket(NetPacket.TYPE_CALL_ANSWER);
                                    ans.targetId = peerId;
                                    ans.callId = callId;
                                    ans.groupId = activeGroupId;
                                    ans.callType = callType;
                                    ans.sdp = answerSdp.description;
                                    signaling.sendCallPacketToPeer(peerId, ans);
                                }

                                @Override
                                public void onSetFailure(String error) {
                                    notifyMediaError("Failed to set local answer: " + error);
                                }
                            }, answerSdp);
                        }

                        @Override
                        public void onCreateFailure(String error) {
                            notifyMediaError("Failed to create answer: " + error);
                        }
                    }, constraints);
                }

                @Override
                public void onSetFailure(String error) {
                    notifyMediaError("Failed to apply remote offer: " + error);
                }
            }, offerSdp);
        };

        if (offerCollision) {
            session.peerConnection.setLocalDescription(new SimpleSdpObserver() {
                @Override
                public void onSetSuccess() {
                    applyOfferAndAnswer.run();
                }

                @Override
                public void onSetFailure(String s) {
                    applyOfferAndAnswer.run();
                }
            }, new SessionDescription(SessionDescription.Type.ROLLBACK, ""));
        } else {
            applyOfferAndAnswer.run();
        }
    }

    public void handleRemoteAnswer(String peerId, String sdp) {
        if (released || peerId == null || sdp == null || sdp.isEmpty()) return;
        PeerSession session = peerSessions.get(peerId);
        if (session == null || session.peerConnection == null) return;

        session.peerConnection.setRemoteDescription(new SimpleSdpObserver() {
            @Override
            public void onSetSuccess() {
                session.remoteDescriptionSet = true;
                drainPendingRemoteIce(session);
            }

            @Override
            public void onSetFailure(String error) {
                notifyMediaError("Failed to apply remote answer: " + error);
            }
        }, new SessionDescription(SessionDescription.Type.ANSWER, sdp));
    }

    public void handleRemoteIce(String peerId, String candidate, String sdpMid, int sdpMLineIndex) {
        if (released || peerId == null || candidate == null || candidate.isEmpty()) return;
        PeerSession session = peerSessions.computeIfAbsent(peerId, PeerSession::new);
        IceCandidate ice = new IceCandidate(sdpMid, sdpMLineIndex, candidate);
        synchronized (session.pendingRemoteIce) {
            if (session.peerConnection != null && session.remoteDescriptionSet) {
                try {
                    session.peerConnection.addIceCandidate(ice);
                } catch (Exception ignored) {
                }
            } else {
                session.pendingRemoteIce.add(ice);
            }
        }
    }

    private void drainPendingRemoteIce(PeerSession session) {
        if (session.peerConnection == null) return;
        synchronized (session.pendingRemoteIce) {
            for (IceCandidate candidate : session.pendingRemoteIce) {
                try {
                    session.peerConnection.addIceCandidate(candidate);
                } catch (Exception ignored) {
                }
            }
            session.pendingRemoteIce.clear();
        }
    }

    public void setMuted(boolean muted) {
        this.audioMuted = muted;
        if (localAudioTrack != null) {
            localAudioTrack.setEnabled(!muted);
        }
    }

    public void setVideoEnabled(boolean enabled) {
        this.videoEnabled = enabled;
        if (localVideoTrack != null) {
            localVideoTrack.setEnabled(enabled);
        }
        if (videoCapturer != null) {
            if (enabled && !capturerRunning) {
                try {
                    videoCapturer.startCapture(640, 480, 24);
                    capturerRunning = true;
                } catch (Exception e) {
                    notifyMediaError("Failed to resume camera: " + e.getMessage());
                }
            } else if (!enabled && capturerRunning) {
                try {
                    videoCapturer.stopCapture();
                    capturerRunning = false;
                } catch (Exception ignored) {
                }
            }
        }
    }

    public void switchCamera() {
        if (videoCapturer == null) return;
        videoCapturer.switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
            @Override
            public void onCameraSwitchDone(boolean isFront) {
                isFrontCamera = isFront;
                SurfaceViewRenderer local = boundLocalRenderer;
                if (local != null) {
                    mainHandler.post(() -> local.setMirror(isFront));
                }
            }

            @Override
            public void onCameraSwitchError(String errorDescription) {
                notifyMediaError("Camera flip failed: " + errorDescription);
            }
        });
    }

    public void setSpeakerphoneOn(boolean on) {
        audioRouter.setSpeakerphoneEnabled(on);
    }

    public void releaseAll() {
        released = true;
        if (localVideoTrack != null && boundLocalRenderer != null) {
            try {
                localVideoTrack.removeSink(boundLocalRenderer);
            } catch (Exception ignored) {
            }
        }
        boundLocalRenderer = null;

        for (PeerSession session : peerSessions.values()) {
            if (session.iceRestartRunnable != null) {
                mainHandler.removeCallbacks(session.iceRestartRunnable);
                session.iceRestartRunnable = null;
            }
            if (session.peerConnection != null) {
                try {
                    session.peerConnection.close();
                    session.peerConnection.dispose();
                } catch (Exception ignored) {
                }
                session.peerConnection = null;
            }
        }
        peerSessions.clear();

        if (videoCapturer != null) {
            try {
                if (capturerRunning) {
                    videoCapturer.stopCapture();
                }
                videoCapturer.dispose();
            } catch (Exception ignored) {
            }
            videoCapturer = null;
            capturerRunning = false;
        }
        if (localVideoTrack != null) {
            try {
                localVideoTrack.dispose();
            } catch (Exception ignored) {
            }
            localVideoTrack = null;
        }
        if (localAudioTrack != null) {
            try {
                localAudioTrack.dispose();
            } catch (Exception ignored) {
            }
            localAudioTrack = null;
        }
        if (surfaceTextureHelper != null) {
            try {
                surfaceTextureHelper.dispose();
            } catch (Exception ignored) {
            }
            surfaceTextureHelper = null;
        }
        if (videoSource != null) {
            try {
                videoSource.dispose();
            } catch (Exception ignored) {
            }
            videoSource = null;
        }
        if (audioSource != null) {
            try {
                audioSource.dispose();
            } catch (Exception ignored) {
            }
            audioSource = null;
        }
        if (factory != null) {
            try {
                factory.dispose();
            } catch (Exception ignored) {
            }
            factory = null;
        }
        if (audioDeviceModule != null) {
            try {
                audioDeviceModule.release();
            } catch (Exception ignored) {
            }
            audioDeviceModule = null;
        }
        if (eglBase != null) {
            try {
                eglBase.release();
            } catch (Exception ignored) {
            }
            eglBase = null;
        }
        audioRouter.stop();
    }

    @Nullable
    private PeerSession getOrCreatePeerSession(String peerId) {
        if (factory == null || released) return null;
        PeerSession session = peerSessions.computeIfAbsent(peerId, PeerSession::new);
        if (session.peerConnection != null) return session;

        List<PeerConnection.IceServer> iceServers = buildIceServers();
        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED;
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;

        PeerConnection pc = factory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) { }

            @Override
            public void onConnectionChange(PeerConnection.PeerConnectionState newState) {
                handleConnectionStateTransition(session, newState);
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState newState) {
                if (newState == PeerConnection.IceConnectionState.CHECKING) {
                    if (!session.isConnected) {
                        notifyPeerConnecting(peerId);
                    }
                } else if (newState == PeerConnection.IceConnectionState.CONNECTED
                        || newState == PeerConnection.IceConnectionState.COMPLETED) {
                    markSessionConnected(session);
                } else if (newState == PeerConnection.IceConnectionState.DISCONNECTED) {
                    handleSessionTemporarilyDisconnected(session);
                } else if (newState == PeerConnection.IceConnectionState.FAILED) {
                    handleSessionFailed(session);
                } else if (newState == PeerConnection.IceConnectionState.CLOSED) {
                    handleSessionClosed(session);
                }
            }

            @Override
            public void onIceConnectionReceivingChange(boolean b) { }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) { }

            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                if (released || iceCandidate == null) return;
                NetPacket ice = signaling.buildBasePacket(NetPacket.TYPE_CALL_ICE);
                ice.targetId = peerId;
                ice.callId = activeCallId;
                ice.groupId = activeGroupId;
                ice.iceCandidate = iceCandidate.sdp;
                ice.iceSdpMid = iceCandidate.sdpMid;
                ice.iceSdpMLineIndex = iceCandidate.sdpMLineIndex;
                signaling.sendCallPacketToPeer(peerId, ice);
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) { }

            @Override
            public void onAddStream(MediaStream mediaStream) {
                if (mediaStream == null) return;
                if (mediaStream.audioTracks != null && !mediaStream.audioTracks.isEmpty()) {
                    handleRemoteAudioTrack(session, mediaStream.audioTracks.get(0));
                }
                if (mediaStream.videoTracks != null && !mediaStream.videoTracks.isEmpty()) {
                    handleRemoteVideoTrack(session, mediaStream.videoTracks.get(0));
                }
            }

            @Override
            public void onRemoveStream(MediaStream mediaStream) { }

            @Override
            public void onDataChannel(DataChannel dataChannel) { }

            @Override
            public void onRenegotiationNeeded() { }

            @Override
            public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
                if (rtpReceiver == null || rtpReceiver.track() == null) return;
                if (rtpReceiver.track() instanceof AudioTrack) {
                    handleRemoteAudioTrack(session, (AudioTrack) rtpReceiver.track());
                } else if (rtpReceiver.track() instanceof VideoTrack) {
                    handleRemoteVideoTrack(session, (VideoTrack) rtpReceiver.track());
                }
            }

            @Override
            public void onTrack(RtpTransceiver transceiver) {
                if (transceiver == null || transceiver.getReceiver() == null) return;
                RtpReceiver receiver = transceiver.getReceiver();
                if (receiver.track() instanceof AudioTrack) {
                    handleRemoteAudioTrack(session, (AudioTrack) receiver.track());
                } else if (receiver.track() instanceof VideoTrack) {
                    handleRemoteVideoTrack(session, (VideoTrack) receiver.track());
                }
            }
        });

        if (pc != null) {
            if (localAudioTrack != null) {
                pc.addTrack(localAudioTrack, Collections.singletonList("ARDAMS"));
            }
            if (localVideoTrack != null) {
                pc.addTrack(localVideoTrack, Collections.singletonList("ARDAMS"));
            }
            session.peerConnection = pc;
        }
        return session;
    }

    private void handleRemoteAudioTrack(PeerSession session, AudioTrack track) {
        if (track == null || released) return;
        track.setEnabled(true);
        if (session.remoteAudioTrack == track) return;
        session.remoteAudioTrack = track;
        CallEvents cb = this.events;
        if (cb != null) {
            mainHandler.post(() -> cb.onRemoteAudioTrack(session.peerId, true));
        }
    }

    private void handleRemoteVideoTrack(PeerSession session, VideoTrack track) {
        if (track == null || released) return;
        track.setEnabled(true);
        if (session.remoteVideoTrack == track) return;
        session.remoteVideoTrack = track;
        CallEvents cb = this.events;
        if (cb != null) {
            mainHandler.post(() -> cb.onRemoteVideoTrack(session.peerId, track));
        }
    }

    private void handleConnectionStateTransition(PeerSession session, PeerConnection.PeerConnectionState state) {
        if (state == PeerConnection.PeerConnectionState.CONNECTING) {
            if (!session.isConnected) {
                notifyPeerConnecting(session.peerId);
            }
        } else if (state == PeerConnection.PeerConnectionState.CONNECTED) {
            markSessionConnected(session);
        } else if (state == PeerConnection.PeerConnectionState.DISCONNECTED) {
            handleSessionTemporarilyDisconnected(session);
        } else if (state == PeerConnection.PeerConnectionState.FAILED) {
            handleSessionFailed(session);
        } else if (state == PeerConnection.PeerConnectionState.CLOSED) {
            handleSessionClosed(session);
        }
    }

    private void markSessionConnected(PeerSession session) {
        if (session.iceRestartRunnable != null) {
            mainHandler.removeCallbacks(session.iceRestartRunnable);
            session.iceRestartRunnable = null;
        }
        if (!session.isConnected) {
            session.isConnected = true;
            CallEvents cb = this.events;
            if (cb != null) {
                mainHandler.post(() -> cb.onPeerConnected(session.peerId));
            }
        }
    }

    private void handleSessionTemporarilyDisconnected(PeerSession session) {
        CallEvents cb = this.events;
        if (cb != null && !released) {
            mainHandler.post(() -> cb.onPeerReconnecting(session.peerId));
        }
        if (session.iceRestartRunnable == null && !released && myDeviceId.compareTo(session.peerId) <= 0) {
            session.iceRestartRunnable = () -> triggerIceRestart(session);
            mainHandler.postDelayed(session.iceRestartRunnable, ICE_RESTART_DELAY_MS);
        }
    }

    private void handleSessionFailed(PeerSession session) {
        if (session.iceRestartRunnable != null) {
            mainHandler.removeCallbacks(session.iceRestartRunnable);
            session.iceRestartRunnable = null;
        }
        session.isConnected = false;
        CallEvents cb = this.events;
        if (cb != null && !released) {
            mainHandler.post(() -> cb.onPeerFailed(session.peerId, "Media connection failed"));
        }
    }

    private void handleSessionClosed(PeerSession session) {
        if (session.iceRestartRunnable != null) {
            mainHandler.removeCallbacks(session.iceRestartRunnable);
            session.iceRestartRunnable = null;
        }
        session.isConnected = false;
        CallEvents cb = this.events;
        if (cb != null && !released) {
            mainHandler.post(() -> cb.onPeerDisconnected(session.peerId));
        }
    }

    private void triggerIceRestart(PeerSession session) {
        session.iceRestartRunnable = null;
        if (released || session.peerConnection == null || activeCallId == null) return;
        if (session.peerConnection.signalingState() != PeerConnection.SignalingState.STABLE) return;

        MediaConstraints constraints = createSdpConstraints(activeCallType, true);
        session.makingOffer = true;
        session.peerConnection.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                if (released || session.peerConnection == null) {
                    session.makingOffer = false;
                    return;
                }
                session.peerConnection.setLocalDescription(new SimpleSdpObserver() {
                    @Override
                    public void onSetSuccess() {
                        session.makingOffer = false;
                        NetPacket offer = signaling.buildBasePacket(NetPacket.TYPE_CALL_OFFER);
                        offer.targetId = session.peerId;
                        offer.callId = activeCallId;
                        offer.groupId = activeGroupId;
                        offer.callType = activeCallType;
                        offer.sdp = sdp.description;
                        signaling.sendCallPacketToPeer(session.peerId, offer);
                    }

                    @Override
                    public void onSetFailure(String s) {
                        session.makingOffer = false;
                    }
                }, sdp);
            }

            @Override
            public void onCreateFailure(String s) {
                session.makingOffer = false;
            }
        }, constraints);
    }

    private List<PeerConnection.IceServer> buildIceServers() {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        // Host ICE candidates work directly on offline LAN / Hotspot / WiFi Direct with zero latency.
        // When internet connectivity is available, add STUN servers without inventing TURN credentials.
        if (NetworkUtils.hasInternetConnection(context)) {
            iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
            iceServers.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());
        }
        return iceServers;
    }

    private MediaConstraints createSdpConstraints(String callType, boolean iceRestart) {
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo",
                callType != null && callType.contains("VIDEO") ? "true" : "false"));
        if (iceRestart) {
            constraints.mandatory.add(new MediaConstraints.KeyValuePair("IceRestart", "true"));
        }
        return constraints;
    }

    private CameraVideoCapturer createCameraCapturer() {
        CameraVideoCapturer.CameraEventsHandler cameraEvents = new CameraVideoCapturer.CameraEventsHandler() {
            @Override
            public void onCameraError(String errorDescription) {
                notifyMediaError("Camera error: " + errorDescription);
            }

            @Override
            public void onCameraDisconnected() {
                notifyMediaError("Camera disconnected");
            }

            @Override
            public void onCameraFreezed(String errorDescription) {
                notifyMediaError("Camera stream interrupted: " + errorDescription);
            }

            @Override
            public void onCameraOpening(String cameraName) { }

            @Override
            public void onFirstFrameAvailable() { }

            @Override
            public void onCameraClosed() {
                capturerRunning = false;
            }
        };

        CameraEnumerator enumerator = Camera2Enumerator.isSupported(context)
                ? new Camera2Enumerator(context)
                : new Camera1Enumerator(true);

        CameraVideoCapturer capturer = findCapturer(enumerator, cameraEvents, true);
        if (capturer != null) {
            isFrontCamera = true;
            return capturer;
        }
        capturer = findCapturer(enumerator, cameraEvents, false);
        if (capturer != null) {
            isFrontCamera = false;
            return capturer;
        }

        if (enumerator instanceof Camera2Enumerator) {
            CameraEnumerator fallback = new Camera1Enumerator(true);
            capturer = findCapturer(fallback, cameraEvents, true);
            if (capturer != null) {
                isFrontCamera = true;
                return capturer;
            }
            capturer = findCapturer(fallback, cameraEvents, false);
            if (capturer != null) {
                isFrontCamera = false;
                return capturer;
            }
        }
        return null;
    }

    @Nullable
    private CameraVideoCapturer findCapturer(CameraEnumerator enumerator,
                                             CameraVideoCapturer.CameraEventsHandler eventsHandler,
                                             boolean frontFacing) {
        for (String name : enumerator.getDeviceNames()) {
            if (enumerator.isFrontFacing(name) == frontFacing) {
                CameraVideoCapturer c = enumerator.createCapturer(name, eventsHandler);
                if (c != null) return c;
            }
        }
        return null;
    }

    private void notifyPeerConnecting(String peerId) {
        CallEvents cb = this.events;
        if (cb != null && !released) {
            mainHandler.post(() -> cb.onPeerConnecting(peerId));
        }
    }

    private void notifyMediaError(String message) {
        CallEvents cb = this.events;
        if (cb != null && !released) {
            mainHandler.post(() -> cb.onMediaError(message));
        }
    }

    private static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sessionDescription) { }
        @Override public void onSetSuccess() { }
        @Override public void onCreateFailure(String s) { }
        @Override public void onSetFailure(String s) { }
    }
}
