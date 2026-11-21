package com.android.wificalllocal.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.android.wificalllocal.data.local.entity.MessageEntity;

import java.util.List;

@Dao
public interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND isDeletedForMe = 0 ORDER BY hlcTimestamp ASC, hlcCounter ASC, createdAt ASC")
    LiveData<List<MessageEntity>> observeMessagesForChat(String chatId);

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND isDeletedForMe = 0 AND content LIKE '%' || :query || '%' ORDER BY hlcTimestamp ASC, hlcCounter ASC")
    LiveData<List<MessageEntity>> searchMessagesInChat(String chatId, String query);

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    MessageEntity getByIdSync(String messageId);

    @Query("SELECT messageId FROM messages WHERE chatId = :chatId")
    List<String> getMessageIdsForChatSync(String chatId);

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY hlcTimestamp ASC, hlcCounter ASC")
    List<MessageEntity> getMessagesForChatSync(String chatId);

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND isDeletedForMe = 0 AND attachmentName IS NOT NULL AND attachmentName != '' ORDER BY createdAt DESC")
    List<MessageEntity> getSharedFilesForChatSync(String chatId);

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND isDeletedForMe = 0 AND attachmentName IS NOT NULL AND attachmentName != '' ORDER BY createdAt DESC")
    LiveData<List<MessageEntity>> observeSharedFilesForChat(String chatId);

    @Query("SELECT * FROM messages WHERE status = 'SENDING' AND isDeletedForMe = 0 ORDER BY createdAt ASC")
    List<MessageEntity> getPendingOutgoingSync();

    @Query("SELECT DISTINCT chatId FROM messages")
    List<String> getDistinctChatIdsSync();

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertIfAbsent(MessageEntity message);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(MessageEntity message);

    @Query("UPDATE messages SET status = :status WHERE messageId = :messageId")
    void updateStatus(String messageId, String status);

    @Query("UPDATE messages SET isDeletedForMe = 1 WHERE messageId = :messageId")
    void deleteForMe(String messageId);

    @Query("UPDATE messages SET isDeletedForEveryone = 1, content = '' WHERE messageId = :messageId")
    void deleteForEveryone(String messageId);

    @Query("UPDATE messages SET content = :content, isEdited = 1, editedAt = :editedAt WHERE messageId = :messageId")
    void editMessage(String messageId, String content, long editedAt);

    @Query("UPDATE messages SET reactionsJson = :reactionsJson WHERE messageId = :messageId")
    void updateReactions(String messageId, String reactionsJson);

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    void clearChatMessages(String chatId);
}
