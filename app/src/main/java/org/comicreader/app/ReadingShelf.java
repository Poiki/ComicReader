package org.comicreader.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.net.Uri;

import java.io.*;
import java.net.URI;
import java.util.*;

/** Cached folder identity and reading suggestions; comic contents and progress stay in Library. */
final class ReadingShelf extends SQLiteOpenHelper {
    static final int MAX_KEY_LENGTH = 131072;
    private static final Set<String> PROVIDERS = Set.of("local", "mega", "drive", "webdav");
    private static ReadingShelf instance;
    private final Context context;

    static synchronized ReadingShelf get(Context context) {
        if (instance == null) instance = new ReadingShelf(context.getApplicationContext());
        return instance;
    }

    private ReadingShelf(Context context) {
        super(context, "reading-shelf.db", null, 1);
        this.context = context.getApplicationContext();
    }

    record Location(
            String provider, String owner, String folder, String entry, String name, long size) {
        Location {
            if (!PROVIDERS.contains(provider)
                    || owner == null
                    || owner.length() > 256
                    || folder == null
                    || folder.length() > 4096
                    || entry == null
                    || entry.isBlank()
                    || entry.length() > 4096
                    || name == null
                    || name.isBlank()
                    || name.length() > 1024
                    || !ComicFiles.supported(name)
                    || size < -1
                    || size > ComicFiles.MAX_COMIC)
                throw new IllegalArgumentException("Invalid reading location");
        }

        String key() {
            return new Uri.Builder()
                    .scheme("comicreader")
                    .authority("read")
                    .appendPath(provider)
                    .appendPath(owner)
                    .appendPath(folder)
                    .appendPath(entry)
                    .build()
                    .toString();
        }

        String scope() {
            return provider + "\n" + owner + "\n" + folder;
        }
    }

    record Item(Location location, Library.Book book, Progress progress, boolean completed) {
        String title() {
            return location.name;
        }

        String identity() {
            return book != null ? book.id() : progress != null ? progress.id : location.key();
        }

