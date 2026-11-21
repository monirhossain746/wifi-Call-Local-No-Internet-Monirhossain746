package com.android.wificalllocal.control;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.android.wificalllocal.BuildConfig;
import com.android.wificalllocal.R;
import com.android.wificalllocal.network.NetworkUtils;
import com.android.wificalllocal.storage.CacheManager;
import com.google.firebase.FirebaseApp;
import com.google.firebase.remoteconfig.FirebaseRemoteConfig;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages Firebase Remote Config fetching, activation, and offline SharedPreferences caching
 * for all 14 remote parameters cleanly, without failing when offline or before
 * app/google-services.json is provisioned.
 */
public class RemoteConfigManager {

    public static final String KEY_IS_APP_ENABLED = "is_app_enabled";
    public static final String KEY_MAINTENANCE_MODE = "maintenance_mode";
    public static final String KEY_MAINTENANCE_MESSAGE = "maintenance_message";
    public static final String KEY_LATEST_VERSION = "latest_version";
    public static final String KEY_LATEST_VERSION_CODE = "latest_version_code";
    public static final String KEY_UPDATE_LINK = "update_link";
    public static final String KEY_FORCE_UPDATE = "force_update";
    public static final String KEY_ADS_ENABLED = "ads_enabled";
    public static final String KEY_MAX_CACHE_SIZE_MB = "max_cache_size_mb";
    public static final String KEY_ONLINE_SERVICES_ENABLED = "online_services_enabled";
    public static final String KEY_GROUP_CALLS_ENABLED = "group_calls_enabled";
    public static final String KEY_FILE_TRANSFER_ENABLED = "file_transfer_enabled";
    public static final String KEY_PUBLIC_GROUPS_ENABLED = "public_groups_enabled";
    public static final String KEY_EVERYONE_GROUP_ENABLED = "everyone_group_enabled";
    public static final String KEY_UPDATE_MESSAGE = "update_message";

    private static final String PREFS_NAME = "wcl_remote_config_cache";
    private static final String PREF_ENABLED = "cached_is_app_enabled";
    private static final String PREF_MAINTENANCE = "cached_maintenance_mode";
    private static final String PREF_MAINTENANCE_MSG = "cached_maintenance_message";
    private static final String PREF_LATEST_VERSION_NAME = "cached_latest_version";
    private static final String PREF_LATEST_VERSION = "cached_latest_version_code";
    private static final String PREF_UPDATE_LINK = "cached_update_link";
    private static final String PREF_FORCE_UPDATE = "cached_force_update";
    private static final String PREF_ADS_ENABLED = "cached_ads_enabled";
    private static final String PREF_ADS_ENABLED_EXPLICIT = "cached_ads_enabled_explicit";
    private static final String PREF_MAX_CACHE_MB = "cached_max_cache_mb";
    private static final String PREF_ONLINE_SERVICES = "cached_online_services_enabled";
    private static final String PREF_GROUP_CALLS = "cached_group_calls_enabled";
    private static final String PREF_FILE_TRANSFER = "cached_file_transfer_enabled";
    private static final String PREF_PUBLIC_GROUPS = "cached_public_groups_enabled";
    private static final String PREF_EVERYONE_GROUP = "cached_everyone_group_enabled";
    private static final String PREF_UPDATE_MESSAGE = "cached_update_message";
    private static final String PREF_LAST_FETCH_MS = "cached_last_fetch_ms";
    private static final String PREF_LAST_FETCH_STATUS = "cached_last_fetch_status";

    public static class ConfigSnapshot {
        public final boolean isAppEnabled;
        public final boolean maintenanceMode;
        public final String maintenanceMessage;
        public final String latestVersionName;
        public final long latestVersionCode;
        public final String updateLink;
        public final boolean forceUpdate;
        public final boolean adsEnabled;
        public final long maxCacheSizeMb;
        public final boolean onlineServicesEnabled;
        public final boolean groupCallsEnabled;
        public final boolean fileTransferEnabled;
        public final boolean publicGroupsEnabled;
        public final boolean everyoneGroupEnabled;
        public final String updateMessage;
        public final boolean fromNetwork;
        public final long lastSuccessfulFetchMs;

