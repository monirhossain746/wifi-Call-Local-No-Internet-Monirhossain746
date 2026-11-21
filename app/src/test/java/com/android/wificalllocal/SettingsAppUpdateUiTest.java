package com.android.wificalllocal;

import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.android.wificalllocal.control.RemoteConfigManager;
import com.android.wificalllocal.ui.fragment.SettingsFragment;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Robolectric UI verification for the Settings screen App Update card:
 * - Verifies "App Update" card sits immediately above "About & Developer"
 * - Verifies installed and latest version labels are displayed
 * - Verifies both "Check Update" and "Update Now" buttons are visible and wired
 * - Verifies existing Settings options remain intact
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SettingsAppUpdateUiTest {

    private AppCompatActivity launchSettingsHost() {
        AppCompatActivity activity = Robolectric.buildActivity(AppCompatActivity.class)
                .create()
                .start()
                .resume()
                .get();
        activity.setTheme(R.style.Theme_WifiCallLocal);
        activity.getSupportFragmentManager()
                .beginTransaction()
                .add(android.R.id.content, new SettingsFragment(), "settings")
                .commitNow();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return activity;
    }

    @Test
    public void appUpdateCardAppearsImmediatelyAboveAboutDeveloperWithBothButtonsVisible() {
        AppCompatActivity host = launchSettingsHost();
        View root = host.findViewById(android.R.id.content);

        View updateCard = root.findViewById(R.id.card_app_update);
        View aboutCard = root.findViewById(R.id.card_about_developer);
        TextView cardTitle = root.findViewById(R.id.settings_app_update_title_text);
        TextView installedVersion = root.findViewById(R.id.settings_installed_version);
        TextView latestVersion = root.findViewById(R.id.settings_latest_version);
        Button btnCheckUpdate = root.findViewById(R.id.btn_check_update);
        Button btnUpdateNow = root.findViewById(R.id.btn_update_now);
        Button btnAboutDeveloper = root.findViewById(R.id.btn_about_developer);

        assertNotNull(updateCard);
        assertNotNull(aboutCard);
        assertEquals(View.VISIBLE, updateCard.getVisibility());
        assertEquals(View.VISIBLE, aboutCard.getVisibility());
        assertEquals("App Update", cardTitle.getText().toString());
        assertEquals("About & Developer", btnAboutDeveloper.getText().toString());

        // Verify App Update card is immediately above About & Developer in parent container
        ViewGroup parent = (ViewGroup) updateCard.getParent();
        assertEquals(parent, aboutCard.getParent());
        int updateIndex = parent.indexOfChild(updateCard);
        int aboutIndex = parent.indexOfChild(aboutCard);
        assertTrue(updateIndex >= 0);
        assertEquals(updateIndex + 1, aboutIndex);

        // Verify both buttons are visible and labeled properly
        assertNotNull(btnCheckUpdate);
        assertNotNull(btnUpdateNow);
        assertEquals(View.VISIBLE, btnCheckUpdate.getVisibility());
        assertEquals(View.VISIBLE, btnUpdateNow.getVisibility());
        assertTrue(btnCheckUpdate.isEnabled());
        assertTrue(btnUpdateNow.isEnabled());
        assertEquals("Check Update", btnCheckUpdate.getText().toString());
        assertEquals("Update Now", btnUpdateNow.getText().toString());

        // Verify installed & latest versions are rendered
        String installedText = installedVersion.getText().toString();
        String latestText = latestVersion.getText().toString();
        assertTrue(installedText.contains(BuildConfig.VERSION_NAME));
        assertTrue(installedText.contains(String.valueOf(BuildConfig.VERSION_CODE)));
        assertTrue(latestText.contains(String.valueOf(BuildConfig.VERSION_CODE)));

        // Verify existing Settings options were not removed
        assertNotNull(root.findViewById(R.id.btn_connection_mode));
        assertNotNull(root.findViewById(R.id.switch_auto_connect));
        assertNotNull(root.findViewById(R.id.btn_server_port));
        assertNotNull(root.findViewById(R.id.switch_notifications));
        assertNotNull(root.findViewById(R.id.btn_theme_select));
        assertNotNull(root.findViewById(R.id.btn_language_select));
        assertNotNull(root.findViewById(R.id.btn_storage_info));
        assertNotNull(root.findViewById(R.id.btn_blocked_users));
        assertNotNull(root.findViewById(R.id.btn_saved_contacts));
        assertNotNull(root.findViewById(R.id.btn_battery_opt));
        assertNotNull(root.findViewById(R.id.btn_privacy_trust));
    }

    @Test
    public void checkUpdateAndUpdateNowIntegrateWithRemoteConfigAndUpdateManager() {
        AppCompatActivity host = launchSettingsHost();
        RemoteConfigManager rcm = ServiceLocator.getInstance(host)
                .getAppControlManager()
                .getRemoteConfigManager();

        long nextBuild = BuildConfig.VERSION_CODE + 2L;
        rcm.persistSnapshot(new RemoteConfigManager.ConfigSnapshot(
                true,
                nextBuild,
                "http://insecure.example.com/update.apk",
                "Critical WebRTC update ready",
                false
        ));

        Button btnCheckUpdate = host.findViewById(R.id.btn_check_update);
        Button btnUpdateNow = host.findViewById(R.id.btn_update_now);
        TextView latestVersion = host.findViewById(R.id.settings_latest_version);
        TextView badge = host.findViewById(R.id.settings_update_badge);
        TextView status = host.findViewById(R.id.settings_update_status);

        btnCheckUpdate.performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertTrue(btnCheckUpdate.isEnabled());
        assertTrue(latestVersion.getText().toString().contains(String.valueOf(nextBuild)));
        assertEquals("Update Available", badge.getText().toString());
        assertEquals("Critical WebRTC update ready", status.getText().toString());

        // Clicking Update Now delegates to UpdateManager validation (rejecting non-HTTPS URL cleanly)
        btnUpdateNow.performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(host.getString(R.string.update_invalid_url), status.getText().toString());

        // Reset cached snapshot so other tests remain clean
        rcm.persistSnapshot(new RemoteConfigManager.ConfigSnapshot(
                true, BuildConfig.VERSION_CODE, "", "", false));
    }

    @Test
    public void manualUpdateButtonAndDiagnosticsAndCacheCardsWorkWithoutFirebase() {
        AppCompatActivity host = launchSettingsHost();

        Button btnManualUpdate = host.findViewById(R.id.btn_manual_update);
        TextView manualSubtitle = host.findViewById(R.id.settings_manual_update_subtitle);
        Button btnClearCache = host.findViewById(R.id.btn_clear_all_cache);
        TextView currentCache = host.findViewById(R.id.settings_current_cache);
        TextView maxCache = host.findViewById(R.id.settings_max_cache);
        TextView firebaseStatus = host.findViewById(R.id.settings_firebase_status);
        TextView admobStatus = host.findViewById(R.id.settings_admob_status);
        TextView commStatus = host.findViewById(R.id.settings_comm_status);

        assertNotNull(btnManualUpdate);
        assertEquals(View.VISIBLE, btnManualUpdate.getVisibility());
        assertEquals(host.getString(R.string.btn_manual_update), btnManualUpdate.getText().toString());
        assertNotNull(manualSubtitle);
        assertEquals(host.getString(R.string.settings_manual_update_subtitle), manualSubtitle.getText().toString());

        assertNotNull(btnClearCache);
        assertEquals(View.VISIBLE, btnClearCache.getVisibility());
        assertNotNull(currentCache);
        assertNotNull(maxCache);
        assertTrue(maxCache.getText().toString().contains("500 MB"));

        assertNotNull(firebaseStatus);
        assertNotNull(admobStatus);
        assertNotNull(commStatus);
        assertTrue(firebaseStatus.getText().toString().contains("wifi-call-local-1"));
        assertTrue(admobStatus.getText().toString().contains("SDK Init"));
        assertTrue(commStatus.getText().toString().contains("Internet"));

        btnManualUpdate.performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        android.content.Intent started = Shadows.shadowOf(host).getNextStartedActivity();
        assertNotNull("Expected Intent.ACTION_VIEW for GitHub Manual Update", started);
        assertEquals(android.content.Intent.ACTION_VIEW, started.getAction());
        assertEquals(SettingsFragment.GITHUB_REPO_URL, String.valueOf(started.getData()));
    }
}

