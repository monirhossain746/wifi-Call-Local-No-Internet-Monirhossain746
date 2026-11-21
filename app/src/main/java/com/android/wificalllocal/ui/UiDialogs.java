package com.android.wificalllocal.ui;

import android.content.Context;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.android.wificalllocal.R;
import com.android.wificalllocal.network.NetworkUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class UiDialogs {

    public interface ManualIpCallback {
        void onConnect(String ip, int port);
    }

    public interface CreateGroupCallback {
        void onCreate(String name, String desc, boolean isPrivate, String pin);
    }

    public static void showManualIpDialog(Context context, int defaultPort, ManualIpCallback callback) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * context.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        EditText ipInput = new EditText(context);
        ipInput.setHint(R.string.hint_ip_address);
        ipInput.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(ipInput);

        EditText portInput = new EditText(context);
        portInput.setHint(R.string.hint_port);
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        portInput.setText(String.valueOf(defaultPort));
        box.addView(portInput);

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.dialog_manual_ip_title)
                .setView(box)
                .setPositiveButton(R.string.connect, (d, w) -> {
                    String ip = ipInput.getText().toString().trim();
                    if (!NetworkUtils.isPrivateLanIp(ip)) {
                        Toast.makeText(context, R.string.error_invalid_ip, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int port = defaultPort;
                    try {
                        port = Integer.parseInt(portInput.getText().toString().trim());
                    } catch (NumberFormatException ignored) {
                    }
                    if (!NetworkUtils.isValidPort(port)) {
                        Toast.makeText(context, "Port must be 1024–65535", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    callback.onConnect(ip, port);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    public static void showCreateGroupDialog(Context context, CreateGroupCallback callback) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * context.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        EditText nameInput = new EditText(context);
        nameInput.setHint(R.string.hint_group_name);
        box.addView(nameInput);

        EditText descInput = new EditText(context);
        descInput.setHint(R.string.hint_group_desc);
        box.addView(descInput);

        CheckBox privateBox = new CheckBox(context);
        privateBox.setText(R.string.label_private_group);
        box.addView(privateBox);

        EditText pinInput = new EditText(context);
        pinInput.setHint(R.string.hint_group_pin);
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinInput.setEnabled(false);
        privateBox.setOnCheckedChangeListener((btn, checked) -> pinInput.setEnabled(checked));
        box.addView(pinInput);

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.create_group_title)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.length() < 2) {
                        Toast.makeText(context, R.string.error_name_length, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean isPriv = privateBox.isChecked();
                    String pin = pinInput.getText().toString().trim();
                    if (isPriv && pin.length() < 4) {
                        Toast.makeText(context, R.string.hint_group_pin, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    callback.onCreate(name, descInput.getText().toString().trim(), isPriv, pin);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
