package com.android.wificalllocal.webrtc;

import android.content.Context;
import android.media.AudioManager;

import com.android.wificalllocal.network.PeerConnectionManager;
import com.android.wificalllocal.network.model.NetPacket;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
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
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Native Android WebRTC engine using host ICE candidates for same-LAN / Hotspot / WiFi Direct calls
 * without requiring internet STUN/TURN servers. Supports 1-to-1 voice/video and 4-6 participant mesh calls.
 */
public class WebRtcCallManager {

    public interface CallEvents {
        void onRemoteVideoTrack(String peerId, VideoTrack track);
        void onPeerConnected(String peerId);
        void onPeerDisconnected(String peerId);
    }

    private final Context context;
    private final PeerConnectionManager signaling;
    private EglBase eglBase;
    private PeerConnectionFactory factory;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;
    private VideoSource videoSource;
    private VideoTrack localVideoTrack;
    private CameraVideoCapturer videoCapturer;
    private SurfaceTextureHelper surfaceTextureHelper;

    private final Map<String, PeerConnection> peerConnections = new ConcurrentHashMap<>();
    private CallEvents events;
    private String activeCallId;
    private String activeGroupId;
    private String activeCallType;
    private boolean audioMuted = false;
    private boolean videoEnabled = true;

    public WebRtcCallManager(Context context, PeerConnectionManager signaling) {
        this.context = context.getApplicationContext();
        this.signaling = signaling;
    }

    public void setGroupContext(String callId, String groupId, String callType) {
        this.activeCallId = callId;
        this.activeGroupId = groupId;
        this.activeCallType = callType;
    }

    public boolean hasPeer(String peerId) {
        return peerId != null && peerConnections.containsKey(peerId);
    }

    public void removePeer(String peerId) {
        if (peerId == null) return;
        PeerConnection pc = peerConnections.remove(peerId);
        if (pc != null) {
            try {
                pc.close();
                pc.dispose();
            } catch (Exception ignored) {
            }
        }
    }

    public EglBase.Context getEglContext() {
        if (eglBase == null) eglBase = EglBase.create();
        return eglBase.getEglBaseContext();
    }

