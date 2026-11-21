package com.android.wificalllocal.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.android.wificalllocal.R;
import com.android.wificalllocal.ServiceLocator;
import com.android.wificalllocal.databinding.FragmentHomeBinding;
import com.android.wificalllocal.network.NetworkUtils;
import com.android.wificalllocal.ui.MainActivity;
import com.android.wificalllocal.ui.MainViewModel;
import com.android.wificalllocal.ui.UiDialogs;
import com.android.wificalllocal.ui.adapter.ChatSummaryAdapter;
import com.android.wificalllocal.ui.adapter.HomePeerAdapter;
import com.android.wificalllocal.util.AvatarLoader;

public class HomeFragment extends Fragment {

    private FragmentHomeBinding binding;
    private MainViewModel viewModel;
    private HomePeerAdapter peerAdapter;
    private ChatSummaryAdapter chatAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
        ServiceLocator locator = ServiceLocator.getInstance(requireContext());

        peerAdapter = new HomePeerAdapter(peer -> {
            String chatId = viewModel.openOrCreateDmChat(peer, () ->
                    ((MainActivity) requireActivity()).openChatDetail(
                            com.android.wificalllocal.network.PeerConnectionManager.makeDmChatId(
                                    locator.getPreferencesManager().getOrCreateDeviceId(), peer.deviceId),
                            peer.name, peer.deviceId));
        });
        binding.homeNearbyRecycler.setLayoutManager(
                new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        binding.homeNearbyRecycler.setAdapter(peerAdapter);

        chatAdapter = new ChatSummaryAdapter(chat ->
                ((MainActivity) requireActivity()).openChatDetail(chat.chatId, chat.title, chat.peerDeviceId));
        binding.homeChatsRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.homeChatsRecycler.setAdapter(chatAdapter);

        viewModel.observeProfile().observe(getViewLifecycleOwner(), profile -> {
            if (profile != null) {
                binding.homeUserName.setText(profile.name + " • " + profile.activeStatus);
                AvatarLoader.loadLocalAvatar(binding.homeUserAvatar, profile.avatarPath, profile.name, profile.deviceId);
            }
        });

        viewModel.observeActivePeers().observe(getViewLifecycleOwner(), peers -> {
            peerAdapter.submitList(peers);
            binding.homeNearbyEmpty.setVisibility(peers == null || peers.isEmpty() ? View.VISIBLE : View.GONE);
        });

        viewModel.observeChats().observe(getViewLifecycleOwner(), chats -> {
            chatAdapter.submitList(chats);
            binding.homeChatsEmpty.setVisibility(chats == null || chats.isEmpty() ? View.VISIBLE : View.GONE);
        });

        viewModel.getDiscoveryManager().getStatusTextLive().observe(getViewLifecycleOwner(), status -> refreshNetworkBanner(status));
        refreshNetworkBanner(null);

        binding.btnHomeScan.setOnClickListener(v -> ((MainActivity) requireActivity()).launchQrScanner());
        binding.btnHomeGroup.setOnClickListener(v -> UiDialogs.showCreateGroupDialog(requireContext(),
                (name, desc, isPriv, pin) -> viewModel.createGroup(name, desc, isPriv, pin)));
        binding.btnHomeQr.setOnClickListener(v -> ((MainActivity) requireActivity()).openQrShow());
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshNetworkBanner(null);
    }

    private void refreshNetworkBanner(String statusOverride) {
        if (binding == null) return;
        ServiceLocator locator = ServiceLocator.getInstance(requireContext());
        NetworkUtils.LocalNetworkInfo net = NetworkUtils.detectLocalNetwork(requireContext());
        int port = locator.getPreferencesManager().getServerPort();
        String mode = locator.getPreferencesManager().getConnectionMode();
        binding.homeNetworkModeChip.setText(mode);

        if (net.isValid()) {
            binding.homeIpPortText.setText(net.ipAddress + ":" + port);
            String label = statusOverride != null ? statusOverride
                    : getString(R.string.auto_connected_via, net.routeType);
            binding.homeConnectionStatus.setText(label);
        } else {
            binding.homeIpPortText.setText("0.0.0.0:" + port);
            binding.homeConnectionStatus.setText(R.string.status_no_local_network);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
