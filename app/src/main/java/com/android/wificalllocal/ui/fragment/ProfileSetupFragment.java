package com.android.wificalllocal.ui.fragment;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.android.wificalllocal.R;
import com.android.wificalllocal.ServiceLocator;
import com.android.wificalllocal.databinding.FragmentProfileSetupBinding;
import com.android.wificalllocal.ui.MainActivity;
import com.android.wificalllocal.ui.MainViewModel;
import com.android.wificalllocal.util.AvatarHelper;
import com.android.wificalllocal.util.AvatarLoader;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;

public class ProfileSetupFragment extends Fragment {

    private static final String ARG_IS_ONBOARDING = "is_onboarding";

    private FragmentProfileSetupBinding binding;
    private MainViewModel viewModel;
    private boolean isOnboarding;
    private String pendingAvatarPath = "";
    private Uri cameraCaptureUri;

    private final ActivityResultLauncher<PickVisualMediaRequest> photoPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    String saved = AvatarHelper.saveCircularAvatarFromUri(requireContext(), uri, "my_avatar");
                    if (saved != null) {
                        pendingAvatarPath = saved;
                        refreshPreview();
                    }
                }
            });

    private final ActivityResultLauncher<Uri> takePictureLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
                if (success && cameraCaptureUri != null) {
                    String saved = AvatarHelper.saveCircularAvatarFromUri(requireContext(), cameraCaptureUri, "my_avatar");
                    if (saved != null) {
                        pendingAvatarPath = saved;
                        refreshPreview();
                    }
                }
            });

    private final ActivityResultLauncher<String> cameraPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) launchCameraCapture();
                else Toast.makeText(requireContext(), R.string.perm_camera, Toast.LENGTH_SHORT).show();
            });

    public static ProfileSetupFragment newInstance(boolean isOnboarding) {
        ProfileSetupFragment f = new ProfileSetupFragment();
        Bundle args = new Bundle();
        args.putBoolean(ARG_IS_ONBOARDING, isOnboarding);
        f.setArguments(args);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentProfileSetupBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        isOnboarding = getArguments() == null || getArguments().getBoolean(ARG_IS_ONBOARDING, true);
        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);

        viewModel.observeProfile().observe(getViewLifecycleOwner(), profile -> {
            if (profile != null) {
                if (binding.inputName.getText() == null || binding.inputName.getText().length() == 0) {
                    binding.inputName.setText(profile.name);
                }
                if (binding.inputBio.getText() == null || binding.inputBio.getText().length() == 0) {
                    binding.inputBio.setText(profile.bio);
                }
                if (pendingAvatarPath.isEmpty() && profile.avatarPath != null) {
                    pendingAvatarPath = profile.avatarPath;
                }
                refreshPreview();
            }
        });

        binding.btnPickGallery.setOnClickListener(v ->
                photoPickerLauncher.launch(new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build()));

        binding.btnTakeCamera.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                launchCameraCapture();
            } else {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.perm_rationale_title)
                        .setMessage(R.string.perm_camera)
                        .setPositiveButton(R.string.ok, (d, w) -> cameraPermLauncher.launch(Manifest.permission.CAMERA))
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }
        });

        binding.btnRemovePhoto.setOnClickListener(v -> {
            pendingAvatarPath = "";
            refreshPreview();
        });

        binding.btnSaveProfile.setOnClickListener(v -> saveAndProceed());
        refreshPreview();
    }

    private void launchCameraCapture() {
        try {
            File dir = new File(requireContext().getCacheDir(), "camera");
            if (!dir.exists()) dir.mkdirs();
            File temp = new File(dir, "avatar_capture.jpg");
            cameraCaptureUri = FileProvider.getUriForFile(requireContext(),
                    requireContext().getPackageName() + ".fileprovider", temp);
            takePictureLauncher.launch(cameraCaptureUri);
        } catch (Exception ignored) {
        }
    }

    private void refreshPreview() {
        String name = binding.inputName.getText() != null ? binding.inputName.getText().toString() : "W";
        String id = ServiceLocator.getInstance(requireContext()).getPreferencesManager().getOrCreateDeviceId();
        AvatarLoader.loadLocalAvatar(binding.setupAvatarPreview, pendingAvatarPath, name, id);
        binding.btnRemovePhoto.setVisibility(pendingAvatarPath != null && !pendingAvatarPath.isEmpty()
                ? View.VISIBLE : View.GONE);
    }

    private void saveAndProceed() {
        String name = binding.inputName.getText() != null ? binding.inputName.getText().toString().trim() : "";
        String bio = binding.inputBio.getText() != null ? binding.inputBio.getText().toString().trim() : "";
        if (name.length() < 2 || name.length() > 30) {
            binding.nameInputLayout.setError(getString(R.string.error_name_length));
            return;
        }
        binding.nameInputLayout.setError(null);
        if (bio.length() > 60) {
            binding.bioInputLayout.setError(getString(R.string.error_bio_length));
            return;
        }
        binding.bioInputLayout.setError(null);

        viewModel.saveProfile(name, bio, pendingAvatarPath);
        if (isOnboarding) {
            ((MainActivity) requireActivity()).onProfileSetupCompleted();
        } else {
            ((MainActivity) requireActivity()).popCurrentTab();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
