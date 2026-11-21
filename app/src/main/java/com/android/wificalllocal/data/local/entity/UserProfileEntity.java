package com.android.wificalllocal.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "user_profile")
public class UserProfileEntity {
    @PrimaryKey
    @NonNull
    public String deviceId;
    @NonNull
    public String name;
    public String bio;
    public String avatarPath;
    @NonNull
    public String activeStatus; // ONLINE, AWAY, BUSY, DND
    public long createdAt;
    public long updatedAt;

    public UserProfileEntity(@NonNull String deviceId, @NonNull String name, String bio,
                             String avatarPath, @NonNull String activeStatus,
                             long createdAt, long updatedAt) {
        this.deviceId = deviceId;
        this.name = name;
        this.bio = bio != null ? bio : "";
        this.avatarPath = avatarPath != null ? avatarPath : "";
        this.activeStatus = activeStatus;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
