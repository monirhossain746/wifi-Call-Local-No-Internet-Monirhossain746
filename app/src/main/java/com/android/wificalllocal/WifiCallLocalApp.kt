package com.android.wificalllocal

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.android.wificalllocal.ads.AdConsentManager
import com.android.wificalllocal.util.PreferencesManager

class WifiCallLocalApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val locator = ServiceLocator.getInstance(this)
        locator.appControlManager.remoteConfigManager.initialize()

        val prefs = locator.preferencesManager
        applyTheme(prefs.themeMode)
        applyLanguage(prefs.language)

        val maxMb = locator.appControlManager.currentGateState.maxCacheSizeMb
        locator.cacheManager.enforceCacheLimitAsync(maxMb)

        // Initialize Google Mobile Ads SDK once per process on app startup.
        val started = AdConsentManager.ensureMobileAdsInitialized(this)
        locator.adConsentManager.syncComposeInitStarted(started)
    }

    companion object {
        @JvmStatic
        fun applyTheme(themeMode: String?) {
            when (themeMode) {
                PreferencesManager.THEME_LIGHT ->
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

                PreferencesManager.THEME_DARK ->
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

                else ->
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }

        @JvmStatic
        fun applyLanguage(langCode: String?) {
            val locales = LocaleListCompat.forLanguageTags(langCode ?: "en")
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}