        String scope() {
            return location.scope();
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE entries(key TEXT PRIMARY KEY,provider TEXT NOT NULL,owner TEXT NOT NULL,folder TEXT NOT NULL,entry TEXT NOT NULL,name TEXT NOT NULL,size INTEGER NOT NULL,present INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX folders ON entries(provider,owner,folder)");
        db.execSQL("CREATE TABLE origins(bookid TEXT NOT NULL,key TEXT PRIMARY KEY)");
        db.execSQL("CREATE INDEX content_origins ON origins(bookid)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new SQLiteException("Reading locations were preserved; migration is unavailable");
    }

    private void put(Location source, boolean present) {
        ContentValues values = new ContentValues();
        values.put("key", source.key());
        values.put("provider", source.provider);
        values.put("owner", source.owner);
        values.put("folder", source.folder);
        values.put("entry", source.entry);
        values.put("name", source.name);
        values.put("size", source.size);
        values.put("present", present ? 1 : 0);
        if (getWritableDatabase()
                        .insertWithOnConflict(
                                "entries", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                == -1) throw new SQLiteException("Could not save the reading location");
    }

    synchronized void link(Library.Book book, Location source) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            put(source, true);
            // A provider locator can be replaced with a different content hash.
            db.execSQL(
                    "DELETE FROM origins WHERE key=? AND bookid<>?",
                    new Object[] {source.key(), book.id()});
            db.execSQL(
                    "INSERT OR REPLACE INTO origins(bookid,key) VALUES(?,?)",
                    new Object[] {book.id(), source.key()});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        ComicWidget.changed(context);
    }

    synchronized void replace(
            String provider, String owner, String folder, List<Location> sources) {
        if (sources.size() > Progress.MAX_BOOKS)
            throw new IllegalArgumentException("Too many reading entries");
        Set<String> keys = new HashSet<>();
        for (Location source : sources)
            if (!source.provider.equals(provider)
                    || !source.owner.equals(owner)
                    || !source.folder.equals(folder)
                    || !keys.add(source.key()))
                throw new IllegalArgumentException("Invalid or repeated folder entry");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL(
                    "UPDATE entries SET present=0 WHERE provider=? AND owner=? AND folder=?",
                    new Object[] {provider, owner, folder});
            for (Location source : sources) put(source, true);
            db.execSQL(
                    "DELETE FROM entries WHERE present=0 AND key NOT IN (SELECT key FROM origins)");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        ComicWidget.changed(context);
    }

    private static Location location(Cursor row) {
        return new Location(
                row.getString(0),
                row.getString(1),
                row.getString(2),
                row.getString(3),
                row.getString(4),
                row.getLong(5));
    }

    synchronized List<Item> items() {
        Library library = Library.get(context);
        Map<String, Library.Book> books = new LinkedHashMap<>();
        for (Library.Book book : library.books()) books.put(book.id(), book);
        Map<String, Progress> states = new HashMap<>();
        for (Progress state : library.states()) states.put(state.id, state);
        Map<String, Item> items = new LinkedHashMap<>();
        Set<String> located = new HashSet<>();
        String mega = context.getSharedPreferences("library", 0).getString("mega_account", "");
        if (!mega.isEmpty() && MegaClient.instance(context).loggedIn()) {
            for (MegaLibrary.ReadingEntry entry : MegaLibrary.get(context).readingEntries(mega)) {
                Location source =
                        new Location(
                                "mega",
                                mega,
                                Long.toString(entry.parent()),
                                Long.toString(entry.entry().node().handle()),
                                entry.entry().node().name(),
                                entry.entry().node().size());
                Library.Book book = books.get(entry.entry().bookId());
                Progress state = states.get(entry.entry().bookId());
                items.put(
                        source.key(),
                        new Item(
                                source,
                                book,
                                state,
                                state == null
                                        ? Boolean.TRUE.equals(entry.entry().completed())
                                        : state.completed));
                if (book != null) located.add(book.id());
            }
        }
        try (Cursor rows =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT e.provider,e.owner,e.folder,e.entry,e.name,e.size,o.bookid,e.present FROM entries e LEFT JOIN origins o ON e.key=o.key ORDER BY o.rowid DESC",
                                null)) {
            while (rows.moveToNext()) {
                Location source = location(rows);
                Library.Book book = books.get(rows.getString(6));
                Progress state = states.get(rows.getString(6));
                boolean account =
                        switch (source.provider) {
                            case "drive" ->
                                    source.owner.equals(
                                            context.getSharedPreferences("drive", 0)
                                                    .getString("account", ""));
                            case "webdav" ->
                                    source.owner.equals(
                                            context.getSharedPreferences("cloud", 0)
                                                    .getString("webdav_id", ""));
                            default -> false;
                        };
                if (book != null || rows.getInt(7) != 0 && account) {
                    Item item = new Item(source, book, state, state != null && state.completed);
                    Item previous = items.get(source.key());
                    if (previous == null || previous.book == null) items.put(source.key(), item);
                    if (book != null) located.add(book.id());
                }
            }
        }
        for (Library.Book book : books.values())
            if (!located.contains(book.id())) {
                Location source =
                        new Location("local", "", "imported", book.id(), book.title(), book.size());
                Progress state = states.get(book.id());
                items.put(
                        source.key(),
                        new Item(source, book, state, state != null && state.completed));
            }
        return new ArrayList<>(items.values());
    }

    Item find(String key) {
        for (Item item : items()) if (item.location.key().equals(key)) return item;
        return null;
    }

    String origin(String bookId) {
        try (Cursor row =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT key FROM origins WHERE bookid=? ORDER BY rowid DESC LIMIT 1",
                                new String[] {bookId})) {
            if (row.moveToFirst()) return row.getString(0);
        }
        return null;
    }

    Item current(String bookId, String key) {
        if (key == null) key = origin(bookId);
        List<Item> matching =
                items().stream()
                        .filter(item -> item.book != null && item.book.id().equals(bookId))
                        .toList();
        if (key != null)
            for (Item item : matching) if (item.location.key().equals(key)) return item;
        return matching.size() == 1 ? matching.get(0) : null;
    }

    static Item next(Item current, Collection<Item> items) {
        return Series.next(current, items, Item::title, Item::scope, Item::completed);
    }

    static List<Item> select(List<Item> items, String mode, String last) {
        List<Item> recent = new ArrayList<>(items);
        recent.sort(
                Comparator.<Item>comparingInt(
                                item ->
                                        item.progress != null && item.progress.id.equals(last)
                                                ? 0
                                                : 1)
                        .thenComparing(
                                Comparator.comparingLong(
                                                (Item item) ->
                                                        item.progress == null
                                                                ? 0
                                                                : item.progress.revision)
                                        .reversed()));
        LinkedHashMap<String, Item> result = new LinkedHashMap<>();
        if (!mode.equals("next"))
            for (Item item : recent) {
                if (!item.completed
                        && item.progress != null
                        && (item.progress.page > 0 || item.progress.id.equals(last)))
                    result.putIfAbsent(item.identity(), item);
                if (result.size() >= 9) break;
            }
        if (!mode.equals("continue") && result.size() < 9) {
            Map<String, List<Item>> groups = new HashMap<>();
            for (Item item : items) {
                Series.Issue issue = Series.parse(item.title());
                if (issue != null)
                    groups.computeIfAbsent(
                                    item.scope() + "\n" + issue.series(),
                                    ignored -> new ArrayList<>())
                            .add(item);
            }
            Set<String> seen = new HashSet<>();
            for (Item item : recent) {
                if (result.size() >= 9) break;
                if (item.progress != null && (item.completed || item.progress.page > 0)) {
                    Series.Issue issue = Series.parse(item.title());
                    if (issue == null) continue;
                    String group = item.scope() + "\n" + issue.series();
                    if (!seen.add(group)) continue;
                    Item next = next(item, groups.getOrDefault(group, List.of()));
                    if (next != null) result.putIfAbsent(next.identity(), next);
                }
            }
            if (mode.equals("shelf") && result.size() < 9) {
                items.stream()
                        .filter(item -> !item.completed)
                        .filter(item -> !result.containsKey(item.identity()))
                        .sorted((a, b) -> ComicFiles.naturalCompare(a.title(), b.title()))
                        .limit(9 - result.size())
                        .forEach(item -> result.putIfAbsent(item.identity(), item));
            }
        }
        return result.values().stream().limit(9).toList();
    }

