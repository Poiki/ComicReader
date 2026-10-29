package org.comicreader.app;

import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import java.io.*;
import java.util.*;
import java.util.zip.*;

final class PageSource implements AutoCloseable {
    private ZipFile zip;
    private PdfRenderer pdf;
    private ParcelFileDescriptor descriptor;
    private final List<ZipEntry> entries = new ArrayList<>();

    PageSource(File file, String format) throws IOException {
        try {
            if (format.equals("pdf")) {
                descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                pdf = new PdfRenderer(descriptor);
            } else {
                zip = new ZipFile(file);
                Enumeration<? extends ZipEntry> iterator = zip.entries(); int seen = 0;
                Set<String> names = new HashSet<>();
                while (iterator.hasMoreElements()) {
                    ZipEntry entry = iterator.nextElement();
                    if (++seen > 50_000) throw new IOException("El ZIP contiene demasiadas entradas");
                    if (!entry.isDirectory() && ComicFiles.image(entry.getName())) {
                        if (!names.add(entry.getName())) throw new IOException("El ZIP contiene páginas duplicadas");
                        if (entry.getSize() < 0 || entry.getSize() > ComicFiles.MAX_PAGE_BYTES)
                            throw new IOException("Una página supera el límite de 64 MiB");
                        entries.add(entry);
                    }
                }
                entries.sort((a, b) -> ComicFiles.naturalCompare(a.getName(), b.getName()));
            }
            if (count() == 0 || count() > Progress.MAX_PAGES) throw new IOException("El cómic debe contener entre 1 y 10000 páginas");
        } catch (IOException | RuntimeException e) { close(); throw e; }
    }
    int count() { return pdf != null ? pdf.getPageCount() : entries.size(); }

    Bitmap render(int index, int width, int height) throws IOException {
        if (index < 0 || index >= count()) throw new IOException("Página fuera de rango");
        if (pdf != null) {
            try (PdfRenderer.Page page = pdf.openPage(index)) {
                double scale = Math.min((double) width / page.getWidth(), (double) height / page.getHeight());
                scale = Math.min(scale, Math.sqrt(3_000_000.0 / ((long) page.getWidth() * page.getHeight())));
                Bitmap bitmap = Bitmap.createBitmap(Math.max(1, (int) (page.getWidth() * scale)),
                        Math.max(1, (int) (page.getHeight() * scale)), Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bitmap;
            }
        }
        ZipEntry entry = entries.get(index);
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        try (InputStream in = ComicFiles.limited(zip.getInputStream(entry), ComicFiles.MAX_PAGE_BYTES)) { BitmapFactory.decodeStream(in, null, options); }
        if (options.outWidth <= 0 || options.outHeight <= 0
                || (long) options.outWidth * options.outHeight > 100_000_000L)
            throw new IOException("Página no válida o imagen de más de 100 megapíxeles");
        options.inSampleSize = ComicFiles.sampleSize(options.outWidth, options.outHeight, width, height);
        options.inJustDecodeBounds = false; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap;
        try (InputStream in = ComicFiles.limited(zip.getInputStream(entry), ComicFiles.MAX_PAGE_BYTES)) { bitmap = BitmapFactory.decodeStream(in, null, options); }
        if (bitmap == null) throw new IOException("No se puede decodificar " + entry.getName());
        return bitmap;
    }
    @Override public void close() throws IOException {
        if (pdf != null) { pdf.close(); pdf = null; }
        if (descriptor != null) { descriptor.close(); descriptor = null; }
        if (zip != null) { zip.close(); zip = null; }
    }
}
