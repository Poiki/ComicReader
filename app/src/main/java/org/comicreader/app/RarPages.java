package org.comicreader.app;

import com.github.junrar.*;
import com.github.junrar.rarfile.*;
import com.github.junrar.rarfile.rar5.Rar5MainHeader;
import com.github.junrar.volume.FileVolume;

import java.io.*;
import java.util.*;

/** One bounded page at a time; archive names are never filesystem destinations. */
final class RarPages implements AutoCloseable {
    private Archive archive;
    private final List<FileHeader> physical = new ArrayList<>(), pages = new ArrayList<>();
    private boolean solid;
    private int next;

    RarPages(File file) throws IOException {
        try {
            archive =
                    new Archive(
                            (owner, previous) -> {
                                if (previous != null)
                                    throw new IOException(
                                            "Multi-volume CBR archives are not supported");
                                return new FileVolume(owner, file);
                            },
                            ArchiveOptions.builder().maxDictionarySize(64L * 1024 * 1024).build());
            if (archive.isPasswordProtected())
                throw new IOException("The CBR is encrypted; use an unencrypted copy");
            if (archive.hasBrokenHeaders()) throw new IOException("The CBR has damaged headers");
            List<BaseBlock> headers = archive.getHeaders();
            if (headers.size() > 50_000) throw new IOException("The RAR has too many entries");
            if (archive.getMainHeader() != null) {
                if (archive.getMainHeader().isMultiVolume())
                    throw new IOException("Multi-volume CBR archives are not supported");
                solid = archive.getMainHeader().isSolid();
            }
            for (BaseBlock header : headers)
                if (header instanceof Rar5MainHeader main) {
                    if (main.isVolume() || main.isNextVolume())
                        throw new IOException("Multi-volume CBR archives are not supported");
                    solid |= main.isSolid();
                }
            long total = 0;
            Set<String> names = new HashSet<>();
            physical.addAll(archive.getFileHeaders());
            for (FileHeader header : physical) {
                if (header.isSplitBefore() || header.isSplitAfter())
                    throw new IOException("The CBR requires additional volumes");
                long size = header.getFullUnpackSize();
                total += size;
                solid |= header.isSolid();
                if (size < 0 || size > ComicFiles.MAX_PAGE_BYTES || total > 4L * 1024 * 1024 * 1024)
                    throw new IOException(
                            "RAR exceeds the limits: 64 MiB per entry and 4 GiB total unpacked data");
                if (header.getRar5WinSize() > 64L * 1024 * 1024)
                    throw new IOException("The RAR dictionary exceeds the 64 MiB limit");
                if (!header.isDirectory() && ComicFiles.image(header.getFileName())) {
                    if (!names.add(header.getFileName().replace('\\', '/')))
                        throw new IOException("The RAR contains duplicate pages");
                    pages.add(header);
                }
            }
            pages.sort((a, b) -> ComicFiles.naturalCompare(a.getFileName(), b.getFileName()));
            if (pages.isEmpty() || pages.size() > Progress.MAX_PAGES)
                throw new IOException("A CBR must contain between 1 and 10000 pages");
        } catch (Exception failure) {
            try {
                close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            if (failure instanceof IOException io) throw io;
            throw new IOException(
                    "Cannot open the CBR/RAR: damaged, encrypted or unsupported archive variant",
                    failure);
        }
    }

    int count() {
        return pages.size();
    }

    byte[] read(int index) throws IOException {
        if (archive == null || index < 0 || index >= count())
            throw new IOException("CBR page index out of range");
        FileHeader target = pages.get(index);
        int position = physical.indexOf(target);
        try {
            // Replay solid predecessors explicitly: the library's implicit skip can swallow their
            // CRC errors.
            if (solid) {
                if (position < next) next = 0;
                while (next < position) {
                    FileHeader previous = physical.get(next++);
                    if (!previous.isDirectory()) extract(previous, false);
                }
            }
            byte[] result = extract(target, true);
            next = position + 1;
            return result;
        } catch (Exception failure) {
            next = 0;
            if (failure instanceof IOException io) throw io;
            throw new IOException("Could not verify or extract the CBR page", failure);
        }
    }

    private byte[] extract(FileHeader header, boolean keep) throws Exception {
        int length = (int) header.getFullUnpackSize();
        byte[] bytes = keep ? new byte[length] : null;
        int[] written = {0};
        archive.extractFile(
                header,
                new OutputStream() {
                    private void check(int count) throws IOException {
                        if (Thread.currentThread().isInterrupted())
                            throw new InterruptedIOException("CBR reading cancelled");
                        if (count < 0 || count > length - written[0])
                            throw new IOException("The RAR page exceeds its declared size");
                    }

                    @Override
                    public void write(int value) throws IOException {
                        check(1);
                        if (bytes != null) bytes[written[0]] = (byte) value;
                        written[0]++;
                    }

                    @Override
                    public void write(byte[] input, int offset, int count) throws IOException {
                        check(count);
                        if (bytes != null)
                            System.arraycopy(input, offset, bytes, written[0], count);
                        written[0] += count;
                    }
                });
        if (written[0] != length) throw new IOException("Incomplete RAR page");
        return bytes;
    }

    @Override
    public void close() throws IOException {
        if (archive != null) {
            archive.close();
            archive = null;
        }
    }
}
