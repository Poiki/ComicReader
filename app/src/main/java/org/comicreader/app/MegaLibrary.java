package org.comicreader.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;

import java.util.*;

/** Remote metadata stays separate from content-hash reading progress. */
final class MegaLibrary extends SQLiteOpenHelper {
    private static MegaLibrary instance;

    static synchronized MegaLibrary get(Context context) {
        if (instance == null) instance = new MegaLibrary(context.getApplicationContext());
        return instance;
    }

    private MegaLibrary(Context context) {
        super(context, "mega-library.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE folders(account TEXT,handle INTEGER,name TEXT NOT NULL,updated INTEGER NOT NULL,PRIMARY KEY(account,handle))");
        db.execSQL(
                "CREATE TABLE nodes(account TEXT,handle INTEGER,parent INTEGER NOT NULL,name TEXT NOT NULL,directory INTEGER NOT NULL,size INTEGER NOT NULL,PRIMARY KEY(account,handle))");
        db.execSQL("CREATE INDEX children ON nodes(account,parent)");
        db.execSQL(
                "CREATE TABLE links(account TEXT,handle INTEGER,bookid TEXT,completed INTEGER,favorite INTEGER,PRIMARY KEY(account,handle))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException(
                "The MEGA catalog has been preserved; migration is unavailable");
    }

    private static void account(String account) {
        if (account == null || !account.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Invalid MEGA account");
    }

    synchronized void replace(
            String account, long parent, String name, List<MegaClient.Node> nodes) {
        account(account);
        if (name == null
                || name.isBlank()
                || name.length() > 1024
                || nodes.size() > Progress.MAX_BOOKS)
            throw new IllegalArgumentException("Invalid MEGA folder");
        Set<Long> handles = new HashSet<>();
        for (MegaClient.Node node : nodes) {
            if (node == null
                    || node.handle() == MegaClient.ROOT
                    || node.handle() == parent
                    || !handles.add(node.handle())
                    || node.name() == null
                    || node.name().isBlank()
                    || node.name().length() > 1024
                    || !node.directory() && node.size() < 0)
                throw new IllegalArgumentException("Invalid or duplicate MEGA metadata");
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(
                    "nodes",
                    "account=? AND parent=?",
                    new String[] {account, Long.toString(parent)});
            for (MegaClient.Node node : nodes) {
                if (node.name().equals(".comicreader-state-v1")
                        || !node.directory() && !ComicFiles.supported(node.name())) continue;
                ContentValues values = new ContentValues();
                values.put("account", account);
                values.put("handle", node.handle());
                values.put("parent", parent);
                values.put("name", node.name());
                values.put("directory", node.directory() ? 1 : 0);
                values.put("size", node.directory() ? 0 : node.size());
                if (db.insertWithOnConflict("nodes", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                        == -1) throw new SQLiteException("The catalog was not saved");
            }
            ContentValues folder = new ContentValues();
            folder.put("account", account);
            folder.put("handle", parent);
            folder.put("name", name);
            folder.put("updated", System.currentTimeMillis());
            if (db.insertWithOnConflict("folders", null, folder, SQLiteDatabase.CONFLICT_REPLACE)
                    == -1) throw new SQLiteException("The folder was not saved");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    synchronized Snapshot snapshot(String account, long parent) {
        account(account);
        SQLiteDatabase db = getReadableDatabase();
        String name;
        long updated;
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT name,updated FROM folders WHERE account=? AND handle=?",
                        new String[] {account, Long.toString(parent)})) {
            if (!cursor.moveToFirst()) return null;
            name = cursor.getString(0);
            updated = cursor.getLong(1);
        }
        List<Entry> entries = new ArrayList<>();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT n.handle,n.name,n.directory,n.size,l.bookid,l.completed,l.favorite FROM nodes n LEFT JOIN links l ON n.account=l.account AND n.handle=l.handle WHERE n.account=? AND n.parent=?",
                        new String[] {account, Long.toString(parent)})) {
            while (cursor.moveToNext())
                entries.add(
                        new Entry(
                                new MegaClient.Node(
                                        cursor.getLong(0),
                                        cursor.getString(1),
                                        cursor.getInt(2) != 0,
                                        cursor.getLong(3)),
                                cursor.getString(4),
                                cursor.isNull(5) ? null : cursor.getInt(5) != 0,
                                cursor.isNull(6) ? null : cursor.getInt(6) != 0));
        }
        entries.sort(
                (a, b) ->
                        a.node.directory() != b.node.directory()
                                ? (a.node.directory() ? -1 : 1)
                                : ComicFiles.naturalCompare(a.node.name(), b.node.name()));
        return new Snapshot(name, updated, entries);
    }

    synchronized void link(Context context, String account, long handle, Library.Book book) {
        account(account);
        ContentValues values = new ContentValues();
        Library library = Library.get(context);
        Progress state = library.state(book.id());
        try (Cursor cursor =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT completed,favorite FROM links WHERE account=? AND handle=?",
                                new String[] {account, Long.toString(handle)})) {
            if (cursor.moveToFirst() && (!cursor.isNull(0) || !cursor.isNull(1)))
                library.update(
                        book.id(),
                        state.page,
                        cursor.isNull(0) ? state.completed : cursor.getInt(0) != 0,
                        cursor.isNull(1) ? state.favorite : cursor.getInt(1) != 0,
                        state.bookmarks());
        }
        values.put("account", account);
        values.put("handle", handle);
        values.put("bookid", book.id());
        if (getWritableDatabase()
                        .insertWithOnConflict(
                                "links", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                == -1) throw new SQLiteException("Could not link the MEGA comic");
    }

    synchronized void flags(
            Context context, String account, Entry entry, Boolean completed, Boolean favorite) {
        account(account);
        Progress state = entry.bookId == null ? null : Library.get(context).state(entry.bookId);
        if (state != null) {
            Library.get(context)
                    .update(
                            state.id,
                            Boolean.FALSE.equals(completed) ? 0 : state.page,
                            completed == null ? state.completed : completed,
                            favorite == null ? state.favorite : favorite,
                            state.bookmarks());
            return;
        }
        ContentValues values = new ContentValues();
        values.put("account", account);
        values.put("handle", entry.node.handle());
        values.put("bookid", entry.bookId);
        Boolean read = completed == null ? entry.completed : completed,
                star = favorite == null ? entry.favorite : favorite;
        if (read != null) values.put("completed", read ? 1 : 0);
        if (star != null) values.put("favorite", star ? 1 : 0);
        if (getWritableDatabase()
                        .insertWithOnConflict(
                                "links", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                == -1) throw new SQLiteException("MEGA state was not saved");
    }

    record Entry(MegaClient.Node node, String bookId, Boolean completed, Boolean favorite) {}

    record Snapshot(String name, long updated, List<Entry> entries) {}
}
