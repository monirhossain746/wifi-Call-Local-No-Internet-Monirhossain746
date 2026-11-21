package com.android.wificalllocal.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.wificalllocal.R;
import com.android.wificalllocal.data.local.entity.PeerEntity;
import com.android.wificalllocal.databinding.ItemPeerNearbyBinding;
import com.android.wificalllocal.util.AvatarLoader;

import java.util.ArrayList;
import java.util.List;

public class NearbyPeerAdapter extends RecyclerView.Adapter<NearbyPeerAdapter.VH> {

    public interface Listener {
        void onChat(PeerEntity peer);
        void onVoiceCall(PeerEntity peer);
        void onVideoCall(PeerEntity peer);
        void onToggleFriend(PeerEntity peer);
        void onBlock(PeerEntity peer);
        void onViewProfile(PeerEntity peer);
    }

    private final List<PeerEntity> items = new ArrayList<>();
    private final Listener listener;

    public NearbyPeerAdapter(Listener listener) {
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
        ItemPeerNearbyBinding b = ItemPeerNearbyBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        PeerEntity p = items.get(position);
        h.b.nearbyName.setText(p.name);
        h.b.nearbyBio.setText(p.bio != null && !p.bio.isEmpty() ? p.bio : p.connectionMode);
        h.b.nearbyIpStatus.setText(p.ipAddress + ":" + p.port + " • " + (p.isOnline ? p.activeStatus : "OFFLINE"));
        h.b.nearbyWebBadge.setVisibility(p.isWebUser ? View.VISIBLE : View.GONE);

        AvatarLoader.loadPeerAvatar(h.b.nearbyAvatar, p.name, p.deviceId, p.avatarBase64);
        HomePeerAdapter.applyStatusDot(h.b.nearbyStatusDot, p);

        h.b.btnPeerFriend.setText(p.isFriend ? R.string.btn_remove_friend : R.string.btn_add_friend);
        h.b.btnPeerVoice.setEnabled(p.isOnline);
        h.b.btnPeerVideo.setEnabled(p.isOnline);

        h.itemView.setOnClickListener(v -> listener.onViewProfile(p));
        h.b.btnPeerChat.setOnClickListener(v -> listener.onChat(p));
        h.b.btnPeerVoice.setOnClickListener(v -> listener.onVoiceCall(p));
        h.b.btnPeerVideo.setOnClickListener(v -> listener.onVideoCall(p));
        h.b.btnPeerFriend.setOnClickListener(v -> listener.onToggleFriend(p));
        h.b.btnPeerBlock.setOnClickListener(v -> listener.onBlock(p));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemPeerNearbyBinding b;
        VH(ItemPeerNearbyBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
