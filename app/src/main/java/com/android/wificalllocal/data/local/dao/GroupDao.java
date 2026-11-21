package com.android.wificalllocal.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.android.wificalllocal.data.local.entity.GroupEntity;
import com.android.wificalllocal.data.local.entity.GroupMemberEntity;

import java.util.List;

@Dao
public interface GroupDao {
    @Query("SELECT * FROM groups ORDER BY isJoined DESC, name ASC")
    LiveData<List<GroupEntity>> observeAllGroups();

    @Query("SELECT * FROM groups WHERE groupId = :groupId LIMIT 1")
    LiveData<GroupEntity> observeGroup(String groupId);

    @Query("SELECT * FROM groups")
    List<GroupEntity> getAllGroupsSync();

    @Query("SELECT * FROM groups WHERE isPrivate = 0")
    List<GroupEntity> getPublicGroupsSync();

    @Query("SELECT * FROM groups WHERE groupId = :groupId LIMIT 1")
    GroupEntity getGroupSync(String groupId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertGroup(GroupEntity group);

    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY role ASC, displayName ASC")
    LiveData<List<GroupMemberEntity>> observeMembers(String groupId);

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    List<GroupMemberEntity> getMembersSync(String groupId);

    @Query("SELECT * FROM group_members WHERE groupId = :groupId AND deviceId = :deviceId LIMIT 1")
    GroupMemberEntity getMemberSync(String groupId, String deviceId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertMember(GroupMemberEntity member);

    @Query("DELETE FROM group_members WHERE groupId = :groupId AND deviceId = :deviceId")
    void removeMember(String groupId, String deviceId);

    @Query("UPDATE groups SET isJoined = :joined WHERE groupId = :groupId")
    void setJoined(String groupId, boolean joined);

    @Query("UPDATE groups SET isMuted = :muted WHERE groupId = :groupId")
    void setMuted(String groupId, boolean muted);
}
