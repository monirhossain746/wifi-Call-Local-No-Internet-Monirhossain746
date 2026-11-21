package com.android.wificalllocal.service;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wificalllocal.ServiceLocator;
import com.android.wificalllocal.network.NetworkUtils;
import com.android.wificalllocal.util.NotificationHelper;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Long-running foreground service hosting the NanoHTTPD/WebSocket server,
 * mDNS/UDP/WiFi-Direct discovery, ConnectivityManager IP change listener, and peer heartbeat loop.
 * Uses connectedDevice | specialUse foreground service types (no dataSync).
 */
public class LocalMeshService extends Service {

    private ScheduledExecutorService statusUpdater;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile String lastKnownIp = "";

    public static void start(Context context) {
        Intent intent = new Intent(context, LocalMeshService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, LocalMeshService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ServiceLocator locator = ServiceLocator.getInstance(this);
        locator.ensureServerStarted();
        locator.getDiscoveryManager().startDiscovery();
        locator.getPeerConnectionManager().startHeartbeatLoop();

        NetworkUtils.LocalNetworkInfo initialNet = NetworkUtils.detectLocalNetwork(this);
        lastKnownIp = initialNet.ipAddress != null ? initialNet.ipAddress : "";

        startForegroundCompat(locator);
        registerNetworkChangeCallback();

        statusUpdater = Executors.newSingleThreadScheduledExecutor();
        statusUpdater.scheduleWithFixedDelay(() -> {
            try {
                ServiceLocator sl = ServiceLocator.getInstance(this);
                sl.getDiscoveryManager().updateRouteStatus();
                NetworkUtils.LocalNetworkInfo net = NetworkUtils.detectLocalNetwork(this);
                if (net.ipAddress != null && !net.ipAddress.equals(lastKnownIp)) {
                    lastKnownIp = net.ipAddress;
                    sl.getDiscoveryManager().triggerManualScan();
                    sl.getPeerConnectionManager().broadcastProfileUpdate();
                }
                int count = sl.getDatabase().peerDao().getOnlinePeersSync().size();
                Notification n = sl.getNotificationHelper().buildMeshForegroundNotification(
                        net.ipAddress, sl.getPreferencesManager().getServerPort(), count);
                android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
                if (nm != null) nm.notify(NotificationHelper.ID_MESH_SERVICE, n);
            } catch (Exception ignored) {
            }
        }, 5, 10, TimeUnit.SECONDS);
    }

    private void registerNetworkChangeCallback() {
        try {
            connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager == null) return;
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    handleNetworkChange();
                }

                @Override
                public void onLinkPropertiesChanged(@NonNull Network network, @NonNull LinkProperties linkProperties) {
                    handleNetworkChange();
                }

                @Override
                public void onLost(@NonNull Network network) {
                    handleNetworkChange();
                }
            };
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception ignored) {
        }
    }

    private void handleNetworkChange() {
        try {
            ServiceLocator sl = ServiceLocator.getInstance(this);
            sl.ensureServerStarted();
            sl.getDiscoveryManager().updateRouteStatus();
            sl.getDiscoveryManager().triggerManualScan();
            sl.getPeerConnectionManager().broadcastProfileUpdate();
            sl.getPeerConnectionManager().flushAllPendingMessages();
        } catch (Exception ignored) {
        }
    }

    private void startForegroundCompat(ServiceLocator locator) {
        NetworkUtils.LocalNetworkInfo net = NetworkUtils.detectLocalNetwork(this);
        Notification notification = locator.getNotificationHelper().buildMeshForegroundNotification(
                net.ipAddress, locator.getPreferencesManager().getServerPort(), 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NotificationHelper.ID_MESH_SERVICE, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NotificationHelper.ID_MESH_SERVICE, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            if (connectivityManager != null && networkCallback != null) {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            }
        } catch (Exception ignored) {
        }
        if (statusUpdater != null) statusUpdater.shutdownNow();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
