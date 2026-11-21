package com.android.wificalllocal.network;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;

import com.android.wificalllocal.ServiceLocator;
import com.android.wificalllocal.control.AppControlManager;
import com.android.wificalllocal.data.local.AppDatabase;
import com.android.wificalllocal.data.local.entity.CallLogEntity;
import com.android.wificalllocal.data.local.entity.FileTransferEntity;
import com.android.wificalllocal.data.local.entity.GroupEntity;
import com.android.wificalllocal.data.local.entity.GroupMemberEntity;
import com.android.wificalllocal.data.local.entity.MessageEntity;
import com.android.wificalllocal.data.local.entity.PeerEntity;
import com.android.wificalllocal.data.local.entity.UserProfileEntity;
import com.android.wificalllocal.files.FileTransferManager;
import com.android.wificalllocal.network.model.NetPacket;
import com.android.wificalllocal.security.CryptoManager;
import com.android.wificalllocal.storage.CacheManager;
import com.google.gson.Gson;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import fi.iki.elonen.NanoWSD;

public class LocalHttpServer extends NanoWSD {

    private static final long MAX_WEB_UPLOAD_BYTES = 100L * 1024L * 1024L;

    private final Context context;
    private final AppDatabase db;
    private final FileTransferManager fileTransferManager;
    private final PeerConnectionManager peerManager;
    private final Gson gson = new Gson();
    private final java.util.Set<PeerServerSocket> activeWebSockets = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile boolean listening = false;

    public LocalHttpServer(Context context, int port, AppDatabase db,
                           FileTransferManager fileTransferManager, PeerConnectionManager peerManager) {
        super(port);
        this.context = context.getApplicationContext();
        this.db = db;
        this.fileTransferManager = fileTransferManager;
        this.peerManager = peerManager;
    }

    @Override
    public void start(int timeout, boolean daemon) throws IOException {
        super.start(timeout, daemon);
        listening = true;
    }

    @Override
    public void stop() {
        listening = false;
        for (PeerServerSocket ws : activeWebSockets) {
            try {
                ws.close(WebSocketFrame.CloseCode.GoingAway, "Server stopped", false);
            } catch (Exception ignored) {
            }
        }
        activeWebSockets.clear();
        super.stop();
    }

    public boolean isListening() {
        return listening && isAlive();
    }

    public void broadcastToWebClients(String jsonPayload) {
        broadcastToWebClientsExcept(jsonPayload, null);
    }

    public void broadcastPersonalizedSnapshots() {
        for (PeerServerSocket ws : activeWebSockets) {
            try {
                if (ws.isOpen()) {
                    Map<String, Object> snap = buildInfoSnapshot(ws.remotePeerId);
                    snap.put("version", 1);
                    snap.put("type", "WEB_SNAPSHOT");
                    snap.put("event", "WEB_SNAPSHOT");
                    snap.put("timestamp", System.currentTimeMillis());
                    ws.send(gson.toJson(snap));
                }
            } catch (Exception ignored) {
            }
        }
    }

    public void broadcastToWebClientsExcept(String jsonPayload, String excludePeerId) {
        for (PeerServerSocket ws : activeWebSockets) {
            try {
                if (ws.isOpen()) {
                    if (excludePeerId != null && excludePeerId.equals(ws.remotePeerId)) {
                        continue;
                    }
                    ws.send(jsonPayload);
                }
            } catch (Exception ignored) {
            }
        }
    }

    public int broadcastToWebClientsExcluding(String jsonPayload, java.util.Set<String> excludePeerIds) {
        int count = 0;
        for (PeerServerSocket ws : activeWebSockets) {
            try {
                if (ws.isOpen()) {
                    if (excludePeerIds != null && ws.remotePeerId != null && excludePeerIds.contains(ws.remotePeerId)) {
                        continue;
                    }
                    ws.send(jsonPayload);
                    count++;
                }
            } catch (Exception ignored) {
            }
        }
        return count;
    }

    private String jsonError(String error) {
        Map<String, Object> err = new HashMap<>();
        err.put("ok", false);
        err.put("error", error != null ? error : "Request failed");
        return gson.toJson(err);
    }

