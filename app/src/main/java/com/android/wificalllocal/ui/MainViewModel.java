package com.android.wificalllocal.ui;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.android.wificalllocal.ServiceLocator;
import com.android.wificalllocal.data.local.AppDatabase;
import com.android.wificalllocal.data.local.entity.CallLogEntity;
import com.android.wificalllocal.data.local.entity.ChatEntity;
import com.android.wificalllocal.data.local.entity.FileTransferEntity;
import com.android.wificalllocal.data.local.entity.GroupEntity;
import com.android.wificalllocal.data.local.entity.GroupMemberEntity;
import com.android.wificalllocal.data.local.entity.PeerEntity;
import com.android.wificalllocal.data.local.entity.SavedContactEntity;
import com.android.wificalllocal.data.local.entity.UserProfileEntity;
import com.android.wificalllocal.network.DiscoveryManager;
import com.android.wificalllocal.network.PeerConnectionManager;
import com.android.wificalllocal.security.CryptoManager;
import com.android.wificalllocal.util.PreferencesManager;

import java.util.List;
import java.util.UUID;

public class MainViewModel extends AndroidViewModel {

    private final ServiceLocator locator;
    private final AppDatabase db;
    private final PreferencesManager prefs;
    private final MutableLiveData<String> chatSearchQuery = new MutableLiveData<>("");
    private final LiveData<List<ChatEntity>> chatsLive;

    public MainViewModel(@NonNull Application application) {
        super(application);
        this.locator = ServiceLocator.getInstance(application);
        this.db = locator.getDatabase();
        this.prefs = locator.getPreferencesManager();
        this.chatsLive = Transformations.switchMap(chatSearchQuery, q -> {
            if (q == null || q.trim().isEmpty()) {
                return db.chatDao().observeAllChats();
            }
            return db.chatDao().searchChats(q.trim());
        });
    }

    public LiveData<UserProfileEntity> observeProfile() {
        return db.userProfileDao().observeProfile();
    }

    public LiveData<List<PeerEntity>> observeActivePeers() {
        return db.peerDao().observeActivePeers();
    }

    public LiveData<List<PeerEntity>> observeBlockedPeers() {
        return db.peerDao().observeBlockedPeers();
    }

    public LiveData<List<ChatEntity>> observeChats() {
        return chatsLive;
    }

    public void setChatSearchQuery(String query) {
        chatSearchQuery.setValue(query);
    }

    public LiveData<List<GroupEntity>> observeAllGroups() {
        return db.groupDao().observeAllGroups();
    }

    public LiveData<List<CallLogEntity>> observeCallLogs() {
        return db.callLogDao().observeAll();
    }

    public LiveData<List<SavedContactEntity>> observeSavedContacts() {
        return db.savedContactDao().observeAll();
    }

    public LiveData<List<FileTransferEntity>> observeTransfers() {
        return db.fileTransferDao().observeAll();
    }

    public DiscoveryManager getDiscoveryManager() {
        return locator.getDiscoveryManager();
    }

    public PeerConnectionManager getPeerManager() {
        return locator.getPeerConnectionManager();
    }

