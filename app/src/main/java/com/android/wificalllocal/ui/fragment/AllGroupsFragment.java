package com.android.wificalllocal.ui.fragment;

import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.android.wificalllocal.R;
import com.android.wificalllocal.ServiceLocator;
import com.android.wificalllocal.data.local.entity.GroupEntity;
import com.android.wificalllocal.databinding.FragmentAllGroupsBinding;
import com.android.wificalllocal.ui.MainActivity;
import com.android.wificalllocal.ui.MainViewModel;
import com.android.wificalllocal.ui.UiDialogs;
import com.android.wificalllocal.ui.adapter.GroupCardAdapter;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class AllGroupsFragment extends Fragment {

    private FragmentAllGroupsBinding binding;
    private MainViewModel viewModel;
    private GroupCardAdapter adapter;
    private List<GroupEntity> allGroups = new ArrayList<>();
    private String searchQuery = "";
    private int filterMode = 0; // 0=All, 1=Joined, 2=NotJoined, 3=OnlineNow
    private boolean sortByMembers = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentAllGroupsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
        ServiceLocator locator = ServiceLocator.getInstance(requireContext());

        adapter = new GroupCardAdapter(group -> {
            if (group.isJoined) {
                ((MainActivity) requireActivity()).openChatDetail(group.groupId, group.name, null);
            } else if (!group.isPrivate) {
                viewModel.joinPublicGroup(group);
                ((MainActivity) requireActivity()).openChatDetail(group.groupId, group.name, null);
            } else {
                promptPrivatePin(group);
            }
        });

        binding.allGroupsRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.allGroupsRecycler.setAdapter(adapter);

        viewModel.observeAllGroups().observe(getViewLifecycleOwner(), list -> {
            allGroups = list != null ? list : new ArrayList<>();
            applyFilters();
        });

        binding.btnGroupsBack.setOnClickListener(v -> ((MainActivity) requireActivity()).popCurrentTab());
        binding.groupsSwipeRefresh.setOnRefreshListener(() -> {
            locator.getDiscoveryManager().triggerManualScan();
            binding.groupsSwipeRefresh.setRefreshing(false);
        });

        binding.btnSortGroups.setOnClickListener(v -> {
            sortByMembers = !sortByMembers;
            binding.btnSortGroups.setText(sortByMembers ? "Members" : "A-Z");
            applyFilters();
        });

        binding.inputSearchGroups.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s != null ? s.toString().trim().toLowerCase() : "";
                applyFilters();
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        binding.groupsFilterChips.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.contains(R.id.chip_filter_joined)) filterMode = 1;
            else if (checkedIds.contains(R.id.chip_filter_not_joined)) filterMode = 2;
            else if (checkedIds.contains(R.id.chip_filter_online)) filterMode = 3;
            else filterMode = 0;
            applyFilters();
        });

        binding.fabCreateGroup.setOnClickListener(v -> UiDialogs.showCreateGroupDialog(requireContext(),
                (name, desc, isPriv, pin) -> viewModel.createGroup(name, desc, isPriv, pin)));

        locator.getPeerConnectionManager().setGroupAuthListener((groupId, accepted, error) -> {
            if (getContext() == null) return;
            if (accepted) {
                Toast.makeText(requireContext(), "Joined private group!", Toast.LENGTH_SHORT).show();
                ((MainActivity) requireActivity()).openChatDetail(groupId, "Private Group", null);
            } else {
                Toast.makeText(requireContext(), error != null ? error : "Authentication failed", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void promptPrivatePin(GroupEntity group) {
        ServiceLocator locator = ServiceLocator.getInstance(requireContext());
        long lockSec = locator.getCryptoManager().getRemainingLockoutSeconds("client:" + group.groupId);
        if (lockSec > 0) {
            Toast.makeText(requireContext(), getString(R.string.pin_lockout_message, lockSec), Toast.LENGTH_LONG).show();
            return;
        }
        EditText pinInput = new EditText(requireContext());
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinInput.setHint(R.string.hint_group_pin);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.enter_pin_title)
                .setMessage(R.string.enter_pin_desc)
                .setView(pinInput)
                .setPositiveButton(R.string.join, (d, w) -> {
                    String pin = pinInput.getText().toString().trim();
                    if (pin.length() < 4) {
                        Toast.makeText(requireContext(), R.string.hint_group_pin, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    locator.getPeerConnectionManager().requestPrivateGroupChallenge(group, pin);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void applyFilters() {
        List<GroupEntity> filtered = new ArrayList<>();
        for (GroupEntity g : allGroups) {
            if (!searchQuery.isEmpty() && !g.name.toLowerCase().contains(searchQuery)
                    && (g.description == null || !g.description.toLowerCase().contains(searchQuery))) {
                continue;
            }
            if (filterMode == 1 && !g.isJoined) continue;
            if (filterMode == 2 && g.isJoined) continue;
            if (filterMode == 3 && g.onlineCount <= 0) continue;
            filtered.add(g);
        }
        // Always group Private Groups (Section A) then Public Groups (Section B)
        Collections.sort(filtered, (a, b) -> {
            int privCmp = Boolean.compare(b.isPrivate, a.isPrivate);
            if (privCmp != 0) return privCmp;
            if (sortByMembers) return Integer.compare(b.memberCount, a.memberCount);
            return a.name.compareToIgnoreCase(b.name);
        });
        adapter.submitList(filtered);
    }

    @Override
    public void onDestroyView() {
        ServiceLocator.getInstance(requireContext()).getPeerConnectionManager().setGroupAuthListener(null);
        super.onDestroyView();
        binding = null;
    }
}
