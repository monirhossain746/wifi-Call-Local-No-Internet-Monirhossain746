package com.android.wificalllocal.storage;

import android.content.Context;

import com.android.wificalllocal.data.local.AppDatabase;
import com.android.wificalllocal.data.local.entity.FileTransferEntity;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages temporary application cache size with LRU cleanup (default target 500 MB)
 * and safe user-triggered "Clear All Cache" without ever touching Room databases,
 * SharedPreferences, messages, contacts, groups, or user-saved files in Downloads/WifiCallLocal/.
 */
public class CacheManager {

    public static final long DEFAULT_MAX_CACHE_MB = 500L;
    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final long STALE_TEMP_AGE_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final long ENFORCE_DEBOUNCE_MS = 30_000L;

    public static class CacheStats {
        public final long totalBytes;
        public final long tempCacheBytes;
        public final long chunkCacheBytes;
        public final long maxCacheBytes;

        public CacheStats(long totalBytes, long tempCacheBytes, long chunkCacheBytes, long maxCacheBytes) {
            this.totalBytes = totalBytes;
            this.tempCacheBytes = tempCacheBytes;
            this.chunkCacheBytes = chunkCacheBytes;
            this.maxCacheBytes = maxCacheBytes;
        }
    }

    public static class ClearResult {
        public final long freedBytes;
        public final long remainingBytes;
        public final boolean fullyUnderLimit;

        public ClearResult(long freedBytes, long remainingBytes, boolean fullyUnderLimit) {
            this.freedBytes = freedBytes;
            this.remainingBytes = remainingBytes;
            this.fullyUnderLimit = fullyUnderLimit;
        }
    }

    public interface StatsCallback {
        void onStats(CacheStats stats);
    }

    public interface ClearCallback {
        void onCleared(ClearResult result);
    }

    private static class CacheCandidate {
        final File file;
        final long size;
        final long lastModified;

        CacheCandidate(File file, long size, long lastModified) {
            this.file = file;
            this.size = size;
            this.lastModified = lastModified;
        }
    }

