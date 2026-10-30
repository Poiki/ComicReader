package org.comicreader.app;

import java.io.*;
import java.util.*;

/** Versioned, bounded exchange format; no paths, account details or credentials. */
public final class Progress {
    public static final int MAX_BOOKS = 50_000, MAX_PAGES = 10_000, MAX_BYTES = 8 * 1024 * 1024;
    public final String id, device;
    public final int page, pages;
    public final boolean completed, favorite;
    public final long revision;
    private final int[] bookmarks;

    public Progress(
            String id,
            int page,
            int pages,
            boolean completed,
            boolean favorite,
            int[] bookmarks,
            long revision,
            String device) {
        if (!id.matches("[0-9a-f]{64}")
                || !device.matches("[0-9a-f-]{36}")
                || pages < 1
                || pages > MAX_PAGES
                || page < 0
                || page >= pages
                || revision < 1
                || revision > 1_000_000_000_000L
                || bookmarks.length > pages)
            throw new IllegalArgumentException("Invalid reading state");
        this.bookmarks = bookmarks.clone();
        Arrays.sort(this.bookmarks);
        for (int i = 0; i < this.bookmarks.length; i++)
            if (this.bookmarks[i] < 0
                    || this.bookmarks[i] >= pages
                    || (i > 0 && this.bookmarks[i] == this.bookmarks[i - 1]))
                throw new IllegalArgumentException("Invalid bookmark");
        this.id = id;
        this.page = page;
        this.pages = pages;
        this.completed = completed;
        this.favorite = favorite;
        this.revision = revision;
        this.device = device;
    }

    public int[] bookmarks() {
        return bookmarks.clone();
    }

    public boolean bookmarked(int number) {
        return Arrays.binarySearch(bookmarks, number) >= 0;
    }

    public static Progress latest(Progress a, Progress b) {
        if (!a.id.equals(b.id)) throw new IllegalArgumentException("Different comics");
        int order = Long.compare(a.revision, b.revision);
        if (order == 0) order = a.device.compareTo(b.device);
        if (order == 0
                && (a.page != b.page
                        || a.pages != b.pages
                        || a.completed != b.completed
                        || a.favorite != b.favorite
                        || !Arrays.equals(a.bookmarks, b.bookmarks)))
            throw new IllegalArgumentException("A revision contains conflicting states");
        return order >= 0 ? a : b;
    }

    public static byte[] encode(Collection<Progress> states) throws IOException {
        if (states.size() > MAX_BOOKS) throw new IOException("Too many comics");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x43525031); // CRP1, network byte order.
            out.writeInt(states.size());
            TreeMap<String, Progress> sorted = new TreeMap<>();
            for (Progress state : states)
                if (sorted.put(state.id, state) != null) throw new IOException("Duplicate comic");
            for (Progress state : sorted.values()) {
                out.writeUTF(state.id);
                out.writeUTF(state.device);
                out.writeLong(state.revision);
                out.writeInt(state.page);
                out.writeInt(state.pages);
                out.writeBoolean(state.completed);
                out.writeBoolean(state.favorite);
                out.writeInt(state.bookmarks.length);
                for (int page : state.bookmarks) out.writeInt(page);
                if (bytes.size() > MAX_BYTES)
                    throw new IOException("Progress exceeds the size limit");
            }
        }
        return bytes.toByteArray();
    }

    public static List<Progress> decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("Progress exceeds the size limit");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0x43525031) throw new IOException("Unsupported progress format");
            int count = in.readInt();
            if (count < 0 || count > MAX_BOOKS) throw new IOException("Invalid comic count");
            List<Progress> states = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < count; i++) {
                String id = in.readUTF(), device = in.readUTF();
                long revision = in.readLong();
                int page = in.readInt(), pages = in.readInt();
                boolean completed = in.readBoolean(), favorite = in.readBoolean();
                int size = in.readInt();
                if (size < 0 || size > MAX_PAGES) throw new IOException("Invalid bookmark count");
                int[] bookmarks = new int[size];
                for (int j = 0; j < size; j++) bookmarks[j] = in.readInt();
                if (!ids.add(id)) throw new IOException("Duplicate comic");
                states.add(
                        new Progress(
                                id, page, pages, completed, favorite, bookmarks, revision, device));
            }
            if (in.read() != -1) throw new IOException("Unexpected trailing progress data");
            return states;
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }
}
