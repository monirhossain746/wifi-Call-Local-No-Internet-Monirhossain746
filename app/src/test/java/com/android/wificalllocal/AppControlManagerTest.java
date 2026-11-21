package com.android.wificalllocal;

import android.content.Context;

import com.android.wificalllocal.control.AppControlManager;
import com.android.wificalllocal.control.RemoteConfigManager;
import com.android.wificalllocal.control.UpdateManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies Remote Config Kill Switch (Test A/B/C), Force Update (Test D),
 * offline SharedPreferences caching, and HTTPS update link validation.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class AppControlManagerTest {

    @Test
    public void testA_normalStartupAllowsAccess() {
        RemoteConfigManager.ConfigSnapshot snap = new RemoteConfigManager.ConfigSnapshot(
                true, 1L, "", "No update", true);
        AppControlManager.AppGateState state = AppControlManager.evaluate(snap, 1);

        assertEquals(AppControlManager.AccessDecision.ALLOWED, state.decision);
        assertFalse(state.isBlocked());
    }

    @Test
    public void testB_killSwitchBlocksEvenWhenUpdatePresent() {
        RemoteConfigManager.ConfigSnapshot snap = new RemoteConfigManager.ConfigSnapshot(
                false, 2L, "https://example.com/update.apk", "Maintenance", true);
        AppControlManager.AppGateState state = AppControlManager.evaluate(snap, 1);

        assertEquals(AppControlManager.AccessDecision.BLOCKED_DISABLED, state.decision);
        assertTrue(state.isBlocked());
    }

    @Test
    public void testC_reenableRestoresNormalAccess() {
        Context ctx = RuntimeEnvironment.getApplication();
        RemoteConfigManager rcm = new RemoteConfigManager(ctx);

        // First disable
        rcm.persistSnapshot(new RemoteConfigManager.ConfigSnapshot(false, 1L, "", "", true));
        assertEquals(
                AppControlManager.AccessDecision.BLOCKED_DISABLED,
                AppControlManager.evaluate(rcm.getCachedSnapshot(), 1).decision
        );

        // Then re-enable
        rcm.persistSnapshot(new RemoteConfigManager.ConfigSnapshot(true, 1L, "", "", true));
        AppControlManager.AppGateState restored = AppControlManager.evaluate(rcm.getCachedSnapshot(), 1);
        assertEquals(AppControlManager.AccessDecision.ALLOWED, restored.decision);
        assertFalse(restored.isBlocked());
    }

    @Test
    public void testD_forceUpdateTriggersWhenRemoteVersionHigher() {
        RemoteConfigManager.ConfigSnapshot snap = new RemoteConfigManager.ConfigSnapshot(
                true, 5L, "https://downloads.example.com/wifi-call-local-v5.apk", "Security & WebRTC upgrade required", true);
        AppControlManager.AppGateState state = AppControlManager.evaluate(snap, 4);

        assertEquals(AppControlManager.AccessDecision.BLOCKED_FORCE_UPDATE, state.decision);
        assertTrue(state.isBlocked());
        assertEquals(5L, state.latestVersionCode);
        assertEquals("https://downloads.example.com/wifi-call-local-v5.apk", state.updateLink);
    }

    @Test
    public void testUpdateUrlMustBeHttps() {
        assertTrue(UpdateManager.isValidHttpsUpdateUrl("https://github.com/user/repo/releases/download/v2/app.apk"));
        assertFalse(UpdateManager.isValidHttpsUpdateUrl("http://insecure.example.com/app.apk"));
        assertFalse(UpdateManager.isValidHttpsUpdateUrl("file:///sdcard/app.apk"));
        assertFalse(UpdateManager.isValidHttpsUpdateUrl(""));
        assertFalse(UpdateManager.isValidHttpsUpdateUrl(null));
    }
}
