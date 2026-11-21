package com.android.wificalllocal.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.android.wificalllocal.data.local.entity.FileTransferEntity;

import java.util.List;

@Dao
public interface FileTransferDao {
    @Query("SELECT * FROM file_transfers ORDER BY updatedAt DESC")
    LiveData<List<FileTransferEntity>> observeAll();

    @Query("SELECT * FROM file_transfers ORDER BY updatedAt DESC")
    List<FileTransferEntity> getAllSync();

    @Query("SELECT * FROM file_transfers WHERE transferId = :transferId LIMIT 1")
    FileTransferEntity getByIdSync(String transferId);

    @Query("SELECT * FROM file_transfers WHERE transferId = :transferId LIMIT 1")
    LiveData<FileTransferEntity> observeById(String transferId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(FileTransferEntity transfer);

    @Query("UPDATE file_transfers SET state = :state, completedChunks = :completedChunks, speedBytesPerSec = :speed, savedPath = :savedPath, updatedAt = :updatedAt WHERE transferId = :transferId")
    void updateProgress(String transferId, String state, int completedChunks, long speed, String savedPath, long updatedAt);
}
