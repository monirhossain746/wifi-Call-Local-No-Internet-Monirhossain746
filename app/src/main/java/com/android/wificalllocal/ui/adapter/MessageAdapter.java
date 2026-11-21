package com.android.wificalllocal.ui.adapter;

import android.content.Context;
import android.util.Base64;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.android.wificalllocal.R;
import com.android.wificalllocal.data.local.entity.FileTransferEntity;
import com.android.wificalllocal.data.local.entity.MessageEntity;
import com.android.wificalllocal.databinding.ItemMessageBinding;
import com.android.wificalllocal.files.FileTransferManager;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class MessageAdapter extends ListAdapter<MessageEntity, MessageAdapter.VH> {

    private static final String PAYLOAD_TRANSFER = "payload_transfer";

    public interface Listener {
        void onMessageLongClick(MessageEntity msg, View anchor);
        void onReactionsClick(MessageEntity msg);
        void onImageClick(MessageEntity msg);
        void onFileOpen(MessageEntity msg, FileTransferEntity transfer);
        void onFileDownload(MessageEntity msg, FileTransferEntity transfer);
        void onFileCancel(FileTransferEntity transfer);
        void onPlayVoice(MessageEntity msg);
    }

    private static final DiffUtil.ItemCallback<MessageEntity> DIFF_CALLBACK = new DiffUtil.ItemCallback<MessageEntity>() {
        @Override
        public boolean areItemsTheSame(@NonNull MessageEntity oldItem, @NonNull MessageEntity newItem) {
            return Objects.equals(oldItem.messageId, newItem.messageId);
        }

        @Override
        public boolean areContentsTheSame(@NonNull MessageEntity oldItem, @NonNull MessageEntity newItem) {
            return Objects.equals(oldItem.content, newItem.content)
                    && Objects.equals(oldItem.status, newItem.status)
                    && Objects.equals(oldItem.localFilePath, newItem.localFilePath)
                    && Objects.equals(oldItem.thumbnailBase64, newItem.thumbnailBase64)
                    && Objects.equals(oldItem.reactionsJson, newItem.reactionsJson)
                    && oldItem.isDeletedForEveryone == newItem.isDeletedForEveryone
                    && oldItem.isEdited == newItem.isEdited
                    && oldItem.attachmentSize == newItem.attachmentSize;
        }
    };

    private final String myDeviceId;
    private final Listener listener;
    private final FileTransferManager fileTransferManager;
    private final Map<String, FileTransferEntity> transferMap = new HashMap<>();
    private final Gson gson = new Gson();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());

    public MessageAdapter(String myDeviceId, FileTransferManager fileTransferManager, Listener listener) {
        super(DIFF_CALLBACK);
        this.myDeviceId = myDeviceId;
        this.fileTransferManager = fileTransferManager;
        this.listener = listener;
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        String id = getItem(position).messageId;
        return id != null ? id.hashCode() : position;
    }

    public void submitMessages(List<MessageEntity> list) {
        submitList(list != null ? new java.util.ArrayList<>(list) : null);
    }

    public void submitTransfers(List<FileTransferEntity> list) {
        Set<String> changedIds = new HashSet<>();
        Map<String, FileTransferEntity> next = new HashMap<>();
        if (list != null) {
            for (FileTransferEntity t : list) {
                next.put(t.transferId, t);
                FileTransferEntity prev = transferMap.get(t.transferId);
                if (prev == null
                        || !Objects.equals(prev.state, t.state)
                        || prev.completedChunks != t.completedChunks
                        || prev.speedBytesPerSec != t.speedBytesPerSec
                        || !Objects.equals(prev.savedPath, t.savedPath)) {
                    changedIds.add(t.transferId);
                }
            }
        }
        for (String oldId : transferMap.keySet()) {
            if (!next.containsKey(oldId)) changedIds.add(oldId);
        }
        transferMap.clear();
        transferMap.putAll(next);

        if (changedIds.isEmpty()) return;
        for (int i = 0; i < getItemCount(); i++) {
            MessageEntity m = getItem(i);
            if (m.attachmentId != null && changedIds.contains(m.attachmentId)) {
                notifyItemChanged(i, PAYLOAD_TRANSFER);
            }
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemMessageBinding b = ItemMessageBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_TRANSFER)) {
            MessageEntity m = getItem(position);
            FileTransferEntity tr = m.attachmentId != null ? transferMap.get(m.attachmentId) : null;
            bindTransferUi(h, m, tr, h.itemView.getContext());
            return;
        }
        onBindViewHolder(h, position);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        MessageEntity m = getItem(position);
        Context ctx = h.itemView.getContext();
        boolean isMine = myDeviceId.equals(m.senderId);

        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) h.b.msgBubble.getLayoutParams();
        lp.gravity = isMine ? Gravity.END : Gravity.START;
        h.b.msgBubble.setLayoutParams(lp);

        int bgColor = ContextCompat.getColor(ctx, isMine ? R.color.bubble_outgoing_dark : R.color.bubble_incoming_dark);
        h.b.msgBubble.setCardBackgroundColor(bgColor);

        h.b.msgSenderName.setText(m.senderName);
        h.b.msgSenderRow.setVisibility(isMine ? View.GONE : View.VISIBLE);
        h.b.msgWebBadge.setVisibility(m.senderIsWeb ? View.VISIBLE : View.GONE);
        h.b.msgForwarded.setVisibility(m.forwardedFrom != null && !m.forwardedFrom.isEmpty() ? View.VISIBLE : View.GONE);

        if (m.replyToPreview != null && !m.replyToPreview.isEmpty()) {
            h.b.msgReplyQuote.setVisibility(View.VISIBLE);
            h.b.msgReplyQuote.setText(m.replyToPreview);
        } else {
            h.b.msgReplyQuote.setVisibility(View.GONE);
        }

        FileTransferEntity tr = m.attachmentId != null ? transferMap.get(m.attachmentId) : null;

        if (m.isDeletedForEveryone) {
            h.b.msgBodyText.setVisibility(View.VISIBLE);
            h.b.msgBodyText.setText(R.string.msg_deleted_placeholder);
            h.b.msgImageCard.setVisibility(View.GONE);
            h.b.msgAttachmentBox.setVisibility(View.GONE);
        } else {
            boolean hasBodyText = m.content != null && !m.content.isEmpty()
                    && (m.attachmentId == null || !m.content.startsWith("Shared file:"));
            h.b.msgBodyText.setVisibility(hasBodyText ? View.VISIBLE : View.GONE);
            h.b.msgBodyText.setText(m.content);
            h.b.msgBodyText.setTextColor(ContextCompat.getColor(ctx, R.color.white));

            bindImagePreview(h, m, tr, ctx);

            if (m.attachmentId != null && !m.attachmentId.isEmpty()) {
                h.b.msgAttachmentBox.setVisibility(View.VISIBLE);
                bindTransferUi(h, m, tr, ctx);
            } else {
                h.b.msgAttachmentBox.setVisibility(View.GONE);
            }
        }

        String reactionsSummary = formatReactions(m.reactionsJson);
        if (reactionsSummary != null) {
            h.b.msgReactionsBar.setVisibility(View.VISIBLE);
            h.b.msgReactionsBar.setText(reactionsSummary);
            h.b.msgReactionsBar.setOnClickListener(v -> listener.onReactionsClick(m));
        } else {
            h.b.msgReactionsBar.setVisibility(View.GONE);
        }

        String time = timeFmt.format(new Date(m.createdAt));
        String editedTag = (m.isEdited && !m.isDeletedForEveryone) ? (" " + ctx.getString(R.string.msg_edited_tag)) : "";
        String meta = (isMine ? (time + " • " + m.status) : time) + editedTag;
        h.b.msgMetaTimeStatus.setText(meta);
        h.b.msgMetaTimeStatus.setTextColor(ContextCompat.getColor(ctx, R.color.blue_200));

        h.b.msgBubble.setOnLongClickListener(v -> {
            listener.onMessageLongClick(m, v);
            return true;
        });
    }

    private void bindImagePreview(VH h, MessageEntity m, FileTransferEntity tr, Context ctx) {
        if (!"IMAGE".equals(m.type)) {
            h.b.msgImageCard.setVisibility(View.GONE);
            return;
        }

        String localPath = resolveLocalPath(m, tr);
        Object imageModel = null;
        if (FileTransferManager.isLocalFileAvailable(ctx, localPath)) {
            imageModel = localPath;
        } else if (m.attachmentId != null && fileTransferManager != null) {
            File thumbFile = fileTransferManager.getThumbnailFile(m.attachmentId);
            if (thumbFile != null && thumbFile.exists()) {
                imageModel = thumbFile;
            }
        }
        if (imageModel == null && m.thumbnailBase64 != null && !m.thumbnailBase64.isEmpty()) {
            try {
                imageModel = Base64.decode(m.thumbnailBase64, Base64.DEFAULT);
            } catch (Exception ignored) {
            }
        }

        if (imageModel == null) {
            // Never show an empty black rectangle if no image preview is available yet
            h.b.msgImageCard.setVisibility(View.GONE);
            return;
        }

        h.b.msgImageCard.setVisibility(View.VISIBLE);
        String nameLabel = m.attachmentName != null ? m.attachmentName : "Image";
        String sizeLabel = m.attachmentSize > 0 ? (" • " + FileTransferManager.formatFileSize(m.attachmentSize)) : "";
        h.b.msgImageBadge.setText(nameLabel + sizeLabel);

        if (!Objects.equals(h.loadedImageKey, m.messageId + ":" + String.valueOf(localPath != null))) {
            h.loadedImageKey = m.messageId + ":" + String.valueOf(localPath != null);
            Glide.with(ctx)
                    .load(imageModel)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .centerCrop()
                    .into(h.b.msgImagePreview);
        }

        h.b.msgImageCard.setOnClickListener(v -> listener.onImageClick(m));
    }

    private void bindTransferUi(VH h, MessageEntity m, FileTransferEntity tr, Context ctx) {
        String fileName = m.attachmentName != null && !m.attachmentName.isEmpty() ? m.attachmentName : "File";
        h.b.msgFileName.setText(fileName);
        h.b.msgFileExt.setText(FileTransferManager.getExtensionUpper(fileName));
        h.b.msgFileSize.setText(FileTransferManager.formatFileSize(m.attachmentSize));
        String senderLabel = (m.senderName != null ? m.senderName : "Peer")
                + " • " + timeFmt.format(new Date(m.createdAt));
        h.b.msgFileSenderTime.setText(senderLabel);

        // Icon or small thumbnail
        File thumbFile = (m.attachmentId != null && fileTransferManager != null)
                ? fileTransferManager.getThumbnailFile(m.attachmentId) : null;
        if (thumbFile != null && thumbFile.exists()) {
            h.b.msgFileIcon.setImageTintList(null);
            h.b.msgFileIcon.setPadding(0, 0, 0, 0);
            Glide.with(ctx).load(thumbFile).centerCrop().into(h.b.msgFileIcon);
        } else {
            h.b.msgFileIcon.setImageResource(R.drawable.ic_file);
            h.b.msgFileIcon.setImageTintList(ContextCompat.getColorStateList(ctx, R.color.cyan_400));
            int pad = Math.round(7 * ctx.getResources().getDisplayMetrics().density);
            h.b.msgFileIcon.setPadding(pad, pad, pad, pad);
        }

        String localPath = resolveLocalPath(m, tr);
        boolean localAvailable = FileTransferManager.isLocalFileAvailable(ctx, localPath);

        if ("VOICE".equals(m.type) && localAvailable) {
            h.b.msgShaBadge.setVisibility(View.VISIBLE);
            h.b.msgShaBadge.setText(R.string.file_sha_verified);
            h.b.msgShaBadge.setTextColor(ContextCompat.getColor(ctx, R.color.green_500));
            h.b.msgFileProgress.setVisibility(View.GONE);
            h.b.msgFileStatus.setText("Voice Note");
            h.b.btnFileOpen.setVisibility(View.VISIBLE);
            h.b.btnFileOpen.setText("Play");
            h.b.btnFileOpen.setOnClickListener(v -> listener.onPlayVoice(m));
            h.b.btnFileDownload.setVisibility(View.GONE);
            h.b.btnFileCancel.setVisibility(View.GONE);
            return;
        }

        h.b.btnFileOpen.setVisibility(View.VISIBLE);
        h.b.btnFileOpen.setText(R.string.btn_open_file);
        h.b.btnFileOpen.setAlpha(localAvailable ? 1.0f : 0.72f);
        h.b.btnFileOpen.setOnClickListener(v -> listener.onFileOpen(m, tr));

        h.b.btnFileDownload.setVisibility(View.VISIBLE);
        h.b.btnFileDownload.setOnClickListener(v -> listener.onFileDownload(m, tr));
        h.b.btnFileCancel.setOnClickListener(v -> {
            if (tr != null) listener.onFileCancel(tr);
        });

        h.b.msgFileProgress.setVisibility(View.VISIBLE);

        if (localAvailable) {
            h.b.msgShaBadge.setVisibility(View.VISIBLE);
            h.b.msgShaBadge.setText(R.string.file_sha_verified);
            h.b.msgShaBadge.setTextColor(ContextCompat.getColor(ctx, R.color.green_500));
            h.b.msgFileProgress.setProgress(100);
            h.b.msgFileStatus.setText(ctx.getString(R.string.file_completed, fileName));
            h.b.btnFileDownload.setText(R.string.btn_downloaded);
            h.b.btnFileDownload.setEnabled(false);
            h.b.btnFileCancel.setVisibility(View.GONE);
            return;
        }

        h.b.btnFileDownload.setEnabled(true);
        if (tr == null) {
            h.b.msgShaBadge.setVisibility(View.VISIBLE);
            h.b.msgShaBadge.setText(m.attachmentSha256 != null && !m.attachmentSha256.isEmpty()
                    ? "SHA-256: " + m.attachmentSha256.substring(0, Math.min(8, m.attachmentSha256.length())) + "…"
                    : "Pending download");
            h.b.msgShaBadge.setTextColor(ContextCompat.getColor(ctx, R.color.blue_200));
            h.b.msgFileProgress.setProgress(0);
            h.b.msgFileStatus.setText(R.string.error_download_before_open);
            h.b.btnFileDownload.setText(R.string.file_download);
            h.b.btnFileCancel.setVisibility(View.GONE);
            return;
        }

        int pct = tr.totalChunks > 0 ? (tr.completedChunks * 100 / tr.totalChunks) : 0;
        h.b.msgFileProgress.setProgress(pct);
        h.b.msgShaBadge.setVisibility(View.VISIBLE);

        switch (tr.state) {
            case "TRANSFERRING":
                h.b.msgShaBadge.setText("Verifying chunks…");
                h.b.msgShaBadge.setTextColor(ContextCompat.getColor(ctx, R.color.cyan_400));
                h.b.msgFileStatus.setText(ctx.getString(R.string.file_downloading, pct, (tr.speedBytesPerSec / 1024) + " KB/s"));
                h.b.btnFileDownload.setText(R.string.pause);
                h.b.btnFileCancel.setVisibility(View.VISIBLE);
                break;
            case "PAUSED":
                h.b.msgShaBadge.setText("Paused");
                h.b.msgShaBadge.setTextColor(ContextCompat.getColor(ctx, R.color.amber_500));
                h.b.msgFileStatus.setText(ctx.getString(R.string.file_paused, pct));
                h.b.btnFileDownload.setText(R.string.resume);
                h.b.btnFileCancel.setVisibility(View.VISIBLE);
                break;
            case "FAILED":
                h.b.msgShaBadge.setText(R.string.file_sha_failed);
                h.b.msgShaBadge.setTextColor(ContextCompat.getColor(ctx, R.color.red_500));
                h.b.msgFileStatus.setText("Transfer interrupted or checksum mismatch");
                h.b.btnFileDownload.setText(R.string.retry);
                h.b.btnFileCancel.setVisibility(View.GONE);
                break;
            case "WAITING_SEEDER":
                h.b.msgShaBadge.setText("Waiting for peer");
                h.b.msgShaBadge.setTextColor(ContextCompat.getColor(ctx, R.color.amber_500));
                h.b.msgFileStatus.setText(R.string.file_waiting_seeder);
                h.b.btnFileDownload.setText(R.string.retry);
                h.b.btnFileCancel.setVisibility(View.VISIBLE);
                break;
            default:
                h.b.msgShaBadge.setText("Ready to download");
                h.b.msgShaBadge.setTextColor(ContextCompat.getColor(ctx, R.color.blue_200));
                h.b.msgFileStatus.setText(FileTransferManager.formatFileSize(m.attachmentSize) + " • Not downloaded");
                h.b.btnFileDownload.setText(R.string.file_download);
                h.b.btnFileCancel.setVisibility(View.GONE);
                break;
        }
    }

    private String resolveLocalPath(MessageEntity m, FileTransferEntity tr) {
        if (m.localFilePath != null && !m.localFilePath.isEmpty()) return m.localFilePath;
        if (tr != null && tr.savedPath != null && !tr.savedPath.isEmpty()) return tr.savedPath;
        if (tr != null && tr.localUri != null && !tr.localUri.isEmpty()) return tr.localUri;
        return null;
    }

    private String formatReactions(String json) {
        if (json == null || json.isEmpty() || "{}".equals(json)) return null;
        try {
            Type mapType = new TypeToken<Map<String, List<Map<String, String>>>>() {}.getType();
            Map<String, List<Map<String, String>>> map = gson.fromJson(json, mapType);
            if (map == null || map.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, List<Map<String, String>>> entry : map.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    if (sb.length() > 0) sb.append("  ");
                    sb.append(entry.getKey()).append(" ").append(entry.getValue().size());
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemMessageBinding b;
        String loadedImageKey;

        VH(ItemMessageBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
