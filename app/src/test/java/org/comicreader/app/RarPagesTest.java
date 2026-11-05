package org.comicreader.app;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.*;
import java.nio.file.*;
import java.util.zip.CRC32;

public class RarPagesTest {
    private File fixture(String name) throws Exception {
        return new File(getClass().getResource("/comics/" + name).toURI());
    }

    @Test
    public void providersAcceptRarExtensions() {
        assertTrue(ComicFiles.supported("My collection.CBR"));
        assertTrue(ComicFiles.supported("Comic.RAR"));
        assertFalse(ComicFiles.supported("comic.cbr.exe"));
        assertFalse(ComicFiles.supported("comic.7z"));
    }

    @Test
    public void rar4PagesAreNaturallyOrderedWithoutHiddenResources() throws Exception {
        checkPages("rar4-stored.cbr");
    }

    @Test
    public void compressedSolidRar5SupportsForwardBackwardAndRepeatedReads() throws Exception {
        checkPages("rar5-solid.cbr");
    }

    @Test
    public void compressedRar4WithDirectoryVerifiesItsDataChecksum() throws Exception {
        try (RarPages pages = new RarPages(fixture("rar4-compressed-text.cbr"))) {
            assertEquals(1, pages.count());
            assertArrayEquals(
                    "test text document\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    pages.read(0));
        }
        byte[] damaged = Files.readAllBytes(fixture("rar4-compressed-text.cbr").toPath());
        int directorySize = (damaged[25] & 255) | ((damaged[26] & 255) << 8);
        int member = 20 + directorySize;
        int headerSize = (damaged[member + 5] & 255) | ((damaged[member + 6] & 255) << 8);
        damaged[member + 16] ^= 1;
        repairHeader(damaged, member, headerSize);
        Path file = Files.createTempFile("comic-rar4-crc-", ".cbr");
        try {
            Files.write(file, damaged);
            try (RarPages pages = new RarPages(file.toFile())) {
                assertThrows(IOException.class, () -> pages.read(0));
            }
        } finally {
            Files.delete(file);
        }
    }

    private void checkPages(String name) throws Exception {
        try (RarPages pages = new RarPages(fixture(name))) {
            assertEquals(3, pages.count());
            for (int index : new int[] {0, 2, 1, 0, 2, 2}) {
                byte[] image = pages.read(index);
                assertEquals('B', image[0]);
                assertEquals('M', image[1]);
                assertEquals(index == 2 ? 255 : 0, image[54] & 255);
                assertEquals(index == 1 ? 255 : 0, image[55] & 255);
                assertEquals(index == 0 ? 255 : 0, image[56] & 255);
            }
            assertThrows(IOException.class, () -> pages.read(3));
        }
    }

    @Test
    public void duplicateAndEncryptedArchivesAreRejected() throws Exception {
        assertThrows(IOException.class, () -> new RarPages(fixture("duplicate.cbr")));
        assertThrows(IOException.class, () -> new RarPages(fixture("encrypted.cbr")));
    }

    @Test
    public void solidNonImagePredecessorsCannotHideBadDataChecksums() throws Exception {
        assertThrows(
                IOException.class,
                () -> {
                    try (RarPages pages = new RarPages(fixture("rar5-bad-predecessor.cbr"))) {
                        pages.read(0);
                    }
                });
    }

    @Test
    public void damagedDataIsNotReturnedAsAValidPage() throws Exception {
        byte[] data = Files.readAllBytes(fixture("rar4-stored.cbr").toPath());
        int headerSize = (data[25] & 255) | ((data[26] & 255) << 8);
        data[20 + headerSize + 70] ^= 1;
        Path damaged = Files.createTempFile("comic-crc-", ".cbr");
        try {
            Files.write(damaged, data);
            try (RarPages pages = new RarPages(damaged.toFile())) {
                assertThrows(IOException.class, () -> pages.read(2));
            }
        } finally {
            Files.delete(damaged);
        }
    }

    @Test
    public void readingKeepsSourceBytesAndSupportsUnicodePathsAndClosedReaders() throws Exception {
        byte[] original = Files.readAllBytes(fixture("rar5-solid.cbr").toPath());
        Path directory = Files.createTempDirectory("comic-rar-");
        Path path = directory.resolve("Página-\uD83D\uDCDA.cbr");
        try {
            Files.write(path, original);
            RarPages pages = new RarPages(path.toFile());
            try {
                byte[] first = pages.read(0);
                assertSame(first, pages.read(0));
                pages.read(2);
                pages.read(0);
                assertArrayEquals(original, Files.readAllBytes(path));
            } finally {
                pages.close();
            }
            pages.close();
            assertThrows(IOException.class, () -> pages.read(0));
        } finally {
            Files.deleteIfExists(path);
            Files.delete(directory);
        }
    }

    @Test
    public void interruptionDoesNotClearTheFlagOrPreventAHealthyReaderAfterwards()
            throws Exception {
        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    InterruptedIOException.class, () -> new RarPages(fixture("rar5-solid.cbr")));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        try (RarPages pages = new RarPages(fixture("rar5-solid.cbr"))) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class, () -> pages.read(0));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            assertEquals('B', pages.read(0)[0]);
        }
    }

    @Test
    public void declaredOversizeAndSplitEntriesAreRejectedBeforeDecoding() throws Exception {
        byte[] original = Files.readAllBytes(fixture("rar4-stored.cbr").toPath());
        byte[] oversized = original.clone();
        int length = (oversized[25] & 255) | ((oversized[26] & 255) << 8);
        int size = 65 * 1024 * 1024;
        for (int i = 0; i < 4; i++) oversized[31 + i] = (byte) (size >>> (i * 8));
        repairHeader(oversized, 20, length);
        byte[] split = original.clone();
        split[23] |= 1;
        repairHeader(split, 20, length);
        for (byte[] data : new byte[][] {oversized, split}) {
            Path file = Files.createTempFile("comic-rar-rejected-", ".cbr");
            try {
                Files.write(file, data);
                assertThrows(IOException.class, () -> new RarPages(file.toFile()));
            } finally {
                Files.delete(file);
            }
        }
    }

    private static void repairHeader(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset + 2, length - 2);
        data[offset] = (byte) crc.getValue();
        data[offset + 1] = (byte) (crc.getValue() >>> 8);
    }
}
