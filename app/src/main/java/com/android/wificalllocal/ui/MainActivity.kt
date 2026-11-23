package com.android.wificalllocal.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import com.android.wificalllocal.BuildConfig
import com.android.wificalllocal.R
import com.android.wificalllocal.ServiceLocator
import com.android.wificalllocal.control.AppControlManager
import com.android.wificalllocal.data.local.AppDatabase
import com.android.wificalllocal.data.local.entity.PeerEntity
import com.android.wificalllocal.data.local.entity.SavedContactEntity
import com.android.wificalllocal.network.NetworkUtils
import com.android.wificalllocal.service.LocalMeshService
import com.android.wificalllocal.ui.call.CallActivity
import com.android.wificalllocal.ui.fragment.AboutDeveloperFragment
import com.android.wificalllocal.ui.fragment.AllGroupsFragment
import com.android.wificalllocal.ui.fragment.CallsFragment
import com.android.wificalllocal.ui.fragment.ChatDetailFragment
import com.android.wificalllocal.ui.fragment.ChatsFragment
import com.android.wificalllocal.ui.fragment.GroupInfoFragment
import com.android.wificalllocal.ui.fragment.HomeFragment
import com.android.wificalllocal.ui.fragment.NearbyFragment
import com.android.wificalllocal.ui.fragment.ProfileSetupFragment
import com.android.wificalllocal.ui.fragment.QrShowFragment
import com.android.wificalllocal.ui.fragment.SettingsFragment
import com.android.wificalllocal.ui.qr.QrScannerActivity
import com.android.wificalllocal.ui.theme.WifiCallLocalTheme
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.ArrayDeque
import java.util.Deque
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var locator: ServiceLocator
    private lateinit var fragmentContainer: FragmentContainerView

    private var currentTabId: Int = R.id.tab_home
    private val tabStacks: MutableMap<Int, Deque<String>> = HashMap()
    private var lastBackPressMs: Long = 0L

    private var selectedTabState by mutableStateOf(MainTab.HOME)
    private var hideBottomChromeState by mutableStateOf(false)
    private var adsEnabledState by mutableStateOf(true)
    private var initialContentAttached = false

    private var maintenanceNoticeShown = false
    private var optionalUpdatePromptShown = false

    private val qrScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val scanned = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_RESULT)
            handleScannedQr(scanned)
        }
    }

    private val discoveryPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        startMeshService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        locator = ServiceLocator.getInstance(this)

        // Immediate cached gate check before starting mesh or showing chats
        val cachedGate = locator.appControlManager.currentGateState
        if (cachedGate.isBlocked) {
            locator.appControlManager.enforceGateIfBlocked(this, cachedGate)
            return
        }

        initTabStacks()
        setupBackDispatcher()

        adsEnabledState = cachedGate.adsEnabled
        locator.adConsentManager.setAdsEnabledByRemoteConfig(cachedGate.adsEnabled)
        val profileDone = locator.preferencesManager.isProfileSetupDone
        hideBottomChromeState = !profileDone
        locator.adConsentManager.setSuppressedForScreen(!profileDone)

        fragmentContainer = FragmentContainerView(this).apply {
            id = R.id.fragment_container
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    if (!initialContentAttached) {
                        initialContentAttached = true
                        onFragmentContainerAttached()
                    }
                }

                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        }

        setContent {
            WifiCallLocalTheme {
                val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                MainScreen(
                    selectedTab = selectedTabState,
                    onTabSelected = { tab ->
                        if (tab.tabId != currentTabId) {
                            showTab(tab.tabId)
                        }
                    },
                    hideBottomChrome = hideBottomChromeState || imeVisible,
                    adsEnabled = adsEnabledState,
                    adConsentManager = locator.adConsentManager,
                ) {
                    AndroidView(
                        factory = { fragmentContainer },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        checkRemoteControlGate()
    }

    private fun onFragmentContainerAttached() {
        if (!locator.preferencesManager.isProfileSetupDone) {
            showOnboarding()
        } else {
            showTab(R.id.tab_home)
            requestDiscoveryPermissionsIfNeededAndStart()
        }
        handleNotificationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::locator.isInitialized) {
            checkRemoteControlGate()
        }
    }

    private fun checkRemoteControlGate() {
        if (!::locator.isInitialized) return
        locator.appControlManager.checkRemoteGate { state ->
            if (isFinishing || isDestroyed) return@checkRemoteGate
            if (state.isBlocked) {
                locator.appControlManager.enforceGateIfBlocked(this, state)
                return@checkRemoteGate
            }
            adsEnabledState = state.adsEnabled
            locator.adConsentManager.setAdsEnabledByRemoteConfig(state.adsEnabled)

            if (state.maintenanceMode && !maintenanceNoticeShown) {
                maintenanceNoticeShown = true
                val msg = state.maintenanceMessage.ifEmpty {
                    "Remote services are undergoing maintenance. Local WiFi chat, calling, and file sharing remain active."
                }
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.maintenance_mode_title)
                    .setMessage(msg)
                    .setPositiveButton(R.string.ok, null)
                    .show()
            } else if (state.isOptionalUpdateAvailable(BuildConfig.VERSION_CODE) && !optionalUpdatePromptShown) {
                optionalUpdatePromptShown = true
                val msg = state.updateMessage.ifEmpty {
                    "Version v${state.latestVersionName} (Build ${state.latestVersionCode}) is available."
                }
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.optional_update_title)
                    .setMessage(msg)
                    .setPositiveButton(R.string.btn_update_now) { _, _ ->
                        val url = if (com.android.wificalllocal.control.UpdateManager.isValidHttpsUpdateUrl(state.updateLink)) {
                            state.updateLink
                        } else {
                            SettingsFragment.GITHUB_REPO_URL
                        }
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (_: Exception) {
                            Toast.makeText(this, R.string.error_no_browser_github, Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent == null) return
        val chatId = intent.getStringExtra("open_chat_id")
        if (!chatId.isNullOrEmpty() && locator.preferencesManager.isProfileSetupDone) {
            openChatDetail(chatId, chatId, null)
        }
    }

    private fun initTabStacks() {
        tabStacks[R.id.tab_home] = ArrayDeque()
        tabStacks[R.id.tab_chats] = ArrayDeque()
        tabStacks[R.id.tab_nearby] = ArrayDeque()
        tabStacks[R.id.tab_calls] = ArrayDeque()
        tabStacks[R.id.tab_settings] = ArrayDeque()
    }

    private fun setupBackDispatcher() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!locator.preferencesManager.isProfileSetupDone) {
                        finish()
                        return
                    }
                    val stack = tabStacks[currentTabId]
                    if (stack != null && stack.size > 1) {
                        popCurrentTab()
                        return
                    }
                    if (currentTabId != R.id.tab_home) {
                        showTab(R.id.tab_home)
                        return
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastBackPressMs < 2000) {
                        finish()
                    } else {
                        lastBackPressMs = now
                        Toast.makeText(
                            this@MainActivity,
                            R.string.press_back_again_to_exit,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
        )
    }

    fun showOnboarding() {
        hideBottomChromeState = true
        locator.adConsentManager.setSuppressedForScreen(true)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, ProfileSetupFragment.newInstance(true), "onboarding")
            .commit()
    }

    fun onProfileSetupCompleted() {
        val onboarding = supportFragmentManager.findFragmentByTag("onboarding")
        if (onboarding != null) {
            supportFragmentManager.beginTransaction().remove(onboarding).commitNow()
        }
        hideBottomChromeState = false
        locator.adConsentManager.setSuppressedForScreen(false)
        showTab(R.id.tab_home)
        requestDiscoveryPermissionsIfNeededAndStart()
    }

    fun showTab(tabId: Int) {
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()

        val oldStack = tabStacks[currentTabId]
        if (!oldStack.isNullOrEmpty()) {
            val currentFrag = fm.findFragmentByTag(oldStack.peek())
            if (currentFrag != null) tx.hide(currentFrag)
        }

        currentTabId = tabId
        selectedTabState = MainTab.fromTabId(tabId)
        val newStack = tabStacks.getOrPut(tabId) { ArrayDeque() }

        if (newStack.isEmpty()) {
            val rootTag = "tab_root_$tabId"
            val root = createTabRootFragment(tabId)
            newStack.push(rootTag)
            tx.add(R.id.fragment_container, root, rootTag)
        } else {
            val top = fm.findFragmentByTag(newStack.peek())
            if (top != null) tx.show(top)
        }
        tx.commit()
        syncChromeForStackDepth()
    }

    fun pushFragmentToCurrentTab(fragment: Fragment, tagPrefix: String) {
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        val stack = tabStacks.getOrPut(currentTabId) { ArrayDeque() }
        if (stack.isNotEmpty()) {
            val current = fm.findFragmentByTag(stack.peek())
            if (current != null) tx.hide(current)
        }
        val tag = "${tagPrefix}_${currentTabId}_${System.currentTimeMillis()}"
        stack.push(tag)
        tx.add(R.id.fragment_container, fragment, tag)
        tx.commit()
        syncChromeForStackDepth()
    }

    fun popCurrentTab() {
        val fm = supportFragmentManager
        val stack = tabStacks[currentTabId]
        if (stack == null || stack.size <= 1) return
        val topTag = stack.pop()
        val top = fm.findFragmentByTag(topTag)
        val prev = fm.findFragmentByTag(stack.peek())
        val tx = fm.beginTransaction()
        if (top != null) tx.remove(top)
        if (prev != null) tx.show(prev)
        tx.commit()
        syncChromeForStackDepth()
    }

    private fun isInDetailScreen(): Boolean {
        val stack = tabStacks[currentTabId]
        return stack != null && stack.size > 1
    }

    private fun syncChromeForStackDepth() {
        val onboarding = !locator.preferencesManager.isProfileSetupDone
        val suppress = isInDetailScreen() || onboarding
        hideBottomChromeState = suppress
        locator.adConsentManager.setSuppressedForScreen(suppress)
    }

    private fun createTabRootFragment(tabId: Int): Fragment = when (tabId) {
        R.id.tab_chats -> ChatsFragment()
        R.id.tab_nearby -> NearbyFragment()
        R.id.tab_calls -> CallsFragment()
        R.id.tab_settings -> SettingsFragment()
        else -> HomeFragment()
    }

    fun openChatDetail(chatId: String?, title: String?, peerId: String?) {
        pushFragmentToCurrentTab(ChatDetailFragment.newInstance(chatId, title, peerId), "chat")
    }

    fun openGroupInfo(groupId: String?) {
        pushFragmentToCurrentTab(GroupInfoFragment.newInstance(groupId), "group_info")
    }

    fun openAllGroups() {
        pushFragmentToCurrentTab(AllGroupsFragment(), "all_groups")
    }

    fun openQrShow() {
        pushFragmentToCurrentTab(QrShowFragment(), "qr_show")
    }

    fun openAboutDeveloper() {
        pushFragmentToCurrentTab(AboutDeveloperFragment(), "about")
    }

    fun openProfileEdit() {
        pushFragmentToCurrentTab(ProfileSetupFragment.newInstance(false), "profile_edit")
    }

    fun launchQrScanner() {
        qrScanLauncher.launch(Intent(this, QrScannerActivity::class.java))
    }

    fun startOutgoingCall(peerId: String?, peerName: String?, peerIp: String?, callType: String?) {
        val i = Intent(this, CallActivity::class.java).apply {
            putExtra(CallActivity.EXTRA_INCOMING, false)
            putExtra(CallActivity.EXTRA_PEER_ID, peerId)
            putExtra(CallActivity.EXTRA_PEER_NAME, peerName)
            putExtra(CallActivity.EXTRA_PEER_IP, peerIp)
            putExtra(CallActivity.EXTRA_CALL_TYPE, callType)
        }
        startActivity(i)
    }

    fun startGroupCall(callId: String?, groupId: String?, groupName: String?, callType: String?) {
        val gate: AppControlManager.AppGateState? = locator.appControlManager.lastState
        if (gate != null && !gate.groupCallsEnabled) {
            Toast.makeText(this, R.string.feature_disabled_remote, Toast.LENGTH_LONG).show()
            return
        }
        val resolvedCallId = if (!callId.isNullOrEmpty()) callId else UUID.randomUUID().toString()
        val i = Intent(this, CallActivity::class.java).apply {
            putExtra(CallActivity.EXTRA_INCOMING, false)
            putExtra(CallActivity.EXTRA_CALL_ID, resolvedCallId)
            putExtra(CallActivity.EXTRA_GROUP_ID, groupId)
            putExtra(CallActivity.EXTRA_GROUP_NAME, groupName)
            putExtra(CallActivity.EXTRA_CALL_TYPE, callType)
        }
        startActivity(i)
    }

    fun openPeerProfile(peer: PeerEntity?) {
        if (peer == null) return
        val details = buildString {
            append("Device ID: ").append(peer.deviceId)
            append("\nAddress: ").append(peer.ipAddress).append(':').append(peer.port)
            append("\nMode: ").append(peer.connectionMode)
            append(if (peer.isWebUser) " (Browser Companion)" else " (Android App)")
            append("\nStatus: ").append(if (peer.isOnline) peer.activeStatus else "OFFLINE")
            if (!peer.bio.isNullOrEmpty()) {
                append("\nBio: ").append(peer.bio)
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(peer.name)
            .setMessage(details)
            .setPositiveButton(R.string.btn_voice_call) { _, _ ->
                startOutgoingCall(peer.deviceId, peer.name, peer.ipAddress, "VOICE")
            }
            .setNeutralButton(R.string.action_save_contact) { _, _ ->
                AppDatabase.DB_EXECUTOR.execute {
                    locator.database.savedContactDao().upsert(
                        SavedContactEntity(
                            UUID.randomUUID().toString(),
                            peer.deviceId,
                            peer.name,
                            "${peer.ipAddress}:${peer.port}",
                            peer.bio ?: "",
                            System.currentTimeMillis(),
                        ),
                    )
                }
                Toast.makeText(this, "Saved to local contacts", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun handleScannedQr(scanned: String?) {
        val valStr = scanned?.trim() ?: ""
        if (valStr.isEmpty()) return
        when {
            valStr.startsWith("http://") || valStr.startsWith("https://") -> {
                val uri = Uri.parse(valStr)
                val host = uri.host
                val port = if (uri.port > 0) uri.port else 8080
                if (NetworkUtils.isPrivateLanIp(host) && NetworkUtils.isValidPort(port)) {
                    locator.peerConnectionManager.connectToPeer(host, port, null)
                    Toast.makeText(
                        this,
                        getString(R.string.connecting_to_peer, host, port),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    Toast.makeText(this, R.string.error_invalid_ip, Toast.LENGTH_SHORT).show()
                }
            }

            valStr.startsWith("wificalllocal://group/") -> openAllGroups()
            else -> Toast.makeText(this, valStr, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestDiscoveryPermissionsIfNeededAndStart() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (needed.isEmpty()) {
            startMeshService()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.perm_rationale_title)
                .setMessage(
                    getString(R.string.perm_nearby_wifi) + "\n\n" + getString(R.string.perm_notifications),
                )
                .setPositiveButton(R.string.ok) { _, _ ->
                    discoveryPermLauncher.launch(needed.toTypedArray())
                }
                .setNegativeButton(R.string.cancel) { _, _ -> startMeshService() }
                .show()
        }
    }

    private fun startMeshService() {
        LocalMeshService.start(this)
    }
}