    public boolean sendToWebClient(String peerId, String jsonPayload) {
        if (peerId == null || peerId.isEmpty()) return false;
        boolean delivered = false;
        for (PeerServerSocket ws : activeWebSockets) {
            try {
                if (ws.isOpen() && peerId.equals(ws.remotePeerId)) {
                    ws.send(jsonPayload);
                    delivered = true;
                }
            } catch (Exception ignored) {
            }
        }
        return delivered;
    }

    private boolean hasOtherSocketForPeer(String peerId, PeerServerSocket excluding) {
        if (peerId == null) return false;
        for (PeerServerSocket ws : activeWebSockets) {
            if (ws != excluding && ws.isOpen() && peerId.equals(ws.remotePeerId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected WebSocket openWebSocket(IHTTPSession handshake) {
        return new PeerServerSocket(handshake);
    }

    @Override
    protected Response serveHttp(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        try {
            if ("/".equals(uri) || "/index.html".equals(uri)) {
                return serveAsset("web/index.html", "text/html; charset=utf-8");
            } else if ("/app.js".equals(uri)) {
                return serveAsset("web/app.js", "application/javascript; charset=utf-8");
            } else if ("/style.css".equals(uri)) {
                return serveAsset("web/style.css", "text/css; charset=utf-8");
            } else if ("/qrcode.min.js".equals(uri)) {
                return serveAsset("web/qrcode.min.js", "application/javascript; charset=utf-8");
            } else if ("/api/info".equals(uri) && Method.GET.equals(method)) {
                return handleApiInfo(session);
            } else if ("/api/web/join".equals(uri) && Method.POST.equals(method)) {
                return handleWebJoin(session);
            } else if ("/api/group/auth".equals(uri) && Method.POST.equals(method)) {
                return handleGroupAuth(session);
            } else if ("/api/chunk".equals(uri) && Method.GET.equals(method)) {
                return handleGetChunk(session);
            } else if ("/api/manifest".equals(uri) && Method.GET.equals(method)) {
                return handleGetManifest(session);
            } else if ("/api/thumb".equals(uri) && Method.GET.equals(method)) {
                return handleGetThumbnail(session);
            } else if ("/api/download".equals(uri) && Method.GET.equals(method)) {
                return handleDownloadFull(session);
            } else if ("/api/upload".equals(uri) && Method.POST.equals(method)) {
                return handleWebUpload(session);
            } else if ("/api/qr.png".equals(uri) && Method.GET.equals(method)) {
                return handleQrPng(session);
            }
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                    gson.toJson(Collections.singletonMap("error", "Request failed")));
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found");
    }

    private Response serveAsset(String assetPath, String mimeType) throws IOException {
        try (InputStream is = context.getAssets().open(assetPath);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) > 0) baos.write(buf, 0, r);
            byte[] data = baos.toByteArray();
            return newFixedLengthResponse(Response.Status.OK, mimeType,
                    new ByteArrayInputStream(data), data.length);
        }
    }

    public Map<String, Object> buildInfoSnapshot(String requesterPeerId) {
        UserProfileEntity me = db.userProfileDao().getProfileSync();
        NetworkUtils.LocalNetworkInfo net = NetworkUtils.detectLocalNetwork(context);
        List<PeerEntity> peers = new ArrayList<>(db.peerDao().getOnlinePeersSync());

        // Ensure Android host device appears in peer list for browser DMs & calls
        if (me != null) {
            boolean hostPresent = false;
            for (PeerEntity p : peers) {
                if (me.deviceId.equals(p.deviceId)) {
                    hostPresent = true;
                    break;
                }
            }
            if (!hostPresent) {
                peers.add(0, new PeerEntity(
                        me.deviceId,
                        me.name + " (Host)",
                        me.bio != null ? me.bio : "",
                        net.ipAddress,
                        getListeningPort(),
                        net.routeType,
                        "ONLINE",
                        true,
                        false,
                        false,
                        false,
                        "",
                        System.currentTimeMillis(),
                        System.currentTimeMillis()
                ));
            }
        }

        AppControlManager.AppGateState gate = peerManager.getGateState();
        boolean groupCallsEnabled = gate == null || gate.groupCallsEnabled;
        boolean fileTransferEnabled = gate == null || gate.fileTransferEnabled;
        boolean publicGroupsEnabled = gate == null || gate.publicGroupsEnabled;
        boolean everyoneGroupEnabled = gate == null || gate.everyoneGroupEnabled;

        List<GroupEntity> allGroups = db.groupDao().getAllGroupsSync();
        List<Map<String, Object>> safeGroups = new ArrayList<>();
        List<MessageEntity> collectedMsgs = new ArrayList<>();
        if (allGroups != null) {
            for (GroupEntity g : allGroups) {
                boolean isEveryone = AppDatabase.EVERYONE_ROOM_ID.equals(g.groupId);
                if (isEveryone && !everyoneGroupEnabled) continue;
                if (!isEveryone && !g.isPrivate && !publicGroupsEnabled) continue;

                boolean authorized = peerManager.isPeerAuthorizedForGroup(g, requesterPeerId);
                Map<String, Object> gm = new HashMap<>();
                gm.put("groupId", g.groupId);
                gm.put("name", g.name);
                gm.put("description", g.description != null ? g.description : "");
                gm.put("isPrivate", g.isPrivate);
                gm.put("ownerId", g.ownerId);
                gm.put("creatorName", g.creatorName != null ? g.creatorName : "");
                gm.put("adminsOnlySend", g.adminsOnlySend);
                gm.put("isJoined", authorized && (!g.isPrivate || (requesterPeerId != null && !requesterPeerId.isEmpty())));
                gm.put("authorized", authorized);
                gm.put("memberCount", g.memberCount);
                gm.put("onlineCount", g.onlineCount);
                gm.put("updatedAt", g.updatedAt);
                // Strip pinHash, pinSalt, and encryptionKeySalt completely from HTTP/WS snapshots
                if (authorized) {
                    List<GroupMemberEntity> members = db.groupDao().getMembersSync(g.groupId);
                    gm.put("members", members != null ? members : Collections.emptyList());
                    List<MessageEntity> msgs = db.messageDao().getMessagesForChatSync(g.groupId);
                    if (msgs != null) collectedMsgs.addAll(msgs);
                }
                safeGroups.add(gm);
            }
        }

        if (requesterPeerId != null && !requesterPeerId.isEmpty()) {
            for (PeerEntity p : peers) {
                if (!requesterPeerId.equals(p.deviceId)) {
                    String dmId = PeerConnectionManager.makeDmChatId(requesterPeerId, p.deviceId);
                    List<MessageEntity> dms = db.messageDao().getMessagesForChatSync(dmId);
                    if (dms != null) collectedMsgs.addAll(dms);
                }
            }
        }
        List<CallLogEntity> callLogs = db.callLogDao().getRecentSync(30);

        Map<String, Object> featureFlags = new HashMap<>();
        featureFlags.put("groupCallsEnabled", groupCallsEnabled);
        featureFlags.put("fileTransferEnabled", fileTransferEnabled);
        featureFlags.put("publicGroupsEnabled", publicGroupsEnabled);
        featureFlags.put("everyoneGroupEnabled", everyoneGroupEnabled);

        Map<String, Object> payload = new HashMap<>();
        payload.put("hostName", me != null ? me.name : "Wifi Call Local");
        payload.put("deviceId", me != null ? me.deviceId : "");
        payload.put("ip", net.ipAddress);
        payload.put("port", getListeningPort());
        payload.put("peers", peers);
        payload.put("groups", safeGroups);
        payload.put("messages", collectedMsgs);
        payload.put("callLogs", callLogs);
        payload.put("activeGroupCalls", peerManager.getActiveGroupCallsSnapshotForPeer(requesterPeerId));
        payload.put("featureFlags", featureFlags);
        return payload;
    }

    private Response handleApiInfo(IHTTPSession session) {
        String requesterPeerId = session.getParms().get("peerId");
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8",
                gson.toJson(buildInfoSnapshot(requesterPeerId)));
    }

    private Response handleWebJoin(IHTTPSession session) throws Exception {
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        String body = files.get("postData");
        Map<?, ?> req = gson.fromJson(body, Map.class);
        if (req == null || req.get("deviceId") == null || req.get("name") == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Missing fields\"}");
        }
        String id = String.valueOf(req.get("deviceId"));
        String name = String.valueOf(req.get("name")).trim();
        if (name.length() < 2 || name.length() > 30) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Invalid name\"}");
        }
        String remoteIp = session.getRemoteIpAddress();
        long now = System.currentTimeMillis();
        PeerEntity webPeer = new PeerEntity(id, name, "Browser Participant", remoteIp, getListeningPort(),
                "WEB", "ONLINE", true, false, false, true, "", now, now);
        db.peerDao().upsert(webPeer);
        peerManager.broadcastWebSnapshotToAll();
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"ok\":true}");
    }

    private Response handleGroupAuth(IHTTPSession session) throws Exception {
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        String body = files.get("postData");
        Map<?, ?> req = gson.fromJson(body, Map.class);
        if (req == null || req.get("groupId") == null || req.get("deviceId") == null || req.get("pin") == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                    "{\"ok\":false,\"error\":\"Missing groupId, deviceId, or pin\"}");
        }
        String groupId = String.valueOf(req.get("groupId"));
        String deviceId = String.valueOf(req.get("deviceId"));
        String displayName = req.get("name") != null ? String.valueOf(req.get("name")).trim() : "Web User";
        String pin = String.valueOf(req.get("pin")).trim();

        GroupEntity group = db.groupDao().getGroupSync(groupId);
        if (group == null) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json",
                    "{\"ok\":false,\"error\":\"Group not found\"}");
        }
        CryptoManager crypto = ServiceLocator.getInstance(context).getCryptoManager();
        String rateKey = "web:" + groupId + ":" + deviceId;
        long locked = crypto.getRemainingLockoutSeconds(rateKey);
        if (locked > 0) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
                    jsonError("Locked for " + locked + "s"));
        }

        boolean ok;
        if (!group.isPrivate) {
            ok = true;
        } else {
            ok = crypto.verifyPin(pin, group.pinSalt, group.pinHash);
        }

        if (!ok) {
            long lock = crypto.recordFailedAttempt(rateKey);
            String msg = lock > 0 ? ("Locked for " + lock + "s") : "Invalid PIN";
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
                    jsonError(msg));
        }

        crypto.resetAttempts(rateKey);
        db.groupDao().upsertMember(new GroupMemberEntity(
                groupId, deviceId, displayName, "MEMBER", true, System.currentTimeMillis()
        ));
        group.memberCount = Math.max(group.memberCount, db.groupDao().getMembersSync(groupId).size());
        group.updatedAt = System.currentTimeMillis();
        db.groupDao().upsertGroup(group);
        peerManager.syncGroupMetadata(group);
        peerManager.broadcastWebSnapshotToAll();
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"ok\":true}");
    }

    private Response handleGetChunk(IHTTPSession session) throws Exception {
        String transferId = session.getParms().get("transferId");
        String idxStr = session.getParms().get("index");
        if (!FileTransferManager.isValidTransferId(transferId) || idxStr == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid transferId or index");
        }
        int index;
        try {
            index = Integer.parseInt(idxStr);
        } catch (NumberFormatException nfe) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid chunk index");
        }
        if (index < 0) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid chunk index");
        }
        File chunkFile = new File(fileTransferManager.getChunkCacheDir(transferId), index + ".part");
        if (!chunkFile.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Chunk not found");
        }
        FileInputStream fis = new FileInputStream(chunkFile);
        return newFixedLengthResponse(Response.Status.OK, "application/octet-stream", fis, chunkFile.length());
    }

    private Response handleGetManifest(IHTTPSession session) {
        String transferId = session.getParms().get("transferId");
        if (!FileTransferManager.isValidTransferId(transferId)) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{}");
        }
        FileTransferEntity e = db.fileTransferDao().getByIdSync(transferId);
        if (e == null || e.manifestJson == null) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{}");
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", e.manifestJson);
    }

    private Response handleGetThumbnail(IHTTPSession session) throws Exception {
        String transferId = session.getParms().get("transferId");
        if (!FileTransferManager.isValidTransferId(transferId)) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid transferId");
        }
        File thumbFile = fileTransferManager.getThumbnailFile(transferId);
        if (thumbFile != null && thumbFile.exists() && thumbFile.length() > 0) {
            return newFixedLengthResponse(Response.Status.OK, "image/jpeg",
                    new FileInputStream(thumbFile), thumbFile.length());
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Thumbnail not found");
    }

    private Response handleDownloadFull(IHTTPSession session) throws Exception {
        String transferId = session.getParms().get("transferId");
        if (!FileTransferManager.isValidTransferId(transferId)) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid transferId");
        }
        FileTransferEntity e = db.fileTransferDao().getByIdSync(transferId);
        if (e == null) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found");
        }
        File dir = fileTransferManager.getChunkCacheDir(transferId);
        List<InputStream> streams = new ArrayList<>();
        long totalLength = 0L;
        try {
            for (int i = 0; i < e.totalChunks; i++) {
                File part = new File(dir, i + ".part");
                if (!part.exists()) {
                    closeQuietly(streams);
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File chunks incomplete");
                }
                totalLength += part.length();
                streams.add(new FileInputStream(part));
            }
        } catch (Exception ex) {
            closeQuietly(streams);
            throw ex;
        }
        InputStream combined = streams.isEmpty()
                ? new ByteArrayInputStream(new byte[0])
                : new SequenceInputStream(Collections.enumeration(streams));
        String safeFileName = FileTransferManager.sanitizeFileName(e.fileName);
        String mimeType = (e.mimeType != null && !e.mimeType.trim().isEmpty()) ? e.mimeType : "application/octet-stream";
        Response resp = newFixedLengthResponse(Response.Status.OK, mimeType, combined, totalLength);
        resp.addHeader("Content-Disposition", "attachment; filename=\"" + safeFileName + "\"");
        return resp;
    }

    private void closeQuietly(List<InputStream> streams) {
        for (InputStream is : streams) {
            try {
                is.close();
            } catch (IOException ignored) {
            }
        }
    }

    private Response handleWebUpload(IHTTPSession session) throws Exception {
        AppControlManager.AppGateState gate = peerManager.getGateState();
        if (gate != null && !gate.fileTransferEnabled) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json",
                    "{\"error\":\"File transfers are currently disabled via Remote Config\"}");
        }
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        String tempPath = files.get("file");
        String fileName = FileTransferManager.sanitizeFileName(session.getParms().get("file"));
        String chatId = session.getParms().get("chatId");
        if (chatId == null || chatId.isEmpty()) chatId = AppDatabase.EVERYONE_ROOM_ID;
        String targetId = session.getParms().get("targetId");
        String senderId = session.getParms().get("senderId");
        String senderName = session.getParms().get("senderName");
        if (senderId == null) senderId = "web-guest";
        if (senderName == null) senderName = "Web Guest";

        if (tempPath == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"No file\"}");
        }
        File uploaded = new File(tempPath);
        if (uploaded.length() > MAX_WEB_UPLOAD_BYTES) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                    "{\"error\":\"File exceeds 100 MB web upload limit\"}");
        }
        if (!chatId.startsWith("dm:")) {
            GroupEntity g = db.groupDao().getGroupSync(chatId);
            if (g != null && !peerManager.isPeerAuthorizedForGroup(g, senderId)) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json",
                        "{\"error\":\"Not authorized for private group\"}");
            }
        }
        String transferId = UUID.randomUUID().toString();
        String messageId = UUID.randomUUID().toString();
        File cacheDir = fileTransferManager.getChunkCacheDir(transferId);

        byte[] buf = new byte[FileTransferManager.CHUNK_SIZE_BYTES];
        List<String> chunkHashes = new ArrayList<>();
        MessageDigest fullDigest = MessageDigest.getInstance("SHA-256");
        int idx = 0;
        long total = 0;
        try (FileInputStream fis = new FileInputStream(uploaded)) {
            while (true) {
                int readTotal = 0;
                while (readTotal < buf.length) {
                    int r = fis.read(buf, readTotal, buf.length - readTotal);
                    if (r < 0) break;
                    readTotal += r;
                }
                if (readTotal <= 0) break;
                total += readTotal;
                fullDigest.update(buf, 0, readTotal);
                chunkHashes.add(CryptoManager.sha256Hex(buf, 0, readTotal));
                try (FileOutputStream fos = new FileOutputStream(new File(cacheDir, idx + ".part"))) {
                    fos.write(buf, 0, readTotal);
                }
                idx++;
            }
        }
        StringBuilder shaSb = new StringBuilder();
        for (byte b : fullDigest.digest()) shaSb.append(String.format("%02x", b));

        NetworkUtils.LocalNetworkInfo net = NetworkUtils.detectLocalNetwork(context);
        String mimeType = FileTransferManager.resolveMimeType(context, Uri.fromFile(new File(fileName)), fileName);
        String thumbBase64 = fileTransferManager.createSmallPreviewBase64(
                Uri.fromFile(uploaded).toString(), mimeType, cacheDir);

        NetPacket.FileManifestDto manifest = new NetPacket.FileManifestDto();
        manifest.transferId = transferId;
        manifest.messageId = messageId;
        manifest.chatId = chatId;
        manifest.fileName = fileName;
        manifest.mimeType = mimeType;
        manifest.totalSize = total;
        manifest.chunkSize = FileTransferManager.CHUNK_SIZE_BYTES;
        manifest.totalChunks = idx;
        manifest.fileSha256 = shaSb.toString();
        manifest.chunkSha256List = chunkHashes;
        manifest.seederDeviceId = senderId;
        manifest.seederPort = getListeningPort();
        manifest.seederIps = Collections.singletonList(net.ipAddress + ":" + getListeningPort());
        manifest.thumbnailBase64 = thumbBase64;

        long now = System.currentTimeMillis();
        FileTransferEntity ft = new FileTransferEntity(transferId, messageId, chatId, fileName,
                manifest.mimeType, total, FileTransferManager.CHUNK_SIZE_BYTES, idx, idx,
                manifest.fileSha256, gson.toJson(manifest.seederIps), null, null, "UPLOAD", "COMPLETED",
                0L, gson.toJson(manifest), now, now);
        db.fileTransferDao().upsert(ft);

        peerManager.publishWebFileMessage(chatId, targetId, senderId, senderName, messageId, manifest);
        CacheManager cm = ServiceLocator.getInstance(context).getCacheManager();
        long maxMb = gate != null ? gate.maxCacheSizeMb : CacheManager.DEFAULT_MAX_CACHE_MB;
        cm.enforceCacheLimitAsync(maxMb);
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"ok\":true}");
    }

    private Response handleQrPng(IHTTPSession session) throws Exception {
        String data = session.getParms().get("data");
        if (data == null || data.isEmpty()) {
            NetworkUtils.LocalNetworkInfo net = NetworkUtils.detectLocalNetwork(context);
            data = "http://" + net.ipAddress + ":" + getListeningPort();
        }
        BitMatrix matrix = new QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, 256, 256);
        Bitmap bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.RGB_565);
        for (int x = 0; x < 256; x++) {
            for (int y = 0; y < 256; y++) {
                bmp.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos);
        byte[] png = baos.toByteArray();
        return newFixedLengthResponse(Response.Status.OK, "image/png", new ByteArrayInputStream(png), png.length);
    }

    private class PeerServerSocket extends WebSocket {
        private volatile String remotePeerId;

        PeerServerSocket(IHTTPSession handshakeRequest) {
            super(handshakeRequest);
        }

        @Override
        protected void onOpen() {
            activeWebSockets.add(this);
        }

        @Override
        protected void onClose(WebSocketFrame.CloseCode code, String reason, boolean initiatedByRemote) {
            activeWebSockets.remove(this);
            if (remotePeerId != null && !hasOtherSocketForPeer(remotePeerId, this)) {
                peerManager.handleWebClientDisconnected(remotePeerId);
            }
        }

        @Override
        protected void onMessage(WebSocketFrame message) {
            try {
                String text = message.getTextPayload();
                if (text == null || text.length() > 1024 * 1024) return;
                NetPacket packet = peerManager.normalizeIncomingPacket(text);
                if (packet != null) {
                    if (packet.senderId != null && !packet.senderId.isEmpty()) {
                        this.remotePeerId = packet.senderId;
                    }
                    peerManager.handleIncomingPacket(packet, getHandshakeRequest().getRemoteIpAddress(), this);
                }
            } catch (Exception ignored) {
            }
        }

        @Override
        protected void onPong(WebSocketFrame pong) {
        }

        @Override
        protected void onException(IOException exception) {
            activeWebSockets.remove(this);
            if (remotePeerId != null && !hasOtherSocketForPeer(remotePeerId, this)) {
                peerManager.handleWebClientDisconnected(remotePeerId);
            }
        }
    }
}
