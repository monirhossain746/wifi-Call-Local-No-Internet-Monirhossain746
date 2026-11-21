package com.android.wificalllocal.ui.fragment;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.android.wificalllocal.BuildConfig;
import com.android.wificalllocal.R;
import com.android.wificalllocal.ServiceLocator;
import com.android.wificalllocal.WifiCallLocalApp;
import com.android.wificalllocal.ads.AdConsentManager;
import com.android.wificalllocal.control.AppControlManager;
import com.android.wificalllocal.control.RemoteConfigManager;
import com.android.wificalllocal.control.UpdateManager;
import com.android.wificalllocal.data.local.entity.PeerEntity;
import com.android.wificalllocal.data.local.entity.SavedContactEntity;
import com.android.wificalllocal.databinding.FragmentSettingsBinding;
import com.android.wificalllocal.network.NetworkUtils;
import com.android.wificalllocal.storage.CacheManager;
import com.android.wificalllocal.ui.MainActivity;
import com.android.wificalllocal.ui.MainViewModel;
import com.android.wificalllocal.util.AvatarLoader;
import com.android.wificalllocal.util.PreferencesManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

public class SettingsFragment extends Fragment {

    public static final String GITHUB_REPO_URL = "https://github.com/monirhossain746/wifi-Call-Local-No-Internet-Monirhossain746";

    private FragmentSettingsBinding binding;
    private MainViewModel viewModel;
    private ServiceLocator locator;
    private PreferencesManager prefs;
    private RemoteConfigManager remoteConfigManager;
    private UpdateManager updateManager;
    private RemoteConfigManager.ConfigSnapshot currentUpdateSnapshot;

