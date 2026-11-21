(function () {
  const canCaptureMedia = !!(window.isSecureContext && navigator.mediaDevices && navigator.mediaDevices.getUserMedia);
  const MAX_WEB_UPLOAD_BYTES = 100 * 1024 * 1024;
  const EMOJIS = ['👍', '❤️', '😂', '😮', '😢', '🔥', '👏', '🎉'];

  function loadStoredId() {
    let id = localStorage.getItem('wcl_web_id') || sessionStorage.getItem('wcl_web_id');
    if (!id) id = 'web-' + Math.random().toString(36).slice(2, 10);
    localStorage.setItem('wcl_web_id', id);
    return id;
  }

  function loadStoredName() {
    const name = localStorage.getItem('wcl_web_name') || sessionStorage.getItem('wcl_web_name') || '';
    if (name) localStorage.setItem('wcl_web_name', name);
    return name;
  }

  function loadOutbox() {
    try {
      const raw = localStorage.getItem('wcl_web_outbox');
      return raw ? JSON.parse(raw) : [];
    } catch (e) {
      return [];
    }
  }

  function saveOutbox(list) {
    try {
      localStorage.setItem('wcl_web_outbox', JSON.stringify(list || []));
    } catch (e) {}
  }

  const state = {
    webUserId: loadStoredId(),
    displayName: loadStoredName(),
    activeChatId: 'everyone_room',
    activeTargetPeerId: null,
    searchQuery: '',
    ws: null,
    messages: [],
    peers: [],
    groups: [],
    callLogs: [],
    activeGroupCalls: [],
    featureFlags: {
      groupCallsEnabled: true,
      fileTransferEnabled: true,
      publicGroupsEnabled: true,
      everyoneGroupEnabled: true
    },
    outbox: loadOutbox(),
    replyTo: null,
    editingMsg: null,
    forwardingMsg: null,
    reactingMsgId: null,
    pendingPinGroup: null,
    zoomScale: 1,
    typingTimer: null,
    recorder: null,
    recorderChunks: [],
    recorderStream: null,
    call: {
      active: false,
      isGroupCall: false,
      callId: null,
      groupId: null,
      peerId: null,
      peerName: '',
      callType: 'VOICE',
      isIncoming: false,
      facingMode: 'user',
      pc: null,
      meshPcs: {},
      remoteStreams: {},
      localStream: null,
      muted: false,
      videoOn: true,
      timerHandle: null,
      connectedAt: 0,
      pendingOfferSdp: null
    }
  };

  const el = {
    hostInfo: document.getElementById('host-info'),
    connPill: document.getElementById('conn-pill'),
    httpsBanner: document.getElementById('https-notice-banner'),
    themeToggleBtn: document.getElementById('theme-toggle-btn'),
    joinModal: document.getElementById('join-modal'),
    nameInput: document.getElementById('web-name-input'),
    joinBtn: document.getElementById('join-btn'),
    qrModal: document.getElementById('qr-modal'),
    qrToggleBtn: document.getElementById('qr-toggle-btn'),
    qrCloseBtn: document.getElementById('qr-close-btn'),
    qrCanvas: document.getElementById('web-qr-canvas'),
    qrUrl: document.getElementById('web-qr-url'),
    pinModal: document.getElementById('pin-modal'),
    pinModalTitle: document.getElementById('pin-modal-title'),
    pinModalSub: document.getElementById('pin-modal-sub'),
    pinModalInput: document.getElementById('pin-modal-input'),
    pinModalError: document.getElementById('pin-modal-error'),
    pinCancelBtn: document.getElementById('pin-cancel-btn'),
    pinSubmitBtn: document.getElementById('pin-submit-btn'),
    roomInfoModal: document.getElementById('room-info-modal'),
    infoModalKind: document.getElementById('info-modal-kind'),
    infoModalTitle: document.getElementById('info-modal-title'),
    infoModalBody: document.getElementById('info-modal-body'),
    infoModalClose: document.getElementById('info-modal-close'),
    forwardModal: document.getElementById('forward-modal'),
    forwardPreview: document.getElementById('forward-preview'),
    forwardTargetsList: document.getElementById('forward-targets-list'),
    forwardCancelBtn: document.getElementById('forward-cancel-btn'),
    editModal: document.getElementById('edit-modal'),
    editModalInput: document.getElementById('edit-modal-input'),
    editCancelBtn: document.getElementById('edit-cancel-btn'),
    editSaveBtn: document.getElementById('edit-save-btn'),
    reactModal: document.getElementById('react-modal'),
    emojiPickerGrid: document.getElementById('emoji-picker-grid'),
    whoReactedList: document.getElementById('who-reacted-list'),
    reactCloseBtn: document.getElementById('react-close-btn'),
    groupsList: document.getElementById('groups-list'),
    groupsCount: document.getElementById('groups-count'),
    privateGroupsList: document.getElementById('private-groups-list'),
    privateGroupsCount: document.getElementById('private-groups-count'),
    peersList: document.getElementById('peers-list'),
    peersCount: document.getElementById('peers-count'),
    callsList: document.getElementById('calls-list'),
    messagesBox: document.getElementById('messages-box'),
    composerForm: document.getElementById('composer-form'),
    composerLockBanner: document.getElementById('composer-lock-banner'),
    msgInput: document.getElementById('msg-input'),
    fileInput: document.getElementById('file-input'),
    fileUploadLabel: document.getElementById('file-upload-label'),
    btnVoiceNote: document.getElementById('btn-voice-note'),
    btnSend: document.getElementById('btn-send'),
    uploadProgressWrap: document.getElementById('upload-progress-wrap'),
    uploadProgressLabel: document.getElementById('upload-progress-label'),
    uploadProgressPct: document.getElementById('upload-progress-pct'),
    uploadProgressFill: document.getElementById('upload-progress-fill'),
    roomTitle: document.getElementById('active-room-title'),
    roomSub: document.getElementById('active-room-sub'),
    roomAvatar: document.getElementById('active-room-avatar'),
    roomDot: document.getElementById('active-room-dot'),
    btnSearchToggle: document.getElementById('btn-search-toggle'),
    btnRoomInfo: document.getElementById('btn-room-info'),
    btnDmVoice: document.getElementById('btn-dm-voice'),
    btnDmVideo: document.getElementById('btn-dm-video'),
    meBadge: document.getElementById('me-badge'),
    searchBar: document.getElementById('chat-search-bar'),
    searchInput: document.getElementById('chat-search-input'),
    searchCount: document.getElementById('chat-search-count'),
    searchClear: document.getElementById('chat-search-clear'),
    activeGroupCallBanner: document.getElementById('active-group-call-banner'),
    activeGroupCallText: document.getElementById('active-group-call-text'),
    btnJoinGroupCall: document.getElementById('btn-join-group-call'),
    typingBanner: document.getElementById('typing-banner'),
    replyBanner: document.getElementById('reply-banner'),
    replyBannerText: document.getElementById('reply-banner-text'),
    replyCancelBtn: document.getElementById('reply-cancel-btn'),
    sidebarPane: document.getElementById('sidebar-pane'),
    chatPane: document.getElementById('chat-pane'),
    mobileTabs: document.getElementById('mobile-tabs'),
    webrtcNote: document.getElementById('webrtc-capability-note'),
    imgModal: document.getElementById('image-modal'),
    imgModalTitle: document.getElementById('img-modal-title'),
    imgModalSub: document.getElementById('img-modal-sub'),
    imgModalPreview: document.getElementById('img-modal-preview'),
    imgModalDownload: document.getElementById('img-modal-download'),
    imgModalClose: document.getElementById('img-modal-close'),
    imgZoomIn: document.getElementById('img-zoom-in'),
    imgZoomOut: document.getElementById('img-zoom-out'),
    imgZoomReset: document.getElementById('img-zoom-reset'),
    callModal: document.getElementById('call-modal'),
    callTypeBadge: document.getElementById('call-type-badge'),
    callPeerAvatar: document.getElementById('call-peer-avatar'),
    callPeerName: document.getElementById('call-peer-name'),
    callStatusText: document.getElementById('call-status-text'),
    callTimerText: document.getElementById('call-timer-text'),
    callParticipantsStrip: document.getElementById('call-participants-strip'),
    callVideoStage: document.getElementById('call-video-stage'),
    groupMediaGrid: document.getElementById('group-media-grid'),
    remoteVideo: document.getElementById('remote-video'),
    localVideo: document.getElementById('local-video'),
    remoteAudio: document.getElementById('remote-audio'),
    incomingActions: document.getElementById('incoming-call-actions'),
    activeActions: document.getElementById('active-call-actions'),
    btnCallAccept: document.getElementById('btn-call-accept'),
    btnCallDecline: document.getElementById('btn-call-decline'),
    btnCallMute: document.getElementById('btn-call-mute'),
    btnCallCam: document.getElementById('btn-call-cam'),
    btnCallFlip: document.getElementById('btn-call-flip'),
    btnCallEnd: document.getElementById('btn-call-end')
  };

  function makeDmChatId(a, b) {
    return a <= b ? 'dm:' + a + '_' + b : 'dm:' + b + '_' + a;
  }

  function formatBytes(bytes) {
    if (!bytes || bytes <= 0) return '0 B';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / 1048576).toFixed(1) + ' MB';
  }

  function extUpper(name) {
    if (!name || name.indexOf('.') < 0) return 'FILE';
    return name.split('.').pop().slice(0, 6).toUpperCase();
  }

  function escapeHtml(s) {
    return String(s || '').replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function sendPacket(type, fields) {
    if (!state.ws || state.ws.readyState !== 1) return false;
    const pkt = Object.assign({
      version: 1,
      packetId: 'pkt-' + Date.now() + '-' + Math.random().toString(36).slice(2, 7),
      type: type,
      event: type,
      timestamp: Date.now(),
      senderId: state.webUserId,
      senderName: state.displayName || 'Web User',
      isWebUser: true
    }, fields || {});
    state.ws.send(JSON.stringify(pkt));
    return true;
  }

  function flushOutbox() {
    if (!state.ws || state.ws.readyState !== 1 || !state.outbox.length) return;
    const remaining = [];
    state.outbox.forEach(function (item) {
      const ok = sendPacket('CHAT_MESSAGE', {
        chatId: item.chatId,
        targetId: item.targetId || null,
        messageId: item.message.messageId,
        message: item.message
      });
      if (!ok) {
        remaining.push(item);
      } else {
        addOrUpdateMessage(Object.assign({}, item.message, { status: 'SENT' }));
      }
    });
    state.outbox = remaining;
    saveOutbox(remaining);
  }

  function initThemeAndCapabilities() {
    const savedTheme = localStorage.getItem('wcl_theme') || 'dark';
    document.documentElement.setAttribute('data-theme', savedTheme);
    el.themeToggleBtn.onclick = function () {
      const next = document.documentElement.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
      document.documentElement.setAttribute('data-theme', next);
      localStorage.setItem('wcl_theme', next);
    };

    if (!canCaptureMedia) {
      el.httpsBanner.classList.remove('hidden');
      el.webrtcNote.textContent = 'WebRTC camera/mic & voice notes blocked by browser on insecure HTTP origin. Host over HTTPS/localhost or use the native Android app for calls.';
    } else {
      el.webrtcNote.textContent = 'Secure context verified: WebRTC Host-ICE voice & video calling and voice notes ready.';
    }
  }

  function connectWs() {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(proto + '//' + location.host + '/ws');
    state.ws = ws;

    ws.onopen = function () {
      el.connPill.textContent = 'Connected';
      el.connPill.className = 'pill online';
      sendPacket('HELLO');
      flushOutbox();
    };

    ws.onclose = function () {
      el.connPill.textContent = 'Reconnecting…';
      el.connPill.className = 'pill offline';
      setTimeout(connectWs, 3000);
    };

    ws.onmessage = function (ev) {
      try {
        const pkt = JSON.parse(ev.data);
        const type = pkt.type || pkt.event;
        if (type === 'CHAT_MESSAGE' && pkt.message) {
          addOrUpdateMessage(pkt.message);
        } else if (type === 'MESSAGE_EDIT' && pkt.messageId && typeof pkt.newContent === 'string') {
          state.messages = state.messages.map(function (m) {
            if (m.messageId === pkt.messageId) {
              return Object.assign({}, m, { content: pkt.newContent, isEdited: true, editedAt: pkt.editedAt || Date.now() });
            }
            return m;
          });
          renderMessages();
        } else if (type === 'MESSAGE_DELETE' && pkt.messageId) {
          state.messages = state.messages.map(function (m) {
            if (m.messageId === pkt.messageId) {
              return Object.assign({}, m, { isDeletedForEveryone: true, content: '' });
            }
            return m;
          });
          renderMessages();
        } else if (type === 'MESSAGE_REACTION' && pkt.messageId && pkt.emoji) {
          refreshInfo();
        } else if (type === 'TYPING' && pkt.chatId === state.activeChatId && pkt.senderId !== state.webUserId) {
          el.typingBanner.textContent = pkt.typing ? ((pkt.senderName || 'Peer') + ' is typing…') : '';
          el.typingBanner.classList.toggle('hidden', !pkt.typing);
        } else if (type === 'WEB_SNAPSHOT') {
          applySnapshot(pkt);
        } else if (type && (type.indexOf('CALL_') === 0 || type.indexOf('GROUP_CALL_') === 0)) {
          handleIncomingCallSignal(type, pkt);
        }
      } catch (e) {
        console.error(e);
      }
    };
  }

  function applySnapshot(data) {
    if (data.peers) state.peers = data.peers;
    if (data.groups) state.groups = data.groups;
    if (data.callLogs) state.callLogs = data.callLogs;
    if (data.activeGroupCalls) state.activeGroupCalls = data.activeGroupCalls;
    if (data.featureFlags) state.featureFlags = data.featureFlags;
    if (data.messages) {
      const merged = new Map();
      state.messages.forEach(function (m) { merged.set(m.messageId, m); });
      data.messages.forEach(function (m) { merged.set(m.messageId, m); });
      state.messages = Array.from(merged.values()).sort(function (a, b) {
        return (a.createdAt || 0) - (b.createdAt || 0);
      });
    }
    renderPeers();
    renderGroups();
    renderCalls();
    updateActiveRoomHeader();
    renderComposerState();
    renderMessages();
    if (state.call.active && state.call.isGroupCall) {
      syncActiveGroupCallParticipants();
    }
  }

  function addOrUpdateMessage(msg) {
    const idx = state.messages.findIndex(function (m) { return m.messageId === msg.messageId; });
    if (idx >= 0) state.messages[idx] = msg;
    else state.messages.push(msg);
    renderMessages();
  }

  function getActiveGroup() {
    return state.groups.find(function (g) { return g.groupId === state.activeChatId; }) || null;
  }

  function getActivePeer() {
    if (!state.activeTargetPeerId) return null;
    return state.peers.find(function (p) { return p.deviceId === state.activeTargetPeerId; }) || null;
  }

  function renderComposerState() {
    const fileOk = !state.featureFlags || state.featureFlags.fileTransferEnabled !== false;
    el.fileUploadLabel.classList.toggle('disabled-btn', !fileOk);
    el.fileInput.disabled = !fileOk;
    el.btnVoiceNote.disabled = !fileOk;

    const grp = getActiveGroup();
    let lockedReason = '';
    if (grp && grp.isPrivate && !grp.authorized) {
      lockedReason = 'This private group is locked. Unlock it with the PIN first.';
    } else if (grp && grp.adminsOnlySend) {
      const meMember = (grp.members || []).find(function (m) { return m.deviceId === state.webUserId; });
      const role = meMember && meMember.role ? meMember.role.toUpperCase() : 'MEMBER';
      const isAdmin = role === 'OWNER' || role === 'ADMIN' || grp.ownerId === state.webUserId;
      if (!isAdmin) lockedReason = 'Admins only can send messages in this group.';
    }
    el.composerLockBanner.textContent = lockedReason;
    el.composerLockBanner.classList.toggle('hidden', !lockedReason);
    el.msgInput.disabled = !!lockedReason;
    el.btnSend.disabled = !!lockedReason;
  }

  function updateActiveRoomHeader() {
    const peer = getActivePeer();
    const grp = getActiveGroup();
    const groupCallsOk = !state.featureFlags || state.featureFlags.groupCallsEnabled !== false;

    if (state.activeTargetPeerId) {
      const peerName = peer ? peer.name : (el.roomTitle.textContent || 'Peer');
      const online = peer ? !!peer.isOnline : false;
      el.roomTitle.textContent = peerName;
      el.roomSub.textContent = (peer && peer.ipAddress ? peer.ipAddress : 'Local Peer') + (online ? ' • Online' : ' • Offline');
      el.roomAvatar.textContent = peerName.slice(0, 1).toUpperCase();
      el.roomDot.className = 'dot ' + (online ? ((peer && peer.activeStatus === 'BUSY') ? 'busy' : ((peer && peer.activeStatus === 'AWAY') ? 'away' : 'on')) : 'off');
      el.roomDot.classList.remove('hidden');
      el.btnDmVoice.classList.remove('hidden');
      el.btnDmVideo.classList.remove('hidden');
      el.btnDmVoice.onclick = function () { if (peer) startOutgoingCall(peer, 'VOICE'); };
      el.btnDmVideo.onclick = function () { if (peer) startOutgoingCall(peer, 'VIDEO'); };
      el.activeGroupCallBanner.classList.add('hidden');
    } else {
      const name = grp ? grp.name : 'Everyone Room';
      const sub = grp ? (grp.description || (grp.isPrivate ? 'Private PIN-protected group' : 'Public local group')) : 'Public local room';
      el.roomTitle.textContent = name;
      el.roomSub.textContent = sub;
      el.roomAvatar.textContent = name.slice(0, 1).toUpperCase();
      el.roomDot.classList.add('hidden');

      const canGroupCall = groupCallsOk && (!grp || !grp.isPrivate || grp.authorized);
      el.btnDmVoice.classList.toggle('hidden', !canGroupCall);
      el.btnDmVideo.classList.toggle('hidden', !canGroupCall);
      el.btnDmVoice.onclick = function () {
        startOrJoinGroupCall(state.activeChatId, name, 'GROUP_VOICE', null);
      };
      el.btnDmVideo.onclick = function () {
        startOrJoinGroupCall(state.activeChatId, name, 'GROUP_VIDEO', null);
      };

      const activeCall = (state.activeGroupCalls || []).find(function (c) { return c.groupId === state.activeChatId; });
      if (activeCall && groupCallsOk) {
        const count = (activeCall.participants || []).length;
        const label = activeCall.callType === 'GROUP_VIDEO' ? 'Group Video Call' : 'Group Voice Call';
        el.activeGroupCallText.textContent = 'Active ' + label + ' • ' + count + ' joined';
        el.activeGroupCallBanner.classList.remove('hidden');
        el.btnJoinGroupCall.onclick = function () {
          startOrJoinGroupCall(activeCall.groupId, activeCall.groupName || name, activeCall.callType, activeCall.callId);
        };
      } else {
        el.activeGroupCallBanner.classList.add('hidden');
      }
    }
  }

  function renderPeers() {
    el.peersList.innerHTML = '';
    const others = state.peers.filter(function (p) { return p.deviceId !== state.webUserId; });
    el.peersCount.textContent = String(others.length);

    if (others.length === 0) {
      const li = document.createElement('li');
      li.className = 'muted';
      li.textContent = 'No other nearby peers online';
      el.peersList.appendChild(li);
      return;
    }

    others.forEach(function (p) {
      const dmId = makeDmChatId(state.webUserId, p.deviceId);
      const li = document.createElement('li');
      if (state.activeChatId === dmId) li.className = 'active';

      const left = document.createElement('div');
      left.className = 'peer-row-left';
      const dot = document.createElement('span');
      const st = (p.activeStatus || 'ONLINE').toUpperCase();
      dot.className = 'dot ' + (!p.isOnline ? 'off' : (st === 'BUSY' ? 'busy' : (st === 'AWAY' ? 'away' : 'on')));
      left.appendChild(dot);

      const nameEl = document.createElement('span');
      nameEl.className = 'peer-name';
      nameEl.textContent = p.name;
      left.appendChild(nameEl);

      if (p.isWebUser) {
        const wb = document.createElement('span');
        wb.className = 'badge-web';
        wb.textContent = 'Web';
        left.appendChild(wb);
      }

      const actions = document.createElement('div');
      actions.className = 'peer-actions';
      const voiceBtn = document.createElement('button');
      voiceBtn.type = 'button';
      voiceBtn.className = 'mini-btn';
      voiceBtn.textContent = 'Voice';
      voiceBtn.disabled = !p.isOnline;
      voiceBtn.onclick = function (ev) {
        ev.stopPropagation();
        startOutgoingCall(p, 'VOICE');
      };

      const videoBtn = document.createElement('button');
      videoBtn.type = 'button';
      videoBtn.className = 'mini-btn';
      videoBtn.textContent = 'Video';
      videoBtn.disabled = !p.isOnline;
      videoBtn.onclick = function (ev) {
        ev.stopPropagation();
        startOutgoingCall(p, 'VIDEO');
      };

      actions.appendChild(voiceBtn);
      actions.appendChild(videoBtn);
      li.appendChild(left);
      li.appendChild(actions);

      li.onclick = function () {
        selectDirectChat(p);
        switchMobileTab('chat');
      };
      el.peersList.appendChild(li);
    });
  }

  function renderGroups() {
    el.groupsList.innerHTML = '';
    el.privateGroupsList.innerHTML = '';

    const publicGroups = state.groups.filter(function (g) { return !g.isPrivate; });
    if (publicGroups.length === 0 && (!state.featureFlags || state.featureFlags.everyoneGroupEnabled !== false)) {
      publicGroups.push({ groupId: 'everyone_room', name: 'Everyone Room', description: 'Public local room', authorized: true });
    }
    el.groupsCount.textContent = String(publicGroups.length);

    publicGroups.forEach(function (g) {
      const li = document.createElement('li');
      if (g.groupId === state.activeChatId) li.className = 'active';
      const label = document.createElement('span');
      label.textContent = g.name;
      li.appendChild(label);
      li.onclick = function () {
        selectGroupChat(g);
        switchMobileTab('chat');
      };
      el.groupsList.appendChild(li);
    });

    const privateGroups = state.groups.filter(function (g) { return !!g.isPrivate; });
    el.privateGroupsCount.textContent = String(privateGroups.length);
    if (privateGroups.length === 0) {
      const li = document.createElement('li');
      li.className = 'muted';
      li.textContent = 'No private groups visible';
      el.privateGroupsList.appendChild(li);
    } else {
      privateGroups.forEach(function (g) {
        const li = document.createElement('li');
        if (g.groupId === state.activeChatId) li.className = 'active';
        const left = document.createElement('div');
        left.className = 'peer-row-left';
        const label = document.createElement('span');
        label.className = 'peer-name';
        label.textContent = g.name;
        left.appendChild(label);
        li.appendChild(left);

        const badge = document.createElement('span');
        badge.className = g.authorized ? 'badge-web' : 'badge-lock';
        badge.textContent = g.authorized ? 'Unlocked' : 'PIN Locked';
        li.appendChild(badge);

        li.onclick = function () {
          if (!g.authorized) {
            openPinModal(g);
          } else {
            selectGroupChat(g);
            switchMobileTab('chat');
          }
        };
        el.privateGroupsList.appendChild(li);
      });
    }
  }

  function selectGroupChat(g) {
    state.activeChatId = g.groupId;
    state.activeTargetPeerId = null;
    renderGroups();
    renderPeers();
    updateActiveRoomHeader();
    renderComposerState();
    renderMessages();
  }

  function selectDirectChat(peer) {
    state.activeChatId = makeDmChatId(state.webUserId, peer.deviceId);
    state.activeTargetPeerId = peer.deviceId;
    renderGroups();
    renderPeers();
    updateActiveRoomHeader();
    renderComposerState();
    refreshInfo();
  }

  function openPinModal(group) {
    state.pendingPinGroup = group;
    el.pinModalTitle.textContent = 'Unlock "' + group.name + '"';
    el.pinModalSub.textContent = 'Enter the private group PIN to join from your browser.';
    el.pinModalInput.value = '';
    el.pinModalError.textContent = '';
    el.pinModalError.classList.add('hidden');
    el.pinModal.classList.remove('hidden');
    el.pinModalInput.focus();
  }

  el.pinCancelBtn.onclick = function () {
    state.pendingPinGroup = null;
    el.pinModal.classList.add('hidden');
  };

  el.pinSubmitBtn.onclick = async function () {
    const group = state.pendingPinGroup;
    if (!group) return;
    const pin = el.pinModalInput.value.trim();
    if (!pin) {
      el.pinModalError.textContent = 'Please enter a PIN.';
      el.pinModalError.classList.remove('hidden');
      return;
    }
    el.pinSubmitBtn.disabled = true;
    try {
      const res = await fetch('/api/group/auth', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          groupId: group.groupId,
          deviceId: state.webUserId,
          name: state.displayName || 'Web User',
          pin: pin
        })
      });
      const data = await res.json();
      if (res.ok && data.ok) {
        el.pinModal.classList.add('hidden');
        state.pendingPinGroup = null;
        await refreshInfo();
        const refreshed = state.groups.find(function (x) { return x.groupId === group.groupId; }) || group;
        selectGroupChat(refreshed);
        switchMobileTab('chat');
      } else {
        el.pinModalError.textContent = (data && data.error) ? data.error : 'Invalid PIN';
        el.pinModalError.classList.remove('hidden');
      }
    } catch (err) {
      el.pinModalError.textContent = 'Unable to reach host for PIN verification.';
      el.pinModalError.classList.remove('hidden');
    } finally {
      el.pinSubmitBtn.disabled = false;
    }
  };

  function renderCalls() {
    el.callsList.innerHTML = '';
    if (!state.callLogs || state.callLogs.length === 0) {
      const li = document.createElement('li');
      li.className = 'muted';
      li.textContent = 'No recent calls';
      el.callsList.appendChild(li);
      return;
    }
    state.callLogs.slice(0, 12).forEach(function (c) {
      const li = document.createElement('li');
      const left = document.createElement('div');
      left.innerHTML = '<strong>' + escapeHtml(c.peerName || 'Peer') + '</strong><div class="muted">' +
        escapeHtml((c.callType || 'VOICE') + ' • ' + (c.status || '') + (c.durationSeconds ? ' • ' + c.durationSeconds + 's' : '')) + '</div>';
      li.appendChild(left);
      el.callsList.appendChild(li);
    });
  }

  function renderMessages() {
    el.messagesBox.innerHTML = '';
    const q = state.searchQuery.trim().toLowerCase();
    const roomMsgs = state.messages.filter(function (m) {
      return m.chatId === state.activeChatId;
    });
    const filtered = q ? roomMsgs.filter(function (m) {
      return ((m.content || '').toLowerCase().indexOf(q) >= 0) ||
        ((m.attachmentName || '').toLowerCase().indexOf(q) >= 0) ||
        ((m.senderName || '').toLowerCase().indexOf(q) >= 0);
    }) : roomMsgs;

    el.searchCount.textContent = q ? (filtered.length + ' match' + (filtered.length === 1 ? '' : 'es')) : '';

    if (filtered.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'muted';
      empty.style.textAlign = 'center';
      empty.style.marginTop = '24px';
      empty.textContent = q ? 'No matching messages in this chat.' : 'No messages yet. Say hello over local WiFi!';
      el.messagesBox.appendChild(empty);
      return;
    }

    filtered.forEach(function (m) {
      const isMine = m.senderId === state.webUserId;
      const div = document.createElement('div');
      div.className = 'msg' + (isMine ? ' mine' : '');

      const meta = document.createElement('div');
      meta.className = 'msg-meta';
      meta.appendChild(document.createTextNode((m.senderName || 'Peer') + ' • ' + new Date(m.createdAt || Date.now()).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })));
      if (m.senderIsWeb) {
        const wb = document.createElement('span');
        wb.className = 'badge-web';
        wb.textContent = 'Web';
        meta.appendChild(wb);
      }
      if (m.isEdited) {
        const ed = document.createElement('span');
        ed.className = 'msg-edited-tag';
        ed.textContent = '(edited)';
        meta.appendChild(ed);
      }
      if (isMine && m.status) {
        const st = document.createElement('span');
        st.textContent = '• ' + m.status;
        meta.appendChild(st);
      }
      div.appendChild(meta);

      if (m.isDeletedForEveryone) {
        const delNotice = document.createElement('div');
        delNotice.className = 'msg-body muted';
        delNotice.textContent = 'This message was deleted';
        div.appendChild(delNotice);
        el.messagesBox.appendChild(div);
        return;
      }

      if (m.replyToPreview) {
        const rq = document.createElement('div');
        rq.className = 'msg-reply';
        rq.textContent = m.replyToPreview;
        div.appendChild(rq);
      }

      const mime = (m.attachmentMime || '').toLowerCase();
      const isImage = m.type === 'IMAGE' || mime.indexOf('image/') === 0;
      const isVideo = m.type === 'VIDEO' || mime.indexOf('video/') === 0;
      const isAudio = m.type === 'AUDIO' || m.type === 'VOICE' || mime.indexOf('audio/') === 0;
      const dlUrl = m.attachmentId ? ('/api/download?transferId=' + encodeURIComponent(m.attachmentId)) : '';

      if (isImage && m.attachmentId) {
        const thumbSrc = m.thumbnailBase64
          ? ('data:image/jpeg;base64,' + m.thumbnailBase64)
          : ('/api/thumb?transferId=' + encodeURIComponent(m.attachmentId));
        const wrap = document.createElement('div');
        wrap.className = 'msg-thumb-wrap';
        const img = document.createElement('img');
        img.src = thumbSrc;
        img.alt = m.attachmentName || 'Image';
        img.onerror = function () { wrap.style.display = 'none'; };
        const cap = document.createElement('div');
        cap.className = 'msg-thumb-caption';
        cap.textContent = (m.attachmentName || 'Image') + ' • ' + formatBytes(m.attachmentSize);
        wrap.appendChild(img);
        wrap.appendChild(cap);
        wrap.onclick = function () { openImageModal(m, thumbSrc); };
        div.appendChild(wrap);
      } else if (isVideo && m.attachmentId) {
        const vid = document.createElement('video');
        vid.className = 'inline-media-video';
        vid.controls = true;
        vid.preload = 'metadata';
        vid.src = dlUrl;
        div.appendChild(vid);
      } else if (isAudio && m.attachmentId) {
        const aud = document.createElement('audio');
        aud.className = 'inline-media-audio';
        aud.controls = true;
        aud.preload = 'metadata';
        aud.src = dlUrl;
        div.appendChild(aud);
      }

      if (m.content && (!m.attachmentId || m.content.indexOf('Shared file:') !== 0)) {
        const body = document.createElement('div');
        body.className = 'msg-body';
        body.textContent = m.content;
        div.appendChild(body);
      }

      if (m.attachmentId && m.attachmentName) {
        const card = document.createElement('div');
        card.className = 'file-card';

        const top = document.createElement('div');
        top.className = 'file-card-top';
        const ext = document.createElement('span');
        ext.className = 'file-ext';
        ext.textContent = extUpper(m.attachmentName);
        const title = document.createElement('strong');
        title.textContent = m.attachmentName;
        top.appendChild(ext);
        top.appendChild(title);

        const info = document.createElement('div');
        info.className = 'file-meta';
        info.textContent = formatBytes(m.attachmentSize) + ' • ' + (m.senderName || 'Peer') +
          (m.attachmentSha256 ? ' • SHA-256 ready' : '');

        const actions = document.createElement('div');
        actions.className = 'file-actions';

        if (isImage) {
          const openBtn = document.createElement('button');
          openBtn.type = 'button';
          openBtn.className = 'mini-btn';
          openBtn.textContent = 'Preview';
          openBtn.onclick = function () { openImageModal(m, '/api/thumb?transferId=' + encodeURIComponent(m.attachmentId)); };
          actions.appendChild(openBtn);
        } else {
          const openLink = document.createElement('a');
          openLink.href = dlUrl;
          openLink.target = '_blank';
          openLink.className = 'mini-btn';
          openLink.textContent = 'Open';
          actions.appendChild(openLink);
        }

        const dlLink = document.createElement('a');
        dlLink.href = dlUrl;
        dlLink.download = m.attachmentName;
        dlLink.className = 'mini-btn';
        dlLink.textContent = 'Download';
        actions.appendChild(dlLink);

        card.appendChild(top);
        card.appendChild(info);
        card.appendChild(actions);
        div.appendChild(card);
      }

      const footer = document.createElement('div');
      footer.className = 'msg-footer';
      const reacts = document.createElement('div');
      reacts.className = 'msg-reactions';
      renderReactionsChips(reacts, m);

      const tools = document.createElement('div');
      tools.className = 'msg-tools';

      const replyBtn = document.createElement('button');
      replyBtn.type = 'button';
      replyBtn.className = 'msg-tool-btn';
      replyBtn.textContent = 'Reply';
      replyBtn.onclick = function () {
        state.replyTo = m;
        el.replyBannerText.textContent = 'Replying to ' + (m.senderName || 'Peer') + ': ' + (m.content || m.attachmentName || '');
        el.replyBanner.classList.remove('hidden');
        el.msgInput.focus();
      };
      tools.appendChild(replyBtn);

      const reactBtn = document.createElement('button');
      reactBtn.type = 'button';
      reactBtn.className = 'msg-tool-btn';
      reactBtn.textContent = '😊';
      reactBtn.title = 'React';
      reactBtn.onclick = function () { openReactModal(m); };
      tools.appendChild(reactBtn);

      const fwdBtn = document.createElement('button');
      fwdBtn.type = 'button';
      fwdBtn.className = 'msg-tool-btn';
      fwdBtn.textContent = 'Forward';
      fwdBtn.onclick = function () { openForwardModal(m); };
      tools.appendChild(fwdBtn);

      if (isMine && m.type === 'TEXT') {
        const editBtn = document.createElement('button');
        editBtn.type = 'button';
        editBtn.className = 'msg-tool-btn';
        editBtn.textContent = 'Edit';
        editBtn.onclick = function () { openEditModal(m); };
        tools.appendChild(editBtn);
      }

      if (isMine) {
        const delBtn = document.createElement('button');
        delBtn.type = 'button';
        delBtn.className = 'msg-tool-btn danger';
        delBtn.textContent = 'Delete';
        delBtn.onclick = function () {
          sendPacket('MESSAGE_DELETE', { chatId: state.activeChatId, messageId: m.messageId });
          state.messages = state.messages.map(function (x) {
            return x.messageId === m.messageId ? Object.assign({}, x, { isDeletedForEveryone: true, content: '' }) : x;
          });
          renderMessages();
        };
        tools.appendChild(delBtn);
      }

      footer.appendChild(reacts);
      footer.appendChild(tools);
      div.appendChild(footer);
      el.messagesBox.appendChild(div);
    });
    el.messagesBox.scrollTop = el.messagesBox.scrollHeight;
  }

  function renderReactionsChips(container, msg) {
    const json = msg.reactionsJson;
    if (!json || json === '{}') return;
    try {
      const map = JSON.parse(json);
      Object.keys(map).forEach(function (emoji) {
        const arr = map[emoji] || [];
        if (arr.length > 0) {
          const btn = document.createElement('button');
          btn.type = 'button';
          btn.className = 'react-chip';
          btn.textContent = emoji + ' ' + arr.length;
          btn.title = 'Click to toggle or inspect';
          btn.onclick = function () { openReactModal(msg); };
          container.appendChild(btn);
        }
      });
    } catch (ignored) {}
  }

  function openReactModal(msg) {
    state.reactingMsgId = msg.messageId;
    el.emojiPickerGrid.innerHTML = '';
    EMOJIS.forEach(function (emoji) {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'emoji-pick-btn';
      b.textContent = emoji;
      b.onclick = function () {
        sendPacket('MESSAGE_REACTION', {
          chatId: state.activeChatId,
          messageId: msg.messageId,
          emoji: emoji
        });
        el.reactModal.classList.add('hidden');
        setTimeout(refreshInfo, 150);
      };
      el.emojiPickerGrid.appendChild(b);
    });

    el.whoReactedList.innerHTML = '';
    try {
      const map = msg.reactionsJson ? JSON.parse(msg.reactionsJson) : {};
      const entries = Object.keys(map).filter(function (k) { return (map[k] || []).length > 0; });
      if (entries.length === 0) {
        el.whoReactedList.textContent = 'No reactions yet.';
      } else {
        entries.forEach(function (emoji) {
          const names = (map[emoji] || []).map(function (entry) {
            return typeof entry === 'string' ? entry : (entry.name || entry.deviceId || 'User');
          }).join(', ');
          const row = document.createElement('div');
          row.textContent = emoji + ' — ' + names;
          el.whoReactedList.appendChild(row);
        });
      }
    } catch (e) {
      el.whoReactedList.textContent = '';
    }
    el.reactModal.classList.remove('hidden');
  }

  el.reactCloseBtn.onclick = function () { el.reactModal.classList.add('hidden'); };

  function openEditModal(msg) {
    state.editingMsg = msg;
    el.editModalInput.value = msg.content || '';
    el.editModal.classList.remove('hidden');
    el.editModalInput.focus();
  }

  el.editCancelBtn.onclick = function () {
    state.editingMsg = null;
    el.editModal.classList.add('hidden');
  };

  el.editSaveBtn.onclick = function () {
    const m = state.editingMsg;
    if (!m) return;
    const updated = el.editModalInput.value.trim();
    if (!updated) return;
    const editedAt = Date.now();
    sendPacket('MESSAGE_EDIT', {
      chatId: state.activeChatId,
      messageId: m.messageId,
      newContent: updated,
      editedAt: editedAt
    });
    state.messages = state.messages.map(function (x) {
      return x.messageId === m.messageId
        ? Object.assign({}, x, { content: updated, isEdited: true, editedAt: editedAt })
        : x;
    });
    state.editingMsg = null;
    el.editModal.classList.add('hidden');
    renderMessages();
  };

  function openForwardModal(msg) {
    state.forwardingMsg = msg;
    el.forwardPreview.textContent = (msg.senderName || 'Peer') + ': ' + (msg.content || msg.attachmentName || 'Attachment');
    el.forwardTargetsList.innerHTML = '';

    const targets = [];
    state.groups.filter(function (g) { return !g.isPrivate || g.authorized; }).forEach(function (g) {
      targets.push({ chatId: g.groupId, targetId: null, label: 'Group: ' + g.name });
    });
    state.peers.filter(function (p) { return p.deviceId !== state.webUserId; }).forEach(function (p) {
      targets.push({ chatId: makeDmChatId(state.webUserId, p.deviceId), targetId: p.deviceId, label: 'Direct: ' + p.name });
    });

    targets.forEach(function (t) {
      const li = document.createElement('li');
      li.textContent = t.label;
      li.onclick = function () {
        const fwd = {
          messageId: 'msg-' + Date.now() + '-' + Math.random().toString(36).slice(2, 7),
          chatId: t.chatId,
          senderId: state.webUserId,
          senderName: state.displayName || 'Web User',
          senderIsWeb: true,
          type: msg.type || 'TEXT',
          content: msg.content || ('Shared file: ' + (msg.attachmentName || '')),
          attachmentId: msg.attachmentId || null,
          attachmentName: msg.attachmentName || null,
          attachmentMime: msg.attachmentMime || null,
          attachmentSize: msg.attachmentSize || 0,
          attachmentSha256: msg.attachmentSha256 || null,
          thumbnailBase64: msg.thumbnailBase64 || null,
          replyToPreview: 'Forwarded from ' + (msg.senderName || 'Peer'),
          createdAt: Date.now(),
          status: 'SENT'
        };
        sendOrQueueMessage(t.chatId, t.targetId, fwd);
        el.forwardModal.classList.add('hidden');
        state.forwardingMsg = null;
      };
      el.forwardTargetsList.appendChild(li);
    });

    el.forwardModal.classList.remove('hidden');
  }

  el.forwardCancelBtn.onclick = function () {
    state.forwardingMsg = null;
    el.forwardModal.classList.add('hidden');
  };

  function openRoomInfoModal() {
    el.infoModalBody.innerHTML = '';
    const peer = getActivePeer();
    const grp = getActiveGroup();

    function addKv(k, v) {
      const row = document.createElement('div');
      row.className = 'info-kv';
      row.innerHTML = '<span class="muted">' + escapeHtml(k) + '</span><span>' + escapeHtml(v) + '</span>';
      el.infoModalBody.appendChild(row);
    }

    if (peer) {
      el.infoModalKind.textContent = 'PEER PROFILE';
      el.infoModalTitle.textContent = peer.name || 'Peer';
      addKv('Type', peer.isWebUser ? 'Web Browser Companion' : 'Android App Device');
      addKv('Status', (peer.isOnline ? 'Online' : 'Offline') + ' (' + (peer.activeStatus || 'ONLINE') + ')');
      addKv('IP Address', peer.ipAddress || 'Local');
      addKv('Bio', peer.bio || 'Local WiFi Call participant');
      addKv('Device ID', peer.deviceId || '');
    } else if (grp) {
      el.infoModalKind.textContent = grp.isPrivate ? 'PRIVATE GROUP' : 'PUBLIC GROUP';
      el.infoModalTitle.textContent = grp.name || 'Group';
      addKv('Description', grp.description || 'Local WiFi Group');
      addKv('Created By', grp.creatorName || 'Host');
      addKv('Permissions', grp.adminsOnlySend ? 'Admins Only Send' : 'Everyone Can Send');
      addKv('Members', String((grp.members || []).length || grp.memberCount || 0));
      if (grp.members && grp.members.length > 0) {
        const title = document.createElement('strong');
        title.style.marginTop = '8px';
        title.textContent = 'Member List';
        el.infoModalBody.appendChild(title);
        grp.members.forEach(function (m) {
          addKv((m.displayName || m.deviceId) + (m.role ? ' [' + m.role + ']' : ''), m.isOnline ? 'Online' : 'Offline');
        });
      }
    } else {
      el.infoModalKind.textContent = 'PUBLIC ROOM';
      el.infoModalTitle.textContent = 'Everyone Room';
      addKv('Scope', 'All discovered Android & Web peers on this WiFi LAN');
    }
    el.roomInfoModal.classList.remove('hidden');
  }

  el.btnRoomInfo.onclick = openRoomInfoModal;
  el.infoModalClose.onclick = function () { el.roomInfoModal.classList.add('hidden'); };

  el.btnSearchToggle.onclick = function () {
    el.searchBar.classList.toggle('hidden');
    if (!el.searchBar.classList.contains('hidden')) {
      el.searchInput.focus();
    } else {
      state.searchQuery = '';
      el.searchInput.value = '';
      renderMessages();
    }
  };
  el.searchInput.oninput = function () {
    state.searchQuery = el.searchInput.value;
    renderMessages();
  };
  el.searchClear.onclick = function () {
    state.searchQuery = '';
    el.searchInput.value = '';
    el.searchBar.classList.add('hidden');
    renderMessages();
  };

  function openImageModal(msg, thumbSrc) {
    state.zoomScale = 1;
    el.imgModalPreview.style.transform = 'scale(1)';
    el.imgModalTitle.textContent = msg.attachmentName || 'Image';
    el.imgModalSub.textContent = (msg.senderName || 'Peer') + ' • ' + formatBytes(msg.attachmentSize);
    el.imgModalPreview.src = thumbSrc;
    const dlUrl = '/api/download?transferId=' + encodeURIComponent(msg.attachmentId);
    el.imgModalDownload.href = dlUrl;
    el.imgModalDownload.download = msg.attachmentName || 'image.jpg';
    el.imgModal.classList.remove('hidden');
  }

  el.imgZoomIn.onclick = function () {
    state.zoomScale = Math.min(4, state.zoomScale + 0.5);
    el.imgModalPreview.style.transform = 'scale(' + state.zoomScale + ')';
  };
  el.imgZoomOut.onclick = function () {
    state.zoomScale = Math.max(1, state.zoomScale - 0.5);
    el.imgModalPreview.style.transform = 'scale(' + state.zoomScale + ')';
  };
  el.imgZoomReset.onclick = function () {
    state.zoomScale = 1;
    el.imgModalPreview.style.transform = 'scale(1)';
  };
  el.imgModalClose.onclick = function () { el.imgModal.classList.add('hidden'); };

  // --- WebRTC Calling (1-to-1 + Mesh Group Calling with Honest HTTPS Guard) ---
  async function startOutgoingCall(peer, callType) {
    if (!canCaptureMedia) {
      alert('Voice/Video calling requires HTTPS or localhost because browsers block microphone/camera access on plain HTTP LAN URLs.');
      return;
    }
    if (state.call.active) return;

    const callId = 'call-' + Date.now() + '-' + Math.random().toString(36).slice(2, 7);
    resetCallState();
    state.call.active = true;
    state.call.isGroupCall = false;
    state.call.callId = callId;
    state.call.peerId = peer.deviceId;
    state.call.peerName = peer.name;
    state.call.callType = callType;
    state.call.isIncoming = false;

    showCallModal('Ringing…', false);
    try {
      const isVideo = callType === 'VIDEO';
      const constraints = isVideo ? { audio: true, video: { facingMode: state.call.facingMode } } : { audio: true, video: false };
      const stream = await navigator.mediaDevices.getUserMedia(constraints);
      state.call.localStream = stream;
      if (isVideo) {
        el.localVideo.srcObject = stream;
        el.callVideoStage.classList.remove('hidden');
        el.btnCallCam.classList.remove('hidden');
        el.btnCallFlip.classList.remove('hidden');
      }

      const pc = createPeerConnection(peer.deviceId, callId, null);
      stream.getTracks().forEach(function (track) { pc.addTrack(track, stream); });
      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);

      sendPacket('CALL_OFFER', {
        targetId: peer.deviceId,
        callId: callId,
        callType: callType,
        sdp: offer.sdp
      });
    } catch (err) {
      alert('Unable to start media call: ' + (err && err.message ? err.message : 'Permission denied'));
      endCurrentCall(true);
    }
  }

  async function startOrJoinGroupCall(groupId, groupName, callType, existingCallId) {
    if (!canCaptureMedia) {
      alert('Group WebRTC calling requires HTTPS or localhost because browsers block microphone/camera access on plain HTTP LAN URLs.');
      return;
    }
    if (state.featureFlags && state.featureFlags.groupCallsEnabled === false) {
      alert('Group calls are currently disabled via Remote Config.');
      return;
    }
    if (state.call.active) return;

    const existing = existingCallId ? (state.activeGroupCalls || []).find(function (c) { return c.callId === existingCallId; })
      : (state.activeGroupCalls || []).find(function (c) { return c.groupId === groupId; });
    const callId = existing ? existing.callId : ('gcall-' + Date.now() + '-' + Math.random().toString(36).slice(2, 7));
    const isVideo = callType === 'GROUP_VIDEO' || callType === 'VIDEO';

    resetCallState();
    state.call.active = true;
    state.call.isGroupCall = true;
    state.call.callId = callId;
    state.call.groupId = groupId;
    state.call.peerName = groupName || 'Group Call';
    state.call.callType = isVideo ? 'GROUP_VIDEO' : 'GROUP_VOICE';
    state.call.isIncoming = false;

    showCallModal('Joining Group Mesh…', false);
    el.callParticipantsStrip.classList.remove('hidden');

    try {
      const constraints = isVideo ? { audio: true, video: { facingMode: state.call.facingMode } } : { audio: true, video: false };
      const stream = await navigator.mediaDevices.getUserMedia(constraints);
      state.call.localStream = stream;
      if (isVideo) {
        el.groupMediaGrid.classList.remove('hidden');
        el.btnCallCam.classList.remove('hidden');
        el.btnCallFlip.classList.remove('hidden');
        addOrUpdateGroupMediaTile(state.webUserId, (state.displayName || 'Me') + ' (You)', stream, true, true);
      }
      startCallTimer();
      el.callStatusText.textContent = 'Connected to Group Call';

      sendPacket(existing ? 'GROUP_CALL_JOIN' : 'GROUP_CALL_INVITE', {
        callId: callId,
        groupId: groupId,
        groupName: groupName || 'Group Call',
        callType: state.call.callType,
        muted: false,
        videoEnabled: isVideo
      });
      syncActiveGroupCallParticipants();
    } catch (err) {
      alert('Unable to start group media call: ' + (err && err.message ? err.message : 'Permission denied'));
      endCurrentCall(true);
    }
  }

  function syncActiveGroupCallParticipants() {
    if (!state.call.active || !state.call.isGroupCall) return;
    const room = (state.activeGroupCalls || []).find(function (c) { return c.callId === state.call.callId; });
    el.callParticipantsStrip.innerHTML = '';
    const selfPill = document.createElement('span');
    selfPill.className = 'participant-pill';
    selfPill.textContent = (state.displayName || 'You') + (state.call.muted ? ' (Muted)' : '');
    el.callParticipantsStrip.appendChild(selfPill);

    if (!room || !room.participants) return;
    room.participants.forEach(function (p) {
      if (!p.deviceId || p.deviceId === state.webUserId) return;
      const pill = document.createElement('span');
      pill.className = 'participant-pill';
      pill.textContent = (p.displayName || p.deviceId) + (p.muted ? ' 🔇' : '');
      el.callParticipantsStrip.appendChild(pill);

      if (!state.call.meshPcs[p.deviceId] && state.webUserId < p.deviceId) {
        initiateMeshOffer(p.deviceId, p.displayName || 'Peer');
      }
    });
  }

  async function initiateMeshOffer(targetPeerId, targetName) {
    if (!state.call.localStream || state.call.meshPcs[targetPeerId]) return;
    const pc = createPeerConnection(targetPeerId, state.call.callId, state.call.groupId, targetName);
    state.call.localStream.getTracks().forEach(function (track) { pc.addTrack(track, state.call.localStream); });
    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    sendPacket('CALL_OFFER', {
      targetId: targetPeerId,
      callId: state.call.callId,
      groupId: state.call.groupId,
      callType: state.call.callType,
      sdp: offer.sdp
    });
  }

  function addOrUpdateGroupMediaTile(peerId, label, stream, isVideo, isLocal) {
    if (!isVideo) {
      const audioId = 'grp-aud-' + peerId;
      let aud = document.getElementById(audioId);
      if (!aud && !isLocal) {
        aud = document.createElement('audio');
        aud.id = audioId;
        aud.autoplay = true;
        el.callModal.appendChild(aud);
      }
      if (aud) aud.srcObject = stream;
      return;
    }
    el.groupMediaGrid.classList.remove('hidden');
    const tileId = 'grp-tile-' + peerId;
    let tile = document.getElementById(tileId);
    if (!tile) {
      tile = document.createElement('div');
      tile.id = tileId;
      tile.className = 'group-video-tile';
      const v = document.createElement('video');
      v.autoplay = true;
      v.playsInline = true;
      if (isLocal) v.muted = true;
      const lbl = document.createElement('div');
      lbl.className = 'group-video-label';
      lbl.textContent = label || peerId;
      tile.appendChild(v);
      tile.appendChild(lbl);
      el.groupMediaGrid.appendChild(tile);
    }
    const vid = tile.querySelector('video');
    if (vid && vid.srcObject !== stream) vid.srcObject = stream;
  }

  function createPeerConnection(targetPeerId, callId, groupId, targetLabel) {
    const pc = new RTCPeerConnection({ iceServers: [] });
    if (groupId) {
      state.call.meshPcs[targetPeerId] = pc;
    } else {
      state.call.pc = pc;
    }

    pc.onicecandidate = function (ev) {
      if (ev.candidate) {
        sendPacket('CALL_ICE', {
          targetId: targetPeerId,
          callId: callId,
          groupId: groupId || undefined,
          iceCandidate: ev.candidate.candidate,
          iceSdpMid: ev.candidate.sdpMid,
          iceSdpMLineIndex: ev.candidate.sdpMLineIndex
        });
      }
    };

    pc.ontrack = function (ev) {
      const stream = ev.streams[0];
      if (!stream) return;
      if (groupId) {
        const isVideo = state.call.callType === 'GROUP_VIDEO';
        addOrUpdateGroupMediaTile(targetPeerId, targetLabel || targetPeerId, stream, isVideo, false);
      } else {
        if (state.call.callType === 'VIDEO') {
          el.remoteVideo.srcObject = stream;
        } else {
          el.remoteAudio.srcObject = stream;
        }
        if (!state.call.connectedAt) {
          startCallTimer();
        }
      }
    };

    pc.onconnectionstatechange = function () {
      if (pc.connectionState === 'connected') {
        el.callStatusText.textContent = 'Connected';
        if (!state.call.connectedAt) startCallTimer();
      } else if (!groupId && (pc.connectionState === 'failed' || pc.connectionState === 'closed' || pc.connectionState === 'disconnected')) {
        endCurrentCall(false);
      }
    };
    return pc;
  }

  async function handleIncomingCallSignal(type, pkt) {
    if (type === 'GROUP_CALL_INVITE' || type === 'GROUP_CALL_JOIN' || type === 'GROUP_CALL_STATE' || type === 'GROUP_CALL_LEAVE') {
      if (type === 'GROUP_CALL_JOIN' && state.call.active && state.call.isGroupCall && pkt.callId === state.call.callId) {
        if (pkt.senderId && !state.call.meshPcs[pkt.senderId] && state.webUserId < pkt.senderId) {
          await initiateMeshOffer(pkt.senderId, pkt.senderName || 'Peer');
        }
      } else if (type === 'GROUP_CALL_LEAVE' && state.call.active && state.call.isGroupCall && pkt.callId === state.call.callId && pkt.senderId) {
        const pc = state.call.meshPcs[pkt.senderId];
        if (pc) { try { pc.close(); } catch (e) {} delete state.call.meshPcs[pkt.senderId]; }
        const tile = document.getElementById('grp-tile-' + pkt.senderId);
        if (tile) tile.remove();
        const aud = document.getElementById('grp-aud-' + pkt.senderId);
        if (aud) aud.remove();
      }
      refreshInfo();
      return;
    }

    if (type === 'CALL_OFFER') {
      if (pkt.groupId && state.call.active && state.call.isGroupCall && pkt.callId === state.call.callId) {
        const pc = createPeerConnection(pkt.senderId, pkt.callId, pkt.groupId, pkt.senderName || 'Peer');
        if (state.call.localStream) {
          state.call.localStream.getTracks().forEach(function (track) { pc.addTrack(track, state.call.localStream); });
        }
        await pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp: pkt.sdp }));
        const answer = await pc.createAnswer();
        await pc.setLocalDescription(answer);
        sendPacket('CALL_ANSWER', {
          targetId: pkt.senderId,
          callId: pkt.callId,
          groupId: pkt.groupId,
          sdp: answer.sdp
        });
        return;
      }
      if (state.call.active) {
        sendPacket('CALL_BUSY', { targetId: pkt.senderId, callId: pkt.callId });
        return;
      }
      resetCallState();
      state.call.active = true;
      state.call.isGroupCall = false;
      state.call.callId = pkt.callId;
      state.call.peerId = pkt.senderId;
      state.call.peerName = pkt.senderName || 'Local Peer';
      state.call.callType = pkt.callType || 'VOICE';
      state.call.isIncoming = true;
      state.call.pendingOfferSdp = pkt.sdp;

      sendPacket('CALL_RINGING', { targetId: pkt.senderId, callId: pkt.callId });
      showCallModal('Incoming ' + (state.call.callType === 'VIDEO' ? 'Video Call' : 'Voice Call'), true);
    } else if (state.call.active && pkt.callId === state.call.callId) {
      const targetPc = (state.call.isGroupCall && pkt.senderId) ? state.call.meshPcs[pkt.senderId] : state.call.pc;
      if (type === 'CALL_RINGING') {
        el.callStatusText.textContent = 'Ringing…';
      } else if (type === 'CALL_BUSY') {
        el.callStatusText.textContent = pkt.reason || 'User is busy';
        setTimeout(function () { endCurrentCall(false); }, 1200);
      } else if (type === 'CALL_REJECT') {
        el.callStatusText.textContent = 'Call declined';
        setTimeout(function () { endCurrentCall(false); }, 900);
      } else if (type === 'CALL_ANSWER' && targetPc && pkt.sdp) {
        await targetPc.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp: pkt.sdp }));
        el.callStatusText.textContent = 'Connecting WebRTC…';
      } else if (type === 'CALL_ICE' && targetPc && pkt.iceCandidate) {
        try {
          await targetPc.addIceCandidate(new RTCIceCandidate({
            candidate: pkt.iceCandidate,
            sdpMid: pkt.iceSdpMid,
            sdpMLineIndex: pkt.iceSdpMLineIndex || 0
          }));
        } catch (ignored) {}
      } else if (type === 'CALL_END' && !state.call.isGroupCall) {
        el.callStatusText.textContent = 'Call ended';
        setTimeout(function () { endCurrentCall(false); }, 700);
      }
    }
  }

  el.btnCallAccept.onclick = async function () {
    if (!canCaptureMedia) {
      alert('Cannot answer WebRTC call without HTTPS secure context or localhost (browser blocks microphone/camera on plain HTTP).');
      endCurrentCall(true, true);
      return;
    }
    try {
      const isVideo = state.call.callType === 'VIDEO';
      const stream = await navigator.mediaDevices.getUserMedia(isVideo ? { audio: true, video: { facingMode: state.call.facingMode } } : { audio: true, video: false });
      state.call.localStream = stream;
      if (isVideo) {
        el.localVideo.srcObject = stream;
        el.callVideoStage.classList.remove('hidden');
        el.btnCallCam.classList.remove('hidden');
        el.btnCallFlip.classList.remove('hidden');
      }
      el.incomingActions.classList.add('hidden');
      el.activeActions.classList.remove('hidden');
      el.callStatusText.textContent = 'Connecting WebRTC…';

      const pc = createPeerConnection(state.call.peerId, state.call.callId, null);
      stream.getTracks().forEach(function (track) { pc.addTrack(track, stream); });
      await pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp: state.call.pendingOfferSdp }));
      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);
      sendPacket('CALL_ANSWER', {
        targetId: state.call.peerId,
        callId: state.call.callId,
        sdp: answer.sdp
      });
    } catch (err) {
      alert('Media permission error: ' + (err && err.message ? err.message : 'Failed'));
      endCurrentCall(true, true);
    }
  };

  el.btnCallDecline.onclick = function () { endCurrentCall(true, true); };
  el.btnCallEnd.onclick = function () { endCurrentCall(true, false); };

  el.btnCallMute.onclick = function () {
    if (!state.call.localStream) return;
    state.call.muted = !state.call.muted;
    state.call.localStream.getAudioTracks().forEach(function (t) { t.enabled = !state.call.muted; });
    el.btnCallMute.textContent = state.call.muted ? 'Unmute' : 'Mute';
    if (state.call.isGroupCall) {
      sendPacket('GROUP_CALL_STATE', {
        callId: state.call.callId,
        groupId: state.call.groupId,
        muted: state.call.muted,
        videoEnabled: state.call.videoOn
      });
    }
  };

  el.btnCallCam.onclick = function () {
    if (!state.call.localStream) return;
    state.call.videoOn = !state.call.videoOn;
    state.call.localStream.getVideoTracks().forEach(function (t) { t.enabled = state.call.videoOn; });
    el.btnCallCam.textContent = state.call.videoOn ? 'Camera Off' : 'Camera On';
    if (state.call.isGroupCall) {
      sendPacket('GROUP_CALL_STATE', {
        callId: state.call.callId,
        groupId: state.call.groupId,
        muted: state.call.muted,
        videoEnabled: state.call.videoOn
      });
    }
  };

  el.btnCallFlip.onclick = async function () {
    if (!state.call.localStream || !canCaptureMedia) return;
    const nextFacing = state.call.facingMode === 'user' ? 'environment' : 'user';
    try {
      const newStream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: nextFacing }, audio: false });
      const newTrack = newStream.getVideoTracks()[0];
      if (!newTrack) return;
      state.call.facingMode = nextFacing;
      const oldTracks = state.call.localStream.getVideoTracks();
      oldTracks.forEach(function (ot) {
        state.call.localStream.removeTrack(ot);
        ot.stop();
      });
      state.call.localStream.addTrack(newTrack);
      el.localVideo.srcObject = state.call.localStream;

      const pcs = state.call.isGroupCall ? Object.values(state.call.meshPcs) : (state.call.pc ? [state.call.pc] : []);
      pcs.forEach(function (pc) {
        pc.getSenders().forEach(function (sender) {
          if (sender.track && sender.track.kind === 'video') {
            sender.replaceTrack(newTrack);
          }
        });
      });
    } catch (ignored) {}
  };

  function showCallModal(status, isIncoming) {
    el.callTypeBadge.textContent = state.call.callType.replace('_', ' ');
    el.callPeerName.textContent = state.call.peerName || 'Peer';
    el.callPeerAvatar.textContent = (state.call.peerName || 'P').slice(0, 1).toUpperCase();
    el.callStatusText.textContent = status;
    el.callTimerText.textContent = '00:00';
    el.incomingActions.classList.toggle('hidden', !isIncoming);
    el.activeActions.classList.toggle('hidden', isIncoming);
    el.callVideoStage.classList.add('hidden');
    el.groupMediaGrid.innerHTML = '';
    el.groupMediaGrid.classList.add('hidden');
    el.callParticipantsStrip.innerHTML = '';
    el.callParticipantsStrip.classList.add('hidden');
    el.btnCallCam.classList.add('hidden');
    el.btnCallFlip.classList.add('hidden');
    el.callModal.classList.remove('hidden');
  }

  function startCallTimer() {
    state.call.connectedAt = Date.now();
    clearInterval(state.call.timerHandle);
    state.call.timerHandle = setInterval(function () {
      const s = Math.floor((Date.now() - state.call.connectedAt) / 1000);
      const mm = String(Math.floor(s / 60)).padStart(2, '0');
      const ss = String(s % 60).padStart(2, '0');
      el.callTimerText.textContent = mm + ':' + ss;
    }, 1000);
  }

  function endCurrentCall(notifyPeer, isReject) {
    if (notifyPeer && state.call.callId) {
      if (state.call.isGroupCall) {
        sendPacket('GROUP_CALL_LEAVE', { callId: state.call.callId, groupId: state.call.groupId });
      } else if (state.call.peerId) {
        sendPacket(isReject ? 'CALL_REJECT' : 'CALL_END', { targetId: state.call.peerId, callId: state.call.callId });
      }
    }
    resetCallState();
    el.callModal.classList.add('hidden');
    refreshInfo();
  }

  function resetCallState() {
    clearInterval(state.call.timerHandle);
    if (state.call.pc) {
      try { state.call.pc.close(); } catch (ignored) {}
    }
    Object.values(state.call.meshPcs || {}).forEach(function (pc) {
      try { pc.close(); } catch (ignored) {}
    });
    if (state.call.localStream) {
      state.call.localStream.getTracks().forEach(function (t) { t.stop(); });
    }
    document.querySelectorAll('[id^="grp-aud-"]').forEach(function (a) { a.remove(); });
    el.groupMediaGrid.innerHTML = '';
    el.localVideo.srcObject = null;
    el.remoteVideo.srcObject = null;
    el.remoteAudio.srcObject = null;
    state.call = {
      active: false, isGroupCall: false, callId: null, groupId: null, peerId: null, peerName: '',
      callType: 'VOICE', isIncoming: false, facingMode: 'user', pc: null, meshPcs: {},
      remoteStreams: {}, localStream: null, muted: false, videoOn: true,
      timerHandle: null, connectedAt: 0, pendingOfferSdp: null
    };
  }

  function switchMobileTab(tabName) {
    const btns = el.mobileTabs.querySelectorAll('.tab-btn');
    btns.forEach(function (b) { b.classList.toggle('active', b.getAttribute('data-tab') === tabName); });

    if (tabName === 'chat') {
      el.sidebarPane.classList.remove('mobile-visible');
      el.chatPane.classList.remove('mobile-hidden');
    } else {
      el.sidebarPane.classList.add('mobile-visible');
      el.chatPane.classList.add('mobile-hidden');
      const panels = el.sidebarPane.querySelectorAll('.panel');
      panels.forEach(function (p) {
        const pType = p.getAttribute('data-pane');
        if (tabName === 'nearby') p.style.display = (pType === 'chats' || pType === 'private-chats' || pType === 'nearby') ? 'block' : 'none';
        else if (tabName === 'calls') p.style.display = pType === 'calls' ? 'block' : 'none';
        else if (tabName === 'info') p.style.display = pType === 'info' ? 'block' : 'none';
      });
    }
  }

  el.mobileTabs.querySelectorAll('.tab-btn').forEach(function (btn) {
    btn.onclick = function () { switchMobileTab(btn.getAttribute('data-tab')); };
  });

  async function refreshInfo() {
    try {
      const res = await fetch('/api/info?peerId=' + encodeURIComponent(state.webUserId));
      const data = await res.json();
      el.hostInfo.textContent = 'Host: ' + data.hostName + ' (' + data.ip + ':' + data.port + ')';
      applySnapshot(data);
    } catch (e) {
      el.hostInfo.textContent = 'Local host unreachable';
    }
  }

  el.joinBtn.onclick = async function () {
    const name = el.nameInput.value.trim();
    if (name.length < 2 || name.length > 30) {
      alert('Display name must be 2–30 characters.');
      return;
    }
    state.displayName = name;
    localStorage.setItem('wcl_web_name', name);
    el.meBadge.textContent = name + ' • Web';
    el.joinModal.classList.add('hidden');
    await fetch('/api/web/join', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceId: state.webUserId, name: name })
    });
    await refreshInfo();
    connectWs();
  };

  el.replyCancelBtn.onclick = function () {
    state.replyTo = null;
    el.replyBanner.classList.add('hidden');
  };

  el.msgInput.oninput = function () {
    const typing = el.msgInput.value.trim().length > 0;
    sendPacket('TYPING', { chatId: state.activeChatId, targetId: state.activeTargetPeerId, typing: typing });
    clearTimeout(state.typingTimer);
    if (typing) {
      state.typingTimer = setTimeout(function () {
        sendPacket('TYPING', { chatId: state.activeChatId, targetId: state.activeTargetPeerId, typing: false });
      }, 2000);
    }
  };

  function sendOrQueueMessage(chatId, targetId, msg) {
    const sent = sendPacket('CHAT_MESSAGE', {
      chatId: chatId,
      targetId: targetId || null,
      messageId: msg.messageId,
      message: msg
    });
    if (sent) {
      addOrUpdateMessage(msg);
    } else {
      const queuedMsg = Object.assign({}, msg, { status: 'QUEUED' });
      state.outbox.push({ chatId: chatId, targetId: targetId || null, message: queuedMsg });
      saveOutbox(state.outbox);
      addOrUpdateMessage(queuedMsg);
    }
  }

  el.composerForm.onsubmit = function (e) {
    e.preventDefault();
    const text = el.msgInput.value.trim();
    if (!text) return;
    const msg = {
      messageId: 'msg-' + Date.now() + '-' + Math.random().toString(36).slice(2, 7),
      chatId: state.activeChatId,
      senderId: state.webUserId,
      senderName: state.displayName || 'Web User',
      senderIsWeb: true,
      type: 'TEXT',
      content: text,
      replyToMessageId: state.replyTo ? state.replyTo.messageId : null,
      replyToPreview: state.replyTo ? (state.replyTo.senderName + ': ' + (state.replyTo.content || state.replyTo.attachmentName || '')) : null,
      createdAt: Date.now(),
      status: 'SENT'
    };
    sendOrQueueMessage(state.activeChatId, state.activeTargetPeerId, msg);
    el.msgInput.value = '';
    state.replyTo = null;
    el.replyBanner.classList.add('hidden');
    sendPacket('TYPING', { chatId: state.activeChatId, targetId: state.activeTargetPeerId, typing: false });
  };

  function uploadFileWithProgress(file, fileNameOverride) {
    if (state.featureFlags && state.featureFlags.fileTransferEnabled === false) {
      alert('File transfers are currently disabled via Remote Config.');
      return;
    }
    if (file.size > MAX_WEB_UPLOAD_BYTES) {
      alert('File exceeds the 100 MB web upload limit.');
      return;
    }
    const form = new FormData();
    form.append('file', file, fileNameOverride || file.name);
    form.append('chatId', state.activeChatId);
    if (state.activeTargetPeerId) form.append('targetId', state.activeTargetPeerId);
    form.append('senderId', state.webUserId);
    form.append('senderName', state.displayName || 'Web User');

    el.uploadProgressWrap.classList.remove('hidden');
    el.uploadProgressLabel.textContent = 'Uploading ' + (fileNameOverride || file.name) + '…';
    el.uploadProgressPct.textContent = '0%';
    el.uploadProgressFill.style.width = '0%';

    const xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/upload', true);
    xhr.upload.onprogress = function (ev) {
      if (ev.lengthComputable) {
        const pct = Math.min(100, Math.round((ev.loaded / ev.total) * 100));
        el.uploadProgressPct.textContent = pct + '%';
        el.uploadProgressFill.style.width = pct + '%';
      }
    };
    xhr.onload = async function () {
      el.uploadProgressWrap.classList.add('hidden');
      if (xhr.status >= 200 && xhr.status < 300) {
        await refreshInfo();
      } else {
        try {
          const err = JSON.parse(xhr.responseText);
          alert(err.error || 'Upload failed.');
        } catch (e) {
          alert('Upload failed (HTTP ' + xhr.status + ').');
        }
      }
    };
    xhr.onerror = function () {
      el.uploadProgressWrap.classList.add('hidden');
      alert('Network error during file upload.');
    };
    xhr.send(form);
  }

  el.fileInput.onchange = function () {
    const file = el.fileInput.files[0];
    if (!file) return;
    el.fileInput.value = '';
    uploadFileWithProgress(file);
  };

  el.btnVoiceNote.onclick = async function () {
    if (state.recorder && state.recorder.state === 'recording') {
      state.recorder.stop();
      return;
    }
    if (!canCaptureMedia || !window.MediaRecorder) {
      alert('Voice note recording requires HTTPS or localhost with MediaRecorder support.');
      return;
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
      state.recorderStream = stream;
      state.recorderChunks = [];
      const rec = new MediaRecorder(stream);
      state.recorder = rec;
      rec.ondataavailable = function (ev) {
        if (ev.data && ev.data.size > 0) state.recorderChunks.push(ev.data);
      };
      rec.onstop = function () {
        el.btnVoiceNote.classList.remove('recording');
        el.btnVoiceNote.textContent = '🎤';
        if (state.recorderStream) {
          state.recorderStream.getTracks().forEach(function (t) { t.stop(); });
          state.recorderStream = null;
        }
        const mimeType = rec.mimeType || 'audio/webm';
        const ext = mimeType.indexOf('ogg') >= 0 ? 'ogg' : 'webm';
        const blob = new Blob(state.recorderChunks, { type: mimeType });
        state.recorder = null;
        if (blob.size > 0) {
          uploadFileWithProgress(blob, 'voice_note_' + Date.now() + '.' + ext);
        }
      };
      rec.start();
      el.btnVoiceNote.classList.add('recording');
      el.btnVoiceNote.textContent = '⏹';
    } catch (err) {
      alert('Microphone permission denied: ' + (err && err.message ? err.message : 'Failed'));
    }
  };

  el.qrToggleBtn.onclick = function () {
    el.qrModal.classList.remove('hidden');
    el.qrUrl.textContent = location.origin;
    if (window.LocalQrRenderer) {
      window.LocalQrRenderer.render(el.qrCanvas, location.origin);
    }
  };
  el.qrCloseBtn.onclick = function () { el.qrModal.classList.add('hidden'); };

  initThemeAndCapabilities();
  if (state.displayName) {
    el.meBadge.textContent = state.displayName + ' • Web';
    el.joinModal.classList.add('hidden');
    fetch('/api/web/join', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceId: state.webUserId, name: state.displayName })
    }).finally(function () {
      refreshInfo();
      connectWs();
    });
  } else {
    refreshInfo();
  }
})();
