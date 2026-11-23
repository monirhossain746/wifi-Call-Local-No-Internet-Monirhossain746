package com.android.wificalllocal.ui.call;

import android.Manifest;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Rational;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.android.wificalllocal.R;
import com.android.wificalllocal.ServiceLocator;
import com.android.wificalllocal.control.AppControlManager;
import com.android.wificalllocal.data.local.AppDatabase;
import com.android.wificalllocal.data.local.entity.CallLogEntity;
import com.android.wificalllocal.databinding.ActivityCallBinding;
import com.android.wificalllocal.network.PeerConnectionManager;
import com.android.wificalllocal.network.model.NetPacket;
import com.android.wificalllocal.service.CallForegroundService;
import com.android.wificalllocal.util.AvatarLoader;
import com.android.wificalllocal.util.PreferencesManager;
import com.android.wificalllocal.webrtc.CallAudioRouter;
import com.android.wificalllocal.webrtc.WebRtcCallManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class CallActivity extends AppCompatActivity implements PeerConnectionManager.CallSignalListener, WebRtcCallManager.CallEvents {

    public static final String EXTRA_INCOMING = "incoming";
    public static final String EXTRA_CALL_ID = "call_id";
    public static final String EXTRA_PEER_ID = "peer_id";
    public static final String EXTRA_PEER_NAME = "peer_name";
    public static final String EXTRA_PEER_IP = "peer_ip";
    public static final String EXTRA_CALL_TYPE = "call_type";
    public static final String EXTRA_OFFER_SDP = "offer_sdp";
    public static final String EXTRA_GROUP_ID = "group_id";
    public static final String EXTRA_GROUP_NAME = "group_name";

    private static final long OUTGOING_NO_ANSWER_TIMEOUT_MS = 35_000L;

    private static final class GroupVideoTile {
        final FrameLayout container;
        final SurfaceViewRenderer renderer;
        final TextView label;
        VideoTrack attachedTrack;

        GroupVideoTile(FrameLayout container, SurfaceViewRenderer renderer, TextView label) {
            this.container = container;
            this.renderer = renderer;
            this.label = label;
        }
    }

    private ActivityCallBinding binding;
    private ServiceLocator locator;
    private WebRtcCallManager webRtcManager;

    private boolean isIncoming;
    private String callId;
    private String peerId;
    private String peerName;
    private String peerIp;
    private String callType;
    private String offerSdp;
    private String groupId;
    private String groupName;
    private boolean isGroupCall;
    private String myDeviceId;

    private boolean isMuted = false;
    private boolean isSpeaker = false;
    private boolean isVideoEnabled = true;
    private boolean effectiveIsVideo = false;
    private boolean callConnected = false;
    private boolean engineStarted = false;
    private long connectedAtMs = 0L;
    private String finalStatus = "MISSED";
    private boolean endSignalSent = false;

    private boolean singleRemoteVideoAttached = false;
    private boolean remotePeerMuted = false;
    private boolean remotePeerCameraOff = false;
    private VideoTrack singleRemoteVideoTrack;
    private final Set<String> connectedPeers = new HashSet<>();
    private final Map<String, GroupVideoTile> groupVideoTiles = new HashMap<>();

    private Ringtone incomingRingtone;
    private Vibrator incomingVibrator;

    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (callConnected && binding != null) {
                long sec = (System.currentTimeMillis() - connectedAtMs) / 1000L;
                binding.callDurationText.setText(String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60));
                timerHandler.postDelayed(this, 1000L);
            }
        }
    };

    private final Runnable noAnswerTimeoutRunnable = () -> {
        if (!isFinishing() && !isDestroyed() && !callConnected && !isIncoming && !isGroupCall) {
            finalStatus = "NO_ANSWER";
            sendEndSignal(false);
            Toast.makeText(this, R.string.call_status_no_answer, Toast.LENGTH_SHORT).show();
            finish();
        }
    };

    private final ActivityResultLauncher<String[]> mediaPermLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            results -> {
                boolean micOk = Boolean.TRUE.equals(results.getOrDefault(Manifest.permission.RECORD_AUDIO,
                        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED));
                if (!micOk) {
                    Toast.makeText(this, R.string.perm_microphone, Toast.LENGTH_LONG).show();
                    finalStatus = isIncoming ? "REJECTED" : "MISSED";
                    sendEndSignal(isIncoming);
                    finish();
                    return;
                }
                if (effectiveIsVideo && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
                    effectiveIsVideo = false;
                    isVideoEnabled = false;
                    updateVideoButtonUi();
                    Toast.makeText(this, R.string.call_camera_permission_voice_fallback, Toast.LENGTH_LONG).show();
                }
                startCallEngine();
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCallBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        locator = ServiceLocator.getInstance(this);
        AppControlManager.AppGateState gateState = locator.getAppControlManager().getCurrentGateState();
        if (gateState.isBlocked()) {
            locator.getAppControlManager().enforceGateIfBlocked(this, gateState);
            return;
        }
        locator.getNotificationHelper().cancelIncomingCallNotification();

        isIncoming = getIntent().getBooleanExtra(EXTRA_INCOMING, false);
        callId = getIntent().getStringExtra(EXTRA_CALL_ID);
        if (callId == null || callId.isEmpty()) callId = UUID.randomUUID().toString();
        peerId = getIntent().getStringExtra(EXTRA_PEER_ID);
        peerName = getIntent().getStringExtra(EXTRA_PEER_NAME);
        peerIp = getIntent().getStringExtra(EXTRA_PEER_IP);
        callType = getIntent().getStringExtra(EXTRA_CALL_TYPE);
        if (callType == null) callType = "VOICE";
        offerSdp = getIntent().getStringExtra(EXTRA_OFFER_SDP);
        groupId = getIntent().getStringExtra(EXTRA_GROUP_ID);
        groupName = getIntent().getStringExtra(EXTRA_GROUP_NAME);
        isGroupCall = groupId != null && !groupId.isEmpty();
        myDeviceId = locator.getPreferencesManager().getOrCreateDeviceId();

        if (isGroupCall && !gateState.groupCallsEnabled) {
            Toast.makeText(this, R.string.feature_disabled_remote, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        boolean isVideo = callType.contains("VIDEO");
        effectiveIsVideo = isVideo;
        isVideoEnabled = isVideo;
        isSpeaker = isVideo;

        String titleName = isGroupCall && groupName != null && !groupName.isEmpty()
                ? groupName
                : (peerName != null ? peerName : "Local Peer");
        binding.callPeerName.setText(titleName);
        AvatarLoader.loadPeerAvatar(binding.callAvatar, titleName, isGroupCall ? groupId : peerId, null);
        updateVideoButtonUi();

        if (isGroupCall) {
            binding.callParticipantsStrip.setVisibility(View.VISIBLE);
            refreshGroupParticipantsStrip();
        }

        locator.getPeerConnectionManager().setCallSignalListener(this);

        if (isIncoming && PreferencesManager.STATUS_BUSY.equals(locator.getPreferencesManager().getActiveStatus())) {
            NetPacket busy = locator.getPeerConnectionManager().buildBasePacket(NetPacket.TYPE_CALL_BUSY);
            busy.targetId = peerId;
            busy.callId = callId;
            busy.groupId = groupId;
            locator.getPeerConnectionManager().sendCallPacketToPeer(peerId, busy);
            finalStatus = "BUSY";
            finish();
            return;
        }

        setupControls();

        if (isIncoming) {
            binding.callStatusText.setText(isVideo ? R.string.call_incoming_video : R.string.call_incoming_voice);
            binding.incomingControls.setVisibility(View.VISIBLE);
            binding.callControlsCard.setVisibility(View.GONE);
            startIncomingAlert();

            if (!isGroupCall && peerId != null) {
                NetPacket ringing = locator.getPeerConnectionManager().buildBasePacket(NetPacket.TYPE_CALL_RINGING);
                ringing.targetId = peerId;
                ringing.callId = callId;
                locator.getPeerConnectionManager().sendCallPacketToPeer(peerId, ringing);
            }
        } else {
            binding.callStatusText.setText(isGroupCall ? R.string.call_status_waiting_participant : R.string.call_status_calling);
            checkMediaPermissionsAndStart();
            if (!isGroupCall) {
                timerHandler.postDelayed(noAnswerTimeoutRunnable, OUTGOING_NO_ANSWER_TIMEOUT_MS);
            }
        }
    }

    private void updateVideoButtonUi() {
        if (binding == null) return;
        int vis = effectiveIsVideo ? View.VISIBLE : View.GONE;
        binding.btnVideoToggle.setVisibility(vis);
        binding.btnFlipCamera.setVisibility(vis);
        binding.btnPip.setVisibility(vis);
        binding.btnVideoToggle.setImageResource(isVideoEnabled ? R.drawable.ic_video : R.drawable.ic_videocam_off);
    }

    private void setupControls() {
        binding.btnAcceptCall.setOnClickListener(v -> {
            stopIncomingAlert();
            binding.incomingControls.setVisibility(View.GONE);
            binding.callControlsCard.setVisibility(View.VISIBLE);
            binding.callStatusText.setText(R.string.call_status_connecting);
            checkMediaPermissionsAndStart();
        });

        binding.btnRejectCall.setOnClickListener(v -> {
            stopIncomingAlert();
            finalStatus = "REJECTED";
            sendEndSignal(true);
            finish();
        });

        binding.btnEndCall.setOnClickListener(v -> {
            stopIncomingAlert();
            finalStatus = callConnected ? "COMPLETED" : "MISSED";
            sendEndSignal(false);
            finish();
        });

        binding.btnMute.setOnClickListener(v -> {
            isMuted = !isMuted;
            if (webRtcManager != null) webRtcManager.setMuted(isMuted);
            binding.btnMute.setImageResource(isMuted ? R.drawable.ic_mic_off : R.drawable.ic_mic);
            sendLocalMediaStateSignal();
            if (isGroupCall) {
                locator.getPeerConnectionManager().updateGroupCallSelfState(callId, groupId, isMuted, isVideoEnabled);
                refreshGroupParticipantsStrip();
            }
        });

        binding.btnSpeaker.setOnClickListener(v -> {
            isSpeaker = !isSpeaker;
            if (webRtcManager != null) webRtcManager.setSpeakerphoneOn(isSpeaker);
            binding.btnSpeaker.setAlpha(isSpeaker ? 1.0f : 0.6f);
        });

        binding.btnVideoToggle.setOnClickListener(v -> {
            isVideoEnabled = !isVideoEnabled;
            if (webRtcManager != null) webRtcManager.setVideoEnabled(isVideoEnabled);
            binding.btnVideoToggle.setImageResource(isVideoEnabled ? R.drawable.ic_video : R.drawable.ic_videocam_off);
            sendLocalMediaStateSignal();
            if (isGroupCall) {
                locator.getPeerConnectionManager().updateGroupCallSelfState(callId, groupId, isMuted, isVideoEnabled);
                refreshGroupParticipantsStrip();
            }
        });

        binding.btnFlipCamera.setOnClickListener(v -> {
            if (webRtcManager != null) webRtcManager.switchCamera();
        });

        binding.btnPip.setOnClickListener(v -> enterPip());
    }

    private void sendLocalMediaStateSignal() {
        if (isGroupCall || peerId == null || peerId.isEmpty()) return;
        NetPacket state = locator.getPeerConnectionManager().buildBasePacket(NetPacket.TYPE_CALL_STATE);
        state.targetId = peerId;
        state.callId = callId;
        state.muted = isMuted;
        state.videoEnabled = isVideoEnabled;
        locator.getPeerConnectionManager().sendCallPacketToPeer(peerId, state);
    }

    private void startIncomingAlert() {
        try {
            Uri tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            incomingRingtone = RingtoneManager.getRingtone(this, tone);
            if (incomingRingtone != null) {
                incomingRingtone.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                incomingRingtone.play();
            }
        } catch (Exception ignored) {
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null) incomingVibrator = vm.getDefaultVibrator();
            } else {
                incomingVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (incomingVibrator != null && incomingVibrator.hasVibrator()) {
                long[] pattern = {0, 600, 800};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    incomingVibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                } else {
                    incomingVibrator.vibrate(pattern, 0);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void stopIncomingAlert() {
        try {
            if (incomingRingtone != null && incomingRingtone.isPlaying()) {
                incomingRingtone.stop();
            }
        } catch (Exception ignored) {
        }
        incomingRingtone = null;
        try {
            if (incomingVibrator != null) {
                incomingVibrator.cancel();
            }
        } catch (Exception ignored) {
        }
        incomingVibrator = null;
    }

    private void checkMediaPermissionsAndStart() {
        List<String> req = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            req.add(Manifest.permission.RECORD_AUDIO);
        }
        if (effectiveIsVideo && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            req.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            req.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (req.isEmpty()) {
            startCallEngine();
        } else if (req.size() == 1 && Manifest.permission.BLUETOOTH_CONNECT.equals(req.get(0))) {
            // Optional Bluetooth routing permission on Android 12+; do not block call on rationale
            mediaPermLauncher.launch(req.toArray(new String[0]));
        } else {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.perm_rationale_title)
                    .setMessage(effectiveIsVideo ? (getString(R.string.perm_microphone) + "\n\n" + getString(R.string.perm_camera))
                            : getString(R.string.perm_microphone))
                    .setPositiveButton(R.string.ok, (d, w) -> mediaPermLauncher.launch(req.toArray(new String[0])))
                    .setNegativeButton(R.string.cancel, (d, w) -> {
                        finalStatus = isIncoming ? "REJECTED" : "MISSED";
                        sendEndSignal(isIncoming);
                        finish();
                    })
                    .show();
        }
    }

    private void startCallEngine() {
        if (engineStarted || isFinishing() || isDestroyed()) return;
        engineStarted = true;

        CallForegroundService.start(this, isGroupCall && groupName != null && !groupName.isEmpty() ? groupName : peerName, effectiveIsVideo);
        webRtcManager = new WebRtcCallManager(this, locator.getPeerConnectionManager(), myDeviceId);
        if (isGroupCall) {
            webRtcManager.setGroupContext(callId, groupId, callType);
        }

        webRtcManager.getAudioRouter().setRouteListener(route -> {
            if (binding == null) return;
            binding.callAudioRouteBadge.setVisibility(View.VISIBLE);
            int labelRes;
            switch (route) {
                case BLUETOOTH:
                    labelRes = R.string.call_route_bluetooth;
                    break;
                case WIRED_HEADSET:
                    labelRes = R.string.call_route_wired;
                    break;
                case SPEAKER:
                    labelRes = R.string.call_route_speaker;
                    break;
                default:
                    labelRes = R.string.call_route_earpiece;
                    break;
            }
            binding.callAudioRouteBadge.setText(labelRes);
            isSpeaker = (route == CallAudioRouter.AudioRoute.SPEAKER);
            binding.btnSpeaker.setAlpha(isSpeaker ? 1.0f : 0.65f);
        });

        if (effectiveIsVideo) {
            binding.localVideoView.init(webRtcManager.getEglContext(), null);
            binding.localVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
            binding.localVideoView.setEnableHardwareScaler(true);
            binding.localVideoView.setMirror(true);
            binding.localVideoView.setZOrderMediaOverlay(true);
            binding.localVideoView.setVisibility(View.VISIBLE);

            if (isGroupCall) {
                binding.groupVideoScroll.setVisibility(View.VISIBLE);
                binding.remoteVideoView.setVisibility(View.GONE);
            } else {
                binding.remoteVideoView.init(webRtcManager.getEglContext(), null);
                binding.remoteVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
                binding.remoteVideoView.setEnableHardwareScaler(true);
                binding.remoteVideoView.setVisibility(View.VISIBLE);
            }
        }

        boolean ok = webRtcManager.initEngine(effectiveIsVideo, effectiveIsVideo ? binding.localVideoView : null, this);
        if (!ok) {
            finish();
            return;
        }
        webRtcManager.setSpeakerphoneOn(isSpeaker);

        // Drain any early ICE / join / offer packets that arrived before engine startup
        locator.getPeerConnectionManager().drainBufferedCallSignals(callId, this);

        if (isGroupCall) {
            binding.callStatusText.setText(R.string.call_status_waiting_participant);
            locator.getPeerConnectionManager().startOrJoinGroupCall(
                    callId, groupId, groupName, callType, isMuted, isVideoEnabled,
                    this::connectToExistingGroupParticipants
            );
        } else if (isIncoming) {
            binding.callStatusText.setText(R.string.call_status_establishing_media);
            webRtcManager.acceptIncomingCall(callId, peerId, callType, offerSdp);
        } else {
            binding.callStatusText.setText(R.string.call_status_calling);
            webRtcManager.startOutgoingCall(callId, peerId, callType);
        }
    }

    private void connectToExistingGroupParticipants() {
        if (isFinishing() || isDestroyed() || webRtcManager == null) return;
        PeerConnectionManager.GroupCallRoom room = locator.getPeerConnectionManager().getActiveGroupCallForGroup(groupId);
        if (room != null) {
            for (NetPacket.GroupCallParticipantDto p : room.participants.values()) {
                if (p.deviceId != null && !p.deviceId.equals(myDeviceId) && !webRtcManager.hasPeer(p.deviceId)) {
                    if (myDeviceId.compareTo(p.deviceId) < 0) {
                        webRtcManager.startOutgoingCall(callId, p.deviceId, callType);
                    }
                }
            }
        }
        refreshGroupParticipantsStrip();
    }

    private void refreshGroupParticipantsStrip() {
        if (!isGroupCall || binding == null) return;
        PeerConnectionManager.GroupCallRoom room = locator.getPeerConnectionManager().getActiveGroupCallForGroup(groupId);
        if (room == null || room.participants.isEmpty()) {
            binding.callParticipantsStrip.setText("Group Call • Waiting for participants (max 6)");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(room.participants.size()).append("/").append(PeerConnectionManager.MAX_GROUP_CALL_PARTICIPANTS).append(" in call: ");
        int idx = 0;
        for (NetPacket.GroupCallParticipantDto p : room.participants.values()) {
            if (idx > 0) sb.append(", ");
            sb.append(p.displayName != null ? p.displayName : "Peer");
            if (p.muted) sb.append(" (muted)");
            if (!p.videoEnabled && callType.contains("VIDEO")) sb.append(" (cam off)");
            idx++;
        }
        binding.callParticipantsStrip.setText(sb.toString());
    }

    private void updateRemoteMediaStatusBanner() {
        if (binding == null || isGroupCall) return;
        List<String> notes = new ArrayList<>();
        if (remotePeerMuted) notes.add(getString(R.string.call_remote_mic_muted));
        if (effectiveIsVideo && remotePeerCameraOff) notes.add(getString(R.string.call_remote_cam_off));
        if (notes.isEmpty()) {
            binding.callRemoteMediaStatus.setVisibility(View.GONE);
        } else {
            binding.callRemoteMediaStatus.setVisibility(View.VISIBLE);
            binding.callRemoteMediaStatus.setText(String.join(" • ", notes));
        }
    }

    private void enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(9, 16))
                    .build();
            enterPictureInPictureMode(params);
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, @NonNull Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (binding != null) {
            binding.callControlsCard.setVisibility(isInPictureInPictureMode ? View.GONE : View.VISIBLE);
            binding.callInfoContainer.setVisibility(isInPictureInPictureMode ? View.GONE : View.VISIBLE);
        }
    }

    @Override
    public void onCallSignal(NetPacket pkt) {
        if (pkt == null || pkt.callId == null || !pkt.callId.equals(callId)) return;
        switch (pkt.type) {
            case NetPacket.TYPE_CALL_RINGING:
                if (!callConnected && binding != null) {
                    binding.callStatusText.setText(R.string.call_status_ringing);
                }
                break;
            case NetPacket.TYPE_CALL_BUSY:
                stopIncomingAlert();
                timerHandler.removeCallbacks(noAnswerTimeoutRunnable);
                finalStatus = "BUSY";
                Toast.makeText(this, pkt.reason != null ? pkt.reason : getString(R.string.call_status_busy), Toast.LENGTH_SHORT).show();
                finish();
                break;
            case NetPacket.TYPE_CALL_REJECT:
                stopIncomingAlert();
                timerHandler.removeCallbacks(noAnswerTimeoutRunnable);
                finalStatus = "REJECTED";
                Toast.makeText(this, R.string.call_status_rejected, Toast.LENGTH_SHORT).show();
                finish();
                break;
            case NetPacket.TYPE_CALL_OFFER:
                if (isGroupCall && webRtcManager != null && pkt.senderId != null
                        && !pkt.senderId.equals(myDeviceId) && pkt.sdp != null) {
                    webRtcManager.acceptIncomingCall(callId, pkt.senderId, callType, pkt.sdp);
                }
                break;
            case NetPacket.TYPE_CALL_ANSWER:
                if (!callConnected && binding != null) {
                    binding.callStatusText.setText(R.string.call_status_establishing_media);
                }
                if (webRtcManager != null && pkt.senderId != null) {
                    webRtcManager.handleRemoteAnswer(pkt.senderId, pkt.sdp);
                }
                break;
            case NetPacket.TYPE_CALL_ICE:
                if (webRtcManager != null && pkt.senderId != null) {
                    webRtcManager.handleRemoteIce(pkt.senderId, pkt.iceCandidate, pkt.iceSdpMid, pkt.iceSdpMLineIndex);
                }
                break;
            case NetPacket.TYPE_CALL_STATE:
                if (!isGroupCall) {
                    remotePeerMuted = pkt.muted;
                    remotePeerCameraOff = !pkt.videoEnabled;
                    updateRemoteMediaStatusBanner();
                }
                break;
            case NetPacket.TYPE_CALL_END:
                stopIncomingAlert();
                timerHandler.removeCallbacks(noAnswerTimeoutRunnable);
                if (!isGroupCall) {
                    finalStatus = callConnected ? "COMPLETED" : "MISSED";
                    if (binding != null) binding.callStatusText.setText(R.string.call_status_ended);
                    finish();
                } else if (pkt.senderId != null) {
                    removeGroupParticipantResources(pkt.senderId);
                    refreshGroupParticipantsStrip();
                }
                break;
            case NetPacket.TYPE_GROUP_CALL_INVITE:
            case NetPacket.TYPE_GROUP_CALL_JOIN:
                if (isGroupCall) {
                    refreshGroupParticipantsStrip();
                    if (webRtcManager != null && pkt.senderId != null
                            && !pkt.senderId.equals(myDeviceId)
                            && !webRtcManager.hasPeer(pkt.senderId)
                            && myDeviceId.compareTo(pkt.senderId) < 0) {
                        webRtcManager.startOutgoingCall(callId, pkt.senderId, callType);
                    }
                }
                break;
            case NetPacket.TYPE_GROUP_CALL_STATE:
                if (isGroupCall) refreshGroupParticipantsStrip();
                break;
            case NetPacket.TYPE_GROUP_CALL_LEAVE:
                if (isGroupCall && pkt.senderId != null) {
                    removeGroupParticipantResources(pkt.senderId);
                    refreshGroupParticipantsStrip();
                }
                break;
        }
    }

    @Override
    public void onRemoteVideoTrack(String remotePeerId, VideoTrack track) {
        runOnUiThread(() -> {
            if (binding == null || track == null || isFinishing() || isDestroyed()) return;
            if (isGroupCall) {
                attachGroupRemoteVideo(remotePeerId, track);
            } else {
                if (singleRemoteVideoTrack != null && singleRemoteVideoTrack != track) {
                    try {
                        singleRemoteVideoTrack.removeSink(binding.remoteVideoView);
                    } catch (Exception ignored) {
                    }
                }
                singleRemoteVideoTrack = track;
                singleRemoteVideoAttached = true;
                binding.remoteVideoView.setVisibility(View.VISIBLE);
                binding.callAvatar.setVisibility(View.GONE);
                track.addSink(binding.remoteVideoView);
            }
        });
    }

    private void attachGroupRemoteVideo(String remotePeerId, VideoTrack track) {
        if (binding == null || webRtcManager == null) return;
        binding.groupVideoScroll.setVisibility(View.VISIBLE);
        GroupVideoTile tile = groupVideoTiles.get(remotePeerId);
        if (tile == null) {
            FrameLayout container = new FrameLayout(this);
            container.setBackgroundColor(Color.parseColor("#111B33"));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = Math.round(210 * getResources().getDisplayMetrics().density);
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            int margin = Math.round(4 * getResources().getDisplayMetrics().density);
            params.setMargins(margin, margin, margin, margin);
            container.setLayoutParams(params);

            SurfaceViewRenderer renderer = new SurfaceViewRenderer(this);
            renderer.init(webRtcManager.getEglContext(), null);
            renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
            renderer.setEnableHardwareScaler(true);
            container.addView(renderer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView label = new TextView(this);
            label.setText(resolveParticipantName(remotePeerId));
            label.setTextColor(Color.WHITE);
            label.setTextSize(12f);
            label.setBackgroundColor(Color.parseColor("#88000000"));
            label.setPadding(16, 6, 16, 6);
            FrameLayout.LayoutParams lblParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
            lblParams.setMargins(12, 12, 12, 12);
            container.addView(label, lblParams);

            binding.groupVideoGrid.addView(container);
            tile = new GroupVideoTile(container, renderer, label);
            groupVideoTiles.put(remotePeerId, tile);
        }
        if (tile.attachedTrack != null && tile.attachedTrack != track) {
            try {
                tile.attachedTrack.removeSink(tile.renderer);
            } catch (Exception ignored) {
            }
        }
        tile.attachedTrack = track;
        track.addSink(tile.renderer);
        tile.label.setText(resolveParticipantName(remotePeerId));
    }

    private String resolveParticipantName(String remotePeerId) {
        if (isGroupCall) {
            PeerConnectionManager.GroupCallRoom room = locator.getPeerConnectionManager().getActiveGroupCallForGroup(groupId);
            if (room != null && room.participants.containsKey(remotePeerId)) {
                NetPacket.GroupCallParticipantDto dto = room.participants.get(remotePeerId);
                if (dto != null && dto.displayName != null) return dto.displayName;
            }
        }
        return remotePeerId.length() > 8 ? remotePeerId.substring(0, 8) : remotePeerId;
    }

    private void removeGroupParticipantResources(String remotePeerId) {
        connectedPeers.remove(remotePeerId);
        if (webRtcManager != null) {
            webRtcManager.removePeer(remotePeerId);
        }
        GroupVideoTile tile = groupVideoTiles.remove(remotePeerId);
        if (tile != null) {
            if (tile.attachedTrack != null) {
                try {
                    tile.attachedTrack.removeSink(tile.renderer);
                } catch (Exception ignored) {
                }
            }
            try {
                tile.renderer.release();
            } catch (Exception ignored) {
            }
            if (binding != null) {
                binding.groupVideoGrid.removeView(tile.container);
            }
        }
        if (isGroupCall && binding != null && connectedPeers.isEmpty()) {
            binding.callStatusText.setText(R.string.call_status_waiting_participant);
        }
    }

    @Override
    public void onRemoteAudioTrack(String remotePeerId, boolean available) {
        // Explicit remote audio track confirmation; audio renders via JavaAudioDeviceModule
    }

    @Override
    public void onPeerConnecting(String remotePeerId) {
        runOnUiThread(() -> {
            if (binding == null) return;
            if (!callConnected) {
                binding.callStatusText.setText(R.string.call_status_establishing_media);
            }
        });
    }

    @Override
    public void onPeerConnected(String remotePeerId) {
        runOnUiThread(() -> {
            if (binding == null) return;
            timerHandler.removeCallbacks(noAnswerTimeoutRunnable);
            connectedPeers.add(remotePeerId);
            if (!callConnected) {
                callConnected = true;
                connectedAtMs = System.currentTimeMillis();
                timerHandler.post(timerRunnable);
            }
            finalStatus = "COMPLETED";
            if (isGroupCall) {
                binding.callStatusText.setText(getString(R.string.call_status_connected_group,
                        connectedPeers.size() + 1, PeerConnectionManager.MAX_GROUP_CALL_PARTICIPANTS));
                refreshGroupParticipantsStrip();
            } else {
                binding.callStatusText.setText(R.string.call_status_connected);
            }
        });
    }

    @Override
    public void onPeerReconnecting(String remotePeerId) {
        runOnUiThread(() -> {
            if (binding == null) return;
            if (!isGroupCall || connectedPeers.size() <= 1) {
                binding.callStatusText.setText(R.string.call_status_reconnecting);
            }
        });
    }

    @Override
    public void onPeerFailed(String remotePeerId, String reason) {
        runOnUiThread(() -> {
            if (binding == null) return;
            connectedPeers.remove(remotePeerId);
            if (!isGroupCall) {
                binding.callStatusText.setText(R.string.call_status_failed);
                Toast.makeText(this, reason != null ? reason : getString(R.string.call_status_failed), Toast.LENGTH_LONG).show();
                finalStatus = callConnected ? "COMPLETED" : "FAILED";
                finish();
            } else {
                removeGroupParticipantResources(remotePeerId);
                refreshGroupParticipantsStrip();
            }
        });
    }

    @Override
    public void onPeerDisconnected(String remotePeerId) {
        runOnUiThread(() -> {
            if (!isGroupCall) {
                finalStatus = callConnected ? "COMPLETED" : "MISSED";
                if (binding != null) binding.callStatusText.setText(R.string.call_status_ended);
                finish();
            } else {
                removeGroupParticipantResources(remotePeerId);
                refreshGroupParticipantsStrip();
            }
        });
    }

    @Override
    public void onMediaError(String message) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed() && message != null) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void sendEndSignal(boolean isExplicitReject) {
        if (endSignalSent) return;
        endSignalSent = true;
        locator.getPeerConnectionManager().markCallTerminated(callId);
        locator.getNotificationHelper().clearActiveIncomingCallIfMatches(callId);
        if (isGroupCall) {
            locator.getPeerConnectionManager().leaveGroupCall(callId, groupId);
            return;
        }
        if (peerId == null) return;
        NetPacket end = locator.getPeerConnectionManager().buildBasePacket(
                isExplicitReject ? NetPacket.TYPE_CALL_REJECT : NetPacket.TYPE_CALL_END);
        end.targetId = peerId;
        end.callId = callId;
        locator.getPeerConnectionManager().sendCallPacketToPeer(peerId, end);
    }

    @Override
    protected void onDestroy() {
        stopIncomingAlert();
        timerHandler.removeCallbacks(timerRunnable);
        timerHandler.removeCallbacks(noAnswerTimeoutRunnable);
        locator.getPeerConnectionManager().setCallSignalListener(null);
        if (!endSignalSent && isFinishing()) {
            sendEndSignal(false);
        }
        CallForegroundService.stop(this);

        long durationSec = callConnected ? (System.currentTimeMillis() - connectedAtMs) / 1000L : 0L;
        String dir = isIncoming ? (callConnected ? "INCOMING" : "MISSED") : "OUTGOING";
        String logPeerId = isGroupCall ? groupId : peerId;
        String logPeerName = isGroupCall && groupName != null ? groupName : (peerName != null ? peerName : "Peer");
        if (logPeerId != null) {
            AppDatabase.DB_EXECUTOR.execute(() -> locator.getDatabase().callLogDao().upsert(
                    new CallLogEntity(callId, logPeerId, logPeerName,
                            peerIp != null ? peerIp : "", callType, dir, finalStatus, durationSec, System.currentTimeMillis())
            ));
        }

        if (singleRemoteVideoTrack != null && singleRemoteVideoAttached) {
            try {
                singleRemoteVideoTrack.removeSink(binding.remoteVideoView);
            } catch (Exception ignored) {
            }
            singleRemoteVideoTrack = null;
        }
        for (GroupVideoTile tile : groupVideoTiles.values()) {
            if (tile.attachedTrack != null) {
                try {
                    tile.attachedTrack.removeSink(tile.renderer);
                } catch (Exception ignored) {
                }
            }
            try {
                tile.renderer.release();
            } catch (Exception ignored) {
            }
        }
        groupVideoTiles.clear();

        if (webRtcManager != null) {
            try {
                binding.localVideoView.release();
                binding.remoteVideoView.release();
            } catch (Exception ignored) {
            }
            webRtcManager.releaseAll();
            webRtcManager = null;
        }
        super.onDestroy();
    }
}
