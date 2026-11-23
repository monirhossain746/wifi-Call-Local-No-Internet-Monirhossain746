package com.android.wificalllocal;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;

import androidx.room.Room;

import com.android.wificalllocal.data.local.AppDatabase;
import com.android.wificalllocal.data.local.entity.GroupEntity;
import com.android.wificalllocal.data.local.entity.GroupMemberEntity;
import com.android.wificalllocal.files.FileTransferManager;
import com.android.wificalllocal.network.CallSignalingBuffer;
import com.android.wificalllocal.network.PeerConnectionManager;
import com.android.wificalllocal.network.model.NetPacket;
import com.android.wificalllocal.security.CryptoManager;
import com.android.wificalllocal.util.NotificationHelper;
import com.android.wificalllocal.util.PreferencesManager;
import com.android.wificalllocal.webrtc.CallAudioRouter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Automated JVM / Robolectric verification for repaired call signaling buffering,
 * audio focus & routing state restoration, Private Group call authorization,
 * remote media state packets, and manifest hardware permissions.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class CallSignalingAndRoutingTest {

    @Test
    public void signalingBufferHandlesEarlySignalsAndRejectsTerminatedReplays() {
        CallSignalingBuffer buffer = new CallSignalingBuffer();
        long t0 = 1_000_000L;

        assertTrue(CallSignalingBuffer.isCallSignalingType(NetPacket.TYPE_CALL_STATE));
        assertTrue(CallSignalingBuffer.isCallSignalingType(NetPacket.TYPE_CALL_ICE));
        assertTrue(CallSignalingBuffer.isCallSignalingType(NetPacket.TYPE_GROUP_CALL_INVITE));
        assertFalse(CallSignalingBuffer.isCallSignalingType(NetPacket.TYPE_CHAT_MESSAGE));

        NetPacket earlyOffer = new NetPacket();
        earlyOffer.type = NetPacket.TYPE_CALL_OFFER;
        earlyOffer.callId = "call-101";

        NetPacket earlyIce = new NetPacket();
        earlyIce.type = NetPacket.TYPE_CALL_ICE;
        earlyIce.callId = "call-101";
        earlyIce.iceCandidate = "candidate:1 1 udp 2122260223 192.168.1.10 54321 typ host";

        buffer.bufferIncoming(earlyOffer, t0);
        buffer.bufferIncoming(earlyIce, t0 + 100);

        List<NetPacket> drained = buffer.drainIncomingForCall("call-101", t0 + 500);
        assertEquals(2, drained.size());
        assertEquals(NetPacket.TYPE_CALL_OFFER, drained.get(0).type);
        assertEquals(NetPacket.TYPE_CALL_ICE, drained.get(1).type);

        // Terminated calls must drop late/stale buffered signals
        buffer.markCallTerminated("call-101", t0 + 1000);
        assertTrue(buffer.isCallTerminated("call-101", t0 + 2000));
        buffer.bufferIncoming(earlyIce, t0 + 2000);
        assertTrue(buffer.drainIncomingForCall("call-101", t0 + 2500).isEmpty());
    }

    @Test
    public void outboundSignalingBufferPreservesFreshPacketsAndDropsExpiredOnes() {
        CallSignalingBuffer buffer = new CallSignalingBuffer();
        long t0 = 5_000_000L;

        NetPacket staleIce = new NetPacket();
        staleIce.type = NetPacket.TYPE_CALL_ICE;
        staleIce.callId = "call-202";
        buffer.enqueueOutbound("peer-a", staleIce, t0);

        NetPacket freshAnswer = new NetPacket();
        freshAnswer.type = NetPacket.TYPE_CALL_ANSWER;
        freshAnswer.callId = "call-202";
        buffer.enqueueOutbound("peer-a", freshAnswer, t0 + CallSignalingBuffer.OUTBOUND_SIGNAL_TTL_MS);

        List<NetPacket> flushed = buffer.drainOutbound("peer-a", t0 + CallSignalingBuffer.OUTBOUND_SIGNAL_TTL_MS + 1000L);
        assertEquals(1, flushed.size());
        assertEquals(NetPacket.TYPE_CALL_ANSWER, flushed.get(0).type);
        assertTrue(buffer.drainOutbound("peer-a", t0 + CallSignalingBuffer.OUTBOUND_SIGNAL_TTL_MS + 2000L).isEmpty());
    }

    @Test
    public void callAudioRouterUnmutesSystemMicSwitchesModeAndRestoresPriorState() {
        Context ctx = RuntimeEnvironment.getApplication();
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        assertNotNull(am);

        am.setMode(AudioManager.MODE_NORMAL);
        am.setSpeakerphoneOn(false);
        am.setMicrophoneMute(true);

        List<CallAudioRouter.AudioRoute> observedRoutes = new ArrayList<>();
        CallAudioRouter router = new CallAudioRouter(ctx);
        router.setRouteListener(observedRoutes::add);

        router.start(true);
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, am.getMode());
        assertFalse("CallAudioRouter must clear system mic mute on call start", am.isMicrophoneMute());
        assertEquals(CallAudioRouter.AudioRoute.SPEAKER, router.getCurrentRoute());

        router.setSpeakerphoneEnabled(false);
        assertEquals(CallAudioRouter.AudioRoute.EARPIECE, router.getCurrentRoute());

        router.stop();
        assertEquals(AudioManager.MODE_NORMAL, am.getMode());
        assertFalse(am.isSpeakerphoneOn());
        assertTrue("CallAudioRouter must restore prior mic mute state after call ends", am.isMicrophoneMute());
    }

    @Test
    public void privateGroupAuthorizationAndCallStateEnvelopeWorkCorrectly() {
        Context ctx = RuntimeEnvironment.getApplication();
        PreferencesManager prefs = new PreferencesManager(ctx);
        AppDatabase db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase.class)
                .allowMainThreadQueries()
                .build();
        try {
            PeerConnectionManager pcm = new PeerConnectionManager(
                    ctx, db, prefs, new CryptoManager(), new FileTransferManager(ctx, db), new NotificationHelper(ctx));

            GroupEntity privateGroup = new GroupEntity(
                    "priv-room-1",
                    "Secret Team",
                    "Private team",
                    "",
                    true,
                    "salt",
                    "pin-hash",
                    "owner-device",
                    "Owner",
                    false,
                    true,
                    false,
                    "enc-salt",
                    1,
                    2,
                    2,
                    1000L
            );
            db.groupDao().upsertGroup(privateGroup);
            db.groupDao().upsertMember(new GroupMemberEntity("priv-room-1", "member-1", "Member One", "MEMBER", true, 1000L));

            assertTrue(pcm.isPeerAuthorizedForGroup(privateGroup, "owner-device"));
            assertTrue(pcm.isPeerAuthorizedForGroup(privateGroup, "member-1"));
            assertFalse("Unauthorized outsider must not be permitted in Private Group calls",
                    pcm.isPeerAuthorizedForGroup(privateGroup, "outsider-x"));

            String callStateJson = "{"
                    + "\"version\":1,"
                    + "\"type\":\"CALL_STATE\","
                    + "\"event\":\"CALL_STATE\","
                    + "\"senderId\":\"peer-1\","
                    + "\"payload\":{"
                    + "  \"targetId\":\"my-device\","
                    + "  \"callId\":\"call-900\","
                    + "  \"muted\":true,"
                    + "  \"videoEnabled\":false"
                    + "}"
                    + "}";
            NetPacket statePkt = pcm.normalizeIncomingPacket(callStateJson);
            assertNotNull(statePkt);
            assertEquals(NetPacket.TYPE_CALL_STATE, statePkt.type);
            assertEquals("call-900", statePkt.callId);
            assertTrue(statePkt.muted);
            assertFalse(statePkt.videoEnabled);
        } finally {
            db.close();
        }
    }

    @Test
    public void manifestDeclaresRequiredCallAudioVideoAndBluetoothPermissions() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        PackageInfo pkgInfo = ctx.getPackageManager()
                .getPackageInfo(ctx.getPackageName(), PackageManager.GET_PERMISSIONS);
        List<String> perms = Arrays.asList(pkgInfo.requestedPermissions);

        assertTrue(perms.contains(Manifest.permission.RECORD_AUDIO));
        assertTrue(perms.contains(Manifest.permission.CAMERA));
        assertTrue(perms.contains(Manifest.permission.BLUETOOTH_CONNECT));
        assertTrue(perms.contains(Manifest.permission.MODIFY_AUDIO_SETTINGS));
    }
}
