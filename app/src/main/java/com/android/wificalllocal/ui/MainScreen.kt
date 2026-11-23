package com.android.wificalllocal.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.android.wificalllocal.R
import com.android.wificalllocal.ads.AdConsentManager
import com.android.wificalllocal.ui.ads.AdaptiveBannerAd

enum class MainTab(
    val tabId: Int,
    @param:StringRes val labelRes: Int,
    val testTag: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    HOME(
        tabId = R.id.tab_home,
        labelRes = R.string.nav_home,
        testTag = "nav_tab_home",
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home,
    ),
    CHATS(
        tabId = R.id.tab_chats,
        labelRes = R.string.nav_chats,
        testTag = "nav_tab_chats",
        selectedIcon = Icons.AutoMirrored.Filled.Chat,
        unselectedIcon = Icons.AutoMirrored.Outlined.Chat,
    ),
    NEARBY(
        tabId = R.id.tab_nearby,
        labelRes = R.string.nav_nearby,
        testTag = "nav_tab_nearby",
        selectedIcon = Icons.Filled.WifiTethering,
        unselectedIcon = Icons.Outlined.WifiTethering,
    ),
    CALLS(
        tabId = R.id.tab_calls,
        labelRes = R.string.nav_calls,
        testTag = "nav_tab_calls",
        selectedIcon = Icons.Filled.Call,
        unselectedIcon = Icons.Outlined.Call,
    ),
    SETTINGS(
        tabId = R.id.tab_settings,
        labelRes = R.string.nav_settings,
        testTag = "nav_tab_settings",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
    );

    companion object {
        fun fromTabId(tabId: Int): MainTab = entries.firstOrNull { it.tabId == tabId } ?: HOME
    }
}

/**
 * Root Jetpack Compose shell layout:
 * 1. Content area (`Modifier.weight(1f).fillMaxWidth()`) filling available space above the ad
 * 2. Bottom `Column(verticalArrangement = Arrangement.Bottom)` with:
 *    - Full-width `Box` containing the Adaptive Banner Ad immediately above navigation
 *    - Material 3 `NavigationBar` with 5 items: Home, Chats, Nearby, Calls, Settings
 */
@Composable
fun MainScreen(
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    hideBottomChrome: Boolean = false,
    adsEnabled: Boolean = true,
    adConsentManager: AdConsentManager? = null,
    adContent: @Composable () -> Unit = {
        AdaptiveBannerAd(
            adsEnabled = adsEnabled,
            adConsentManager = adConsentManager,
        )
    },
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Content area: fills available space strictly above the adaptive banner & bottom navigation
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                )
                .then(
                    if (hideBottomChrome) {
                        Modifier.windowInsetsPadding(
                            WindowInsets.navigationBars.union(WindowInsets.ime),
                        )
                    } else {
                        Modifier
                    },
                )
                .testTag("main_content_area"),
        ) {
            content()
        }

        // Bottom container: Column with verticalArrangement = Arrangement.Bottom
        // holding the AdMob Adaptive Banner Box immediately above the 5-item Bottom Navigation Bar
        if (!hideBottomChrome) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.Bottom,
            ) {
                if (adsEnabled) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                            )
                            .testTag("ad_banner_container_box"),
                    ) {
                        adContent()
                    }
                }

                WifiCallBottomNavigationBar(
                    selectedTab = selectedTab,
                    onTabSelected = onTabSelected,
                )
            }
        }
    }
}

@Composable
fun WifiCallBottomNavigationBar(
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier
            .fillMaxWidth()
            .testTag("bottom_navigation_bar"),
        containerColor = MaterialTheme.colorScheme.surface,
        windowInsets = NavigationBarDefaults.windowInsets,
    ) {
        MainTab.entries.forEach { tab ->
            val selected = tab == selectedTab
            val label = stringResource(tab.labelRes)
            NavigationBarItem(
                selected = selected,
                onClick = { onTabSelected(tab) },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag(tab.testTag),
                icon = {
                    Icon(
                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                        contentDescription = label,
                    )
                },
                label = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
