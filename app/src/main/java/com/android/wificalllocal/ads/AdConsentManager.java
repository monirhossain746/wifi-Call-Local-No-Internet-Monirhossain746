package com.android.wificalllocal.ads;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowMetrics;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.wificalllocal.R;
import com.android.wificalllocal.network.NetworkUtils;
import com.google.ads.mediation.admob.AdMobAdapter;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.RequestConfiguration;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages Google UMP consent flow, Remote Config ads_enabled gating, responsive Adaptive Banner
 * sizing, Activity lifecycle synchronization, and complete AdListener telemetry/error logging.
 */
public class AdConsentManager {

    private static final String TAG = "AdMobBanner";
    public static final String GOOGLE_TEST_BANNER_UNIT_ID = "ca-app-pub-3940256099942544/9214589741";
    private static final long RETRY_COOLDOWN_MS = 15_000L;

    private static final AtomicBoolean PROCESS_MOBILE_ADS_INIT_STARTED = new AtomicBoolean(false);
    private static volatile boolean processMobileAdsInitialized = false;

    private final AtomicBoolean consentFlowInFlight = new AtomicBoolean(false);
    private final AtomicInteger mobileAdsInitCallCount = new AtomicInteger(0);
    private final AtomicInteger loadAdCallCount = new AtomicInteger(0);
    private final AtomicInteger adOpenedCount = new AtomicInteger(0);
    private final AtomicInteger adClosedCount = new AtomicInteger(0);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ConsentInformation consentInformation;
    private FrameLayout currentContainer;
    private View.OnLayoutChangeListener containerLayoutListener;
    private AdView currentAdView;
    private String currentAdUnitId;
    private String adUnitIdOverrideForTesting;
    private AdSize lastComputedAdSize;
    private int lastAdWidthDp = -1;
    private long lastFailedTimestampMs = 0L;
    private Runnable activeUmpTimeout;

    private volatile boolean adLoading = false;
    private volatile boolean adLoaded = false;
    private volatile boolean suppressedForScreen = false;
    private volatile boolean remoteAdsEnabled = true;

    private volatile String initStatus = "Not Initialized";
    private volatile String bannerStatus = "Idle";
    private volatile String availabilityStatus = "Pending";
    private volatile int lastErrorCode = -1;
    private volatile String lastErrorMessage = "";

    @VisibleForTesting
    public static void resetMobileAdsInitForTesting() {
        PROCESS_MOBILE_ADS_INIT_STARTED.set(false);
        processMobileAdsInitialized = false;
    }

    @VisibleForTesting
    public void setAdUnitIdOverrideForTesting(@Nullable String adUnitId) {
        this.adUnitIdOverrideForTesting = adUnitId;
    }

    public void setAdsEnabledByRemoteConfig(boolean enabled) {
        this.remoteAdsEnabled = enabled;
        if (!enabled) {
            Log.i(TAG, "Ads disabled via Firebase Remote Config (ads_enabled=false)");
            initStatus = "Disabled via Remote Config";
            bannerStatus = "Skipped (ads_enabled = false)";
            availabilityStatus = "Disabled intentionally via Remote Config";
            adLoading = false;
            adLoaded = false;
            mainHandler.post(this::destroyBanner);
        }
    }

