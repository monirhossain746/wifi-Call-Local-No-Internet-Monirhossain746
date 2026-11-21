package com.android.wificalllocal.service;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;

import com.android.wificalllocal.ServiceLocator;
import com.android.wificalllocal.util.NotificationHelper;

/**
 * Foreground service active only during live WebRTC voice/video calls,
 * declaring microphone and camera foreground service types when applicable.
 */
public class CallForegroundService extends Service {

    public static final String EXTRA_PEER_NAME = "peer_name";
    public static final String EXTRA_IS_VIDEO = "is_video";

    public static void start(Context context, String peerName, boolean isVideo) {
        Intent intent = new Intent(context, CallForegroundService.class);
        intent.putExtra(EXTRA_PEER_NAME, peerName);
        intent.putExtra(EXTRA_IS_VIDEO, isVideo);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, CallForegroundService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String peerName = intent != null ? intent.getStringExtra(EXTRA_PEER_NAME) : "Local Call";
        boolean isVideo = intent != null && intent.getBooleanExtra(EXTRA_IS_VIDEO, false);

        Notification notification = ServiceLocator.getInstance(this)
                .getNotificationHelper()
                .buildCallForegroundNotification(peerName, isVideo);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            if (isVideo) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            startForeground(NotificationHelper.ID_CALL_SERVICE, notification, types);
        } else {
            startForeground(NotificationHelper.ID_CALL_SERVICE, notification);
        }
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
