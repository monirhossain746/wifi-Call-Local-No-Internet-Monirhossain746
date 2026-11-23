package com.android.wificalllocal.webrtc;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.List;

/**
 * Manages Android call audio routing (Earpiece, Speakerphone, Wired Headset, Bluetooth SCO/BLE),
 * audio focus acquisition, microphone unmute guard, and exact AudioManager state restoration on call end.
 */
public class CallAudioRouter {

    public enum AudioRoute {
        EARPIECE,
        SPEAKER,
        WIRED_HEADSET,
        BLUETOOTH
    }

    public interface RouteListener {
        void onAudioRouteChanged(@NonNull AudioRoute route);
    }

    private final Context context;
    @Nullable
    private final AudioManager audioManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean started = false;
    private boolean speakerPreferred = false;
    private boolean scoStarted = false;
    private AudioRoute currentRoute = AudioRoute.EARPIECE;
    @Nullable
    private RouteListener routeListener;

    private int savedMode = AudioManager.MODE_NORMAL;
    private boolean savedSpeakerOn = false;
    private boolean savedMicMute = false;
    @Nullable
    private AudioFocusRequest focusRequest;
    @Nullable
    private AudioDeviceCallback deviceCallback;

    public CallAudioRouter(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    public void setRouteListener(@Nullable RouteListener listener) {
        this.routeListener = listener;
        if (listener != null) {
            listener.onAudioRouteChanged(currentRoute);
        }
    }

    public AudioRoute getCurrentRoute() {
        return currentRoute;
    }

    public boolean isSpeakerActive() {
        return currentRoute == AudioRoute.SPEAKER;
    }

    public synchronized void start(boolean defaultSpeakerOn) {
        this.speakerPreferred = defaultSpeakerOn;
        if (audioManager == null) return;

        if (!started) {
            savedMode = audioManager.getMode();
            savedSpeakerOn = audioManager.isSpeakerphoneOn();
            savedMicMute = audioManager.isMicrophoneMute();
            started = true;
            requestAudioFocus();
            registerDeviceCallback();
        }

        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        if (audioManager.isMicrophoneMute()) {
            audioManager.setMicrophoneMute(false);
        }
        updateAudioRoute();
    }

    public synchronized void setSpeakerphoneEnabled(boolean enabled) {
        this.speakerPreferred = enabled;
        if (audioManager == null || !started) return;
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        updateAudioRoute();
    }

    public synchronized void stop() {
        if (audioManager == null || !started) {
            started = false;
            return;
        }
        started = false;
        unregisterDeviceCallback();
        abandonAudioFocus();
        stopBluetoothScoIfNeeded();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.clearCommunicationDevice();
            } catch (Exception ignored) {
            }
        }
        try {
            audioManager.setSpeakerphoneOn(savedSpeakerOn);
            audioManager.setMicrophoneMute(savedMicMute);
            audioManager.setMode(savedMode);
        } catch (Exception ignored) {
        }
        currentRoute = AudioRoute.EARPIECE;
    }

    private void updateAudioRoute() {
        if (audioManager == null || !started) return;

        boolean hasWired = hasWiredHeadset();
        boolean hasBluetooth = canUseBluetoothAudio() && hasBluetoothHeadset();

        AudioRoute target;
        if (hasBluetooth && !speakerPreferred) {
            target = AudioRoute.BLUETOOTH;
        } else if (hasWired && !speakerPreferred) {
            target = AudioRoute.WIRED_HEADSET;
        } else if (speakerPreferred) {
            target = AudioRoute.SPEAKER;
        } else {
            target = AudioRoute.EARPIECE;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean routed = applyCommunicationDeviceApi31(target);
            if (!routed) {
                applyLegacyRoute(target);
            }
        } else {
            applyLegacyRoute(target);
        }

        currentRoute = target;
        RouteListener listener = this.routeListener;
        if (listener != null) {
            mainHandler.post(() -> listener.onAudioRouteChanged(target));
        }
    }

    private boolean applyCommunicationDeviceApi31(AudioRoute target) {
        if (audioManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false;
        try {
            List<AudioDeviceInfo> devices = audioManager.getAvailableCommunicationDevices();
            AudioDeviceInfo match = null;
            for (AudioDeviceInfo d : devices) {
                int type = d.getType();
                if (target == AudioRoute.BLUETOOTH
                        && (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || type == AudioDeviceInfo.TYPE_BLE_HEADSET)) {
                    match = d;
                    break;
                } else if (target == AudioRoute.WIRED_HEADSET
                        && (type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || type == AudioDeviceInfo.TYPE_USB_HEADSET)) {
                    match = d;
                    break;
                } else if (target == AudioRoute.SPEAKER && type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    match = d;
                    break;
                } else if (target == AudioRoute.EARPIECE && type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                    match = d;
                    break;
                }
            }
            if (match != null) {
                return audioManager.setCommunicationDevice(match);
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private void applyLegacyRoute(AudioRoute target) {
        if (audioManager == null) return;
        try {
            if (target == AudioRoute.BLUETOOTH) {
                audioManager.setSpeakerphoneOn(false);
                if (!scoStarted) {
                    audioManager.startBluetoothSco();
                    scoStarted = true;
                }
                audioManager.setBluetoothScoOn(true);
            } else {
                stopBluetoothScoIfNeeded();
                audioManager.setSpeakerphoneOn(target == AudioRoute.SPEAKER);
            }
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private void stopBluetoothScoIfNeeded() {
        if (audioManager != null && scoStarted) {
            try {
                audioManager.setBluetoothScoOn(false);
                audioManager.stopBluetoothSco();
            } catch (Exception ignored) {
            }
            scoStarted = false;
        }
    }

    private boolean hasWiredHeadset() {
        if (audioManager == null) return false;
        AudioDeviceInfo[] outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo d : outputs) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    || t == AudioDeviceInfo.TYPE_USB_HEADSET) {
                return true;
            }
        }
        return false;
    }

    private boolean hasBluetoothHeadset() {
        if (audioManager == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (AudioDeviceInfo d : audioManager.getAvailableCommunicationDevices()) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    return true;
                }
            }
            return false;
        }
        AudioDeviceInfo[] outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo d : outputs) {
            if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                return true;
            }
        }
        return false;
    }

    private boolean canUseBluetoothAudio() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestAudioFocus() {
        if (audioManager == null) return;
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(focusChange -> {
                        if (focusChange == AudioManager.AUDIOFOCUS_GAIN && started) {
                            mainHandler.post(this::updateAudioRoute);
                        }
                    })
                    .build();
            audioManager.requestAudioFocus(focusRequest);
        } catch (Exception ignored) {
        }
    }

    private void abandonAudioFocus() {
        if (audioManager != null && focusRequest != null) {
            try {
                audioManager.abandonAudioFocusRequest(focusRequest);
            } catch (Exception ignored) {
            }
            focusRequest = null;
        }
    }

    private void registerDeviceCallback() {
        if (audioManager == null || deviceCallback != null) return;
        deviceCallback = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                if (started) updateAudioRoute();
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                if (started) updateAudioRoute();
            }
        };
        try {
            audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler);
        } catch (Exception ignored) {
        }
    }

    private void unregisterDeviceCallback() {
        if (audioManager != null && deviceCallback != null) {
            try {
                audioManager.unregisterAudioDeviceCallback(deviceCallback);
            } catch (Exception ignored) {
            }
            deviceCallback = null;
        }
    }
}
