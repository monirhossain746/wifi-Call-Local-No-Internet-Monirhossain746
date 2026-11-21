package com.android.wificalllocal.network;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.android.wificalllocal.data.local.AppDatabase;
import com.android.wificalllocal.data.local.entity.PeerEntity;
import com.android.wificalllocal.data.local.entity.UserProfileEntity;
import com.android.wificalllocal.util.PreferencesManager;
import com.google.gson.Gson;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Parallel discovery engine supporting:
 * 1. NsdManager / mDNS (_wificalllocal._tcp)
 * 2. UDP LAN Broadcast Fallback + WifiManager.MulticastLock
 * 3. WiFi Direct (WifiP2pManager)
 * 4. LocalOnlyHotspot (where supported by Android OS)
 */
public class DiscoveryManager {

    public static final String SERVICE_TYPE = "_wificalllocal._tcp.";
    private static final int UDP_DISCOVERY_PORT = 45678;

    private final Context context;
    private final AppDatabase db;
    private final PreferencesManager prefs;
    private final PeerConnectionManager peerManager;
    private final Gson gson = new Gson();

    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    private NsdManager.DiscoveryListener discoveryListener;
    private WifiManager.MulticastLock multicastLock;
    private WifiManager.LocalOnlyHotspotReservation hotspotReservation;

    private WifiP2pManager wifiP2pManager;
    private WifiP2pManager.Channel wifiP2pChannel;
    private BroadcastReceiver p2pReceiver;

    private DatagramSocket udpSocket;
    private ExecutorService udpExecutor;
    private volatile boolean running = false;

    private final MutableLiveData<String> hotspotInfoLive = new MutableLiveData<>("");
    private final MutableLiveData<String> statusTextLive = new MutableLiveData<>("Idle");
    private final MutableLiveData<List<WifiP2pDevice>> p2pPeersLive = new MutableLiveData<>(new ArrayList<>());

    public DiscoveryManager(Context context, AppDatabase db, PreferencesManager prefs,
                            PeerConnectionManager peerManager) {
        this.context = context.getApplicationContext();
        this.db = db;
        this.prefs = prefs;
        this.peerManager = peerManager;
    }

    public LiveData<String> getHotspotInfoLive() {
        return hotspotInfoLive;
    }

    public LiveData<String> getStatusTextLive() {
        return statusTextLive;
    }

    public LiveData<List<WifiP2pDevice>> getP2pPeersLive() {
        return p2pPeersLive;
    }

    public synchronized void startDiscovery() {
        if (running) {
            broadcastUdpPresenceOnce();
            return;
        }
        running = true;
        String mode = prefs.getConnectionMode();

        acquireMulticastLock();
        if (PreferencesManager.MODE_AUTO.equals(mode) || PreferencesManager.MODE_WIFI.equals(mode)
                || PreferencesManager.MODE_HOTSPOT.equals(mode)) {
            startNsd();
            startUdpDiscovery();
        }
        if (PreferencesManager.MODE_AUTO.equals(mode) || PreferencesManager.MODE_WIFI_DIRECT.equals(mode)) {
            startWifiDirectDiscovery();
        }
        updateRouteStatus();
    }

    public synchronized void stopDiscovery() {
        running = false;
        stopNsd();
        stopUdpDiscovery();
        stopWifiDirect();
        releaseMulticastLock();
        stopLocalHotspot();
    }

    public void triggerManualScan() {
        updateRouteStatus();
        if (!running) {
            startDiscovery();
        } else {
            broadcastUdpPresenceOnce();
            discoverP2pPeers();
        }
    }

    public void updateRouteStatus() {
        NetworkUtils.LocalNetworkInfo net = NetworkUtils.detectLocalNetwork(context);
        if (net.isValid()) {
            String label;
            switch (net.routeType) {
                case "HOTSPOT":
                    label = "Hotspot";
                    break;
                case "WIFI_DIRECT":
                    label = "WiFi Direct";
                    break;
                default:
                    label = "WiFi";
                    break;
            }
            statusTextLive.postValue("Auto: connected via " + label);
        } else {
            statusTextLive.postValue("Waiting for WiFi / Hotspot / WiFi Direct");
        }
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wm != null && multicastLock == null) {
                multicastLock = wm.createMulticastLock("WifiCallLocalMulticast");
                multicastLock.setReferenceCounted(true);
                multicastLock.acquire();
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (Exception ignored) {
        }
        multicastLock = null;
    }

