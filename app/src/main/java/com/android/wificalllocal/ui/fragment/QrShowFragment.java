package com.android.wificalllocal.ui.fragment;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.android.wificalllocal.R;
import com.android.wificalllocal.ServiceLocator;
import com.android.wificalllocal.databinding.FragmentQrShowBinding;
import com.android.wificalllocal.network.LocalHttpServer;
import com.android.wificalllocal.network.NetworkUtils;
import com.android.wificalllocal.ui.MainActivity;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

public class QrShowFragment extends Fragment {

    private FragmentQrShowBinding binding;
    private ServiceLocator locator;
    private String activeUrl = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentQrShowBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        locator = ServiceLocator.getInstance(requireContext());

        binding.btnQrBack.setOnClickListener(v -> ((MainActivity) requireActivity()).popCurrentTab());
        binding.btnOpenWifiSettings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        binding.btnCopyQrUrl.setOnClickListener(v -> {
            if (!activeUrl.isEmpty()) {
                ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("Local Server URL", activeUrl));
                    Toast.makeText(requireContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
                }
            }
        });

        renderQrState();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding != null) renderQrState();
    }

    private void renderQrState() {
        NetworkUtils.LocalNetworkInfo netInfo = NetworkUtils.detectLocalNetwork(requireContext());
        LocalHttpServer server = locator.getLocalHttpServer();
        boolean serverReady = server != null && server.isListening();

        if (!netInfo.isValid() || !serverReady) {
            binding.qrValidCard.setVisibility(View.GONE);
            binding.qrNoNetworkCard.setVisibility(View.VISIBLE);
            activeUrl = "";
            return;
        }

        int port = server.getListeningPort();
        activeUrl = "http://" + netInfo.ipAddress + ":" + port;

        binding.qrValidCard.setVisibility(View.VISIBLE);
        binding.qrNoNetworkCard.setVisibility(View.GONE);
        binding.qrUrlText.setText(activeUrl);
        binding.qrBrowserHint.setText(getString(R.string.qr_browser_hint, activeUrl));

        Bitmap qrBitmap = generateQrBitmap(activeUrl, 640);
        if (qrBitmap != null) {
            binding.qrCodeImage.setImageBitmap(qrBitmap);
        }
    }

    @Nullable
    private Bitmap generateQrBitmap(String content, int size) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    bmp.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
