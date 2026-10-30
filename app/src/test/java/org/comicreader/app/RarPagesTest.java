package org.comicreader.app;

import org.junit.Test;
import java.io.*;
import java.nio.file.*;
import static org.junit.Assert.*;

public class RarPagesTest {
    private File fixture(String name) throws Exception { return new File(getClass().getResource("/comics/" + name).toURI()); }
    @Test public void providersAcceptRarExtensions() {
        assertTrue(ComicFiles.supported("Mi colección.CBR")); assertTrue(ComicFiles.supported("Comic.RAR"));
        assertFalse(ComicFiles.supported("comic.cbr.exe")); assertFalse(ComicFiles.supported("comic.7z"));
    }
    @Test public void rar4PagesAreNaturallyOrderedWithoutHiddenResources() throws Exception { checkPages("rar4-stored.cbr"); }
    @Test public void compressedSolidRar5SupportsForwardBackwardAndRepeatedReads() throws Exception { checkPages("rar5-solid.cbr"); }
    private void checkPages(String name) throws Exception {
        try (RarPages pages = new RarPages(fixture(name))) {
            assertEquals(3, pages.count());
            for (int index : new int[]{0, 2, 1, 0, 2, 2}) {
                byte[] image = pages.read(index); assertEquals('B', image[0]); assertEquals('M', image[1]);
                assertEquals(index == 2 ? 255 : 0, image[54] & 255);
                assertEquals(index == 1 ? 255 : 0, image[55] & 255);
                assertEquals(index == 0 ? 255 : 0, image[56] & 255);
            }
            assertThrows(IOException.class, () -> pages.read(3));
        }
    }
    @Test public void duplicateAndEncryptedArchivesAreRejected() throws Exception {
        assertThrows(IOException.class, () -> new RarPages(fixture("duplicate.cbr")));
        assertThrows(IOException.class, () -> new RarPages(fixture("encrypted.cbr")));
    }
    @Test public void damagedDataIsNotReturnedAsAValidPage() throws Exception {
        byte[] data = Files.readAllBytes(fixture("rar4-stored.cbr").toPath());
        int headerSize = (data[25] & 255) | ((data[26] & 255) << 8);
        data[20 + headerSize + 70] ^= 1;
        Path damaged = Files.createTempFile("comic-crc-", ".cbr");
        try {
            Files.write(damaged, data);
            try (RarPages pages = new RarPages(damaged.toFile())) { assertThrows(IOException.class, () -> pages.read(2)); }
        } finally { Files.delete(damaged); }
    }
}
