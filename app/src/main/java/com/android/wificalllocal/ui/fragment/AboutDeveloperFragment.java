package com.android.wificalllocal.ui.fragment;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.android.wificalllocal.R;
import com.android.wificalllocal.databinding.FragmentAboutDeveloperBinding;
import com.android.wificalllocal.ui.MainActivity;

public class AboutDeveloperFragment extends Fragment {

    private static final String PORTFOLIO_URL = "https://monirhossain746.blogspot.com";
    private static final String FACEBOOK_URL = "https://facebook.com/Monirhossain746";
    private static final String GITHUB_URL = "https://github.com/monirhossain746/wifi-Call-Local-No-Internet-Monirhossain746";

    private FragmentAboutDeveloperBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentAboutDeveloperBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.btnAboutBack.setOnClickListener(v -> ((MainActivity) requireActivity()).popCurrentTab());
        binding.btnVisitPortfolio.setOnClickListener(v -> openUrl(PORTFOLIO_URL));
        binding.btnFacebook.setOnClickListener(v -> openUrl(FACEBOOK_URL));
        binding.btnContactDev.setOnClickListener(v -> openUrl(PORTFOLIO_URL));
        binding.btnGithubRepo.setOnClickListener(v -> openUrl(GITHUB_URL));
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.open_settings, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
