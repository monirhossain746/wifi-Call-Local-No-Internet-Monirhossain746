package com.android.wificalllocal.network;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.android.wificalllocal.control.AppControlManager;
import com.android.wificalllocal.data.local.AppDatabase;
import com.android.wificalllocal.data.local.entity.CallLogEntity;
import com.android.wificalllocal.data.local.entity.ChatEntity;
import com.android.wificalllocal.data.local.entity.FileTransferEntity;
import com.android.wificalllocal.data.local.entity.GroupEntity;
import com.android.wificalllocal.data.local.entity.GroupMemberEntity;
import com.android.wificalllocal.data.local.entity.MessageEntity;
import com.android.wificalllocal.data.local.entity.PeerEntity;
import com.android.wificalllocal.data.local.entity.UserProfileEntity;
import com.android.wificalllocal.files.FileTransferManager;
import com.android.wificalllocal.network.model.NetPacket;
import com.android.wificalllocal.security.CryptoManager;
import com.android.wificalllocal.util.AvatarHelper;
import com.android.wificalllocal.util.HybridLogicalClock;
import com.android.wificalllocal.util.NotificationHelper;
import com.android.wificalllocal.util.PreferencesManager;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.lang.reflect.Type;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoWSD;

/**
 * Manages outbound WebSocket connections, inbound NanoWSD sockets, 5s heartbeats,
 * 15s offline detection, message deduplication, offline outbox flushing, HLC reconciliation,
 * group security & challenge-response, versioned JSON packet envelopes, browser/Android WebRTC
 * 1-to-1 + mesh group call signaling, and call history recording.
 */
public class PeerConnectionManager {

    private static final long HEARTBEAT_INTERVAL_SEC = 5L;
    private static final long OFFLINE_TIMEOUT_MS = 15_000L;
    public static final int MAX_GROUP_CALL_PARTICIPANTS = 6;

    public interface CallSignalListener {
        void onCallSignal(NetPacket packet);
    }

    public interface TypingListener {
        void onTyping(String chatId, String peerName, boolean isTyping);
    }

    public interface GroupAuthListener {
        void onGroupAuthResult(String groupId, boolean accepted, String error);
    }

    public interface ActiveGroupCallListener {
        void onActiveGroupCallsChanged();
    }

    public static class GroupCallRoom {
        public String callId;
        public String groupId;
        public String groupName;
        public String callType;
        public long startedAtMs;
        public final Map<String, NetPacket.GroupCallParticipantDto> participants = new ConcurrentHashMap<>();
    }

    private static class TokenBucket {
        double tokens = 60.0;
        long lastRefillMs = System.currentTimeMillis();
    }

    private static class RelayedCallRecord {
        final String callId;
        final String callerId;
        final String callerName;
        final String calleeId;
        final String callType;
        final long startedAtMs;
        long connectedAtMs = 0L;
        boolean connected = false;

        RelayedCallRecord(String callId, String callerId, String callerName, String calleeId, String callType, long startedAtMs) {
            this.callId = callId;
            this.callerId = callerId;
            this.callerName = callerName;
            this.calleeId = calleeId;
            this.callType = callType != null ? callType : "VOICE";
            this.startedAtMs = startedAtMs;
        }
    }

    private final Context context;
    private final AppDatabase db;
    private final PreferencesManager prefs;
    private final CryptoManager crypto;
    private final FileTransferManager fileTransferManager;
    private final NotificationHelper notificationHelper;
    private final HybridLogicalClock hlc = new HybridLogicalClock();
    private final Gson gson = new Gson();

    private final Map<String, PeerClientSocket> outboundClients = new ConcurrentHashMap<>();
    private final Map<String, NanoWSD.WebSocket> inboundSockets = new ConcurrentHashMap<>();
    private final Set<String> seenPacketIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, String> pendingGroupNonces = new ConcurrentHashMap<>();
    private final Map<String, String> pendingPinCache = new ConcurrentHashMap<>();
    private final Map<String, byte[]> derivedGroupKeys = new ConcurrentHashMap<>();
    private final Map<String, RelayedCallRecord> activeRelayedCalls = new ConcurrentHashMap<>();
    private final Map<String, GroupCallRoom> activeGroupCalls = new ConcurrentHashMap<>();
    private final Map<String, TokenBucket> rateLimitBuckets = new ConcurrentHashMap<>();

    private LocalHttpServer localHttpServer;
    private AppControlManager appControlManager;
    private ScheduledExecutorService scheduler;
    private volatile CallSignalListener callSignalListener;
    private volatile TypingListener typingListener;
    private volatile GroupAuthListener groupAuthListener;
    private volatile ActiveGroupCallListener activeGroupCallListener;
    private volatile String activeChatId = null;

    public PeerConnectionManager(Context context, AppDatabase db, PreferencesManager prefs,
                                 CryptoManager crypto, FileTransferManager fileTransferManager,
                                 NotificationHelper notificationHelper) {
        this.context = context.getApplicationContext();
        this.db = db;
        this.prefs = prefs;
        this.crypto = crypto;
        this.fileTransferManager = fileTransferManager;
        this.notificationHelper = notificationHelper;
        this.derivedGroupKeys.putAll(prefs.loadAllSavedGroupKeys());
    }

    public void attachServer(LocalHttpServer server) {
        this.localHttpServer = server;
    }

    public void attachAppControlManager(AppControlManager manager) {
        this.appControlManager = manager;
    }

    public AppControlManager.AppGateState getGateState() {
        return appControlManager != null ? appControlManager.getLastState() : null;
    }

    public void setCallSignalListener(CallSignalListener listener) {
        this.callSignalListener = listener;
    }

    public void setTypingListener(TypingListener listener) {
        this.typingListener = listener;
    }

    public void setGroupAuthListener(GroupAuthListener listener) {
        this.groupAuthListener = listener;
    }

    public void setActiveGroupCallListener(ActiveGroupCallListener listener) {
        this.activeGroupCallListener = listener;
    }

    public void setActiveChatId(String chatId) {
        this.activeChatId = chatId;
    }

