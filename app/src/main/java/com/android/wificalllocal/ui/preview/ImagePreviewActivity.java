package com.android.wificalllocal.ui.preview;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.android.wificalllocal.R;
import com.android.wificalllocal.ServiceLocator;
import com.android.wificalllocal.data.local.AppDatabase;
import com.android.wificalllocal.data.local.entity.FileTransferEntity;
import com.android.wificalllocal.data.local.entity.MessageEntity;
import com.android.wificalllocal.databinding.ActivityImagePreviewBinding;
import com.android.wificalllocal.databinding.ItemImagePreviewPageBinding;
import com.android.wificalllocal.files.FileTransferManager;
import com.bumptech.glide.Glide;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Full-screen zoomable multi-image preview activity.
 * Loads cached local original when available, or lightweight thumbnail without auto-downloading the full file.
 */
public class ImagePreviewActivity extends AppCompatActivity {

    public static final String EXTRA_CHAT_ID = "chat_id";
    public static final String EXTRA_MESSAGE_ID = "message_id";

    private ActivityImagePreviewBinding binding;
    private ServiceLocator locator;
    private final List<MessageEntity> imageMessages = new ArrayList<>();
    private final Map<String, FileTransferEntity> transferMap = new HashMap<>();
    private PreviewPagerAdapter pagerAdapter;
    private String initialMessageId;
    private boolean initialPositioned = false;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault());

    public static void launch(Context context, String chatId, String messageId) {
        Intent i = new Intent(context, ImagePreviewActivity.class);
        i.putExtra(EXTRA_CHAT_ID, chatId);
        i.putExtra(EXTRA_MESSAGE_ID, messageId);
        context.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityImagePreviewBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        locator = ServiceLocator.getInstance(this);
        String chatId = getIntent().getStringExtra(EXTRA_CHAT_ID);
        initialMessageId = getIntent().getStringExtra(EXTRA_MESSAGE_ID);

        ViewCompat.setOnApplyWindowInsetsListener(binding.previewRoot, (v, windowInsets) -> {
            Insets sys = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            binding.previewTopBar.setPadding(
                    sys.left + dp(12),
                    sys.top + dp(10),
                    sys.right + dp(12),
                    dp(10)
            );
            binding.previewBottomBar.setPadding(
                    sys.left + dp(16),
                    dp(14),
                    sys.right + dp(16),
                    sys.bottom + dp(14)
            );
            return WindowInsetsCompat.CONSUMED;
        });

        pagerAdapter = new PreviewPagerAdapter();
        binding.previewPager.setAdapter(pagerAdapter);
        binding.previewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateOverlayForPosition(position);
            }
        });

        binding.btnPreviewClose.setOnClickListener(v -> finish());
        binding.btnPreviewDownload.setOnClickListener(v -> handleDownloadClick());

        if (chatId == null || chatId.isEmpty()) {
            finish();
            return;
        }

        locator.getDatabase().fileTransferDao().observeAll().observe(this, list -> {
            transferMap.clear();
            if (list != null) {
                for (FileTransferEntity t : list) {
                    transferMap.put(t.transferId, t);
                }
            }
            pagerAdapter.notifyDataSetChanged();
            updateOverlayForPosition(binding.previewPager.getCurrentItem());
        });

        AppDatabase.DB_EXECUTOR.execute(() -> {
            List<MessageEntity> all = locator.getDatabase().messageDao().getMessagesForChatSync(chatId);
            List<MessageEntity> filtered = new ArrayList<>();
            int targetIdx = 0;
            if (all != null) {
                for (MessageEntity m : all) {
                    if (!m.isDeletedForEveryone && "IMAGE".equals(m.type)) {
                        if (m.messageId.equals(initialMessageId)) {
                            targetIdx = filtered.size();
                        }
                        filtered.add(m);
                    }
                }
            }
            final int scrollTarget = targetIdx;
            runOnUiThread(() -> {
                imageMessages.clear();
                imageMessages.addAll(filtered);
                pagerAdapter.notifyDataSetChanged();
                if (imageMessages.isEmpty()) {
                    finish();
                    return;
                }
                if (!initialPositioned) {
                    initialPositioned = true;
                    binding.previewPager.setCurrentItem(scrollTarget, false);
                }
                updateOverlayForPosition(binding.previewPager.getCurrentItem());
            });
        });
    }

    private void updateOverlayForPosition(int position) {
        if (position < 0 || position >= imageMessages.size()) return;
        MessageEntity msg = imageMessages.get(position);
        FileTransferEntity tr = msg.attachmentId != null ? transferMap.get(msg.attachmentId) : null;

        String title = (msg.attachmentName != null && !msg.attachmentName.isEmpty())
                ? msg.attachmentName : "Image";
        binding.previewFilename.setText(title);

        String sizeStr = msg.attachmentSize > 0 ? FileTransferManager.formatFileSize(msg.attachmentSize) : "";
        String meta = (msg.senderName != null ? msg.senderName : "Peer")
                + " • " + timeFmt.format(new Date(msg.createdAt))
                + (sizeStr.isEmpty() ? "" : (" • " + sizeStr));
        binding.previewMeta.setText(meta);

        binding.previewCounter.setText(getString(R.string.image_count_format, position + 1, imageMessages.size()));
        binding.previewCounter.setVisibility(imageMessages.size() > 1 ? View.VISIBLE : View.GONE);

        String localPath = resolveLocalPath(msg, tr);
        boolean isCached = FileTransferManager.isLocalFileAvailable(this, localPath);

        if (isCached) {
            binding.previewSourceHint.setText(R.string.preview_cached_badge);
            binding.btnPreviewDownload.setText(R.string.btn_downloaded);
            binding.btnPreviewDownload.setEnabled(false);
            binding.btnPreviewDownload.setVisibility(View.VISIBLE);
        } else if (tr != null && "TRANSFERRING".equals(tr.state)) {
            int pct = tr.totalChunks > 0 ? (tr.completedChunks * 100 / tr.totalChunks) : 0;
            binding.previewSourceHint.setText(getString(R.string.file_downloading, pct, (tr.speedBytesPerSec / 1024) + " KB/s"));
            binding.btnPreviewDownload.setText(R.string.pause);
            binding.btnPreviewDownload.setEnabled(true);
            binding.btnPreviewDownload.setVisibility(View.VISIBLE);
        } else {
            binding.previewSourceHint.setText(R.string.preview_thumbnail_badge);
            binding.btnPreviewDownload.setText(R.string.file_download);
            binding.btnPreviewDownload.setEnabled(msg.attachmentId != null && !msg.attachmentId.isEmpty());
            binding.btnPreviewDownload.setVisibility(View.VISIBLE);
        }
    }

    private void handleDownloadClick() {
        int pos = binding.previewPager.getCurrentItem();
        if (pos < 0 || pos >= imageMessages.size()) return;
        MessageEntity msg = imageMessages.get(pos);
        if (msg.attachmentId == null || msg.attachmentId.isEmpty()) return;

        FileTransferEntity tr = transferMap.get(msg.attachmentId);
        String localPath = resolveLocalPath(msg, tr);
        if (FileTransferManager.isLocalFileAvailable(this, localPath)) {
            Toast.makeText(this, R.string.file_already_downloaded, Toast.LENGTH_SHORT).show();
            return;
        }
        if (tr != null && "TRANSFERRING".equals(tr.state)) {
            locator.getFileTransferManager().pauseDownload(msg.attachmentId);
        } else {
            locator.getFileTransferManager().enqueueDownload(msg.attachmentId);
            Toast.makeText(this, getString(R.string.file_downloading, 0, "0 KB/s"), Toast.LENGTH_SHORT).show();
        }
    }

    private String resolveLocalPath(MessageEntity msg, FileTransferEntity tr) {
        if (msg.localFilePath != null && !msg.localFilePath.isEmpty()) return msg.localFilePath;
        if (tr != null && tr.savedPath != null && !tr.savedPath.isEmpty()) return tr.savedPath;
        if (tr != null && tr.localUri != null && !tr.localUri.isEmpty()) return tr.localUri;
        return null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private class PreviewPagerAdapter extends RecyclerView.Adapter<PreviewPagerAdapter.PageVH> {

        @NonNull
        @Override
        public PageVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemImagePreviewPageBinding pb = ItemImagePreviewPageBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            return new PageVH(pb);
        }

        @Override
        public void onBindViewHolder(@NonNull PageVH holder, int position) {
            MessageEntity msg = imageMessages.get(position);
            FileTransferEntity tr = msg.attachmentId != null ? transferMap.get(msg.attachmentId) : null;
            String localPath = resolveLocalPath(msg, tr);

            holder.pb.zoomImageView.resetZoom();

            if (FileTransferManager.isLocalFileAvailable(holder.itemView.getContext(), localPath)) {
                Glide.with(holder.itemView.getContext())
                        .load(localPath)
                        .into(holder.pb.zoomImageView);
                return;
            }

            if (msg.attachmentId != null) {
                File thumbFile = locator.getFileTransferManager().getThumbnailFile(msg.attachmentId);
                if (thumbFile != null && thumbFile.exists()) {
                    Glide.with(holder.itemView.getContext())
                            .load(thumbFile)
                            .into(holder.pb.zoomImageView);
                    return;
                }
            }

            if (msg.thumbnailBase64 != null && !msg.thumbnailBase64.isEmpty()) {
                try {
                    byte[] bytes = Base64.decode(msg.thumbnailBase64, Base64.DEFAULT);
                    Glide.with(holder.itemView.getContext())
                            .load(bytes)
                            .into(holder.pb.zoomImageView);
                    return;
                } catch (Exception ignored) {
                }
            }

            holder.pb.zoomImageView.setImageResource(R.drawable.ic_file);
        }

        @Override
        public int getItemCount() {
            return imageMessages.size();
        }

        class PageVH extends RecyclerView.ViewHolder {
            final ItemImagePreviewPageBinding pb;

            PageVH(ItemImagePreviewPageBinding pb) {
                super(pb.getRoot());
                this.pb = pb;
            }
        }
    }
}
