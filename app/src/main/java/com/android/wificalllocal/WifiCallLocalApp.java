package com.android.wificalllocal;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.android.wificalllocal.util.PreferencesManager;

public class WifiCallLocalApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        ServiceLocator locator = ServiceLocator.getInstance(this);
        locator.getAppControlManager().getRemoteConfigManager().initialize();
        PreferencesManager prefs = locator.getPreferencesManager();
        applyTheme(prefs.getThemeMode());
        applyLanguage(prefs.getLanguage());
        long maxMb = locator.getAppControlManager().getCurrentGateState().maxCacheSizeMb;
        locator.getCacheManager().enforceCacheLimitAsync(maxMb);
    }

    public static void applyTheme(String themeMode) {
        if (PreferencesManager.THEME_LIGHT.equals(themeMode)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        } else if (PreferencesManager.THEME_DARK.equals(themeMode)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }

    public static void applyLanguage(String langCode) {
        LocaleListCompat locales = LocaleListCompat.forLanguageTags(langCode);
        AppCompatDelegate.setApplicationLocales(locales);
    }
}
