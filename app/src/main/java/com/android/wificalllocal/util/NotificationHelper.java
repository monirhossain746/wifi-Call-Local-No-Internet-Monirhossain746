package com.android.wificalllocal.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.android.wificalllocal.R;
import com.android.wificalllocal.network.model.NetPacket;
import com.android.wificalllocal.ui.MainActivity;
import com.android.wificalllocal.ui.call.CallActivity;

public class NotificationHelper {

    public static final String CHANNEL_MESH = "wcl_mesh_channel";
    public static final String CHANNEL_MESSAGES = "wcl_messages_channel";
    public static final String CHANNEL_CALLS = "wcl_calls_channel";
    public static final String CHANNEL_FILES = "wcl_files_channel";

    public static final int ID_MESH_SERVICE = 1001;
    public static final int ID_CALL_SERVICE = 1002;
    public static final int ID_INCOMING_CALL = 2001;

    private final Context context;
    private volatile String activeIncomingCallId;

    public NotificationHelper(Context context) {
        this.context = context.getApplicationContext();
        createChannels();
    }

    public void createChannels() {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel mesh = new NotificationChannel(
                CHANNEL_MESH, context.getString(R.string.channel_mesh_name), NotificationManager.IMPORTANCE_LOW);
        mesh.setDescription(context.getString(R.string.channel_mesh_desc));

        NotificationChannel msgs = new NotificationChannel(
                CHANNEL_MESSAGES, context.getString(R.string.channel_messages_name), NotificationManager.IMPORTANCE_HIGH);

        NotificationChannel calls = new NotificationChannel(
                CHANNEL_CALLS, context.getString(R.string.channel_calls_name), NotificationManager.IMPORTANCE_HIGH);
        calls.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        NotificationChannel files = new NotificationChannel(
                CHANNEL_FILES, context.getString(R.string.channel_files_name), NotificationManager.IMPORTANCE_LOW);

        nm.createNotificationChannel(mesh);
        nm.createNotificationChannel(msgs);
        nm.createNotificationChannel(calls);
        nm.createNotificationChannel(files);
    }

    public Notification buildMeshForegroundNotification(String ip, int port, int peerCount) {
        Intent openIntent = new Intent(context, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(context, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String content = (ip == null || ip.isEmpty())
                ? context.getString(R.string.status_no_local_network)
                : context.getString(R.string.notif_mesh_text, ip, port, peerCount);

        return new NotificationCompat.Builder(context, CHANNEL_MESH)
                .setSmallIcon(R.drawable.ic_nearby)
                .setContentTitle(context.getString(R.string.notif_mesh_title))
                .setContentText(content)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    public Notification buildCallForegroundNotification(String peerName, boolean isVideo) {
        Intent openIntent = new Intent(context, CallActivity.class);
        PendingIntent pi = PendingIntent.getActivity(context, 1, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(context, CHANNEL_CALLS)
                .setSmallIcon(isVideo ? R.drawable.ic_video : R.drawable.ic_call)
                .setContentTitle(peerName != null ? peerName : context.getString(R.string.app_name))
                .setContentText(context.getString(isVideo ? R.string.call_type_video : R.string.call_type_voice))
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    public void showMessageNotification(String chatId, String chatTitle, String peerId, String senderName, String text) {
        if (!canPostNotifications() || chatId == null) return;
        Intent intent = new Intent(context, MainActivity.class);
        intent.putExtra("open_chat_id", chatId);
        intent.putExtra("open_chat_title", chatTitle != null ? chatTitle : senderName);
        if (peerId != null && !peerId.isEmpty()) {
            intent.putExtra("open_peer_id", peerId);
        }
        PendingIntent pi = PendingIntent.getActivity(context, chatId.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String displayTitle = chatTitle != null && !chatTitle.isEmpty() && !chatId.startsWith("dm:")
                ? (chatTitle + " • " + (senderName != null ? senderName : "Peer"))
                : (senderName != null ? senderName : context.getString(R.string.app_name));

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_chat)
                .setContentTitle(displayTitle)
                .setContentText(text != null ? text : "")
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH);

        NotificationManagerCompat.from(context).notify(chatId.hashCode(), builder.build());
    }

    public void showMessageNotification(String chatId, String senderName, String text) {
        showMessageNotification(chatId, senderName, null, senderName, text);
    }

    public synchronized void showIncomingCallNotification(NetPacket offer) {
        if (offer == null || offer.callId == null || offer.callId.isEmpty()) return;
        if (offer.callId.equals(activeIncomingCallId)) {
            return; // Suppress duplicate incoming call notification/screen for the same callId
        }
        activeIncomingCallId = offer.callId;

        Intent callIntent = new Intent(context, CallActivity.class);
        callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        callIntent.putExtra(CallActivity.EXTRA_INCOMING, true);
        callIntent.putExtra(CallActivity.EXTRA_CALL_ID, offer.callId);
        callIntent.putExtra(CallActivity.EXTRA_PEER_ID, offer.senderId);
        callIntent.putExtra(CallActivity.EXTRA_PEER_NAME, offer.senderName);
        callIntent.putExtra(CallActivity.EXTRA_PEER_IP, offer.senderIp);
        callIntent.putExtra(CallActivity.EXTRA_CALL_TYPE, offer.callType);
        callIntent.putExtra(CallActivity.EXTRA_OFFER_SDP, offer.sdp);
        if (offer.groupId != null) {
            callIntent.putExtra(CallActivity.EXTRA_GROUP_ID, offer.groupId);
        }
        if (offer.groupName != null) {
            callIntent.putExtra(CallActivity.EXTRA_GROUP_NAME, offer.groupName);
        }

        if (canPostNotifications()) {
            PendingIntent fullScreenPi = PendingIntent.getActivity(context, ID_INCOMING_CALL, callIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            boolean isVideo = offer.callType != null && offer.callType.contains("VIDEO");
            boolean isGroup = offer.groupId != null && !offer.groupId.isEmpty();
            String title = isGroup && offer.groupName != null && !offer.groupName.isEmpty()
                    ? offer.groupName + " (" + (offer.senderName != null ? offer.senderName : "Peer") + ")"
                    : (offer.senderName != null ? offer.senderName : "Incoming Call");
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_CALLS)
                    .setSmallIcon(isVideo ? R.drawable.ic_video : R.drawable.ic_call)
                    .setContentTitle(title)
                    .setContentText(context.getString(isVideo ? R.string.call_incoming_video : R.string.call_incoming_voice))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setFullScreenIntent(fullScreenPi, true)
                    .setAutoCancel(true);

            NotificationManagerCompat.from(context).notify(ID_INCOMING_CALL, builder.build());
        }
        try {
            context.startActivity(callIntent);
        } catch (Exception ignored) {
        }
    }

    public synchronized void cancelIncomingCallNotification() {
        activeIncomingCallId = null;
        NotificationManagerCompat.from(context).cancel(ID_INCOMING_CALL);
    }

    public synchronized void clearActiveIncomingCallIfMatches(String callId) {
        if (callId != null && callId.equals(activeIncomingCallId)) {
            cancelIncomingCallNotification();
        }
    }

    private boolean canPostNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }
}