    public void initEngine(boolean isVideo, SurfaceViewRenderer localRenderer, CallEvents events) {
        this.events = events;
        if (eglBase == null) eglBase = EglBase.create();

        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        );
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        DefaultVideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(
                eglBase.getEglBaseContext(), true, true);
        DefaultVideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());

        factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory();

        audioSource = factory.createAudioSource(new MediaConstraints());
        localAudioTrack = factory.createAudioTrack("ARDAMSa0", audioSource);
        localAudioTrack.setEnabled(!audioMuted);

        if (isVideo) {
            videoCapturer = createCameraCapturer();
            if (videoCapturer != null) {
                surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
                videoSource = factory.createVideoSource(videoCapturer.isScreencast());
                videoCapturer.initialize(surfaceTextureHelper, context, videoSource.getCapturerObserver());
                videoCapturer.startCapture(640, 480, 24);
                localVideoTrack = factory.createVideoTrack("ARDAMSv0", videoSource);
                localVideoTrack.setEnabled(videoEnabled);
                if (localRenderer != null) {
                    localVideoTrack.addSink(localRenderer);
                }
            }
        }
    }

    public void startOutgoingCall(String callId, String peerId, String callType) {
        this.activeCallId = callId;
        this.activeCallType = callType;
        PeerConnection pc = getOrCreatePeerConnection(peerId);
        if (pc == null) return;

        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo",
                callType != null && callType.contains("VIDEO") ? "true" : "false"));

        pc.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                pc.setLocalDescription(new SimpleSdpObserver(), sdp);
                NetPacket offer = signaling.buildBasePacket(NetPacket.TYPE_CALL_OFFER);
                offer.targetId = peerId;
                offer.callId = callId;
                offer.groupId = activeGroupId;
                offer.callType = callType;
                offer.sdp = sdp.description;
                signaling.sendPacketToPeer(peerId, offer);
            }
        }, constraints);
    }

    public void acceptIncomingCall(String callId, String peerId, String callType, String remoteSdp) {
        this.activeCallId = callId;
        this.activeCallType = callType;
        PeerConnection pc = getOrCreatePeerConnection(peerId);
        if (pc == null) return;

        SessionDescription offerSdp = new SessionDescription(SessionDescription.Type.OFFER, remoteSdp);
        pc.setRemoteDescription(new SimpleSdpObserver() {
            @Override
            public void onSetSuccess() {
                MediaConstraints constraints = new MediaConstraints();
                constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
                constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo",
                        callType != null && callType.contains("VIDEO") ? "true" : "false"));
                pc.createAnswer(new SimpleSdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription answerSdp) {
                        pc.setLocalDescription(new SimpleSdpObserver(), answerSdp);
                        NetPacket ans = signaling.buildBasePacket(NetPacket.TYPE_CALL_ANSWER);
                        ans.targetId = peerId;
                        ans.callId = callId;
                        ans.groupId = activeGroupId;
                        ans.callType = callType;
                        ans.sdp = answerSdp.description;
                        signaling.sendPacketToPeer(peerId, ans);
                    }
                }, constraints);
            }
        }, offerSdp);
    }

    public void handleRemoteAnswer(String peerId, String sdp) {
        PeerConnection pc = peerConnections.get(peerId);
        if (pc != null && sdp != null) {
            pc.setRemoteDescription(new SimpleSdpObserver(),
                    new SessionDescription(SessionDescription.Type.ANSWER, sdp));
        }
    }

    public void handleRemoteIce(String peerId, String candidate, String sdpMid, int sdpMLineIndex) {
        PeerConnection pc = peerConnections.get(peerId);
        if (pc != null && candidate != null) {
            pc.addIceCandidate(new IceCandidate(sdpMid, sdpMLineIndex, candidate));
        }
    }

    public void setMuted(boolean muted) {
        this.audioMuted = muted;
        if (localAudioTrack != null) localAudioTrack.setEnabled(!muted);
    }

    public void setVideoEnabled(boolean enabled) {
        this.videoEnabled = enabled;
        if (localVideoTrack != null) localVideoTrack.setEnabled(enabled);
    }

    public void switchCamera() {
        if (videoCapturer != null) {
            videoCapturer.switchCamera(null);
        }
    }

    public void setSpeakerphoneOn(boolean on) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            am.setSpeakerphoneOn(on);
        }
    }

    public void releaseAll() {
        for (PeerConnection pc : peerConnections.values()) {
            try {
                pc.close();
                pc.dispose();
            } catch (Exception ignored) {
            }
        }
        peerConnections.clear();

        if (videoCapturer != null) {
            try {
                videoCapturer.stopCapture();
                videoCapturer.dispose();
            } catch (Exception ignored) {
            }
            videoCapturer = null;
        }
        if (surfaceTextureHelper != null) {
            surfaceTextureHelper.dispose();
            surfaceTextureHelper = null;
        }
        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }
        if (factory != null) {
            factory.dispose();
            factory = null;
        }
        if (eglBase != null) {
            eglBase.release();
            eglBase = null;
        }
    }

    private PeerConnection getOrCreatePeerConnection(String peerId) {
        if (factory == null) return null;
        PeerConnection existing = peerConnections.get(peerId);
        if (existing != null) return existing;

        // Same-LAN Host ICE configuration (no STUN/TURN needed)
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        PeerConnection pc = factory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) { }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState newState) {
                if (events == null) return;
                if (newState == PeerConnection.IceConnectionState.CONNECTED) {
                    events.onPeerConnected(peerId);
                } else if (newState == PeerConnection.IceConnectionState.DISCONNECTED
                        || newState == PeerConnection.IceConnectionState.FAILED
                        || newState == PeerConnection.IceConnectionState.CLOSED) {
                    events.onPeerDisconnected(peerId);
                }
            }

            @Override
            public void onIceConnectionReceivingChange(boolean b) { }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) { }

            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                NetPacket ice = signaling.buildBasePacket(NetPacket.TYPE_CALL_ICE);
                ice.targetId = peerId;
                ice.callId = activeCallId;
                ice.groupId = activeGroupId;
                ice.iceCandidate = iceCandidate.sdp;
                ice.iceSdpMid = iceCandidate.sdpMid;
                ice.iceSdpMLineIndex = iceCandidate.sdpMLineIndex;
                signaling.sendPacketToPeer(peerId, ice);
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) { }

            @Override
            public void onAddStream(MediaStream mediaStream) { }

            @Override
            public void onRemoveStream(MediaStream mediaStream) { }

            @Override
            public void onDataChannel(DataChannel dataChannel) { }

            @Override
            public void onRenegotiationNeeded() { }

            @Override
            public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
                if (rtpReceiver.track() instanceof VideoTrack && events != null) {
                    events.onRemoteVideoTrack(peerId, (VideoTrack) rtpReceiver.track());
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
            peerConnections.put(peerId, pc);
        }
        return pc;
    }

    private CameraVideoCapturer createCameraCapturer() {
        Camera2Enumerator enumerator = new Camera2Enumerator(context);
        for (String name : enumerator.getDeviceNames()) {
            if (enumerator.isFrontFacing(name)) {
                CameraVideoCapturer c = enumerator.createCapturer(name, null);
                if (c != null) return c;
            }
        }
        for (String name : enumerator.getDeviceNames()) {
            if (!enumerator.isFrontFacing(name)) {
                CameraVideoCapturer c = enumerator.createCapturer(name, null);
                if (c != null) return c;
            }
        }
        return null;
    }

    private static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sessionDescription) { }
        @Override public void onSetSuccess() { }
        @Override public void onCreateFailure(String s) { }
        @Override public void onSetFailure(String s) { }
    }
}