    private final Context context;
    private final AppDatabase db;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wcl-cache-mgr");
        t.setDaemon(true);
        return t;
    });
    private final Object lock = new Object();
    private volatile long lastEnforceAtMs = 0L;

    public CacheManager(Context context, AppDatabase db) {
        this.context = context.getApplicationContext();
        this.db = db;
    }

    public void getCacheStatsAsync(long maxCacheMb, StatsCallback callback) {
        executor.execute(() -> {
            CacheStats stats = computeCacheStatsSync(maxCacheMb);
            if (callback != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> callback.onStats(stats));
            }
        });
    }

    public CacheStats computeCacheStatsSync(long maxCacheMb) {
        synchronized (lock) {
            long limitMb = maxCacheMb > 0 ? maxCacheMb : DEFAULT_MAX_CACHE_MB;
            long tempBytes = folderSize(context.getCacheDir()) + folderSize(context.getExternalCacheDir());
            long chunkBytes = folderSize(new File(context.getFilesDir(), "transfer_chunks"));
            return new CacheStats(tempBytes + chunkBytes, tempBytes, chunkBytes, limitMb * BYTES_PER_MB);
        }
    }

    public void enforceCacheLimitAsync(long maxCacheMb) {
        long now = System.currentTimeMillis();
        if (now - lastEnforceAtMs < ENFORCE_DEBOUNCE_MS) {
            return;
        }
        executor.execute(() -> enforceCacheLimitSync(maxCacheMb));
    }

    public ClearResult enforceCacheLimitSync(long maxCacheMb) {
        synchronized (lock) {
            lastEnforceAtMs = System.currentTimeMillis();
            long limitMb = maxCacheMb > 0 ? maxCacheMb : DEFAULT_MAX_CACHE_MB;
            long maxBytes = limitMb * BYTES_PER_MB;

            long beforeBytes = computeTotalBytesInternal();
            if (beforeBytes <= maxBytes) {
                return new ClearResult(0L, beforeBytes, true);
            }

            Set<String> activeTransferIds = loadActiveTransferIds();
            long now = System.currentTimeMillis();
            long freed = 0L;

            // Phase 1: remove expired temp files/dirs (> 7 days) first
            freed += purgeExpiredTempEntries(context.getCacheDir(), now - STALE_TEMP_AGE_MS);
            freed += purgeExpiredTempEntries(context.getExternalCacheDir(), now - STALE_TEMP_AGE_MS);
            freed += purgeStaleChunkDirs(new File(context.getFilesDir(), "transfer_chunks"), activeTransferIds, now - STALE_TEMP_AGE_MS);

            long afterPhase1 = computeTotalBytesInternal();
            if (afterPhase1 <= maxBytes) {
                return new ClearResult(freed, afterPhase1, true);
            }

            // Phase 2: LRU deletion across eligible temporary files (never touching DBs, SharedPreferences, Downloads, or active transfers)
            List<CacheCandidate> candidates = new ArrayList<>();
            collectDeletableFiles(context.getCacheDir(), candidates);
            collectDeletableFiles(context.getExternalCacheDir(), candidates);
            collectEligibleChunkFiles(new File(context.getFilesDir(), "transfer_chunks"), activeTransferIds, candidates);

            Collections.sort(candidates, Comparator.comparingLong(c -> c.lastModified));
            long current = afterPhase1;
            for (CacheCandidate cand : candidates) {
                if (current <= maxBytes) break;
                if (cand.file.exists() && cand.file.delete()) {
                    freed += cand.size;
                    current = Math.max(0L, current - cand.size);
                }
            }
            pruneEmptyDirs(new File(context.getFilesDir(), "transfer_chunks"));
            pruneEmptyDirs(context.getCacheDir());
            pruneEmptyDirs(context.getExternalCacheDir());

            long remaining = computeTotalBytesInternal();
            return new ClearResult(freed, remaining, remaining <= maxBytes);
        }
    }

    public void clearAllCacheAsync(long maxCacheMb, ClearCallback callback) {
        executor.execute(() -> {
            ClearResult res = clearAllCacheSync(maxCacheMb);
            if (callback != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> callback.onCleared(res));
            }
        });
    }

    /**
     * Safely clears temporary cache directories (app cache, external cache, Glide disk cache,
     * update APK staging files, non-active transfer chunk staging) while preserving Room SQLite,
     * SharedPreferences, contacts, messages, groups, and public Downloads/WifiCallLocal/ files.
     */
    public ClearResult clearAllCacheSync(long maxCacheMb) {
        synchronized (lock) {
            long limitMb = maxCacheMb > 0 ? maxCacheMb : DEFAULT_MAX_CACHE_MB;
            long maxBytes = limitMb * BYTES_PER_MB;
            long before = computeTotalBytesInternal();
            Set<String> activeIds = loadActiveTransferIds();

            deleteChildrenPreservingActive(context.getCacheDir(), null);
            deleteChildrenPreservingActive(context.getExternalCacheDir(), null);
            deleteChildrenPreservingActive(new File(context.getFilesDir(), "transfer_chunks"), activeIds);

            long remaining = computeTotalBytesInternal();
            long freed = Math.max(0L, before - remaining);
            return new ClearResult(freed, remaining, remaining <= maxBytes);
        }
    }

    public static String formatSizeMb(long bytes) {
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private long computeTotalBytesInternal() {
        return folderSize(context.getCacheDir())
                + folderSize(context.getExternalCacheDir())
                + folderSize(new File(context.getFilesDir(), "transfer_chunks"));
    }

    private Set<String> loadActiveTransferIds() {
        Set<String> ids = new HashSet<>();
        if (db == null) return ids;
        try {
            List<FileTransferEntity> all = db.fileTransferDao().getAllSync();
            if (all != null) {
                for (FileTransferEntity ft : all) {
                    if (ft != null && ft.transferId != null && (
                            "TRANSFERRING".equals(ft.state)
                                    || "QUEUED".equals(ft.state)
                                    || "PAUSED".equals(ft.state))) {
                        ids.add(ft.transferId);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return ids;
    }

    private long purgeExpiredTempEntries(File dir, long cutoffMs) {
        if (dir == null || !dir.exists()) return 0L;
        File[] children = dir.listFiles();
        if (children == null) return 0L;
        long freed = 0L;
        for (File child : children) {
            if (child.isDirectory()) {
                freed += purgeExpiredTempEntries(child, cutoffMs);
                File[] remaining = child.listFiles();
                if (remaining == null || remaining.length == 0) {
                    child.delete();
                }
            } else if (child.lastModified() <= cutoffMs) {
                long len = child.length();
                if (child.delete()) {
                    freed += len;
                }
            }
        }
        return freed;
    }

    private long purgeStaleChunkDirs(File chunkRoot, Set<String> activeIds, long cutoffMs) {
        if (chunkRoot == null || !chunkRoot.exists()) return 0L;
        File[] dirs = chunkRoot.listFiles();
        if (dirs == null) return 0L;
        long freed = 0L;
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            if (activeIds.contains(d.getName())) continue;
            if (d.lastModified() <= cutoffMs) {
                long sz = folderSize(d);
                deleteRecursive(d);
                if (!d.exists()) freed += sz;
            }
        }
        return freed;
    }

    private void collectDeletableFiles(File dir, List<CacheCandidate> out) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) {
                collectDeletableFiles(f, out);
            } else {
                out.add(new CacheCandidate(f, f.length(), f.lastModified()));
            }
        }
    }

    private void collectEligibleChunkFiles(File chunkRoot, Set<String> activeIds, List<CacheCandidate> out) {
        if (chunkRoot == null || !chunkRoot.exists()) return;
        File[] dirs = chunkRoot.listFiles();
        if (dirs == null) return;
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            if (activeIds.contains(d.getName())) continue;
            collectDeletableFiles(d, out);
        }
    }

    private void deleteChildrenPreservingActive(File root, Set<String> activeIds) {
        if (root == null || !root.exists()) return;
        File[] children = root.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (activeIds != null && activeIds.contains(child.getName())) {
                continue;
            }
            deleteRecursive(child);
        }
    }

    private void pruneEmptyDirs(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                pruneEmptyDirs(child);
                File[] nested = child.listFiles();
                if (nested == null || nested.length == 0) {
                    child.delete();
                }
            }
        }
    }

    private long folderSize(File file) {
        if (file == null || !file.exists()) return 0L;
        if (!file.isDirectory()) return file.length();
        File[] children = file.listFiles();
        if (children == null) return 0L;
        long total = 0L;
        for (File child : children) {
            total += folderSize(child);
        }
        return total;
    }

    private void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }
}
