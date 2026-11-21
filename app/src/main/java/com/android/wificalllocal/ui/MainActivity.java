package com.android.wificalllocal.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.android.wificalllocal.R;
import com.android.wificalllocal.ServiceLocator;
import com.android.wificalllocal.control.AppControlManager;
import com.android.wificalllocal.databinding.ActivityMainBinding;
import com.android.wificalllocal.network.NetworkUtils;
import com.android.wificalllocal.service.LocalMeshService;
import com.android.wificalllocal.ui.call.CallActivity;
import com.android.wificalllocal.ui.fragment.AboutDeveloperFragment;
import com.android.wificalllocal.ui.fragment.AllGroupsFragment;
import com.android.wificalllocal.ui.fragment.CallsFragment;
import com.android.wificalllocal.ui.fragment.ChatDetailFragment;
import com.android.wificalllocal.ui.fragment.ChatsFragment;
import com.android.wificalllocal.ui.fragment.GroupInfoFragment;
import com.android.wificalllocal.ui.fragment.HomeFragment;
import com.android.wificalllocal.ui.fragment.NearbyFragment;
import com.android.wificalllocal.ui.fragment.ProfileSetupFragment;
import com.android.wificalllocal.ui.fragment.QrShowFragment;
import com.android.wificalllocal.ui.fragment.SettingsFragment;
import com.android.wificalllocal.ui.qr.QrScannerActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private ServiceLocator locator;

    private int currentTabId = R.id.tab_home;
    private final Map<Integer, Deque<String>> tabStacks = new HashMap<>();
    private long lastBackPressMs = 0L;

    private final ActivityResultLauncher<Intent> qrScanLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String scanned = result.getData().getStringExtra(QrScannerActivity.EXTRA_QR_RESULT);
                    handleScannedQr(scanned);
                }
            }
    );

    private final ActivityResultLauncher<String[]> discoveryPermLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            res -> startMeshService()
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        locator = ServiceLocator.getInstance(this);

        // Immediate cached gate check before starting mesh or showing chats
        AppControlManager.AppGateState cachedGate = locator.getAppControlManager().getCurrentGateState();
        if (cachedGate.isBlocked()) {
            locator.getAppControlManager().enforceGateIfBlocked(this, cachedGate);
            return;
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot, (v, windowInsets) -> {
            Insets sys = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets safe = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            boolean imeOpen = windowInsets.isVisible(WindowInsetsCompat.Type.ime()) && ime.bottom > 0;
            boolean detailOpen = isInDetailScreen();
            boolean onboarding = !locator.getPreferencesManager().isProfileSetupDone();
            boolean hideBottomNav = imeOpen || detailOpen || onboarding;
            boolean suppressBanner = detailOpen || onboarding;

            binding.fragmentContainer.setPadding(safe.left, safe.top, safe.right, 0);
            binding.adBannerContainer.setPadding(safe.left, 0, safe.right, 0);
            if (hideBottomNav) {
                binding.bottomNavigation.setVisibility(View.GONE);
                binding.bottomNavigation.setPadding(0, 0, 0, 0);
                binding.mainRoot.setPadding(0, 0, 0, Math.max(sys.bottom, ime.bottom));
            } else {
                binding.bottomNavigation.setVisibility(View.VISIBLE);
                binding.bottomNavigation.setPadding(safe.left, 0, safe.right, sys.bottom);
                binding.mainRoot.setPadding(0, 0, 0, 0);
            }
            locator.getAdConsentManager().setSuppressedForScreen(suppressBanner);
            return WindowInsetsCompat.CONSUMED;
        });

        initTabStacks();
        setupBottomNav();
        setupBackDispatcher();

        // Sync cached Remote Config ads_enabled state immediately without blocking startup/test ads
        locator.getAdConsentManager().setAdsEnabledByRemoteConfig(cachedGate.adsEnabled);

        if (!locator.getPreferencesManager().isProfileSetupDone()) {
            showOnboarding();
        } else {
            showTab(R.id.tab_home);
            requestDiscoveryPermissionsIfNeededAndStart();
            locator.getAdConsentManager().initializeAndLoadBanner(this, binding.adBannerContainer);
        }

        handleNotificationIntent(getIntent());
        checkRemoteControlGate();
    }

    private boolean maintenanceNoticeShown = false;
    private boolean optionalUpdatePromptShown = false;

    @Override
    protected void onResume() {
        super.onResume();
        if (locator != null) {
            locator.getAdConsentManager().resumeBanner();
        }
        checkRemoteControlGate();
    }

    @Override
    protected void onPause() {
        if (locator != null) {
            locator.getAdConsentManager().pauseBanner();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (locator != null) {
            locator.getAdConsentManager().destroyBanner();
        }
        super.onDestroy();
    }

    private void checkRemoteControlGate() {
        if (locator == null) return;
        locator.getAppControlManager().checkRemoteGate(state -> {
            if (isFinishing() || isDestroyed()) return;
            if (state.isBlocked()) {
                locator.getAppControlManager().enforceGateIfBlocked(this, state);
                return;
            }
            locator.getAdConsentManager().setAdsEnabledByRemoteConfig(state.adsEnabled);
            if (state.adsEnabled && locator.getPreferencesManager().isProfileSetupDone()) {
                locator.getAdConsentManager().initializeAndLoadBanner(this, binding.adBannerContainer);
            }
            if (state.maintenanceMode && !maintenanceNoticeShown) {
                maintenanceNoticeShown = true;
                String msg = !state.maintenanceMessage.isEmpty()
                        ? state.maintenanceMessage
                        : "Remote services are undergoing maintenance. Local WiFi chat, calling, and file sharing remain active.";
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.maintenance_mode_title)
                        .setMessage(msg)
                        .setPositiveButton(R.string.ok, null)
                        .show();
            } else if (state.isOptionalUpdateAvailable(com.android.wificalllocal.BuildConfig.VERSION_CODE)
                    && !optionalUpdatePromptShown) {
                optionalUpdatePromptShown = true;
                String msg = !state.updateMessage.isEmpty()
                        ? state.updateMessage
                        : ("Version v" + state.latestVersionName + " (Build " + state.latestVersionCode + ") is available.");
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.optional_update_title)
                        .setMessage(msg)
                        .setPositiveButton(R.string.btn_update_now, (d, w) -> {
                            String url = com.android.wificalllocal.control.UpdateManager.isValidHttpsUpdateUrl(state.updateLink)
                                    ? state.updateLink
                                    : SettingsFragment.GITHUB_REPO_URL;
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                            } catch (Exception e) {
                                Toast.makeText(this, R.string.error_no_browser_github, Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }
        });
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        handleNotificationIntent(intent);
    }

    private void handleNotificationIntent(Intent intent) {
        if (intent == null) return;
        String chatId = intent.getStringExtra("open_chat_id");
        if (chatId != null && !chatId.isEmpty() && locator.getPreferencesManager().isProfileSetupDone()) {
            openChatDetail(chatId, chatId, null);
        }
    }

    private void initTabStacks() {
        tabStacks.put(R.id.tab_home, new ArrayDeque<>());
        tabStacks.put(R.id.tab_chats, new ArrayDeque<>());
        tabStacks.put(R.id.tab_nearby, new ArrayDeque<>());
        tabStacks.put(R.id.tab_calls, new ArrayDeque<>());
        tabStacks.put(R.id.tab_settings, new ArrayDeque<>());
    }

    private void setupBottomNav() {
        binding.bottomNavigation.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id != currentTabId) {
                showTab(id);
            }
            return true;
        });
    }

    private void setupBackDispatcher() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!locator.getPreferencesManager().isProfileSetupDone()) {
                    finish();
                    return;
                }
                Deque<String> stack = tabStacks.get(currentTabId);
                if (stack != null && stack.size() > 1) {
                    popCurrentTab();
                    return;
                }
                if (currentTabId != R.id.tab_home) {
                    binding.bottomNavigation.setSelectedItemId(R.id.tab_home);
                    return;
                }
                long now = System.currentTimeMillis();
                if (now - lastBackPressMs < 2000) {
                    finish();
                } else {
                    lastBackPressMs = now;
                    Toast.makeText(MainActivity.this, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    public void showOnboarding() {
        binding.bottomNavigation.setVisibility(View.GONE);
        binding.adBannerContainer.setVisibility(View.GONE);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, ProfileSetupFragment.newInstance(true), "onboarding")
                .commit();
    }

    public void onProfileSetupCompleted() {
        Fragment onboarding = getSupportFragmentManager().findFragmentByTag("onboarding");
        if (onboarding != null) {
            getSupportFragmentManager().beginTransaction().remove(onboarding).commitNow();
        }
        binding.bottomNavigation.setVisibility(View.VISIBLE);
        showTab(R.id.tab_home);
        requestDiscoveryPermissionsIfNeededAndStart();
        locator.getAdConsentManager().initializeAndLoadBanner(this, binding.adBannerContainer);
    }

    public void showTab(int tabId) {
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction tx = fm.beginTransaction();

        // Hide currently visible top fragment
        Deque<String> oldStack = tabStacks.get(currentTabId);
        if (oldStack != null && !oldStack.isEmpty()) {
            Fragment currentFrag = fm.findFragmentByTag(oldStack.peek());
            if (currentFrag != null) tx.hide(currentFrag);
        }

        currentTabId = tabId;
        Deque<String> newStack = tabStacks.get(tabId);
        if (newStack == null) {
            newStack = new ArrayDeque<>();
            tabStacks.put(tabId, newStack);
        }

        if (newStack.isEmpty()) {
            String rootTag = "tab_root_" + tabId;
            Fragment root = createTabRootFragment(tabId);
            newStack.push(rootTag);
            tx.add(R.id.fragment_container, root, rootTag);
        } else {
            Fragment top = fm.findFragmentByTag(newStack.peek());
            if (top != null) tx.show(top);
        }
        tx.commit();
        syncChromeForStackDepth();
    }

    public void pushFragmentToCurrentTab(Fragment fragment, String tagPrefix) {
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction tx = fm.beginTransaction();
        Deque<String> stack = tabStacks.get(currentTabId);
        if (stack == null) {
            stack = new ArrayDeque<>();
            tabStacks.put(currentTabId, stack);
        }
        if (!stack.isEmpty()) {
            Fragment current = fm.findFragmentByTag(stack.peek());
            if (current != null) tx.hide(current);
        }
        String tag = tagPrefix + "_" + currentTabId + "_" + System.currentTimeMillis();
        stack.push(tag);
        tx.add(R.id.fragment_container, fragment, tag);
        tx.commit();
        syncChromeForStackDepth();
    }

    public void popCurrentTab() {
        FragmentManager fm = getSupportFragmentManager();
        Deque<String> stack = tabStacks.get(currentTabId);
        if (stack == null || stack.size() <= 1) return;
        String topTag = stack.pop();
        Fragment top = fm.findFragmentByTag(topTag);
        Fragment prev = fm.findFragmentByTag(stack.peek());
        FragmentTransaction tx = fm.beginTransaction();
        if (top != null) tx.remove(top);
        if (prev != null) tx.show(prev);
        tx.commit();
        syncChromeForStackDepth();
    }

    private boolean isInDetailScreen() {
        Deque<String> stack = tabStacks.get(currentTabId);
        return stack != null && stack.size() > 1;
    }

    private void syncChromeForStackDepth() {
        if (binding == null) return;
        ViewCompat.requestApplyInsets(binding.mainRoot);
    }

    private Fragment createTabRootFragment(int tabId) {
        if (tabId == R.id.tab_chats) return new ChatsFragment();
        if (tabId == R.id.tab_nearby) return new NearbyFragment();
        if (tabId == R.id.tab_calls) return new CallsFragment();
        if (tabId == R.id.tab_settings) return new SettingsFragment();
        return new HomeFragment();
    }

    public void openChatDetail(String chatId, String title, String peerId) {
        pushFragmentToCurrentTab(ChatDetailFragment.newInstance(chatId, title, peerId), "chat");
    }

    public void openGroupInfo(String groupId) {
        pushFragmentToCurrentTab(GroupInfoFragment.newInstance(groupId), "group_info");
    }

    public void openAllGroups() {
        pushFragmentToCurrentTab(new AllGroupsFragment(), "all_groups");
    }

    public void openQrShow() {
        pushFragmentToCurrentTab(new QrShowFragment(), "qr_show");
    }

    public void openAboutDeveloper() {
        pushFragmentToCurrentTab(new AboutDeveloperFragment(), "about");
    }

    public void openProfileEdit() {
        pushFragmentToCurrentTab(ProfileSetupFragment.newInstance(false), "profile_edit");
    }

    public void launchQrScanner() {
        qrScanLauncher.launch(new Intent(this, QrScannerActivity.class));
    }

    public void startOutgoingCall(String peerId, String peerName, String peerIp, String callType) {
        Intent i = new Intent(this, CallActivity.class);
        i.putExtra(CallActivity.EXTRA_INCOMING, false);
        i.putExtra(CallActivity.EXTRA_PEER_ID, peerId);
        i.putExtra(CallActivity.EXTRA_PEER_NAME, peerName);
        i.putExtra(CallActivity.EXTRA_PEER_IP, peerIp);
        i.putExtra(CallActivity.EXTRA_CALL_TYPE, callType);
        startActivity(i);
    }

    public void startGroupCall(String callId, String groupId, String groupName, String callType) {
        AppControlManager.AppGateState gate = locator.getAppControlManager().getLastState();
        if (gate != null && !gate.groupCallsEnabled) {
            Toast.makeText(this, R.string.feature_disabled_remote, Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(this, CallActivity.class);
        i.putExtra(CallActivity.EXTRA_INCOMING, false);
        i.putExtra(CallActivity.EXTRA_CALL_ID, callId != null && !callId.isEmpty()
                ? callId : java.util.UUID.randomUUID().toString());
        i.putExtra(CallActivity.EXTRA_GROUP_ID, groupId);
        i.putExtra(CallActivity.EXTRA_GROUP_NAME, groupName);
        i.putExtra(CallActivity.EXTRA_CALL_TYPE, callType);
        startActivity(i);
    }

    public void openPeerProfile(com.android.wificalllocal.data.local.entity.PeerEntity peer) {
        if (peer == null) return;
        String details = "Device ID: " + peer.deviceId
                + "\nAddress: " + peer.ipAddress + ":" + peer.port
                + "\nMode: " + peer.connectionMode
                + (peer.isWebUser ? " (Browser Companion)" : " (Android App)")
                + "\nStatus: " + (peer.isOnline ? peer.activeStatus : "OFFLINE")
                + (peer.bio != null && !peer.bio.isEmpty() ? "\nBio: " + peer.bio : "");

        new MaterialAlertDialogBuilder(this)
                .setTitle(peer.name)
                .setMessage(details)
                .setPositiveButton(R.string.btn_voice_call, (d, w) ->
                        startOutgoingCall(peer.deviceId, peer.name, peer.ipAddress, "VOICE"))
                .setNeutralButton(R.string.action_save_contact, (d, w) -> {
                    com.android.wificalllocal.data.local.AppDatabase.DB_EXECUTOR.execute(() -> {
                        locator.getDatabase().savedContactDao().upsert(
                                new com.android.wificalllocal.data.local.entity.SavedContactEntity(
                                        java.util.UUID.randomUUID().toString(),
                                        peer.deviceId,
                                        peer.name,
                                        peer.ipAddress + ":" + peer.port,
                                        peer.bio != null ? peer.bio : "",
                                        System.currentTimeMillis()
                                )
                        );
                    });
                    Toast.makeText(this, "Saved to local contacts", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void handleScannedQr(String scanned) {
        if (scanned == null || scanned.trim().isEmpty()) return;
        String val = scanned.trim();
        if (val.startsWith("http://") || val.startsWith("https://")) {
            Uri uri = Uri.parse(val);
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 8080;
            if (NetworkUtils.isPrivateLanIp(host) && NetworkUtils.isValidPort(port)) {
                locator.getPeerConnectionManager().connectToPeer(host, port, null);
                Toast.makeText(this, getString(R.string.connecting_to_peer, host, port), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.error_invalid_ip, Toast.LENGTH_SHORT).show();
            }
        } else if (val.startsWith("wificalllocal://group/")) {
            openAllGroups();
        } else {
            Toast.makeText(this, val, Toast.LENGTH_SHORT).show();
        }
    }

    private void requestDiscoveryPermissionsIfNeededAndStart() {
        java.util.List<String> needed = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        if (needed.isEmpty()) {
            startMeshService();
        } else {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.perm_rationale_title)
                    .setMessage(getString(R.string.perm_nearby_wifi) + "\n\n" + getString(R.string.perm_notifications))
                    .setPositiveButton(R.string.ok, (d, w) -> discoveryPermLauncher.launch(needed.toArray(new String[0])))
                    .setNegativeButton(R.string.cancel, (d, w) -> startMeshService())
                    .show();
        }
    }

    private void startMeshService() {
        LocalMeshService.start(this);
    }
}
