package org.comicreader.app;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Read-only RAR pages; archive names are never filesystem destinations. */
final class RarPages implements AutoCloseable {
    private record Entry(String name, long size, int position) {}

    private static boolean loaded;
    private final byte[] path;
    private final List<Entry> pages = new ArrayList<>();
    private long handle;
    private int next, cachedIndex = -1;
    private byte[] cached;
    private boolean solid, closed;

    RarPages(File file) throws IOException {
        load();
        path = file.getAbsolutePath().getBytes(StandardCharsets.UTF_8);
        long index = nativeOpen(path);
        try {
            long total = 0, namesSize = 0;
            int position = 0;
            Set<String> names = new HashSet<>();
            byte[] encoded;
            while ((encoded = nativeNext(index)) != null) {
                if (++position > 50_000) throw new IOException("The RAR has too many entries");
                namesSize += encoded.length;
                if (namesSize > 4 * 1024 * 1024)
                    throw new IOException("The RAR entry names exceed the 4 MiB limit");
                String name = decode(encoded);
                long size = nativeSize(index);
                int flags = nativeFlags(index);
                solid |= (flags & 2) != 0;
                total += size;
                if (size < 0 || size > ComicFiles.MAX_PAGE_BYTES || total > 4L * 1024 * 1024 * 1024)
                    throw new IOException(
                            "RAR exceeds the limits: 64 MiB per entry and 4 GiB total unpacked data");
                if ((flags & 1) == 0 && ComicFiles.image(name)) {
                    if (!names.add(name.replace('\\', '/')))
                        throw new IOException("The RAR contains duplicate pages");
                    if (pages.size() >= Progress.MAX_PAGES)
                        throw new IOException("A CBR must contain between 1 and 10000 pages");
                    pages.add(new Entry(name, size, position - 1));
                }
            }
            pages.sort((a, b) -> ComicFiles.naturalCompare(a.name(), b.name()));
            if (pages.isEmpty())
                throw new IOException("A CBR must contain between 1 and 10000 pages");
        } finally {
            nativeClose(index);
        }
    }

    private static synchronized void load() throws IOException {
        if (loaded) return;
        try {
            String library = System.getProperty("comicreader.archive.library");
            if (library == null) System.loadLibrary("comicarchive");
            else System.load(library);
            loaded = true;
        } catch (UnsatisfiedLinkError error) {
            throw new IOException("The native CBR decoder is missing or incompatible", error);
        }
    }

    private static String decode(byte[] encoded) throws IOException {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(encoded))
                .toString();
    }

    int count() {
        return pages.size();
    }

    synchronized byte[] read(int index) throws IOException {
        if (closed || index < 0 || index >= count())
            throw new IOException("CBR page index out of range");
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("CBR reading cancelled");
        if (cachedIndex == index) return cached;
        Entry target = pages.get(index);
        try {
            if (handle == 0 || target.position() < next) {
                reset();
                handle = nativeOpen(path);
            }
            while (next <= target.position()) {
                byte[] encoded = nativeNext(handle);
                if (encoded == null) throw new IOException("The CBR changed or ended unexpectedly");
                boolean keep = next == target.position();
                next++;
                if (keep) {
                    if (!decode(encoded).equals(target.name())
                            || nativeSize(handle) != target.size())
                        throw new IOException("The CBR changed while reading");
                    byte[] result = nativeRead(handle, true);
                    cached = result.length <= 8 * 1024 * 1024 ? result : null;
                    cachedIndex = cached == null ? -1 : index;
                    return result;
                }
                // Solid predecessors must be decoded and verified, including non-image entries.
                if (solid) nativeRead(handle, false);
                else nativeSkip(handle);
            }
            throw new IOException("Could not locate the CBR page");
        } catch (IOException failure) {
            reset();
            throw failure;
        }
    }

    private void reset() {
        if (handle != 0) nativeClose(handle);
        handle = 0;
        next = 0;
    }

    @Override
    public synchronized void close() {
        reset();
        cached = null;
        cachedIndex = -1;
        closed = true;
    }

    private static native long nativeOpen(byte[] path) throws IOException;

    private static native byte[] nativeNext(long handle) throws IOException;

    private static native long nativeSize(long handle) throws IOException;

    private static native int nativeFlags(long handle) throws IOException;

    private static native byte[] nativeRead(long handle, boolean keep) throws IOException;

    private static native void nativeSkip(long handle) throws IOException;

    private static native void nativeClose(long handle);
}
