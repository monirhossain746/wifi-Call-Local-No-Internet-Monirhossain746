package com.android.wificalllocal;

import android.content.Context;

import com.android.wificalllocal.control.UpdateManager;
import com.android.wificalllocal.files.FileTransferManager;
import com.android.wificalllocal.network.NetworkUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies security controls: path traversal rejection, filename sanitization,
 * RFC 1918 LAN-only SSRF enforcement, HTTPS update URL hardening, and APK verification.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SecurityAuditTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void transferIdRejectsPathTraversalAndSpecialChars() {
        assertTrue(FileTransferManager.isValidTransferId("550e8400-e29b-41d4-a716-446655440000"));
        assertTrue(FileTransferManager.isValidTransferId("transfer_01"));
        assertFalse(FileTransferManager.isValidTransferId("../shared_prefs"));
        assertFalse(FileTransferManager.isValidTransferId("../../etc/passwd"));
        assertFalse(FileTransferManager.isValidTransferId("a/b"));
        assertFalse(FileTransferManager.isValidTransferId("a\\b"));
        assertFalse(FileTransferManager.isValidTransferId(""));
        assertFalse(FileTransferManager.isValidTransferId(null));
    }

    @Test
    public void sanitizeFileNameNeutralizesTraversalAndHeaderInjection() {
        assertEquals("report.pdf", FileTransferManager.sanitizeFileName("../../evil/report.pdf"));
        assertEquals("payload.exe", FileTransferManager.sanitizeFileName("C:\\temp\\payload.exe"));
        String headerInject = FileTransferManager.sanitizeFileName("photo.jpg\"\r\nX-Injected: true");
        assertFalse(headerInject.contains("\r"));
        assertFalse(headerInject.contains("\n"));
        assertFalse(headerInject.contains("\""));
        assertFalse(headerInject.contains(".."));
    }

    @Test
    public void parseLocalLanHostPortAllowsOnlyPrivateLanIpsAndValidPorts() {
        NetworkUtils.HostPort validWifi = NetworkUtils.parseLocalLanHostPort("192.168.1.42:8080");
        assertNotNull(validWifi);
        assertEquals("192.168.1.42", validWifi.host);
        assertEquals(8080, validWifi.port);

        assertNotNull(NetworkUtils.parseLocalLanHostPort("10.0.0.5:8080"));
        assertNotNull(NetworkUtils.parseLocalLanHostPort("172.16.0.9:8443"));
        assertNotNull(NetworkUtils.parseLocalLanHostPort("192.168.49.1:8080"));

        // Reject SSRF targets: cloud metadata, loopback, public IP, DNS hostname, privileged port
        assertNull(NetworkUtils.parseLocalLanHostPort("169.254.169.254:80"));
        assertNull(NetworkUtils.parseLocalLanHostPort("127.0.0.1:8080"));
        assertNull(NetworkUtils.parseLocalLanHostPort("8.8.8.8:8080"));
        assertNull(NetworkUtils.parseLocalLanHostPort("evil.example.com:8080"));
        assertNull(NetworkUtils.parseLocalLanHostPort("192.168.1.42:80"));
        assertNull(NetworkUtils.parseLocalLanHostPort("192.168.1.42:70000"));
        assertNull(NetworkUtils.parseLocalLanHostPort("192.168.1.1@8.8.8.8:8080"));
    }

    @Test
    public void updateUrlValidationRejectsUserinfoAndControlChars() {
        assertTrue(UpdateManager.isValidHttpsUpdateUrl("https://releases.example.com/v2/app-release.apk"));
        assertFalse(UpdateManager.isValidHttpsUpdateUrl("https://trusted.com@evil.com/app.apk"));
        assertFalse(UpdateManager.isValidHttpsUpdateUrl("https://evil.com/app.apk\nHeader: x"));
        assertFalse(UpdateManager.isValidHttpsUpdateUrl("https://evil.com/app with spaces.apk"));
        assertFalse(UpdateManager.isValidHttpsUpdateUrl("http://releases.example.com/app.apk"));
    }

    @Test
    public void verifyDownloadedApkRejectsNonApkAndUnsignedZipArchives() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        UpdateManager manager = new UpdateManager(ctx);

        File plainText = tempFolder.newFile("fake.apk");
        try (FileOutputStream fos = new FileOutputStream(plainText)) {
            fos.write("not an apk".getBytes(StandardCharsets.UTF_8));
        }
        assertFalse(manager.verifyDownloadedApk(plainText, 1));

        File unsignedZip = tempFolder.newFile("unsigned.apk");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(unsignedZip))) {
            zos.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zos.write(new byte[]{1, 2, 3});
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("classes.dex"));
            zos.write(new byte[]{4, 5, 6});
            zos.closeEntry();
        }
        assertFalse(manager.verifyDownloadedApk(unsignedZip, 1));
    }
}
