package com.android.wificalllocal.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.android.wificalllocal.data.local.entity.ChatEntity;

import java.util.List;

@Dao
public interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY isPinned DESC, lastMessageTimestamp DESC")
    LiveData<List<ChatEntity>> observeAllChats();

    @Query("SELECT * FROM chats WHERE title LIKE '%' || :query || '%' OR lastMessagePreview LIKE '%' || :query || '%' ORDER BY isPinned DESC, lastMessageTimestamp DESC")
    LiveData<List<ChatEntity>> searchChats(String query);

    @Query("SELECT * FROM chats WHERE chatId = :chatId LIMIT 1")
    ChatEntity getChatSync(String chatId);

    @Query("SELECT * FROM chats ORDER BY isPinned DESC, lastMessageTimestamp DESC")
    List<ChatEntity> getAllChatsSync();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(ChatEntity chat);

    @Query("UPDATE chats SET unreadCount = 0 WHERE chatId = :chatId")
    void markRead(String chatId);

    @Query("UPDATE chats SET isMuted = :muted WHERE chatId = :chatId")
    void setMuted(String chatId, boolean muted);

    @Query("DELETE FROM chats WHERE chatId = :chatId")
    void deleteChat(String chatId);
}
