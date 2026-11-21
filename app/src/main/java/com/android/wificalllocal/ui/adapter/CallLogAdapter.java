package com.android.wificalllocal.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.wificalllocal.R;
import com.android.wificalllocal.data.local.entity.CallLogEntity;
import com.android.wificalllocal.databinding.ItemCallLogBinding;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CallLogAdapter extends RecyclerView.Adapter<CallLogAdapter.VH> {

    public interface Listener {
        void onCallBack(CallLogEntity log);
        void onSaveContact(CallLogEntity log);
        void onAddToSaved(CallLogEntity log);
        void onCopyAddress(CallLogEntity log);
        void onSendSms(CallLogEntity log);
    }

    private final List<CallLogEntity> items = new ArrayList<>();
    private final Listener listener;
    private final SimpleDateFormat fmt = new SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault());

    public CallLogAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submitList(List<CallLogEntity> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemCallLogBinding b = ItemCallLogBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        CallLogEntity log = items.get(position);
        h.b.callLogName.setText(log.peerName);
        h.b.callLogIcon.setImageResource(log.callType.contains("VIDEO") ? R.drawable.ic_video : R.drawable.ic_call);
        String meta = log.direction + " • " + log.status + " • " + log.durationSeconds + "s • " + fmt.format(new Date(log.timestamp));
        h.b.callLogMeta.setText(meta);

        h.b.btnCallBack.setOnClickListener(v -> listener.onCallBack(log));
        h.b.btnSaveContact.setOnClickListener(v -> listener.onSaveContact(log));
        h.b.btnAddSaved.setOnClickListener(v -> listener.onAddToSaved(log));
        h.b.btnCopyAddress.setOnClickListener(v -> listener.onCopyAddress(log));
        h.b.btnSendSms.setOnClickListener(v -> listener.onSendSms(log));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemCallLogBinding b;
        VH(ItemCallLogBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
