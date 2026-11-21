package com.android.wificalllocal.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "file_transfers")
public class FileTransferEntity {
    @PrimaryKey
    @NonNull
    public String transferId;
    public String messageId;
    public String chatId;
    @NonNull
    public String fileName;
    public String mimeType;
    public long totalSize;
    public int chunkSize;
    public int totalChunks;
    public int completedChunks;
    public String sha256;
    public String sourcePeerIdsJson;
    public String localUri;
    public String savedPath;
    @NonNull
    public String direction; // UPLOAD, DOWNLOAD
    @NonNull
    public String state; // QUEUED, TRANSFERRING, PAUSED, COMPLETED, FAILED, WAITING_SEEDER, CANCELLED
    public long speedBytesPerSec;
    public String manifestJson;
    public long createdAt;
    public long updatedAt;

    public FileTransferEntity(@NonNull String transferId, String messageId, String chatId,
                              @NonNull String fileName, String mimeType, long totalSize,
                              int chunkSize, int totalChunks, int completedChunks,
                              String sha256, String sourcePeerIdsJson, String localUri,
                              String savedPath, @NonNull String direction, @NonNull String state,
                              long speedBytesPerSec, String manifestJson, long createdAt, long updatedAt) {
        this.transferId = transferId;
        this.messageId = messageId;
        this.chatId = chatId;
        this.fileName = fileName;
        this.mimeType = mimeType != null ? mimeType : "application/octet-stream";
        this.totalSize = totalSize;
        this.chunkSize = chunkSize;
        this.totalChunks = totalChunks;
        this.completedChunks = completedChunks;
        this.sha256 = sha256 != null ? sha256 : "";
        this.sourcePeerIdsJson = sourcePeerIdsJson != null ? sourcePeerIdsJson : "[]";
        this.localUri = localUri;
        this.savedPath = savedPath;
        this.direction = direction;
        this.state = state;
        this.speedBytesPerSec = speedBytesPerSec;
        this.manifestJson = manifestJson;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
