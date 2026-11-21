package com.android.wificalllocal.ui.qr;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.android.wificalllocal.R;
import com.android.wificalllocal.databinding.ActivityQrScannerBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;

public class QrScannerActivity extends AppCompatActivity {

    public static final String EXTRA_QR_RESULT = "qr_result";

    private ActivityQrScannerBinding binding;
    private boolean handled = false;

    private final ActivityResultLauncher<String> cameraPermLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> {
                if (granted) {
                    startScanning();
                } else {
                    Toast.makeText(this, R.string.perm_camera, Toast.LENGTH_SHORT).show();
                    finish();
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityQrScannerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.btnScannerBack.setOnClickListener(v -> finish());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startScanning();
        } else {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.perm_rationale_title)
                    .setMessage(R.string.perm_camera)
                    .setPositiveButton(R.string.ok, (d, w) -> cameraPermLauncher.launch(Manifest.permission.CAMERA))
                    .setNegativeButton(R.string.cancel, (d, w) -> finish())
                    .show();
        }
    }

    private void startScanning() {
        binding.zxingBarcodeScanner.decodeContinuous(new BarcodeCallback() {
            @Override
            public void barcodeResult(BarcodeResult result) {
                if (handled || result.getText() == null) return;
                handled = true;
                Intent data = new Intent();
                data.putExtra(EXTRA_QR_RESULT, result.getText());
                setResult(RESULT_OK, data);
                finish();
            }
        });
        binding.zxingBarcodeScanner.resume();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            binding.zxingBarcodeScanner.resume();
        }
    }

    @Override
    protected void onPause() {
        binding.zxingBarcodeScanner.pause();
        super.onPause();
    }
}
