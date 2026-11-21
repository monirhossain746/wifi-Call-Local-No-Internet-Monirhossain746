package com.android.wificalllocal.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "messages", indices = {@Index("chatId"), @Index("hlcTimestamp")})
public class MessageEntity {
    @PrimaryKey
    @NonNull
    public String messageId;
    @NonNull
    public String chatId;
    @NonNull
    public String senderId;
    @NonNull
    public String senderName;
    public boolean senderIsWeb;
    @NonNull
    public String type; // TEXT, IMAGE, VIDEO, AUDIO, VOICE, DOCUMENT, SYSTEM
    public String content;
    public String replyToMessageId;
    public String replyToPreview;
    public String forwardedFrom;
    public String attachmentId;
    public String attachmentName;
    public String attachmentMime;
    public long attachmentSize;
    public String attachmentSha256;
    public String localFilePath;
    @NonNull
    public String status; // SENDING, SENT, DELIVERED, SEEN, FAILED
    public long hlcTimestamp;
    public int hlcCounter;
    public long createdAt;
    public boolean isDeletedForMe;
    public boolean isDeletedForEveryone;
    public String reactionsJson; // JSON map: {"👍":[{"id":"...","name":"..."}]}
    public boolean isEncrypted;
    public String thumbnailBase64;
    public boolean isEdited;
    public long editedAt;

    @androidx.room.Ignore
    public MessageEntity(@NonNull String messageId, @NonNull String chatId, @NonNull String senderId,
                         @NonNull String senderName, boolean senderIsWeb, @NonNull String type,
                         String content, String replyToMessageId, String replyToPreview,
                         String forwardedFrom, String attachmentId, String attachmentName,
                         String attachmentMime, long attachmentSize, String attachmentSha256,
                         String localFilePath, @NonNull String status, long hlcTimestamp,
                         int hlcCounter, long createdAt, boolean isDeletedForMe,
                         boolean isDeletedForEveryone, String reactionsJson, boolean isEncrypted) {
        this(messageId, chatId, senderId, senderName, senderIsWeb, type, content,
                replyToMessageId, replyToPreview, forwardedFrom, attachmentId, attachmentName,
                attachmentMime, attachmentSize, attachmentSha256, localFilePath, status,
                hlcTimestamp, hlcCounter, createdAt, isDeletedForMe, isDeletedForEveryone,
                reactionsJson, isEncrypted, null, false, 0L);
    }

    @androidx.room.Ignore
    public MessageEntity(@NonNull String messageId, @NonNull String chatId, @NonNull String senderId,
                         @NonNull String senderName, boolean senderIsWeb, @NonNull String type,
                         String content, String replyToMessageId, String replyToPreview,
                         String forwardedFrom, String attachmentId, String attachmentName,
                         String attachmentMime, long attachmentSize, String attachmentSha256,
                         String localFilePath, @NonNull String status, long hlcTimestamp,
                         int hlcCounter, long createdAt, boolean isDeletedForMe,
                         boolean isDeletedForEveryone, String reactionsJson, boolean isEncrypted,
                         String thumbnailBase64) {
        this(messageId, chatId, senderId, senderName, senderIsWeb, type, content,
                replyToMessageId, replyToPreview, forwardedFrom, attachmentId, attachmentName,
                attachmentMime, attachmentSize, attachmentSha256, localFilePath, status,
                hlcTimestamp, hlcCounter, createdAt, isDeletedForMe, isDeletedForEveryone,
                reactionsJson, isEncrypted, thumbnailBase64, false, 0L);
    }

    public MessageEntity(@NonNull String messageId, @NonNull String chatId, @NonNull String senderId,
                         @NonNull String senderName, boolean senderIsWeb, @NonNull String type,
                         String content, String replyToMessageId, String replyToPreview,
                         String forwardedFrom, String attachmentId, String attachmentName,
                         String attachmentMime, long attachmentSize, String attachmentSha256,
                         String localFilePath, @NonNull String status, long hlcTimestamp,
                         int hlcCounter, long createdAt, boolean isDeletedForMe,
                         boolean isDeletedForEveryone, String reactionsJson, boolean isEncrypted,
                         String thumbnailBase64, boolean isEdited, long editedAt) {
        this.messageId = messageId;
        this.chatId = chatId;
        this.senderId = senderId;
        this.senderName = senderName;
        this.senderIsWeb = senderIsWeb;
        this.type = type;
        this.content = content != null ? content : "";
        this.replyToMessageId = replyToMessageId;
        this.replyToPreview = replyToPreview;
        this.forwardedFrom = forwardedFrom;
        this.attachmentId = attachmentId;
        this.attachmentName = attachmentName;
        this.attachmentMime = attachmentMime;
        this.attachmentSize = attachmentSize;
        this.attachmentSha256 = attachmentSha256;
        this.localFilePath = localFilePath;
        this.status = status;
        this.hlcTimestamp = hlcTimestamp;
        this.hlcCounter = hlcCounter;
        this.createdAt = createdAt;
        this.isDeletedForMe = isDeletedForMe;
        this.isDeletedForEveryone = isDeletedForEveryone;
        this.reactionsJson = reactionsJson != null ? reactionsJson : "{}";
        this.isEncrypted = isEncrypted;
        this.thumbnailBase64 = thumbnailBase64;
        this.isEdited = isEdited;
        this.editedAt = editedAt;
    }
}
