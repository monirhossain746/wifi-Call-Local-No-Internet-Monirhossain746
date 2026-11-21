package com.android.wificalllocal.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "chats")
public class ChatEntity {
    @PrimaryKey
    @NonNull
    public String chatId;
    @NonNull
    public String title;
    public boolean isGroup;
    public String peerDeviceId;
    public String lastMessageId;
    public String lastMessagePreview;
    public long lastMessageTimestamp;
    public int unreadCount;
    public boolean isMuted;
    public boolean isPinned;

    public ChatEntity(@NonNull String chatId, @NonNull String title, boolean isGroup,
                      String peerDeviceId, String lastMessageId, String lastMessagePreview,
                      long lastMessageTimestamp, int unreadCount, boolean isMuted, boolean isPinned) {
        this.chatId = chatId;
        this.title = title;
        this.isGroup = isGroup;
        this.peerDeviceId = peerDeviceId;
        this.lastMessageId = lastMessageId;
        this.lastMessagePreview = lastMessagePreview != null ? lastMessagePreview : "";
        this.lastMessageTimestamp = lastMessageTimestamp;
        this.unreadCount = unreadCount;
        this.isMuted = isMuted;
        this.isPinned = isPinned;
    }
}
