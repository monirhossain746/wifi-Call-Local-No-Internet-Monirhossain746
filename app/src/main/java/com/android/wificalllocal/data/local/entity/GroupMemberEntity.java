package com.android.wificalllocal.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;

@Entity(tableName = "group_members", primaryKeys = {"groupId", "deviceId"})
public class GroupMemberEntity {
    @NonNull
    public String groupId;
    @NonNull
    public String deviceId;
    @NonNull
    public String displayName;
    @NonNull
    public String role; // OWNER, ADMIN, MEMBER
    public boolean isOnline;
    public long joinedAt;

    public GroupMemberEntity(@NonNull String groupId, @NonNull String deviceId,
                             @NonNull String displayName, @NonNull String role,
                             boolean isOnline, long joinedAt) {
        this.groupId = groupId;
        this.deviceId = deviceId;
        this.displayName = displayName;
        this.role = role;
        this.isOnline = isOnline;
        this.joinedAt = joinedAt;
    }
}
