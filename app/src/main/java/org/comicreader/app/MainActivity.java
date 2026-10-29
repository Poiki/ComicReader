package org.comicreader.app;

import android.app.*;
import android.app.job.JobScheduler;
import android.content.*;
import android.net.Uri;
import android.text.TextUtils;
import android.view.inputmethod.InputMethodManager;
import android.os.Bundle;
import android.text.*;
import android.text.format.DateUtils;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int OPEN = 1, EXPORT = 2, RESTORE = 3;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Library.Book> books = new ArrayList<>();
    private final List<ShelfItem> visible = new ArrayList<>();
    private final List<MegaLibrary.Entry> remoteBooks = new ArrayList<>();
    private final Map<String, Library.Book> localBooks = new HashMap<>();
    private final Map<String, Progress> states = new HashMap<>();
    private final List<Button> actions = new ArrayList<>();
    private TextView status;
    private EditText search;
    private int selection;
    private boolean home = true, grid;
    private FrameLayout content;
    private LinearLayout libraryScreen, bottomBar;
    private TextView sectionTitle;
    private GridView shelf;
    private BaseAdapter adapter;
    private CoverCache covers;
    private Button add;
    private boolean busy, resumed;
    private Updater updater;
    private String megaAccount = "", megaName = "MEGA", catalogError = "";
    private long megaRoot = MegaClient.ROOT, megaFolder = MegaClient.ROOT, catalogUpdated;
    private boolean remoteMode, catalogStale = true, autoRefreshAttempted;
    private int refreshGeneration;
    private final Deque<MegaClient.Node> megaParents = new ArrayDeque<>();
    private static final String[] FILTERS = {"Todos los cómics", "Leyendo", "Sin leer", "Leídos", "Favoritos", "Título A–Z"};

    @Override public void onCreate(Bundle saved) {
        Ui.applyTheme(this);
        super.onCreate(saved);
        updater = new Updater(this);
        Ui.bars(this);
        if (android.os.Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::navigateBack);
        LinearLayout root = Ui.column(this); root.setPadding(0, 0, 0, 0); Ui.insets(root); setContentView(root);
        covers = new CoverCache(this);
        SharedPreferences libraryPreferences = getSharedPreferences("library", 0);
        grid = libraryPreferences.getBoolean("grid", true);
        megaAccount = libraryPreferences.getString("mega_account", "");
        megaRoot = megaFolder = libraryPreferences.getLong("mega_root", MegaClient.ROOT);
        megaName = libraryPreferences.getString("mega_name", "MEGA");
        remoteMode = !megaAccount.isEmpty() && libraryPreferences.getString("source", "local").equals("mega");
        if (saved != null && remoteMode) {
            autoRefreshAttempted = saved.getBoolean("mega_attempted", false); catalogStale = saved.getBoolean("mega_stale", true);
            catalogError = saved.getString("mega_error", "");
            megaFolder = saved.getLong("mega_folder", megaRoot); megaName = saved.getString("mega_name", megaName);
            long[] handles = saved.getLongArray("mega_parents"); ArrayList<String> names = saved.getStringArrayList("mega_parent_names");
            if (handles != null && names != null && handles.length == names.size())
                for (int i = 0; i < handles.length; i++) megaParents.addLast(new MegaClient.Node(handles[i], names.get(i), true, 0));
        }
        content = new FrameLayout(this); root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        libraryScreen = new LinearLayout(this); libraryScreen.setOrientation(LinearLayout.VERTICAL);
        sectionTitle = section("Biblioteca"); sectionTitle.setMaxLines(2); sectionTitle.setEllipsize(TextUtils.TruncateAt.END);
        sectionTitle.setOnClickListener(view -> { if (!megaParents.isEmpty()) upMega(); else librarySources(); });
        sectionTitle.setTooltipText("Subir carpeta o cambiar biblioteca"); libraryScreen.addView(sectionTitle);
        search = Ui.input(this, "Buscar en tu biblioteca"); search.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        search.setContentDescription("Buscar en tu biblioteca"); search.setVisibility(View.GONE); libraryScreen.addView(search, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        shelf = new GridView(this); shelf.setClipToPadding(false); shelf.setVerticalSpacing(Ui.dp(this, 4));
        shelf.setHorizontalSpacing(Ui.dp(this, 8)); shelf.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        TextView empty = Ui.text(this, "No hay cómics en esta sección. Usa + para añadir una carpeta MEGA o archivos locales.", 16); empty.setTextColor(Ui.secondary(this)); empty.setGravity(Gravity.CENTER);
        FrameLayout libraryBody = new FrameLayout(this); libraryBody.addView(shelf, new FrameLayout.LayoutParams(-1, -1));
        libraryBody.addView(empty, new FrameLayout.LayoutParams(-1, -1)); shelf.setEmptyView(empty);
        libraryScreen.addView(libraryBody, new LinearLayout.LayoutParams(-1, 0, 1));
        adapter = new BaseAdapter() {
            public int getCount() { return visible.size(); }
            public ShelfItem getItem(int position) { return visible.get(position); }
            public long getItemId(int position) { return position; }
            public int getViewTypeCount() { return 2; }
            public int getItemViewType(int position) { return grid ? 1 : 0; }
            public View getView(int position, View recycled, ViewGroup parent) {
                BookTile tile = recycled instanceof BookTile ? (BookTile) recycled : new BookTile(grid);
                ShelfItem item = getItem(position); Progress state = state(item);
                tile.title.setText(item.title());
                tile.detail.setText(item.directory() ? "Carpeta" : (favorite(item) ? "♥  " : "") + (completed(item) ? "Leído" : state == null || state.page == 0 ? "Sin leer" : (state.page + 1) + " / " + state.pages)
                        + (item.book() == null ? " · MEGA" : " · Sin conexión"));
                tile.setContentDescription(item.title() + ". " + tile.detail.getText());
                if (item.book() != null) covers.bind(tile.cover, item.book());
                else {
                    tile.cover.bookId = null;
                    android.graphics.drawable.Drawable symbol = getDrawable(item.directory() ? R.drawable.icon_folder : R.drawable.icon_cloud).mutate();
                    symbol.setTint(Ui.foreground(MainActivity.this)); tile.cover.setImageDrawable(symbol);
                }
                return tile;
            }
        };
        shelf.setAdapter(adapter); updateDisplayMode();
        shelf.setOnItemClickListener((parent, view, position, id) -> openItem(visible.get(position)));
        shelf.setOnItemLongClickListener((parent, view, position, id) -> { itemMenu(visible.get(position)); return true; });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { applyFilter(); }
            public void afterTextChanged(Editable s) { }
        });
        status = Ui.text(this, "", 14); status.setTextColor(Ui.secondary(this)); status.setMaxLines(3); root.addView(status);
        View divider = new View(this); divider.setBackgroundColor(Ui.dark(this) ? 0xff333333 : 0xffdddddd); root.addView(divider, new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
        bottomBar = new LinearLayout(this); bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(bottomBar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));
        if (saved != null ? saved.getBoolean("home", false) : getSharedPreferences("appearance", 0).getString("startup", "library").equals("home")) showHome();
        else showLibrary(saved == null ? 0 : saved.getInt("selection", 0));
        if (saved == null && Intent.ACTION_VIEW.equals(getIntent().getAction()) && getIntent().getData() != null)
            importUris(List.of(getIntent().getData()));
        else if (saved == null && getSharedPreferences("appearance", 0).getString("startup", "library").equals("reader")) resumeLastBook();
    }
    private TextView section(String label) {
        TextView view = Ui.text(this, label, 20); view.setTextColor(0xfffffbef); view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8)); view.setBackgroundColor(Ui.header(this)); return view;
    }
    private void homeRow(LinearLayout parent, int icon, String title, String subtitle, Runnable action) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(Ui.dp(this, subtitle == null ? 52 : 56));
        int padding = Ui.dp(this, 12); row.setPadding(padding, 0, padding, 0);
        ImageView symbol = new ImageView(this); symbol.setImageResource(icon); symbol.setColorFilter(Ui.foreground(this)); symbol.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(symbol, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        LinearLayout labels = new LinearLayout(this); labels.setOrientation(LinearLayout.VERTICAL); labels.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), 0, Ui.dp(this, 6));
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        TextView name = Ui.text(this, title, 18); name.setPadding(0, 0, 0, 0); labels.addView(name);
        if (subtitle != null) { TextView detail = Ui.text(this, subtitle, 14); detail.setTextColor(Ui.secondary(this)); detail.setPadding(0, Ui.dp(this, 2), 0, 0); labels.addView(detail); }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        row.setBackgroundResource(android.R.drawable.list_selector_background); row.setClickable(true); row.setFocusable(true);
        row.setContentDescription(title + (subtitle == null ? "" : ". " + subtitle)); row.setOnClickListener(view -> action.run()); parent.addView(row);
    }
    private void showHome() {
        search.setVisibility(View.GONE); search.getText().clear();
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(search.getWindowToken(), 0);
        home = true; content.removeAllViews();
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); LinearLayout navigation = new LinearLayout(this); navigation.setOrientation(LinearLayout.VERTICAL);
        navigation.setPadding(0, 0, 0, Ui.dp(this, 88)); scroll.addView(navigation); content.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        navigation.addView(section("Almacenamiento del dispositivo"));
        homeRow(navigation, R.drawable.icon_phone, "Importar archivos", "CBZ, ZIP y PDF", this::pick);
        navigation.addView(section("Biblioteca"));
        homeRow(navigation, R.drawable.icon_history, "Leyendo", null, () -> showLibrary(1));
        homeRow(navigation, R.drawable.icon_book, "Abrir biblioteca", remoteMode ? megaName : "Biblioteca local", () -> showLibrary(0));
        homeRow(navigation, R.drawable.icon_eye, "Terminados", null, () -> showLibrary(3));
        homeRow(navigation, R.drawable.icon_unread, "Sin leer", null, () -> showLibrary(2));
        homeRow(navigation, R.drawable.icon_favorite, "Favoritos", null, () -> showLibrary(4));
        homeRow(navigation, R.drawable.icon_download, "Descargas", "Copias locales para leer sin conexión", () -> setSource(false));
        navigation.addView(section("Nube"));
        homeRow(navigation, R.drawable.icon_cloud, "MEGA", "Acceso directo", this::megaLogin);
        homeRow(navigation, R.drawable.icon_cloud, "Google Drive", "Acceso directo", () -> DriveAuth.signIn(this, this::driveConnected, error -> Ui.error(this, error)));
        add = Ui.icon(this, R.drawable.icon_plus, "Añadir archivos o nube", this::addMenu); add.setBackground(Ui.background(Ui.ACCENT, Ui.dp(this, 28)));
        add.getCompoundDrawables()[0].setTint(0xff111111);
        add.setElevation(Ui.dp(this, 5)); FrameLayout.LayoutParams floating = new FrameLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56), Gravity.BOTTOM | Gravity.END);
        floating.setMargins(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16)); content.addView(add, floating);
        navigationBar(); updateStatus();
    }
    private void showLibrary(int filter) {
        home = false; selection = filter; content.removeAllViews(); content.addView(libraryScreen, new FrameLayout.LayoutParams(-1, -1));
        sectionTitle.setText((remoteMode ? "MEGA · " + megaName : "Biblioteca") + (selection == 0 ? "" : " · " + FILTERS[selection]));
        sectionTitle.setContentDescription("Carpeta actual: " + (remoteMode ? megaName : "Biblioteca local"));
        navigationBar(); applyFilter(); updateStatus();
    }
    private void setSource(boolean mega) {
        if (mega && megaAccount.isEmpty()) { megaLogin(); return; }
        remoteMode = mega; megaFolder = megaRoot; megaParents.clear();
        megaName = getSharedPreferences("library", 0).getString("mega_name", "MEGA");
        getSharedPreferences("library", 0).edit().putString("source", mega ? "mega" : "local").apply();
        catalogError = ""; catalogStale = true; autoRefreshAttempted = false; remoteBooks.clear(); showLibrary(0); refresh();
    }
    private void librarySources() {
        new AlertDialog.Builder(this).setTitle("Biblioteca").setItems(new String[]{"Biblioteca local", "Biblioteca MEGA guardada", "Elegir otra carpeta MEGA", "Actualizar carpeta MEGA"}, (dialog, which) -> {
            if (which < 2) setSource(which == 1);
            else if (which == 2) megaLogin();
            else if (remoteMode) refreshMega(); else Ui.toast(this, "Abre primero la biblioteca MEGA");
        }).setNegativeButton("Cerrar", null).show();
    }
    private void navigationBar() {
        actions.clear(); bottomBar.removeAllViews();
        if (!home && !megaParents.isEmpty()) navIcon(R.drawable.icon_back, "Subir carpeta", this::upMega);
        else navIcon(R.drawable.icon_menu, "Menú", this::showHome);
        if (!home) navIcon(R.drawable.icon_plus, "Añadir archivos o nube", this::addMenu);
        bottomBar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        navIcon(R.drawable.icon_search, "Buscar", () -> {
            if (home) showLibrary(0);
            boolean showing = search.getVisibility() != View.VISIBLE; search.setVisibility(showing ? View.VISIBLE : View.GONE);
            InputMethodManager keyboard = getSystemService(InputMethodManager.class);
            if (showing) { search.requestFocus(); keyboard.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT); }
            else { search.getText().clear(); keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0); }
        });
        if (home) navIcon(R.drawable.icon_settings, "Ajustes", this::more);
        else {
            navIcon(R.drawable.icon_book, "Filtrar biblioteca", () -> new AlertDialog.Builder(this).setTitle("Mostrar")
                    .setSingleChoiceItems(FILTERS, selection, (dialog, which) -> { dialog.dismiss(); showLibrary(which); }).setNegativeButton("Cancelar", null).show());
            navIcon(grid ? R.drawable.icon_list : R.drawable.icon_grid, grid ? "Ver lista" : "Ver portadas", () -> {
                grid = !grid; getSharedPreferences("library", 0).edit().putBoolean("grid", grid).apply(); updateDisplayMode(); navigationBar();
            });
            navIcon(R.drawable.icon_settings, "Ajustes", this::more);
        }
        if (home && add != null) actions.add(add);
        for (Button action : actions) action.setEnabled(!busy);
    }
    private void navIcon(int icon, String description, Runnable action) {
        Button button = Ui.icon(this, icon, description, action); actions.add(button);
        bottomBar.addView(button, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
    }
    private void updateDisplayMode() {
        shelf.setNumColumns(grid ? GridView.AUTO_FIT : 1); shelf.setColumnWidth(Ui.dp(this, 152));
        int pad = grid ? Ui.dp(this, 8) : 0; shelf.setPadding(pad, pad, pad, pad); if (adapter != null) adapter.notifyDataSetChanged();
    }
    private void addMenu() {
        LinearLayout choices = new LinearLayout(this); choices.setOrientation(LinearLayout.VERTICAL); choices.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        Dialog dialog = new Dialog(this); dialog.setContentView(choices);
        homeRow(choices, R.drawable.icon_phone, "Archivo local", null, () -> { dialog.dismiss(); pick(); });
        homeRow(choices, R.drawable.icon_cloud, "MEGA", null, () -> { dialog.dismiss(); megaLogin(); });
        homeRow(choices, R.drawable.icon_cloud, "Google Drive", null, () -> { dialog.dismiss(); DriveAuth.signIn(this, this::driveConnected, error -> Ui.error(this, error)); });
        homeRow(choices, R.drawable.icon_cloud, "WebDAV HTTPS", null, () -> { dialog.dismiss(); webDavMenu(); });
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.background(this))); dialog.getWindow().setGravity(Gravity.BOTTOM);
        dialog.getWindow().setLayout(-1, -2); dialog.show(); dialog.getWindow().setLayout(-1, -2);
    }
    private final class BookTile extends LinearLayout {
        final CoverCache.CoverView cover;
        final TextView title, detail;
        BookTile(boolean gridMode) {
            super(MainActivity.this); setOrientation(gridMode ? VERTICAL : HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
            int pad = Ui.dp(MainActivity.this, gridMode ? 4 : 12); setPadding(pad, Ui.dp(MainActivity.this, 6), pad, Ui.dp(MainActivity.this, 6));
            cover = new CoverCache.CoverView(MainActivity.this); cover.setScaleType(ImageView.ScaleType.FIT_CENTER); cover.setBackgroundColor(Ui.surface(MainActivity.this));
            cover.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(cover, new LinearLayout.LayoutParams(gridMode ? -1 : Ui.dp(MainActivity.this, 48), Ui.dp(MainActivity.this, gridMode ? 176 : 68)));
            LinearLayout labels = new LinearLayout(MainActivity.this); labels.setOrientation(VERTICAL);
            labels.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            title = Ui.text(MainActivity.this, "", 16); title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END);
            detail = Ui.text(MainActivity.this, "", 14); detail.setTextColor(Ui.secondary(MainActivity.this)); detail.setMaxLines(1);
            title.setPadding(Ui.dp(MainActivity.this, gridMode ? 0 : 16), Ui.dp(MainActivity.this, 4), 0, 0);
            detail.setPadding(Ui.dp(MainActivity.this, gridMode ? 0 : 16), Ui.dp(MainActivity.this, 4), 0, 0);
            labels.addView(title); labels.addView(detail); addView(labels, new LinearLayout.LayoutParams(gridMode ? -1 : 0, -2, gridMode ? 0 : 1));
        }
    }
    @Override protected void onResume() { super.onResume(); resumed = true; if (!busy) refresh(); Cloud.schedule(this); updater.onResume(); }
    private void refresh() {
        int ticket = ++refreshGeneration; String account = megaAccount; long folder = megaFolder; boolean remote = remoteMode;
        worker.execute(() -> {
            try {
                Library library = Library.get(this); List<Library.Book> loaded = library.books();
                Map<String, Progress> progress = new HashMap<>();
                for (Progress state : library.states()) progress.put(state.id, state);
                MegaLibrary.Snapshot snapshot = remote ? MegaLibrary.get(this).snapshot(account, folder) : null;
                runOnUiThread(() -> {
                    if (isDestroyed() || ticket != refreshGeneration) return;
                    books.clear(); books.addAll(loaded); localBooks.clear(); for (Library.Book book : loaded) localBooks.put(book.id(), book);
                    states.clear(); states.putAll(progress); remoteBooks.clear(); catalogUpdated = 0;
                    if (snapshot != null) { remoteBooks.addAll(snapshot.entries()); catalogUpdated = snapshot.updated(); }
                    applyFilter(); updateStatus();
                    if (resumed && remoteMode && !home && !busy && !autoRefreshAttempted && MegaClient.instance(this).loggedIn()) refreshMega();
                });
            } catch (Exception e) { runOnUiThread(() -> Ui.error(this, e)); }
        });
    }
    private void applyFilter() {
        if (adapter == null) return;
        String query = search.getText().toString().toLowerCase(Locale.ROOT);
        visible.clear();
        List<ShelfItem> items = new ArrayList<>();
        if (remoteMode) for (MegaLibrary.Entry entry : remoteBooks) items.add(new ShelfItem(localBooks.get(entry.bookId()), entry));
        else for (Library.Book book : books) items.add(new ShelfItem(book, null));
        for (ShelfItem item : items) {
            Progress state = state(item);
            if (!item.title().toLowerCase(Locale.ROOT).contains(query)) continue;
            if (!item.directory()) {
                boolean reading = state != null && state.page > 0 && !completed(item);
                if (selection == 1 && !reading || selection == 2 && (reading || completed(item))
                        || selection == 3 && !completed(item) || selection == 4 && !favorite(item)) continue;
            }
            visible.add(item);
        }
        if (selection == 5) visible.sort((a, b) -> a.directory() != b.directory() ? (a.directory() ? -1 : 1) : ComicFiles.naturalCompare(a.title(), b.title()));
        adapter.notifyDataSetChanged();
    }
    private Progress state(ShelfItem item) {
        String id = item.remote() != null ? item.remote().bookId() : item.book().id();
        return id == null ? null : states.get(id);
    }
    private boolean completed(ShelfItem item) { Progress state = state(item); return state != null ? state.completed : item.remote() != null && Boolean.TRUE.equals(item.remote().completed()); }
    private boolean favorite(ShelfItem item) { Progress state = state(item); return state != null ? state.favorite : item.remote() != null && Boolean.TRUE.equals(item.remote().favorite()); }
    private record ShelfItem(Library.Book book, MegaLibrary.Entry remote) {
        String title() { return remote != null ? remote.node().name() : book.title(); }
        boolean directory() { return remote != null && remote.node().directory(); }
    }
    private void updateStatus() {
        if (busy) return;
        SharedPreferences preferences = getSharedPreferences("cloud", 0);
        String error = preferences.getString("last_error", ""); long synced = preferences.getLong("last_sync", 0);
        status.setVisibility(home && error.isEmpty() ? View.GONE : View.VISIBLE);
        if (!home && remoteMode) {
            long count = remoteBooks.stream().filter(entry -> !entry.node().directory()).count();
            status.setText("MEGA · " + count + " cómics" + (catalogUpdated == 0 ? " · Sin listado guardado" : catalogStale ? " · Lista guardada" : " · Actualizada")
                    + (catalogError.isEmpty() ? "" : " · No se pudo actualizar: " + catalogError));
            return;
        }
        status.setText(books.isEmpty() ? "Tu biblioteca está vacía. Usa + para añadir una carpeta MEGA o un cómic."
                : books.size() + " cómics sin conexión" + (!error.isEmpty() ? " · Sync pendiente: " + error
                : synced == 0 ? "" : " · Sync " + DateUtils.getRelativeTimeSpanString(synced)));
    }
    private void pick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, OPEN);
    }
    private void open(Library.Book book) {
        startActivity(new Intent(this, ReaderActivity.class).putExtra("id", book.id()));
    }
    private void resumeLastBook() {
        String id = getSharedPreferences("reader", 0).getString("last_book", "");
        worker.execute(() -> {
            try {
                Library library = Library.get(this); Library.Book book = library.book(id); Progress state = book == null ? null : library.state(id);
                if (book != null && state != null && !state.completed && library.file(book).isFile())
                    runOnUiThread(() -> { if (!isDestroyed()) open(book); });
            } catch (Exception error) { runOnUiThread(() -> Ui.error(this, error)); }
        });
    }
    private void openItem(ShelfItem item) {
        if (busy) { Ui.toast(this, "Espera a que termine la operación actual"); return; }
        if (item.directory()) {
            megaParents.push(new MegaClient.Node(megaFolder, megaName, true, 0));
            megaFolder = item.remote().node().handle(); megaName = item.title();
            catalogError = ""; catalogStale = true; autoRefreshAttempted = false; remoteBooks.clear();
            showLibrary(selection); refresh();
        } else if (item.book() != null) open(item.book());
        else {
            MegaClient mega = MegaClient.instance(this);
            if (!mega.loggedIn()) { megaLogin(); return; }
            String account = megaAccount;
            task("Descargando para leer…", () -> {
                if (!account.equals(mega.account())) throw new IOException("La carpeta guardada pertenece a otra cuenta MEGA");
                File temporary = mega.download(item.remote().node());
                try {
                    Library.Book book = Importer.fromDownload(this, item.title(), temporary);
                    MegaLibrary.get(this).link(this, account, item.remote().node().handle(), book); return book;
                } finally { if (temporary.exists() && !temporary.delete()) android.util.Log.w("ComicReader", "No se pudo borrar la descarga temporal"); }
            }, book -> { refresh(); open(book); });
        }
    }
    private void itemMenu(ShelfItem item) {
        if (busy || item.directory()) return;
        if (item.remote() == null) { bookMenu(item.book()); return; }
        String account = megaAccount;
        List<String> labels = new ArrayList<>(List.of(favorite(item) ? "Quitar de favoritos" : "Añadir a favoritos", completed(item) ? "Marcar sin leer" : "Marcar leído"));
        if (item.book() != null) labels.add("Eliminar copia local");
        new AlertDialog.Builder(this).setTitle(item.title()).setItems(labels.toArray(new String[0]), (dialog, which) -> {
            if (which == 2) { removeBook(item.book()); return; }
            Boolean read = which == 1 ? !completed(item) : null, star = which == 0 ? !favorite(item) : null;
            task("Guardando…", () -> {
                MegaLibrary.get(this).flags(this, account, item.remote(), read, star); return null;
            }, ignored -> { refresh(); Cloud.schedule(this); });
        }).show();
    }
    private void refreshMega() {
        if (!remoteMode || busy) return;
        autoRefreshAttempted = true; String account = megaAccount, name = megaName; long folder = megaFolder;
        catalogError = "";
        task("Actualizando carpeta MEGA…", () -> {
            try {
                MegaClient mega = MegaClient.instance(this);
                if (!account.equals(mega.account())) throw new IOException("La biblioteca guardada pertenece a otra cuenta MEGA");
                MegaLibrary.get(this).replace(account, folder, name, mega.list(folder)); catalogStale = false; return null;
            } catch (Exception error) { catalogStale = true; catalogError = Ui.message(error); throw error; }
        }, ignored -> refresh());
    }
    private void bookMenu(Library.Book book) {
        Progress state = states.get(book.id());
        new AlertDialog.Builder(this).setTitle(book.title()).setItems(new String[]{
                state.favorite ? "Quitar de favoritos" : "Añadir a favoritos",
                state.completed ? "Marcar sin leer" : "Marcar leído", "Eliminar copia local"}, (dialog, selected) -> {
            if (selected == 2) removeBook(book);
            else task("Guardando…", () -> {
                Progress current = Library.get(this).state(book.id());
                return Library.get(this).update(book.id(), selected == 1 && current.completed ? 0 : current.page, selected == 1 ? !current.completed : current.completed,
                        selected == 0 ? !current.favorite : current.favorite, current.bookmarks());
            }, ignored -> { refresh(); Cloud.schedule(this); });
        }).show();
    }
    private void removeBook(Library.Book book) {
        new AlertDialog.Builder(this).setMessage("Se eliminará solo la copia privada. Conservaremos el progreso y el archivo original.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Eliminar", (dialog, which) -> task("Eliminando…", () -> {
                    Library.get(this).remove(book); return null;
                }, ignored -> refresh())).show();
    }
    private void more() {
        new AlertDialog.Builder(this).setTitle("Ajustes").setItems(new String[]{"Ajustes de lectura", "Exportar progreso", "Importar progreso", "Acerca de", "Nube y cuentas", "Sincronizar progreso", "Ordenar por título", "Tema", "Pantalla de inicio", "Carpetas y bibliotecas", "Actualizar biblioteca", "Actualizaciones de la app"}, (dialog, selected) -> {
            switch (selected) {
                case 0 -> readingSettings();
                case 1 -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream")
                        .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "comicreader-progress.crp"), EXPORT);
                case 2 -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), RESTORE);
                case 4 -> cloudMenu();
                case 5 -> sync();
                case 6 -> showLibrary(5);
                case 7 -> themeSettings();
                case 8 -> startupSettings();
                case 9 -> librarySources();
                case 10 -> { if (remoteMode) refreshMega(); else refresh(); }
                case 11 -> updater.showSettings();
                case 3 -> new AlertDialog.Builder(this).setTitle("ComicReader · MIT")
                        .setMessage("CBZ / ZIP y PDF. Código propio y sin anuncios ni telemetría.\n\nDrive: autorización directa Google (requiere proyecto OAuth propio).\nMEGA: "
                                + (MegaClient.instance(this).available() ? "SDK oficial integrado para acceso directo." : "Esta compilación no incluye el SDK nativo.")
                                + "\nWebDAV HTTPS también disponible.\n\nLos CBR/RAR y 7z no son compatibles en esta versión.")
                        .setPositiveButton("Aceptar", null).show();
            }
        }).show();
    }
    private void themeSettings() {
        String[] values = {"light", "dark", "oled"};
        new AlertDialog.Builder(this).setTitle("Tema").setSingleChoiceItems(new String[]{"Claro", "Oscuro", "Negro OLED"}, Arrays.asList(values).indexOf(Ui.theme(this)), (dialog, which) -> {
            getSharedPreferences("appearance", 0).edit().putString("theme", values[which]).apply(); dialog.dismiss(); recreate();
        }).setNegativeButton("Cancelar", null).show();
    }
    private void startupSettings() {
        String[] values = {"library", "home", "reader"}; SharedPreferences preferences = getSharedPreferences("appearance", 0);
        new AlertDialog.Builder(this).setTitle("Al abrir la app").setSingleChoiceItems(new String[]{"Biblioteca", "Conexiones y configuración", "Último cómic en curso"},
                Arrays.asList(values).indexOf(preferences.getString("startup", "library")), (dialog, which) -> {
                    preferences.edit().putString("startup", values[which]).apply(); dialog.dismiss();
                }).setNegativeButton("Cancelar", null).show();
    }
    private void readingSettings() {
        SharedPreferences preferences = getSharedPreferences("reader", 0);
        String[] keys = {"manga", "width", "volume", "keep_screen"};
        String[] labels = {"Manga: derecha a izquierda", "Ajustar al ancho", "Pasar página con volumen", "Mantener pantalla encendida"};
        boolean[] values = new boolean[keys.length];
        for (int i = 0; i < keys.length; i++) values[i] = preferences.getBoolean(keys[i], false);
        new AlertDialog.Builder(this).setTitle("Lectura").setMultiChoiceItems(labels, values, (dialog, which, checked) ->
                preferences.edit().putBoolean(keys[which], checked).apply()).setPositiveButton("Listo", null).show();
    }
    private void cloudMenu() {
        new AlertDialog.Builder(this).setTitle("Tu nube").setItems(new String[]{"Google Drive · acceso directo", "MEGA · acceso directo", "Ajustes de sincronización", "WebDAV opcional", "Desconectar proveedor activo"}, (dialog, which) -> {
            if (which == 0) DriveAuth.signIn(this, this::driveConnected, error -> Ui.error(this, error));
            else if (which == 1) megaLogin();
            else if (which == 2) syncSettings();
            else if (which == 3) webDavMenu();
            else disconnect();
        }).show();
    }
    private void webDavMenu() {
        new AlertDialog.Builder(this).setTitle("WebDAV HTTPS").setItems(new String[]{"Explorar / descargar", "Configurar conexión"}, (dialog, which) -> {
            if (which == 0) {
                if (getSharedPreferences("cloud", 0).getString("endpoint", "").isEmpty()) configureCloud();
                else task("Conectando…", () -> Cloud.client(this), dav -> {
                    useProvider("webdav"); browse(dav, dav.root(), new ArrayDeque<>());
                });
            } else if (which == 1) configureCloud();
        }).show();
    }
    private void useProvider(String name) {
        getSystemService(JobScheduler.class).cancel(1);
        getSharedPreferences("cloud", 0).edit().putString("provider", name).remove("last_error").remove("last_sync").apply();
        updateStatus();
    }
    private void driveConnected(String token) {
        useProvider("drive");
        try { browseDrive(new DriveClient(token), "root", new ArrayDeque<>()); }
        catch (Exception e) { Ui.error(this, e); }
    }
    private void browseDrive(DriveClient drive, String folder, Deque<String> parents) {
        task("Leyendo Google Drive…", () -> drive.list(folder), entries -> {
            List<DriveClient.Entry> listed = new ArrayList<>();
            for (DriveClient.Entry entry : entries) if (entry.directory() || ComicFiles.supported(entry.name())) listed.add(entry);
            String[] labels = new String[listed.size()];
            for (int i = 0; i < listed.size(); i++) labels[i] = (listed.get(i).directory() ? "Carpeta · " : "") + listed.get(i).name();
            new AlertDialog.Builder(this).setTitle("Google Drive").setItems(labels, (dialog, index) -> {
                DriveClient.Entry entry = listed.get(index);
                if (entry.directory()) { parents.push(folder); browseDrive(drive, entry.id(), parents); }
                else confirmDownload(entry.name(), entry.size(), () -> task("Descargando de Drive…", () -> {
                    try (InputStream input = drive.download(entry.id())) { return Importer.fromStream(this, entry.name(), input); }
                }, book -> { refresh(); open(book); }));
            }).setNegativeButton("Cerrar", null).setNeutralButton(parents.isEmpty() ? "Raíz" : "Subir", (dialog, which) -> {
                if (!parents.isEmpty()) browseDrive(drive, parents.pop(), parents);
            }).show();
        });
    }
    private void megaLogin() {
        MegaClient mega = MegaClient.instance(this);
        if (!mega.available()) {
            new AlertDialog.Builder(this).setTitle("MEGA no está disponible")
                    .setMessage(mega.status()).setPositiveButton("Aceptar", null).show(); return;
        }
        if (mega.loggedIn()) { useProvider("mega"); chooseMegaFolder(mega, MegaClient.ROOT, "MEGA", new ArrayDeque<>()); return; }
        LinearLayout form = Ui.column(this);
        EditText email = Ui.input(this, "Correo MEGA"), password = Ui.input(this, "Contraseña"), otp = Ui.input(this, "Código 2FA (si está activado)");
        email.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        otp.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); form.addView(email); form.addView(password); form.addView(otp);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Iniciar sesión en MEGA").setView(form)
                .setNegativeButton("Cancelar", null).setPositiveButton("Entrar", null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String address = email.getText().toString().trim(), secret = password.getText().toString(), code = otp.getText().toString().trim();
                password.getText().clear(); otp.getText().clear();
                task("Iniciando sesión en MEGA…", () -> { mega.login(address, secret, code); return null; }, result -> {
                    dialog.dismiss(); useProvider("mega"); chooseMegaFolder(mega, MegaClient.ROOT, "MEGA", new ArrayDeque<>());
                });
            });
        }); dialog.show();
    }
    private void chooseMegaFolder(MegaClient mega, long folder, String name, Deque<MegaClient.Node> parents) {
        task("Leyendo carpetas MEGA…", () -> new FolderListing(mega.account(), mega.list(folder)), listing -> showMegaFolderPicker(mega, folder, name, parents, listing));
    }
    void showMegaFolderPicker(MegaClient mega, long folder, String name, Deque<MegaClient.Node> parents, FolderListing listing) {
            List<MegaClient.Node> directories = new ArrayList<>();
            for (MegaClient.Node entry : listing.nodes()) if (entry.directory() && !entry.name().equals(".comicreader-state-v1")) directories.add(entry);
            String[] labels = directories.stream().map(MegaClient.Node::name).toArray(String[]::new);
            AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("Elegir carpeta · " + name);
            if (directories.isEmpty()) dialog.setMessage("Sin subcarpetas. Pulsa Aceptar para usar esta carpeta como biblioteca.");
            else dialog.setItems(labels, (picker, index) -> {
                MegaClient.Node entry = directories.get(index); parents.push(new MegaClient.Node(folder, name, true, 0));
                chooseMegaFolder(mega, entry.handle(), entry.name(), parents);
            });
            dialog.setPositiveButton("Aceptar", (picker, which) -> task("Guardando biblioteca…", () -> {
                MegaLibrary.get(this).replace(listing.account(), folder, name, listing.nodes());
                if (!getSharedPreferences("library", 0).edit().putString("mega_account", listing.account()).putLong("mega_root", folder)
                        .putString("mega_name", name).putString("source", "mega").commit()) throw new IOException("No se pudo guardar la carpeta elegida");
                return null;
            }, ignored -> {
                megaAccount = listing.account(); megaRoot = megaFolder = folder; megaName = name; megaParents.clear();
                remoteMode = true; catalogStale = false; catalogError = ""; autoRefreshAttempted = true; showLibrary(0); refresh();
            })).setNegativeButton("Cancelar", null);
            if (!parents.isEmpty()) dialog.setNeutralButton("Subir", (picker, which) -> {
                MegaClient.Node parent = parents.pop(); chooseMegaFolder(mega, parent.handle(), parent.name(), parents);
            });
            dialog.show();
    }
    record FolderListing(String account, List<MegaClient.Node> nodes) { }
    private void confirmDownload(String name, long size, Runnable download) {
        new AlertDialog.Builder(this).setTitle(name).setMessage("Descargar para leer sin conexión"
                        + (size >= 0 ? " · " + String.format(Locale.getDefault(), "%.1f MiB", size / 1048576.0) : "") + ".")
                .setNegativeButton("Cancelar", null).setPositiveButton("Descargar", (dialog, which) -> download.run()).show();
    }
    private void disconnect() {
        String provider = getSharedPreferences("cloud", 0).getString("provider", "");
        Runnable clear = () -> {
            if (provider.equals("mega")) {
                getSharedPreferences("library", 0).edit().remove("mega_account").remove("mega_root").remove("mega_name").putString("source", "local").apply();
                megaAccount = ""; remoteMode = false; megaParents.clear(); showLibrary(0); refresh();
            }
            getSharedPreferences("cloud", 0).edit().clear().apply(); getSystemService(JobScheduler.class).cancel(1); updateStatus();
        };
        new AlertDialog.Builder(this).setMessage("Desconectar " + (provider.isEmpty() ? "la nube" : provider) + ". Tu biblioteca y progreso local se conservan.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Desconectar", (dialog, which) -> {
                    if (provider.equals("drive")) DriveAuth.disconnect(this, ignored -> clear.run(), error -> Ui.error(this, error));
                    else if (provider.equals("mega")) this.<Exception>task("Cerrando sesión…", () -> {
                        MegaClient mega = MegaClient.instance(this);
                        try { mega.logout(); return null; }
                        catch (Exception error) { if (!mega.loggedIn()) return error; throw error; }
                    }, error -> { clear.run(); if (error != null) Ui.error(this, error); });
                    else clear.run();
                }).show();
    }
    private void configureCloud() {
        LinearLayout form = Ui.column(this);
        form.addView(Ui.text(this, "Servidor WebDAV HTTPS. Usa una carpeta dedicada y las credenciales de ese servidor.", 14));
        EditText endpoint = Ui.input(this, "https://tu-servidor/comics/");
        endpoint.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        endpoint.setText(getSharedPreferences("cloud", 0).getString("endpoint", ""));
        EditText user = Ui.input(this, "Usuario WebDAV"), password = Ui.input(this, "Contraseña WebDAV");
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(endpoint); form.addView(user); form.addView(password);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Conexión HTTPS").setView(form)
                .setNegativeButton("Cancelar", null).setPositiveButton("Probar y guardar", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String address = endpoint.getText().toString().trim(), username = user.getText().toString(), secret = password.getText().toString();
            task("Verificando conexión…", () -> {
                String auth = username.isEmpty() && secret.isEmpty() ? "" : "Basic " + android.util.Base64.encodeToString(
                        (username + ":" + secret).getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
                WebDav dav = new WebDav(address, auth); dav.list(dav.root());
                Secrets.configure(this, dav.root().toString(), username, secret); return null;
            }, result -> { dialog.dismiss(); Ui.toast(this, "Conexión guardada"); updateStatus(); });
        })); dialog.show();
    }
    private void syncSettings() {
        SharedPreferences preferences = getSharedPreferences("cloud", 0);
        String[] keys = {"auto", "wifi"};
        boolean[] values = {preferences.getBoolean("auto", false), preferences.getBoolean("wifi", true)};
        new AlertDialog.Builder(this).setTitle("Progreso · " + getSharedPreferences("cloud", 0).getString("provider", "sin proveedor"))
                .setMultiChoiceItems(new String[]{"Automática al usar/cerrar el lector", "Automática solo en redes sin coste medido"}, values,
                        (dialog, which, checked) -> {
                            preferences.edit().putBoolean(keys[which], checked).apply();
                            getSystemService(JobScheduler.class).cancel(1); Cloud.schedule(this);
                        }).setPositiveButton("Listo", null).show();
    }
    private void sync() {
        if (!Cloud.configured(this)) { cloudMenu(); return; }
        task("Sincronizando progreso…", () -> Cloud.sync(this), count -> { Ui.toast(this, "Progreso sincronizado"); refresh(); });
    }
    private void browse(WebDav dav, URI folder, Deque<URI> parents) {
        task("Leyendo carpeta…", () -> dav.list(folder), entries -> {
            List<WebDav.Entry> listed = new ArrayList<>();
            for (WebDav.Entry entry : entries)
                if (!entry.name().equals(".comicreader-state-v1") && (entry.directory() || ComicFiles.supported(entry.name()))) listed.add(entry);
            listed.sort((a, b) -> a.directory() == b.directory() ? ComicFiles.naturalCompare(a.name(), b.name()) : a.directory() ? -1 : 1);
            String[] labels = new String[listed.size()];
            for (int i = 0; i < listed.size(); i++) labels[i] = (listed.get(i).directory() ? "Carpeta · " : "") + listed.get(i).name();
            new AlertDialog.Builder(this).setTitle(folder.getPath()).setItems(labels, (dialog, index) -> {
                WebDav.Entry entry = listed.get(index);
                if (entry.directory()) { parents.push(folder); browse(dav, entry.uri(), parents); }
                else new AlertDialog.Builder(this).setTitle(entry.name()).setMessage("Descargar una copia privada para leer sin conexión"
                        + (entry.size() >= 0 ? " (" + String.format(Locale.getDefault(), "%.1f MiB", entry.size() / 1048576.0) + ")" : "") + ".")
                        .setNegativeButton("Cancelar", null).setPositiveButton("Descargar", (d, w) -> task("Descargando…", () -> {
                            try (InputStream in = dav.download(entry.uri())) { return Importer.fromStream(this, entry.name(), in); }
                        }, book -> { refresh(); open(book); })).show();
            }).setNegativeButton("Cerrar", null).setNeutralButton(parents.isEmpty() ? "Raíz" : "Subir", (dialog, which) -> {
                if (!parents.isEmpty()) browse(dav, parents.pop(), parents);
            }).show();
        });
    }
    private void importUris(List<Uri> uris) {
        task("Importando…", () -> {
            Library.Book last = null;
            for (Uri uri : uris) last = Importer.fromUri(this, uri);
            return last;
        }, last -> { refresh(); if (last != null && uris.size() == 1) open(last); });
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (DriveAuth.onActivityResult(this, request, result, data, this::driveConnected, error -> Ui.error(this, error))) return;
        if (result != RESULT_OK || data == null) return;
        if (request == OPEN) {
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                if (data.getClipData().getItemCount() > 100) { Ui.error(this, new IOException("Importa como máximo 100 cómics cada vez")); return; }
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) uris.add(data.getData());
            if (!uris.isEmpty()) importUris(uris);
        } else if (data.getData() != null && request == EXPORT) task("Exportando…", () -> {
            byte[] bytes = Progress.encode(Library.get(this).states());
            try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
                if (out == null) throw new IOException("No se puede crear el documento"); out.write(bytes);
            }
            return null;
        }, ignored -> Ui.toast(this, "Progreso exportado"));
        else if (data.getData() != null && request == RESTORE) task("Importando progreso…", () -> {
            try (InputStream in = getContentResolver().openInputStream(data.getData())) {
                if (in == null) throw new IOException("No se puede abrir el documento");
                Library.get(this).merge(Progress.decode(ComicFiles.read(in, Progress.MAX_BYTES)));
            }
            return null;
        }, ignored -> { refresh(); Cloud.schedule(this); });
    }
    private interface Work<T> { T run() throws Exception; }
    private <T> void task(String label, Work<T> work, java.util.function.Consumer<T> done) {
        if (busy) { Ui.toast(this, "Espera a que termine la operación actual"); return; }
        busy = true; status.setVisibility(View.VISIBLE); status.setText(label); for (Button action : actions) action.setEnabled(false);
        worker.execute(() -> {
            try {
                T result = work.run();
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    busy = false; for (Button action : actions) action.setEnabled(true); updateStatus(); done.accept(result);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    busy = false; for (Button action : actions) action.setEnabled(true);
                    updateStatus(); Ui.error(this, e); refresh();
                });
            }
        });
    }
    @android.annotation.SuppressLint("GestureBackNavigation") // API 26–32; newer Android uses the dispatcher above.
    @Override public void onBackPressed() { navigateBack(); }
    private void navigateBack() {
        if (search.getVisibility() == View.VISIBLE) { search.getText().clear(); search.setVisibility(View.GONE); }
        else if (!home && !megaParents.isEmpty()) upMega();
        else if (!home) showHome(); else finish();
    }
    private void upMega() {
        if (busy || megaParents.isEmpty()) return;
        MegaClient.Node parent = megaParents.pop(); megaFolder = parent.handle(); megaName = parent.name();
        catalogStale = true; catalogError = ""; autoRefreshAttempted = false; remoteBooks.clear(); showLibrary(selection); refresh();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("home", home); out.putInt("selection", selection); out.putLong("mega_folder", megaFolder); out.putString("mega_name", megaName);
        out.putBoolean("mega_attempted", autoRefreshAttempted); out.putBoolean("mega_stale", catalogStale); out.putString("mega_error", catalogError);
        out.putLongArray("mega_parents", megaParents.stream().mapToLong(MegaClient.Node::handle).toArray());
        out.putStringArrayList("mega_parent_names", new ArrayList<>(megaParents.stream().map(MegaClient.Node::name).toList()));
        super.onSaveInstanceState(out);
    }
    @Override protected void onDestroy() { updater.close(); covers.close(); worker.shutdownNow(); super.onDestroy(); }
    @Override protected void onPause() {
        resumed = false;
        updater.onPause();
        covers.cancelPending();
        if (!busy && MegaClient.instance(this).loggedIn()) worker.execute(() -> {
            try { MegaClient.instance(this).suspend(); }
            catch (Exception e) { android.util.Log.w("ComicReader", "MEGA no pudo desconectarse al pausar", e); }
        });
        super.onPause();
    }
}
