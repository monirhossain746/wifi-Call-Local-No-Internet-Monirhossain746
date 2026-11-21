package com.android.wificalllocal.ui.adapter;

import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.android.wificalllocal.R;
import com.android.wificalllocal.data.local.entity.PeerEntity;
import com.android.wificalllocal.databinding.ItemPeerHomeBinding;
import com.android.wificalllocal.util.AvatarLoader;

import java.util.ArrayList;
import java.util.List;

public class HomePeerAdapter extends RecyclerView.Adapter<HomePeerAdapter.VH> {

    public interface Listener {
        void onPeerClick(PeerEntity peer);
    }

    private final List<PeerEntity> items = new ArrayList<>();
    private final Listener listener;

    public HomePeerAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submitList(List<PeerEntity> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemPeerHomeBinding b = ItemPeerHomeBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        PeerEntity p = items.get(position);
        holder.b.peerName.setText(p.name);
        AvatarLoader.loadPeerAvatar(holder.b.peerAvatar, p.name, p.deviceId, p.avatarBase64);
        applyStatusDot(holder.b.peerStatusDot, p);
        holder.itemView.setOnClickListener(v -> listener.onPeerClick(p));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public static void applyStatusDot(View dot, PeerEntity p) {
        if (!p.isOnline) {
            dot.setVisibility(View.GONE);
            return;
        }
        dot.setVisibility(View.VISIBLE);
        int colorRes = R.color.status_online;
        if ("AWAY".equals(p.activeStatus)) colorRes = R.color.status_away;
        else if ("BUSY".equals(p.activeStatus) || "DND".equals(p.activeStatus)) colorRes = R.color.status_busy;
        if (dot.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) dot.getBackground().mutate())
                    .setColor(ContextCompat.getColor(dot.getContext(), colorRes));
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemPeerHomeBinding b;
        VH(ItemPeerHomeBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
