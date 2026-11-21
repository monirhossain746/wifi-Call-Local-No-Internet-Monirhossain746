package com.android.wificalllocal.ui.fragment;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.android.wificalllocal.R;
import com.android.wificalllocal.data.local.entity.CallLogEntity;
import com.android.wificalllocal.databinding.FragmentCallsBinding;
import com.android.wificalllocal.ui.MainActivity;
import com.android.wificalllocal.ui.MainViewModel;
import com.android.wificalllocal.ui.adapter.CallLogAdapter;

public class CallsFragment extends Fragment {

    private FragmentCallsBinding binding;
    private MainViewModel viewModel;
    private CallLogAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentCallsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);

        adapter = new CallLogAdapter(new CallLogAdapter.Listener() {
            @Override
            public void onCallBack(CallLogEntity log) {
                ((MainActivity) requireActivity()).startOutgoingCall(log.peerId, log.peerName, log.peerIp, log.callType);
            }

            @Override
            public void onSaveContact(CallLogEntity log) {
                Intent insert = new Intent(ContactsContract.Intents.Insert.ACTION);
                insert.setType(ContactsContract.RawContacts.CONTENT_TYPE);
                insert.putExtra(ContactsContract.Intents.Insert.NAME, log.peerName);
                insert.putExtra(ContactsContract.Intents.Insert.NOTES, "Wifi Call Local ID: " + log.peerId + " IP: " + log.peerIp);
                try {
                    startActivity(insert);
                } catch (Exception e) {
                    Toast.makeText(requireContext(), "Contacts app unavailable", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onAddToSaved(CallLogEntity log) {
                viewModel.saveContactLocally(log.peerName, log.peerId, log.peerIp, "From Call Log");
                Toast.makeText(requireContext(), R.string.settings_saved_contacts, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onCopyAddress(CallLogEntity log) {
                ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("Address", log.peerIp != null && !log.peerIp.isEmpty() ? log.peerIp : log.peerId));
                    Toast.makeText(requireContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onSendSms(CallLogEntity log) {
                Intent sms = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"));
                sms.putExtra("sms_body", "Join me on Wifi Call Local (" + log.peerIp + ")");
                try {
                    startActivity(sms);
                } catch (Exception e) {
                    Toast.makeText(requireContext(), "SMS app unavailable", Toast.LENGTH_SHORT).show();
                }
            }
        });

        binding.callsRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.callsRecycler.setAdapter(adapter);

        viewModel.observeCallLogs().observe(getViewLifecycleOwner(), list -> {
            adapter.submitList(list);
            binding.callsEmptyText.setVisibility(list == null || list.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
