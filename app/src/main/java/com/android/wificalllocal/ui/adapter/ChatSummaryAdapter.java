package com.android.wificalllocal.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.wificalllocal.data.local.entity.ChatEntity;
import com.android.wificalllocal.databinding.ItemChatSummaryBinding;
import com.android.wificalllocal.util.AvatarHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChatSummaryAdapter extends RecyclerView.Adapter<ChatSummaryAdapter.VH> {

    public interface Listener {
        void onChatSelected(ChatEntity chat);
    }

    private final List<ChatEntity> items = new ArrayList<>();
    private final Listener listener;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());

    public ChatSummaryAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submitList(List<ChatEntity> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemChatSummaryBinding b = ItemChatSummaryBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ChatEntity c = items.get(position);
        h.b.chatRowTitle.setText(c.title);
        h.b.chatRowPreview.setText(c.lastMessagePreview);
        h.b.chatRowTime.setText(c.lastMessageTimestamp > 0 ? timeFmt.format(new Date(c.lastMessageTimestamp)) : "");
        if (c.unreadCount > 0) {
            h.b.chatRowUnread.setVisibility(View.VISIBLE);
            h.b.chatRowUnread.setText(String.valueOf(c.unreadCount));
        } else {
            h.b.chatRowUnread.setVisibility(View.GONE);
        }
        h.b.chatRowAvatar.setImageBitmap(AvatarHelper.createLetterAvatar(c.title, c.chatId));
        h.itemView.setOnClickListener(v -> listener.onChatSelected(c));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemChatSummaryBinding b;
        VH(ItemChatSummaryBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