        /**
         * Backwards-compatible 5-arg constructor used by existing unit tests.
         * Preserves legacy force-update check when remote versionCode > installed versionCode.
         */
        public ConfigSnapshot(boolean isAppEnabled, long latestVersionCode,
                              String updateLink, String updateMessage, boolean fromNetwork) {
            this(isAppEnabled, false, "", BuildConfig.VERSION_NAME, latestVersionCode,
                    updateLink, true, true, CacheManager.DEFAULT_MAX_CACHE_MB,
                    true, true, true, true, true, updateMessage, fromNetwork, 0L);
        }

        public ConfigSnapshot(boolean isAppEnabled, boolean maintenanceMode, String maintenanceMessage,
                              String latestVersionName, long latestVersionCode, String updateLink,
                              boolean forceUpdate, boolean adsEnabled, long maxCacheSizeMb,
                              boolean onlineServicesEnabled, boolean groupCallsEnabled,
                              boolean fileTransferEnabled, boolean publicGroupsEnabled,
                              boolean everyoneGroupEnabled, String updateMessage,
                              boolean fromNetwork, long lastSuccessfulFetchMs) {
            this.isAppEnabled = isAppEnabled;
            this.maintenanceMode = maintenanceMode;
            this.maintenanceMessage = maintenanceMessage != null ? maintenanceMessage : "";
            this.latestVersionName = (latestVersionName != null && !latestVersionName.trim().isEmpty())
                    ? latestVersionName.trim()
                    : BuildConfig.VERSION_NAME;
            this.latestVersionCode = latestVersionCode;
            this.updateLink = updateLink != null ? updateLink.trim() : "";
            this.forceUpdate = forceUpdate;
            this.adsEnabled = adsEnabled;
            this.maxCacheSizeMb = maxCacheSizeMb > 0 ? maxCacheSizeMb : CacheManager.DEFAULT_MAX_CACHE_MB;
            this.onlineServicesEnabled = onlineServicesEnabled;
            this.groupCallsEnabled = groupCallsEnabled;
            this.fileTransferEnabled = fileTransferEnabled;
            this.publicGroupsEnabled = publicGroupsEnabled;
            this.everyoneGroupEnabled = everyoneGroupEnabled;
            this.updateMessage = updateMessage != null ? updateMessage : "";
            this.fromNetwork = fromNetwork;
            this.lastSuccessfulFetchMs = lastSuccessfulFetchMs;
        }
    }

    public interface FetchCallback {
        void onResult(ConfigSnapshot snapshot);
    }

    private final Context context;
    private final SharedPreferences cachePrefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean defaultsApplied = false;
    private volatile String lastStatusMessage = "Cached defaults active";

    public RemoteConfigManager(Context context) {
        this.context = context.getApplicationContext();
        this.cachePrefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.lastStatusMessage = this.cachePrefs.getString(PREF_LAST_FETCH_STATUS, "Using cached / default configuration");
    }

    public boolean isFirebaseConfigured() {
        return getRemoteConfigIfAvailable() != null;
    }

    public String getFirebaseInitStatusLabel() {
        return isFirebaseConfigured()
                ? "Initialized (FirebaseApp active)"
                : "Offline / Missing google-services.json";
    }

    public String getRemoteConfigStatusLabel() {
        return lastStatusMessage;
    }

    public long getLastSuccessfulFetchMs() {
        return cachePrefs.getLong(PREF_LAST_FETCH_MS, 0L);
    }

