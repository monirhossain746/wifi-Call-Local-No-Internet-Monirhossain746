package com.android.wificalllocal.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "groups")
public class GroupEntity {
    @PrimaryKey
    @NonNull
    public String groupId;
    @NonNull
    public String name;
    public String description;
    public String iconPath;
    public boolean isPrivate;
    public String pinSalt;
    public String pinHash;
    @NonNull
    public String ownerId;
    public String creatorName;
    public boolean adminsOnlySend;
    public boolean isJoined;
    public boolean isMuted;
    public String encryptionKeySalt;
    public int keyVersion;
    public int memberCount;
    public int onlineCount;
    public long updatedAt;

    public GroupEntity(@NonNull String groupId, @NonNull String name, String description,
                       String iconPath, boolean isPrivate, String pinSalt, String pinHash,
                       @NonNull String ownerId, String creatorName, boolean adminsOnlySend,
                       boolean isJoined, boolean isMuted, String encryptionKeySalt,
                       int keyVersion, int memberCount, int onlineCount, long updatedAt) {
        this.groupId = groupId;
        this.name = name;
        this.description = description != null ? description : "";
        this.iconPath = iconPath != null ? iconPath : "";
        this.isPrivate = isPrivate;
        this.pinSalt = pinSalt != null ? pinSalt : "";
        this.pinHash = pinHash != null ? pinHash : "";
        this.ownerId = ownerId;
        this.creatorName = creatorName != null ? creatorName : "";
        this.adminsOnlySend = adminsOnlySend;
        this.isJoined = isJoined;
        this.isMuted = isMuted;
        this.encryptionKeySalt = encryptionKeySalt != null ? encryptionKeySalt : "";
        this.keyVersion = keyVersion;
        this.memberCount = memberCount;
        this.onlineCount = onlineCount;
        this.updatedAt = updatedAt;
    }
}