    private void startNsd() {
        try {
            nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            if (nsdManager == null) return;

            String myId = prefs.getOrCreateDeviceId();
            String shortId = myId.length() > 6 ? myId.substring(0, 6) : myId;

            NsdServiceInfo serviceInfo = new NsdServiceInfo();
            serviceInfo.setServiceName("WCL-" + shortId);
            serviceInfo.setServiceType(SERVICE_TYPE);
            serviceInfo.setPort(prefs.getServerPort());

            registrationListener = new NsdManager.RegistrationListener() {
                @Override
                public void onServiceRegistered(NsdServiceInfo info) { }
                @Override
                public void onRegistrationFailed(NsdServiceInfo info, int errorCode) { }
                @Override
                public void onServiceUnregistered(NsdServiceInfo info) { }
                @Override
                public void onUnregistrationFailed(NsdServiceInfo info, int errorCode) { }
            };
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);

            discoveryListener = new NsdManager.DiscoveryListener() {
                @Override
                public void onDiscoveryStarted(String regType) { }

                @Override
                public void onServiceFound(NsdServiceInfo service) {
                    if (!service.getServiceType().contains("_wificalllocal")) return;
                    if (service.getServiceName().equals("WCL-" + shortId)) return;
                    try {
                        nsdManager.resolveService(service, new NsdManager.ResolveListener() {
                            @Override
                            public void onResolveFailed(NsdServiceInfo info, int errorCode) { }

                            @Override
                            public void onServiceResolved(NsdServiceInfo resolved) {
                                InetAddress host = resolved.getHost();
                                if (host != null && host.getHostAddress() != null) {
                                    peerManager.connectToPeer(host.getHostAddress(), resolved.getPort(), null);
                                }
                            }
                        });
                    } catch (Exception ignored) {
                    }
                }

                @Override
                public void onServiceLost(NsdServiceInfo service) { }
                @Override
                public void onDiscoveryStopped(String serviceType) { }
                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) { }
                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) { }
            };
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception ignored) {
        }
    }

    private void stopNsd() {
        try {
            if (nsdManager != null && registrationListener != null) {
                nsdManager.unregisterService(registrationListener);
            }
        } catch (Exception ignored) {
        }
        try {
            if (nsdManager != null && discoveryListener != null) {
                nsdManager.stopServiceDiscovery(discoveryListener);
            }
        } catch (Exception ignored) {
        }
        registrationListener = null;
        discoveryListener = null;
    }

    private void startUdpDiscovery() {
        udpExecutor = Executors.newFixedThreadPool(2);
        udpExecutor.execute(() -> {
            try {
                udpSocket = new DatagramSocket(UDP_DISCOVERY_PORT);
                udpSocket.setBroadcast(true);
                byte[] buf = new byte[2048];
                while (running && !udpSocket.isClosed()) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    udpSocket.receive(packet);
                    String json = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    handleUdpAnnounce(json, packet.getAddress().getHostAddress());
                }
            } catch (Exception ignored) {
            }
        });

        udpExecutor.execute(() -> {
            while (running) {
                broadcastUdpPresenceOnce();
                try {
                    Thread.sleep(6000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
    }

    public void broadcastUdpPresenceOnce() {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                NetworkUtils.LocalNetworkInfo net = NetworkUtils.detectLocalNetwork(context);
                if (!net.isValid()) return;
                UserProfileEntity me = db.userProfileDao().getProfileSync();
                Map<String, Object> msg = new HashMap<>();
                msg.put("deviceId", prefs.getOrCreateDeviceId());
                msg.put("name", me != null ? me.name : "Peer");
                msg.put("bio", me != null ? me.bio : "");
                msg.put("ip", net.ipAddress);
                msg.put("port", prefs.getServerPort());
                msg.put("mode", net.routeType);
                msg.put("status", prefs.getActiveStatus());

                byte[] bytes = gson.toJson(msg).getBytes(StandardCharsets.UTF_8);
                try (DatagramSocket sock = new DatagramSocket()) {
                    sock.setBroadcast(true);
                    DatagramPacket dp = new DatagramPacket(bytes, bytes.length,
                            InetAddress.getByName("255.255.255.255"), UDP_DISCOVERY_PORT);
                    sock.send(dp);
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void handleUdpAnnounce(String json, String remoteIp) {
        try {
            Map<?, ?> map = gson.fromJson(json, Map.class);
            if (map == null || map.get("deviceId") == null) return;
            String deviceId = String.valueOf(map.get("deviceId"));
            if (deviceId.equals(prefs.getOrCreateDeviceId())) return;

            String name = map.get("name") != null ? String.valueOf(map.get("name")) : "Peer";
            String bio = map.get("bio") != null ? String.valueOf(map.get("bio")) : "";
            int port = map.get("port") instanceof Number ? ((Number) map.get("port")).intValue() : 8080;
            if (!NetworkUtils.isPrivateLanIp(remoteIp) || !NetworkUtils.isValidPort(port)) return;
            String mode = map.get("mode") != null ? String.valueOf(map.get("mode")) : "WIFI";
            String status = map.get("status") != null ? String.valueOf(map.get("status")) : "ONLINE";
            long now = System.currentTimeMillis();

            PeerEntity existing = db.peerDao().getByIdSync(deviceId);
            if (existing != null && existing.isBlocked) return;

            PeerEntity peer = new PeerEntity(deviceId, name, bio, remoteIp, port, mode, status,
                    true, existing != null && existing.isFriend, false, false,
                    existing != null ? existing.avatarBase64 : "", now, now);
            db.peerDao().upsert(peer);

            if (prefs.isAutoConnect()) {
                peerManager.connectToPeer(remoteIp, port, deviceId);
            }
        } catch (Exception ignored) {
        }
    }

    private void stopUdpDiscovery() {
        try {
            if (udpSocket != null && !udpSocket.isClosed()) udpSocket.close();
        } catch (Exception ignored) {
        }
        if (udpExecutor != null) {
            udpExecutor.shutdownNow();
            udpExecutor = null;
        }
    }

    @SuppressLint("MissingPermission")
    private void startWifiDirectDiscovery() {
        try {
            wifiP2pManager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
            if (wifiP2pManager == null) return;
            wifiP2pChannel = wifiP2pManager.initialize(context, Looper.getMainLooper(), null);

            IntentFilter filter = new IntentFilter();
            filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
            filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);

            p2pReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    String action = intent.getAction();
                    if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                        wifiP2pManager.requestPeers(wifiP2pChannel, (WifiP2pDeviceList peers) -> {
                            p2pPeersLive.postValue(new ArrayList<>(peers.getDeviceList()));
                        });
                    } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                        wifiP2pManager.requestConnectionInfo(wifiP2pChannel, (WifiP2pInfo info) -> {
                            if (info != null && info.groupFormed && info.groupOwnerAddress != null) {
                                updateRouteStatus();
                                if (!info.isGroupOwner) {
                                    peerManager.connectToPeer(info.groupOwnerAddress.getHostAddress(),
                                            prefs.getServerPort(), null);
                                }
                            }
                        });
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(p2pReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(p2pReceiver, filter);
            }
            discoverP2pPeers();
        } catch (Exception ignored) {
        }
    }

    @SuppressLint("MissingPermission")
    public void discoverP2pPeers() {
        try {
            if (wifiP2pManager != null && wifiP2pChannel != null) {
                wifiP2pManager.discoverPeers(wifiP2pChannel, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() { }
                    @Override
                    public void onFailure(int reason) { }
                });
            }
        } catch (Exception ignored) {
        }
    }

    @SuppressLint("MissingPermission")
    public void connectToWifiDirectDevice(WifiP2pDevice device, WifiP2pManager.ActionListener listener) {
        if (wifiP2pManager == null || wifiP2pChannel == null) return;
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        wifiP2pManager.connect(wifiP2pChannel, config, listener);
    }

    private void stopWifiDirect() {
        try {
            if (p2pReceiver != null) {
                context.unregisterReceiver(p2pReceiver);
                p2pReceiver = null;
            }
        } catch (Exception ignored) {
        }
    }

    @SuppressLint("MissingPermission")
    public void startLocalOnlyHotspot(HotspotCallback callback) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                callback.onError("WiFi service unavailable");
                return;
            }
            wm.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                    hotspotReservation = reservation;
                    String ssid = "";
                    String pass = "";
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && reservation.getSoftApConfiguration() != null) {
                        ssid = reservation.getSoftApConfiguration().getSsid();
                        pass = reservation.getSoftApConfiguration().getPassphrase();
                    } else if (reservation.getWifiConfiguration() != null) {
                        ssid = reservation.getWifiConfiguration().SSID;
                        pass = reservation.getWifiConfiguration().preSharedKey;
                    }
                    String info = "SSID: " + ssid + "\nPassword: " + pass;
                    hotspotInfoLive.postValue(info);
                    updateRouteStatus();
                    callback.onStarted(info);
                }

                @Override
                public void onFailed(int reason) {
                    callback.onError("LocalOnlyHotspot unavailable (code " + reason + "). Please enable your Phone Hotspot manually in Android Settings.");
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Exception e) {
            callback.onError("Please enable Phone Hotspot manually in Android Settings: " + e.getMessage());
        }
    }

    public void stopLocalHotspot() {
        if (hotspotReservation != null) {
            try {
                hotspotReservation.close();
            } catch (Exception ignored) {
            }
            hotspotReservation = null;
            hotspotInfoLive.postValue("");
        }
    }

    public interface HotspotCallback {
        void onStarted(String credentialsInfo);
        void onError(String message);
    }
}
