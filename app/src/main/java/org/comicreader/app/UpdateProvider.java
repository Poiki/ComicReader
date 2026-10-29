package org.comicreader.app;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Grants the installer read access to one APK, never to library files or sessions. */
public final class UpdateProvider extends ContentProvider {
    static final Uri URI = Uri.parse("content://org.comicreader.app.updates/apk");
    static File apk(Context context) { return new File(context.getCacheDir(), "updates/update.apk"); }
    private static void path(Uri uri) { if (!URI.equals(uri)) throw new IllegalArgumentException("URI de actualización inválida"); }
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { path(uri); return "application/vnd.android.package-archive"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        path(uri); if (!mode.equals("r")) throw new FileNotFoundException("Solo lectura");
        return ParcelFileDescriptor.open(apk(getContext()), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        path(uri); String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns); Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) values[i] = switch (columns[i]) {
            case OpenableColumns.DISPLAY_NAME -> "ComicReader-update.apk";
            case OpenableColumns.SIZE -> apk(getContext()).length();
            default -> throw new IllegalArgumentException("Columna no disponible");
        };
        if (apk(getContext()).isFile()) cursor.addRow(values); return cursor;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
}
