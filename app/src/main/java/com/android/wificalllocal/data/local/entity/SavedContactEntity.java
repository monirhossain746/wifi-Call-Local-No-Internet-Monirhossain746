package com.android.wificalllocal.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "saved_contacts")
public class SavedContactEntity {
    @PrimaryKey
    @NonNull
    public String contactId;
    @NonNull
    public String displayName;
    public String deviceId;
    public String phoneOrIp;
    public String notes;
    public long addedAt;

    public SavedContactEntity(@NonNull String contactId, @NonNull String displayName,
                              String deviceId, String phoneOrIp, String notes, long addedAt) {
        this.contactId = contactId;
        this.displayName = displayName;
        this.deviceId = deviceId;
        this.phoneOrIp = phoneOrIp != null ? phoneOrIp : "";
        this.notes = notes != null ? notes : "";
        this.addedAt = addedAt;
    }
}
