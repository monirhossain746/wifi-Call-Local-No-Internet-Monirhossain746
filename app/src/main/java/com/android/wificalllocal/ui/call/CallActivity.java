package com.android.wificalllocal.ui.call;

import android.Manifest;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
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
import android.view.View;
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
import com.android.wificalllocal.webrtc.WebRtcCallManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private boolean callConnected = false;
    private long connectedAtMs = 0L;
    private String finalStatus = "MISSED";
    private boolean endSignalSent = false;

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
                    finish();
                } else {
                    startCallEngine();
                }
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
        isVideoEnabled = isVideo;
        isSpeaker = isVideo;

        String titleName = isGroupCall && groupName != null && !groupName.isEmpty()
                ? groupName
                : (peerName != null ? peerName : "Local Peer");
        binding.callPeerName.setText(titleName);
        AvatarLoader.loadPeerAvatar(binding.callAvatar, titleName, isGroupCall ? groupId : peerId, null);
        binding.btnVideoToggle.setVisibility(isVideo ? View.VISIBLE : View.GONE);
        binding.btnFlipCamera.setVisibility(isVideo ? View.VISIBLE : View.GONE);
        binding.btnPip.setVisibility(isVideo ? View.VISIBLE : View.GONE);

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
            locator.getPeerConnectionManager().sendPacketToPeer(peerId, busy);
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

            if (!isGroupCall) {
                NetPacket ringing = locator.getPeerConnectionManager().buildBasePacket(NetPacket.TYPE_CALL_RINGING);
                ringing.targetId = peerId;
                ringing.callId = callId;
                locator.getPeerConnectionManager().sendPacketToPeer(peerId, ringing);
            }
        } else {
            binding.callStatusText.setText(isGroupCall ? R.string.call_status_connecting : R.string.call_status_ringing);
            checkMediaPermissionsAndStart();
            if (!isGroupCall) {
                timerHandler.postDelayed(noAnswerTimeoutRunnable, OUTGOING_NO_ANSWER_TIMEOUT_MS);
            }
        }
    }

    private void setupControls() {
        binding.btnAcceptCall.setOnClickListener(v -> {
            stopIncomingAlert();
            binding.incomingControls.setVisibility(View.GONE);
            binding.callControlsCard.setVisibility(View.VISIBLE);
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
        boolean isVideo = callType.contains("VIDEO");
        List<String> req = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            req.add(Manifest.permission.RECORD_AUDIO);
        }
        if (isVideo && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            req.add(Manifest.permission.CAMERA);
        }
        if (req.isEmpty()) {
            startCallEngine();
        } else {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.perm_rationale_title)
                    .setMessage(isVideo ? (getString(R.string.perm_microphone) + "\n\n" + getString(R.string.perm_camera))
                            : getString(R.string.perm_microphone))
                    .setPositiveButton(R.string.ok, (d, w) -> mediaPermLauncher.launch(req.toArray(new String[0])))
                    .setNegativeButton(R.string.cancel, (d, w) -> finish())
                    .show();
        }
    }

    private void startCallEngine() {
        boolean isVideo = callType.contains("VIDEO");
        String label = isGroupCall && groupName != null && !groupName.isEmpty() ? groupName : peerName;
        CallForegroundService.start(this, label, isVideo);
        webRtcManager = new WebRtcCallManager(this, locator.getPeerConnectionManager());
        if (isGroupCall) {
            webRtcManager.setGroupContext(callId, groupId, callType);
        }

        if (isVideo) {
            binding.localVideoView.init(webRtcManager.getEglContext(), null);
            binding.localVideoView.setMirror(true);
            binding.localVideoView.setZOrderMediaOverlay(true);
            binding.localVideoView.setVisibility(View.VISIBLE);

            binding.remoteVideoView.init(webRtcManager.getEglContext(), null);
            binding.remoteVideoView.setVisibility(View.VISIBLE);
        }

        webRtcManager.initEngine(isVideo, isVideo ? binding.localVideoView : null, this);
        webRtcManager.setSpeakerphoneOn(isSpeaker);

        binding.callStatusText.setText(R.string.call_status_connecting);

        if (isGroupCall) {
            callConnected = true;
            connectedAtMs = System.currentTimeMillis();
            finalStatus = "COMPLETED";
            timerHandler.post(timerRunnable);

            locator.getPeerConnectionManager().startOrJoinGroupCall(
                    callId, groupId, groupName, callType, isMuted, isVideoEnabled);

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
        } else if (isIncoming) {
            webRtcManager.acceptIncomingCall(callId, peerId, callType, offerSdp);
        } else {
            webRtcManager.startOutgoingCall(callId, peerId, callType);
        }
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
            idx++;
        }
        binding.callParticipantsStrip.setText(sb.toString());
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
        binding.callControlsCard.setVisibility(isInPictureInPictureMode ? View.GONE : View.VISIBLE);
        binding.callInfoContainer.setVisibility(isInPictureInPictureMode ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onCallSignal(NetPacket pkt) {
        if (pkt == null || pkt.callId == null || !pkt.callId.equals(callId)) return;
        switch (pkt.type) {
            case NetPacket.TYPE_CALL_RINGING:
                binding.callStatusText.setText(R.string.call_status_ringing);
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
                if (webRtcManager != null) webRtcManager.handleRemoteAnswer(pkt.senderId, pkt.sdp);
                break;
            case NetPacket.TYPE_CALL_ICE:
                if (webRtcManager != null) {
                    webRtcManager.handleRemoteIce(pkt.senderId, pkt.iceCandidate, pkt.iceSdpMid, pkt.iceSdpMLineIndex);
                }
                break;
            case NetPacket.TYPE_CALL_END:
                stopIncomingAlert();
                timerHandler.removeCallbacks(noAnswerTimeoutRunnable);
                if (!isGroupCall) {
                    finalStatus = callConnected ? "COMPLETED" : "MISSED";
                    finish();
                } else if (webRtcManager != null && pkt.senderId != null) {
                    webRtcManager.removePeer(pkt.senderId);
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
                if (isGroupCall) {
                    if (webRtcManager != null && pkt.senderId != null) {
                        webRtcManager.removePeer(pkt.senderId);
                    }
                    refreshGroupParticipantsStrip();
                }
                break;
        }
    }

    @Override
    public void onRemoteVideoTrack(String remotePeerId, VideoTrack track) {
        runOnUiThread(() -> {
            if (binding == null) return;
            binding.remoteVideoView.setVisibility(View.VISIBLE);
            track.addSink(binding.remoteVideoView);
        });
    }

    @Override
    public void onPeerConnected(String remotePeerId) {
        runOnUiThread(() -> {
            if (binding == null) return;
            timerHandler.removeCallbacks(noAnswerTimeoutRunnable);
            if (!callConnected) {
                callConnected = true;
                connectedAtMs = System.currentTimeMillis();
                timerHandler.post(timerRunnable);
            }
            finalStatus = "COMPLETED";
            binding.callStatusText.setText(R.string.call_status_connected);
            if (isGroupCall) refreshGroupParticipantsStrip();
        });
    }

    @Override
    public void onPeerDisconnected(String remotePeerId) {
        runOnUiThread(() -> {
            if (!isGroupCall) {
                finalStatus = callConnected ? "COMPLETED" : "MISSED";
                finish();
            } else {
                if (webRtcManager != null) webRtcManager.removePeer(remotePeerId);
                refreshGroupParticipantsStrip();
            }
        });
    }

    private void sendEndSignal(boolean isExplicitReject) {
        if (endSignalSent) return;
        endSignalSent = true;
        if (isGroupCall) {
            locator.getPeerConnectionManager().leaveGroupCall(callId, groupId);
            return;
        }
        if (peerId == null) return;
        NetPacket end = locator.getPeerConnectionManager().buildBasePacket(
                isExplicitReject ? NetPacket.TYPE_CALL_REJECT : NetPacket.TYPE_CALL_END);
        end.targetId = peerId;
        end.callId = callId;
        locator.getPeerConnectionManager().sendPacketToPeer(peerId, end);
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

        if (webRtcManager != null) {
            try {
                binding.localVideoView.release();
                binding.remoteVideoView.release();
            } catch (Exception ignored) {
            }
            webRtcManager.releaseAll();
        }
        super.onDestroy();
    }
}
