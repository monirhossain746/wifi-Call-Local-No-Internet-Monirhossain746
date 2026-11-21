package com.android.wificalllocal.ui.fragment;

import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.wificalllocal.R;
import com.android.wificalllocal.ServiceLocator;
import com.android.wificalllocal.data.local.AppDatabase;
import com.android.wificalllocal.data.local.entity.GroupEntity;
import com.android.wificalllocal.data.local.entity.GroupMemberEntity;
import com.android.wificalllocal.data.local.entity.MessageEntity;
import com.android.wificalllocal.data.local.entity.PeerEntity;
import com.android.wificalllocal.data.local.entity.UserProfileEntity;
import com.android.wificalllocal.databinding.FragmentGroupInfoBinding;
import com.android.wificalllocal.databinding.ItemSimpleRowBinding;
import com.android.wificalllocal.ui.MainActivity;
import com.android.wificalllocal.ui.adapter.GroupMemberAdapter;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class GroupInfoFragment extends Fragment {

    private static final String ARG_GROUP_ID = "group_id";
    private FragmentGroupInfoBinding binding;
    private ServiceLocator locator;
    private String groupId;
    private GroupEntity currentGroup;
    private GroupMemberAdapter memberAdapter;

    public static GroupInfoFragment newInstance(String groupId) {
        GroupInfoFragment f = new GroupInfoFragment();
        Bundle b = new Bundle();
        b.putString(ARG_GROUP_ID, groupId);
        f.setArguments(b);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentGroupInfoBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    private boolean isOwnerOrAdmin = false;

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        locator = ServiceLocator.getInstance(requireContext());
        groupId = getArguments() != null ? getArguments().getString(ARG_GROUP_ID) : AppDatabase.EVERYONE_ROOM_ID;
        String myId = locator.getPreferencesManager().getOrCreateDeviceId();

        memberAdapter = new GroupMemberAdapter((member, anchor) -> {
            boolean isMemberOwner = currentGroup != null && member.deviceId.equals(currentGroup.ownerId);
            if (isMemberOwner) {
                Toast.makeText(requireContext(), "Group creator role cannot be changed.", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean isMemberAdmin = "ADMIN".equalsIgnoreCase(member.role) || "OWNER".equalsIgnoreCase(member.role);
            String roleAction = isMemberAdmin
                    ? getString(R.string.group_demote_admin)
                    : getString(R.string.group_promote_admin);
            String[] opts = {roleAction, getString(R.string.group_remove_member)};
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(member.displayName)
                    .setItems(opts, (d, which) -> AppDatabase.DB_EXECUTOR.execute(() -> {
                        if (which == 0) {
                            member.role = isMemberAdmin ? "MEMBER" : "ADMIN";
                            locator.getDatabase().groupDao().upsertMember(member);
                            postSystemMessage(member.displayName + (isMemberAdmin ? " is no longer an admin" : " is now an admin"));
                        } else {
                            locator.getDatabase().groupDao().removeMember(groupId, member.deviceId);
                            postSystemMessage(member.displayName + " was removed from the group");
                        }
                        if (currentGroup != null) locator.getPeerConnectionManager().syncGroupMetadata(currentGroup);
                    }))
                    .show();
        });

        binding.groupMembersRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.groupMembersRecycler.setAdapter(memberAdapter);

        locator.getDatabase().groupDao().observeGroup(groupId).observe(getViewLifecycleOwner(), group -> {
            if (group == null) return;
            currentGroup = group;
            binding.groupInfoName.setText(group.name);
            binding.groupInfoDesc.setText(group.description);
            binding.switchGroupMute.setChecked(group.isMuted);
            binding.switchAdminsOnly.setChecked(group.adminsOnlySend);
            updateAdminUi(myId, group, null);
        });

        locator.getDatabase().groupDao().observeMembers(groupId).observe(getViewLifecycleOwner(), members -> {
            memberAdapter.submitList(members);
            if (currentGroup != null) {
                updateAdminUi(myId, currentGroup, members);
            }
        });

        loadSharedFiles();

        binding.btnGroupInfoBack.setOnClickListener(v -> ((MainActivity) requireActivity()).popCurrentTab());
        binding.btnGroupQr.setOnClickListener(v -> ((MainActivity) requireActivity()).openQrShow());

        binding.switchGroupMute.setOnCheckedChangeListener((b, checked) -> AppDatabase.DB_EXECUTOR.execute(() ->
                locator.getDatabase().groupDao().setMuted(groupId, checked)));

        binding.switchAdminsOnly.setOnCheckedChangeListener((b, checked) -> {
            if (currentGroup != null && currentGroup.adminsOnlySend != checked) {
                currentGroup.adminsOnlySend = checked;
                currentGroup.updatedAt = System.currentTimeMillis();
                AppDatabase.DB_EXECUTOR.execute(() -> {
                    locator.getDatabase().groupDao().upsertGroup(currentGroup);
                    locator.getPeerConnectionManager().syncGroupMetadata(currentGroup);
                });
            }
        });

        binding.btnRenameGroup.setOnClickListener(v -> {
            EditText input = new EditText(requireContext());
            if (currentGroup != null) input.setText(currentGroup.name);
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.edit)
                    .setView(input)
                    .setPositiveButton(R.string.save, (d, w) -> {
                        String newName = input.getText().toString().trim();
                        if (newName.length() >= 2 && currentGroup != null) {
                            currentGroup.name = newName;
                            currentGroup.updatedAt = System.currentTimeMillis();
                            AppDatabase.DB_EXECUTOR.execute(() -> {
                                locator.getDatabase().groupDao().upsertGroup(currentGroup);
                                postSystemMessage("Group renamed to " + newName);
                                locator.getPeerConnectionManager().syncGroupMetadata(currentGroup);
                            });
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });

        binding.btnRotatePin.setOnClickListener(v -> {
            EditText pinInput = new EditText(requireContext());
            pinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            pinInput.setHint(R.string.hint_group_pin);
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.group_rotate_pin)
                    .setView(pinInput)
                    .setPositiveButton(R.string.save, (d, w) -> {
                        String newPin = pinInput.getText().toString().trim();
                        if (newPin.length() >= 4 && currentGroup != null) {
                            AppDatabase.DB_EXECUTOR.execute(() -> {
                                try {
                                    String newSalt = locator.getCryptoManager().generateSaltBase64();
                                    String newEncSalt = locator.getCryptoManager().generateSaltBase64();
                                    currentGroup.pinSalt = newSalt;
                                    currentGroup.pinHash = locator.getCryptoManager().hashPinWithSalt(newPin, newSalt);
                                    currentGroup.encryptionKeySalt = newEncSalt;
                                    currentGroup.keyVersion++;
                                    currentGroup.updatedAt = System.currentTimeMillis();
                                    locator.getDatabase().groupDao().upsertGroup(currentGroup);
                                    locator.getPeerConnectionManager().cacheGroupDerivedKey(groupId, newPin, newEncSalt);
                                    locator.getPeerConnectionManager().syncGroupMetadata(currentGroup);
                                } catch (Exception ignored) {
                                }
                            });
                            Toast.makeText(requireContext(), "PIN rotated & group re-keyed", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });

        binding.btnGroupAddMember.setOnClickListener(v -> showAddMemberDialog());

        binding.btnLeaveGroup.setOnClickListener(v -> {
            if (AppDatabase.EVERYONE_ROOM_ID.equals(groupId)) {
                Toast.makeText(requireContext(), "Everyone Room cannot be left", Toast.LENGTH_SHORT).show();
                return;
            }
            AppDatabase.DB_EXECUTOR.execute(() -> {
                UserProfileEntity me = locator.getDatabase().userProfileDao().getProfileSync();
                String myName = me != null ? me.name : "Peer";
                locator.getDatabase().groupDao().setJoined(groupId, false);
                locator.getDatabase().groupDao().removeMember(groupId, myId);
                postSystemMessage(myName + " left the group");
                if (currentGroup != null) locator.getPeerConnectionManager().syncGroupMetadata(currentGroup);
            });
            ((MainActivity) requireActivity()).popCurrentTab();
        });
    }

    private void showAddMemberDialog() {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            List<PeerEntity> peers = locator.getDatabase().peerDao().getAllPeersSync();
            requireActivity().runOnUiThread(() -> {
                if (peers.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.empty_nearby_home, Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] names = new String[peers.size()];
                for (int i = 0; i < peers.size(); i++) names[i] = peers.get(i).name;
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.group_add_member)
                        .setItems(names, (d, idx) -> {
                            PeerEntity p = peers.get(idx);
                            AppDatabase.DB_EXECUTOR.execute(() -> {
                                locator.getDatabase().groupDao().upsertMember(
                                        new GroupMemberEntity(groupId, p.deviceId, p.name, "MEMBER", p.isOnline, System.currentTimeMillis()));
                                postSystemMessage(p.name + " was added to the group");
                                if (currentGroup != null) locator.getPeerConnectionManager().syncGroupMetadata(currentGroup);
                            });
                        })
                        .show();
            });
        });
    }

    private void loadSharedFiles() {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            List<MessageEntity> files = locator.getDatabase().messageDao().getSharedFilesForChatSync(groupId);
            requireActivity().runOnUiThread(() -> {
                if (binding == null) return;
                binding.groupSharedFilesRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
                binding.groupSharedFilesRecycler.setAdapter(new SharedFilesAdapter(files));
            });
        });
    }

    private void postSystemMessage(String text) {
        MessageEntity sys = new MessageEntity(
                UUID.randomUUID().toString(), groupId, "system", "System", false,
                "SYSTEM", text, null, null, null, null, null, null, 0L, null, null,
                "SENT", System.currentTimeMillis(), 0, System.currentTimeMillis(),
                false, false, "{}", false
        );
        locator.getPeerConnectionManager().sendMessage(groupId, null, sys, null);
    }

    private void updateAdminUi(String myId, GroupEntity group, @Nullable List<GroupMemberEntity> members) {
        boolean admin = myId.equals(group.ownerId);
        if (!admin && members != null) {
            for (GroupMemberEntity m : members) {
                if (myId.equals(m.deviceId) && ("OWNER".equalsIgnoreCase(m.role) || "ADMIN".equalsIgnoreCase(m.role))) {
                    admin = true;
                    break;
                }
            }
        }
        isOwnerOrAdmin = admin;
        binding.btnRotatePin.setVisibility(group.isPrivate && isOwnerOrAdmin ? View.VISIBLE : View.GONE);
        binding.switchAdminsOnly.setEnabled(isOwnerOrAdmin);
        binding.btnRenameGroup.setVisibility(isOwnerOrAdmin ? View.VISIBLE : View.GONE);
        memberAdapter.setCanManage(isOwnerOrAdmin);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private static class SharedFilesAdapter extends RecyclerView.Adapter<SharedFilesAdapter.VH> {
        private final List<MessageEntity> items;
        SharedFilesAdapter(List<MessageEntity> items) { this.items = items != null ? items : new ArrayList<>(); }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(ItemSimpleRowBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            MessageEntity m = items.get(pos);
            h.b.rowTitle.setText(m.attachmentName);
            h.b.rowSubtitle.setText(m.senderName + " • " + (m.attachmentSize / 1024) + " KB");
            h.b.rowActionBtn.setVisibility(View.GONE);
        }
        @Override public int getItemCount() { return items.size(); }
        static class VH extends RecyclerView.ViewHolder {
            final ItemSimpleRowBinding b;
            VH(ItemSimpleRowBinding b) { super(b.getRoot()); this.b = b; }
        }
    }
}
