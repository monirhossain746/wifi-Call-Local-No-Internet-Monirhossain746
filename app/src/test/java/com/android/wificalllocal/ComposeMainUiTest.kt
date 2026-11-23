package com.android.wificalllocal

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.android.wificalllocal.ui.MainScreen
import com.android.wificalllocal.ui.MainTab
import com.android.wificalllocal.ui.ads.AdMobAdConfig
import com.android.wificalllocal.ui.ads.computeAnchoredAdaptiveAdSize
import com.android.wificalllocal.ui.theme.WifiCallLocalTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ComposeMainUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun mainScreenDisplays5TabsAndPositionsAdaptiveBannerAboveBottomNav() {
        var currentTab by mutableStateOf(MainTab.HOME)

        composeRule.setContent {
            WifiCallLocalTheme {
                MainScreen(
                    selectedTab = currentTab,
                    onTabSelected = { currentTab = it },
                    adsEnabled = true,
                    adContent = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp),
                        )
                    },
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Text(text = "Active tab: ${currentTab.name}")
                    }
                }
            }
        }

        // Verify all 5 Bottom Navigation items are displayed
        composeRule.onNodeWithText("Home").assertIsDisplayed()
        composeRule.onNodeWithText("Chats").assertIsDisplayed()
        composeRule.onNodeWithText("Nearby").assertIsDisplayed()
        composeRule.onNodeWithText("Calls").assertIsDisplayed()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()

        // Verify layout order: Content Area -> Adaptive Banner Container -> Bottom Navigation Bar
        val contentBounds = composeRule.onNodeWithTag("main_content_area").getUnclippedBoundsInRoot()
        val adBounds = composeRule.onNodeWithTag("ad_banner_container_box").getUnclippedBoundsInRoot()
        val navBounds = composeRule.onNodeWithTag("bottom_navigation_bar").getUnclippedBoundsInRoot()

        assertTrue(
            "Content area bottom (${contentBounds.bottom}) must not overlap ad top (${adBounds.top})",
            contentBounds.bottom <= adBounds.top + 1.dp,
        )
        assertTrue(
            "Ad banner bottom (${adBounds.bottom}) must sit immediately above bottom nav top (${navBounds.top})",
            adBounds.bottom <= navBounds.top + 1.dp,
        )
        assertEquals(60f, (adBounds.bottom - adBounds.top).value, 1.5f)

        // Verify interactive tab switching across all 5 tabs
        composeRule.onNodeWithTag("nav_tab_chats").performClick()
        composeRule.onNodeWithTag("nav_tab_chats").assertIsSelected()
        assertEquals(MainTab.CHATS, currentTab)

        composeRule.onNodeWithTag("nav_tab_nearby").performClick()
        composeRule.onNodeWithTag("nav_tab_nearby").assertIsSelected()
        assertEquals(MainTab.NEARBY, currentTab)

        composeRule.onNodeWithTag("nav_tab_calls").performClick()
        composeRule.onNodeWithTag("nav_tab_calls").assertIsSelected()
        assertEquals(MainTab.CALLS, currentTab)

        composeRule.onNodeWithTag("nav_tab_settings").performClick()
        composeRule.onNodeWithTag("nav_tab_settings").assertIsSelected()
        assertEquals(MainTab.SETTINGS, currentTab)

        composeRule.onNodeWithTag("nav_tab_home").performClick()
        composeRule.onNodeWithTag("nav_tab_home").assertIsSelected()
        assertEquals(MainTab.HOME, currentTab)
    }

    @Test
    fun adConfigAlwaysUsesLiveProductionIdsAndComputesAdaptiveSizes() {
        val context: Context = ApplicationProvider.getApplicationContext()

        assertEquals(
            "ca-app-pub-3181872815781744/1818458941",
            AdMobAdConfig.resolveBannerAdUnitId(),
        )
        assertEquals(
            "ca-app-pub-3181872815781744/1818458941",
            AdMobAdConfig.PRODUCTION_BANNER_AD_UNIT_ID,
        )
        assertEquals(
            "ca-app-pub-3181872815781744~9894324318",
            AdMobAdConfig.ADMOB_APP_ID,
        )

        val phoneSize = computeAnchoredAdaptiveAdSize(context, 360)
        val tabletSize = computeAnchoredAdaptiveAdSize(context, 720)
        assertEquals(360, phoneSize.width)
        assertEquals(720, tabletSize.width)
        assertTrue(phoneSize.height > 0)
        assertTrue(tabletSize.height >= phoneSize.height)
    }
}
