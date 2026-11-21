package com.android.wificalllocal.files;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import androidx.documentfile.provider.DocumentFile;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.android.wificalllocal.data.local.AppDatabase;
import com.android.wificalllocal.data.local.entity.FileTransferEntity;
import com.android.wificalllocal.data.local.entity.PeerEntity;
import com.android.wificalllocal.network.NetworkUtils;
import com.android.wificalllocal.network.model.NetPacket;
import com.android.wificalllocal.security.CryptoManager;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class FileTransferManager {

    public static final int CHUNK_SIZE_BYTES = 1024 * 1024; // 1 MB chunks
    private static final int MAX_CHUNK_RESPONSE_BYTES = CHUNK_SIZE_BYTES + 4096;
    private static final int MAX_CHUNK_RETRIES = 3;
    private static final Pattern SAFE_TRANSFER_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private final Context context;
    private final AppDatabase db;
    private final Gson gson = new Gson();
    private final ExecutorService transferExecutor = Executors.newFixedThreadPool(3);
    private final Map<String, Boolean> pausedFlags = new ConcurrentHashMap<>();
    private final Map<String, Boolean> cancelledFlags = new ConcurrentHashMap<>();

    public FileTransferManager(Context context, AppDatabase db) {
        this.context = context.getApplicationContext();
        this.db = db;
    }

    public static boolean isValidTransferId(String transferId) {
        return transferId != null && SAFE_TRANSFER_ID_PATTERN.matcher(transferId).matches();
    }

    /**
     * Sanitizes peer-supplied filenames against path traversal ("../"), directory separators,
     * quotes, and control characters (CR/LF/NUL) before filesystem or HTTP header use.
     */
    public static String sanitizeFileName(String rawName) {
        if (rawName == null) return "file_" + System.currentTimeMillis();
        String s = rawName.replace('\0', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace("\"", "")
                .trim();
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        while (s.contains("..")) {
            s = s.replace("..", "_");
        }
        while (s.startsWith(".")) {
            s = s.substring(1);
        }
        s = s.replaceAll("[<>:\"|?*\\\\/]", "_").trim();
        if (s.length() > 180) {
            int dot = s.lastIndexOf('.');
            if (dot > 0 && s.length() - dot <= 16) {
                String ext = s.substring(dot);
                s = s.substring(0, 180 - ext.length()) + ext;
            } else {
                s = s.substring(0, 180);
            }
        }
        return s.isEmpty() ? ("file_" + System.currentTimeMillis()) : s;
    }

    public File getChunkCacheDir(String transferId) {
        if (!isValidTransferId(transferId)) {
            throw new SecurityException("Invalid transferId");
        }
        File root = new File(context.getFilesDir(), "transfer_chunks");
        File dir = new File(root, transferId);
        try {
            String rootCanonical = root.getCanonicalPath();
            String dirCanonical = dir.getCanonicalPath();
            if (!dirCanonical.startsWith(rootCanonical + File.separator)) {
                throw new SecurityException("Path traversal blocked");
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            throw new SecurityException("Unable to verify cache path", e);
        }
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static String resolveDisplayName(Context context, Uri uri) {
        String name = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) name = cursor.getString(idx);
                }
            } catch (Exception ignored) {
            }
        }
        if (name == null) {
            DocumentFile df = DocumentFile.fromSingleUri(context, uri);
            if (df != null) name = df.getName();
        }
        if (name == null) {
            name = uri.getLastPathSegment();
        }
        return sanitizeFileName(name);
    }

    public static String resolveMimeType(Context context, Uri uri, String fileName) {
        String mime = context.getContentResolver().getType(uri);
        if (mime != null && !mime.isEmpty()) return mime;
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            String ext = fileName.substring(dot + 1).toLowerCase();
            String guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (guessed != null) return guessed;
        }
        return "application/octet-stream";
    }

    public static String classifySubfolder(String fileName, String mimeType) {
        String lowerName = fileName != null ? fileName.toLowerCase() : "";
        String lowerMime = mimeType != null ? mimeType.toLowerCase() : "";
        if (lowerMime.startsWith("image/") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")
                || lowerName.endsWith(".png") || lowerName.endsWith(".webp") || lowerName.endsWith(".gif")) {
            return "Images";
        }
        if (lowerMime.startsWith("video/") || lowerName.endsWith(".mp4") || lowerName.endsWith(".mkv")
                || lowerName.endsWith(".webm")) {
            return "Videos";
        }
        if (lowerMime.startsWith("audio/") || lowerName.endsWith(".mp3") || lowerName.endsWith(".wav")
                || lowerName.endsWith(".m4a") || lowerName.endsWith(".ogg")) {
            return "Audio";
        }
        if (lowerMime.contains("pdf") || lowerMime.contains("word") || lowerMime.contains("document")
                || lowerName.endsWith(".pdf") || lowerName.endsWith(".doc") || lowerName.endsWith(".docx")
                || lowerName.endsWith(".txt")) {
            return "Documents";
        }
        return "Others";
    }

    /**
     * Prepares an outgoing file by splitting it into 1 MB verified chunks,
     * computing chunk SHA-256 hashes and full-file SHA-256, and storing the manifest.
     */
    public NetPacket.FileManifestDto prepareOutgoingFile(Uri sourceUri, String messageId, String chatId,
                                                         String seederDeviceId, String seederIp, int seederPort) throws Exception {
        String fileName = resolveDisplayName(context, sourceUri);
        String mimeType = resolveMimeType(context, sourceUri, fileName);
        String transferId = UUID.randomUUID().toString();
        File cacheDir = getChunkCacheDir(transferId);

        List<String> chunkHashes = new ArrayList<>();
        MessageDigest fullDigest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[CHUNK_SIZE_BYTES];
        long totalSize = 0;
        int chunkIdx = 0;

        try (InputStream is = context.getContentResolver().openInputStream(sourceUri)) {
            if (is == null) throw new IllegalStateException("Cannot open selected file");
            while (true) {
                int readTotal = 0;
                while (readTotal < CHUNK_SIZE_BYTES) {
                    int r = is.read(buffer, readTotal, CHUNK_SIZE_BYTES - readTotal);
                    if (r < 0) break;
                    readTotal += r;
                }
                if (readTotal <= 0) break;

                totalSize += readTotal;
                fullDigest.update(buffer, 0, readTotal);
                chunkHashes.add(CryptoManager.sha256Hex(buffer, 0, readTotal));

                File chunkFile = new File(cacheDir, chunkIdx + ".part");
                try (FileOutputStream fos = new FileOutputStream(chunkFile)) {
                    fos.write(buffer, 0, readTotal);
                }
                chunkIdx++;
            }
        }

        if (chunkIdx == 0) {
            File emptyChunk = new File(cacheDir, "0.part");
            emptyChunk.createNewFile();
            chunkHashes.add(CryptoManager.sha256Hex(new byte[0]));
            chunkIdx = 1;
        }

        byte[] fullHashBytes = fullDigest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : fullHashBytes) sb.append(String.format("%02x", b));
        String fileSha256 = sb.toString();

        NetPacket.FileManifestDto manifest = new NetPacket.FileManifestDto();
        manifest.transferId = transferId;
        manifest.messageId = messageId;
        manifest.chatId = chatId;
        manifest.fileName = fileName;
        manifest.mimeType = mimeType;
        manifest.totalSize = totalSize;
        manifest.chunkSize = CHUNK_SIZE_BYTES;
        manifest.totalChunks = chunkIdx;
        manifest.fileSha256 = fileSha256;
        manifest.seederDeviceId = seederDeviceId;
        manifest.seederPort = seederPort;
        manifest.chunkSha256List = chunkHashes;
        manifest.seederIps = new ArrayList<>();
        if (seederIp != null && !seederIp.isEmpty()) {
            manifest.seederIps.add(seederIp + ":" + seederPort);
        }

        String thumbBase64 = createSmallPreviewBase64(sourceUri.toString(), mimeType, cacheDir);
        manifest.thumbnailBase64 = thumbBase64;

        long now = System.currentTimeMillis();
        FileTransferEntity entity = new FileTransferEntity(
                transferId, messageId, chatId, fileName, mimeType, totalSize,
                CHUNK_SIZE_BYTES, chunkIdx, chunkIdx, fileSha256,
                gson.toJson(manifest.seederIps), sourceUri.toString(),
                sourceUri.toString(), "UPLOAD", "COMPLETED", 0L, gson.toJson(manifest), now, now
        );
        db.fileTransferDao().upsert(entity);
        return manifest;
    }

    public File getThumbnailFile(String transferId) {
        if (!isValidTransferId(transferId)) return null;
        return new File(getChunkCacheDir(transferId), "thumb.jpg");
    }

    public String createSmallPreviewBase64(String uriString, String mimeType, File cacheDir) {
        try {
            if (mimeType == null || (!mimeType.startsWith("image/") && !mimeType.startsWith("video/"))) {
                return "";
            }
            Bitmap bmp = null;
            if (mimeType.startsWith("image/")) {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                try (InputStream is = context.getContentResolver().openInputStream(Uri.parse(uriString))) {
                    if (is != null) BitmapFactory.decodeStream(is, null, bounds);
                }
                int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
                int sample = 1;
                while (maxDim / sample > 360) sample *= 2;
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                try (InputStream is = context.getContentResolver().openInputStream(Uri.parse(uriString))) {
                    if (is != null) bmp = BitmapFactory.decodeStream(is, null, opts);
                }
            } else {
                bmp = createThumbnail(uriString, mimeType);
            }
            if (bmp == null) return "";
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            if (w > 320 || h > 320) {
                float scale = 320f / Math.max(w, h);
                bmp = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(w * scale)),
                        Math.max(1, Math.round(h * scale)), true);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, baos);
            byte[] jpeg = baos.toByteArray();
            try (FileOutputStream fos = new FileOutputStream(new File(cacheDir, "thumb.jpg"))) {
                fos.write(jpeg);
            }
            return android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    public FileTransferEntity registerIncomingManifest(NetPacket.FileManifestDto manifest) {
        if (manifest == null || !isValidTransferId(manifest.transferId)) return null;
        manifest.fileName = sanitizeFileName(manifest.fileName);
        if (manifest.thumbnailBase64 != null && !manifest.thumbnailBase64.isEmpty()) {
            try {
                byte[] jpeg = android.util.Base64.decode(manifest.thumbnailBase64, android.util.Base64.DEFAULT);
                File tf = getThumbnailFile(manifest.transferId);
                if (tf != null && !tf.exists()) {
                    try (FileOutputStream fos = new FileOutputStream(tf)) {
                        fos.write(jpeg);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        FileTransferEntity existing = db.fileTransferDao().getByIdSync(manifest.transferId);
        if (existing != null) {
            // Merge new seeder IPs for multi-source support
            Type listType = new TypeToken<List<String>>() {}.getType();
            List<String> sources = gson.fromJson(existing.sourcePeerIdsJson, listType);
            if (sources == null) sources = new ArrayList<>();
            if (manifest.seederIps != null) {
                for (String s : manifest.seederIps) {
                    if (NetworkUtils.parseLocalLanHostPort(s) != null && !sources.contains(s)) {
                        sources.add(s);
                    }
                }
            }
            existing.sourcePeerIdsJson = gson.toJson(sources);
            existing.updatedAt = System.currentTimeMillis();
            db.fileTransferDao().upsert(existing);
            return existing;
        }
        long now = System.currentTimeMillis();
        List<String> validatedSeeders = new ArrayList<>();
        if (manifest.seederIps != null) {
            for (String s : manifest.seederIps) {
                if (NetworkUtils.parseLocalLanHostPort(s) != null) validatedSeeders.add(s);
            }
        }
        FileTransferEntity entity = new FileTransferEntity(
                manifest.transferId, manifest.messageId, manifest.chatId,
                manifest.fileName, manifest.mimeType, manifest.totalSize,
                manifest.chunkSize, manifest.totalChunks, 0,
                manifest.fileSha256, gson.toJson(validatedSeeders),
                null, null, "DOWNLOAD", "QUEUED", 0L, gson.toJson(manifest), now, now
        );
        db.fileTransferDao().upsert(entity);
        return entity;
    }

    public void enqueueDownload(String transferId) {
        if (transferId == null || transferId.isEmpty()) return;
        pausedFlags.put(transferId, false);
        cancelledFlags.put(transferId, false);
        AppDatabase.DB_EXECUTOR.execute(() -> {
            FileTransferEntity existing = db.fileTransferDao().getByIdSync(transferId);
            if (existing != null && "COMPLETED".equals(existing.state)
                    && isLocalFileAvailable(context, existing.savedPath != null ? existing.savedPath : existing.localUri)) {
                return;
            }
            Data input = new Data.Builder().putString("transferId", transferId).build();
            OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(FileTransferWorker.class)
                    .setInputData(input)
                    .build();
            WorkManager.getInstance(context).enqueue(req);
        });
    }

    public static boolean isLocalFileAvailable(Context context, String pathOrUri) {
        if (pathOrUri == null || pathOrUri.trim().isEmpty()) return false;
        if (pathOrUri.startsWith("content://")) {
            try (InputStream is = context.getContentResolver().openInputStream(Uri.parse(pathOrUri))) {
                return is != null;
            } catch (Exception e) {
                return false;
            }
        }
        String rawPath = pathOrUri.startsWith("file://") ? Uri.parse(pathOrUri).getPath() : pathOrUri;
        return rawPath != null && new File(rawPath).exists();
    }

    public static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    public static String getExtensionUpper(String fileName) {
        if (fileName == null) return "FILE";
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            String ext = fileName.substring(dot + 1).trim();
            if (!ext.isEmpty() && ext.length() <= 6) return ext.toUpperCase(java.util.Locale.US);
        }
        return "FILE";
    }

    public void pauseDownload(String transferId) {
        pausedFlags.put(transferId, true);
        AppDatabase.DB_EXECUTOR.execute(() -> {
            FileTransferEntity e = db.fileTransferDao().getByIdSync(transferId);
            if (e != null && !"COMPLETED".equals(e.state)) {
                db.fileTransferDao().updateProgress(transferId, "PAUSED", e.completedChunks, 0L, e.savedPath, System.currentTimeMillis());
            }
        });
    }

    public void cancelDownload(String transferId) {
        cancelledFlags.put(transferId, true);
        AppDatabase.DB_EXECUTOR.execute(() -> {
            FileTransferEntity e = db.fileTransferDao().getByIdSync(transferId);
            if (e != null) {
                db.fileTransferDao().updateProgress(transferId, "CANCELLED", e.completedChunks, 0L, null, System.currentTimeMillis());
            }
            deleteRecursive(getChunkCacheDir(transferId));
        });
    }

    public boolean downloadSynchronously(String transferId) {
        if (!isValidTransferId(transferId)) return false;
        FileTransferEntity entity = db.fileTransferDao().getByIdSync(transferId);
        if (entity == null || entity.manifestJson == null) return false;
        NetPacket.FileManifestDto manifest = gson.fromJson(entity.manifestJson, NetPacket.FileManifestDto.class);
        if (manifest == null || manifest.totalChunks < 0 || manifest.totalChunks > 100000) return false;

        List<String> seeders = resolveActiveSeeders(manifest);
        if (seeders.isEmpty()) {
            db.fileTransferDao().updateProgress(transferId, "WAITING_SEEDER", entity.completedChunks, 0L, null, System.currentTimeMillis());
            return false;
        }

        File cacheDir = getChunkCacheDir(transferId);
        int completed = 0;
        long bytesDownloaded = 0;
        long startTime = System.currentTimeMillis();

        db.fileTransferDao().updateProgress(transferId, "TRANSFERRING", entity.completedChunks, 0L, null, System.currentTimeMillis());

        for (int i = 0; i < manifest.totalChunks; i++) {
            if (Boolean.TRUE.equals(cancelledFlags.get(transferId))) return false;
            if (Boolean.TRUE.equals(pausedFlags.get(transferId))) return false;

            File chunkFile = new File(cacheDir, i + ".part");
            String expectedHash = (manifest.chunkSha256List != null && i < manifest.chunkSha256List.size())
                    ? manifest.chunkSha256List.get(i) : null;

            if (chunkFile.exists() && verifyFileHash(chunkFile, expectedHash)) {
                completed++;
                continue;
            }

            boolean chunkOk = false;
            for (int attempt = 0; attempt < MAX_CHUNK_RETRIES && !chunkOk; attempt++) {
                String seeder = seeders.get((i + attempt) % seeders.size());
                try {
                    byte[] data = fetchChunkFromPeer(seeder, transferId, i);
                    String actualHash = CryptoManager.sha256Hex(data);
                    if (expectedHash == null || expectedHash.equalsIgnoreCase(actualHash)) {
                        try (FileOutputStream fos = new FileOutputStream(chunkFile)) {
                            fos.write(data);
                        }
                        bytesDownloaded += data.length;
                        chunkOk = true;
                    }
                } catch (Exception ignored) {
                }
            }

            if (!chunkOk) {
                db.fileTransferDao().updateProgress(transferId, "FAILED", completed, 0L, null, System.currentTimeMillis());
                return false;
            }
            completed++;
            long elapsedMs = Math.max(100, System.currentTimeMillis() - startTime);
            long speed = (bytesDownloaded * 1000L) / elapsedMs;
            db.fileTransferDao().updateProgress(transferId, "TRANSFERRING", completed, speed, null, System.currentTimeMillis());
        }

        // Assemble and verify full file SHA-256 before moving into Downloads/WifiCallLocal/
        try {
            File assembledTemp = new File(cacheDir, "assembled.tmp");
            MessageDigest fullDigest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            try (FileOutputStream out = new FileOutputStream(assembledTemp)) {
                for (int i = 0; i < manifest.totalChunks; i++) {
                    File chunkFile = new File(cacheDir, i + ".part");
                    try (FileInputStream in = new FileInputStream(chunkFile)) {
                        int read;
                        while ((read = in.read(buf)) > 0) {
                            fullDigest.update(buf, 0, read);
                            out.write(buf, 0, read);
                        }
                    }
                }
            }
            byte[] digest = fullDigest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            if (manifest.fileSha256 != null && !manifest.fileSha256.isEmpty()
                    && !manifest.fileSha256.equalsIgnoreCase(sb.toString())) {
                db.fileTransferDao().updateProgress(transferId, "FAILED", completed, 0L, null, System.currentTimeMillis());
                return false;
            }

            String savedPath = saveToDownloadsWifiCallLocal(assembledTemp, manifest.fileName, manifest.mimeType);
            if (manifest.messageId != null) {
                com.android.wificalllocal.data.local.entity.MessageEntity msg = db.messageDao().getByIdSync(manifest.messageId);
                if (msg != null) {
                    msg.localFilePath = savedPath;
                    db.messageDao().upsert(msg);
                }
            }
            db.fileTransferDao().updateProgress(transferId, "COMPLETED", manifest.totalChunks, 0L, savedPath, System.currentTimeMillis());
            try {
                com.android.wificalllocal.ServiceLocator loc = com.android.wificalllocal.ServiceLocator.getInstance(context);
                loc.getCacheManager().enforceCacheLimitAsync(loc.getAppControlManager().getLastState().maxCacheSizeMb);
            } catch (Exception ignored) {
            }
            return true;
        } catch (Exception e) {
            db.fileTransferDao().updateProgress(transferId, "FAILED", completed, 0L, null, System.currentTimeMillis());
            return false;
        }
    }

    private List<String> resolveActiveSeeders(NetPacket.FileManifestDto manifest) {
        List<String> list = new ArrayList<>();
        if (manifest.seederIps != null) {
            list.addAll(manifest.seederIps);
        }
        if (manifest.seederDeviceId != null) {
            PeerEntity peer = db.peerDao().getByIdSync(manifest.seederDeviceId);
            if (peer != null && peer.isOnline && peer.ipAddress != null && !peer.ipAddress.isEmpty()) {
                String hp = peer.ipAddress + ":" + peer.port;
                if (!list.contains(hp)) list.add(0, hp);
            }
        }
        return list;
    }

    private byte[] fetchChunkFromPeer(String hostPort, String transferId, int index) throws Exception {
        if (!isValidTransferId(transferId) || index < 0) {
            throw new SecurityException("Invalid transfer parameters");
        }
        NetworkUtils.HostPort parsed = NetworkUtils.parseLocalLanHostPort(hostPort);
        if (parsed == null) {
            throw new SecurityException("Blocked non-LAN seeder address: " + hostPort);
        }
        URL url = new URL("http", parsed.host, parsed.port,
                "/api/chunk?transferId=" + transferId + "&index=" + index);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(15000);
        if (conn.getResponseCode() != 200) {
            conn.disconnect();
            throw new IllegalStateException("HTTP " + conn.getResponseCode());
        }
        try (InputStream is = conn.getInputStream(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int totalRead = 0;
            int read;
            while ((read = is.read(buf)) > 0) {
                totalRead += read;
                if (totalRead > MAX_CHUNK_RESPONSE_BYTES) {
                    throw new SecurityException("Chunk exceeds safe size limit");
                }
                baos.write(buf, 0, read);
            }
            return baos.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    private boolean verifyFileHash(File file, String expectedSha256) {
        if (expectedSha256 == null || expectedSha256.isEmpty()) return true;
        try (FileInputStream fis = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[16384];
            int r;
            while ((r = fis.read(buf)) > 0) md.update(buf, 0, r);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return expectedSha256.equalsIgnoreCase(sb.toString());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Saves the verified file under Downloads/WifiCallLocal/{Images,Videos,Audio,Documents,Others}
     * using MediaStore on Android 10+ (zero permission) or File API on Android 8-9,
     * never overwriting existing files silently.
     */
    public String saveToDownloadsWifiCallLocal(File sourceFile, String fileName, String mimeType) throws Exception {
        String safeFileName = sanitizeFileName(fileName);
        String subFolder = classifySubfolder(safeFileName, mimeType);
        String relativePath = Environment.DIRECTORY_DOWNLOADS + "/WifiCallLocal/" + subFolder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            String uniqueName = ensureUniqueMediaStoreName(resolver, relativePath, safeFileName);
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, uniqueName);
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType != null ? mimeType : "application/octet-stream");
            values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            Uri itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (itemUri == null) throw new IllegalStateException("MediaStore insert failed");

            try (OutputStream os = resolver.openOutputStream(itemUri);
                 FileInputStream fis = new FileInputStream(sourceFile)) {
                if (os == null) throw new IllegalStateException("OutputStream null");
                byte[] buf = new byte[32768];
                int r;
                while ((r = fis.read(buf)) > 0) os.write(buf, 0, r);
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(itemUri, values, null, null);
            return itemUri.toString();
        } else {
            File baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File targetDir = new File(baseDir, "WifiCallLocal/" + subFolder);
            if (!targetDir.exists()) targetDir.mkdirs();
            File target = resolveNonOverwritingFile(targetDir, safeFileName);
            try (FileInputStream fis = new FileInputStream(sourceFile);
                 FileOutputStream fos = new FileOutputStream(target)) {
                byte[] buf = new byte[32768];
                int r;
                while ((r = fis.read(buf)) > 0) fos.write(buf, 0, r);
            }
            return target.getAbsolutePath();
        }
    }

    private String ensureUniqueMediaStoreName(ContentResolver resolver, String relPath, String fileName) {
        String candidate = sanitizeFileName(fileName);
        int counter = 1;
        while (mediaStoreFileExists(resolver, relPath, candidate) && counter < 999) {
            candidate = appendSuffix(fileName, counter++);
        }
        return candidate;
    }

    private boolean mediaStoreFileExists(ContentResolver resolver, String relPath, String fileName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        String[] proj = {MediaStore.Downloads._ID};
        String sel = MediaStore.Downloads.DISPLAY_NAME + "=? AND " + MediaStore.Downloads.RELATIVE_PATH + " LIKE ?";
        String[] args = {fileName, "%" + relPath + "%"};
        try (Cursor c = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj, sel, args, null)) {
            return c != null && c.moveToFirst();
        } catch (Exception e) {
            return false;
        }
    }

    public static File resolveNonOverwritingFile(File dir, String fileName) {
        String safeName = sanitizeFileName(fileName);
        File f = new File(dir, safeName);
        int counter = 1;
        while (f.exists() && counter < 999) {
            f = new File(dir, appendSuffix(safeName, counter++));
        }
        try {
            String dirCanonical = dir.getCanonicalPath();
            String fileCanonical = f.getCanonicalPath();
            if (!fileCanonical.startsWith(dirCanonical + File.separator)) {
                throw new SecurityException("Blocked unsafe file path");
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            throw new SecurityException("Failed canonical path validation", e);
        }
        return f;
    }

    public static String appendSuffix(String fileName, int n) {
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            return fileName.substring(0, dot) + " (" + n + ")" + fileName.substring(dot);
        }
        return fileName + " (" + n + ")";
    }

    public Bitmap createThumbnail(String pathOrUri, String mimeType) {
        try {
            if (mimeType != null && mimeType.startsWith("image/")) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 4;
                if (pathOrUri.startsWith("content://")) {
                    try (InputStream is = context.getContentResolver().openInputStream(Uri.parse(pathOrUri))) {
                        return BitmapFactory.decodeStream(is, null, opts);
                    }
                }
                return BitmapFactory.decodeFile(pathOrUri, opts);
            } else if (mimeType != null && mimeType.startsWith("video/")) {
                MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                if (pathOrUri.startsWith("content://")) {
                    mmr.setDataSource(context, Uri.parse(pathOrUri));
                } else {
                    mmr.setDataSource(pathOrUri);
                }
                Bitmap frame = mmr.getFrameAtTime(0);
                mmr.release();
                return frame;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        file.delete();
    }
}