    public ConfigSnapshot getCachedSnapshot() {
        boolean enabled = cachePrefs.getBoolean(PREF_ENABLED, true);
        boolean maintenance = cachePrefs.getBoolean(PREF_MAINTENANCE, false);
        String maintenanceMsg = cachePrefs.getString(PREF_MAINTENANCE_MSG, "");
        String latestVerName = cachePrefs.getString(PREF_LATEST_VERSION_NAME, BuildConfig.VERSION_NAME);
        long latestVer = cachePrefs.getLong(PREF_LATEST_VERSION, BuildConfig.VERSION_CODE);
        String link = cachePrefs.getString(PREF_UPDATE_LINK, "");
        boolean forceUpdate = cachePrefs.getBoolean(PREF_FORCE_UPDATE, false);
        boolean adsExplicit = cachePrefs.getBoolean(PREF_ADS_ENABLED_EXPLICIT, false);
        boolean adsEnabled = adsExplicit ? cachePrefs.getBoolean(PREF_ADS_ENABLED, true) : true;
        long maxCacheMb = cachePrefs.getLong(PREF_MAX_CACHE_MB, CacheManager.DEFAULT_MAX_CACHE_MB);
        boolean onlineServices = cachePrefs.getBoolean(PREF_ONLINE_SERVICES, true);
        boolean groupCalls = cachePrefs.getBoolean(PREF_GROUP_CALLS, true);
        boolean fileTransfer = cachePrefs.getBoolean(PREF_FILE_TRANSFER, true);
        boolean publicGroups = cachePrefs.getBoolean(PREF_PUBLIC_GROUPS, true);
        boolean everyoneGroup = cachePrefs.getBoolean(PREF_EVERYONE_GROUP, true);
        String msg = cachePrefs.getString(PREF_UPDATE_MESSAGE, context.getString(R.string.force_update_default_msg));
        long lastFetchMs = cachePrefs.getLong(PREF_LAST_FETCH_MS, 0L);
        return new ConfigSnapshot(
                enabled, maintenance, maintenanceMsg, latestVerName, latestVer,
                link, forceUpdate, adsEnabled, maxCacheMb,
                onlineServices, groupCalls, fileTransfer, publicGroups, everyoneGroup,
                msg, false, lastFetchMs
        );
    }

    public void persistSnapshot(ConfigSnapshot snapshot) {
        if (snapshot == null) return;
        long now = snapshot.lastSuccessfulFetchMs > 0 ? snapshot.lastSuccessfulFetchMs : System.currentTimeMillis();
        cachePrefs.edit()
                .putBoolean(PREF_ENABLED, snapshot.isAppEnabled)
                .putBoolean(PREF_MAINTENANCE, snapshot.maintenanceMode)
                .putString(PREF_MAINTENANCE_MSG, snapshot.maintenanceMessage)
                .putString(PREF_LATEST_VERSION_NAME, snapshot.latestVersionName)
                .putLong(PREF_LATEST_VERSION, snapshot.latestVersionCode)
                .putString(PREF_UPDATE_LINK, snapshot.updateLink)
                .putBoolean(PREF_FORCE_UPDATE, snapshot.forceUpdate)
                .putBoolean(PREF_ADS_ENABLED, snapshot.adsEnabled)
                .putBoolean(PREF_ADS_ENABLED_EXPLICIT, snapshot.fromNetwork)
                .putLong(PREF_MAX_CACHE_MB, snapshot.maxCacheSizeMb)
                .putBoolean(PREF_ONLINE_SERVICES, snapshot.onlineServicesEnabled)
                .putBoolean(PREF_GROUP_CALLS, snapshot.groupCallsEnabled)
                .putBoolean(PREF_FILE_TRANSFER, snapshot.fileTransferEnabled)
                .putBoolean(PREF_PUBLIC_GROUPS, snapshot.publicGroupsEnabled)
                .putBoolean(PREF_EVERYONE_GROUP, snapshot.everyoneGroupEnabled)
                .putString(PREF_UPDATE_MESSAGE, snapshot.updateMessage)
                .putLong(PREF_LAST_FETCH_MS, now)
                .putString(PREF_LAST_FETCH_STATUS, lastStatusMessage)
                .apply();
    }

    public boolean initialize() {
        FirebaseRemoteConfig remoteConfig = getRemoteConfigIfAvailable();
        if (remoteConfig == null) {
            return false;
        }
        ensureDefaultsApplied(remoteConfig, null);
        return true;
    }

