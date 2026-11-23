package com.android.wificalllocal.ui.ads

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.wificalllocal.ads.AdConsentManager
import com.android.wificalllocal.network.NetworkUtils
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AdMobComposeBanner"

object AdMobAdConfig {
    const val ADMOB_APP_ID = "ca-app-pub-3181872815781744~9894324318"
    const val PRODUCTION_BANNER_AD_UNIT_ID = "ca-app-pub-3181872815781744/1818458941"

    /**
     * Always returns the live production Banner Ad Unit ID.
     */
    fun resolveBannerAdUnitId(): String = PRODUCTION_BANNER_AD_UNIT_ID
}

/**
 * Computes the current-orientation anchored adaptive banner [AdSize] for the given width in dp.
 */
fun computeAnchoredAdaptiveAdSize(context: Context, screenWidthDp: Int): AdSize {
    val safeWidthDp = screenWidthDp.coerceAtLeast(320)
    return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, safeWidthDp)
}

/**
 * Jetpack Compose AdMob Adaptive Banner component.
 *
 * Designed to live inside a [androidx.compose.foundation.layout.Box] inside a bottom
 * [androidx.compose.foundation.layout.Column] (`verticalArrangement = Arrangement.Bottom`)
 * immediately above the bottom navigation bar. Uses a fixed adaptive height computed from
 * [AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize] so screen content above it never
 * overlaps or shifts unexpectedly.
 */
@Composable
fun AdaptiveBannerAd(
    modifier: Modifier = Modifier,
    adsEnabled: Boolean = true,
    adConsentManager: AdConsentManager? = null,
) {
    if (!adsEnabled) {
        return
    }

    val isPreview = LocalInspectionMode.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .testTag("admob_adaptive_banner_container"),
    ) {
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val fallbackWidthDp = (context.resources.displayMetrics.widthPixels / density).toInt()
        val availableWidthDp = if (maxWidth != Dp.Infinity) {
            maxWidth.value.toInt().coerceAtLeast(320)
        } else {
            fallbackWidthDp.coerceAtLeast(320)
        }

        val adSize = remember(context, availableWidthDp) {
            computeAnchoredAdaptiveAdSize(context, availableWidthDp)
        }
        val bannerHeightDp = adSize.height.coerceAtLeast(50).dp
        val adUnitId = AdMobAdConfig.PRODUCTION_BANNER_AD_UNIT_ID

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(bannerHeightDp)
                .background(Color.White)
                .testTag("admob_adaptive_banner_box"),
            contentAlignment = Alignment.Center,
        ) {
            HorizontalDivider(
                modifier = Modifier.align(Alignment.TopCenter),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            if (isPreview) {
                Text(
                    text = "Adaptive Banner (${adSize.width}x${adSize.height}dp)",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.DarkGray,
                )
            } else {
                var consentResolved by remember { mutableStateOf(false) }
                var useNonPersonalized by remember { mutableStateOf(false) }

                LaunchedEffect(context, adUnitId) {
                    val initiated = AdConsentManager.ensureMobileAdsInitialized(context)
                    adConsentManager?.syncComposeInitStarted(initiated)

                    val activity = context.findActivity()
                    if (activity == null) {
                        consentResolved = true
                        return@LaunchedEffect
                    }

                    val timeoutJob = launch {
                        delay(3000L)
                        if (!consentResolved) {
                            Log.w(TAG, "UMP consent check timed out after 3s; proceeding with non-personalized fallback")
                            useNonPersonalized = true
                            consentResolved = true
                        }
                    }

                    try {
                        val consentInfo = UserMessagingPlatform.getConsentInformation(activity)
                        if (consentInfo.canRequestAds()) {
                            timeoutJob.cancel()
                            useNonPersonalized = false
                            consentResolved = true
                        }
                        val params = ConsentRequestParameters.Builder().build()
                        consentInfo.requestConsentInfoUpdate(
                            activity,
                            params,
                            {
                                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                                    timeoutJob.cancel()
                                    if (formError != null) {
                                        Log.w(TAG, "UMP consent notice [${formError.errorCode}]: ${formError.message}")
                                    }
                                    useNonPersonalized = !consentInfo.canRequestAds()
                                    consentResolved = true
                                }
                            },
                            { requestError ->
                                timeoutJob.cancel()
                                Log.w(TAG, "UMP update warning [${requestError.errorCode}]: ${requestError.message}")
                                useNonPersonalized = !consentInfo.canRequestAds()
                                consentResolved = true
                            },
                        )
                    } catch (t: Throwable) {
                        timeoutJob.cancel()
                        Log.w(TAG, "UMP fallback to non-personalized ads", t)
                        useNonPersonalized = true
                        consentResolved = true
                    }
                }

                val adView = remember(context, adUnitId, adSize.width, adSize.height) {
                    AdView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        this.adUnitId = adUnitId
                        setAdSize(adSize)
                        adListener = object : AdListener() {
                            override fun onAdLoaded() {
                                Log.d(
                                    TAG,
                                    "onAdLoaded: unitId=$adUnitId, size=${adSize.width}x${adSize.height}dp",
                                )
                                adConsentManager?.recordComposeBannerLoaded(adUnitId, adSize, false)
                            }

                            override fun onAdFailedToLoad(error: LoadAdError) {
                                Log.e(
                                    TAG,
                                    "onAdFailedToLoad: code=${error.code}, message=${error.message}, domain=${error.domain}, unitId=$adUnitId",
                                )
                                adConsentManager?.recordComposeBannerFailed(error.code, error.message)
                            }

                            override fun onAdOpened() {
                                Log.d(TAG, "onAdOpened: unitId=$adUnitId")
                                adConsentManager?.recordComposeBannerOpened(adUnitId)
                            }

                            override fun onAdClosed() {
                                Log.d(TAG, "onAdClosed: unitId=$adUnitId")
                                adConsentManager?.recordComposeBannerClosed(adUnitId)
                            }
                        }
                    }
                }

                var lastLoadedSignature by remember(adView) { mutableStateOf<String?>(null) }

                LaunchedEffect(adView, consentResolved, useNonPersonalized) {
                    if (!consentResolved) return@LaunchedEffect
                    if (!NetworkUtils.hasNetworkConnectivity(context)) {
                        Log.d(TAG, "Offline: deferring adaptive banner load")
                        adConsentManager?.recordComposeBannerOffline()
                        return@LaunchedEffect
                    }
                    val signature = "$adUnitId:${adSize.width}x${adSize.height}:npa=$useNonPersonalized"
                    if (lastLoadedSignature == signature) return@LaunchedEffect
                    lastLoadedSignature = signature

                    adConsentManager?.recordComposeBannerLoading(adUnitId, adSize)
                    val requestBuilder = AdRequest.Builder()
                    if (useNonPersonalized) {
                        val extras = Bundle().apply { putString("npa", "1") }
                        requestBuilder.addNetworkExtrasBundle(AdMobAdapter::class.java, extras)
                    }
                    try {
                        adView.loadAd(requestBuilder.build())
                    } catch (t: Throwable) {
                        Log.e(TAG, "AdView.loadAd() threw exception", t)
                        adConsentManager?.recordComposeBannerFailed(
                            -999,
                            t.message ?: "loadAd threw exception",
                        )
                    }
                }

                DisposableEffect(lifecycleOwner, adView) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> runCatching { adView.resume() }
                            Lifecycle.Event.ON_PAUSE -> runCatching { adView.pause() }
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                        runCatching { adView.destroy() }
                    }
                }

                AndroidView(
                    factory = { adView },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("admob_adaptive_banner_view"),
                )
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
