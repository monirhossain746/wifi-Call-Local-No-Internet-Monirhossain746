package com.android.wificalllocal.ui.blocked;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.android.wificalllocal.BuildConfig;
import com.android.wificalllocal.R;
import com.android.wificalllocal.ServiceLocator;
import com.android.wificalllocal.control.AppControlManager;
import com.android.wificalllocal.control.UpdateManager;
import com.android.wificalllocal.databinding.ActivityAppBlockedBinding;
import com.android.wificalllocal.ui.MainActivity;

/**
 * Non-bypassable blocking screen shown when Firebase Remote Config indicates:
 * 1) is_app_enabled == false (Kill Switch)
 * 2) latest_version_code > current VERSION_CODE (Force Update)
 * Supports re-checking Remote Config so re-enabling access restores normal app entry.
 */
public class AppBlockedActivity extends AppCompatActivity {

    public static final String EXTRA_DECISION = "decision";
    public static final String EXTRA_UPDATE_URL = "update_url";
    public static final String EXTRA_UPDATE_MESSAGE = "update_message";
    public static final String EXTRA_LATEST_VERSION = "latest_version";

    private ActivityAppBlockedBinding binding;
    private ServiceLocator locator;
    private UpdateManager updateManager;
    private String decisionName;
    private String updateUrl;
    private String updateMessage;
    private long latestVersionCode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityAppBlockedBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        locator = ServiceLocator.getInstance(this);
        updateManager = new UpdateManager(this);

        readExtras(getIntent());
        bindStateUi();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Non-cancelable blocking screen: back closes app completely
                finishAffinity();
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        readExtras(intent);
        bindStateUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        recheckRemoteConfig();
    }

    private void readExtras(Intent i) {
        decisionName = i != null ? i.getStringExtra(EXTRA_DECISION) : AppControlManager.AccessDecision.BLOCKED_DISABLED.name();
        updateUrl = i != null ? i.getStringExtra(EXTRA_UPDATE_URL) : "";
        updateMessage = i != null ? i.getStringExtra(EXTRA_UPDATE_MESSAGE) : "";
        latestVersionCode = i != null ? i.getLongExtra(EXTRA_LATEST_VERSION, BuildConfig.VERSION_CODE) : BuildConfig.VERSION_CODE;
    }

    private void bindStateUi() {
        boolean isUpdateMode = AppControlManager.AccessDecision.BLOCKED_FORCE_UPDATE.name().equals(decisionName);
        if (isUpdateMode) {
            binding.blockedIcon.setColorFilter(ContextCompat.getColor(this, R.color.cyan_400));
            binding.blockedTitle.setText(R.string.force_update_title);
            binding.blockedPrimaryMsg.setText(
                    (updateMessage != null && !updateMessage.trim().isEmpty())
                            ? updateMessage
                            : getString(R.string.force_update_default_msg)
            );
            binding.blockedSecondaryMsg.setText("v" + BuildConfig.VERSION_NAME + " (Build " + BuildConfig.VERSION_CODE + ") → Build " + latestVersionCode);
            binding.btnBlockedPrimary.setText(R.string.btn_update_now);
            binding.btnBlockedPrimary.setEnabled(true);
            binding.btnBlockedPrimary.setOnClickListener(v -> triggerUpdateDownload());
            binding.btnBlockedSecondary.setText(R.string.btn_exit_app);
            binding.btnBlockedSecondary.setOnClickListener(v -> {
                updateManager.cancelDownload();
                finishAffinity();
            });
        } else {
            binding.blockedIcon.setColorFilter(ContextCompat.getColor(this, R.color.red_500));
            binding.blockedTitle.setText(R.string.app_disabled_title);
            binding.blockedPrimaryMsg.setText(R.string.app_disabled_message_bn);
            binding.blockedSecondaryMsg.setText(R.string.app_disabled_message_en);
            binding.updateProgress.setVisibility(View.GONE);
            binding.updateStatusText.setVisibility(View.GONE);
            binding.btnBlockedPrimary.setText(R.string.btn_check_again);
            binding.btnBlockedPrimary.setEnabled(true);
            binding.btnBlockedPrimary.setOnClickListener(v -> {
                binding.btnBlockedPrimary.setEnabled(false);
                recheckRemoteConfig();
            });
            binding.btnBlockedSecondary.setText(R.string.btn_exit_app);
            binding.btnBlockedSecondary.setOnClickListener(v -> finishAffinity());
        }
    }

    private void triggerUpdateDownload() {
        if (!UpdateManager.isValidHttpsUpdateUrl(updateUrl)) {
            Toast.makeText(this, R.string.update_invalid_url, Toast.LENGTH_LONG).show();
            return;
        }
        binding.btnBlockedPrimary.setEnabled(false);
        binding.updateProgress.setVisibility(View.VISIBLE);
        binding.updateProgress.setProgress(0);
        binding.updateStatusText.setVisibility(View.VISIBLE);
        binding.updateStatusText.setText(R.string.update_downloading);

        updateManager.startUpdate(updateUrl, BuildConfig.VERSION_CODE, new UpdateManager.UpdateListener() {
            @Override
            public void onProgress(int percent, String statusText) {
                binding.updateProgress.setProgress(percent);
                binding.updateStatusText.setText(statusText);
            }

            @Override
            public void onPermissionRequired(Intent settingsIntent) {
                binding.btnBlockedPrimary.setEnabled(true);
                binding.updateStatusText.setText(R.string.update_perm_unknown_sources);
                Toast.makeText(AppBlockedActivity.this, R.string.update_perm_unknown_sources, Toast.LENGTH_LONG).show();
                startActivity(settingsIntent);
            }

            @Override
            public void onReadyToInstall(Intent installIntent) {
                binding.btnBlockedPrimary.setEnabled(true);
                binding.updateStatusText.setText(R.string.force_update_title);
                startActivity(installIntent);
            }

            @Override
            public void onError(String message) {
                binding.btnBlockedPrimary.setEnabled(true);
                binding.updateStatusText.setText(message);
                Toast.makeText(AppBlockedActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void recheckRemoteConfig() {
        locator.getAppControlManager().checkRemoteGate(state -> {
            if (isFinishing() || isDestroyed()) return;
            binding.btnBlockedPrimary.setEnabled(true);
            if (!state.isBlocked()) {
                Intent main = new Intent(this, MainActivity.class);
                main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(main);
                finish();
            } else {
                decisionName = state.decision.name();
                updateUrl = state.updateLink;
                updateMessage = state.updateMessage;
                latestVersionCode = state.latestVersionCode;
                bindStateUi();
            }
        });
    }
}