    private void ensureDefaultsApplied(FirebaseRemoteConfig remoteConfig, @Nullable Runnable afterDefaults) {
        if (remoteConfig == null) {
            if (afterDefaults != null) afterDefaults.run();
            return;
        }
        if (defaultsApplied) {
            if (afterDefaults != null) afterDefaults.run();
            return;
        }
        com.google.android.gms.tasks.Task<Void> defaultsTask;
        synchronized (this) {
            if (defaultsApplied) {
                defaultsTask = null;
            } else {
                long minFetchSeconds = BuildConfig.DEBUG ? 0L : 900L;
                FirebaseRemoteConfigSettings settings = new FirebaseRemoteConfigSettings.Builder()
                        .setMinimumFetchIntervalInSeconds(minFetchSeconds)
                        .setFetchTimeoutInSeconds(10L)
                        .build();
                remoteConfig.setConfigSettingsAsync(settings);
                defaultsTask = remoteConfig.setDefaultsAsync(R.xml.remote_config_defaults)
                        .addOnCompleteListener(t -> defaultsApplied = true);
            }
        }
        if (defaultsTask != null && afterDefaults != null) {
            defaultsTask.addOnCompleteListener(t -> afterDefaults.run());
        } else if (afterDefaults != null) {
            afterDefaults.run();
        }
    }

    public void fetchAndActivate(FetchCallback callback) {
        ConfigSnapshot cached = getCachedSnapshot();
        FirebaseRemoteConfig remoteConfig = getRemoteConfigIfAvailable();
        if (remoteConfig == null) {
            lastStatusMessage = "Local/default mode (no google-services.json)";
            cachePrefs.edit().putString(PREF_LAST_FETCH_STATUS, lastStatusMessage).apply();
            postCallback(callback, cached);
            return;
        }

        if (!NetworkUtils.hasInternetConnection(context)) {
            ensureDefaultsApplied(remoteConfig, null);
            lastStatusMessage = "Cached fallback (offline WiFi mode)";
            cachePrefs.edit().putString(PREF_LAST_FETCH_STATUS, lastStatusMessage).apply();
            postCallback(callback, cached);
            return;
        }

        ensureDefaultsApplied(remoteConfig, () -> {
            final AtomicBoolean completed = new AtomicBoolean(false);
            final Runnable timeoutFallback = () -> {
                if (completed.compareAndSet(false, true)) {
                    lastStatusMessage = "Cached fallback (fetch timed out)";
                    cachePrefs.edit().putString(PREF_LAST_FETCH_STATUS, lastStatusMessage).apply();
                    postCallback(callback, getCachedSnapshot());
                }
            };
            mainHandler.postDelayed(timeoutFallback, 12_000L);

            remoteConfig.fetchAndActivate()
                    .addOnCompleteListener(task -> {
                        mainHandler.removeCallbacks(timeoutFallback);
                        if (!completed.compareAndSet(false, true)) return;
                        if (task.isSuccessful()) {
                            lastStatusMessage = "Active (synced from Firebase)";
                            ConfigSnapshot latest = readFromFirebase(remoteConfig, true);
                            persistSnapshot(latest);
                            postCallback(callback, latest);
                        } else {
                            lastStatusMessage = "Cached fallback (offline or throttled)";
                            cachePrefs.edit().putString(PREF_LAST_FETCH_STATUS, lastStatusMessage).apply();
                            postCallback(callback, getCachedSnapshot());
                        }
                    })
                    .addOnFailureListener(e -> {
                        mainHandler.removeCallbacks(timeoutFallback);
                        if (!completed.compareAndSet(false, true)) return;
                        lastStatusMessage = "Cached fallback (network unavailable)";
                        cachePrefs.edit().putString(PREF_LAST_FETCH_STATUS, lastStatusMessage).apply();
                        postCallback(callback, getCachedSnapshot());
                    });
        });
    }

