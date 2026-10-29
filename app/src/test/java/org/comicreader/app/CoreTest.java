package org.comicreader.app;

import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class CoreTest {
    static final String ID = "a".repeat(64), DEVICE_A = "00000000-0000-0000-0000-000000000001", DEVICE_B = "00000000-0000-0000-0000-000000000002";
    static Progress state(int page, long revision, String device) { return new Progress(ID, page, 12, false, true, new int[]{2, 7}, revision, device); }

    @Test public void naturalOrderHasNoNumericOverflow() {
        List<String> names = new ArrayList<>(List.of("page10.jpg", "page0002.jpg", "page2.jpg", "page1.jpg", "page999999999999999999999.jpg"));
        names.sort(ComicFiles::naturalCompare);
        assertEquals(List.of("page1.jpg", "page2.jpg", "page0002.jpg", "page10.jpg", "page999999999999999999999.jpg"), names);
    }
    @Test public void hiddenFilesAreNotPages() {
        assertTrue(ComicFiles.image("book/02.JPG")); assertFalse(ComicFiles.image("__MACOSX/02.jpg"));
        assertFalse(ComicFiles.image("book/.thumb.jpg")); assertFalse(ComicFiles.supported("test.cbr"));
    }
    @Test public void copyEnforcesActualBytesAndCancellation() throws Exception {
        assertEquals(3, ComicFiles.copy(new ByteArrayInputStream(new byte[3]), new ByteArrayOutputStream(), 3));
        assertThrows(IOException.class, () -> ComicFiles.read(new ByteArrayInputStream(new byte[4]), 3));
        Thread.currentThread().interrupt();
        try { assertThrows(InterruptedIOException.class, () -> ComicFiles.read(new ByteArrayInputStream(new byte[1]), 3)); }
        finally { Thread.interrupted(); }
    }
    @Test public void decoderBudgetHandlesExtremelyThinImages() {
        for (int[] dimensions : new int[][]{{1, 100_000_000}, {100_000_000, 1}, {30000, 30000}, {4000, 6000}}) {
            int sample = ComicFiles.sampleSize(dimensions[0], dimensions[1], 1080, 1920);
            long pixels = ((dimensions[0] + (long) sample - 1) / sample) * ((dimensions[1] + (long) sample - 1) / sample);
            assertTrue("Decoded pixels: " + pixels, pixels <= 3_000_000);
        }
    }
    @Test public void progressRoundTripsAndDefensivelyCopies() throws Exception {
        Progress original = state(4, 5, DEVICE_A);
        Progress decoded = Progress.decode(Progress.encode(List.of(original))).get(0);
        assertSame(original, Progress.latest(original, decoded));
        int[] bookmarks = decoded.bookmarks(); bookmarks[0] = 11;
        assertTrue(decoded.bookmarked(2)); assertFalse(decoded.bookmarked(11));
    }
    @Test public void newRevisionAllowsDeliberateRereading() {
        assertEquals(1, Progress.latest(state(9, 4, DEVICE_A), state(1, 5, DEVICE_A)).page);
    }
    @Test public void concurrentMergeIsCommutativeAndAssociative() {
        Progress a = state(4, 5, DEVICE_A), b = state(6, 5, DEVICE_B), c = state(2, 6, DEVICE_A);
        assertSame(b, Progress.latest(a, b)); assertSame(b, Progress.latest(b, a));
        assertSame(c, Progress.latest(Progress.latest(a, b), c));
        assertSame(c, Progress.latest(a, Progress.latest(b, c)));
        assertSame(a, Progress.latest(a, a));
        assertThrows(IllegalArgumentException.class, () -> Progress.latest(a, state(7, 5, DEVICE_A)));
    }
    @Test public void snapshotRejectsTruncationTrailingBytesAndDuplicates() throws Exception {
        byte[] bytes = Progress.encode(List.of(state(0, 1, DEVICE_A)));
        assertThrows(IOException.class, () -> Progress.decode(Arrays.copyOf(bytes, bytes.length - 1)));
        assertThrows(IOException.class, () -> Progress.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        assertThrows(IOException.class, () -> Progress.encode(List.of(state(0, 1, DEVICE_A), state(0, 1, DEVICE_A))));
        assertThrows(IllegalArgumentException.class, () -> new Progress(ID, 0, 12, false, false, new int[]{2, 2}, 1, DEVICE_A));
        assertThrows(IllegalArgumentException.class, () -> new Progress(ID, 12, 12, false, false, new int[0], 1, DEVICE_A));
        assertThrows(IllegalArgumentException.class, () -> state(0, Long.MAX_VALUE - 1, DEVICE_A));
    }
    @Test public void pageStreamsEnforceActualLengthIncludingSkip() throws Exception {
        try (InputStream in = ComicFiles.limited(new ByteArrayInputStream(new byte[4]), 3)) {
            assertEquals(3, in.skip(3)); assertThrows(IOException.class, in::read);
        }
    }
}
