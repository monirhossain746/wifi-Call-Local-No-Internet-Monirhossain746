package com.android.wificalllocal.ui;

import android.app.Application;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.android.wificalllocal.ServiceLocator;
import com.android.wificalllocal.data.local.AppDatabase;
import com.android.wificalllocal.data.local.entity.ChatEntity;
import com.android.wificalllocal.data.local.entity.FileTransferEntity;
import com.android.wificalllocal.data.local.entity.GroupEntity;
import com.android.wificalllocal.data.local.entity.GroupMemberEntity;
import com.android.wificalllocal.data.local.entity.MessageEntity;
import com.android.wificalllocal.data.local.entity.UserProfileEntity;
import com.android.wificalllocal.files.FileTransferManager;
import com.android.wificalllocal.network.NetworkUtils;
import com.android.wificalllocal.network.model.NetPacket;

import java.util.List;
import java.util.UUID;

public class ChatDetailViewModel extends AndroidViewModel {

    private final ServiceLocator locator;
    private final AppDatabase db;
    private final MutableLiveData<String> searchQuery = new MutableLiveData<>("");
    private String chatId;
    private String peerId;
    private LiveData<List<MessageEntity>> messagesLive;

    public ChatDetailViewModel(@NonNull Application application) {
        super(application);
        this.locator = ServiceLocator.getInstance(application);
        this.db = locator.getDatabase();
    }

    public void init(String chatId, String peerId) {
        this.chatId = chatId;
        this.peerId = peerId;
        this.messagesLive = Transformations.switchMap(searchQuery, q -> {
            if (q == null || q.trim().isEmpty()) {
                return db.messageDao().observeMessagesForChat(chatId);
            }
            return db.messageDao().searchMessagesInChat(chatId, q.trim());
        });
        locator.getPeerConnectionManager().markChatSeen(chatId, peerId);
    }

    public LiveData<List<MessageEntity>> observeMessages() {
        return messagesLive;
    }

    public LiveData<List<FileTransferEntity>> observeTransfers() {
        return db.fileTransferDao().observeAll();
    }

    public LiveData<GroupEntity> observeGroup() {
        return db.groupDao().observeGroup(chatId);
    }

    public LiveData<List<GroupMemberEntity>> observeGroupMembers() {
        return db.groupDao().observeMembers(chatId);
    }

    public LiveData<com.android.wificalllocal.data.local.entity.PeerEntity> observePeer() {
        return peerId != null ? db.peerDao().observeById(peerId) : new MutableLiveData<>(null);
    }

    public void setSearchQuery(String query) {
        searchQuery.setValue(query);
    }

    public void sendTextMessage(String text, MessageEntity replyTo, String forwardedFrom) {
        if (text == null || text.trim().isEmpty()) return;
        AppDatabase.DB_EXECUTOR.execute(() -> {
            UserProfileEntity me = db.userProfileDao().getProfileSync();
            String myId = me != null ? me.deviceId : locator.getPreferencesManager().getOrCreateDeviceId();
            String myName = me != null ? me.name : "Me";
            String replyId = replyTo != null ? replyTo.messageId : null;
            String replyPreview = replyTo != null ? (replyTo.senderName + ": " + replyTo.content) : null;

            MessageEntity msg = new MessageEntity(
                    UUID.randomUUID().toString(), chatId, myId, myName, false,
                    "TEXT", text.trim(), replyId, replyPreview, forwardedFrom,
                    null, null, null, 0L, null, null,
                    "SENDING", 0L, 0, System.currentTimeMillis(),
                    false, false, "{}", false
            );
            locator.getPeerConnectionManager().sendMessage(chatId, peerId, msg, null);
        });
    }

    public void sendFileAttachment(Uri uri, String overrideType) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            try {
                UserProfileEntity me = db.userProfileDao().getProfileSync();
                String myId = me != null ? me.deviceId : locator.getPreferencesManager().getOrCreateDeviceId();
                String myName = me != null ? me.name : "Me";
                NetworkUtils.LocalNetworkInfo net = NetworkUtils.detectLocalNetwork(getApplication());
                int port = locator.getPreferencesManager().getServerPort();
                String msgId = UUID.randomUUID().toString();

                NetPacket.FileManifestDto manifest = locator.getFileTransferManager()
                        .prepareOutgoingFile(uri, msgId, chatId, myId, net.ipAddress, port);

                String type = overrideType;
                if (type == null) {
                    if (manifest.mimeType != null && manifest.mimeType.startsWith("image/")) type = "IMAGE";
                    else if (manifest.mimeType != null && manifest.mimeType.startsWith("video/")) type = "VIDEO";
                    else if (manifest.mimeType != null && manifest.mimeType.startsWith("audio/")) type = "AUDIO";
                    else type = "DOCUMENT";
                }

                MessageEntity msg = new MessageEntity(
                        msgId, chatId, myId, myName, false,
                        type, manifest.fileName, null, null, null,
                        manifest.transferId, manifest.fileName, manifest.mimeType,
                        manifest.totalSize, manifest.fileSha256, uri.toString(),
                        "SENDING", 0L, 0, System.currentTimeMillis(),
                        false, false, "{}", false, manifest.thumbnailBase64
                );
                locator.getPeerConnectionManager().sendMessage(chatId, peerId, msg, manifest);
            } catch (Exception ignored) {
            }
        });
    }

    public void forwardMessageTo(MessageEntity source, String targetChatId, String targetPeerId) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            UserProfileEntity me = db.userProfileDao().getProfileSync();
            String myId = me != null ? me.deviceId : locator.getPreferencesManager().getOrCreateDeviceId();
            String myName = me != null ? me.name : "Me";
            MessageEntity fwd = new MessageEntity(
                    UUID.randomUUID().toString(), targetChatId, myId, myName, false,
                    source.type, source.content, null, null, source.senderName,
                    source.attachmentId, source.attachmentName, source.attachmentMime,
                    source.attachmentSize, source.attachmentSha256, source.localFilePath,
                    "SENDING", 0L, 0, System.currentTimeMillis(),
                    false, false, "{}", false
            );
            locator.getPeerConnectionManager().sendMessage(targetChatId, targetPeerId, fwd, null);
        });
    }

    public void editMessage(String messageId, String newContent) {
        locator.getPeerConnectionManager().sendMessageEdit(chatId, messageId, newContent);
    }

    public void deleteForMe(String messageId) {
        AppDatabase.DB_EXECUTOR.execute(() -> db.messageDao().deleteForMe(messageId));
    }

    public void deleteForEveryone(String messageId) {
        locator.getPeerConnectionManager().sendDeleteForEveryone(chatId, messageId);
    }

    public void clearChat() {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            db.messageDao().clearChatMessages(chatId);
            ChatEntity c = db.chatDao().getChatSync(chatId);
            if (c != null) {
                c.lastMessagePreview = "";
                db.chatDao().upsert(c);
            }
        });
    }

    public void deleteChat() {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            db.messageDao().clearChatMessages(chatId);
            db.chatDao().deleteChat(chatId);
        });
    }
}
