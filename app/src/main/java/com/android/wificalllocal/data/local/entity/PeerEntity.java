package com.android.wificalllocal.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "peers")
public class PeerEntity {
    @PrimaryKey
    @NonNull
    public String deviceId;
    @NonNull
    public String name;
    public String bio;
    public String ipAddress;
    public int port;
    public String connectionMode; // WIFI, HOTSPOT, WIFI_DIRECT, WEB
    public String activeStatus; // ONLINE, AWAY, BUSY, DND, OFFLINE
    public boolean isOnline;
    public boolean isFriend;
    public boolean isBlocked;
    public boolean isWebUser;
    public String avatarBase64;
    public long lastSeenAt;
    public long lastHeartbeatAt;
    public boolean isMuted;

    @androidx.room.Ignore
    public PeerEntity(@NonNull String deviceId, @NonNull String name, String bio,
                      String ipAddress, int port, String connectionMode,
                      String activeStatus, boolean isOnline, boolean isFriend,
                      boolean isBlocked, boolean isWebUser, String avatarBase64,
                      long lastSeenAt, long lastHeartbeatAt) {
        this(deviceId, name, bio, ipAddress, port, connectionMode, activeStatus,
                isOnline, isFriend, isBlocked, isWebUser, avatarBase64, lastSeenAt, lastHeartbeatAt, false);
    }

    public PeerEntity(@NonNull String deviceId, @NonNull String name, String bio,
                      String ipAddress, int port, String connectionMode,
                      String activeStatus, boolean isOnline, boolean isFriend,
                      boolean isBlocked, boolean isWebUser, String avatarBase64,
                      long lastSeenAt, long lastHeartbeatAt, boolean isMuted) {
        this.deviceId = deviceId;
        this.name = name;
        this.bio = bio != null ? bio : "";
        this.ipAddress = ipAddress != null ? ipAddress : "";
        this.port = port;
        this.connectionMode = connectionMode != null ? connectionMode : "WIFI";
        this.activeStatus = activeStatus != null ? activeStatus : "ONLINE";
        this.isOnline = isOnline;
        this.isFriend = isFriend;
        this.isBlocked = isBlocked;
        this.isWebUser = isWebUser;
        this.avatarBase64 = avatarBase64 != null ? avatarBase64 : "";
        this.lastSeenAt = lastSeenAt;
        this.lastHeartbeatAt = lastHeartbeatAt;
        this.isMuted = isMuted;
    }
}
