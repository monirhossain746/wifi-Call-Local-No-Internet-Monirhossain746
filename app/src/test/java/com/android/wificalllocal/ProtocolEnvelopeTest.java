package com.android.wificalllocal;

import android.content.Context;

import com.android.wificalllocal.data.local.AppDatabase;
import com.android.wificalllocal.files.FileTransferManager;
import com.android.wificalllocal.network.PeerConnectionManager;
import com.android.wificalllocal.network.model.NetPacket;
import com.android.wificalllocal.security.CryptoManager;
import com.android.wificalllocal.util.NotificationHelper;
import com.android.wificalllocal.util.PreferencesManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Verifies backward-compatible normalization between legacy flat NetPacket JSON
 * and versioned {version, type, event, messageId, senderId, timestamp, payload} JSON envelopes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class ProtocolEnvelopeTest {

    @Test
    public void testVersionedEnvelopeAndLegacyNormalization() {
        Context ctx = RuntimeEnvironment.getApplication();
        PreferencesManager prefs = new PreferencesManager(ctx);
        AppDatabase db = AppDatabase.build(ctx);
        PeerConnectionManager pcm = new PeerConnectionManager(
                ctx, db, prefs, new CryptoManager(), new FileTransferManager(ctx, db), new NotificationHelper(ctx));

        String envelopeJson = "{"
                + "\"version\":1,"
                + "\"event\":\"CALL_OFFER\","
                + "\"senderId\":\"web-abc123\","
                + "\"senderName\":\"Browser Peer\","
                + "\"timestamp\":1730000000000,"
                + "\"payload\":{"
                + "  \"targetId\":\"android-host\","
                + "  \"callId\":\"call-99\","
                + "  \"callType\":\"VIDEO\","
                + "  \"sdp\":\"v=0\\r\\n\""
                + "}"
                + "}";

        NetPacket normalized = pcm.normalizeIncomingPacket(envelopeJson);
        assertNotNull(normalized);
        assertEquals(1, normalized.version);
        assertEquals("CALL_OFFER", normalized.type);
        assertEquals("CALL_OFFER", normalized.event);
        assertEquals("android-host", normalized.targetId);
        assertEquals("call-99", normalized.callId);
        assertEquals("VIDEO", normalized.callType);
        assertEquals("v=0\r\n", normalized.sdp);

        String legacyFlatJson = "{\"type\":\"CHAT_MESSAGE\",\"senderId\":\"dev-1\",\"chatId\":\"everyone_room\"}";
        NetPacket legacy = pcm.normalizeIncomingPacket(legacyFlatJson);
        assertNotNull(legacy);
        assertEquals("CHAT_MESSAGE", legacy.type);
        assertEquals("CHAT_MESSAGE", legacy.event);
        assertEquals("everyone_room", legacy.chatId);
        db.close();
    }
}
