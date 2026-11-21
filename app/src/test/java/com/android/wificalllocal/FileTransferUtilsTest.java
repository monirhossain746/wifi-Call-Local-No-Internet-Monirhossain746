package com.android.wificalllocal;

import com.android.wificalllocal.files.FileTransferManager;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class FileTransferUtilsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void testSubfolderClassification() {
        assertEquals("Images", FileTransferManager.classifySubfolder("photo.JPG", "image/jpeg"));
        assertEquals("Videos", FileTransferManager.classifySubfolder("clip.mp4", "video/mp4"));
        assertEquals("Audio", FileTransferManager.classifySubfolder("voice.m4a", "audio/mp4"));
        assertEquals("Documents", FileTransferManager.classifySubfolder("notes.pdf", "application/pdf"));
        assertEquals("Others", FileTransferManager.classifySubfolder("archive.zip", "application/zip"));
    }

    @Test
    public void testNonOverwritingFileResolution() throws Exception {
        File dir = tmp.newFolder("downloads");
        File first = new File(dir, "report.pdf");
        assertEquals(true, first.createNewFile());

        File resolved = FileTransferManager.resolveNonOverwritingFile(dir, "report.pdf");
        assertEquals("report (1).pdf", resolved.getName());
        assertFalse(resolved.exists());

        assertEquals("readme (2)", FileTransferManager.appendSuffix("readme", 2));
    }

    @Test
    public void testFileCardHelpers() throws Exception {
        assertEquals("PDF", FileTransferManager.getExtensionUpper("contract.final.pdf"));
        assertEquals("PNG", FileTransferManager.getExtensionUpper("photo.png"));
        assertEquals("FILE", FileTransferManager.getExtensionUpper("no_extension"));
        assertEquals("512 B", FileTransferManager.formatFileSize(512));
        assertEquals("2.0 KB", FileTransferManager.formatFileSize(2048));
        assertEquals("1.5 MB", FileTransferManager.formatFileSize(1572864L));

        File existing = tmp.newFile("cached.png");
        assertEquals(true, FileTransferManager.isLocalFileAvailable(null, existing.getAbsolutePath()));
        assertFalse(FileTransferManager.isLocalFileAvailable(null, existing.getAbsolutePath() + ".missing"));
        assertFalse(FileTransferManager.isLocalFileAvailable(null, ""));
    }
}
