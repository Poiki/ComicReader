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
            archive = new Archive((owner, previous) -> {
                if (previous != null) throw new IOException("Los CBR divididos en varios volúmenes no son compatibles");
                return new FileVolume(owner, file);
            }, ArchiveOptions.builder().maxDictionarySize(64L * 1024 * 1024).build());
            if (archive.isPasswordProtected()) throw new IOException("El CBR está cifrado; usa una copia sin contraseña");
            if (archive.hasBrokenHeaders()) throw new IOException("El CBR contiene cabeceras dañadas");
            List<BaseBlock> headers = archive.getHeaders();
            if (headers.size() > 50_000) throw new IOException("El RAR contiene demasiadas entradas");
            if (archive.getMainHeader() != null) {
                if (archive.getMainHeader().isMultiVolume()) throw new IOException("Los CBR multivolumen no son compatibles");
                solid = archive.getMainHeader().isSolid();
            }
            for (BaseBlock header : headers) if (header instanceof Rar5MainHeader main) {
                if (main.isVolume() || main.isNextVolume()) throw new IOException("Los CBR multivolumen no son compatibles");
                solid |= main.isSolid();
            }
            long total = 0; Set<String> names = new HashSet<>(); physical.addAll(archive.getFileHeaders());
            for (FileHeader header : physical) {
                if (header.isSplitBefore() || header.isSplitAfter()) throw new IOException("El CBR necesita otros volúmenes");
                long size = header.getFullUnpackSize(); total += size; solid |= header.isSolid();
                if (size < 0 || size > ComicFiles.MAX_PAGE_BYTES || total > 4L * 1024 * 1024 * 1024)
                    throw new IOException("RAR demasiado grande: máximo 64 MiB por entrada y 4 GiB descomprimidos");
                if (header.getRar5WinSize() > 64L * 1024 * 1024) throw new IOException("El diccionario RAR supera el límite de 64 MiB");
                if (!header.isDirectory() && ComicFiles.image(header.getFileName())) {
                    if (!names.add(header.getFileName().replace('\\', '/'))) throw new IOException("El RAR contiene páginas duplicadas");
                    pages.add(header);
                }
            }
            pages.sort((a, b) -> ComicFiles.naturalCompare(a.getFileName(), b.getFileName()));
            if (pages.isEmpty() || pages.size() > Progress.MAX_PAGES) throw new IOException("El CBR debe contener entre 1 y 10000 páginas");
        } catch (Exception failure) {
            try { close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            if (failure instanceof IOException io) throw io;
            throw new IOException("No se puede abrir el CBR/RAR: está dañado, cifrado o usa una variante no compatible", failure);
        }
    }
    int count() { return pages.size(); }
    byte[] read(int index) throws IOException {
        if (archive == null || index < 0 || index >= count()) throw new IOException("Página CBR fuera de rango");
        FileHeader target = pages.get(index); int position = physical.indexOf(target);
        try {
            // Replay solid predecessors explicitly: the library's implicit skip can swallow their CRC errors.
            if (solid) {
                if (position < next) next = 0;
                while (next < position) {
                    FileHeader previous = physical.get(next++);
                    if (!previous.isDirectory()) extract(previous, false);
                }
            }
            byte[] result = extract(target, true); next = position + 1; return result;
        } catch (Exception failure) {
            next = 0;
            if (failure instanceof IOException io) throw io;
            throw new IOException("No se pudo verificar o extraer la página del CBR", failure);
        }
    }
    private byte[] extract(FileHeader header, boolean keep) throws Exception {
        int length = (int)header.getFullUnpackSize(); byte[] bytes = keep ? new byte[length] : null;
        int[] written = {0};
        archive.extractFile(header, new OutputStream() {
            private void check(int count) throws IOException {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Lectura CBR cancelada");
                if (count < 0 || count > length - written[0]) throw new IOException("La página RAR supera su tamaño declarado");
            }
            @Override public void write(int value) throws IOException { check(1); if (bytes != null) bytes[written[0]] = (byte)value; written[0]++; }
            @Override public void write(byte[] input, int offset, int count) throws IOException {
                check(count); if (bytes != null) System.arraycopy(input, offset, bytes, written[0], count); written[0] += count;
            }
        });
        if (written[0] != length) throw new IOException("La página RAR está incompleta");
        return bytes;
    }
    @Override public void close() throws IOException { if (archive != null) { archive.close(); archive = null; } }
}
