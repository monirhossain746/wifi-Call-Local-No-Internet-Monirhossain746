package com.android.wificalllocal.data.local;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.android.wificalllocal.data.local.dao.CallLogDao;
import com.android.wificalllocal.data.local.dao.ChatDao;
import com.android.wificalllocal.data.local.dao.FileTransferDao;
import com.android.wificalllocal.data.local.dao.GroupDao;
import com.android.wificalllocal.data.local.dao.MessageDao;
import com.android.wificalllocal.data.local.dao.PeerDao;
import com.android.wificalllocal.data.local.dao.SavedContactDao;
import com.android.wificalllocal.data.local.dao.UserProfileDao;
import com.android.wificalllocal.data.local.entity.CallLogEntity;
import com.android.wificalllocal.data.local.entity.ChatEntity;
import com.android.wificalllocal.data.local.entity.FileTransferEntity;
import com.android.wificalllocal.data.local.entity.GroupEntity;
import com.android.wificalllocal.data.local.entity.GroupMemberEntity;
import com.android.wificalllocal.data.local.entity.MessageEntity;
import com.android.wificalllocal.data.local.entity.PeerEntity;
import com.android.wificalllocal.data.local.entity.SavedContactEntity;
import com.android.wificalllocal.data.local.entity.UserProfileEntity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Database(
        entities = {
                UserProfileEntity.class,
                PeerEntity.class,
                ChatEntity.class,
                MessageEntity.class,
                GroupEntity.class,
                GroupMemberEntity.class,
                CallLogEntity.class,
                FileTransferEntity.class,
                SavedContactEntity.class
        },
        version = 4,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    public static final String EVERYONE_ROOM_ID = "everyone_room";

    public abstract UserProfileDao userProfileDao();
    public abstract PeerDao peerDao();
    public abstract ChatDao chatDao();
    public abstract MessageDao messageDao();
    public abstract GroupDao groupDao();
    public abstract CallLogDao callLogDao();
    public abstract FileTransferDao fileTransferDao();
    public abstract SavedContactDao savedContactDao();

    public static final ExecutorService DB_EXECUTOR = Executors.newFixedThreadPool(4);

    public static final androidx.room.migration.Migration MIGRATION_1_2 =
            new androidx.room.migration.Migration(1, 2) {
                @Override
                public void migrate(@androidx.annotation.NonNull androidx.sqlite.db.SupportSQLiteDatabase db) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN thumbnailBase64 TEXT");
                }
            };

    public static final androidx.room.migration.Migration MIGRATION_2_3 =
            new androidx.room.migration.Migration(2, 3) {
                @Override
                public void migrate(@androidx.annotation.NonNull androidx.sqlite.db.SupportSQLiteDatabase db) {
                    db.execSQL("ALTER TABLE peers ADD COLUMN isMuted INTEGER NOT NULL DEFAULT 0");
                }
            };

    public static final androidx.room.migration.Migration MIGRATION_3_4 =
            new androidx.room.migration.Migration(3, 4) {
                @Override
                public void migrate(@androidx.annotation.NonNull androidx.sqlite.db.SupportSQLiteDatabase db) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN isEdited INTEGER NOT NULL DEFAULT 0");
                    db.execSQL("ALTER TABLE messages ADD COLUMN editedAt INTEGER NOT NULL DEFAULT 0");
                }
            };

    public static AppDatabase build(Context context) {
        return Room.databaseBuilder(
                context.getApplicationContext(),
                AppDatabase.class,
                "wifi_call_local.db"
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).fallbackToDestructiveMigration(false).build();
    }
}
