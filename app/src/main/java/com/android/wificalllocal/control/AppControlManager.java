package com.android.wificalllocal.control;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.android.wificalllocal.BuildConfig;
import com.android.wificalllocal.service.LocalMeshService;
import com.android.wificalllocal.ui.blocked.AppBlockedActivity;

/**
 * Evaluates Firebase Remote Config kill switch, maintenance mode, force/optional updates,
 * and feature flags cleanly outside Activities while keeping local offline WiFi features
 * available unless explicitly disabled via is_app_enabled == false or mandatory force update.
 */
public class AppControlManager {

    public enum AccessDecision {
        ALLOWED,
        BLOCKED_DISABLED,
        BLOCKED_FORCE_UPDATE
    }

    public static class AppGateState {
        public final AccessDecision decision;
        public final long latestVersionCode;
        public final String latestVersionName;
        public final String updateLink;
        public final String updateMessage;
        public final boolean forceUpdate;
        public final boolean maintenanceMode;
        public final String maintenanceMessage;
        public final boolean adsEnabled;
        public final long maxCacheSizeMb;
        public final boolean onlineServicesEnabled;
        public final boolean groupCallsEnabled;
        public final boolean fileTransferEnabled;
        public final boolean publicGroupsEnabled;
        public final boolean everyoneGroupEnabled;
        public final boolean fromNetwork;

        public AppGateState(AccessDecision decision, long latestVersionCode,
                            String updateLink, String updateMessage, boolean fromNetwork) {
            this(decision, latestVersionCode, BuildConfig.VERSION_NAME, updateLink, updateMessage,
                    false, false, "", true, 500L, true, true, true, true, true, fromNetwork);
        }

        public AppGateState(AccessDecision decision, long latestVersionCode, String latestVersionName,
                            String updateLink, String updateMessage, boolean forceUpdate,
                            boolean maintenanceMode, String maintenanceMessage, boolean adsEnabled,
                            long maxCacheSizeMb, boolean onlineServicesEnabled,
                            boolean groupCallsEnabled, boolean fileTransferEnabled,
                            boolean publicGroupsEnabled, boolean everyoneGroupEnabled,
                            boolean fromNetwork) {
            this.decision = decision;
            this.latestVersionCode = latestVersionCode;
            this.latestVersionName = latestVersionName != null ? latestVersionName : BuildConfig.VERSION_NAME;
            this.updateLink = updateLink != null ? updateLink : "";
            this.updateMessage = updateMessage != null ? updateMessage : "";
            this.forceUpdate = forceUpdate;
            this.maintenanceMode = maintenanceMode;
            this.maintenanceMessage = maintenanceMessage != null ? maintenanceMessage : "";
            this.adsEnabled = adsEnabled;
            this.maxCacheSizeMb = maxCacheSizeMb;
            this.onlineServicesEnabled = onlineServicesEnabled;
            this.groupCallsEnabled = groupCallsEnabled;
            this.fileTransferEnabled = fileTransferEnabled;
            this.publicGroupsEnabled = publicGroupsEnabled;
            this.everyoneGroupEnabled = everyoneGroupEnabled;
            this.fromNetwork = fromNetwork;
        }

        public boolean isBlocked() {
            return decision != AccessDecision.ALLOWED;
        }

        public boolean isOptionalUpdateAvailable(int currentVersionCode) {
            return decision == AccessDecision.ALLOWED
                    && latestVersionCode > currentVersionCode
                    && !forceUpdate;
        }
    }

    public interface GateCheckListener {
        void onGateEvaluated(AppGateState state);
    }

    private final Context context;
    private final RemoteConfigManager remoteConfigManager;
    private volatile AppGateState lastState;

    public AppControlManager(Context context) {
        this.context = context.getApplicationContext();
        this.remoteConfigManager = new RemoteConfigManager(this.context);
        this.lastState = evaluate(remoteConfigManager.getCachedSnapshot(), BuildConfig.VERSION_CODE);
    }

