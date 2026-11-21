package com.android.wificalllocal.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.wificalllocal.R;
import com.android.wificalllocal.data.local.entity.GroupMemberEntity;
import com.android.wificalllocal.databinding.ItemGroupMemberBinding;

import java.util.ArrayList;
import java.util.List;

public class GroupMemberAdapter extends RecyclerView.Adapter<GroupMemberAdapter.VH> {

    public interface Listener {
        void onMemberManage(GroupMemberEntity member, View anchor);
    }

    private final List<GroupMemberEntity> items = new ArrayList<>();
    private final Listener listener;
    private boolean canManage = false;

    public GroupMemberAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setCanManage(boolean canManage) {
        this.canManage = canManage;
        notifyDataSetChanged();
    }

    public void submitList(List<GroupMemberEntity> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemGroupMemberBinding b = ItemGroupMemberBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        GroupMemberEntity m = items.get(position);
        h.b.memberName.setText(m.displayName);
        h.b.memberRole.setText(m.role);
        h.b.memberOnlineDot.setVisibility(m.isOnline ? View.VISIBLE : View.INVISIBLE);
        boolean showAction = canManage && !"OWNER".equals(m.role);
        h.b.btnMemberAction.setVisibility(showAction ? View.VISIBLE : View.GONE);
        h.b.btnMemberAction.setText(R.string.edit);
        h.b.btnMemberAction.setOnClickListener(v -> listener.onMemberManage(m, v));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemGroupMemberBinding b;
        VH(ItemGroupMemberBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
