package com.android.wificalllocal.data.local.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.android.wificalllocal.data.local.entity.SavedContactEntity;

import java.util.List;

@Dao
public interface SavedContactDao {
    @Query("SELECT * FROM saved_contacts ORDER BY displayName ASC")
    LiveData<List<SavedContactEntity>> observeAll();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(SavedContactEntity contact);

    @Query("DELETE FROM saved_contacts WHERE contactId = :contactId")
    void delete(String contactId);
}