    private FirebaseRemoteConfig getRemoteConfigIfAvailable() {
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context);
            }
            if (FirebaseApp.getApps(context).isEmpty()) {
                return null;
            }
            return FirebaseRemoteConfig.getInstance();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean readBooleanSafe(FirebaseRemoteConfig rc, String key, boolean safeDefault) {
        try {
            com.google.firebase.remoteconfig.FirebaseRemoteConfigValue val = rc.getValue(key);
            if (val.getSource() == FirebaseRemoteConfig.VALUE_SOURCE_STATIC) {
                return safeDefault;
            }
            String raw = val.asString();
            if (raw.trim().isEmpty()) {
                return safeDefault;
            }
            return val.asBoolean();
        } catch (Throwable ignored) {
            return safeDefault;
        }
    }

    private long readLongSafe(FirebaseRemoteConfig rc, String key, long safeDefault) {
        try {
            com.google.firebase.remoteconfig.FirebaseRemoteConfigValue val = rc.getValue(key);
            if (val.getSource() == FirebaseRemoteConfig.VALUE_SOURCE_STATIC) {
                return safeDefault;
            }
            long parsed = val.asLong();
            return parsed > 0 ? parsed : safeDefault;
        } catch (Throwable ignored) {
            return safeDefault;
        }
    }

    private ConfigSnapshot readFromFirebase(FirebaseRemoteConfig rc, boolean fromNetwork) {
        boolean enabled = readBooleanSafe(rc, KEY_IS_APP_ENABLED, true);
        boolean maintenance = readBooleanSafe(rc, KEY_MAINTENANCE_MODE, false);
        String maintenanceMsg = rc.getString(KEY_MAINTENANCE_MESSAGE);
        String latestVerName = rc.getString(KEY_LATEST_VERSION);
        long latestVer = readLongSafe(rc, KEY_LATEST_VERSION_CODE, BuildConfig.VERSION_CODE);
        String link = rc.getString(KEY_UPDATE_LINK);
        boolean forceUpdate = readBooleanSafe(rc, KEY_FORCE_UPDATE, false);
        boolean adsEnabled = readBooleanSafe(rc, KEY_ADS_ENABLED, true);
        boolean adsFromRemote = false;
        try {
            adsFromRemote = rc.getValue(KEY_ADS_ENABLED).getSource() == FirebaseRemoteConfig.VALUE_SOURCE_REMOTE;
        } catch (Throwable ignored) {
        }
        cachePrefs.edit().putBoolean(PREF_ADS_ENABLED_EXPLICIT, adsFromRemote).apply();
        long maxCacheMb = readLongSafe(rc, KEY_MAX_CACHE_SIZE_MB, CacheManager.DEFAULT_MAX_CACHE_MB);
        boolean onlineServices = readBooleanSafe(rc, KEY_ONLINE_SERVICES_ENABLED, true);
        boolean groupCalls = readBooleanSafe(rc, KEY_GROUP_CALLS_ENABLED, true);
        boolean fileTransfer = readBooleanSafe(rc, KEY_FILE_TRANSFER_ENABLED, true);
        boolean publicGroups = readBooleanSafe(rc, KEY_PUBLIC_GROUPS_ENABLED, true);
        boolean everyoneGroup = readBooleanSafe(rc, KEY_EVERYONE_GROUP_ENABLED, true);
        String msg = rc.getString(KEY_UPDATE_MESSAGE);
        if (msg.trim().isEmpty()) {
            msg = context.getString(R.string.force_update_default_msg);
        }
        return new ConfigSnapshot(
                enabled, maintenance, maintenanceMsg, latestVerName, latestVer,
                link, forceUpdate, adsEnabled, maxCacheMb,
                onlineServices, groupCalls, fileTransfer, publicGroups, everyoneGroup,
                msg, fromNetwork, System.currentTimeMillis()
        );
    }

    private void postCallback(FetchCallback callback, ConfigSnapshot snapshot) {
        if (callback == null) return;
        mainHandler.post(() -> callback.onResult(snapshot));
    }
}
