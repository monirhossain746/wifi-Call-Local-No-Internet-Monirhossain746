package com.android.wificalllocal;

import android.content.Context;

import com.android.wificalllocal.ads.AdConsentManager;
import com.android.wificalllocal.control.AppControlManager;
import com.android.wificalllocal.data.local.AppDatabase;
import com.android.wificalllocal.data.local.entity.ChatEntity;
import com.android.wificalllocal.data.local.entity.GroupEntity;
import com.android.wificalllocal.files.FileTransferManager;
import com.android.wificalllocal.network.DiscoveryManager;
import com.android.wificalllocal.network.LocalHttpServer;
import com.android.wificalllocal.network.PeerConnectionManager;
import com.android.wificalllocal.security.CryptoManager;
import com.android.wificalllocal.storage.CacheManager;
import com.android.wificalllocal.util.NotificationHelper;
import com.android.wificalllocal.util.PreferencesManager;

import java.io.IOException;

/**
 * Manual dependency injection container (ServiceLocator pattern, no Hilt/Koin).
 */
public class ServiceLocator {

    private static volatile ServiceLocator INSTANCE;

    private final Context appContext;
    private final PreferencesManager preferencesManager;
    private final AppDatabase database;
    private final CryptoManager cryptoManager;
    private final NotificationHelper notificationHelper;
    private final FileTransferManager fileTransferManager;
    private final PeerConnectionManager peerConnectionManager;
    private final DiscoveryManager discoveryManager;
    private final AdConsentManager adConsentManager;
    private final AppControlManager appControlManager;
    private final CacheManager cacheManager;
    private LocalHttpServer localHttpServer;

    private ServiceLocator(Context context) {
        this.appContext = context.getApplicationContext();
        this.preferencesManager = new PreferencesManager(appContext);
        this.database = AppDatabase.build(appContext);
        this.cryptoManager = new CryptoManager();
        this.notificationHelper = new NotificationHelper(appContext);
        this.fileTransferManager = new FileTransferManager(appContext, database);
        this.peerConnectionManager = new PeerConnectionManager(
                appContext, database, preferencesManager, cryptoManager, fileTransferManager, notificationHelper);
        this.discoveryManager = new DiscoveryManager(appContext, database, preferencesManager, peerConnectionManager);
        this.adConsentManager = new AdConsentManager();
        this.appControlManager = new AppControlManager(appContext);
        this.cacheManager = new CacheManager(appContext, database);
        this.peerConnectionManager.attachAppControlManager(this.appControlManager);

        seedEveryoneRoom();
    }

    public static ServiceLocator getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (ServiceLocator.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ServiceLocator(context);
                }
            }
        }
        return INSTANCE;
    }

    private void seedEveryoneRoom() {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            GroupEntity existing = database.groupDao().getGroupSync(AppDatabase.EVERYONE_ROOM_ID);
            if (existing == null) {
                long now = System.currentTimeMillis();
                GroupEntity room = new GroupEntity(
                        AppDatabase.EVERYONE_ROOM_ID,
                        "Everyone Room",
                        "Public local room for all peers on this network",
                        "", false, "", "",
                        "system", "System", false,
                        true, false, "", 1, 1, 1, now
                );
                database.groupDao().upsertGroup(room);
                ChatEntity chat = new ChatEntity(
                        AppDatabase.EVERYONE_ROOM_ID,
                        "Everyone Room",
                        true, null, null,
                        "Public local room ready",
                        now, 0, false, true
                );
                database.chatDao().upsert(chat);
            }
        });
    }

    public synchronized void ensureServerStarted() {
        int port = preferencesManager.getServerPort();
        if (localHttpServer != null && localHttpServer.isListening() && localHttpServer.getListeningPort() == port) {
            return;
        }
        restartServer();
    }

    public synchronized void restartServer() {
        if (localHttpServer != null) {
            localHttpServer.stop();
            localHttpServer = null;
        }
        int port = preferencesManager.getServerPort();
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                LocalHttpServer srv = new LocalHttpServer(
                        appContext, port + attempt, database, fileTransferManager, peerConnectionManager);
                srv.start(5000, true);
                if (attempt > 0) {
                    preferencesManager.setServerPort(port + attempt);
                }
                localHttpServer = srv;
                peerConnectionManager.attachServer(srv);
                return;
            } catch (IOException ignored) {
            }
        }
    }

    public PreferencesManager getPreferencesManager() { return preferencesManager; }
    public AppDatabase getDatabase() { return database; }
    public CryptoManager getCryptoManager() { return cryptoManager; }
    public NotificationHelper getNotificationHelper() { return notificationHelper; }
    public FileTransferManager getFileTransferManager() { return fileTransferManager; }
    public PeerConnectionManager getPeerConnectionManager() { return peerConnectionManager; }
    public DiscoveryManager getDiscoveryManager() { return discoveryManager; }
    public AdConsentManager getAdConsentManager() { return adConsentManager; }
    public AppControlManager getAppControlManager() { return appControlManager; }
    public CacheManager getCacheManager() { return cacheManager; }
    public LocalHttpServer getLocalHttpServer() { return localHttpServer; }
}
