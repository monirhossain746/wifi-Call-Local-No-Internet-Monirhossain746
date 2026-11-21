package com.android.wificalllocal.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "call_logs")
public class CallLogEntity {
    @PrimaryKey
    @NonNull
    public String callId;
    @NonNull
    public String peerId;
    @NonNull
    public String peerName;
    public String peerIp;
    @NonNull
    public String callType; // VOICE, VIDEO, GROUP_VOICE, GROUP_VIDEO
    @NonNull
    public String direction; // INCOMING, OUTGOING, MISSED
    @NonNull
    public String status; // COMPLETED, MISSED, REJECTED, BUSY
    public long durationSeconds;
    public long timestamp;

    public CallLogEntity(@NonNull String callId, @NonNull String peerId, @NonNull String peerName,
                         String peerIp, @NonNull String callType, @NonNull String direction,
                         @NonNull String status, long durationSeconds, long timestamp) {
        this.callId = callId;
        this.peerId = peerId;
        this.peerName = peerName;
        this.peerIp = peerIp != null ? peerIp : "";
        this.callType = callType;
        this.direction = direction;
        this.status = status;
        this.durationSeconds = durationSeconds;
        this.timestamp = timestamp;
    }
}
