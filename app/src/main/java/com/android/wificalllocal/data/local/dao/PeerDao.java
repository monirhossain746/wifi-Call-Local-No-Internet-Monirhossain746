package com.android.wificalllocal.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.android.wificalllocal.data.local.entity.PeerEntity;

import java.util.List;

@Dao
public interface PeerDao {
    @Query("SELECT * FROM peers WHERE isBlocked = 0 ORDER BY isOnline DESC, lastSeenAt DESC")
    LiveData<List<PeerEntity>> observeActivePeers();

    @Query("SELECT * FROM peers WHERE isBlocked = 1 ORDER BY name ASC")
    LiveData<List<PeerEntity>> observeBlockedPeers();

    @Query("SELECT * FROM peers WHERE isBlocked = 0 AND isOnline = 1")
    List<PeerEntity> getOnlinePeersSync();

    @Query("SELECT * FROM peers WHERE isBlocked = 0")
    List<PeerEntity> getAllPeersSync();

    @Query("SELECT * FROM peers WHERE deviceId = :deviceId LIMIT 1")
    PeerEntity getByIdSync(String deviceId);

    @Query("SELECT * FROM peers WHERE deviceId = :deviceId LIMIT 1")
    LiveData<PeerEntity> observeById(String deviceId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(PeerEntity peer);

    @Query("UPDATE peers SET isOnline = 0, activeStatus = 'OFFLINE' WHERE isOnline = 1 AND lastHeartbeatAt < :cutoffMs")
    void markStalePeersOffline(long cutoffMs);

    @Query("UPDATE peers SET isOnline = 0, activeStatus = 'OFFLINE' WHERE deviceId = :deviceId")
    void markPeerOffline(String deviceId);

    @Query("UPDATE peers SET isBlocked = :blocked WHERE deviceId = :deviceId")
    void setBlocked(String deviceId, boolean blocked);

    @Query("UPDATE peers SET isFriend = :friend WHERE deviceId = :deviceId")
    void setFriend(String deviceId, boolean friend);

    @Query("UPDATE peers SET isMuted = :muted WHERE deviceId = :deviceId")
    void setMuted(String deviceId, boolean muted);
}
