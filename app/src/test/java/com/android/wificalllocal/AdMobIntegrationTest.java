package com.android.wificalllocal;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.android.wificalllocal.ads.AdConsentManager;
import com.android.wificalllocal.control.RemoteConfigManager;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNetworkCapabilities;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end JVM + Robolectric audit of the AdMob Adaptive Banner integration:
 * - Verifies activity_main.xml placement (banner immediately above BottomNavigationView)
 * - Verifies single MobileAds.initialize() call process-wide & zero duplicate AdView creation
 * - Verifies responsive adaptive banner AdSize calculation across screen sizes
 * - Verifies AdView creation and loadAd() execution with Google test banner ID
 * - Verifies all 4 AdListener callbacks: onAdLoaded, onAdOpened, onAdClosed, onAdFailedToLoad
 * - Verifies Firebase Remote Config ads_enabled gating and offline safe fallback (never blocks test ads)
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class AdMobIntegrationTest {

    @Before
    public void resetStaticInitState() {
        AdConsentManager.resetMobileAdsInitForTesting();
    }

    private void enableInternetCapabilityInShadow(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        Network active = cm.getActiveNetwork();
        if (active == null) return;
        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
        if (caps != null) {
            ShadowNetworkCapabilities shadowCaps = Shadows.shadowOf(caps);
            shadowCaps.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
    }

    @Test
    public void activityMainLayoutPlacesAdBannerImmediatelyAboveBottomNav() {
        AppCompatActivity host = Robolectric.buildActivity(AppCompatActivity.class)
                .create().start().resume().get();
        host.setTheme(R.style.Theme_WifiCallLocal);
        View root = host.getLayoutInflater().inflate(R.layout.activity_main, null, false);
        ViewGroup mainRoot = root.findViewById(R.id.main_root);
        View fragmentContainer = root.findViewById(R.id.fragment_container);
        FrameLayout bannerContainer = root.findViewById(R.id.ad_banner_container);
        View bottomNav = root.findViewById(R.id.bottom_navigation);

        assertNotNull(mainRoot);
        assertNotNull(fragmentContainer);
        assertNotNull(bannerContainer);
        assertNotNull(bottomNav);

        int fragIdx = mainRoot.indexOfChild(fragmentContainer);
        int adIdx = mainRoot.indexOfChild(bannerContainer);
        int navIdx = mainRoot.indexOfChild(bottomNav);
        assertTrue(fragIdx >= 0);
        assertEquals("Ad container must be immediately below fragment container", fragIdx + 1, adIdx);
        assertEquals("Ad container must be immediately above BottomNavigationView", adIdx + 1, navIdx);
        assertEquals(View.GONE, bannerContainer.getVisibility());
    }

    @Test
    public void testBannerLoadsOnceComputesAdaptiveSizeAndHandlesAllCallbacks() {
        AppCompatActivity host = Robolectric.buildActivity(AppCompatActivity.class)
                .create().start().resume().visible().get();
        host.setTheme(R.style.Theme_WifiCallLocal);
        enableInternetCapabilityInShadow(host);

        FrameLayout bannerContainer = new FrameLayout(host);
        bannerContainer.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        host.setContentView(bannerContainer);

        // Verify Remote Config offline fallback keeps ads_enabled = true (never blocks test ads)
        RemoteConfigManager rc = new RemoteConfigManager(host);
        assertTrue(rc.getCachedSnapshot().adsEnabled);

        AdConsentManager mgr = new AdConsentManager();
        mgr.setAdUnitIdOverrideForTesting(AdConsentManager.GOOGLE_TEST_BANNER_UNIT_ID);
        mgr.setAdsEnabledByRemoteConfig(true);

        // Simulate rapid onCreate + checkRemoteControlGate + onResume calls
        mgr.initializeAndLoadBanner(host, bannerContainer);
        mgr.initializeAndLoadBanner(host, bannerContainer);
        mgr.initializeAndLoadBanner(host, bannerContainer);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(3200, TimeUnit.MILLISECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        System.out.println("ADMOB_VERIFY initStatus=" + mgr.getInitStatus()
                + " | mobileAdsInitCalls=" + mgr.getMobileAdsInitCallCount()
                + " | loadAdCalls=" + mgr.getLoadAdCallCount()
                + " | bannerStatus=" + mgr.getBannerStatus()
                + " | adSize=" + mgr.getLastComputedAdSize()
                + " | lastErrorCode=" + mgr.getLastErrorCode()
                + " | lastErrorMsg=" + mgr.getLastErrorMessage());

        // Single MobileAds.initialize() call & single AdView child
        assertEquals("MobileAds.initialize() must execute once", 1, mgr.getMobileAdsInitCallCount());
        assertEquals("Must create exactly one AdView child", 1, bannerContainer.getChildCount());
        assertTrue(bannerContainer.getChildAt(0) instanceof AdView);

        // Ensure second AdConsentManager instance does NOT re-call MobileAds.initialize()
        AdConsentManager secondMgr = new AdConsentManager();
        secondMgr.setAdUnitIdOverrideForTesting(AdConsentManager.GOOGLE_TEST_BANNER_UNIT_ID);
        FrameLayout secondContainer = new FrameLayout(host);
        secondMgr.initializeAndLoadBanner(host, secondContainer);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(3200, TimeUnit.MILLISECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Process-wide MobileAds.initialize() guard must prevent duplicate SDK init",
                0, secondMgr.getMobileAdsInitCallCount());

        // Adaptive banner sizing & responsive width check
        AdSize size = mgr.getLastComputedAdSize();
        assertNotNull(size);
        assertTrue("Adaptive AdSize width must be >= 320dp", size.getWidth() >= 320);
        assertTrue("Adaptive AdSize height must be > 0", size.getHeightInPixels(host) > 0);
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, bannerContainer.getLayoutParams().height);

        AdView adView = (AdView) bannerContainer.getChildAt(0);
        assertEquals(AdConsentManager.GOOGLE_TEST_BANNER_UNIT_ID, adView.getAdUnitId());
        assertEquals(1, mgr.getLoadAdCallCount());
        assertEquals(android.view.Gravity.CENTER_HORIZONTAL,
                ((FrameLayout.LayoutParams) adView.getLayoutParams()).gravity & android.view.Gravity.HORIZONTAL_GRAVITY_MASK);

        // Before onAdLoaded(), container stays GONE (not 1px and not a blank strip)
        assertEquals(View.GONE, bannerContainer.getVisibility());

        // 1) onAdLoaded() makes banner visible
        adView.getAdListener().onAdLoaded();
        assertTrue(mgr.isAdLoaded());
        assertEquals(View.VISIBLE, bannerContainer.getVisibility());
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, bannerContainer.getLayoutParams().height);

        // 2) onAdOpened() and 3) onAdClosed()
        adView.getAdListener().onAdOpened();
        assertEquals(1, mgr.getAdOpenedCount());
        adView.getAdListener().onAdClosed();
        assertEquals(1, mgr.getAdClosedCount());

        // Verify suppression hides banner on detail/composer screens without destroying it
        mgr.setSuppressedForScreen(true);
        assertEquals(View.GONE, bannerContainer.getVisibility());
        mgr.setSuppressedForScreen(false);
        assertEquals(View.VISIBLE, bannerContainer.getVisibility());

        // 4) onAdFailedToLoad() captures exact error code + message and collapses container
        LoadAdError simulatedError = new LoadAdError(3, "No fill.", "com.google.android.gms.ads", null, null);
        adView.getAdListener().onAdFailedToLoad(simulatedError);
        assertFalse(mgr.isAdLoaded());
        assertEquals(3, mgr.getLastErrorCode());
        assertEquals("No fill.", mgr.getLastErrorMessage());
        assertEquals(View.GONE, bannerContainer.getVisibility());
        assertTrue(mgr.getBannerStatus().contains("3: No fill."));
    }

    @Test
    public void adaptiveBannerResizesForDifferentScreenWidths() {
        AppCompatActivity host = Robolectric.buildActivity(AppCompatActivity.class)
                .create().start().resume().visible().get();
        AdConsentManager mgr = new AdConsentManager();

        float density = Math.max(1f, host.getResources().getDisplayMetrics().density);
        FrameLayout smallPhoneContainer = new FrameLayout(host);
        smallPhoneContainer.layout(0, 0, Math.round(320 * density), 100);
        AdSize smallSize = mgr.computeAdaptiveBannerSize(host, smallPhoneContainer);

        FrameLayout tabletContainer = new FrameLayout(host);
        tabletContainer.layout(0, 0, Math.round(720 * density), 100);
        AdSize tabletSize = mgr.computeAdaptiveBannerSize(host, tabletContainer);

        FrameLayout landscapeNavContainer = new FrameLayout(host);
        int leftInset = Math.round(48 * density);
        int rightInset = Math.round(48 * density);
        landscapeNavContainer.setPadding(leftInset, 0, rightInset, 0);
        landscapeNavContainer.layout(0, 0, Math.round(800 * density), 100);
        AdSize landscapeSafeSize = mgr.computeAdaptiveBannerSize(host, landscapeNavContainer);

        assertEquals(320, smallSize.getWidth());
        assertEquals(720, tabletSize.getWidth());
        assertEquals(704, landscapeSafeSize.getWidth());
        assertTrue("Tablet adaptive banner should scale width cleanly",
                tabletSize.getWidth() > smallSize.getWidth());
    }

    @Test
    public void remoteConfigAdsDisabledSuppressesAndDestroysBannerCleanly() {
        AppCompatActivity host = Robolectric.buildActivity(AppCompatActivity.class)
                .create().start().resume().visible().get();
        host.setTheme(R.style.Theme_WifiCallLocal);
        enableInternetCapabilityInShadow(host);

        FrameLayout bannerContainer = new FrameLayout(host);
        host.setContentView(bannerContainer);

        AdConsentManager mgr = new AdConsentManager();
        mgr.setAdUnitIdOverrideForTesting(AdConsentManager.GOOGLE_TEST_BANNER_UNIT_ID);
        mgr.initializeAndLoadBanner(host, bannerContainer);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(3200, TimeUnit.MILLISECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, bannerContainer.getChildCount());

        mgr.setAdsEnabledByRemoteConfig(false);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(View.GONE, bannerContainer.getVisibility());
        assertEquals(0, bannerContainer.getChildCount());
        assertNull(mgr.getCurrentAdView());
        assertEquals("Disabled via Remote Config", mgr.getInitStatus());
    }

    @Test
    public void staleUnverifiedAdsCacheDoesNotDisableAds() {
        Context ctx = RuntimeEnvironment.getApplication();
        ctx.getSharedPreferences("wcl_remote_config_cache", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .putBoolean("cached_ads_enabled", false)
                .commit();

        RemoteConfigManager rc = new RemoteConfigManager(ctx);
        assertTrue("Stale unverified cache must not disable ads", rc.getCachedSnapshot().adsEnabled);
    }

    @Test
    public void manifestHasProductionAdMobAppId() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(
                ctx.getPackageName(), PackageManager.GET_META_DATA);
        assertNotNull(ai.metaData);
        assertEquals("ca-app-pub-3181872815781744~9879947642",
                ai.metaData.getString("com.google.android.gms.ads.APPLICATION_ID"));
        assertEquals("ca-app-pub-3181872815781744~9879947642",
                ctx.getString(R.string.admob_app_id));
        assertEquals("ca-app-pub-3181872815781744/1818458941",
                ctx.getString(R.string.admob_adaptive_banner_unit_id));
    }
}
