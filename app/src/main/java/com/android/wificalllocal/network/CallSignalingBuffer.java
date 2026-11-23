package com.android.wificalllocal.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wificalllocal.network.model.NetPacket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe buffer for outbound WebRTC call signals waiting for socket connection and
 * early incoming call signals (ICE candidates, answers, media states) arriving before
 * CallActivity/WebRtcCallManager initializes. Also tracks terminated call IDs to reject stale replays.
 */
public class CallSignalingBuffer {

    public static final long OUTBOUND_SIGNAL_TTL_MS = 25_000L;
    public static final long INCOMING_SIGNAL_TTL_MS = 45_000L;
    public static final long TERMINATED_CALL_TTL_MS = 300_000L;
    private static final int MAX_OUTBOUND_PER_PEER = 64;
    private static final int MAX_INCOMING_PER_CALL = 128;

    private static final class Entry {
        final NetPacket packet;
        final long createdAtMs;

        Entry(NetPacket packet, long createdAtMs) {
            this.packet = packet;
            this.createdAtMs = createdAtMs;
        }
    }

    private final Map<String, List<Entry>> pendingOutboundByPeer = new ConcurrentHashMap<>();
    private final Map<String, List<Entry>> bufferedIncomingByCall = new ConcurrentHashMap<>();
    private final Map<String, Long> terminatedCalls = new ConcurrentHashMap<>();

    public static boolean isCallSignalingType(@Nullable String type) {
        if (type == null) return false;
        return NetPacket.TYPE_CALL_OFFER.equals(type)
                || NetPacket.TYPE_CALL_ANSWER.equals(type)
                || NetPacket.TYPE_CALL_ICE.equals(type)
                || NetPacket.TYPE_CALL_RINGING.equals(type)
                || NetPacket.TYPE_CALL_BUSY.equals(type)
                || NetPacket.TYPE_CALL_REJECT.equals(type)
                || NetPacket.TYPE_CALL_END.equals(type)
                || NetPacket.TYPE_CALL_STATE.equals(type)
                || NetPacket.TYPE_GROUP_CALL_INVITE.equals(type)
                || NetPacket.TYPE_GROUP_CALL_JOIN.equals(type)
                || NetPacket.TYPE_GROUP_CALL_LEAVE.equals(type)
                || NetPacket.TYPE_GROUP_CALL_STATE.equals(type);
    }

    public static boolean isTerminalCallType(@Nullable String type) {
        return NetPacket.TYPE_CALL_END.equals(type)
                || NetPacket.TYPE_CALL_REJECT.equals(type)
                || NetPacket.TYPE_CALL_BUSY.equals(type);
    }

    public void markCallTerminated(@Nullable String callId, long nowMs) {
        if (callId == null || callId.isEmpty()) return;
        pruneTerminated(nowMs);
        terminatedCalls.put(callId, nowMs);
        bufferedIncomingByCall.remove(callId);
    }

    public boolean isCallTerminated(@Nullable String callId, long nowMs) {
        if (callId == null || callId.isEmpty()) return false;
        Long endedAt = terminatedCalls.get(callId);
        if (endedAt == null) return false;
        if (nowMs - endedAt > TERMINATED_CALL_TTL_MS) {
            terminatedCalls.remove(callId);
            return false;
        }
        return true;
    }

    public void enqueueOutbound(@NonNull String peerId, @NonNull NetPacket packet, long nowMs) {
        List<Entry> list = pendingOutboundByPeer.computeIfAbsent(peerId, k -> new ArrayList<>());
        synchronized (list) {
            pruneExpired(list, nowMs, OUTBOUND_SIGNAL_TTL_MS);
            if (list.size() >= MAX_OUTBOUND_PER_PEER) {
                list.remove(0);
            }
            list.add(new Entry(packet, nowMs));
        }
    }

    @NonNull
    public List<NetPacket> drainOutbound(@NonNull String peerId, long nowMs) {
        List<Entry> list = pendingOutboundByPeer.remove(peerId);
        if (list == null) return Collections.emptyList();
        List<NetPacket> out = new ArrayList<>();
        synchronized (list) {
            for (Entry e : list) {
                if (nowMs - e.createdAtMs <= OUTBOUND_SIGNAL_TTL_MS) {
                    out.add(e.packet);
                }
            }
        }
        return out;
    }

    public void bufferIncoming(@NonNull NetPacket packet, long nowMs) {
        if (packet.callId == null || packet.callId.isEmpty()) return;
        if (isCallTerminated(packet.callId, nowMs)) return;

        List<Entry> list = bufferedIncomingByCall.computeIfAbsent(packet.callId, k -> new ArrayList<>());
        synchronized (list) {
            pruneExpired(list, nowMs, INCOMING_SIGNAL_TTL_MS);
            if (list.size() >= MAX_INCOMING_PER_CALL) {
                list.remove(0);
            }
            list.add(new Entry(packet, nowMs));
        }
    }

    @NonNull
    public List<NetPacket> drainIncomingForCall(@Nullable String callId, long nowMs) {
        if (callId == null || callId.isEmpty()) return Collections.emptyList();
        List<Entry> list = bufferedIncomingByCall.remove(callId);
        if (list == null) return Collections.emptyList();
        List<NetPacket> out = new ArrayList<>();
        synchronized (list) {
            for (Entry e : list) {
                if (nowMs - e.createdAtMs <= INCOMING_SIGNAL_TTL_MS) {
                    out.add(e.packet);
                }
            }
        }
        return out;
    }

    @NonNull
    public List<NetPacket> drainAllIncoming(long nowMs) {
        List<NetPacket> out = new ArrayList<>();
        for (String callId : new ArrayList<>(bufferedIncomingByCall.keySet())) {
            out.addAll(drainIncomingForCall(callId, nowMs));
        }
        return out;
    }

    private void pruneExpired(List<Entry> list, long nowMs, long ttlMs) {
        Iterator<Entry> it = list.iterator();
        while (it.hasNext()) {
            if (nowMs - it.next().createdAtMs > ttlMs) {
                it.remove();
            }
        }
    }

    private void pruneTerminated(long nowMs) {
        for (Map.Entry<String, Long> e : terminatedCalls.entrySet()) {
            if (nowMs - e.getValue() > TERMINATED_CALL_TTL_MS) {
                terminatedCalls.remove(e.getKey());
            }
        }
    }
}
