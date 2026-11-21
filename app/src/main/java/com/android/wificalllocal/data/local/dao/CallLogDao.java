package com.android.wificalllocal.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.android.wificalllocal.data.local.entity.CallLogEntity;

import java.util.List;

@Dao
public interface CallLogDao {
    @Query("SELECT * FROM call_logs ORDER BY timestamp DESC")
    LiveData<List<CallLogEntity>> observeAll();

    @Query("SELECT * FROM call_logs ORDER BY timestamp DESC LIMIT :limit")
    List<CallLogEntity> getRecentSync(int limit);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(CallLogEntity log);

    @Query("DELETE FROM call_logs WHERE callId = :callId")
    void deleteById(String callId);
}
