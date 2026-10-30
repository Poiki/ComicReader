package org.comicreader.app;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.*;
import java.security.*;
import java.util.Locale;

final class Importer {
    static Library.Book fromUri(Context context, Uri uri) throws IOException {
        String name = null;
        try (Cursor cursor =
                context.getContentResolver()
                        .query(
                                uri,
                                new String[] {OpenableColumns.DISPLAY_NAME},
                                null,
                                null,
                                null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        }
        if (name == null || !ComicFiles.supported(name))
            throw new IOException("Select a CBZ/ZIP, CBR/RAR or PDF");
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("The provider cannot open this document");
            return fromStream(context, name, in);
        }
    }

    static Library.Book fromStream(Context context, String title, InputStream in)
            throws IOException {
        if (!ComicFiles.supported(title) || title.length() > 1024)
            throw new IOException("Unsupported format or filename");
        Library library = Library.get(context);
        File staged = library.staging();
        try {
            MessageDigest digest = sha256();
            long size;
            try (FileOutputStream out = new FileOutputStream(staged)) {
                size =
                        ComicFiles.copy(
                                new DigestInputStream(in, digest), out, ComicFiles.MAX_COMIC);
                out.getFD().sync();
            }
            return finish(context, title, staged, digest.digest(), size);
        } finally {
            if (staged.exists() && !staged.delete())
                android.util.Log.w("ComicReader", "Could not remove a temporary import");
        }
    }

    static Library.Book fromDownload(Context context, String title, File downloaded)
            throws IOException {
        File file = downloaded.getCanonicalFile();
        if (!context.getCacheDir().getCanonicalFile().equals(file.getParentFile())
                || !file.isFile())
            throw new IOException("Only downloads in the private cache can be moved");
        if (!ComicFiles.supported(title)
                || title.length() > 1024
                || file.length() > ComicFiles.MAX_COMIC)
            throw new IOException("Unsupported or oversized download");
        MessageDigest digest = sha256();
        long size = 0;
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedIOException("Import cancelled");
                size += count;
                if (size > ComicFiles.MAX_COMIC)
                    throw new IOException("Download exceeds the size limit");
                digest.update(buffer, 0, count);
            }
        }
        return finish(context, title, file, digest.digest(), size);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Library.Book finish(
            Context context, String title, File staged, byte[] digest, long size)
            throws IOException {
        StringBuilder hash = new StringBuilder();
        for (byte value : digest) hash.append(String.format(Locale.ROOT, "%02x", value & 255));
        String format =
                switch (ComicFiles.extension(title)) {
                    case "pdf" -> "pdf";
                    case "cbr", "rar" -> "cbr";
                    default -> "cbz";
                };
        Library.Book book = new Library.Book(hash.toString(), title, format, size);
        try (PageSource pages = new PageSource(staged, format)) {
            Library.get(context).add(book, staged, pages.count());
        }
        return book;
    }
}