    public static AppGateState evaluate(RemoteConfigManager.ConfigSnapshot snapshot, int currentVersionCode) {
        if (snapshot == null) {
            return new AppGateState(AccessDecision.ALLOWED, currentVersionCode, "", "", false);
        }
        if (!snapshot.isAppEnabled) {
            return new AppGateState(
                    AccessDecision.BLOCKED_DISABLED,
                    snapshot.latestVersionCode,
                    snapshot.latestVersionName,
                    snapshot.updateLink,
                    snapshot.updateMessage,
                    snapshot.forceUpdate,
                    snapshot.maintenanceMode,
                    snapshot.maintenanceMessage,
                    snapshot.adsEnabled,
                    snapshot.maxCacheSizeMb,
                    snapshot.onlineServicesEnabled,
                    snapshot.groupCallsEnabled,
                    snapshot.fileTransferEnabled,
                    snapshot.publicGroupsEnabled,
                    snapshot.everyoneGroupEnabled,
                    snapshot.fromNetwork
            );
        }
        if (snapshot.forceUpdate && snapshot.latestVersionCode > currentVersionCode) {
            return new AppGateState(
                    AccessDecision.BLOCKED_FORCE_UPDATE,
                    snapshot.latestVersionCode,
                    snapshot.latestVersionName,
                    snapshot.updateLink,
                    snapshot.updateMessage,
                    true,
                    snapshot.maintenanceMode,
                    snapshot.maintenanceMessage,
                    snapshot.adsEnabled,
                    snapshot.maxCacheSizeMb,
                    snapshot.onlineServicesEnabled,
                    snapshot.groupCallsEnabled,
                    snapshot.fileTransferEnabled,
                    snapshot.publicGroupsEnabled,
                    snapshot.everyoneGroupEnabled,
                    snapshot.fromNetwork
            );
        }
        return new AppGateState(
                AccessDecision.ALLOWED,
                snapshot.latestVersionCode,
                snapshot.latestVersionName,
                snapshot.updateLink,
                snapshot.updateMessage,
                snapshot.forceUpdate,
                snapshot.maintenanceMode,
                snapshot.maintenanceMessage,
                snapshot.adsEnabled,
                snapshot.maxCacheSizeMb,
                snapshot.onlineServicesEnabled,
                snapshot.groupCallsEnabled,
                snapshot.fileTransferEnabled,
                snapshot.publicGroupsEnabled,
                snapshot.everyoneGroupEnabled,
                snapshot.fromNetwork
        );
    }

    public AppGateState getCurrentGateState() {
        lastState = evaluate(remoteConfigManager.getCachedSnapshot(), BuildConfig.VERSION_CODE);
        return lastState;
    }

    public AppGateState getLastState() {
        return lastState != null ? lastState : getCurrentGateState();
    }

    public void checkRemoteGate(GateCheckListener listener) {
        remoteConfigManager.fetchAndActivate(snapshot -> {
            AppGateState evaluated = evaluate(snapshot, BuildConfig.VERSION_CODE);
            lastState = evaluated;
            if (evaluated.isBlocked()) {
                LocalMeshService.stop(context);
            }
            if (listener != null) {
                listener.onGateEvaluated(evaluated);
            }
        });
    }

    public void enforceGateIfBlocked(Activity activity, AppGateState state) {
        if (activity == null || state == null || !state.isBlocked()) return;
        if (activity instanceof AppBlockedActivity) return;

        LocalMeshService.stop(context);
        Intent i = new Intent(activity, AppBlockedActivity.class);
        i.putExtra(AppBlockedActivity.EXTRA_DECISION, state.decision.name());
        i.putExtra(AppBlockedActivity.EXTRA_UPDATE_URL, state.updateLink);
        i.putExtra(AppBlockedActivity.EXTRA_UPDATE_MESSAGE, state.updateMessage);
        i.putExtra(AppBlockedActivity.EXTRA_LATEST_VERSION, state.latestVersionCode);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(i);
        activity.finish();
    }

    public RemoteConfigManager getRemoteConfigManager() {
        return remoteConfigManager;
    }
}
