package com.android.wificalllocal.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.wificalllocal.R;
import com.android.wificalllocal.data.local.entity.GroupEntity;
import com.android.wificalllocal.databinding.ItemGroupCardBinding;

import java.util.ArrayList;
import java.util.List;

public class GroupCardAdapter extends RecyclerView.Adapter<GroupCardAdapter.VH> {

    public interface Listener {
        void onGroupAction(GroupEntity group);
    }

    private final List<GroupEntity> items = new ArrayList<>();
    private final Listener listener;

    public GroupCardAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submitList(List<GroupEntity> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemGroupCardBinding b = ItemGroupCardBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        GroupEntity g = items.get(position);
        h.b.groupCardName.setText(g.name);
        h.b.groupCardDesc.setText(g.description);
        h.b.groupCardIcon.setImageResource(g.isPrivate ? R.drawable.ic_lock : R.drawable.ic_group);
        h.b.groupCardMeta.setText(
                h.itemView.getContext().getString(R.string.group_members_online, g.memberCount, g.onlineCount)
                        + " • " + g.creatorName);

        boolean showSection = position == 0 || items.get(position - 1).isPrivate != g.isPrivate;
        if (showSection) {
            h.b.groupCardSection.setVisibility(View.VISIBLE);
            h.b.groupCardSection.setText(g.isPrivate
                    ? R.string.section_private_groups
                    : R.string.section_public_groups);
        } else {
            h.b.groupCardSection.setVisibility(View.GONE);
        }

        h.b.btnGroupJoin.setText(g.isJoined ? R.string.joined : R.string.join);
        h.b.btnGroupJoin.setOnClickListener(v -> listener.onGroupAction(g));
        h.itemView.setOnClickListener(v -> listener.onGroupAction(g));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemGroupCardBinding b;
        VH(ItemGroupCardBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
