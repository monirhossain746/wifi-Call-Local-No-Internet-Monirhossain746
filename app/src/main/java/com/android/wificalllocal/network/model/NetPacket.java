package com.android.wificalllocal.network.model;

import java.util.List;

public class NetPacket {

    public static final String TYPE_HELLO = "HELLO";
    public static final String TYPE_HEARTBEAT = "HEARTBEAT";
    public static final String TYPE_PROFILE_UPDATE = "PROFILE_UPDATE";
    public static final String TYPE_CHAT_MESSAGE = "CHAT_MESSAGE";
    public static final String TYPE_MESSAGE_RECEIPT = "MESSAGE_RECEIPT";
    public static final String TYPE_MESSAGE_REACTION = "MESSAGE_REACTION";
    public static final String TYPE_MESSAGE_EDIT = "MESSAGE_EDIT";
    public static final String TYPE_MESSAGE_DELETE = "MESSAGE_DELETE";
    public static final String TYPE_TYPING = "TYPING";
    public static final String TYPE_SYNC_SUMMARY = "SYNC_SUMMARY";
    public static final String TYPE_SYNC_REQUEST = "SYNC_REQUEST";
    public static final String TYPE_SYNC_RESPONSE = "SYNC_RESPONSE";
    public static final String TYPE_GROUP_SYNC = "GROUP_SYNC";
    public static final String TYPE_GROUP_AUTH_CHALLENGE = "GROUP_AUTH_CHALLENGE";
    public static final String TYPE_GROUP_AUTH_VERIFY = "GROUP_AUTH_VERIFY";
    public static final String TYPE_CALL_OFFER = "CALL_OFFER";
    public static final String TYPE_CALL_ANSWER = "CALL_ANSWER";
    public static final String TYPE_CALL_ICE = "CALL_ICE";
    public static final String TYPE_CALL_RINGING = "CALL_RINGING";
    public static final String TYPE_CALL_BUSY = "CALL_BUSY";
    public static final String TYPE_CALL_REJECT = "CALL_REJECT";
    public static final String TYPE_CALL_END = "CALL_END";
    public static final String TYPE_GROUP_CALL_INVITE = "GROUP_CALL_INVITE";
    public static final String TYPE_GROUP_CALL_JOIN = "GROUP_CALL_JOIN";
    public static final String TYPE_GROUP_CALL_LEAVE = "GROUP_CALL_LEAVE";
    public static final String TYPE_GROUP_CALL_STATE = "GROUP_CALL_STATE";
    public static final String TYPE_FILE_MANIFEST = "FILE_MANIFEST";
    public static final String TYPE_FILE_TRANSFER = "FILE_TRANSFER";

    public int version = 1;
    public int protocolVersion = 1;
    public String packetId;
    public int ttl = 2;
    public String type;
    public String messageType;
    public String event;
    public long timestamp;
    public java.util.Map<String, Object> payload;

    public String senderId;
    public String senderName;
    public String senderBio;
    public String senderIp;
    public int senderPort;
    public String senderStatus;
    public String connectionMode;
    public boolean isWebUser;
    public String avatarBase64;

    public String targetId;
    public String recipientId;
    public String chatId;
    public String messageId;
    public String status;
    public String emoji;
    public String emojiAction; // ADD, REMOVE
    public String newContent;
    public long editedAt;
    public boolean typing;

    public MessageDto message;
    public GroupDto group;
    public List<GroupDto> groups;
    public List<GroupMemberDto> members;
    public List<String> syncMessageIds;
    public List<MessageDto> syncMessages;

    // Challenge-response group PIN auth (raw PIN never transmitted)
    public String groupId;
    public String groupName;
    public String challengeNonce;
    public String challengeResponse;
    public boolean challengeAccepted;
    public String pinSalt;
    public int keyVersion;
    public String errorMessage;

    // WebRTC signaling (1-to-1 + Group Mesh)
    public String callId;
    public String callType; // VOICE, VIDEO, GROUP_VOICE, GROUP_VIDEO
    public String callState;
    public String reason;
    public String sdp;
    public String iceCandidate;
    public String iceSdpMid;
    public int iceSdpMLineIndex;
    public boolean muted;
    public boolean videoEnabled = true;
    public List<String> participants;
    public List<GroupCallParticipantDto> groupCallParticipants;

    // Chunked file sharing
    public FileManifestDto fileManifest;

    public static class GroupCallParticipantDto {
        public String deviceId;
        public String displayName;
        public boolean isWebUser;
        public boolean muted;
        public boolean videoEnabled = true;
        public String state; // CONNECTING, CONNECTED
    }

    public static class MessageDto {
        public String messageId;
        public String chatId;
        public String senderId;
        public String senderName;
        public boolean senderIsWeb;
        public String type;
        public String content;
        public String replyToMessageId;
        public String replyToPreview;
        public String forwardedFrom;
        public String attachmentId;
        public String attachmentName;
        public String attachmentMime;
        public long attachmentSize;
        public String attachmentSha256;
        public long hlcTimestamp;
        public int hlcCounter;
        public long createdAt;
        public boolean isDeletedForEveryone;
        public String reactionsJson;
        public boolean isEncrypted;
        public String thumbnailBase64;
        public boolean isEdited;
        public long editedAt;
    }

    public static class GroupDto {
        public String groupId;
        public String name;
        public String description;
        public boolean isPrivate;
        public String pinSalt;
        public String ownerId;
        public String creatorName;
        public boolean adminsOnlySend;
        public String encryptionKeySalt;
        public int keyVersion;
        public int memberCount;
        public int onlineCount;
        public long updatedAt;
    }

    public static class GroupMemberDto {
        public String groupId;
        public String deviceId;
        public String displayName;
        public String role;
        public boolean isOnline;
        public long joinedAt;
    }

    public static class FileManifestDto {
        public String transferId;
        public String messageId;
        public String chatId;
        public String fileName;
        public String mimeType;
        public long totalSize;
        public int chunkSize;
        public int totalChunks;
        public String fileSha256;
        public List<String> chunkSha256List;
        public List<String> seederIps;
        public String seederDeviceId;
        public int seederPort;
        public String thumbnailBase64;
    }
}
