package com.android.wificalllocal.ui.fragment;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.wificalllocal.R;
import com.android.wificalllocal.ServiceLocator;
import com.android.wificalllocal.data.local.AppDatabase;
import com.android.wificalllocal.data.local.entity.PeerEntity;
import com.android.wificalllocal.databinding.FragmentNearbyBinding;
import com.android.wificalllocal.databinding.ItemSimpleRowBinding;
import com.android.wificalllocal.network.DiscoveryManager;
import com.android.wificalllocal.network.PeerConnectionManager;
import com.android.wificalllocal.ui.MainActivity;
import com.android.wificalllocal.ui.MainViewModel;
import com.android.wificalllocal.ui.UiDialogs;
import com.android.wificalllocal.ui.adapter.NearbyPeerAdapter;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class NearbyFragment extends Fragment {

    private FragmentNearbyBinding binding;
    private MainViewModel viewModel;
    private NearbyPeerAdapter peerAdapter;
    private P2pAdapter p2pAdapter;

    private final ActivityResultLauncher<String[]> hotspotPermLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            r -> triggerHotspot()
    );

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentNearbyBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
        ServiceLocator locator = ServiceLocator.getInstance(requireContext());

        peerAdapter = new NearbyPeerAdapter(new NearbyPeerAdapter.Listener() {
            @Override
            public void onChat(PeerEntity peer) {
                String myId = locator.getPreferencesManager().getOrCreateDeviceId();
                String chatId = PeerConnectionManager.makeDmChatId(myId, peer.deviceId);
                viewModel.openOrCreateDmChat(peer, () ->
                        ((MainActivity) requireActivity()).openChatDetail(chatId, peer.name, peer.deviceId));
            }

            @Override
            public void onVoiceCall(PeerEntity peer) {
                ((MainActivity) requireActivity()).startOutgoingCall(peer.deviceId, peer.name, peer.ipAddress, "VOICE");
            }

            @Override
            public void onVideoCall(PeerEntity peer) {
                ((MainActivity) requireActivity()).startOutgoingCall(peer.deviceId, peer.name, peer.ipAddress, "VIDEO");
            }

            @Override
            public void onToggleFriend(PeerEntity peer) {
                viewModel.toggleFriend(peer);
            }

            @Override
            public void onBlock(PeerEntity peer) {
                viewModel.blockPeer(peer, true);
                Toast.makeText(requireContext(), peer.name + " blocked", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onViewProfile(PeerEntity peer) {
                ((MainActivity) requireActivity()).openPeerProfile(peer);
            }
        });

        binding.nearbyPeersRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.nearbyPeersRecycler.setAdapter(peerAdapter);

        p2pAdapter = new P2pAdapter(device -> locator.getDiscoveryManager().connectToWifiDirectDevice(device,
                new WifiP2pManager.ActionListener() {
                    @Override public void onSuccess() {
                        Toast.makeText(requireContext(), "Connecting to " + device.deviceName, Toast.LENGTH_SHORT).show();
                    }
                    @Override public void onFailure(int reason) {
                        Toast.makeText(requireContext(), "WiFi Direct error: " + reason, Toast.LENGTH_SHORT).show();
                    }
                }));
        binding.p2pDevicesRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.p2pDevicesRecycler.setAdapter(p2pAdapter);

        viewModel.observeActivePeers().observe(getViewLifecycleOwner(), list -> {
            peerAdapter.submitList(list);
            binding.nearbyEmptyText.setVisibility(list == null || list.isEmpty() ? View.VISIBLE : View.GONE);
        });

        locator.getDiscoveryManager().getHotspotInfoLive().observe(getViewLifecycleOwner(), info -> {
            if (info != null && !info.isEmpty()) {
                binding.hotspotCredentialsCard.setVisibility(View.VISIBLE);
                binding.hotspotCredentialsText.setText(info);
            } else {
                binding.hotspotCredentialsCard.setVisibility(View.GONE);
            }
        });

        locator.getDiscoveryManager().getP2pPeersLive().observe(getViewLifecycleOwner(), p2p -> {
            boolean show = p2p != null && !p2p.isEmpty();
            binding.p2pDevicesHeader.setVisibility(show ? View.VISIBLE : View.GONE);
            binding.p2pDevicesRecycler.setVisibility(show ? View.VISIBLE : View.GONE);
            p2pAdapter.submit(p2p);
        });

        binding.btnScanNearby.setOnClickListener(v -> {
            locator.getDiscoveryManager().triggerManualScan();
            Toast.makeText(requireContext(), R.string.nearby_scan, Toast.LENGTH_SHORT).show();
        });

        binding.btnEveryoneRoom.setOnClickListener(v ->
                ((MainActivity) requireActivity()).openChatDetail(
                        AppDatabase.EVERYONE_ROOM_ID, getString(R.string.everyone_room_title), null));

        binding.btnAllGroups.setOnClickListener(v -> ((MainActivity) requireActivity()).openAllGroups());

        binding.btnManualIp.setOnClickListener(v -> UiDialogs.showManualIpDialog(
                requireContext(), locator.getPreferencesManager().getServerPort(),
                (ip, port) -> {
                    locator.getPeerConnectionManager().connectToPeer(ip, port, null);
                    Toast.makeText(requireContext(), getString(R.string.connecting_to_peer, ip, port), Toast.LENGTH_SHORT).show();
                }));

        binding.btnHostHotspot.setOnClickListener(v -> requestHotspotPermissionAndStart());
        binding.btnWifiDirect.setOnClickListener(v -> {
            locator.getDiscoveryManager().discoverP2pPeers();
            Toast.makeText(requireContext(), R.string.nearby_wifi_direct, Toast.LENGTH_SHORT).show();
        });
    }

    private void requestHotspotPermissionAndStart() {
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.NEARBY_WIFI_DEVICES
                : Manifest.permission.ACCESS_FINE_LOCATION;
        if (ContextCompat.checkSelfPermission(requireContext(), perm) == PackageManager.PERMISSION_GRANTED) {
            triggerHotspot();
        } else {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.perm_rationale_title)
                    .setMessage(R.string.perm_nearby_wifi)
                    .setPositiveButton(R.string.ok, (d, w) -> hotspotPermLauncher.launch(new String[]{perm}))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }
    }

    private void triggerHotspot() {
        ServiceLocator.getInstance(requireContext()).getDiscoveryManager().startLocalOnlyHotspot(
                new DiscoveryManager.HotspotCallback() {
                    @Override public void onStarted(String credentialsInfo) {
                        if (getContext() != null) {
                            Toast.makeText(requireContext(), "LocalOnlyHotspot Started", Toast.LENGTH_SHORT).show();
                        }
                    }
                    @Override public void onError(String message) {
                        if (getContext() != null) {
                            new MaterialAlertDialogBuilder(requireContext())
                                    .setTitle(R.string.nearby_hotspot_host)
                                    .setMessage(message)
                                    .setPositiveButton(R.string.ok, null)
                                    .show();
                        }
                    }
                });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private static class P2pAdapter extends RecyclerView.Adapter<P2pAdapter.VH> {
        interface OnPick { void onPick(WifiP2pDevice device); }
        private final List<WifiP2pDevice> list = new ArrayList<>();
        private final OnPick onPick;

        P2pAdapter(OnPick onPick) { this.onPick = onPick; }
        void submit(List<WifiP2pDevice> items) {
            list.clear();
            if (items != null) list.addAll(items);
            notifyDataSetChanged();
        }

        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(ItemSimpleRowBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            WifiP2pDevice d = list.get(pos);
            h.b.rowTitle.setText(d.deviceName);
            h.b.rowSubtitle.setText(d.deviceAddress);
            h.b.rowActionBtn.setText(R.string.connect);
            h.b.rowActionBtn.setOnClickListener(v -> onPick.onPick(d));
        }

        @Override public int getItemCount() { return list.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final ItemSimpleRowBinding b;
            VH(ItemSimpleRowBinding b) { super(b.getRoot()); this.b = b; }
        }
    }
}
