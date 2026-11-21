package com.android.wificalllocal.ui.fragment;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.android.wificalllocal.databinding.FragmentChatsBinding;
import com.android.wificalllocal.ui.MainActivity;
import com.android.wificalllocal.ui.MainViewModel;
import com.android.wificalllocal.ui.UiDialogs;
import com.android.wificalllocal.ui.adapter.ChatSummaryAdapter;

public class ChatsFragment extends Fragment {

    private FragmentChatsBinding binding;
    private MainViewModel viewModel;
    private ChatSummaryAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentChatsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);

        adapter = new ChatSummaryAdapter(chat ->
                ((MainActivity) requireActivity()).openChatDetail(chat.chatId, chat.title, chat.peerDeviceId));
        binding.chatsRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.chatsRecycler.setAdapter(adapter);

        viewModel.observeChats().observe(getViewLifecycleOwner(), list -> {
            adapter.submitList(list);
            binding.chatsEmptyText.setVisibility(list == null || list.isEmpty() ? View.VISIBLE : View.GONE);
        });

        binding.inputSearchChats.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                viewModel.setChatSearchQuery(s != null ? s.toString() : "");
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        binding.fabNewGroup.setOnClickListener(v -> UiDialogs.showCreateGroupDialog(requireContext(),
                (name, desc, isPriv, pin) -> viewModel.createGroup(name, desc, isPriv, pin)));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
