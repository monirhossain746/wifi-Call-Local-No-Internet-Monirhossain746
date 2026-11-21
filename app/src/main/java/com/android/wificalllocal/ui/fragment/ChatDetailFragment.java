package com.android.wificalllocal.ui.fragment;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.android.wificalllocal.R;
import com.android.wificalllocal.ServiceLocator;
import com.android.wificalllocal.control.AppControlManager;
import com.android.wificalllocal.data.local.AppDatabase;
import com.android.wificalllocal.data.local.entity.ChatEntity;
import com.android.wificalllocal.data.local.entity.FileTransferEntity;
import com.android.wificalllocal.data.local.entity.GroupEntity;
import com.android.wificalllocal.data.local.entity.GroupMemberEntity;
import com.android.wificalllocal.data.local.entity.MessageEntity;
import com.android.wificalllocal.data.local.entity.PeerEntity;
import com.android.wificalllocal.databinding.FragmentChatDetailBinding;
import com.android.wificalllocal.network.PeerConnectionManager;
import com.android.wificalllocal.ui.ChatDetailViewModel;
import com.android.wificalllocal.ui.MainActivity;
import com.android.wificalllocal.ui.adapter.MessageAdapter;
import com.android.wificalllocal.ui.preview.ImagePreviewActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ChatDetailFragment extends Fragment {

    private static final String ARG_CHAT_ID = "chat_id";
    private static final String ARG_TITLE = "title";
    private static final String ARG_PEER_ID = "peer_id";
    private static final String[] EMOJI_CHOICES = {"👍", "❤️", "😂", "😮", "😢", "🔥", "👏", "🎉"};

    private FragmentChatDetailBinding binding;
    private ChatDetailViewModel viewModel;
    private ServiceLocator locator;
    private MessageAdapter adapter;

    private String chatId;
    private String chatTitle;
    private String peerId;
    private boolean isGroup;
    private boolean isGroupAdminOrOwner = false;
    private PeerEntity currentDmPeer;
    private GroupEntity currentGroup;
    private MessageEntity pendingReply;

    private MediaRecorder recorder;
    private File currentVoiceFile;
    private boolean isRecording = false;
    private MediaPlayer voicePlayer;

    private final ActivityResultLauncher<PickVisualMediaRequest> pickMediaLauncher =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) viewModel.sendFileAttachment(uri, null);
            });

    private final ActivityResultLauncher<String[]> openDocLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) viewModel.sendFileAttachment(uri, null);
            });

    private final ActivityResultLauncher<String> micPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) toggleVoiceRecording();
                else Toast.makeText(requireContext(), R.string.perm_microphone, Toast.LENGTH_SHORT).show();
            });

    public static ChatDetailFragment newInstance(String chatId, String title, String peerId) {
        ChatDetailFragment f = new ChatDetailFragment();
        Bundle b = new Bundle();
        b.putString(ARG_CHAT_ID, chatId);
        b.putString(ARG_TITLE, title);
        b.putString(ARG_PEER_ID, peerId);
        f.setArguments(b);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentChatDetailBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        locator = ServiceLocator.getInstance(requireContext());
        chatId = getArguments() != null ? getArguments().getString(ARG_CHAT_ID) : AppDatabase.EVERYONE_ROOM_ID;
        chatTitle = getArguments() != null ? getArguments().getString(ARG_TITLE) : "Chat";
        peerId = getArguments() != null ? getArguments().getString(ARG_PEER_ID) : null;
        isGroup = peerId == null || !chatId.startsWith("dm:");

        viewModel = new ViewModelProvider(this).get(ChatDetailViewModel.class);
        viewModel.init(chatId, peerId);

        binding.chatTitle.setText(chatTitle);
        binding.chatSubtitle.setText(isGroup ? "Local Group" : "Direct Peer");

        AppControlManager.AppGateState gate = locator.getAppControlManager().getLastState();
        boolean groupCallsAllowed = gate == null || gate.groupCallsEnabled;
        int callVis = (!isGroup || groupCallsAllowed) ? View.VISIBLE : View.GONE;
        binding.btnChatVoiceCall.setVisibility(callVis);
        binding.btnChatVideoCall.setVisibility(callVis);

        String myId = locator.getPreferencesManager().getOrCreateDeviceId();
        adapter = new MessageAdapter(myId, locator.getFileTransferManager(), new MessageAdapter.Listener() {
            @Override
            public void onMessageLongClick(MessageEntity msg, View anchor) {
                showMessageOptions(msg);
            }

            @Override
            public void onReactionsClick(MessageEntity msg) {
                showWhoReactedDialog(msg);
            }

            @Override
            public void onImageClick(MessageEntity msg) {
                ImagePreviewActivity.launch(requireContext(), chatId, msg.messageId);
            }

            @Override
            public void onFileOpen(MessageEntity msg, FileTransferEntity tr) {
                String path = resolveLocalFilePath(msg, tr);
                if (!com.android.wificalllocal.files.FileTransferManager.isLocalFileAvailable(requireContext(), path)) {
                    Toast.makeText(requireContext(), R.string.error_download_before_open, Toast.LENGTH_SHORT).show();
                    return;
                }
                String mime = msg.attachmentMime != null && !msg.attachmentMime.isEmpty()
                        ? msg.attachmentMime
                        : (tr != null && tr.mimeType != null ? tr.mimeType : "application/octet-stream");
                String name = msg.attachmentName != null ? msg.attachmentName : (tr != null ? tr.fileName : "file");
                openLocalFile(path, mime, name);
            }

            @Override
            public void onFileDownload(MessageEntity msg, FileTransferEntity tr) {
                String path = resolveLocalFilePath(msg, tr);
                if (com.android.wificalllocal.files.FileTransferManager.isLocalFileAvailable(requireContext(), path)) {
                    Toast.makeText(requireContext(), R.string.file_already_downloaded, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (tr == null) {
                    Toast.makeText(requireContext(), R.string.file_waiting_seeder, Toast.LENGTH_SHORT).show();
                    return;
                }
                if ("TRANSFERRING".equals(tr.state)) {
                    locator.getFileTransferManager().pauseDownload(tr.transferId);
                } else {
                    locator.getFileTransferManager().enqueueDownload(tr.transferId);
                }
            }

            @Override
            public void onFileCancel(FileTransferEntity tr) {
                locator.getFileTransferManager().cancelDownload(tr.transferId);
            }

            @Override
            public void onPlayVoice(MessageEntity msg) {
                playVoiceNote(msg.localFilePath);
            }
        });

        LinearLayoutManager lm = new LinearLayoutManager(requireContext());
        lm.setStackFromEnd(true);
        binding.messagesRecycler.setLayoutManager(lm);
        binding.messagesRecycler.setItemAnimator(null);
        binding.messagesRecycler.setAdapter(adapter);
        binding.messagesRecycler.addOnLayoutChangeListener((v1, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom < oldBottom && adapter.getItemCount() > 0) {
                binding.messagesRecycler.post(() ->
                        binding.messagesRecycler.smoothScrollToPosition(adapter.getItemCount() - 1));
            }
        });

        viewModel.observeMessages().observe(getViewLifecycleOwner(), list -> {
            adapter.submitMessages(list);
            if (list != null && !list.isEmpty()) {
                binding.messagesRecycler.post(() -> {
                    if (binding != null && adapter.getItemCount() > 0) {
                        binding.messagesRecycler.scrollToPosition(adapter.getItemCount() - 1);
                    }
                });
            }
        });

        viewModel.observeTransfers().observe(getViewLifecycleOwner(), transfers -> adapter.submitTransfers(transfers));

        if (isGroup) {
            viewModel.observeGroup().observe(getViewLifecycleOwner(), group -> {
                if (group != null) {
                    currentGroup = group;
                    chatTitle = group.name;
                    binding.chatTitle.setText(group.name);
                    binding.chatSubtitle.setText(getString(R.string.group_members_online, group.memberCount, group.onlineCount));
                    if (myId.equals(group.ownerId)) {
                        isGroupAdminOrOwner = true;
                    }
                    updateComposerForAdminRestriction(group);
                }
            });
            viewModel.observeGroupMembers().observe(getViewLifecycleOwner(), members -> {
                boolean admin = currentGroup != null && myId.equals(currentGroup.ownerId);
                if (!admin && members != null) {
                    for (GroupMemberEntity gm : members) {
                        if (myId.equals(gm.deviceId) && ("OWNER".equalsIgnoreCase(gm.role) || "ADMIN".equalsIgnoreCase(gm.role))) {
                            admin = true;
                            break;
                        }
                    }
                }
                isGroupAdminOrOwner = admin;
                if (currentGroup != null) {
                    updateComposerForAdminRestriction(currentGroup);
                }
            });
            refreshActiveGroupCallBanner();
            locator.getPeerConnectionManager().setActiveGroupCallListener(() -> {
                if (isAdded() && binding != null) {
                    refreshActiveGroupCallBanner();
                }
            });
        } else {
            viewModel.observePeer().observe(getViewLifecycleOwner(), peer -> {
                currentDmPeer = peer;
                if (peer != null && binding != null) {
                    binding.chatTitle.setText(peer.name);
                    String origin = peer.isWebUser ? "Web Browser" : peer.connectionMode;
                    String status = peer.isOnline ? peer.activeStatus : "OFFLINE";
                    binding.chatSubtitle.setText(status + " • " + origin + " • " + peer.ipAddress);
                }
            });
        }

        setupListeners();
    }

    private void updateComposerForAdminRestriction(GroupEntity group) {
        if (binding == null) return;
        if (group.adminsOnlySend) {
            binding.adminsOnlyNotice.setVisibility(isGroupAdminOrOwner ? View.GONE : View.VISIBLE);
            binding.composerBar.setVisibility(isGroupAdminOrOwner ? View.VISIBLE : View.GONE);
        } else {
            binding.adminsOnlyNotice.setVisibility(View.GONE);
            binding.composerBar.setVisibility(View.VISIBLE);
        }
    }

    private void refreshActiveGroupCallBanner() {
        if (!isGroup || binding == null) return;
        AppControlManager.AppGateState gate = locator.getAppControlManager().getLastState();
        if (gate != null && !gate.groupCallsEnabled) {
            binding.activeGroupCallBanner.setVisibility(View.GONE);
            return;
        }
        PeerConnectionManager.GroupCallRoom room = locator.getPeerConnectionManager().getActiveGroupCallForGroup(chatId);
        if (room != null && !room.participants.isEmpty()) {
            binding.activeGroupCallBanner.setVisibility(View.VISIBLE);
            binding.activeGroupCallText.setText(getString(R.string.active_group_call_join, room.participants.size()));
            binding.btnJoinActiveGroupCall.setOnClickListener(v ->
                    ((MainActivity) requireActivity()).startGroupCall(room.callId, chatId, chatTitle, room.callType));
        } else {
            binding.activeGroupCallBanner.setVisibility(View.GONE);
        }
    }

    private void setupListeners() {
        binding.btnChatBack.setOnClickListener(v -> ((MainActivity) requireActivity()).popCurrentTab());
        binding.chatHeaderTitleArea.setOnClickListener(v -> {
            if (isGroup) {
                ((MainActivity) requireActivity()).openGroupInfo(chatId);
            } else if (currentDmPeer != null) {
                ((MainActivity) requireActivity()).openPeerProfile(currentDmPeer);
            }
        });

        binding.btnChatVoiceCall.setOnClickListener(v -> {
            if (isGroup) {
                PeerConnectionManager.GroupCallRoom existing = locator.getPeerConnectionManager().getActiveGroupCallForGroup(chatId);
                ((MainActivity) requireActivity()).startGroupCall(
                        existing != null ? existing.callId : null, chatId, chatTitle, "GROUP_VOICE");
            } else if (peerId != null) {
                ((MainActivity) requireActivity()).startOutgoingCall(peerId, chatTitle, "", "VOICE");
            }
        });
        binding.btnChatVideoCall.setOnClickListener(v -> {
            if (isGroup) {
                PeerConnectionManager.GroupCallRoom existing = locator.getPeerConnectionManager().getActiveGroupCallForGroup(chatId);
                ((MainActivity) requireActivity()).startGroupCall(
                        existing != null ? existing.callId : null, chatId, chatTitle, "GROUP_VIDEO");
            } else if (peerId != null) {
                ((MainActivity) requireActivity()).startOutgoingCall(peerId, chatTitle, "", "VIDEO");
            }
        });

        binding.btnChatMenu.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(requireContext(), v);
            if (isGroup) {
                popup.getMenu().add(0, 1, 0, R.string.action_group_info);
            } else if (currentDmPeer != null) {
                popup.getMenu().add(0, 5, 0, R.string.view_profile);
            }
            popup.getMenu().add(0, 2, 1, R.string.search);
            popup.getMenu().add(0, 3, 2, R.string.action_clear_chat);
            popup.getMenu().add(0, 4, 3, R.string.action_delete_chat);
            popup.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == 1) ((MainActivity) requireActivity()).openGroupInfo(chatId);
                else if (id == 5 && currentDmPeer != null) ((MainActivity) requireActivity()).openPeerProfile(currentDmPeer);
                else if (id == 2) binding.chatSearchBar.setVisibility(View.VISIBLE);
                else if (id == 3) viewModel.clearChat();
                else if (id == 4) {
                    viewModel.deleteChat();
                    ((MainActivity) requireActivity()).popCurrentTab();
                }
                return true;
            });
            popup.show();
        });

        binding.btnCloseChatSearch.setOnClickListener(v -> {
            binding.inputChatSearch.setText("");
            viewModel.setSearchQuery("");
            binding.chatSearchBar.setVisibility(View.GONE);
        });

        binding.inputChatSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                viewModel.setSearchQuery(s != null ? s.toString() : "");
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        binding.inputMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                locator.getPeerConnectionManager().sendTyping(chatId, peerId, s != null && s.length() > 0);
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        binding.btnSendMessage.setOnClickListener(v -> {
            String txt = binding.inputMessage.getText() != null ? binding.inputMessage.getText().toString() : "";
            if (!txt.trim().isEmpty()) {
                viewModel.sendTextMessage(txt, pendingReply, null);
                binding.inputMessage.setText("");
                pendingReply = null;
                binding.replyPreviewBar.setVisibility(View.GONE);
                locator.getPeerConnectionManager().sendTyping(chatId, peerId, false);
            }
        });

        binding.btnCancelReply.setOnClickListener(v -> {
            pendingReply = null;
            binding.replyPreviewBar.setVisibility(View.GONE);
        });

        binding.btnAttach.setOnClickListener(v -> {
            AppControlManager.AppGateState gate = locator.getAppControlManager().getLastState();
            if (gate != null && !gate.fileTransferEnabled) {
                Toast.makeText(requireContext(), R.string.feature_disabled_remote, Toast.LENGTH_SHORT).show();
                return;
            }
            showAttachChooser();
        });

        binding.btnVoiceNote.setOnClickListener(v -> {
            AppControlManager.AppGateState gate = locator.getAppControlManager().getLastState();
            if (gate != null && !gate.fileTransferEnabled) {
                Toast.makeText(requireContext(), R.string.feature_disabled_remote, Toast.LENGTH_SHORT).show();
                return;
            }
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                toggleVoiceRecording();
            } else {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.perm_rationale_title)
                        .setMessage(R.string.perm_microphone)
                        .setPositiveButton(R.string.ok, (d, w) -> micPermLauncher.launch(Manifest.permission.RECORD_AUDIO))
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }
        });

        locator.getPeerConnectionManager().setTypingListener((cid, name, typing) -> {
            if (binding != null && chatId.equals(cid)) {
                binding.chatTypingBanner.setVisibility(typing ? View.VISIBLE : View.GONE);
                if (typing) binding.chatTypingBanner.setText(getString(R.string.typing_indicator, name));
            }
        });
    }

    private void showAttachChooser() {
        String[] options = {
                getString(R.string.attach_photo_video),
                getString(R.string.attach_document),
                getString(R.string.attach_audio)
        };
        new MaterialAlertDialogBuilder(requireContext())
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        pickMediaLauncher.launch(new PickVisualMediaRequest.Builder()
                                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo.INSTANCE)
                                .build());
                    } else if (which == 1) {
                        openDocLauncher.launch(new String[]{"*/*"});
                    } else {
                        openDocLauncher.launch(new String[]{"audio/*"});
                    }
                })
                .show();
    }

    private void toggleVoiceRecording() {
        if (!isRecording) {
            try {
                File dir = new File(requireContext().getCacheDir(), "voice");
                if (!dir.exists()) dir.mkdirs();
                currentVoiceFile = new File(dir, "voice_" + System.currentTimeMillis() + ".m4a");
                recorder = new MediaRecorder();
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setOutputFile(currentVoiceFile.getAbsolutePath());
                recorder.prepare();
                recorder.start();
                isRecording = true;
                Toast.makeText(requireContext(), R.string.recording_voice, Toast.LENGTH_SHORT).show();
                binding.btnVoiceNote.setColorFilter(ContextCompat.getColor(requireContext(), R.color.red_500));
            } catch (Exception e) {
                Toast.makeText(requireContext(), "Microphone unavailable", Toast.LENGTH_SHORT).show();
            }
        } else {
            try {
                recorder.stop();
                recorder.release();
            } catch (Exception ignored) {
            }
            recorder = null;
            isRecording = false;
            binding.btnVoiceNote.clearColorFilter();
            if (currentVoiceFile != null && currentVoiceFile.exists()) {
                viewModel.sendFileAttachment(Uri.fromFile(currentVoiceFile), "VOICE");
            }
        }
    }

    private void playVoiceNote(String path) {
        if (path == null) return;
        try {
            if (voicePlayer != null) voicePlayer.release();
            voicePlayer = new MediaPlayer();
            if (path.startsWith("content://")) {
                voicePlayer.setDataSource(requireContext(), Uri.parse(path));
            } else {
                voicePlayer.setDataSource(path);
            }
            voicePlayer.prepare();
            voicePlayer.start();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Cannot play audio", Toast.LENGTH_SHORT).show();
        }
    }

    private String resolveLocalFilePath(MessageEntity msg, FileTransferEntity tr) {
        if (msg.localFilePath != null && !msg.localFilePath.isEmpty()) return msg.localFilePath;
        if (tr != null && tr.savedPath != null && !tr.savedPath.isEmpty()) return tr.savedPath;
        if (tr != null && tr.localUri != null && !tr.localUri.isEmpty()) return tr.localUri;
        return null;
    }

    private void openLocalFile(String pathOrUri, String mimeType, String displayName) {
        try {
            Uri uri = pathOrUri.startsWith("content://")
                    ? Uri.parse(pathOrUri)
                    : androidx.core.content.FileProvider.getUriForFile(requireContext(),
                    requireContext().getPackageName() + ".fileprovider",
                    new File(pathOrUri.startsWith("file://") ? Uri.parse(pathOrUri).getPath() : pathOrUri));
            Intent viewIntent = new Intent(Intent.ACTION_VIEW);
            viewIntent.setDataAndType(uri, mimeType);
            viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(viewIntent);
        } catch (Exception e) {
            Toast.makeText(requireContext(), getString(R.string.file_completed, displayName), Toast.LENGTH_LONG).show();
        }
    }

    private void showMessageOptions(MessageEntity msg) {
        String myId = locator.getPreferencesManager().getOrCreateDeviceId();
        boolean isMine = myId.equals(msg.senderId);
        boolean canEdit = isMine && "TEXT".equals(msg.type) && !msg.isDeletedForEveryone;
        boolean canDeleteEveryone = !msg.isDeletedForEveryone && (isMine || (isGroup && isGroupAdminOrOwner));

        List<String> labels = new ArrayList<>();
        List<Runnable> runners = new ArrayList<>();

        labels.add(getString(R.string.action_react));
        runners.add(() -> showEmojiPicker(msg));

        labels.add(getString(R.string.action_reply));
        runners.add(() -> {
            pendingReply = msg;
            binding.replyPreviewBar.setVisibility(View.VISIBLE);
            binding.replyPreviewText.setText(getString(R.string.replying_to, msg.senderName) + ": " + msg.content);
        });

        if (canEdit) {
            labels.add(getString(R.string.action_edit_message));
            runners.add(() -> showEditMessageDialog(msg));
        }

        labels.add(getString(R.string.action_forward));
        runners.add(() -> showForwardDialog(msg));

        labels.add(getString(R.string.copy));
        runners.add(() -> {
            ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Message", msg.content));
                Toast.makeText(requireContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
            }
        });

        labels.add(getString(R.string.action_delete_for_me));
        runners.add(() -> viewModel.deleteForMe(msg.messageId));

        if (canDeleteEveryone) {
            labels.add(getString(R.string.action_delete_for_everyone));
            runners.add(() -> viewModel.deleteForEveryone(msg.messageId));
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setItems(labels.toArray(new String[0]), (d, which) -> runners.get(which).run())
                .show();
    }

    private void showEditMessageDialog(MessageEntity msg) {
        EditText input = new EditText(requireContext());
        input.setText(msg.content);
        int pad = Math.round(18 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.edit_message_title)
                .setView(input)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String updated = input.getText().toString().trim();
                    if (!updated.isEmpty() && !updated.equals(msg.content)) {
                        viewModel.editMessage(msg.messageId, updated);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showEmojiPicker(MessageEntity msg) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.choose_reaction)
                .setItems(EMOJI_CHOICES, (d, idx) ->
                        locator.getPeerConnectionManager().sendReaction(chatId, msg.messageId, EMOJI_CHOICES[idx]))
                .show();
    }

    private void showWhoReactedDialog(MessageEntity msg) {
        try {
            Type mapType = new TypeToken<Map<String, List<Map<String, String>>>>() {}.getType();
            Map<String, List<Map<String, String>>> map = new Gson().fromJson(msg.reactionsJson, mapType);
            if (map == null || map.isEmpty()) return;
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, List<Map<String, String>>> e : map.entrySet()) {
                sb.append(e.getKey()).append(" : ");
                for (int i = 0; i < e.getValue().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(e.getValue().get(i).get("name"));
                }
                sb.append("\n");
            }
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.who_reacted_title)
                    .setMessage(sb.toString().trim())
                    .setPositiveButton(R.string.ok, null)
                    .show();
        } catch (Exception ignored) {
        }
    }

    private void showForwardDialog(MessageEntity source) {
        AppDatabase.DB_EXECUTOR.execute(() -> {
            List<ChatEntity> chats = locator.getDatabase().chatDao().getAllChatsSync();
            requireActivity().runOnUiThread(() -> {
                if (chats == null || chats.isEmpty()) {
                    viewModel.forwardMessageTo(source, AppDatabase.EVERYONE_ROOM_ID, null);
                    Toast.makeText(requireContext(), "Forwarded to Everyone Room", Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] names = new String[chats.size()];
                for (int i = 0; i < chats.size(); i++) names[i] = chats.get(i).title;
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.select_forward_target)
                        .setItems(names, (d, idx) -> {
                            ChatEntity target = chats.get(idx);
                            viewModel.forwardMessageTo(source, target.chatId, target.peerDeviceId);
                            Toast.makeText(requireContext(), "Forwarded to " + target.title, Toast.LENGTH_SHORT).show();
                        })
                        .show();
            });
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        locator.getPeerConnectionManager().setActiveChatId(chatId);
        if (isGroup) refreshActiveGroupCallBanner();
    }

    @Override
    public void onPause() {
        locator.getPeerConnectionManager().setActiveChatId(null);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        locator.getPeerConnectionManager().setTypingListener(null);
        locator.getPeerConnectionManager().setActiveGroupCallListener(null);
        if (voicePlayer != null) {
            voicePlayer.release();
            voicePlayer = null;
        }
        if (recorder != null) {
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
            recorder = null;
        }
        super.onDestroyView();
        binding = null;
    }
}
