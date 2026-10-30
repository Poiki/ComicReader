package org.comicreader.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class Library extends SQLiteOpenHelper {
    static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static Library instance;
    public static synchronized Library get(Context context) {
        if (instance == null) instance = new Library(context.getApplicationContext());
        return instance;
    }
    private final File directory;
    public final String device;
    private Library(Context context) {
        super(context, "library.db", null, 1);
        directory = new File(context.getFilesDir(), "comics");
        SharedPreferences preferences = context.getSharedPreferences("reader", 0);
        String stored = preferences.getString("device", null);
        if (stored == null) {
            stored = UUID.randomUUID().toString();
            if (!preferences.edit().putString("device", stored).commit()) throw new IllegalStateException("No se puede guardar la identidad del dispositivo");
        }
        device = stored;
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT NOT NULL,format TEXT NOT NULL,size INTEGER NOT NULL,pages INTEGER NOT NULL,added INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE progress(id TEXT PRIMARY KEY,payload BLOB NOT NULL)");
        db.execSQL("CREATE TABLE clock(value INTEGER NOT NULL)");
        db.execSQL("INSERT INTO clock VALUES(0)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Migración no disponible; se conserva la biblioteca");
    }
    public File file(Book book) { return new File(directory, book.id + "." + book.format); }
    public File staging() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("No se puede crear la biblioteca");
        return File.createTempFile("import-", ".tmp", directory);
    }
    public synchronized void add(Book book, File staged, int pages) throws IOException {
        Progress previous = state(book.id);
        if (previous == null && stateCount() >= Progress.MAX_BOOKS) throw new IOException("La biblioteca de progreso supera los 50.000 cómics");
        if (previous != null && previous.pages != pages) throw new IOException("El total de páginas del progreso no coincide con el archivo. Revisa el documento de progreso importado.");
        File destination = file(book); boolean created = !destination.exists();
        if (created && !staged.renameTo(destination)) throw new IOException("No se puede guardar el cómic");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("id", book.id); values.put("title", book.title); values.put("format", book.format);
            values.put("size", book.size); values.put("pages", pages); values.put("added", System.currentTimeMillis());
            if (book(book.id) == null) db.insertOrThrow("books", null, values);
            if (state(book.id) == null) put(new Progress(book.id, 0, pages, false, false, new int[0], nextRevision(), device));
            db.setTransactionSuccessful();
        } catch (RuntimeException e) {
            if (created && !destination.delete()) android.util.Log.e("ComicReader", "Importación no confirmada: quedó una copia huérfana");
            throw e;
        } finally { db.endTransaction(); }
    }
    public synchronized List<Book> books() {
        List<Book> books = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id,title,format,size FROM books ORDER BY added DESC", null)) {
            while (cursor.moveToNext()) books.add(new Book(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3)));
        }
        return books;
    }
    public Book book(String id) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id,title,format,size FROM books WHERE id=?", new String[]{id})) {
            return cursor.moveToFirst() ? new Book(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3)) : null;
        }
    }
    public synchronized Progress state(String id) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT payload FROM progress WHERE id=?", new String[]{id})) {
            return cursor.moveToFirst() ? decode(cursor.getBlob(0)).get(0) : null;
        }
    }
    public synchronized List<Progress> states() {
        List<Progress> states = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT payload FROM progress", null)) {
            while (cursor.moveToNext()) states.add(decode(cursor.getBlob(0)).get(0));
        }
        return states;
    }
    private static List<Progress> decode(byte[] bytes) {
        try { return Progress.decode(bytes); }
        catch (IOException e) { throw new IllegalStateException("Progreso local dañado; exporta la base antes de modificarla", e); }
    }
    private long nextRevision() {
        SQLiteDatabase db = getWritableDatabase();
        long value;
        try (Cursor cursor = db.rawQuery("SELECT value FROM clock", null)) { cursor.moveToFirst(); value = cursor.getLong(0); }
        if (value >= 1_000_000_000_000L) throw new IllegalStateException("Se ha agotado el contador de revisiones");
        db.execSQL("UPDATE clock SET value=?", new Object[]{value + 1});
        return value + 1;
    }
    private void put(Progress state) {
        ContentValues values = new ContentValues(); values.put("id", state.id);
        try { values.put("payload", Progress.encode(List.of(state))); }
        catch (IOException e) { throw new IllegalStateException(e); }
        if (getWritableDatabase().insertWithOnConflict("progress", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1)
            throw new SQLiteException("No se ha guardado el progreso");
    }
    public synchronized Progress update(String id, int page, boolean completed, boolean favorite, int[] bookmarks) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            Progress current = state(id);
            if (current == null) throw new IllegalArgumentException("Cómic desconocido");
            Progress next = new Progress(id, page, current.pages, completed, favorite, bookmarks, nextRevision(), device);
            put(next); db.setTransactionSuccessful(); return next;
        } finally { db.endTransaction(); }
    }
    public synchronized Progress updateReading(String id, Integer page, Boolean completed, int[] added, int[] removed) {
        Progress current = state(id);
        if (current == null) throw new IllegalArgumentException("Cómic desconocido");
        TreeSet<Integer> marks = new TreeSet<>(); for (int mark : current.bookmarks()) marks.add(mark);
        for (int mark : removed) marks.remove(mark); for (int mark : added) marks.add(mark);
        return update(id, page == null ? current.page : page, completed == null ? current.completed : completed,
                current.favorite, marks.stream().mapToInt(Integer::intValue).toArray());
    }
    public synchronized void merge(Collection<Progress> incoming) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            long clock, count = stateCount();
            try (Cursor cursor = db.rawQuery("SELECT value FROM clock", null)) { cursor.moveToFirst(); clock = cursor.getLong(0); }
            for (Progress state : incoming) {
                // shortcut: reject implausible 10-million-action jumps; revise the protocol for unusually old archives.
                if (state.revision > clock + 10_000_000L) throw new IllegalArgumentException("La revisión remota excede el margen de seguridad; no se ha importado nada");
                try (Cursor cursor = db.rawQuery("SELECT pages FROM books WHERE id=?", new String[]{state.id})) {
                    if (cursor.moveToFirst() && cursor.getInt(0) != state.pages)
                        throw new IllegalArgumentException("El progreso remoto no coincide con las páginas del cómic local");
                }
                Progress current = state(state.id);
                if (current == null && ++count > Progress.MAX_BOOKS) throw new IllegalArgumentException("La biblioteca de progreso supera los 50.000 cómics");
                put(current == null ? state : Progress.latest(current, state));
                db.execSQL("UPDATE clock SET value=MAX(value,?)", new Object[]{state.revision});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    private long stateCount() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM progress", null)) { cursor.moveToFirst(); return cursor.getLong(0); }
    }
    public synchronized void remove(Book book) throws IOException {
        File file = file(book);
        if (file.exists() && !file.delete()) throw new IOException("No se puede eliminar la copia local");
        getWritableDatabase().delete("books", "id=?", new String[]{book.id});
    }
    public record Book(String id, String title, String format, long size) {
        public Book {
            if (id == null || !id.matches("[0-9a-f]{64}") || title == null || title.isBlank() || title.length() > 1024
                    || !Set.of("cbz", "cbr", "pdf").contains(format) || size < 0 || size > ComicFiles.MAX_COMIC)
                throw new IllegalArgumentException("Identidad de cómic inválida");
        }
    }
}
