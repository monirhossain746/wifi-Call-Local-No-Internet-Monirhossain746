package com.android.wificalllocal.control;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import com.android.wificalllocal.R;
import com.android.wificalllocal.security.CryptoManager;

import java.io.File;
import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Handles HTTPS APK update downloads via DownloadManager, unknown-source permission guidance,
 * package/version/signing-certificate verification, and system installer launch.
 */
public class UpdateManager {

    public interface UpdateListener {
        void onProgress(int percent, String statusText);
        void onPermissionRequired(Intent settingsIntent);
        void onReadyToInstall(Intent installIntent);
        void onError(String message);
    }

    private static final String UPDATE_APK_FILENAME = "WifiCallLocal_update.apk";

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long activeDownloadId = -1L;
    private volatile boolean cancelled = false;

    public UpdateManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static boolean isValidHttpsUpdateUrl(String url) {
        if (url == null) return false;
        String trimmed = url.trim();
        if (trimmed.isEmpty() || trimmed.length() > 2048) return false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isWhitespace(c) || c < 0x20 || c == 0x7F) return false;
        }
        if (!trimmed.toLowerCase(Locale.US).startsWith("https://")) return false;
        try {
            URI parsed = new URI(trimmed);
            if (!"https".equalsIgnoreCase(parsed.getScheme())) return false;
            if (parsed.getRawUserInfo() != null) return false;
            String authority = parsed.getRawAuthority();
            if (authority != null && authority.contains("@")) return false;
            String host = parsed.getHost();
            return host != null && !host.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean canInstallUpdates() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return context.getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    public Intent createUnknownSourcesSettingsIntent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return null;
    }

    public void startUpdate(String updateUrl, int currentVersionCode, UpdateListener listener) {
        if (!isValidHttpsUpdateUrl(updateUrl)) {
            if (listener != null) listener.onError(context.getString(R.string.update_invalid_url));
            return;
        }
        if (!canInstallUpdates()) {
            Intent permIntent = createUnknownSourcesSettingsIntent();
            if (listener != null && permIntent != null) listener.onPermissionRequired(permIntent);
            return;
        }

        File outDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (outDir == null) outDir = new File(context.getCacheDir(), "updates");
        if (!outDir.exists()) outDir.mkdirs();
        File targetApk = new File(outDir, UPDATE_APK_FILENAME);
        if (targetApk.exists()) targetApk.delete();

        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            if (listener != null) listener.onError(context.getString(R.string.update_download_failed));
            return;
        }

        cancelled = false;
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(updateUrl.trim()))
                .setTitle(context.getString(R.string.app_name))
                .setDescription(context.getString(R.string.update_downloading))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(targetApk))
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true);

        activeDownloadId = dm.enqueue(req);
        pollDownloadProgress(dm, activeDownloadId, targetApk, currentVersionCode, listener);
    }

    public void cancelDownload() {
        cancelled = true;
        if (activeDownloadId > 0) {
            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) dm.remove(activeDownloadId);
            activeDownloadId = -1L;
        }
    }

    private void pollDownloadProgress(DownloadManager dm, long downloadId, File apkFile,
                                      int currentVersionCode, UpdateListener listener) {
        new Thread(() -> {
            while (!cancelled) {
                DownloadManager.Query q = new DownloadManager.Query().setFilterById(downloadId);
                try (Cursor c = dm.query(q)) {
                    if (c == null || !c.moveToFirst()) {
                        postError(listener, context.getString(R.string.update_download_failed));
                        return;
                    }
                    int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    long downloaded = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    int pct = total > 0 ? (int) Math.min(100, (downloaded * 100L) / total) : 0;

                    if (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING) {
                        mainHandler.post(() -> {
                            if (listener != null) {
                                listener.onProgress(pct, context.getString(R.string.update_downloading) + " (" + pct + "%)");
                            }
                        });
                    } else if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        mainHandler.post(() -> {
                            if (listener != null) listener.onProgress(100, context.getString(R.string.update_verifying));
                        });
                        if (!isExpectedApkDestination(apkFile) || !verifyDownloadedApk(apkFile, currentVersionCode)) {
                            apkFile.delete();
                            postError(listener, context.getString(R.string.update_verify_failed));
                            return;
                        }
                        Uri apkUri = FileProvider.getUriForFile(context,
                                context.getPackageName() + ".fileprovider", apkFile);
                        Intent installIntent = new Intent(Intent.ACTION_VIEW);
                        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        mainHandler.post(() -> {
                            if (listener != null) listener.onReadyToInstall(installIntent);
                        });
                        return;
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        postError(listener, context.getString(R.string.update_download_failed));
                        return;
                    }
                } catch (Exception e) {
                    postError(listener, context.getString(R.string.update_download_failed));
                    return;
                }
                try {
                    Thread.sleep(450);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "apk-update-poll").start();
    }

    private void postError(UpdateListener listener, String msg) {
        mainHandler.post(() -> {
            if (listener != null) listener.onError(msg);
        });
    }

    private boolean isExpectedApkDestination(File apkFile) {
        if (apkFile == null) return false;
        try {
            File extDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            File cacheDir = new File(context.getCacheDir(), "updates");
            String canonicalApk = apkFile.getCanonicalPath();
            boolean inExt = extDir != null && canonicalApk.startsWith(extDir.getCanonicalPath() + File.separator);
            boolean inCache = canonicalApk.startsWith(cacheDir.getCanonicalPath() + File.separator);
            return (inExt || inCache) && UPDATE_APK_FILENAME.equals(apkFile.getName());
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    public boolean verifyDownloadedApk(File apkFile, int currentVersionCode) {
        if (apkFile == null || !apkFile.exists() || !apkFile.isFile() || apkFile.length() == 0) return false;
        try (ZipFile zip = new ZipFile(apkFile)) {
            if (zip.getEntry("AndroidManifest.xml") == null || zip.getEntry("classes.dex") == null) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        try {
            PackageManager pm = context.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES;
            PackageInfo archiveInfo = pm.getPackageArchiveInfo(apkFile.getCanonicalPath(), flags);
            if (archiveInfo == null || archiveInfo.applicationInfo == null) return false;
            if (!context.getPackageName().equals(archiveInfo.packageName)) return false;

            long archiveVer = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? archiveInfo.getLongVersionCode()
                    : archiveInfo.versionCode;
            if (archiveVer <= currentVersionCode) return false;

            PackageInfo installedInfo = pm.getPackageInfo(context.getPackageName(), flags);
            Set<String> installedSigs = extractSignerSha256Set(installedInfo);
            Set<String> archiveSigs = extractSignerSha256Set(archiveInfo);
            return !installedSigs.isEmpty() && installedSigs.equals(archiveSigs);
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private Set<String> extractSignerSha256Set(PackageInfo info) {
        Set<String> hashes = new HashSet<>();
        if (info == null) return hashes;
        Signature[] sigs = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            sigs = info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        }
        if ((sigs == null || sigs.length == 0) && info.signatures != null) {
            sigs = info.signatures;
        }
        if (sigs != null) {
            for (Signature sig : sigs) {
                if (sig != null) {
                    hashes.add(CryptoManager.sha256Hex(sig.toByteArray()));
                }
            }
        }
        return hashes;
    }
}