    public void saveProfile(String name, String bio, String avatarPath) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            String deviceId = prefs.getOrCreateDeviceId();
            UserProfileEntity existing = db.userProfileDao().getProfileSync();
            long now = System.currentTimeMillis();
            long createdAt = existing != null ? existing.createdAt : now;
            String finalAvatar = avatarPath != null ? avatarPath : (existing != null ? existing.avatarPath : "");
            UserProfileEntity profile = new UserProfileEntity(
                    deviceId, name.trim(), bio != null ? bio.trim() : "",
                    finalAvatar, prefs.getActiveStatus(), createdAt, now
            );
            db.userProfileDao().upsert(profile);
            prefs.setProfileSetupDone(true);
            locator.getPeerConnectionManager().broadcastProfileUpdate();
        });
    }

    public void updateActiveStatus(String status) {
        prefs.setActiveStatus(status);
        AppDatabase.DB_EXECUTOR.execute(() -> {
            UserProfileEntity me = db.userProfileDao().getProfileSync();
            if (me != null) {
                me.activeStatus = status;
                me.updatedAt = System.currentTimeMillis();
                db.userProfileDao().upsert(me);
            }
            locator.getPeerConnectionManager().broadcastProfileUpdate();
        });
    }

    public void toggleFriend(PeerEntity peer) {
        AppDatabase.DB_EXECUTOR.execute(() ->
                db.peerDao().setFriend(peer.deviceId, !peer.isFriend));
    }

    public void blockPeer(PeerEntity peer, boolean blocked) {
        AppDatabase.DB_EXECUTOR.execute(() ->
                db.peerDao().setBlocked(peer.deviceId, blocked));
    }

    public String openOrCreateDmChat(PeerEntity peer, Runnable onReady) {
        String myId = prefs.getOrCreateDeviceId();
        String chatId = PeerConnectionManager.makeDmChatId(myId, peer.deviceId);
        AppDatabase.DB_EXECUTOR.execute(() -> {
            ChatEntity existing = db.chatDao().getChatSync(chatId);
            if (existing == null) {
                db.chatDao().upsert(new ChatEntity(
                        chatId, peer.name, false, peer.deviceId,
                        null, "", System.currentTimeMillis(), 0, false, false
                ));
            }
            if (onReady != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(onReady);
            }
        });
        return chatId;
    }

    public void createGroup(String name, String description, boolean isPrivate, String rawPin) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            UserProfileEntity me = db.userProfileDao().getProfileSync();
            String ownerId = me != null ? me.deviceId : prefs.getOrCreateDeviceId();
            String ownerName = me != null ? me.name : "Owner";
            String groupId = "grp-" + UUID.randomUUID().toString().substring(0, 8);
            CryptoManager crypto = locator.getCryptoManager();
            String salt = isPrivate ? crypto.generateSaltBase64() : "";
            String encSalt = isPrivate ? crypto.generateSaltBase64() : "";
            String pinHash = "";
            if (isPrivate && rawPin != null && !rawPin.isEmpty()) {
                try {
                    pinHash = crypto.hashPinWithSalt(rawPin, salt);
                    locator.getPeerConnectionManager().cacheGroupDerivedKey(groupId, rawPin, encSalt);
                } catch (Exception ignored) {
                }
            }
            long now = System.currentTimeMillis();
            GroupEntity group = new GroupEntity(
                    groupId, name.trim(), description != null ? description.trim() : "",
                    "", isPrivate, salt, pinHash, ownerId, ownerName,
                    false, true, false, encSalt, 1, 1, 1, now
            );
            db.groupDao().upsertGroup(group);
            db.groupDao().upsertMember(new GroupMemberEntity(
                    groupId, ownerId, ownerName, "OWNER", true, now
            ));
            db.chatDao().upsert(new ChatEntity(
                    groupId, group.name, true, null, null,
                    "Group created by " + ownerName, now, 0, false, false
            ));
            locator.getPeerConnectionManager().syncGroupMetadata(group);
        });
    }

    public void joinPublicGroup(GroupEntity group) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            UserProfileEntity me = db.userProfileDao().getProfileSync();
            String myId = me != null ? me.deviceId : prefs.getOrCreateDeviceId();
            String myName = me != null ? me.name : "Peer";
            db.groupDao().setJoined(group.groupId, true);
            db.groupDao().upsertMember(new GroupMemberEntity(
                    group.groupId, myId, myName, "MEMBER", true, System.currentTimeMillis()
            ));
            ChatEntity chat = db.chatDao().getChatSync(group.groupId);
            if (chat == null) {
                db.chatDao().upsert(new ChatEntity(
                        group.groupId, group.name, true, null, null,
                        group.description, System.currentTimeMillis(), 0, false, false
                ));
            }
            locator.getPeerConnectionManager().syncGroupMetadata(group);
        });
    }

    public void saveContactLocally(String name, String deviceId, String ipOrPhone, String notes) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            SavedContactEntity entity = new SavedContactEntity(
                    UUID.randomUUID().toString(), name, deviceId, ipOrPhone, notes, System.currentTimeMillis()
            );
            db.savedContactDao().upsert(entity);
        });
    }

    public void deleteSavedContact(String contactId) {
        AppDatabase.DB_EXECUTOR.execute(() -> db.savedContactDao().delete(contactId));
    }
}