    private List<PeerEntity> blockedPeersCache;
    private List<SavedContactEntity> savedContactsCache;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        locator = ServiceLocator.getInstance(requireContext());
        prefs = locator.getPreferencesManager();
        remoteConfigManager = locator.getAppControlManager().getRemoteConfigManager();
        updateManager = new UpdateManager(requireContext());
        currentUpdateSnapshot = remoteConfigManager.getCachedSnapshot();
        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);

        observeData();
        refreshDynamicLabels();
        setupActions();
    }

    private void observeData() {
        viewModel.observeProfile().observe(getViewLifecycleOwner(), me -> {
            if (me != null && binding != null) {
                binding.settingsProfileName.setText(me.name);
                binding.settingsProfileBio.setText(me.bio.isEmpty()
                        ? getString(R.string.app_tagline)
                        : me.bio);
                AvatarLoader.loadLocalAvatar(binding.settingsAvatar, me.avatarPath, me.name, me.deviceId);
                refreshStatusChips(me.activeStatus);
            }
        });

        viewModel.observeBlockedPeers().observe(getViewLifecycleOwner(), list -> {
            blockedPeersCache = list;
            if (binding != null) {
                int count = list != null ? list.size() : 0;
                binding.btnBlockedUsers.setText(getString(R.string.settings_blocked_users) + " (" + count + ")");
            }
        });

        viewModel.observeSavedContacts().observe(getViewLifecycleOwner(), list -> {
            savedContactsCache = list;
            if (binding != null) {
                int count = list != null ? list.size() : 0;
                binding.btnSavedContacts.setText(getString(R.string.settings_saved_contacts) + " (" + count + ")");
            }
        });
    }

    private void refreshDynamicLabels() {
        NetworkUtils.LocalNetworkInfo info = NetworkUtils.detectLocalNetwork(requireContext());
        String ipText = info.isValid() ? info.ipAddress : getString(R.string.status_no_local_network);
        binding.settingsDeviceIp.setText("ID: " + prefs.getOrCreateDeviceId().substring(0, 8)
                + " • " + ipText + ":" + prefs.getServerPort());

        binding.switchAutoConnect.setChecked(prefs.isAutoConnect());
        binding.switchNotifications.setChecked(prefs.areNotificationsEnabled());

        binding.btnConnectionMode.setText(getString(R.string.settings_connection_mode) + ": " + formatMode(prefs.getConnectionMode()));
        binding.btnServerPort.setText(getString(R.string.settings_server_port) + ": " + prefs.getServerPort());
        binding.btnThemeSelect.setText(getString(R.string.settings_theme) + ": " + formatTheme(prefs.getThemeMode()));
        binding.btnLanguageSelect.setText(getString(R.string.settings_language) + ": " + formatLanguage(prefs.getLanguage()));

        RemoteConfigManager.ConfigSnapshot snap = remoteConfigManager.getCachedSnapshot();
        refreshUpdateCardUi(snap, false);
        refreshStorageAndDiagnosticsUi(snap, info);
    }

    private void refreshStorageAndDiagnosticsUi(RemoteConfigManager.ConfigSnapshot snap, NetworkUtils.LocalNetworkInfo net) {
        if (binding == null) return;
        long maxMb = snap != null ? snap.maxCacheSizeMb : CacheManager.DEFAULT_MAX_CACHE_MB;
        binding.settingsMaxCache.setText(getString(R.string.settings_max_cache_format, maxMb));
        binding.settingsCurrentCache.setText(getString(R.string.settings_current_cache_format, "Calculating…"));
        locator.getCacheManager().getCacheStatsAsync(maxMb, stats -> {
            if (!isAdded() || binding == null || stats == null) return;
            binding.settingsCurrentCache.setText(getString(
                    R.string.settings_current_cache_format,
                    CacheManager.formatSizeMb(stats.totalBytes)));
        });

        long lastFetch = remoteConfigManager.getLastSuccessfulFetchMs();
        String fetchTime = lastFetch > 0
                ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(lastFetch))
                : "Never synced";
        String rcState = (snap != null && !snap.isAppEnabled)
                ? "Disabled (Kill Switch)"
                : ((snap != null && snap.maintenanceMode) ? "Maintenance Mode" : "Normal");
        binding.settingsFirebaseStatus.setText(
                "Project: wifi-call-local-1\n"
                        + "SDK Init: " + remoteConfigManager.getFirebaseInitStatusLabel() + "\n"
                        + "Remote Config: " + remoteConfigManager.getRemoteConfigStatusLabel() + "\n"
                        + "App Control: " + rcState + " • Last sync: " + fetchTime);

        AdConsentManager adMgr = locator.getAdConsentManager();
        binding.settingsAdmobStatus.setText(
                "SDK Init: " + adMgr.getInitStatus() + "\n"
                        + "Banner: " + adMgr.getBannerStatus() + "\n"
                        + "Availability: " + adMgr.getAvailabilityStatus());

        boolean hasNet = NetworkUtils.hasInternetConnection(requireContext());
        String wifiLine = net != null && net.isValid()
                ? ("Local WiFi Ready (" + net.ipAddress + ":" + prefs.getServerPort() + " • " + net.routeType + ")")
                : "No Local WiFi/Hotspot detected";
        binding.settingsCommStatus.setText(
                wifiLine + "\n"
                        + "Internet: " + (hasNet ? "Connected (Remote Config/Ads enabled)" : "Offline (Local P2P & Web active)"));
    }

    private void refreshStatusChips(String status) {
        if (PreferencesManager.STATUS_BUSY.equals(status)) {
            binding.statusChipGroup.check(R.id.chip_status_busy);
        } else if (PreferencesManager.STATUS_DND.equals(status)) {
            binding.statusChipGroup.check(R.id.chip_status_dnd);
        } else {
            binding.statusChipGroup.check(R.id.chip_status_online);
        }
    }

    private void setupActions() {
        View.OnClickListener editProfileClick = v -> ((MainActivity) requireActivity()).openProfileEdit();
        binding.cardMyProfile.setOnClickListener(editProfileClick);
        binding.btnEditProfile.setOnClickListener(editProfileClick);

        binding.statusChipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.chip_status_busy) {
                viewModel.updateActiveStatus(PreferencesManager.STATUS_BUSY);
            } else if (id == R.id.chip_status_dnd) {
                viewModel.updateActiveStatus(PreferencesManager.STATUS_DND);
            } else {
                viewModel.updateActiveStatus(PreferencesManager.STATUS_ONLINE);
            }
        });

        binding.btnConnectionMode.setOnClickListener(v -> showConnectionModeDialog());

        binding.switchAutoConnect.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (buttonView.isPressed()) {
                prefs.setAutoConnect(isChecked);
            }
        });

        binding.btnServerPort.setOnClickListener(v -> showServerPortDialog());

        binding.switchNotifications.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (buttonView.isPressed()) {
                prefs.setNotificationsEnabled(isChecked);
            }
        });

        binding.btnThemeSelect.setOnClickListener(v -> showThemeDialog());
        binding.btnLanguageSelect.setOnClickListener(v -> showLanguageDialog());
        binding.btnStorageInfo.setOnClickListener(v -> showStorageDialog());
        binding.btnBlockedUsers.setOnClickListener(v -> showBlockedUsersDialog());
        binding.btnSavedContacts.setOnClickListener(v -> showSavedContactsDialog());
        binding.btnBatteryOpt.setOnClickListener(v -> showBatteryOptimizationDialog());
        binding.btnPrivacyTrust.setOnClickListener(v -> showPrivacyDialog());
        binding.btnClearAllCache.setOnClickListener(v -> confirmAndClearCache());
        binding.btnCheckUpdate.setOnClickListener(v -> performCheckUpdate(false));
        binding.btnUpdateNow.setOnClickListener(v -> handleUpdateNowClicked());
        binding.btnManualUpdate.setOnClickListener(v -> openManualUpdateGitHub());
        binding.btnAboutDeveloper.setOnClickListener(v -> ((MainActivity) requireActivity()).openAboutDeveloper());
    }

    private void showConnectionModeDialog() {
        String[] labels = {
                getString(R.string.mode_auto),
                getString(R.string.mode_wifi),
                getString(R.string.mode_hotspot),
                getString(R.string.mode_wifi_direct)
        };
        String[] keys = {
                PreferencesManager.MODE_AUTO,
                PreferencesManager.MODE_WIFI,
                PreferencesManager.MODE_HOTSPOT,
                PreferencesManager.MODE_WIFI_DIRECT
        };
        int checked = 0;
        String current = prefs.getConnectionMode();
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(current)) checked = i;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_connection_mode)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    prefs.setConnectionMode(keys[which]);
                    refreshDynamicLabels();
                    d.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showServerPortDialog() {
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(prefs.getServerPort()));
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_server_port)
                .setMessage("Enter local HTTP/WebSocket port (1024–65535):")
                .setView(input)
                .setPositiveButton(R.string.save, (d, w) -> {
                    try {
                        int port = Integer.parseInt(input.getText().toString().trim());
                        if (port < 1024 || port > 65535) {
                            Toast.makeText(requireContext(), "Port must be 1024–65535", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        prefs.setServerPort(port);
                        locator.restartServer();
                        refreshDynamicLabels();
                        Toast.makeText(requireContext(), "Server restarted on port " + prefs.getServerPort(), Toast.LENGTH_SHORT).show();
                    } catch (NumberFormatException e) {
                        Toast.makeText(requireContext(), "Invalid port number", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showThemeDialog() {
        String[] labels = {
                getString(R.string.theme_system),
                getString(R.string.theme_dark),
                getString(R.string.theme_light)
        };
        String[] keys = {
                PreferencesManager.THEME_SYSTEM,
                PreferencesManager.THEME_DARK,
                PreferencesManager.THEME_LIGHT
        };
        int checked = 1;
        String current = prefs.getThemeMode();
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(current)) checked = i;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_theme)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    prefs.setThemeMode(keys[which]);
                    WifiCallLocalApp.applyTheme(keys[which]);
                    refreshDynamicLabels();
                    d.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showLanguageDialog() {
        String[] labels = {
                getString(R.string.lang_en),
                getString(R.string.lang_bn)
        };
        String[] keys = {"en", "bn"};
        int checked = "bn".equals(prefs.getLanguage()) ? 1 : 0;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_language)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    prefs.setLanguage(keys[which]);
                    WifiCallLocalApp.applyLanguage(keys[which]);
                    d.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showStorageDialog() {
        File dlDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "WifiCallLocal");
        File chunkDir = new File(requireContext().getFilesDir(), "transfer_chunks");
        long chunkBytes = folderSize(chunkDir);
        String msg = "Saved files location:\nDownloads/WifiCallLocal/{Images, Videos, Audio, Documents, Others}\n\n"
                + "Path: " + dlDir.getAbsolutePath() + "\n"
                + "Pending chunk cache: " + (chunkBytes / 1024) + " KB";
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_storage)
                .setMessage(msg)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private long folderSize(File dir) {
        if (dir == null || !dir.exists()) return 0L;
        File[] children = dir.listFiles();
        if (children == null) return 0L;
        long total = 0L;
        for (File f : children) {
            total += f.isDirectory() ? folderSize(f) : f.length();
        }
        return total;
    }

    private void showBlockedUsersDialog() {
        if (blockedPeersCache == null || blockedPeersCache.isEmpty()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.settings_blocked_users)
                    .setMessage("No blocked peers.")
                    .setPositiveButton(R.string.ok, null)
                    .show();
            return;
        }
        String[] names = new String[blockedPeersCache.size()];
        for (int i = 0; i < blockedPeersCache.size(); i++) {
            PeerEntity p = blockedPeersCache.get(i);
            names[i] = p.name + " (" + p.ipAddress + ") — Tap to unblock";
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_blocked_users)
                .setItems(names, (d, which) -> {
                    PeerEntity p = blockedPeersCache.get(which);
                    viewModel.blockPeer(p, false);
                    Toast.makeText(requireContext(), "Unblocked " + p.name, Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private void showSavedContactsDialog() {
        if (savedContactsCache == null || savedContactsCache.isEmpty()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.settings_saved_contacts)
                    .setMessage("No locally saved contacts yet.")
                    .setPositiveButton(R.string.ok, null)
                    .show();
            return;
        }
        String[] items = new String[savedContactsCache.size()];
        for (int i = 0; i < savedContactsCache.size(); i++) {
            SavedContactEntity c = savedContactsCache.get(i);
            items[i] = c.displayName + (c.phoneOrIp.isEmpty() ? "" : " (" + c.phoneOrIp + ")");
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_saved_contacts)
                .setItems(items, (d, which) -> {
                    SavedContactEntity c = savedContactsCache.get(which);
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(c.displayName)
                            .setMessage("Device ID: " + c.deviceId + "\nAddress: " + c.phoneOrIp + "\nNotes: " + c.notes)
                            .setPositiveButton(R.string.ok, null)
                            .setNegativeButton(R.string.delete, (d2, w2) -> viewModel.deleteSavedContact(c.contactId))
                            .show();
                })
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private void showBatteryOptimizationDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_battery_optimization)
                .setMessage(R.string.battery_honest_note)
                .setPositiveButton(R.string.open_settings, (d, w) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + requireContext().getPackageName()));
                        startActivity(intent);
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showPrivacyDialog() {
        String body = getString(R.string.privacy_statement_en)
                + "\n\n---\n\n"
                + getString(R.string.privacy_statement_bn);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.privacy_title)
                .setMessage(body)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private String formatMode(String mode) {
        if (PreferencesManager.MODE_WIFI.equals(mode)) return getString(R.string.mode_wifi);
        if (PreferencesManager.MODE_HOTSPOT.equals(mode)) return getString(R.string.mode_hotspot);
        if (PreferencesManager.MODE_WIFI_DIRECT.equals(mode)) return getString(R.string.mode_wifi_direct);
        return getString(R.string.mode_auto);
    }

    private String formatTheme(String theme) {
        if (PreferencesManager.THEME_LIGHT.equals(theme)) return getString(R.string.theme_light);
        if (PreferencesManager.THEME_DARK.equals(theme)) return getString(R.string.theme_dark);
        return getString(R.string.theme_system);
    }

    private String formatLanguage(String lang) {
        return "bn".equals(lang) ? getString(R.string.lang_bn) : getString(R.string.lang_en);
    }

    private void refreshUpdateCardUi(@Nullable RemoteConfigManager.ConfigSnapshot snapshot, boolean showNetworkNote) {
        if (binding == null) return;
        RemoteConfigManager.ConfigSnapshot effective = snapshot != null
                ? snapshot
                : remoteConfigManager.getCachedSnapshot();
        currentUpdateSnapshot = effective;

        binding.settingsInstalledVersion.setText(getString(
                R.string.settings_installed_version_format,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE
        ));
        binding.settingsLatestVersion.setText(getString(
                R.string.settings_latest_version_format,
                effective.latestVersionName,
                effective.latestVersionCode
        ));

        boolean updateAvailable = effective.latestVersionCode > BuildConfig.VERSION_CODE;
        if (updateAvailable) {
            binding.settingsUpdateBadge.setText(R.string.settings_update_badge_available);
            String msg = (effective.updateMessage != null && !effective.updateMessage.trim().isEmpty())
                    ? effective.updateMessage
                    : getString(R.string.settings_update_available_format, effective.latestVersionCode);
            binding.settingsUpdateStatus.setText(msg);
        } else {
            binding.settingsUpdateBadge.setText(R.string.settings_update_badge_latest);
            if (showNetworkNote && !effective.fromNetwork) {
                binding.settingsUpdateStatus.setText(R.string.settings_update_offline_fallback);
            } else {
                binding.settingsUpdateStatus.setText(R.string.settings_update_up_to_date);
            }
        }
    }

    private void confirmAndClearCache() {
        RemoteConfigManager.ConfigSnapshot snap = remoteConfigManager != null
                ? remoteConfigManager.getCachedSnapshot()
                : null;
        long maxMb = snap != null ? snap.maxCacheSizeMb : CacheManager.DEFAULT_MAX_CACHE_MB;

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.clear_cache_confirm_title)
                .setMessage(R.string.clear_cache_confirm_message)
                .setPositiveButton(R.string.btn_clear_all_cache, (d, w) -> {
                    if (binding != null) binding.btnClearAllCache.setEnabled(false);
                    locator.getCacheManager().clearAllCacheAsync(maxMb, result -> {
                        if (!isAdded() || binding == null) return;
                        binding.btnClearAllCache.setEnabled(true);
                        refreshStorageAndDiagnosticsUi(snap, NetworkUtils.detectLocalNetwork(requireContext()));
                        Toast.makeText(
                                requireContext(),
                                getString(R.string.clear_cache_done_format, CacheManager.formatSizeMb(result.freedBytes)),
                                Toast.LENGTH_LONG
                        ).show();
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void openManualUpdateGitHub() {
        Uri repoUri = Uri.parse(GITHUB_REPO_URL);
        Toast.makeText(requireContext(), "Opening official GitHub repository…", Toast.LENGTH_SHORT).show();
        Intent githubIntent = new Intent(Intent.ACTION_VIEW, repoUri);
        githubIntent.setPackage("com.github.android");
        try {
            startActivity(githubIntent);
            return;
        } catch (ActivityNotFoundException ignored) {
        }
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, repoUri);
        try {
            startActivity(browserIntent);
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.error_no_browser_github, Toast.LENGTH_LONG).show();
        }
    }

    private void performCheckUpdate(boolean startInstallIfAvailable) {
        if (binding == null || remoteConfigManager == null) return;
        binding.btnCheckUpdate.setEnabled(false);
        binding.settingsUpdateStatus.setText(R.string.settings_update_checking);

        remoteConfigManager.fetchAndActivate(snapshot -> {
            if (!isAdded() || binding == null) return;
            binding.btnCheckUpdate.setEnabled(true);

            if (snapshot != null && !snapshot.isAppEnabled) {
                AppControlManager.AppGateState gateState = AppControlManager.evaluate(snapshot, BuildConfig.VERSION_CODE);
                locator.getAppControlManager().enforceGateIfBlocked(requireActivity(), gateState);
                return;
            }

            refreshUpdateCardUi(snapshot, true);
            refreshStorageAndDiagnosticsUi(snapshot, NetworkUtils.detectLocalNetwork(requireContext()));
            if (startInstallIfAvailable) {
                if (snapshot != null && snapshot.latestVersionCode > BuildConfig.VERSION_CODE) {
                    startApkUpdate(snapshot.updateLink);
                } else {
                    Toast.makeText(requireContext(), R.string.settings_update_up_to_date, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void handleUpdateNowClicked() {
        RemoteConfigManager.ConfigSnapshot snapshot = currentUpdateSnapshot != null
                ? currentUpdateSnapshot
                : remoteConfigManager.getCachedSnapshot();
        if (snapshot != null && snapshot.latestVersionCode > BuildConfig.VERSION_CODE) {
            startApkUpdate(snapshot.updateLink);
        } else {
            performCheckUpdate(true);
        }
    }

    private void startApkUpdate(String updateUrl) {
        if (binding == null || updateManager == null) return;
        if (!UpdateManager.isValidHttpsUpdateUrl(updateUrl)) {
            binding.settingsUpdateStatus.setText(R.string.update_invalid_url);
            Toast.makeText(requireContext(), R.string.update_invalid_url, Toast.LENGTH_LONG).show();
            return;
        }

        binding.btnCheckUpdate.setEnabled(false);
        binding.btnUpdateNow.setEnabled(false);
        binding.settingsUpdateProgress.setVisibility(View.VISIBLE);
        binding.settingsUpdateProgress.setProgress(0);
        binding.settingsUpdateStatus.setText(R.string.update_downloading);

        updateManager.startUpdate(updateUrl, BuildConfig.VERSION_CODE, new UpdateManager.UpdateListener() {
            @Override
            public void onProgress(int percent, String statusText) {
                if (!isAdded() || binding == null) return;
                binding.settingsUpdateProgress.setVisibility(View.VISIBLE);
                binding.settingsUpdateProgress.setProgress(percent);
                binding.settingsUpdateStatus.setText(statusText);
            }

            @Override
            public void onPermissionRequired(Intent settingsIntent) {
                if (!isAdded() || binding == null) return;
                binding.btnCheckUpdate.setEnabled(true);
                binding.btnUpdateNow.setEnabled(true);
                binding.settingsUpdateProgress.setVisibility(View.GONE);
                binding.settingsUpdateStatus.setText(R.string.update_perm_unknown_sources);
                Toast.makeText(requireContext(), R.string.update_perm_unknown_sources, Toast.LENGTH_LONG).show();
                startActivity(settingsIntent);
            }

            @Override
            public void onReadyToInstall(Intent installIntent) {
                if (!isAdded() || binding == null) return;
                binding.btnCheckUpdate.setEnabled(true);
                binding.btnUpdateNow.setEnabled(true);
                binding.settingsUpdateProgress.setVisibility(View.GONE);
                binding.settingsUpdateStatus.setText(R.string.settings_update_ready_install);
                startActivity(installIntent);
            }

            @Override
            public void onError(String message) {
                if (!isAdded() || binding == null) return;
                binding.btnCheckUpdate.setEnabled(true);
                binding.btnUpdateNow.setEnabled(true);
                binding.settingsUpdateProgress.setVisibility(View.GONE);
                binding.settingsUpdateStatus.setText(message);
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding != null) {
            refreshDynamicLabels();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (updateManager != null) {
            updateManager.cancelDownload();
        }
    }
}