    public void initializeAndLoadBanner(Activity activity, FrameLayout bannerContainer) {
        if (activity == null || bannerContainer == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        attachContainer(activity, bannerContainer);

        if (!remoteAdsEnabled) {
            setAdsEnabledByRemoteConfig(false);
            return;
        }

        String resolvedUnitId = resolveAdUnitId(activity);
        if (currentAdView != null
                && currentAdView.getParent() == bannerContainer
                && resolvedUnitId.equals(currentAdUnitId)
                && (adLoading || adLoaded)) {
            applyBannerVisibility();
            return;
        }

        long now = System.currentTimeMillis();
        if (lastFailedTimestampMs > 0 && (now - lastFailedTimestampMs) < RETRY_COOLDOWN_MS) {
            return;
        }

        if (!NetworkUtils.hasNetworkConnectivity(activity)) {
            Log.d(TAG, "Banner load deferred: no active network interface");
            adLoading = false;
            adLoaded = false;
            initStatus = "Deferred (No Network Interface)";
            bannerStatus = "Offline (Hidden)";
            availabilityStatus = "Unavailable offline (app works normally)";
            bannerContainer.setVisibility(View.GONE);
            return;
        }

        if (!consentFlowInFlight.compareAndSet(false, true)) {
            return;
        }

        initStatus = processMobileAdsInitialized ? "Initialized (AdMob SDK active)" : "Checking Consent (UMP)";
        bannerStatus = "Requesting consent…";
        availabilityStatus = "Initializing";

        final AtomicBoolean loadTriggered = new AtomicBoolean(false);
        final Runnable umpTimeoutFallback = () -> {
            activeUmpTimeout = null;
            consentFlowInFlight.set(false);
            if (activity.isFinishing() || activity.isDestroyed() || !remoteAdsEnabled) {
                return;
            }
            boolean canRequest = consentInformation != null && consentInformation.canRequestAds();
            Log.w(TAG, "UMP consent check timed out after 3s; proceeding with load (canRequestAds=" + canRequest + ")");
            if (loadTriggered.compareAndSet(false, true)) {
                initMobileAdsAndLoad(activity, bannerContainer, resolvedUnitId, !canRequest);
            }
        };
        activeUmpTimeout = umpTimeoutFallback;
        mainHandler.postDelayed(umpTimeoutFallback, 3000L);

        try {
            ConsentRequestParameters params = new ConsentRequestParameters.Builder().build();
            consentInformation = UserMessagingPlatform.getConsentInformation(activity);
            consentInformation.requestConsentInfoUpdate(
                    activity,
                    params,
                    () -> {
                        mainHandler.removeCallbacks(umpTimeoutFallback);
                        activeUmpTimeout = null;
                        if (activity.isFinishing() || activity.isDestroyed()) {
                            consentFlowInFlight.set(false);
                            return;
                        }
                        UserMessagingPlatform.loadAndShowConsentFormIfRequired(
                                activity,
                                loadAndShowError -> {
                                    consentFlowInFlight.set(false);
                                    if (loadAndShowError != null) {
                                        Log.w(TAG, "UMP consent form warning [" + loadAndShowError.getErrorCode()
                                                + "]: " + loadAndShowError.getMessage());
                                    }
                                    if (activity.isFinishing() || activity.isDestroyed() || !remoteAdsEnabled) {
                                        return;
                                    }
                                    boolean canRequest = consentInformation != null && consentInformation.canRequestAds();
                                    boolean useNpa = !canRequest;
                                    if (loadTriggered.compareAndSet(false, true)) {
                                        initMobileAdsAndLoad(activity, bannerContainer, resolvedUnitId, useNpa);
                                    }
                                }
                        );
                    },
                    requestConsentError -> {
                        mainHandler.removeCallbacks(umpTimeoutFallback);
                        activeUmpTimeout = null;
                        consentFlowInFlight.set(false);
                        Log.w(TAG, "UMP consent info update error [" + requestConsentError.getErrorCode()
                                + "]: " + requestConsentError.getMessage());
                        if (activity.isFinishing() || activity.isDestroyed() || !remoteAdsEnabled) {
                            return;
                        }
                        boolean canRequest = consentInformation != null && consentInformation.canRequestAds();
                        boolean useNpa = !canRequest;
                        if (loadTriggered.compareAndSet(false, true)) {
                            initMobileAdsAndLoad(activity, bannerContainer, resolvedUnitId, useNpa);
                        }
                    }
            );

            if (consentInformation != null && consentInformation.canRequestAds()
                    && loadTriggered.compareAndSet(false, true)) {
                mainHandler.removeCallbacks(umpTimeoutFallback);
                activeUmpTimeout = null;
                consentFlowInFlight.set(false);
                initMobileAdsAndLoad(activity, bannerContainer, resolvedUnitId, false);
            }
        } catch (Throwable t) {
            mainHandler.removeCallbacks(umpTimeoutFallback);
            consentFlowInFlight.set(false);
            Log.w(TAG, "UMP exception; falling back to non-personalized ad request", t);
            if (loadTriggered.compareAndSet(false, true)) {
                initMobileAdsAndLoad(activity, bannerContainer, resolvedUnitId, true);
            }
        }
    }

    private void attachContainer(Activity activity, FrameLayout bannerContainer) {
        if (this.currentContainer != null && this.containerLayoutListener != null && this.currentContainer != bannerContainer) {
            this.currentContainer.removeOnLayoutChangeListener(this.containerLayoutListener);
        }
        this.currentContainer = bannerContainer;
        if (this.containerLayoutListener == null) {
            this.containerLayoutListener = (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                int newWidthPx = (right - left) - bannerContainer.getPaddingLeft() - bannerContainer.getPaddingRight();
                int oldWidthPx = oldRight - oldLeft;
                if (newWidthPx <= 0 || (right - left) == oldWidthPx || lastAdWidthDp <= 0 || currentAdView == null) {
                    return;
                }
                float density = Math.max(1f, activity.getResources().getDisplayMetrics().density);
                int newWidthDp = Math.max(320, Math.round(newWidthPx / density));
                if (Math.abs(newWidthDp - lastAdWidthDp) >= 24 && remoteAdsEnabled && !activity.isFinishing() && !activity.isDestroyed()) {
                    Log.d(TAG, "Container width changed from " + lastAdWidthDp + "dp to " + newWidthDp + "dp; reloading adaptive banner");
                    String unitId = resolveAdUnitId(activity);
                    boolean useNpa = consentInformation != null && !consentInformation.canRequestAds();
                    adLoading = false;
                    adLoaded = false;
                    initMobileAdsAndLoad(activity, bannerContainer, unitId, useNpa);
                }
            };
        }
        bannerContainer.removeOnLayoutChangeListener(this.containerLayoutListener);
        bannerContainer.addOnLayoutChangeListener(this.containerLayoutListener);
    }

    public void setSuppressedForScreen(boolean suppressed) {
        this.suppressedForScreen = suppressed;
        applyBannerVisibility();
    }

    private void applyBannerVisibility() {
        if (currentContainer == null) return;
        setContainerWrapContent(currentContainer);
        if (suppressedForScreen || !remoteAdsEnabled) {
            currentContainer.setVisibility(View.GONE);
            return;
        }
        if (adLoaded) {
            currentContainer.setVisibility(View.VISIBLE);
            currentContainer.requestLayout();
        } else {
            // Never shrink container to 1px or show a blank box before onAdLoaded()
            currentContainer.setVisibility(View.GONE);
        }
    }

    private void setContainerWrapContent(FrameLayout container) {
        ViewGroup.LayoutParams lp = container.getLayoutParams();
        if (lp != null && lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            container.setLayoutParams(lp);
        }
    }

    public void pauseBanner() {
        if (currentAdView != null) {
            try {
                currentAdView.pause();
            } catch (Throwable ignored) {
            }
        }
    }

    public void resumeBanner() {
        if (currentAdView != null) {
            try {
                currentAdView.resume();
            } catch (Throwable ignored) {
            }
        }
    }

    public void destroyBanner() {
        adLoading = false;
        adLoaded = false;
        consentFlowInFlight.set(false);
        if (activeUmpTimeout != null) {
            mainHandler.removeCallbacks(activeUmpTimeout);
            activeUmpTimeout = null;
        }
        currentAdUnitId = null;
        lastAdWidthDp = -1;
        if (currentAdView != null) {
            try {
                currentAdView.destroy();
            } catch (Throwable ignored) {
            }
            currentAdView = null;
        }
        if (currentContainer != null) {
            if (containerLayoutListener != null) {
                currentContainer.removeOnLayoutChangeListener(containerLayoutListener);
            }
            currentContainer.removeAllViews();
            currentContainer.setVisibility(View.GONE);
        }
    }

    public String getInitStatus() { return initStatus; }
    public String getBannerStatus() { return bannerStatus; }
    public String getAvailabilityStatus() { return availabilityStatus; }
    public int getLastErrorCode() { return lastErrorCode; }
    public String getLastErrorMessage() { return lastErrorMessage; }
    public int getMobileAdsInitCallCount() { return mobileAdsInitCallCount.get(); }
    public int getLoadAdCallCount() { return loadAdCallCount.get(); }
    public int getAdOpenedCount() { return adOpenedCount.get(); }
    public int getAdClosedCount() { return adClosedCount.get(); }
    public AdSize getLastComputedAdSize() { return lastComputedAdSize; }
    public AdView getCurrentAdView() { return currentAdView; }
    public boolean isAdLoaded() { return adLoaded; }
    public boolean isMobileAdsInitialized() { return processMobileAdsInitialized; }

    public String resolveAdUnitId(Activity activity) {
        if (adUnitIdOverrideForTesting != null && !adUnitIdOverrideForTesting.trim().isEmpty()) {
            return adUnitIdOverrideForTesting.trim();
        }
        return activity.getString(R.string.admob_adaptive_banner_unit_id);
    }

    private void initMobileAdsAndLoad(Activity activity, FrameLayout bannerContainer,
                                      String adUnitId, boolean nonPersonalizedOnly) {
        if (!remoteAdsEnabled || activity.isFinishing() || activity.isDestroyed()) return;

        if (PROCESS_MOBILE_ADS_INIT_STARTED.compareAndSet(false, true)) {
            initStatus = "Initializing MobileAds…";
            mobileAdsInitCallCount.incrementAndGet();
            Log.d(TAG, "Calling MobileAds.initialize() (once per process)");
            try {
                RequestConfiguration config = new RequestConfiguration.Builder()
                        .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_G)
                        .setTagForChildDirectedTreatment(RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_UNSPECIFIED)
                        .build();
                MobileAds.setRequestConfiguration(config);
                MobileAds.initialize(activity.getApplicationContext(), initializationStatus -> {
                    processMobileAdsInitialized = true;
                    initStatus = "Initialized (AdMob SDK active)";
                    Log.d(TAG, "MobileAds.initialize() complete: " + initializationStatus.getAdapterStatusMap());
                });
            } catch (Throwable t) {
                initStatus = "SDK init error: " + t.getMessage();
                Log.e(TAG, "MobileAds.initialize() failed", t);
            }
        } else if (processMobileAdsInitialized) {
            initStatus = "Initialized (AdMob SDK active)";
        }

        mainHandler.post(() -> {
            if (activity.isFinishing() || activity.isDestroyed() || !remoteAdsEnabled) return;
            if (currentAdView != null && currentAdView.getParent() == bannerContainer
                    && adUnitId.equals(currentAdUnitId) && (adLoading || adLoaded)) {
                applyBannerVisibility();
                return;
            }

            AdSize adaptiveSize = computeAdaptiveBannerSize(activity, bannerContainer);
            lastComputedAdSize = adaptiveSize;
            lastAdWidthDp = adaptiveSize.getWidth();

            if (currentAdView != null) {
                try {
                    currentAdView.destroy();
                } catch (Throwable ignored) {
                }
            }
            bannerContainer.removeAllViews();
            setContainerWrapContent(bannerContainer);

            adLoading = true;
            adLoaded = false;
            lastErrorCode = -1;
            lastErrorMessage = "";
            lastFailedTimestampMs = 0L;
            currentAdUnitId = adUnitId;
            bannerStatus = "Loading Adaptive Banner (" + adUnitId + ")…";
            availabilityStatus = "Requesting ad";

            AdView adView = new AdView(activity);
            adView.setAdUnitId(adUnitId);
            adView.setAdSize(adaptiveSize);
            adView.setAdListener(new AdListener() {
                @Override
                public void onAdLoaded() {
                    adLoading = false;
                    adLoaded = true;
                    lastErrorCode = -1;
                    lastErrorMessage = "";
                    lastFailedTimestampMs = 0L;
                    bannerStatus = "Loaded (" + adView.getAdUnitId() + ")";
                    availabilityStatus = suppressedForScreen ? "Loaded (Hidden on subscreen)" : "Visible";
                    Log.d(TAG, "onAdLoaded: unitId=" + adView.getAdUnitId()
                            + ", size=" + adaptiveSize.getWidth() + "x" + adaptiveSize.getHeight() + "dp");
                    applyBannerVisibility();
                }

                @Override
                public void onAdFailedToLoad(@NonNull LoadAdError error) {
                    adLoading = false;
                    adLoaded = false;
                    lastErrorCode = error.getCode();
                    lastErrorMessage = error.getMessage();
                    lastFailedTimestampMs = System.currentTimeMillis();
                    bannerStatus = "Failed (" + error.getCode() + ": " + error.getMessage() + ")";
                    availabilityStatus = "Failed (" + error.getCode() + ")";
                    Log.e(TAG, "onAdFailedToLoad: code=" + error.getCode()
                            + ", message=" + error.getMessage()
                            + ", domain=" + error.getDomain()
                            + ", unitId=" + adView.getAdUnitId());
                    applyBannerVisibility();
                }

                @Override
                public void onAdOpened() {
                    adOpenedCount.incrementAndGet();
                    bannerStatus = "Opened (" + adView.getAdUnitId() + ")";
                    Log.d(TAG, "onAdOpened: unitId=" + adView.getAdUnitId());
                }

                @Override
                public void onAdClosed() {
                    adClosedCount.incrementAndGet();
                    bannerStatus = adLoaded ? "Loaded (" + adView.getAdUnitId() + ")" : "Closed";
                    Log.d(TAG, "onAdClosed: unitId=" + adView.getAdUnitId());
                }
            });

            currentAdView = adView;
            FrameLayout.LayoutParams adParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL);
            bannerContainer.addView(adView, adParams);
            applyBannerVisibility();

            AdRequest.Builder reqBuilder = new AdRequest.Builder();
            if (nonPersonalizedOnly) {
                Bundle extras = new Bundle();
                extras.putString("npa", "1");
                reqBuilder.addNetworkExtrasBundle(AdMobAdapter.class, extras);
            }
            loadAdCallCount.incrementAndGet();
            Log.d(TAG, "Ad request started -> unitId=" + adUnitId
                    + ", adSize=" + adaptiveSize.getWidth() + "x" + adaptiveSize.getHeight() + "dp"
                    + ", nonPersonalized=" + nonPersonalizedOnly);
            try {
                adView.loadAd(reqBuilder.build());
            } catch (Throwable t) {
                adLoading = false;
                adLoaded = false;
                lastErrorCode = -999;
                lastErrorMessage = t.getMessage() != null ? t.getMessage() : "loadAd threw exception";
                lastFailedTimestampMs = System.currentTimeMillis();
                bannerStatus = "Failed (-999: " + lastErrorMessage + ")";
                availabilityStatus = "Load exception";
                Log.e(TAG, "AdView.loadAd() threw exception", t);
                applyBannerVisibility();
            }
        });
    }

    @VisibleForTesting
    public AdSize computeAdaptiveBannerSize(Activity activity, FrameLayout container) {
        float density = Math.max(1f, activity.getResources().getDisplayMetrics().density);
        int widthPx = container != null
                ? Math.max(0, container.getWidth() - container.getPaddingLeft() - container.getPaddingRight())
                : 0;
        if (widthPx <= 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowMetrics metrics = activity.getWindowManager().getCurrentWindowMetrics();
                android.graphics.Insets sysInsets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                        WindowInsets.Type.navigationBars() | WindowInsets.Type.displayCutout());
                widthPx = Math.max(0, metrics.getBounds().width() - sysInsets.left - sysInsets.right);
            } else {
                DisplayMetrics out = new DisplayMetrics();
                activity.getWindowManager().getDefaultDisplay().getMetrics(out);
                widthPx = out.widthPixels;
            }
        }
        int adWidthDp = Math.max(320, Math.round(widthPx / density));
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, adWidthDp);
    }
}
