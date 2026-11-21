package com.android.wificalllocal.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

public class PreferencesManager {

    private static final String PREFS_NAME = "wifi_call_local_prefs";

    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_PROFILE_DONE = "profile_done";
    private static final String KEY_CONNECTION_MODE = "connection_mode"; // AUTO, WIFI, HOTSPOT, WIFI_DIRECT
    private static final String KEY_AUTO_CONNECT = "auto_connect";
    private static final String KEY_SERVER_PORT = "server_port";
    private static final String KEY_THEME_MODE = "theme_mode"; // SYSTEM, DARK, LIGHT
    private static final String KEY_LANGUAGE = "language"; // en, bn
    private static final String KEY_NOTIFICATIONS_ENABLED = "notifications_enabled";
    private static final String KEY_ACTIVE_STATUS = "active_status"; // ONLINE, AWAY, BUSY, DND

    public static final String MODE_AUTO = "AUTO";
    public static final String MODE_WIFI = "WIFI";
    public static final String MODE_HOTSPOT = "HOTSPOT";
    public static final String MODE_WIFI_DIRECT = "WIFI_DIRECT";

    public static final String THEME_SYSTEM = "SYSTEM";
    public static final String THEME_DARK = "DARK";
    public static final String THEME_LIGHT = "LIGHT";

    public static final String STATUS_ONLINE = "ONLINE";
    public static final String STATUS_AWAY = "AWAY";
    public static final String STATUS_BUSY = "BUSY";
    public static final String STATUS_DND = "DND";

    private final SharedPreferences prefs;

    public PreferencesManager(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public synchronized String getOrCreateDeviceId() {
        String id = prefs.getString(KEY_DEVICE_ID, null);
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }

    public boolean isProfileSetupDone() {
        return prefs.getBoolean(KEY_PROFILE_DONE, false);
    }

    public void setProfileSetupDone(boolean done) {
        prefs.edit().putBoolean(KEY_PROFILE_DONE, done).apply();
    }

    public String getConnectionMode() {
        return prefs.getString(KEY_CONNECTION_MODE, MODE_AUTO);
    }

    public void setConnectionMode(String mode) {
        prefs.edit().putString(KEY_CONNECTION_MODE, mode).apply();
    }

    public boolean isAutoConnect() {
        return prefs.getBoolean(KEY_AUTO_CONNECT, true);
    }

    public void setAutoConnect(boolean autoConnect) {
        prefs.edit().putBoolean(KEY_AUTO_CONNECT, autoConnect).apply();
    }

    public int getServerPort() {
        return prefs.getInt(KEY_SERVER_PORT, 8080);
    }

    public void setServerPort(int port) {
        int safePort = (port >= 1024 && port <= 65535) ? port : 8080;
        prefs.edit().putInt(KEY_SERVER_PORT, safePort).apply();
    }

    public String getThemeMode() {
        return prefs.getString(KEY_THEME_MODE, THEME_DARK);
    }

    public void setThemeMode(String themeMode) {
        prefs.edit().putString(KEY_THEME_MODE, themeMode).apply();
    }

    public String getLanguage() {
        return prefs.getString(KEY_LANGUAGE, "en");
    }

    public void setLanguage(String lang) {
        prefs.edit().putString(KEY_LANGUAGE, lang).apply();
    }

    public boolean areNotificationsEnabled() {
        return prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true);
    }

    public void setNotificationsEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply();
    }

    public String getActiveStatus() {
        return prefs.getString(KEY_ACTIVE_STATUS, STATUS_ONLINE);
    }

    public void setActiveStatus(String status) {
        prefs.edit().putString(KEY_ACTIVE_STATUS, status).apply();
    }

    public void saveGroupKey(String groupId, byte[] keyBytes) {
        if (groupId == null || keyBytes == null) return;
        String b64 = android.util.Base64.encodeToString(keyBytes, android.util.Base64.NO_WRAP);
        prefs.edit().putString("grp_key_" + groupId, b64).apply();
    }

    public java.util.Map<String, byte[]> loadAllSavedGroupKeys() {
        java.util.Map<String, byte[]> result = new java.util.HashMap<>();
        java.util.Map<String, ?> all = prefs.getAll();
        if (all == null) return result;
        for (java.util.Map.Entry<String, ?> e : all.entrySet()) {
            if (e.getKey() != null && e.getKey().startsWith("grp_key_") && e.getValue() instanceof String) {
                String gid = e.getKey().substring("grp_key_".length());
                try {
                    result.put(gid, android.util.Base64.decode((String) e.getValue(), android.util.Base64.DEFAULT));
                } catch (Exception ignored) {
                }
            }
        }
        return result;
    }

    public void removeGroupKey(String groupId) {
        if (groupId != null) prefs.edit().remove("grp_key_" + groupId).apply();
    }
}