    public void startHeartbeatLoop() {
        if (scheduler != null && !scheduler.isShutdown()) return;
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                sendHeartbeatToAll();
                long cutoff = System.currentTimeMillis() - OFFLINE_TIMEOUT_MS;
                db.peerDao().markStalePeersOffline(cutoff);
                flushPendingMessagesInternal(null);
            } catch (Exception ignored) {
            }
        }, HEARTBEAT_INTERVAL_SEC, HEARTBEAT_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    public void stopAll() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        for (PeerClientSocket c : outboundClients.values()) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
        outboundClients.clear();
        inboundSockets.clear();
    }

    public void handleWebClientDisconnected(String peerId) {
        if (peerId == null || peerId.isEmpty()) return;
        inboundSockets.remove(peerId);
        AppDatabase.DB_EXECUTOR.execute(() -> {
            db.peerDao().markPeerOffline(peerId);
            removePeerFromAllGroupCalls(peerId);
            broadcastWebSnapshotToAll();
        });
    }

    public void broadcastWebSnapshotToAll() {
        if (localHttpServer == null) return;
        AppDatabase.DB_EXECUTOR.execute(() -> localHttpServer.broadcastPersonalizedSnapshots());
    }

    /**
     * Parses both legacy flat NetPacket JSON and versioned {version, type, event, messageId, senderId, timestamp, payload} JSON envelopes.
     */
    public NetPacket normalizeIncomingPacket(String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) return null;
        NetPacket pkt = gson.fromJson(rawJson, NetPacket.class);
        if (pkt == null) return null;
        if (pkt.version <= 0 && pkt.protocolVersion > 0) pkt.version = pkt.protocolVersion;
        if (pkt.protocolVersion <= 0) pkt.protocolVersion = pkt.version > 0 ? pkt.version : 1;
        if (pkt.type == null && pkt.messageType != null) pkt.type = pkt.messageType;
        if (pkt.type == null && pkt.event != null) pkt.type = pkt.event;
        if (pkt.messageType == null && pkt.type != null) pkt.messageType = pkt.type;
        if (pkt.event == null && pkt.type != null) pkt.event = pkt.type;
        if (pkt.targetId == null && pkt.recipientId != null) pkt.targetId = pkt.recipientId;
        if (pkt.recipientId == null && pkt.targetId != null) pkt.recipientId = pkt.targetId;
        if (pkt.timestamp <= 0) pkt.timestamp = System.currentTimeMillis();

        if (pkt.payload != null && !pkt.payload.isEmpty()) {
            if (pkt.targetId == null && pkt.payload.get("targetId") != null) {
                pkt.targetId = String.valueOf(pkt.payload.get("targetId"));
            }
            if (pkt.targetId == null && pkt.payload.get("recipientId") != null) {
                pkt.targetId = String.valueOf(pkt.payload.get("recipientId"));
            }
            if (pkt.chatId == null && pkt.payload.get("chatId") != null) {
                pkt.chatId = String.valueOf(pkt.payload.get("chatId"));
            }
            if (pkt.messageId == null && pkt.payload.get("messageId") != null) {
                pkt.messageId = String.valueOf(pkt.payload.get("messageId"));
            }
            if (pkt.callId == null && pkt.payload.get("callId") != null) {
                pkt.callId = String.valueOf(pkt.payload.get("callId"));
            }
            if (pkt.callType == null && pkt.payload.get("callType") != null) {
                pkt.callType = String.valueOf(pkt.payload.get("callType"));
            }
            if (pkt.callState == null && pkt.payload.get("callState") != null) {
                pkt.callState = String.valueOf(pkt.payload.get("callState"));
            }
            if (pkt.reason == null && pkt.payload.get("reason") != null) {
                pkt.reason = String.valueOf(pkt.payload.get("reason"));
            }
            if (pkt.groupId == null && pkt.payload.get("groupId") != null) {
                pkt.groupId = String.valueOf(pkt.payload.get("groupId"));
            }
            if (pkt.groupName == null && pkt.payload.get("groupName") != null) {
                pkt.groupName = String.valueOf(pkt.payload.get("groupName"));
            }
            if (pkt.sdp == null && pkt.payload.get("sdp") != null) {
                pkt.sdp = String.valueOf(pkt.payload.get("sdp"));
            }
            if (pkt.iceCandidate == null && pkt.payload.get("iceCandidate") != null) {
                pkt.iceCandidate = String.valueOf(pkt.payload.get("iceCandidate"));
            }
            if (pkt.iceSdpMid == null && pkt.payload.get("iceSdpMid") != null) {
                pkt.iceSdpMid = String.valueOf(pkt.payload.get("iceSdpMid"));
            }
            if (pkt.payload.get("iceSdpMLineIndex") instanceof Number && pkt.iceSdpMLineIndex == 0) {
                pkt.iceSdpMLineIndex = ((Number) pkt.payload.get("iceSdpMLineIndex")).intValue();
            }
            if (pkt.emoji == null && pkt.payload.get("emoji") != null) {
                pkt.emoji = String.valueOf(pkt.payload.get("emoji"));
            }
            if (pkt.emojiAction == null && pkt.payload.get("emojiAction") != null) {
                pkt.emojiAction = String.valueOf(pkt.payload.get("emojiAction"));
            }
            if (pkt.status == null && pkt.payload.get("status") != null) {
                pkt.status = String.valueOf(pkt.payload.get("status"));
            }
            if (pkt.newContent == null && pkt.payload.get("newContent") != null) {
                pkt.newContent = String.valueOf(pkt.payload.get("newContent"));
            }
            if (pkt.payload.get("editedAt") instanceof Number && pkt.editedAt == 0L) {
                pkt.editedAt = ((Number) pkt.payload.get("editedAt")).longValue();
            }
            if (pkt.payload.get("muted") instanceof Boolean) {
                pkt.muted = (Boolean) pkt.payload.get("muted");
            }
            if (pkt.payload.get("videoEnabled") instanceof Boolean) {
                pkt.videoEnabled = (Boolean) pkt.payload.get("videoEnabled");
            }
            if (pkt.message == null && pkt.payload.get("message") != null) {
                pkt.message = gson.fromJson(gson.toJson(pkt.payload.get("message")), NetPacket.MessageDto.class);
            }
            if (pkt.fileManifest == null && pkt.payload.get("fileManifest") != null) {
                pkt.fileManifest = gson.fromJson(gson.toJson(pkt.payload.get("fileManifest")), NetPacket.FileManifestDto.class);
            }
        }
        return pkt;
    }

    private String serializePacket(NetPacket pkt) {
        pkt.version = 1;
        pkt.protocolVersion = 1;
        if (pkt.packetId == null) pkt.packetId = UUID.randomUUID().toString();
        if (pkt.messageType == null && pkt.type != null) pkt.messageType = pkt.type;
        if (pkt.event == null && pkt.type != null) pkt.event = pkt.type;
        if (pkt.recipientId == null && pkt.targetId != null) pkt.recipientId = pkt.targetId;
        if (pkt.timestamp <= 0) pkt.timestamp = System.currentTimeMillis();
        return gson.toJson(pkt);
    }

    public void connectToPeer(String ipAddress, int port, String knownDeviceId) {
        if (!NetworkUtils.isPrivateLanIp(ipAddress) || !NetworkUtils.isValidPort(port)) return;
        String normalizedIp = ipAddress.trim();
        NetworkUtils.LocalNetworkInfo myNet = NetworkUtils.detectLocalNetwork(context);
        if (normalizedIp.equals(myNet.ipAddress) && port == prefs.getServerPort()) {
            return; // Do not connect to self
        }

        String key = knownDeviceId != null && !knownDeviceId.isEmpty() ? knownDeviceId : (ipAddress + ":" + port);
        PeerClientSocket existing = outboundClients.get(key);
        if (existing != null && existing.isOpen()) return;

        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                URI uri = new URI("ws://" + ipAddress + ":" + port + "/ws");
                PeerClientSocket client = new PeerClientSocket(uri, key, ipAddress, port);
                outboundClients.put(key, client);
                client.connect();
            } catch (Exception ignored) {
            }
        });
    }

    public boolean sendPacketToPeer(String peerId, NetPacket packet) {
        if (peerId == null || peerId.isEmpty()) return false;
        String json = serializePacket(packet);

        PeerClientSocket out = outboundClients.get(peerId);
        if (out != null && out.isOpen()) {
            try {
                out.send(json);
                return true;
            } catch (Exception ignored) {
            }
        }
        NanoWSD.WebSocket in = inboundSockets.get(peerId);
        if (in != null && in.isOpen()) {
            try {
                in.send(json);
                return true;
            } catch (Exception ignored) {
            }
        }
        if (localHttpServer != null && localHttpServer.sendToWebClient(peerId, json)) {
            return true;
        }
        PeerEntity peer = db.peerDao().getByIdSync(peerId);
        if (peer != null && peer.ipAddress != null && !peer.ipAddress.isEmpty() && !peer.isWebUser) {
            connectToPeer(peer.ipAddress, peer.port, peer.deviceId);
        }
        return false;
    }

    public int broadcastPacket(NetPacket packet) {
        String json = serializePacket(packet);
        Set<String> sentPeerIds = new HashSet<>();
        if (packet.senderId != null) sentPeerIds.add(packet.senderId);
        int sentCount = 0;

        for (Map.Entry<String, PeerClientSocket> e : outboundClients.entrySet()) {
            try {
                if (e.getValue().isOpen() && !sentPeerIds.contains(e.getKey())) {
                    e.getValue().send(json);
                    sentPeerIds.add(e.getKey());
                    sentCount++;
                }
            } catch (Exception ignored) {
            }
        }
        for (Map.Entry<String, NanoWSD.WebSocket> e : inboundSockets.entrySet()) {
            try {
                if (e.getValue().isOpen() && !sentPeerIds.contains(e.getKey())) {
                    e.getValue().send(json);
                    sentPeerIds.add(e.getKey());
                    sentCount++;
                }
            } catch (Exception ignored) {
            }
        }
        if (localHttpServer != null) {
            sentCount += localHttpServer.broadcastToWebClientsExcluding(json, sentPeerIds);
        }
        return sentCount;
    }

    public void broadcastProfileUpdate() {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            NetPacket pkt = buildBasePacket(NetPacket.TYPE_PROFILE_UPDATE);
            broadcastPacket(pkt);
        });
    }

    public void sendHeartbeatToAll() {
        NetPacket pkt = buildBasePacket(NetPacket.TYPE_HEARTBEAT);
        broadcastPacket(pkt);
    }

    public void sendTyping(String chatId, String targetPeerId, boolean isTyping) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            NetPacket pkt = buildBasePacket(NetPacket.TYPE_TYPING);
            pkt.chatId = chatId;
            pkt.targetId = targetPeerId;
            pkt.typing = isTyping;
            if (targetPeerId != null && !targetPeerId.isEmpty()) {
                sendPacketToPeer(targetPeerId, pkt);
            } else {
                broadcastPacket(pkt);
            }
        });
    }

    public void sendMessage(String chatId, String targetPeerId, MessageEntity msg, NetPacket.FileManifestDto manifest) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            AppControlManager.AppGateState gate = getGateState();
            if (gate != null) {
                if (manifest != null && !gate.fileTransferEnabled) return;
                if (AppDatabase.EVERYONE_ROOM_ID.equals(chatId) && !gate.everyoneGroupEnabled) return;
            }
            HybridLogicalClock.Timestamp ts = hlc.nextLocal();
            msg.hlcTimestamp = ts.physical;
            msg.hlcCounter = ts.counter;
            msg.status = "SENDING";

            GroupEntity group = db.groupDao().getGroupSync(chatId);
            if (gate != null && group != null && !group.isPrivate
                    && !AppDatabase.EVERYONE_ROOM_ID.equals(chatId) && !gate.publicGroupsEnabled) {
                return;
            }
            String wireContent = msg.content;
            if (group != null && group.isPrivate && derivedGroupKeys.containsKey(group.groupId) && wireContent != null && !wireContent.isEmpty()) {
                try {
                    wireContent = crypto.encryptAesGcm(wireContent, derivedGroupKeys.get(group.groupId));
                    msg.isEncrypted = true;
                } catch (Exception ignored) {
                }
            }

            db.messageDao().upsert(msg);
            updateChatSummary(chatId, msg, false);

            NetPacket pkt = buildBasePacket(NetPacket.TYPE_CHAT_MESSAGE);
            pkt.chatId = chatId;
            pkt.targetId = targetPeerId;
            pkt.message = toDto(msg);
            pkt.message.content = wireContent;
            pkt.fileManifest = manifest;

            boolean transmitted;
            if (targetPeerId != null && !targetPeerId.isEmpty()) {
                transmitted = sendPacketToPeer(targetPeerId, pkt);
            } else if (group != null && group.isPrivate) {
                transmitted = sendToAuthorizedGroupPeers(group, pkt) > 0;
            } else {
                transmitted = broadcastPacket(pkt) > 0;
            }

            if (transmitted) {
                msg.status = "SENT";
                db.messageDao().updateStatus(msg.messageId, "SENT");
            }
        });
    }

    public void flushAllPendingMessages() {
        AppDatabase.DB_EXECUTOR.execute(() -> flushPendingMessagesInternal(null));
    }

    private void flushPendingMessagesInternal(String onlyPeerId) {
        List<MessageEntity> pending = db.messageDao().getPendingOutgoingSync();
        if (pending == null || pending.isEmpty()) return;
        String myId = prefs.getOrCreateDeviceId();

        for (MessageEntity msg : pending) {
            if (!myId.equals(msg.senderId)) continue;
            String targetPeerId = null;
            if (msg.chatId != null && msg.chatId.startsWith("dm:")) {
                targetPeerId = extractOtherDmPeerId(msg.chatId, myId);
                if (onlyPeerId != null && !onlyPeerId.equals(targetPeerId)) continue;
            }
            GroupEntity group = msg.chatId != null ? db.groupDao().getGroupSync(msg.chatId) : null;
            if (onlyPeerId != null && group != null && !isPeerAuthorizedForGroup(group, onlyPeerId)) {
                continue;
            }

            String wireContent = msg.content;
            if (group != null && group.isPrivate && derivedGroupKeys.containsKey(group.groupId) && wireContent != null && !wireContent.isEmpty()) {
                try {
                    wireContent = crypto.encryptAesGcm(wireContent, derivedGroupKeys.get(group.groupId));
                } catch (Exception ignored) {
                }
            }

            NetPacket.FileManifestDto manifest = null;
            if (msg.attachmentId != null && !msg.attachmentId.isEmpty()) {
                FileTransferEntity ft = db.fileTransferDao().getByIdSync(msg.attachmentId);
                if (ft != null && ft.manifestJson != null) {
                    try {
                        manifest = gson.fromJson(ft.manifestJson, NetPacket.FileManifestDto.class);
                    } catch (Exception ignored) {
                    }
                }
            }

            NetPacket pkt = buildBasePacket(NetPacket.TYPE_CHAT_MESSAGE);
            pkt.chatId = msg.chatId;
            pkt.targetId = targetPeerId;
            pkt.message = toDto(msg);
            pkt.message.content = wireContent;
            pkt.fileManifest = manifest;

            boolean sent;
            if (targetPeerId != null && !targetPeerId.isEmpty()) {
                sent = sendPacketToPeer(targetPeerId, pkt);
            } else if (onlyPeerId != null) {
                sent = sendPacketToPeer(onlyPeerId, pkt);
            } else if (group != null && group.isPrivate) {
                sent = sendToAuthorizedGroupPeers(group, pkt) > 0;
            } else {
                sent = broadcastPacket(pkt) > 0;
            }
            if (sent) {
                db.messageDao().updateStatus(msg.messageId, "SENT");
            }
        }
    }

    private int sendToAuthorizedGroupPeers(GroupEntity group, NetPacket packet) {
        if (group == null) return 0;
        List<GroupMemberEntity> members = db.groupDao().getMembersSync(group.groupId);
        String myId = prefs.getOrCreateDeviceId();
        int sent = 0;
        Set<String> tried = new HashSet<>();
        tried.add(myId);
        if (members != null) {
            for (GroupMemberEntity m : members) {
                if (m.deviceId != null && tried.add(m.deviceId)) {
                    if (sendPacketToPeer(m.deviceId, packet)) sent++;
                }
            }
        }
        if (group.ownerId != null && tried.add(group.ownerId)) {
            if (sendPacketToPeer(group.ownerId, packet)) sent++;
        }
        return sent;
    }

    public void sendReaction(String chatId, String messageId, String emoji) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            UserProfileEntity me = db.userProfileDao().getProfileSync();
            if (me == null) return;
            String action = applyReactionLocally(messageId, emoji, me.deviceId, me.name, null);

            NetPacket pkt = buildBasePacket(NetPacket.TYPE_MESSAGE_REACTION);
            pkt.chatId = chatId;
            pkt.messageId = messageId;
            pkt.emoji = emoji;
            pkt.emojiAction = action;
            broadcastPacket(pkt);
        });
    }

    public void sendMessageEdit(String chatId, String messageId, String newContent) {
        if (chatId == null || messageId == null || newContent == null) return;
        String trimmed = newContent.trim();
        if (trimmed.isEmpty()) return;
        AppDatabase.DB_EXECUTOR.execute(() -> {
            MessageEntity existing = db.messageDao().getByIdSync(messageId);
            String myId = prefs.getOrCreateDeviceId();
            if (existing == null || !myId.equals(existing.senderId) || !"TEXT".equals(existing.type) || existing.isDeletedForEveryone) {
                return;
            }
            long now = System.currentTimeMillis();
            db.messageDao().editMessage(messageId, trimmed, now);
            existing.content = trimmed;
            existing.isEdited = true;
            existing.editedAt = now;
            updateChatSummary(chatId, existing, false);

            String wireContent = trimmed;
            GroupEntity group = db.groupDao().getGroupSync(chatId);
            if (group != null && group.isPrivate && derivedGroupKeys.containsKey(group.groupId)) {
                try {
                    wireContent = crypto.encryptAesGcm(trimmed, derivedGroupKeys.get(group.groupId));
                } catch (Exception ignored) {
                }
            }

            NetPacket pkt = buildBasePacket(NetPacket.TYPE_MESSAGE_EDIT);
            pkt.chatId = chatId;
            pkt.messageId = messageId;
            pkt.newContent = wireContent;
            pkt.editedAt = now;
            if (chatId.startsWith("dm:")) {
                String targetPeerId = extractOtherDmPeerId(chatId, myId);
                if (targetPeerId != null) sendPacketToPeer(targetPeerId, pkt);
            } else if (group != null && group.isPrivate) {
                sendToAuthorizedGroupPeers(group, pkt);
            } else {
                broadcastPacket(pkt);
            }
        });
    }

    public void sendDeleteForEveryone(String chatId, String messageId) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            db.messageDao().deleteForEveryone(messageId);
            NetPacket pkt = buildBasePacket(NetPacket.TYPE_MESSAGE_DELETE);
            pkt.chatId = chatId;
            pkt.messageId = messageId;
            broadcastPacket(pkt);
        });
    }

    public void markChatSeen(String chatId, String targetPeerId) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            db.chatDao().markRead(chatId);
            List<MessageEntity> msgs = db.messageDao().getMessagesForChatSync(chatId);
            UserProfileEntity me = db.userProfileDao().getProfileSync();
            String myId = me != null ? me.deviceId : prefs.getOrCreateDeviceId();
            for (MessageEntity m : msgs) {
                if (!myId.equals(m.senderId) && !"SEEN".equals(m.status)) {
                    db.messageDao().updateStatus(m.messageId, "SEEN");
                    NetPacket receipt = buildBasePacket(NetPacket.TYPE_MESSAGE_RECEIPT);
                    receipt.chatId = chatId;
                    receipt.messageId = m.messageId;
                    receipt.status = "SEEN";
                    if (targetPeerId != null && !targetPeerId.isEmpty()) {
                        sendPacketToPeer(targetPeerId, receipt);
                    } else {
                        sendPacketToPeer(m.senderId, receipt);
                    }
                }
            }
        });
    }

    public void syncGroupMetadata(GroupEntity group) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            NetPacket pkt = buildBasePacket(NetPacket.TYPE_GROUP_SYNC);
            NetPacket.GroupDto dto = new NetPacket.GroupDto();
            dto.groupId = group.groupId;
            dto.name = group.name;
            dto.description = group.description;
            dto.isPrivate = group.isPrivate;
            dto.pinSalt = group.pinSalt;
            dto.ownerId = group.ownerId;
            dto.creatorName = group.creatorName;
            dto.adminsOnlySend = group.adminsOnlySend;
            dto.encryptionKeySalt = group.encryptionKeySalt;
            dto.keyVersion = group.keyVersion;
            dto.memberCount = group.memberCount;
            dto.onlineCount = group.onlineCount;
            dto.updatedAt = group.updatedAt;
            pkt.group = dto;
            List<GroupMemberEntity> members = db.groupDao().getMembersSync(group.groupId);
            pkt.members = new ArrayList<>();
            for (GroupMemberEntity gm : members) {
                NetPacket.GroupMemberDto md = new NetPacket.GroupMemberDto();
                md.groupId = gm.groupId;
                md.deviceId = gm.deviceId;
                md.displayName = gm.displayName;
                md.role = gm.role;
                md.isOnline = gm.isOnline;
                md.joinedAt = gm.joinedAt;
                pkt.members.add(md);
            }
            broadcastPacket(pkt);
        });
    }

    public void requestPrivateGroupChallenge(GroupEntity group, String rawPin) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            pendingPinCache.put(group.groupId, rawPin);
            NetPacket pkt = buildBasePacket(NetPacket.TYPE_GROUP_AUTH_CHALLENGE);
            pkt.groupId = group.groupId;
            pkt.targetId = group.ownerId;
            sendPacketToPeer(group.ownerId, pkt);
        });
    }

    public void cacheGroupDerivedKey(String groupId, String rawPin, String salt) {
        try {
            byte[] key = crypto.deriveKeyBytes(rawPin, salt);
            derivedGroupKeys.put(groupId, key);
            prefs.saveGroupKey(groupId, key);
        } catch (Exception ignored) {
        }
    }

    public boolean hasDerivedGroupKey(String groupId) {
        return groupId != null && derivedGroupKeys.containsKey(groupId);
    }

    public void publishWebFileMessage(String chatId, String targetId, String senderId, String senderName,
                                      String messageId, NetPacket.FileManifestDto manifest) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            HybridLogicalClock.Timestamp ts = hlc.nextLocal();
            MessageEntity msg = new MessageEntity(
                    messageId, chatId, senderId, senderName, true,
                    classifyMessageType(manifest.mimeType, manifest.fileName),
                    "Shared file: " + manifest.fileName,
                    null, null, null,
                    manifest.transferId, manifest.fileName, manifest.mimeType, manifest.totalSize,
                    manifest.fileSha256, null, "DELIVERED",
                    ts.physical, ts.counter, System.currentTimeMillis(),
                    false, false, "{}", false,
                    manifest.thumbnailBase64
            );
            db.messageDao().insertIfAbsent(msg);
            updateChatSummary(chatId, msg, !chatId.equals(activeChatId));

            NetPacket pkt = buildBasePacket(NetPacket.TYPE_CHAT_MESSAGE);
            pkt.chatId = chatId;
            pkt.targetId = targetId;
            pkt.message = toDto(msg);
            pkt.fileManifest = manifest;

            if (targetId != null && !targetId.isEmpty() && chatId.startsWith("dm:")) {
                sendPacketToPeer(targetId, pkt);
                if (localHttpServer != null) {
                    localHttpServer.sendToWebClient(senderId, serializePacket(pkt));
                }
            } else {
                GroupEntity group = db.groupDao().getGroupSync(chatId);
                if (group != null && group.isPrivate) {
                    sendToAuthorizedGroupPeers(group, pkt);
                } else {
                    broadcastPacket(pkt);
                }
                if (localHttpServer != null) {
                    localHttpServer.sendToWebClient(senderId, serializePacket(pkt));
                }
            }
        });
    }

    private boolean checkRateLimit(String peerKey) {
        if (peerKey == null || peerKey.isEmpty()) return true;
        TokenBucket bucket = rateLimitBuckets.computeIfAbsent(peerKey, k -> new TokenBucket());
        synchronized (bucket) {
            long now = System.currentTimeMillis();
            double elapsedSec = Math.max(0L, now - bucket.lastRefillMs) / 1000.0;
            bucket.tokens = Math.min(60.0, bucket.tokens + elapsedSec * 30.0);
            bucket.lastRefillMs = now;
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    public void handleIncomingPacket(NetPacket pkt, String remoteIp, NanoWSD.WebSocket serverSocket) {
        if (pkt == null || pkt.type == null) return;

        // Loop prevention & deduplication
        if (pkt.packetId != null) {
            if (!seenPacketIds.add(pkt.packetId)) return;
            if (seenPacketIds.size() > 5000) seenPacketIds.clear();
        }

        String rateKey = (pkt.senderId != null && !pkt.senderId.isEmpty()) ? pkt.senderId : remoteIp;
        if (!NetPacket.TYPE_CALL_ICE.equals(pkt.type) && !checkRateLimit(rateKey)) {
            return;
        }

        String myId = prefs.getOrCreateDeviceId();
        if (myId.equals(pkt.senderId)) return;

        if (pkt.senderId != null && serverSocket != null) {
            inboundSockets.put(pkt.senderId, serverSocket);
        }

        AppDatabase.DB_EXECUTOR.execute(() -> {
            PeerEntity existing = pkt.senderId != null ? db.peerDao().getByIdSync(pkt.senderId) : null;
            if (existing != null && existing.isBlocked) return;

            if (pkt.senderId != null && pkt.senderName != null && !pkt.senderName.isEmpty()) {
                upsertPeerFromPacket(pkt, remoteIp, existing);
            }

            switch (pkt.type) {
                case NetPacket.TYPE_HELLO:
                    sendKnownGroupsToPeer(pkt.senderId);
                    sendSyncSummary(pkt.senderId);
                    flushPendingMessagesInternal(pkt.senderId);
                    if (pkt.isWebUser && serverSocket != null) {
                        sendWebSnapshot(serverSocket, pkt.senderId);
                        broadcastWebSnapshotToAll();
                    }
                    break;

                case NetPacket.TYPE_HEARTBEAT:
                case NetPacket.TYPE_PROFILE_UPDATE:
                    break;

                case NetPacket.TYPE_TYPING:
                    if (pkt.targetId != null && !pkt.targetId.isEmpty() && !myId.equals(pkt.targetId)) {
                        sendPacketToPeer(pkt.targetId, pkt);
                    } else if (typingListener != null) {
                        new Handler(Looper.getMainLooper()).post(() ->
                                typingListener.onTyping(pkt.chatId, pkt.senderName, pkt.typing));
                    }
                    break;

                case NetPacket.TYPE_CHAT_MESSAGE:
                case NetPacket.TYPE_FILE_TRANSFER:
                    handleIncomingChatMessage(pkt);
                    break;

                case NetPacket.TYPE_MESSAGE_RECEIPT:
                    if (pkt.messageId != null && pkt.status != null) {
                        db.messageDao().updateStatus(pkt.messageId, pkt.status);
                        if (pkt.targetId != null && !pkt.targetId.isEmpty() && !myId.equals(pkt.targetId)) {
                            sendPacketToPeer(pkt.targetId, pkt);
                        }
                    }
                    break;

                case NetPacket.TYPE_MESSAGE_REACTION:
                    if (pkt.messageId != null && pkt.emoji != null) {
                        if (pkt.chatId != null && !isPeerAuthorizedForChat(pkt.chatId, pkt.senderId, myId, null)) {
                            break;
                        }
                        String applied = applyReactionLocally(pkt.messageId, pkt.emoji, pkt.senderId, pkt.senderName, pkt.emojiAction);
                        pkt.emojiAction = applied;
                        if (localHttpServer != null) {
                            localHttpServer.broadcastToWebClientsExcept(serializePacket(pkt), pkt.senderId);
                        }
                    }
                    break;

                case NetPacket.TYPE_MESSAGE_EDIT:
                    if (pkt.messageId != null && pkt.newContent != null) {
                        MessageEntity targetEdit = db.messageDao().getByIdSync(pkt.messageId);
                        if (targetEdit != null && pkt.senderId != null
                                && pkt.senderId.equals(targetEdit.senderId)
                                && "TEXT".equals(targetEdit.type)
                                && !targetEdit.isDeletedForEveryone
                                && isPeerAuthorizedForChat(targetEdit.chatId, pkt.senderId, myId, null)) {
                            String editedText = pkt.newContent;
                            if (targetEdit.isEncrypted && derivedGroupKeys.containsKey(targetEdit.chatId)) {
                                try {
                                    editedText = crypto.decryptAesGcm(pkt.newContent, derivedGroupKeys.get(targetEdit.chatId));
                                } catch (Exception ignored) {
                                }
                            }
                            long editedAt = pkt.editedAt > 0 ? pkt.editedAt : System.currentTimeMillis();
                            db.messageDao().editMessage(pkt.messageId, editedText, editedAt);
                            targetEdit.content = editedText;
                            targetEdit.isEdited = true;
                            targetEdit.editedAt = editedAt;
                            updateChatSummary(targetEdit.chatId, targetEdit, false);
                            if (localHttpServer != null) {
                                NetPacket relay = buildBasePacket(NetPacket.TYPE_MESSAGE_EDIT);
                                relay.senderId = pkt.senderId;
                                relay.senderName = pkt.senderName;
                                relay.chatId = targetEdit.chatId;
                                relay.messageId = pkt.messageId;
                                relay.newContent = editedText;
                                relay.editedAt = editedAt;
                                relayToChatWebClients(targetEdit.chatId, pkt.senderId, serializePacket(relay));
                            }
                        }
                    }
                    break;

                case NetPacket.TYPE_MESSAGE_DELETE:
                    if (pkt.messageId != null) {
                        MessageEntity targetMsg = db.messageDao().getByIdSync(pkt.messageId);
                        if (targetMsg != null && pkt.senderId != null && !pkt.senderId.equals(targetMsg.senderId)) {
                            // Only sender or group admin/owner can delete for everyone
                            GroupEntity grp = db.groupDao().getGroupSync(targetMsg.chatId);
                            if (!isAdminOrOwner(grp, pkt.senderId)) break;
                        }
                        db.messageDao().deleteForEveryone(pkt.messageId);
                        if (localHttpServer != null) {
                            localHttpServer.broadcastToWebClientsExcept(serializePacket(pkt), pkt.senderId);
                        }
                    }
                    break;

                case NetPacket.TYPE_SYNC_SUMMARY:
                    handleSyncSummary(pkt);
                    break;

                case NetPacket.TYPE_SYNC_REQUEST:
                    handleSyncRequest(pkt);
                    break;

                case NetPacket.TYPE_SYNC_RESPONSE:
                    handleSyncResponse(pkt);
                    break;

                case NetPacket.TYPE_GROUP_SYNC:
                    handleGroupSync(pkt);
                    break;

                case NetPacket.TYPE_GROUP_AUTH_CHALLENGE:
                    handleGroupAuthChallenge(pkt);
                    break;

                case NetPacket.TYPE_GROUP_AUTH_VERIFY:
                    handleGroupAuthVerify(pkt);
                    break;

                case NetPacket.TYPE_GROUP_CALL_INVITE:
                case NetPacket.TYPE_GROUP_CALL_JOIN:
                case NetPacket.TYPE_GROUP_CALL_LEAVE:
                case NetPacket.TYPE_GROUP_CALL_STATE:
                    handleGroupCallSignalingPacket(pkt, myId);
                    break;

                case NetPacket.TYPE_CALL_OFFER:
                case NetPacket.TYPE_CALL_ANSWER:
                case NetPacket.TYPE_CALL_ICE:
                case NetPacket.TYPE_CALL_RINGING:
                case NetPacket.TYPE_CALL_BUSY:
                case NetPacket.TYPE_CALL_REJECT:
                case NetPacket.TYPE_CALL_END:
                    handleCallSignalingPacket(pkt, myId);
                    break;
            }
        });
    }

    private void handleGroupCallSignalingPacket(NetPacket pkt, String myId) {
        if (pkt.callId == null || pkt.groupId == null) return;
        AppControlManager.AppGateState gate = getGateState();
        if (gate != null && !gate.groupCallsEnabled) return;
        GroupEntity group = db.groupDao().getGroupSync(pkt.groupId);
        if (!isPeerAuthorizedForGroup(group, pkt.senderId)) {
            return;
        }

        if (NetPacket.TYPE_GROUP_CALL_INVITE.equals(pkt.type) || NetPacket.TYPE_GROUP_CALL_JOIN.equals(pkt.type)) {
            GroupCallRoom room = activeGroupCalls.computeIfAbsent(pkt.callId, id -> {
                GroupCallRoom r = new GroupCallRoom();
                r.callId = id;
                r.groupId = pkt.groupId;
                r.groupName = pkt.groupName != null ? pkt.groupName : (group != null ? group.name : "Group Call");
                r.callType = pkt.callType != null ? pkt.callType : "GROUP_VOICE";
                r.startedAtMs = System.currentTimeMillis();
                return r;
            });
            if (room.participants.size() >= MAX_GROUP_CALL_PARTICIPANTS
                    && !room.participants.containsKey(pkt.senderId)) {
                NetPacket busy = buildBasePacket(NetPacket.TYPE_CALL_BUSY);
                busy.callId = pkt.callId;
                busy.groupId = pkt.groupId;
                busy.targetId = pkt.senderId;
                busy.reason = "Group call full (max " + MAX_GROUP_CALL_PARTICIPANTS + ")";
                sendPacketToPeer(pkt.senderId, busy);
                return;
            }
            NetPacket.GroupCallParticipantDto participant = new NetPacket.GroupCallParticipantDto();
            participant.deviceId = pkt.senderId;
            participant.displayName = pkt.senderName != null ? pkt.senderName : "Peer";
            participant.isWebUser = pkt.isWebUser;
            participant.muted = pkt.muted;
            participant.videoEnabled = pkt.videoEnabled;
            participant.state = "CONNECTED";
            room.participants.put(pkt.senderId, participant);
            notifyActiveGroupCallsChanged();
        } else if (NetPacket.TYPE_GROUP_CALL_STATE.equals(pkt.type)) {
            GroupCallRoom room = activeGroupCalls.get(pkt.callId);
            if (room != null && pkt.senderId != null) {
                NetPacket.GroupCallParticipantDto p = room.participants.get(pkt.senderId);
                if (p != null) {
                    p.muted = pkt.muted;
                    p.videoEnabled = pkt.videoEnabled;
                    notifyActiveGroupCallsChanged();
                }
            }
        } else if (NetPacket.TYPE_GROUP_CALL_LEAVE.equals(pkt.type)) {
            GroupCallRoom room = activeGroupCalls.get(pkt.callId);
            if (room != null && pkt.senderId != null) {
                room.participants.remove(pkt.senderId);
                if (room.participants.isEmpty()) {
                    activeGroupCalls.remove(pkt.callId);
                }
                notifyActiveGroupCallsChanged();
            }
        }

        // Relay group call signaling to web clients
        if (localHttpServer != null) {
            if (group != null && group.isPrivate) {
                List<GroupMemberEntity> members = db.groupDao().getMembersSync(group.groupId);
                String wire = serializePacket(pkt);
                if (members != null) {
                    for (GroupMemberEntity m : members) {
                        if (m.deviceId != null && !m.deviceId.equals(pkt.senderId)) {
                            localHttpServer.sendToWebClient(m.deviceId, wire);
                        }
                    }
                }
            } else {
                localHttpServer.broadcastToWebClientsExcept(serializePacket(pkt), pkt.senderId);
            }
        }

        if (callSignalListener != null) {
            new Handler(Looper.getMainLooper()).post(() -> callSignalListener.onCallSignal(pkt));
        } else if (NetPacket.TYPE_GROUP_CALL_INVITE.equals(pkt.type)
                && (group == null || (!group.isMuted && group.isJoined))) {
            notificationHelper.showIncomingCallNotification(pkt);
        }
    }

    private void removePeerFromAllGroupCalls(String peerId) {
        boolean changed = false;
        for (Map.Entry<String, GroupCallRoom> e : activeGroupCalls.entrySet()) {
            GroupCallRoom room = e.getValue();
            if (room.participants.remove(peerId) != null) {
                changed = true;
                NetPacket leave = buildBasePacket(NetPacket.TYPE_GROUP_CALL_LEAVE);
                leave.senderId = peerId;
                leave.callId = room.callId;
                leave.groupId = room.groupId;
                broadcastPacket(leave);
                if (room.participants.isEmpty()) {
                    activeGroupCalls.remove(e.getKey());
                }
            }
        }
        if (changed) notifyActiveGroupCallsChanged();
    }

    public void startOrJoinGroupCall(String callId, String groupId, String groupName, String callType,
                                     boolean muted, boolean videoEnabled) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            UserProfileEntity me = db.userProfileDao().getProfileSync();
            String myId = me != null ? me.deviceId : prefs.getOrCreateDeviceId();
            String myName = me != null ? me.name : "Me";

            GroupCallRoom room = activeGroupCalls.computeIfAbsent(callId, id -> {
                GroupCallRoom r = new GroupCallRoom();
                r.callId = id;
                r.groupId = groupId;
                r.groupName = groupName != null ? groupName : "Group Call";
                r.callType = callType != null ? callType : "GROUP_VOICE";
                r.startedAtMs = System.currentTimeMillis();
                return r;
            });

            boolean isCreator = room.participants.isEmpty();
            NetPacket.GroupCallParticipantDto self = new NetPacket.GroupCallParticipantDto();
            self.deviceId = myId;
            self.displayName = myName;
            self.isWebUser = false;
            self.muted = muted;
            self.videoEnabled = videoEnabled;
            self.state = "CONNECTED";
            room.participants.put(myId, self);
            notifyActiveGroupCallsChanged();

            NetPacket join = buildBasePacket(isCreator ? NetPacket.TYPE_GROUP_CALL_INVITE : NetPacket.TYPE_GROUP_CALL_JOIN);
            join.callId = callId;
            join.groupId = groupId;
            join.groupName = room.groupName;
            join.callType = room.callType;
            join.muted = muted;
            join.videoEnabled = videoEnabled;

            GroupEntity group = db.groupDao().getGroupSync(groupId);
            if (group != null && group.isPrivate) {
                sendToAuthorizedGroupPeers(group, join);
            } else {
                broadcastPacket(join);
            }
        });
    }

    public void updateGroupCallSelfState(String callId, String groupId, boolean muted, boolean videoEnabled) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            String myId = prefs.getOrCreateDeviceId();
            GroupCallRoom room = activeGroupCalls.get(callId);
            if (room != null) {
                NetPacket.GroupCallParticipantDto self = room.participants.get(myId);
                if (self != null) {
                    self.muted = muted;
                    self.videoEnabled = videoEnabled;
                    notifyActiveGroupCallsChanged();
                }
            }
            NetPacket state = buildBasePacket(NetPacket.TYPE_GROUP_CALL_STATE);
            state.callId = callId;
            state.groupId = groupId;
            state.muted = muted;
            state.videoEnabled = videoEnabled;
            GroupEntity group = groupId != null ? db.groupDao().getGroupSync(groupId) : null;
            if (group != null && group.isPrivate) {
                sendToAuthorizedGroupPeers(group, state);
            } else {
                broadcastPacket(state);
            }
        });
    }

    public void leaveGroupCall(String callId, String groupId) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            String myId = prefs.getOrCreateDeviceId();
            GroupCallRoom room = activeGroupCalls.get(callId);
            if (room != null) {
                room.participants.remove(myId);
                if (room.participants.isEmpty()) {
                    activeGroupCalls.remove(callId);
                }
                notifyActiveGroupCallsChanged();
            }
            NetPacket leave = buildBasePacket(NetPacket.TYPE_GROUP_CALL_LEAVE);
            leave.callId = callId;
            leave.groupId = groupId;
            GroupEntity group = groupId != null ? db.groupDao().getGroupSync(groupId) : null;
            if (group != null && group.isPrivate) {
                sendToAuthorizedGroupPeers(group, leave);
            } else {
                broadcastPacket(leave);
            }
        });
    }

    public GroupCallRoom getActiveGroupCallForGroup(String groupId) {
        if (groupId == null) return null;
        for (GroupCallRoom r : activeGroupCalls.values()) {
            if (groupId.equals(r.groupId) && !r.participants.isEmpty()) {
                return r;
            }
        }
        return null;
    }

    public List<Map<String, Object>> getActiveGroupCallsSnapshotForPeer(String requesterPeerId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (GroupCallRoom r : activeGroupCalls.values()) {
            if (r.participants.isEmpty()) continue;
            GroupEntity g = db.groupDao().getGroupSync(r.groupId);
            if (g != null && g.isPrivate && !isPeerAuthorizedForGroup(g, requesterPeerId)) {
                continue;
            }
            Map<String, Object> m = new HashMap<>();
            m.put("callId", r.callId);
            m.put("groupId", r.groupId);
            m.put("groupName", r.groupName);
            m.put("callType", r.callType);
            m.put("startedAtMs", r.startedAtMs);
            m.put("participants", new ArrayList<>(r.participants.values()));
            out.add(m);
        }
        return out;
    }

    private void notifyActiveGroupCallsChanged() {
        broadcastWebSnapshotToAll();
        if (activeGroupCallListener != null) {
            new Handler(Looper.getMainLooper()).post(() -> activeGroupCallListener.onActiveGroupCallsChanged());
        }
    }

    private void handleCallSignalingPacket(NetPacket pkt, String myId) {
        // Track call lifecycle for call logs when host relays web-to-web calls
        if (pkt.callId != null && (pkt.groupId == null || pkt.groupId.isEmpty())) {
            if (NetPacket.TYPE_CALL_OFFER.equals(pkt.type)) {
                activeRelayedCalls.put(pkt.callId, new RelayedCallRecord(
                        pkt.callId,
                        pkt.senderId,
                        pkt.senderName != null ? pkt.senderName : "Peer",
                        pkt.targetId,
                        pkt.callType,
                        System.currentTimeMillis()
                ));
            } else if (NetPacket.TYPE_CALL_ANSWER.equals(pkt.type)) {
                RelayedCallRecord rec = activeRelayedCalls.get(pkt.callId);
                if (rec != null) {
                    rec.connected = true;
                    rec.connectedAtMs = System.currentTimeMillis();
                }
            }
        }

        // If this signal is targeted at another connected peer/browser (relay via local host)
        if (pkt.targetId != null && !pkt.targetId.isEmpty() && !myId.equals(pkt.targetId)) {
            sendPacketToPeer(pkt.targetId, pkt);
            if (NetPacket.TYPE_CALL_END.equals(pkt.type)
                    || NetPacket.TYPE_CALL_BUSY.equals(pkt.type)
                    || NetPacket.TYPE_CALL_REJECT.equals(pkt.type)) {
                persistRelayedCallLog(pkt);
            }
            return;
        }

        // Targeted directly at this Android host device
        if (callSignalListener != null) {
            new Handler(Looper.getMainLooper()).post(() -> callSignalListener.onCallSignal(pkt));
        } else if (NetPacket.TYPE_CALL_OFFER.equals(pkt.type)) {
            notificationHelper.showIncomingCallNotification(pkt);
        } else if (NetPacket.TYPE_CALL_END.equals(pkt.type)
                || NetPacket.TYPE_CALL_REJECT.equals(pkt.type)
                || NetPacket.TYPE_CALL_BUSY.equals(pkt.type)) {
            notificationHelper.clearActiveIncomingCallIfMatches(pkt.callId);
            persistRelayedCallLog(pkt);
        }
    }

    private void persistRelayedCallLog(NetPacket endPkt) {
        if (endPkt.callId == null) return;
        RelayedCallRecord rec = activeRelayedCalls.remove(endPkt.callId);
        if (rec == null) return;
        long durationSec = rec.connected && rec.connectedAtMs > 0
                ? (System.currentTimeMillis() - rec.connectedAtMs) / 1000L
                : 0L;
        String finalStatus;
        if (NetPacket.TYPE_CALL_BUSY.equals(endPkt.type)) {
            finalStatus = "BUSY";
        } else if (NetPacket.TYPE_CALL_REJECT.equals(endPkt.type)) {
            finalStatus = "REJECTED";
        } else {
            finalStatus = rec.connected ? "COMPLETED" : "MISSED";
        }
        db.callLogDao().upsert(new CallLogEntity(
                rec.callId,
                rec.callerId != null ? rec.callerId : "",
                rec.callerName,
                "",
                rec.callType,
                "INCOMING",
                finalStatus,
                durationSec,
                rec.startedAtMs
        ));
    }

    private void upsertPeerFromPacket(NetPacket pkt, String remoteIp, PeerEntity existing) {
        long now = System.currentTimeMillis();
        String ip = (pkt.senderIp != null && !pkt.senderIp.isEmpty()) ? pkt.senderIp : remoteIp;
        int port = pkt.senderPort > 0 ? pkt.senderPort : (existing != null ? existing.port : 8080);

        String displayName = resolveDuplicateDisplayName(pkt.senderId, pkt.senderName);
        PeerEntity peer = new PeerEntity(
                pkt.senderId,
                displayName,
                pkt.senderBio != null ? pkt.senderBio : (existing != null ? existing.bio : ""),
                ip,
                port,
                pkt.connectionMode != null ? pkt.connectionMode : (existing != null ? existing.connectionMode : "WIFI"),
                pkt.senderStatus != null ? pkt.senderStatus : "ONLINE",
                true,
                existing != null && existing.isFriend,
                existing != null && existing.isBlocked,
                pkt.isWebUser,
                pkt.avatarBase64 != null && !pkt.avatarBase64.isEmpty() ? pkt.avatarBase64 : (existing != null ? existing.avatarBase64 : ""),
                now,
                now
        );
        db.peerDao().upsert(peer);
    }

    private String resolveDuplicateDisplayName(String deviceId, String rawName) {
        List<PeerEntity> all = db.peerDao().getAllPeersSync();
        for (PeerEntity other : all) {
            if (!other.deviceId.equals(deviceId) && other.name.equalsIgnoreCase(rawName)) {
                String suffix = deviceId.length() >= 4 ? deviceId.substring(deviceId.length() - 4) : deviceId;
                return rawName + " (" + suffix + ")";
            }
        }
        return rawName;
    }

    public boolean isPeerAuthorizedForGroup(GroupEntity group, String peerId) {
        if (group == null) return true;
        if (AppDatabase.EVERYONE_ROOM_ID.equals(group.groupId)) return true;
        if (!group.isPrivate) return true;
        if (peerId == null || peerId.isEmpty()) return false;
        if (peerId.equals(group.ownerId)) return true;
        return db.groupDao().getMemberSync(group.groupId, peerId) != null;
    }

    public boolean isAdminOrOwner(GroupEntity group, String peerId) {
        if (group == null || peerId == null) return false;
        if (peerId.equals(group.ownerId)) return true;
        GroupMemberEntity member = db.groupDao().getMemberSync(group.groupId, peerId);
        return member != null && ("OWNER".equalsIgnoreCase(member.role) || "ADMIN".equalsIgnoreCase(member.role));
    }

    public boolean isPeerAuthorizedForChat(String chatId, String peerId, String myId, String targetId) {
        if (chatId == null || peerId == null || peerId.isEmpty()) return false;
        if (AppDatabase.EVERYONE_ROOM_ID.equals(chatId)) return true;
        if (chatId.startsWith("dm:")) {
            String rest = chatId.substring(3);
            boolean senderInDm = rest.startsWith(peerId + "_") || rest.endsWith("_" + peerId);
            if (!senderInDm) return false;
            if (targetId != null && !targetId.isEmpty()) {
                return chatId.equals(makeDmChatId(peerId, targetId));
            }
            return chatId.equals(makeDmChatId(peerId, myId));
        }
        GroupEntity grp = db.groupDao().getGroupSync(chatId);
        if (grp == null) return false;
        return isPeerAuthorizedForGroup(grp, peerId);
    }

    private String extractOtherDmPeerId(String dmChatId, String myId) {
        if (dmChatId == null || !dmChatId.startsWith("dm:")) return null;
        String rest = dmChatId.substring(3);
        if (rest.startsWith(myId + "_")) {
            return rest.substring(myId.length() + 1);
        }
        if (rest.endsWith("_" + myId)) {
            return rest.substring(0, rest.length() - myId.length() - 1);
        }
        int sep = rest.indexOf('_');
        if (sep > 0) {
            String a = rest.substring(0, sep);
            String b = rest.substring(sep + 1);
            return myId.equals(a) ? b : a;
        }
        return null;
    }

    private void handleIncomingChatMessage(NetPacket pkt) {
        if (pkt.message == null) return;
        NetPacket.MessageDto dto = pkt.message;
        hlc.mergeRemote(dto.hlcTimestamp, dto.hlcCounter);

        String myId = prefs.getOrCreateDeviceId();
        String localChatId = resolveLocalChatId(dto.chatId, pkt.senderId, myId);
        boolean addressedToOtherPeer = pkt.targetId != null && !pkt.targetId.isEmpty() && !myId.equals(pkt.targetId);

        GroupEntity group = !localChatId.startsWith("dm:") ? db.groupDao().getGroupSync(localChatId) : null;
        if (group != null) {
            if (group.isPrivate && !isPeerAuthorizedForGroup(group, pkt.senderId)) {
                return;
            }
            if (group.isPrivate && !group.isJoined && !myId.equals(group.ownerId) && !pkt.isWebUser) {
                return;
            }
            if (group.adminsOnlySend && !"SYSTEM".equals(dto.type) && !isAdminOrOwner(group, pkt.senderId)) {
                return;
            }
        }

        String content = dto.content;
        if (dto.isEncrypted && derivedGroupKeys.containsKey(localChatId)) {
            try {
                content = crypto.decryptAesGcm(dto.content, derivedGroupKeys.get(localChatId));
            } catch (Exception ignored) {
            }
        }

        if (pkt.fileManifest != null) {
            fileTransferManager.registerIncomingManifest(pkt.fileManifest);
        }

        String thumbBase64 = (dto.thumbnailBase64 != null && !dto.thumbnailBase64.isEmpty())
                ? dto.thumbnailBase64
                : (pkt.fileManifest != null ? pkt.fileManifest.thumbnailBase64 : null);

        MessageEntity msg = new MessageEntity(
                dto.messageId,
                localChatId,
                dto.senderId,
                dto.senderName,
                dto.senderIsWeb,
                dto.type != null ? dto.type : "TEXT",
                content,
                dto.replyToMessageId,
                dto.replyToPreview,
                dto.forwardedFrom,
                dto.attachmentId,
                dto.attachmentName,
                dto.attachmentMime,
                dto.attachmentSize,
                dto.attachmentSha256,
                null,
                "DELIVERED",
                dto.hlcTimestamp,
                dto.hlcCounter,
                dto.createdAt > 0 ? dto.createdAt : System.currentTimeMillis(),
                false,
                dto.isDeletedForEveryone,
                dto.reactionsJson,
                dto.isEncrypted,
                thumbBase64,
                dto.isEdited,
                dto.editedAt
        );
        long inserted = db.messageDao().insertIfAbsent(msg);
        if (inserted > 0 && !addressedToOtherPeer) {
            boolean unread = !localChatId.equals(activeChatId);
            updateChatSummary(localChatId, msg, unread);
            if (unread && prefs.areNotificationsEnabled() && !PreferencesManager.STATUS_DND.equals(prefs.getActiveStatus())) {
                boolean muted = false;
                String chatTitle = msg.senderName;
                if (localChatId.startsWith("dm:")) {
                    PeerEntity senderPeer = db.peerDao().getByIdSync(pkt.senderId);
                    muted = senderPeer != null && senderPeer.isMuted;
                } else if (group != null) {
                    muted = group.isMuted;
                    chatTitle = group.name;
                }
                if (!muted) {
                    notificationHelper.showMessageNotification(localChatId, chatTitle, pkt.senderId, msg.senderName, msg.content);
                }
            }
        }

        // Send DELIVERED receipt back to sender
        NetPacket receipt = buildBasePacket(NetPacket.TYPE_MESSAGE_RECEIPT);
        receipt.chatId = localChatId;
        receipt.messageId = dto.messageId;
        receipt.status = "DELIVERED";
        sendPacketToPeer(pkt.senderId, receipt);

        // Relay to authorized peers / web clients
        if (localHttpServer != null) {
            String wireJson = serializePacket(pkt);
            if (!localChatId.startsWith("dm:")) {
                if (group != null && group.isPrivate) {
                    List<GroupMemberEntity> members = db.groupDao().getMembersSync(group.groupId);
                    if (members != null) {
                        for (GroupMemberEntity m : members) {
                            if (m.deviceId != null && !m.deviceId.equals(pkt.senderId)) {
                                localHttpServer.sendToWebClient(m.deviceId, wireJson);
                            }
                        }
                    }
                } else {
                    localHttpServer.broadcastToWebClientsExcept(wireJson, pkt.senderId);
                }
            } else if (addressedToOtherPeer) {
                sendPacketToPeer(pkt.targetId, pkt);
            }
        }
    }

    private String resolveLocalChatId(String wireChatId, String senderId, String myId) {
        if (wireChatId != null && wireChatId.startsWith("dm:")) {
            return wireChatId;
        }
        return wireChatId != null ? wireChatId : makeDmChatId(myId, senderId);
    }

    public static String makeDmChatId(String idA, String idB) {
        if (idA.compareTo(idB) <= 0) return "dm:" + idA + "_" + idB;
        return "dm:" + idB + "_" + idA;
    }

    private void updateChatSummary(String chatId, MessageEntity msg, boolean incrementUnread) {
        ChatEntity existing = db.chatDao().getChatSync(chatId);
        boolean isGroup = !chatId.startsWith("dm:");
        String title = existing != null ? existing.title : msg.senderName;
        if (AppDatabase.EVERYONE_ROOM_ID.equals(chatId)) {
            title = "Everyone Room";
        } else if (isGroup) {
            GroupEntity g = db.groupDao().getGroupSync(chatId);
            if (g != null) title = g.name;
        }
        String preview = msg.attachmentName != null && !msg.attachmentName.isEmpty()
                ? "[File] " + msg.attachmentName
                : msg.content;
        int unread = existing != null ? existing.unreadCount : 0;
        if (incrementUnread) unread++;
        ChatEntity updated = new ChatEntity(
                chatId,
                title,
                isGroup,
                isGroup ? null : msg.senderId,
                msg.messageId,
                preview,
                msg.createdAt,
                unread,
                existing != null && existing.isMuted,
                existing != null && existing.isPinned
        );
        db.chatDao().upsert(updated);
    }

    public String applyReactionLocally(String messageId, String emoji, String userId, String userName, String requestedAction) {
        MessageEntity msg = db.messageDao().getByIdSync(messageId);
        if (msg == null || emoji == null || emoji.isEmpty() || userId == null || userId.isEmpty()) return "ADD";
        Type mapType = new TypeToken<Map<String, List<Map<String, String>>>>() {}.getType();
        Map<String, List<Map<String, String>>> map = gson.fromJson(msg.reactionsJson, mapType);
        if (map == null) map = new HashMap<>();
        List<Map<String, String>> list = map.computeIfAbsent(emoji, k -> new ArrayList<>());

        int existingIdx = -1;
        for (int i = 0; i < list.size(); i++) {
            if (userId.equals(list.get(i).get("id"))) {
                existingIdx = i;
                break;
            }
        }

        String resultAction;
        if ("REMOVE".equalsIgnoreCase(requestedAction) || (requestedAction == null && existingIdx >= 0)) {
            if (existingIdx >= 0) {
                list.remove(existingIdx);
            }
            resultAction = "REMOVE";
        } else {
            if (existingIdx < 0) {
                Map<String, String> entry = new HashMap<>();
                entry.put("id", userId);
                entry.put("name", userName != null ? userName : "Peer");
                list.add(entry);
            }
            resultAction = "ADD";
        }
        if (list.isEmpty()) map.remove(emoji);
        db.messageDao().updateReactions(messageId, gson.toJson(map));
        return resultAction;
    }

    private void sendSyncSummary(String peerId) {
        if (peerId == null || peerId.isEmpty()) return;
        String myId = prefs.getOrCreateDeviceId();

        // Always sync Everyone Room
        sendChatSyncSummary(peerId, AppDatabase.EVERYONE_ROOM_ID);

        // Sync DM conversation with this peer
        String dmChatId = makeDmChatId(myId, peerId);
        List<String> dmIds = db.messageDao().getMessageIdsForChatSync(dmChatId);
        if (dmIds != null && !dmIds.isEmpty()) {
            sendChatSyncSummary(peerId, dmChatId, dmIds);
        }

        // Sync joined public groups and joined private groups where peerId is an authorized member
        List<GroupEntity> groups = db.groupDao().getAllGroupsSync();
        if (groups != null) {
            for (GroupEntity g : groups) {
                if (AppDatabase.EVERYONE_ROOM_ID.equals(g.groupId) || !g.isJoined) continue;
                if (g.isPrivate && !isPeerAuthorizedForGroup(g, peerId)) continue;
                sendChatSyncSummary(peerId, g.groupId);
            }
        }
    }

    private void sendChatSyncSummary(String peerId, String chatId) {
        sendChatSyncSummary(peerId, chatId, db.messageDao().getMessageIdsForChatSync(chatId));
    }

    private void sendChatSyncSummary(String peerId, String chatId, List<String> ids) {
        NetPacket pkt = buildBasePacket(NetPacket.TYPE_SYNC_SUMMARY);
        pkt.chatId = chatId;
        pkt.syncMessageIds = ids != null ? ids : Collections.emptyList();
        sendPacketToPeer(peerId, pkt);
    }

    private void handleSyncSummary(NetPacket pkt) {
        if (pkt.syncMessageIds == null || pkt.chatId == null) return;
        String myId = prefs.getOrCreateDeviceId();
        if (!isPeerAuthorizedForChat(pkt.chatId, pkt.senderId, myId, null)) return;
        GroupEntity grp = !pkt.chatId.startsWith("dm:") ? db.groupDao().getGroupSync(pkt.chatId) : null;
        if (grp != null && grp.isPrivate && !grp.isJoined) return;

        List<String> localIds = db.messageDao().getMessageIdsForChatSync(pkt.chatId);
        Set<String> localSet = new HashSet<>(localIds);
        List<String> missing = new ArrayList<>();
        for (String remoteId : pkt.syncMessageIds) {
            if (!localSet.contains(remoteId)) missing.add(remoteId);
        }
        if (!missing.isEmpty()) {
            NetPacket req = buildBasePacket(NetPacket.TYPE_SYNC_REQUEST);
            req.chatId = pkt.chatId;
            req.syncMessageIds = missing;
            sendPacketToPeer(pkt.senderId, req);
        }
    }

    private void handleSyncRequest(NetPacket pkt) {
        if (pkt.syncMessageIds == null || pkt.chatId == null) return;
        String myId = prefs.getOrCreateDeviceId();
        if (!isPeerAuthorizedForChat(pkt.chatId, pkt.senderId, myId, null)) {
            return;
        }
        List<MessageEntity> all = db.messageDao().getMessagesForChatSync(pkt.chatId);
        Set<String> wanted = new HashSet<>(pkt.syncMessageIds);
        List<NetPacket.MessageDto> toSend = new ArrayList<>();
        GroupEntity grp = db.groupDao().getGroupSync(pkt.chatId);
        for (MessageEntity m : all) {
            if (wanted.contains(m.messageId)) {
                NetPacket.MessageDto dto = toDto(m);
                if (grp != null && grp.isPrivate && derivedGroupKeys.containsKey(grp.groupId)
                        && dto.content != null && !dto.content.isEmpty()) {
                    try {
                        dto.content = crypto.encryptAesGcm(dto.content, derivedGroupKeys.get(grp.groupId));
                        dto.isEncrypted = true;
                    } catch (Exception ignored) {
                    }
                }
                toSend.add(dto);
            }
        }
        if (!toSend.isEmpty()) {
            NetPacket resp = buildBasePacket(NetPacket.TYPE_SYNC_RESPONSE);
            resp.chatId = pkt.chatId;
            resp.syncMessages = toSend;
            sendPacketToPeer(pkt.senderId, resp);
        }
    }

    private void handleSyncResponse(NetPacket pkt) {
        if (pkt.syncMessages == null) return;
        for (NetPacket.MessageDto dto : pkt.syncMessages) {
            NetPacket wrap = new NetPacket();
            wrap.type = NetPacket.TYPE_CHAT_MESSAGE;
            wrap.event = NetPacket.TYPE_CHAT_MESSAGE;
            wrap.senderId = dto.senderId;
            wrap.senderName = dto.senderName;
            wrap.message = dto;
            handleIncomingChatMessage(wrap);
        }
    }

    private void sendKnownGroupsToPeer(String peerId) {
        List<GroupEntity> groups = db.groupDao().getAllGroupsSync();
        for (GroupEntity g : groups) {
            NetPacket pkt = buildBasePacket(NetPacket.TYPE_GROUP_SYNC);
            NetPacket.GroupDto dto = new NetPacket.GroupDto();
            dto.groupId = g.groupId;
            dto.name = g.name;
            dto.description = g.description;
            dto.isPrivate = g.isPrivate;
            dto.pinSalt = g.pinSalt;
            dto.ownerId = g.ownerId;
            dto.creatorName = g.creatorName;
            dto.adminsOnlySend = g.adminsOnlySend;
            dto.encryptionKeySalt = g.encryptionKeySalt;
            dto.keyVersion = g.keyVersion;
            dto.memberCount = g.memberCount;
            dto.onlineCount = g.onlineCount;
            dto.updatedAt = g.updatedAt;
            pkt.group = dto;
            sendPacketToPeer(peerId, pkt);
        }
    }

    private void handleGroupSync(NetPacket pkt) {
        if (pkt.group == null) return;
        NetPacket.GroupDto dto = pkt.group;
        GroupEntity local = db.groupDao().getGroupSync(dto.groupId);
        if (local != null && local.updatedAt > dto.updatedAt) return;
        GroupEntity entity = new GroupEntity(
                dto.groupId,
                dto.name,
                dto.description,
                local != null ? local.iconPath : "",
                dto.isPrivate,
                dto.pinSalt,
                local != null ? local.pinHash : "",
                dto.ownerId,
                dto.creatorName,
                dto.adminsOnlySend,
                local != null && local.isJoined,
                local != null && local.isMuted,
                dto.encryptionKeySalt,
                dto.keyVersion,
                dto.memberCount,
                dto.onlineCount,
                dto.updatedAt
        );
        db.groupDao().upsertGroup(entity);
        if (pkt.members != null) {
            for (NetPacket.GroupMemberDto md : pkt.members) {
                db.groupDao().upsertMember(new GroupMemberEntity(
                        md.groupId, md.deviceId, md.displayName, md.role, md.isOnline, md.joinedAt
                ));
            }
        }
    }

    private void handleGroupAuthChallenge(NetPacket pkt) {
        if (pkt.groupId == null) return;
        GroupEntity group = db.groupDao().getGroupSync(pkt.groupId);
        if (group == null) return;

        if (pkt.challengeNonce == null || pkt.challengeNonce.isEmpty()) {
            String nonce = crypto.generateNonceBase64();
            pendingGroupNonces.put(pkt.groupId + ":" + pkt.senderId, nonce);
            NetPacket resp = buildBasePacket(NetPacket.TYPE_GROUP_AUTH_CHALLENGE);
            resp.groupId = pkt.groupId;
            resp.targetId = pkt.senderId;
            resp.challengeNonce = nonce;
            resp.pinSalt = group.pinSalt;
            sendPacketToPeer(pkt.senderId, resp);
        } else {
            String rawPin = pendingPinCache.remove(pkt.groupId);
            if (rawPin == null) return;
            try {
                String salt = pkt.pinSalt != null && !pkt.pinSalt.isEmpty() ? pkt.pinSalt : group.pinSalt;
                String proof = crypto.computeChallengeResponse(rawPin, salt, pkt.challengeNonce,
                        pkt.groupId, prefs.getOrCreateDeviceId());
                cacheGroupDerivedKey(pkt.groupId, rawPin, group.encryptionKeySalt);
                NetPacket verify = buildBasePacket(NetPacket.TYPE_GROUP_AUTH_VERIFY);
                verify.groupId = pkt.groupId;
                verify.targetId = pkt.senderId;
                verify.challengeResponse = proof;
                sendPacketToPeer(pkt.senderId, verify);
            } catch (Exception ignored) {
            }
        }
    }

    private void handleGroupAuthVerify(NetPacket pkt) {
        if (pkt.groupId == null) return;
        GroupEntity group = db.groupDao().getGroupSync(pkt.groupId);
        if (group == null) return;

        if (pkt.challengeResponse != null && !pkt.challengeResponse.isEmpty()) {
            String rateKey = pkt.groupId + ":" + pkt.senderId;
            long locked = crypto.getRemainingLockoutSeconds(rateKey);
            NetPacket reply = buildBasePacket(NetPacket.TYPE_GROUP_AUTH_VERIFY);
            reply.groupId = pkt.groupId;
            reply.targetId = pkt.senderId;

            if (locked > 0) {
                reply.challengeAccepted = false;
                reply.errorMessage = "Locked for " + locked + "s";
                sendPacketToPeer(pkt.senderId, reply);
                return;
            }

            String nonce = pendingGroupNonces.remove(rateKey);
            boolean ok = nonce != null && crypto.verifyChallengeResponse(
                    group.pinHash, nonce, pkt.groupId, pkt.senderId, pkt.challengeResponse);
            if (ok) {
                crypto.resetAttempts(rateKey);
                reply.challengeAccepted = true;
                db.groupDao().upsertMember(new GroupMemberEntity(
                        pkt.groupId, pkt.senderId, pkt.senderName, "MEMBER", true, System.currentTimeMillis()
                ));
            } else {
                long newLock = crypto.recordFailedAttempt(rateKey);
                reply.challengeAccepted = false;
                reply.errorMessage = newLock > 0 ? ("Locked for " + newLock + "s") : "Invalid PIN";
            }
            sendPacketToPeer(pkt.senderId, reply);
        } else {
            if (pkt.challengeAccepted) {
                db.groupDao().setJoined(pkt.groupId, true);
                UserProfileEntity me = db.userProfileDao().getProfileSync();
                if (me != null) {
                    db.groupDao().upsertMember(new GroupMemberEntity(
                            pkt.groupId, me.deviceId, me.name, "MEMBER", true, System.currentTimeMillis()
                    ));
                }
            }
            if (groupAuthListener != null) {
                new Handler(Looper.getMainLooper()).post(() ->
                        groupAuthListener.onGroupAuthResult(pkt.groupId, pkt.challengeAccepted, pkt.errorMessage));
            }
        }
    }

    private void sendWebSnapshot(NanoWSD.WebSocket socket, String requesterPeerId) {
        try {
            if (localHttpServer == null) return;
            Map<String, Object> snap = localHttpServer.buildInfoSnapshot(requesterPeerId);
            snap.put("version", 1);
            snap.put("type", "WEB_SNAPSHOT");
            snap.put("event", "WEB_SNAPSHOT");
            snap.put("timestamp", System.currentTimeMillis());
            socket.send(gson.toJson(snap));
        } catch (Exception ignored) {
        }
    }

    public NetPacket buildBasePacket(String type) {
        NetPacket pkt = new NetPacket();
        pkt.version = 1;
        pkt.packetId = UUID.randomUUID().toString();
        pkt.type = type;
        pkt.event = type;
        pkt.timestamp = System.currentTimeMillis();
        UserProfileEntity me = db.userProfileDao().getProfileSync();
        NetworkUtils.LocalNetworkInfo net = NetworkUtils.detectLocalNetwork(context);
        pkt.senderId = me != null ? me.deviceId : prefs.getOrCreateDeviceId();
        pkt.senderName = me != null ? me.name : "Peer";
        pkt.senderBio = me != null ? me.bio : "";
        pkt.senderStatus = prefs.getActiveStatus();
        pkt.senderIp = net.ipAddress;
        pkt.senderPort = prefs.getServerPort();
        pkt.connectionMode = net.routeType;
        pkt.avatarBase64 = me != null ? AvatarHelper.encodeAvatarBase64(me.avatarPath) : "";
        return pkt;
    }

    private void relayToChatWebClients(String chatId, String excludeSenderId, String wireJson) {
        if (localHttpServer == null || chatId == null) return;
        if (chatId.startsWith("dm:")) {
            String other = extractOtherDmPeerId(chatId, excludeSenderId != null ? excludeSenderId : "");
            if (other != null) localHttpServer.sendToWebClient(other, wireJson);
            return;
        }
        GroupEntity grp = db.groupDao().getGroupSync(chatId);
        if (grp != null && grp.isPrivate) {
            List<GroupMemberEntity> members = db.groupDao().getMembersSync(grp.groupId);
            if (members != null) {
                for (GroupMemberEntity m : members) {
                    if (m.deviceId != null && !m.deviceId.equals(excludeSenderId)) {
                        localHttpServer.sendToWebClient(m.deviceId, wireJson);
                    }
                }
            }
        } else {
            localHttpServer.broadcastToWebClientsExcept(wireJson, excludeSenderId);
        }
    }

    private NetPacket.MessageDto toDto(MessageEntity e) {
        NetPacket.MessageDto d = new NetPacket.MessageDto();
        d.messageId = e.messageId;
        d.chatId = e.chatId;
        d.senderId = e.senderId;
        d.senderName = e.senderName;
        d.senderIsWeb = e.senderIsWeb;
        d.type = e.type;
        d.content = e.content;
        d.replyToMessageId = e.replyToMessageId;
        d.replyToPreview = e.replyToPreview;
        d.forwardedFrom = e.forwardedFrom;
        d.attachmentId = e.attachmentId;
        d.attachmentName = e.attachmentName;
        d.attachmentMime = e.attachmentMime;
        d.attachmentSize = e.attachmentSize;
        d.attachmentSha256 = e.attachmentSha256;
        d.hlcTimestamp = e.hlcTimestamp;
        d.hlcCounter = e.hlcCounter;
        d.createdAt = e.createdAt;
        d.isDeletedForEveryone = e.isDeletedForEveryone;
        d.reactionsJson = e.reactionsJson;
        d.isEncrypted = e.isEncrypted;
        d.thumbnailBase64 = e.thumbnailBase64;
        d.isEdited = e.isEdited;
        d.editedAt = e.editedAt;
        return d;
    }

    private String classifyMessageType(String mime, String fileName) {
        String m = mime != null ? mime.toLowerCase() : "";
        String n = fileName != null ? fileName.toLowerCase() : "";
        if (m.startsWith("image/") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".gif")) {
            return "IMAGE";
        }
        if (m.startsWith("video/") || n.endsWith(".mp4") || n.endsWith(".webm") || n.endsWith(".mkv")) {
            return "VIDEO";
        }
        if (m.startsWith("audio/") || n.endsWith(".m4a") || n.endsWith(".mp3") || n.endsWith(".ogg") || n.endsWith(".wav")) {
            return "AUDIO";
        }
        return "DOCUMENT";
    }

    private class PeerClientSocket extends WebSocketClient {
        private final String key;
        private final String ip;
        private final int port;

        PeerClientSocket(URI serverUri, String key, String ip, int port) {
            super(serverUri);
            this.key = key;
            this.ip = ip;
            this.port = port;
            setConnectionLostTimeout(10);
        }

        @Override
        public void onOpen(ServerHandshake handshakedata) {
            AppDatabase.DB_EXECUTOR.execute(() -> {
                NetPacket hello = buildBasePacket(NetPacket.TYPE_HELLO);
                send(serializePacket(hello));
                flushPendingMessagesInternal(key);
            });
        }

        @Override
        public void onMessage(String message) {
            try {
                NetPacket pkt = normalizeIncomingPacket(message);
                handleIncomingPacket(pkt, ip, null);
            } catch (Exception ignored) {
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            outboundClients.remove(key);
        }

        @Override
        public void onError(Exception ex) {
            outboundClients.remove(key);
        }
    }
}