    static Intent open(Context context, Item item) {
        if (item.book != null)
            return new Intent(context, ReaderActivity.class)
                    .putExtra("id", item.book.id())
                    .putExtra("reading_location", item.location.key());
        return new Intent(context, ReadActivity.class)
                .setAction("org.comicreader.app.READ")
                .setData(Uri.parse(item.location.key()));
    }

    void refresh(Location source) throws Exception {
        if (source.provider.equals("local")) return;
        if (!Cloud.downloadAllowed(context))
            throw new IOException(context.getString(R.string.download_network_required));
        switch (source.provider) {
            case "mega" -> {
                MegaClient mega = MegaClient.instance(context);
                if (!source.owner.equals(mega.account()))
                    throw new IOException("The saved folder belongs to another MEGA account");
                long folder = Long.parseLong(source.folder);
                MegaLibrary catalog = MegaLibrary.get(context);
                MegaLibrary.Snapshot saved = catalog.snapshot(source.owner, folder);
                catalog.replace(
                        source.owner,
                        folder,
                        saved == null ? source.folder : saved.name(),
                        mega.list(folder, true));
            }
            case "drive" -> {
                DriveClient drive = new DriveClient(DriveAuth.silent(context));
                if (!source.owner.equals(drive.account()))
                    throw new IOException("Reconnect the original Google Drive account");
                replace(
                        "drive",
                        source.owner,
                        source.folder,
                        drive.list(source.folder).stream()
                                .filter(
                                        entry ->
                                                !entry.directory()
                                                        && ComicFiles.supported(entry.name()))
                                .map(
                                        entry ->
                                                new Location(
                                                        "drive",
                                                        source.owner,
                                                        source.folder,
                                                        entry.id(),
                                                        entry.name(),
                                                        entry.size()))
                                .toList());
            }
            case "webdav" -> {
                WebDav dav = Cloud.client(context);
                if (!source.owner.equals(dav.owner))
                    throw new IOException("Reconnect the original WebDAV account");
                replace(
                        "webdav",
                        source.owner,
                        source.folder,
                        dav.list(URI.create(source.folder)).stream()
                                .filter(
                                        entry ->
                                                !entry.directory()
                                                        && ComicFiles.supported(entry.name()))
                                .map(
                                        entry ->
                                                new Location(
                                                        "webdav",
                                                        source.owner,
                                                        source.folder,
                                                        entry.uri().toString(),
                                                        entry.name(),
                                                        entry.size()))
                                .toList());
            }
        }
    }

    Library.Book download(Location source) throws Exception {
        Library.Book book;
        if (source.provider.equals("mega")) {
            MegaClient mega = MegaClient.instance(context);
            if (!source.owner.equals(mega.account()))
                throw new IOException("The saved folder belongs to another MEGA account");
            MegaClient.Node node =
                    new MegaClient.Node(
                            Long.parseLong(source.entry), source.name, false, source.size);
            File file = mega.download(node);
            try {
                book = Importer.fromDownload(context, source.name, file);
            } finally {
                if (file.exists() && !file.delete())
                    android.util.Log.w("ComicReader", "Could not remove a temporary download");
            }
            MegaLibrary.get(context).link(context, source.owner, node.handle(), book);
        } else if (source.provider.equals("drive")) {
            DriveClient drive = new DriveClient(DriveAuth.silent(context));
            if (!source.owner.equals(drive.account()))
                throw new IOException("Reconnect the original Google Drive account");
            try (InputStream in = drive.download(source.entry)) {
                book = Importer.fromStream(context, source.name, in);
            }
        } else if (source.provider.equals("webdav")) {
            WebDav dav = Cloud.client(context);
            if (!source.owner.equals(dav.owner))
                throw new IOException("Reconnect the original WebDAV account");
            try (InputStream in = dav.download(URI.create(source.entry))) {
                book = Importer.fromStream(context, source.name, in);
            }
        } else throw new IOException("The local copy is unavailable");
        link(book, source);
        return book;
    }
}
