package com.android.wificalllocal;

import android.content.Context;

import androidx.room.Room;

import com.android.wificalllocal.control.AppControlManager;
import com.android.wificalllocal.control.RemoteConfigManager;
import com.android.wificalllocal.data.local.AppDatabase;
import com.android.wificalllocal.data.local.entity.FileTransferEntity;
import com.android.wificalllocal.data.local.entity.MessageEntity;
import com.android.wificalllocal.storage.CacheManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies CacheManager LRU & Clear All Cache safety, all 14 Remote Config parameter persistence,
 * optional vs force update & feature flags in AppControlManager, and Room v4 MessageDao editMessage.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class FeatureAndUpgradeVerificationTest {

    @Test
    public void remoteConfigPersistsAll14ParametersAndEvaluatesFlagsCorrectly() {
        Context ctx = RuntimeEnvironment.getApplication();
        RemoteConfigManager rcm = new RemoteConfigManager(ctx);

        RemoteConfigManager.ConfigSnapshot snap = new RemoteConfigManager.ConfigSnapshot(
                true,
                true,
                "Scheduled LAN maintenance window",
                "1.4.0",
                BuildConfig.VERSION_CODE + 3L,
                "https://github.com/monirhossain746/wifi-Call-Local-No-Internet-Monirhossain746/releases",
                false,
                false,
                250L,
                true,
                false,
                false,
                true,
                true,
                "Optional performance upgrade",
                true,
                123456789L
        );
        rcm.persistSnapshot(snap);

        RemoteConfigManager.ConfigSnapshot restored = rcm.getCachedSnapshot();
        assertTrue(restored.isAppEnabled);
        assertTrue(restored.maintenanceMode);
        assertEquals("Scheduled LAN maintenance window", restored.maintenanceMessage);
        assertEquals("1.4.0", restored.latestVersionName);
        assertEquals(BuildConfig.VERSION_CODE + 3L, restored.latestVersionCode);
        assertFalse(restored.forceUpdate);
        assertFalse(restored.adsEnabled);
        assertEquals(250L, restored.maxCacheSizeMb);
        assertTrue(restored.onlineServicesEnabled);
        assertFalse(restored.groupCallsEnabled);
        assertFalse(restored.fileTransferEnabled);
        assertTrue(restored.publicGroupsEnabled);
        assertTrue(restored.everyoneGroupEnabled);

        AppControlManager.AppGateState gate = AppControlManager.evaluate(restored, BuildConfig.VERSION_CODE);
        assertEquals(AppControlManager.AccessDecision.ALLOWED, gate.decision);
        assertTrue(gate.maintenanceMode);
        assertTrue(gate.isOptionalUpdateAvailable(BuildConfig.VERSION_CODE));
        assertFalse(gate.adsEnabled);
        assertFalse(gate.groupCallsEnabled);
        assertFalse(gate.fileTransferEnabled);
        assertEquals(250L, gate.maxCacheSizeMb);

        // Clean up prefs for other tests
        rcm.persistSnapshot(new RemoteConfigManager.ConfigSnapshot(
                true, BuildConfig.VERSION_CODE, "", "", false));
    }

    @Test
    public void cacheManagerClearAllCachePreservesDatabasesPrefsAndActiveTransfers() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        AppDatabase db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase.class)
                .allowMainThreadQueries()
                .build();
        try {
            FileTransferEntity activeTransfer = new FileTransferEntity(
                    "active-xfer-1",
                    "msg-1",
                    "everyone_room",
                    "video.mp4",
                    "video/mp4",
                    2048L,
                    1024,
                    2,
                    1,
                    "deadbeef",
                    "[]",
                    null,
                    null,
                    "DOWNLOAD",
                    "TRANSFERRING",
                    1024L,
                    "{}",
                    1000L,
                    1000L
            );
            db.fileTransferDao().upsert(activeTransfer);

            File cacheJunk = new File(ctx.getCacheDir(), "temp_preview.tmp");
            try (FileOutputStream fos = new FileOutputStream(cacheJunk)) {
                fos.write(new byte[4096]);
            }

            File chunkRoot = new File(ctx.getFilesDir(), "transfer_chunks");
            File activeDir = new File(chunkRoot, "active-xfer-1");
            File completedDir = new File(chunkRoot, "done-xfer-2");
            activeDir.mkdirs();
            completedDir.mkdirs();

            File activeChunk = new File(activeDir, "0.part");
            File completedChunk = new File(completedDir, "0.part");
            try (FileOutputStream fos = new FileOutputStream(activeChunk)) {
                fos.write(new byte[1024]);
            }
            try (FileOutputStream fos = new FileOutputStream(completedChunk)) {
                fos.write(new byte[2048]);
            }

            // Protected files that CacheManager must never remove
            File dbFolder = new File(ctx.getApplicationInfo().dataDir, "databases");
            dbFolder.mkdirs();
            File sentinelDb = new File(dbFolder, "sentinel.db");
            try (FileOutputStream fos = new FileOutputStream(sentinelDb)) {
                fos.write("keep-me".getBytes(StandardCharsets.UTF_8));
            }

            CacheManager cm = new CacheManager(ctx, db);
            CacheManager.ClearResult result = cm.clearAllCacheSync(500L);

            assertTrue(result.freedBytes >= 4096L + 2048L);
            assertFalse("Temp cache file should be deleted", cacheJunk.exists());
            assertFalse("Completed transfer chunk should be cleared", completedChunk.exists());
            assertTrue("Active transfer chunk must be preserved", activeChunk.exists());
            assertTrue("Databases directory must never be deleted", sentinelDb.exists());
            assertTrue(result.fullyUnderLimit);
        } finally {
            db.close();
        }
    }

    @Test
    public void messageDaoEditMessageMarksEditedAndUpdatesContent() {
        Context ctx = RuntimeEnvironment.getApplication();
        AppDatabase db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase.class)
                .allowMainThreadQueries()
                .build();
        try {
            MessageEntity msg = new MessageEntity(
                    "msg-edit-1",
                    "everyone_room",
                    "dev-1",
                    "Alice",
                    false,
                    "TEXT",
                    "Original message",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0L,
                    null,
                    null,
                    "SENT",
                    100L,
                    1,
                    100L,
                    false,
                    false,
                    "{}",
                    false
            );
            db.messageDao().upsert(msg);

            long editedAt = 999999L;
            db.messageDao().editMessage("msg-edit-1", "Updated message text", editedAt);

            MessageEntity reloaded = db.messageDao().getByIdSync("msg-edit-1");
            assertNotNull(reloaded);
            assertEquals("Updated message text", reloaded.content);
            assertTrue(reloaded.isEdited);
            assertEquals(editedAt, reloaded.editedAt);
        } finally {
            db.close();
        }
    }

    @Test
    public void firebaseInitializesWithOfficialProjectIdAndPackageName() {
        Context ctx = RuntimeEnvironment.getApplication();
        assertEquals("com.android.wificalllocal", ctx.getPackageName());

        RemoteConfigManager rcm = new RemoteConfigManager(ctx);
        assertTrue("FirebaseApp should initialize from app/google-services.json", rcm.initialize());
        assertTrue(rcm.isFirebaseConfigured());
        assertEquals("wifi-call-local-1",
                com.google.firebase.FirebaseApp.getInstance().getOptions().getProjectId());
        assertEquals("Initialized (FirebaseApp active)", rcm.getFirebaseInitStatusLabel());
    }
}

