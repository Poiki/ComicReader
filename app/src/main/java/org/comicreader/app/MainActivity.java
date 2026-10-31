package org.comicreader.app;

import android.app.*;
import android.app.job.JobScheduler;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.text.*;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
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
    private final List<View> actions = new ArrayList<>();
    private TextView status;
    private EditText search;
    private int selection;
    private boolean home = true, grid, recent, downloadsOnly;
    private FrameLayout content;
    private LinearLayout libraryScreen, bottomBar, topBar, sourceCards, filterChips, settingsBody;
    private TextView sectionTitle;
    private GridView shelf;
    private BaseAdapter adapter;
    private CoverCache covers;
    private ImageButton add;
    private boolean busy, resumed;
    private Updater updater;
    private Dialog settingsDialog;
    private String megaAccount = "", megaName = "MEGA", catalogError = "";
    private long megaRoot = MegaClient.ROOT, megaFolder = MegaClient.ROOT, catalogUpdated;
    private boolean remoteMode, catalogStale = true, autoRefreshAttempted;
    private int refreshGeneration;
    private final Deque<MegaClient.Node> megaParents = new ArrayDeque<>();
    private String[] filters;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.attach(base));
    }

    @Override
    public void onCreate(Bundle saved) {
        Ui.applyTheme(this);
        super.onCreate(saved);
        filters = getResources().getStringArray(R.array.library_filters);
        updater = new Updater(this);
        Ui.bars(this);
        if (android.os.Build.VERSION.SDK_INT >= 33)
            getOnBackInvokedDispatcher()
                    .registerOnBackInvokedCallback(
                            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                            this::navigateBack);
        LinearLayout root = Ui.column(this);
        root.setPadding(0, 0, 0, 0);
        Ui.insets(root);
        setContentView(root);
        topBar = libraryHeader();
        root.addView(topBar, new LinearLayout.LayoutParams(-1, -2));
        covers = new CoverCache(this);
        SharedPreferences libraryPreferences = getSharedPreferences("library", 0);
        grid = libraryPreferences.getBoolean("grid", true);
        megaAccount = libraryPreferences.getString("mega_account", "");
        megaRoot = megaFolder = libraryPreferences.getLong("mega_root", MegaClient.ROOT);
        megaName = libraryPreferences.getString("mega_name", "MEGA");
        remoteMode =
                !megaAccount.isEmpty()
                        && libraryPreferences.getString("source", "local").equals("mega");
        if (saved != null && remoteMode) {
            autoRefreshAttempted = saved.getBoolean("mega_attempted", false);
            catalogStale = saved.getBoolean("mega_stale", true);
            catalogError = saved.getString("mega_error", "");
            megaFolder = saved.getLong("mega_folder", megaRoot);
            megaName = saved.getString("mega_name", megaName);
            long[] handles = saved.getLongArray("mega_parents");
            ArrayList<String> names = saved.getStringArrayList("mega_parent_names");
            if (handles != null && names != null && handles.length == names.size())
                for (int i = 0; i < handles.length; i++)
                    megaParents.addLast(new MegaClient.Node(handles[i], names.get(i), true, 0));
        }
        recent = saved != null && saved.getBoolean("recent", false);
        downloadsOnly = saved != null && saved.getBoolean("downloads", false);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        libraryScreen = new LinearLayout(this);
        libraryScreen.setOrientation(LinearLayout.VERTICAL);
        sourceCards = new LinearLayout(this);
        sourceCards.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        libraryScreen.addView(sourceCards);
        sectionTitle = section(getString(R.string.library_title));
        sectionTitle.setMaxLines(2);
        sectionTitle.setEllipsize(TextUtils.TruncateAt.END);
        sectionTitle.setOnClickListener(
                view -> {
                    if (!megaParents.isEmpty()) upMega();
                    else librarySources();
                });
        sectionTitle.setTooltipText(getString(R.string.ui_go_up_or_switch_libraries));
        libraryScreen.addView(sectionTitle);
        search = Ui.input(this, getString(R.string.ui_search_your_library));
        search.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        search.setContentDescription(getString(R.string.ui_search_your_library));
        search.setVisibility(View.GONE);
        libraryScreen.addView(search, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        HorizontalScrollView chips = new HorizontalScrollView(this);
        chips.setHorizontalScrollBarEnabled(false);
        filterChips = new LinearLayout(this);
        filterChips.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        chips.addView(filterChips);
        libraryScreen.addView(chips, new LinearLayout.LayoutParams(-1, Ui.dp(this, 54)));
        shelf = new GridView(this);
        shelf.setClipToPadding(false);
        shelf.setVerticalSpacing(Ui.dp(this, 8));
        shelf.setHorizontalSpacing(Ui.dp(this, 8));
        shelf.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        TextView empty = Ui.text(this, getString(R.string.library_empty_section), 16);
        empty.setTextColor(Ui.secondary(this));
        empty.setGravity(Gravity.CENTER);
        FrameLayout libraryBody = new FrameLayout(this);
        libraryBody.addView(shelf, new FrameLayout.LayoutParams(-1, -1));
        libraryBody.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        shelf.setEmptyView(empty);
        libraryScreen.addView(libraryBody, new LinearLayout.LayoutParams(-1, 0, 1));
        adapter =
                new BaseAdapter() {
                    public int getCount() {
                        return visible.size();
                    }

                    public ShelfItem getItem(int position) {
                        return visible.get(position);
                    }

                    public long getItemId(int position) {
                        return position;
                    }

                    public int getViewTypeCount() {
                        return 2;
                    }

                    public int getItemViewType(int position) {
                        return grid ? 1 : 0;
                    }

                    public View getView(int position, View recycled, ViewGroup parent) {
                        BookTile tile =
                                recycled instanceof BookTile
                                        ? (BookTile) recycled
                                        : new BookTile(grid);
                        ShelfItem item = getItem(position);
                        tile.options.setVisibility(
                                item.directory() ? View.INVISIBLE : View.VISIBLE);
                        tile.options.setContentDescription(
                                getString(R.string.item_options, item.title()));
                        tile.options.setOnClickListener(view -> itemMenu(item));
                        Progress state = state(item);
                        tile.title.setText(item.title());
                        tile.detail.setText(
                                item.directory()
                                        ? getString(R.string.ui_folder)
                                        : (favorite(item) ? "♥  " : "")
                                                + (completed(item)
                                                        ? getString(R.string.ui_read)
                                                        : state == null || state.page == 0
                                                                ? getString(R.string.ui_unread)
                                                                : (state.page + 1)
                                                                        + " / "
                                                                        + state.pages)
                                                + (item.book() == null
                                                        ? getString(R.string.ui_mega)
                                                        : getString(R.string.ui_offline)));
                        tile.setContentDescription(item.title() + ". " + tile.detail.getText());
                        if (item.book() != null) covers.bind(tile.cover, item.book());
                        else if (!item.directory()) {
                            covers.bind(tile.cover, megaAccount, item.remote().node());
                        } else {
                            tile.cover.bookId = null;
                            android.graphics.drawable.Drawable symbol =
                                    getDrawable(
                                                    item.directory()
                                                            ? R.drawable.icon_folder
                                                            : R.drawable.icon_cloud)
                                            .mutate();
                            symbol.setTint(Ui.foreground(MainActivity.this));
                            tile.cover.setImageDrawable(symbol);
                        }
                        return tile;
                    }
                };
        shelf.setAdapter(adapter);
        updateDisplayMode();
        shelf.setOnItemClickListener(
                (parent, view, position, id) -> openItem(visible.get(position)));
        shelf.setOnItemLongClickListener(
                (parent, view, position, id) -> {
                    itemMenu(visible.get(position));
                    return true;
                });
        search.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(
                            CharSequence s, int start, int count, int after) {}

                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        applyFilter();
                    }

                    public void afterTextChanged(Editable s) {}
                });
        status = Ui.text(this, "", 12);
        status.setTextColor(Ui.secondary(this));
        status.setMaxLines(2);
        status.setOnClickListener(view -> refreshMega());
        root.addView(status);
        View divider = new View(this);
        divider.setBackgroundColor(Ui.dark(this) ? 0xff171b1e : 0xffdddddd);
        root.addView(divider, new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
        bottomBar = new LinearLayout(this);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setMinimumHeight(Ui.dp(this, 68));
        root.addView(bottomBar, new LinearLayout.LayoutParams(-1, -2));
        CoverJob.schedule(this, true);
        if (saved != null
                ? saved.getBoolean("home", false)
                : getSharedPreferences("appearance", 0)
                        .getString("startup", "library")
                        .equals("home")) showHome();
        else showLibrary(saved == null ? 0 : saved.getInt("selection", 0));
        if (saved == null
                && Intent.ACTION_VIEW.equals(getIntent().getAction())
                && getIntent().getData() != null) importUris(List.of(getIntent().getData()));
        else if (saved == null
                && getSharedPreferences("appearance", 0)
                        .getString("startup", "library")
                        .equals("reader")) resumeLastBook();
    }

    private TextView section(String label) {
        TextView view = Ui.text(this, label, 14);
        view.setTextColor(Ui.secondary(this));
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4));
        view.setBackgroundColor(Ui.header(this));
        return view;
    }

    private void homeRow(
            LinearLayout parent, int icon, String title, String subtitle, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(this, subtitle == null ? 52 : 56));
        int padding = Ui.dp(this, 12);
        row.setPadding(padding, 0, padding, 0);
        ImageView symbol = new ImageView(this);
        symbol.setImageResource(icon);
        symbol.setColorFilter(Ui.foreground(this));
        symbol.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(symbol, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), 0, Ui.dp(this, 6));
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        TextView name = Ui.text(this, title, 18);
        name.setPadding(0, 0, 0, 0);
        labels.addView(name);
        if (subtitle != null) {
            TextView detail = Ui.text(this, subtitle, 14);
            detail.setTextColor(Ui.secondary(this));
            detail.setPadding(0, Ui.dp(this, 2), 0, 0);
            labels.addView(detail);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(title + (subtitle == null ? "" : ". " + subtitle));
        row.setOnClickListener(view -> action.run());
        parent.addView(row);
    }

    private void showHome() {
        search.setVisibility(View.GONE);
        search.getText().clear();
        getSystemService(InputMethodManager.class)
                .hideSoftInputFromWindow(search.getWindowToken(), 0);
        home = true;
        content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.VERTICAL);
        navigation.setPadding(0, 0, 0, Ui.dp(this, 88));
        scroll.addView(navigation);
        content.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        navigation.addView(section(getString(R.string.ui_device_storage)));
        homeRow(
                navigation,
                R.drawable.icon_phone,
                getString(R.string.ui_import_files),
                getString(R.string.ui_cbz_zip_cbr_rar_and_pdf),
                this::pick);
        navigation.addView(section(getString(R.string.library_title)));
        homeRow(
                navigation,
                R.drawable.icon_history,
                getString(R.string.ui_reading),
                null,
                () -> showLibrary(1));
        homeRow(
                navigation,
                R.drawable.icon_book,
                getString(R.string.ui_open_library),
                remoteMode ? megaName : getString(R.string.ui_local_library),
                () -> showLibrary(0));
        homeRow(
                navigation,
                R.drawable.icon_eye,
                getString(R.string.ui_finished),
                null,
                () -> showLibrary(3));
        homeRow(
                navigation,
                R.drawable.icon_unread,
                getString(R.string.ui_unread),
                null,
                () -> showLibrary(2));
        homeRow(
                navigation,
                R.drawable.icon_favorite,
                getString(R.string.ui_favorites),
                null,
                () -> showLibrary(4));
        homeRow(
                navigation,
                R.drawable.icon_download,
                getString(R.string.ui_downloads),
                getString(R.string.ui_local_copies_for_offline_reading),
                () -> setSource(false));
        navigation.addView(section(getString(R.string.ui_cloud)));
        homeRow(
                navigation,
                R.drawable.icon_cloud,
                "MEGA",
                getString(R.string.ui_direct_access),
                this::megaLogin);
        homeRow(
                navigation,
                R.drawable.icon_cloud,
                "Google Drive",
                getString(R.string.ui_direct_access),
                () -> DriveAuth.signIn(this, this::driveConnected, error -> Ui.error(this, error)));
        addButton();
        navigationBar();
        updateStatus();
    }

    private void addButton() {
        add =
                Ui.icon(
                        this,
                        R.drawable.icon_plus,
                        getString(R.string.ui_add_files_or_cloud_account),
                        this::addMenu);
        add.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        add.setBackground(Ui.selectable(this, Ui.accent(this), 28));
        add.setImageTintList(
                android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE));
        add.setElevation(Ui.dp(this, 4));
        FrameLayout.LayoutParams floating =
                new FrameLayout.LayoutParams(
                        Ui.dp(this, 56), Ui.dp(this, 56), Gravity.BOTTOM | Gravity.END);
        floating.setMargins(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        content.addView(add, floating);
    }

    private void showLibrary(int filter) {
        home = false;
        selection = filter;
        content.removeAllViews();
        content.addView(libraryScreen, new FrameLayout.LayoutParams(-1, -1));
        addButton();
        sectionTitle.setText(
                (remoteMode
                                ? getString(R.string.mega_status_prefix) + megaName
                                : getString(R.string.library_title))
                        + (recent
                                ? " · " + getString(R.string.nav_recent)
                                : downloadsOnly
                                        ? " · " + getString(R.string.ui_downloads)
                                        : selection == 0 ? "" : " · " + filters[selection]));
        sectionTitle.setContentDescription(
                getString(R.string.ui_current_folder)
                        + (remoteMode ? megaName : getString(R.string.ui_local_library)));
        sectionTitle.setMinimumHeight(Ui.dp(this, 48));
        sectionTitle.setVisibility(
                !recent && !downloadsOnly && (remoteMode || selection != 0)
                        ? View.VISIBLE
                        : View.GONE);
        if (!megaParents.isEmpty() && !downloadsOnly && !recent) {
            android.graphics.drawable.Drawable back = getDrawable(R.drawable.icon_back).mutate();
            back.setTint(Ui.foreground(this));
            back.setBounds(0, 0, Ui.dp(this, 20), Ui.dp(this, 20));
            sectionTitle.setCompoundDrawables(back, null, null, null);
            sectionTitle.setCompoundDrawablePadding(Ui.dp(this, 8));
            sectionTitle.setContentDescription(
                    getString(R.string.ui_up_one_folder) + ". " + megaName);
        } else sectionTitle.setCompoundDrawables(null, null, null, null);
        navigationBar();
        applyFilter();
        updateStatus();
    }

    private void setSource(boolean mega) {
        if (busy) return;
        covers.cancelPending();
        if (mega && megaAccount.isEmpty()) {
            megaLogin();
            return;
        }
        recent = downloadsOnly = false;
        remoteMode = mega;
        megaFolder = megaRoot;
        megaParents.clear();
        megaName = getSharedPreferences("library", 0).getString("mega_name", "MEGA");
        getSharedPreferences("library", 0)
                .edit()
                .putString("source", mega ? "mega" : "local")
                .apply();
        catalogError = "";
        catalogStale = true;
        autoRefreshAttempted = false;
        remoteBooks.clear();
        showLibrary(0);
        refresh();
    }

    private void librarySources() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.library_title))
                .setItems(
                        new String[] {
                            getString(R.string.ui_local_library),
                            getString(R.string.ui_saved_mega_library),
                            getString(R.string.ui_choose_another_mega_folder),
                            getString(R.string.ui_refresh_mega_folder)
                        },
                        (dialog, which) -> {
                            if (which < 2) setSource(which == 1);
                            else if (which == 2) megaLogin();
                            else if (remoteMode && !downloadsOnly) refreshMega();
                            else Ui.toast(this, getString(R.string.ui_open_the_mega_library_first));
                        })
                .setNegativeButton(getString(R.string.action_close), null)
                .show();
    }

    private LinearLayout libraryHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(Ui.dp(this, 56));
        header.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 4), 0);
        TextView brand = Ui.text(this, "ComicReader", 24);
        android.text.SpannableString name = new android.text.SpannableString("ComicReader");
        name.setSpan(new android.text.style.ForegroundColorSpan(Ui.accentText(this)), 5, 11, 0);
        brand.setText(name);
        brand.setTypeface(null, android.graphics.Typeface.BOLD);
        brand.setMaxLines(1);
        brand.setEllipsize(TextUtils.TruncateAt.END);
        brand.setPadding(0, 0, 0, 0);
        brand.setContentDescription(getString(R.string.ui_menu));
        brand.setOnClickListener(view -> showHome());
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        for (ImageButton button :
                new ImageButton[] {
                    Ui.icon(
                            this,
                            R.drawable.icon_search,
                            getString(R.string.ui_search),
                            this::toggleSearch),
                    Ui.icon(
                            this,
                            R.drawable.icon_filter,
                            getString(R.string.ui_filter_library),
                            this::filterMenu),
                    Ui.icon(
                            this,
                            R.drawable.icon_settings,
                            getString(R.string.settings_title),
                            this::more)
                })
            header.addView(button, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        return header;
    }

    private void toggleSearch() {
        if (home) showLibrary(0);
        boolean showing = search.getVisibility() != View.VISIBLE;
        search.setVisibility(showing ? View.VISIBLE : View.GONE);
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (showing) {
            search.requestFocus();
            keyboard.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
        } else {
            search.getText().clear();
            keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
        }
    }

    private void filterMenu() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.ui_show)
                .setSingleChoiceItems(
                        filters,
                        selection,
                        (dialog, which) -> {
                            dialog.dismiss();
                            recent = false;
                            showLibrary(which);
                        })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void navigationBar() {
        actions.clear();
        for (int i = 0; i < topBar.getChildCount(); i++) actions.add(topBar.getChildAt(i));
        bottomBar.removeAllViews();
        navDestination(
                R.drawable.icon_book,
                R.string.library_title,
                !home && !recent && !downloadsOnly && selection != 4,
                () -> {
                    recent = downloadsOnly = false;
                    showLibrary(0);
                    refresh();
                });
        navDestination(
                R.drawable.icon_history,
                R.string.nav_recent,
                !home && recent,
                () -> {
                    recent = true;
                    downloadsOnly = false;
                    showLibrary(0);
                });
        navDestination(
                R.drawable.icon_favorite,
                R.string.ui_favorites,
                !home && selection == 4 && !downloadsOnly && !recent,
                () -> {
                    recent = downloadsOnly = false;
                    showLibrary(4);
                });
        navDestination(
                R.drawable.icon_download,
                R.string.ui_downloads,
                !home && downloadsOnly,
                () -> {
                    downloadsOnly = true;
                    recent = false;
                    showLibrary(0);
                });
        if (add != null) actions.add(add);
        for (View action : actions) action.setEnabled(!busy);
        updateSources();
        sourceCards.setVisibility(recent ? View.GONE : View.VISIBLE);
        ((View) filterChips.getParent()).setVisibility(recent ? View.GONE : View.VISIBLE);
        filterChips.removeAllViews();
        for (int i = 0; i < 5; i++) {
            int value = i;
            Button chip =
                    Ui.button(
                            this,
                            i == 0 ? getString(R.string.chip_all) : filters[i],
                            () -> {
                                recent = false;
                                showLibrary(value);
                            });
            chip.setTextSize(12);
            chip.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
            chip.setMinWidth(0);
            chip.setMinimumWidth(0);
            chip.setSelected(selection == i && !recent);
            chip.setTextColor(
                    chip.isSelected() ? android.graphics.Color.BLACK : Ui.foreground(this));
            chip.setBackground(
                    new android.graphics.drawable.InsetDrawable(
                            Ui.selectable(
                                    this,
                                    chip.isSelected() ? Ui.accent(this) : Ui.surface(this),
                                    16),
                            0,
                            Ui.dp(this, 8),
                            0,
                            Ui.dp(this, 8)));
            chip.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, Ui.dp(this, 48));
            params.setMargins(0, Ui.dp(this, 3), Ui.dp(this, 6), Ui.dp(this, 3));
            filterChips.addView(chip, params);
        }
    }

    private void navDestination(int icon, int label, boolean selected, Runnable action) {
        LinearLayout item = Ui.destination(this, icon, getString(label), selected, false, action);
        bottomBar.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        actions.add(item);
    }

    private void updateSources() {
        sourceCards.removeAllViews();
        sourceCard(
                R.drawable.icon_folder_filled,
                getString(R.string.source_local),
                R.string.source_device,
                0xff348cf4,
                !remoteMode || downloadsOnly || recent,
                () -> setSource(false));
        sourceCard(
                R.drawable.icon_cloud_filled,
                "MEGA",
                R.string.source_cloud,
                0xfff04451,
                remoteMode && !downloadsOnly && !recent,
                () -> setSource(true));
        sourceCard(
                R.drawable.icon_cloud_filled,
                "Drive",
                R.string.source_cloud,
                0xff16b8a6,
                false,
                () -> DriveAuth.signIn(this, this::driveConnected, error -> Ui.error(this, error)));
        sourceCard(
                R.drawable.icon_server,
                "WebDAV",
                R.string.source_server,
                0xffad58e9,
                false,
                this::webDavMenu);
    }

    private void sourceCard(
            int icon, String label, int subtitle, int color, boolean selected, Runnable action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.TOP);
        card.setMinimumHeight(Ui.dp(this, 84));
        card.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
        card.setBackground(Ui.selectable(this, Ui.surface(this), 10));
        android.graphics.drawable.GradientDrawable shape =
                (android.graphics.drawable.GradientDrawable)
                        ((android.graphics.drawable.RippleDrawable) card.getBackground())
                                .getDrawable(0);
        shape.setStroke(
                Ui.dp(this, selected ? 2 : 1),
                selected ? Ui.accent(this) : Ui.dark(this) ? 0xff253038 : 0xffd8dfe5);
        ImageView symbol = new ImageView(this);
        symbol.setImageResource(icon);
        symbol.setColorFilter(color);
        card.addView(symbol, new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32)));
        TextView name = Ui.text(this, label, 14);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setTextColor(selected ? Ui.accentText(this) : Ui.foreground(this));
        name.setPadding(0, Ui.dp(this, 4), 0, 0);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(name);
        TextView detail = Ui.text(this, getString(subtitle), 12);
        detail.setTextColor(Ui.secondary(this));
        detail.setPadding(0, Ui.dp(this, 2), 0, 0);
        detail.setMaxLines(2);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(detail);
        card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        for (int i = 0; i < card.getChildCount(); i++)
            card.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.setContentDescription(label + ". " + getString(subtitle));
        card.setSelected(selected);
        card.setFocusable(true);
        card.setOnClickListener(
                view -> {
                    if (!busy) action.run();
                });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        params.setMarginEnd(Ui.dp(this, sourceCards.getChildCount() == 3 ? 0 : 8));
        sourceCards.addView(card, params);
    }

    private void updateDisplayMode() {
        shelf.setNumColumns(grid ? GridView.AUTO_FIT : 1);
        shelf.setColumnWidth(
                Ui.dp(this, getResources().getConfiguration().screenWidthDp < 600 ? 108 : 152));
        int pad = grid ? Ui.dp(this, 8) : 0;
        shelf.setPadding(pad, pad, pad, Ui.dp(this, 88));
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void addMenu() {
        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.VERTICAL);
        choices.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        Dialog dialog = new Dialog(this);
        dialog.setContentView(choices);
        homeRow(
                choices,
                R.drawable.icon_phone,
                getString(R.string.ui_local_file),
                null,
                () -> {
                    dialog.dismiss();
                    pick();
                });
        homeRow(
                choices,
                R.drawable.icon_cloud,
                "MEGA",
                null,
                () -> {
                    dialog.dismiss();
                    megaLogin();
                });
        homeRow(
                choices,
                R.drawable.icon_cloud,
                "Google Drive",
                null,
                () -> {
                    dialog.dismiss();
                    DriveAuth.signIn(this, this::driveConnected, error -> Ui.error(this, error));
                });
        homeRow(
                choices,
                R.drawable.icon_cloud,
                "WebDAV HTTPS",
                null,
                () -> {
                    dialog.dismiss();
                    webDavMenu();
                });
        dialog.getWindow()
                .setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(Ui.background(this)));
        dialog.getWindow().setGravity(Gravity.BOTTOM);
        dialog.getWindow().setLayout(-1, -2);
        dialog.show();
        dialog.getWindow().setLayout(-1, -2);
    }

    private final class BookTile extends LinearLayout {
        final CoverCache.CoverView cover;
        final TextView title, detail;
        final ImageButton options;
        private final boolean gridMode;
        private final android.graphics.Rect overflowBounds = new android.graphics.Rect(),
                targetBounds = new android.graphics.Rect();

        BookTile(boolean gridMode) {
            super(MainActivity.this);
            this.gridMode = gridMode;
            setOrientation(gridMode ? VERTICAL : HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            int pad = Ui.dp(MainActivity.this, gridMode ? 2 : 12);
            setPadding(pad, Ui.dp(MainActivity.this, 4), pad, Ui.dp(MainActivity.this, 4));
            cover = new CoverCache.CoverView(MainActivity.this);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackground(
                    Ui.background(Ui.surface(MainActivity.this), Ui.dp(MainActivity.this, 10)));
            cover.setClipToOutline(true);
            cover.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(
                    cover,
                    new LinearLayout.LayoutParams(
                            gridMode ? -1 : Ui.dp(MainActivity.this, 52),
                            Ui.dp(MainActivity.this, gridMode ? 128 : 72)));
            FrameLayout information = new FrameLayout(MainActivity.this);
            LinearLayout labels = new LinearLayout(MainActivity.this);
            labels.setOrientation(VERTICAL);
            labels.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            title = Ui.text(MainActivity.this, "", 14);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            detail = Ui.text(MainActivity.this, "", 12);
            detail.setTextColor(Ui.secondary(MainActivity.this));
            detail.setMaxLines(1);
            detail.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(
                    Ui.dp(MainActivity.this, gridMode ? 0 : 12), Ui.dp(MainActivity.this, 4), 0, 0);
            detail.setPadding(
                    Ui.dp(MainActivity.this, gridMode ? 0 : 12), Ui.dp(MainActivity.this, 2), 0, 0);
            labels.addView(title);
            labels.addView(detail);
            labels.setPadding(0, 0, Ui.dp(MainActivity.this, 32), 0);
            information.addView(labels, new FrameLayout.LayoutParams(-1, -2));
            options = Ui.icon(MainActivity.this, R.drawable.icon_more, "", () -> {});
            options.setFocusable(false);
            options.setMinimumWidth(0);
            options.setMinimumHeight(0);
            options.setPadding(
                    Ui.dp(MainActivity.this, 4),
                    Ui.dp(MainActivity.this, 4),
                    Ui.dp(MainActivity.this, 4),
                    Ui.dp(MainActivity.this, 4));
            information.addView(
                    options,
                    new FrameLayout.LayoutParams(
                            Ui.dp(MainActivity.this, 28),
                            Ui.dp(MainActivity.this, 28),
                            Gravity.TOP | Gravity.END));
            addView(
                    information,
                    new LinearLayout.LayoutParams(gridMode ? -1 : 0, -2, gridMode ? 0 : 1));
        }

        @Override
        @android.annotation.SuppressLint(
                "DrawAllocation") // Rebuild only when the touch bounds change.
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            if (options.getVisibility() != View.VISIBLE) {
                setTouchDelegate(null);
                return;
            }
            targetBounds.set(0, 0, options.getWidth(), options.getHeight());
            offsetDescendantRectToMyCoords(options, targetBounds);
            int size = Ui.dp(MainActivity.this, 48);
            int x = Math.max(0, Math.min(getWidth() - size, targetBounds.centerX() - size / 2));
            int y = Math.max(0, Math.min(getHeight() - size, targetBounds.centerY() - size / 2));
            targetBounds.set(x, y, x + size, y + size);
            if (getTouchDelegate() == null || !overflowBounds.equals(targetBounds)) {
                overflowBounds.set(targetBounds);
                setTouchDelegate(new TouchDelegate(overflowBounds, options));
            }
        }

        @Override
        protected void onMeasure(int width, int height) {
            if (gridMode && MeasureSpec.getSize(width) > 0)
                cover.getLayoutParams().height =
                        (int)
                                ((MeasureSpec.getSize(width) - getPaddingLeft() - getPaddingRight())
                                        * 1.1f);
            super.onMeasure(width, height);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!resumed) MegaClient.instance(this).browsing(true);
        resumed = true;
        covers.setPaused(busy);
        covers.observe(() -> adapter.notifyDataSetChanged());
        CoverJob.schedule(this, true);
        if (!busy) refresh();
        Cloud.schedule(this);
        updater.onResume();
    }

    private void refresh() {
        int ticket = ++refreshGeneration;
        String account = megaAccount;
        long folder = megaFolder;
        boolean remote = remoteMode;
        worker.execute(
                () -> {
                    try {
                        Library library = Library.get(this);
                        List<Library.Book> loaded = library.books();
                        Map<String, Progress> progress = new HashMap<>();
                        for (Progress state : library.states()) progress.put(state.id, state);
                        MegaLibrary.Snapshot snapshot =
                                remote ? MegaLibrary.get(this).snapshot(account, folder) : null;
                        runOnUiThread(
                                () -> {
                                    if (isDestroyed() || ticket != refreshGeneration) return;
                                    books.clear();
                                    books.addAll(loaded);
                                    localBooks.clear();
                                    for (Library.Book book : loaded)
                                        localBooks.put(book.id(), book);
                                    states.clear();
                                    states.putAll(progress);
                                    remoteBooks.clear();
                                    catalogUpdated = 0;
                                    if (snapshot != null) {
                                        remoteBooks.addAll(snapshot.entries());
                                        catalogUpdated = snapshot.updated();
                                    }
                                    applyFilter();
                                    updateStatus();
                                    if (resumed
                                            && remoteMode
                                            && !downloadsOnly
                                            && !recent
                                            && !home
                                            && !busy
                                            && !autoRefreshAttempted
                                            && MegaClient.instance(this).loggedIn())
                                        refreshMega(false);
                                });
                    } catch (Exception e) {
                        runOnUiThread(() -> Ui.error(this, e));
                    }
                });
    }

    private void applyFilter() {
        if (adapter == null) return;
        String query = search.getText().toString().toLowerCase(Locale.ROOT);
        visible.clear();
        List<ShelfItem> items = new ArrayList<>();
        if (remoteMode && !downloadsOnly && !recent)
            for (MegaLibrary.Entry entry : remoteBooks)
                items.add(new ShelfItem(localBooks.get(entry.bookId()), entry));
        else for (Library.Book book : books) items.add(new ShelfItem(book, null));
        String lastBook = getSharedPreferences("reader", 0).getString("last_book", "");
        for (ShelfItem item : items) {
            Progress state = state(item);
            if (recent
                    && (item.directory()
                            || state == null
                            || (state.page == 0
                                    && !state.completed
                                    && !item.bookId().equals(lastBook)))) continue;
            if (!item.title().toLowerCase(Locale.ROOT).contains(query)) continue;
            if (!item.directory()) {
                boolean reading = state != null && state.page > 0 && !completed(item);
                if (selection == 1 && !reading
                        || selection == 2 && (reading || completed(item))
                        || selection == 3 && !completed(item)
                        || selection == 4 && !favorite(item)) continue;
            }
            visible.add(item);
        }
        if (recent) visible.sort((a, b) -> Long.compare(state(b).revision, state(a).revision));
        else if (selection == 5)
            visible.sort(
                    (a, b) ->
                            a.directory() != b.directory()
                                    ? (a.directory() ? -1 : 1)
                                    : ComicFiles.naturalCompare(a.title(), b.title()));
        adapter.notifyDataSetChanged();
    }

    private Progress state(ShelfItem item) {
        String id = item.bookId();
        return id == null ? null : states.get(id);
    }

    private boolean completed(ShelfItem item) {
        Progress state = state(item);
        return state != null
                ? state.completed
                : item.remote() != null && Boolean.TRUE.equals(item.remote().completed());
    }

    private boolean favorite(ShelfItem item) {
        Progress state = state(item);
        return state != null
                ? state.favorite
                : item.remote() != null && Boolean.TRUE.equals(item.remote().favorite());
    }

    private record ShelfItem(Library.Book book, MegaLibrary.Entry remote) {
        String bookId() {
            return remote != null ? remote.bookId() : book.id();
        }

        String title() {
            return remote != null ? remote.node().name() : book.title();
        }

        boolean directory() {
            return remote != null && remote.node().directory();
        }
    }

    private void updateStatus() {
        if (busy) return;
        boolean retry = !home && remoteMode && !downloadsOnly && !recent && !catalogError.isEmpty();
        status.setClickable(retry);
        status.setFocusable(retry);
        status.setMaxLines(retry ? 6 : 2);
        status.setMinimumHeight(retry ? Ui.dp(this, 48) : 0);
        status.setTooltipText(retry ? getString(R.string.ui_refresh_library) : null);
        SharedPreferences preferences = getSharedPreferences("cloud", 0);
        String error = preferences.getString("last_error", "");
        long synced = preferences.getLong("last_sync", 0);
        status.setVisibility(
                error.isEmpty()
                                && (!remoteMode
                                        || downloadsOnly
                                        || recent
                                        || catalogError.isEmpty())
                        ? View.GONE
                        : View.VISIBLE);
        if (!home && remoteMode && !downloadsOnly && !recent) {
            long count = remoteBooks.stream().filter(entry -> !entry.node().directory()).count();
            status.setText(
                    getString(R.string.mega_status_prefix)
                            + count
                            + getString(R.string.ui_comics)
                            + (catalogUpdated == 0
                                    ? getString(R.string.ui_no_cached_listing)
                                    : catalogStale
                                            ? getString(R.string.ui_cached_listing)
                                            : getString(R.string.ui_up_to_date))
                            + (catalogError.isEmpty()
                                    ? ""
                                    : getString(R.string.ui_refresh_failed)
                                            + catalogError
                                            + "\n"
                                            + getString(R.string.ui_refresh_library)));
            return;
        }
        status.setText(
                books.isEmpty()
                        ? getString(R.string.library_empty)
                        : books.size()
                                + getString(R.string.ui_offline_comics)
                                + (!error.isEmpty()
                                        ? getString(R.string.ui_sync_pending) + error
                                        : synced == 0
                                                ? ""
                                                : getString(R.string.ui_sync)
                                                        + DateUtils.getRelativeTimeSpanString(
                                                                synced)));
    }

    private void pick() {
        Intent intent =
                new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .setType("*/*")
                        .addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, OPEN);
    }

    private void open(Library.Book book) {
        startActivity(new Intent(this, ReaderActivity.class).putExtra("id", book.id()));
    }

    private void resumeLastBook() {
        String id = getSharedPreferences("reader", 0).getString("last_book", "");
        worker.execute(
                () -> {
                    try {
                        Library library = Library.get(this);
                        Library.Book book = library.book(id);
                        Progress state = book == null ? null : library.state(id);
                        if (book != null
                                && state != null
                                && !state.completed
                                && library.file(book).isFile())
                            runOnUiThread(
                                    () -> {
                                        if (!isDestroyed()) open(book);
                                    });
                    } catch (Exception error) {
                        runOnUiThread(() -> Ui.error(this, error));
                    }
                });
    }

    private void openItem(ShelfItem item) {
        if (busy) {
            Ui.toast(this, getString(R.string.ui_wait_for_the_current_operation_to_finish));
            return;
        }
        if (item.directory()) {
            covers.cancelPending();
            megaParents.push(new MegaClient.Node(megaFolder, megaName, true, 0));
            megaFolder = item.remote().node().handle();
            megaName = item.title();
            catalogError = "";
            catalogStale = true;
            autoRefreshAttempted = false;
            remoteBooks.clear();
            showLibrary(selection);
            refresh();
        } else if (item.book() != null) open(item.book());
        else {
            MegaClient mega = MegaClient.instance(this);
            if (!mega.loggedIn()) {
                megaLogin();
                return;
            }
            String account = megaAccount;
            downloadTask(
                    getString(R.string.ui_downloading_for_reading),
                    () -> {
                        if (!account.equals(mega.account()))
                            throw new IOException(
                                    "The saved folder belongs to another MEGA account");
                        File temporary = mega.download(item.remote().node());
                        try {
                            Library.Book book =
                                    Importer.fromDownload(this, item.title(), temporary);
                            MegaLibrary.get(this)
                                    .link(this, account, item.remote().node().handle(), book);
                            return book;
                        } finally {
                            if (temporary.exists() && !temporary.delete())
                                android.util.Log.w(
                                        "ComicReader", "Could not remove the temporary download");
                        }
                    },
                    book -> {
                        refresh();
                        open(book);
                    });
        }
    }

    private void itemMenu(ShelfItem item) {
        if (busy || item.directory()) return;
        if (item.remote() == null) {
            bookMenu(item.book());
            return;
        }
        String account = megaAccount;
        List<String> labels =
                new ArrayList<>(
                        List.of(
                                favorite(item)
                                        ? getString(R.string.ui_remove_from_favorites)
                                        : getString(R.string.ui_add_to_favorites),
                                completed(item)
                                        ? getString(R.string.ui_mark_as_unread)
                                        : getString(R.string.ui_mark_as_read)));
        if (item.book() != null) labels.add(getString(R.string.ui_delete_local_copy));
        new AlertDialog.Builder(this)
                .setTitle(item.title())
                .setItems(
                        labels.toArray(new String[0]),
                        (dialog, which) -> {
                            if (which == 2) {
                                removeBook(item.book());
                                return;
                            }
                            Boolean read = which == 1 ? !completed(item) : null,
                                    star = which == 0 ? !favorite(item) : null;
                            task(
                                    getString(R.string.ui_saving),
                                    () -> {
                                        MegaLibrary.get(this)
                                                .flags(this, account, item.remote(), read, star);
                                        return null;
                                    },
                                    ignored -> {
                                        refresh();
                                        Cloud.schedule(this);
                                    });
                        })
                .show();
    }

    private void refreshMega() {
        refreshMega(true);
    }

    private void refreshMega(boolean interactive) {
        if (!remoteMode || busy) return;
        autoRefreshAttempted = true;
        String account = megaAccount, name = megaName;
        long folder = megaFolder;
        catalogError = "";
        task(
                getString(R.string.ui_refreshing_mega_folder),
                () -> {
                    try {
                        MegaClient mega = MegaClient.instance(this);
                        if (!account.equals(mega.account()))
                            throw new IOException(
                                    "The saved library belongs to another MEGA account");
                        MegaLibrary.get(this).replace(account, folder, name, mega.list(folder));
                        catalogStale = false;
                        return null;
                    } catch (Exception error) {
                        catalogStale = true;
                        catalogError = Ui.message(this, error);
                        throw error;
                    }
                },
                ignored -> refresh(),
                error -> {
                    if (interactive) Ui.error(this, error);
                    else
                        android.util.Log.w(
                                "ComicReader", "Automatic MEGA folder refresh failed", error);
                    refresh();
                });
    }

    private void bookMenu(Library.Book book) {
        Progress state = states.get(book.id());
        new AlertDialog.Builder(this)
                .setTitle(book.title())
                .setItems(
                        new String[] {
                            state.favorite
                                    ? getString(R.string.ui_remove_from_favorites)
                                    : getString(R.string.ui_add_to_favorites),
                            state.completed
                                    ? getString(R.string.ui_mark_as_unread)
                                    : getString(R.string.ui_mark_as_read),
                            getString(R.string.ui_delete_local_copy)
                        },
                        (dialog, selected) -> {
                            if (selected == 2) removeBook(book);
                            else
                                task(
                                        getString(R.string.ui_saving),
                                        () -> {
                                            Progress current = Library.get(this).state(book.id());
                                            return Library.get(this)
                                                    .update(
                                                            book.id(),
                                                            selected == 1 && current.completed
                                                                    ? 0
                                                                    : current.page,
                                                            selected == 1
                                                                    ? !current.completed
                                                                    : current.completed,
                                                            selected == 0
                                                                    ? !current.favorite
                                                                    : current.favorite,
                                                            current.bookmarks());
                                        },
                                        ignored -> {
                                            refresh();
                                            Cloud.schedule(this);
                                        });
                        })
                .show();
    }

    private void removeBook(Library.Book book) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.delete_local_copy_message))
                .setNegativeButton(getString(R.string.action_cancel), null)
                .setPositiveButton(
                        getString(R.string.ui_delete),
                        (dialog, which) ->
                                task(
                                        getString(R.string.ui_deleting),
                                        () -> {
                                            Library.get(this).remove(book);
                                            return null;
                                        },
                                        ignored -> refresh()))
                .show();
    }

    private void more() {
        if (settingsDialog != null && settingsDialog.isShowing()) return;
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        settingsDialog = dialog;
        LinearLayout root = Ui.column(this);
        root.setPadding(0, 0, 0, 0);
        Ui.insets(root);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(
                Ui.icon(
                        this,
                        R.drawable.icon_back,
                        getString(R.string.action_close),
                        dialog::dismiss),
                new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 56)));
        TextView title = Ui.text(this, getString(R.string.settings_title), 24);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        EditText query = Ui.input(this, getString(R.string.settings_search));
        query.setContentDescription(getString(R.string.settings_search));
        query.setVisibility(View.GONE);
        header.addView(
                Ui.icon(
                        this,
                        R.drawable.icon_search,
                        getString(R.string.settings_search),
                        () -> {
                            boolean show = query.getVisibility() != View.VISIBLE;
                            query.setVisibility(show ? View.VISIBLE : View.GONE);
                            InputMethodManager keyboard =
                                    getSystemService(InputMethodManager.class);
                            if (show) {
                                query.requestFocus();
                                keyboard.showSoftInput(query, InputMethodManager.SHOW_IMPLICIT);
                            } else {
                                query.getText().clear();
                                keyboard.hideSoftInputFromWindow(query.getWindowToken(), 0);
                            }
                        }),
                new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 56)));
        root.addView(header);
        root.addView(query, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        settingsBody = new LinearLayout(this);
        settingsBody.setOrientation(LinearLayout.VERTICAL);
        LinearLayout body = settingsBody;
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        SharedPreferences appearance = getSharedPreferences("appearance", 0);
        SharedPreferences reader = getSharedPreferences("reader", 0);
        SharedPreferences cloud = getSharedPreferences("cloud", 0);

        LinearLayout group =
                settingsSection(body, R.string.settings_appearance, R.drawable.icon_palette);
        settingsRow(
                group,
                dialog,
                R.drawable.icon_theme,
                R.string.ui_theme,
                getString(
                        switch (Ui.theme(this)) {
                            case "dark" -> R.string.ui_dark;
                            case "oled" -> R.string.ui_oled_black;
                            default -> R.string.ui_light;
                        }),
                this::themeSettings);
        settingsRow(
                group,
                dialog,
                R.drawable.icon_search,
                R.string.accent_color,
                getString(
                        switch (appearance.getString("accent", "blue")) {
                            case "orange" -> R.string.accent_orange;
                            case "teal" -> R.string.accent_teal;
                            default -> R.string.accent_blue;
                        }),
                this::accentSettings);
        String language = AppLanguage.selected(this);
        settingsRow(
                group,
                dialog,
                R.drawable.icon_language,
                R.string.app_language,
                language.equals("es")
                        ? "Español"
                        : language.equals("en") ? "English" : getString(R.string.system_language),
                this::languageSettings);
        settingsRow(
                group,
                dialog,
                R.drawable.icon_grid,
                R.string.library_view,
                getString(grid ? R.string.ui_cover_view : R.string.ui_list_view),
                this::libraryViewSettings);

        group = settingsSection(body, R.string.settings_reading, R.drawable.icon_book);
        settingsRow(
                group,
                dialog,
                R.drawable.icon_reading_direction,
                R.string.reading_direction,
                getString(
                        reader.getBoolean("manga", false)
                                ? R.string.reading_right_left
                                : R.string.reading_left_right),
                () ->
                        readingChoice(
                                R.string.reading_direction,
                                "manga",
                                R.string.reading_left_right,
                                R.string.reading_right_left));
        settingsRow(
                group,
                dialog,
                R.drawable.icon_zoom,
                R.string.reading_zoom,
                getString(
                        reader.getBoolean("width", false)
                                ? R.string.reading_fit_width
                                : R.string.reading_fit_page),
                () ->
                        readingChoice(
                                R.string.reading_zoom,
                                "width",
                                R.string.reading_fit_page,
                                R.string.reading_fit_width));
        settingsToggle(
                group,
                R.drawable.icon_volume,
                R.string.reading_volume,
                reader.getBoolean("volume", false),
                checked -> reader.edit().putBoolean("volume", checked).apply());
        settingsToggle(
                group,
                R.drawable.icon_history,
                R.string.reading_show_progress,
                reader.getBoolean("progress", true),
                checked -> reader.edit().putBoolean("progress", checked).apply());
        settingsToggle(
                group,
                R.drawable.icon_watch,
                R.string.reading_keep_screen,
                reader.getBoolean("keep_screen", false),
                checked -> reader.edit().putBoolean("keep_screen", checked).apply());

        group =
                settingsSection(
                        body, R.string.settings_library_downloads, R.drawable.icon_download);
        settingsRow(
                group,
                dialog,
                R.drawable.icon_startup,
                R.string.ui_startup_screen,
                getString(
                        switch (appearance.getString("startup", "library")) {
                            case "home" -> R.string.ui_connections_and_setup;
                            case "reader" -> R.string.ui_last_unfinished_comic;
                            default -> R.string.library_title;
                        }),
                this::startupSettings);
        settingsRow(
                group,
                dialog,
                R.drawable.icon_folder,
                R.string.ui_folders_and_libraries,
                remoteMode ? megaName : getString(R.string.ui_local_library),
                this::librarySources);
        settingsRow(
                group,
                dialog,
                R.drawable.icon_download,
                R.string.download_settings,
                getString(
                        getSharedPreferences("downloads", 0).getBoolean("metered", false)
                                ? R.string.covers_any
                                : R.string.covers_wifi),
                this::downloadSettings);
        settingsRow(
                group,
                dialog,
                R.drawable.icon_package,
                R.string.remote_covers,
                getString(
                        switch (appearance.getString("covers", "wifi")) {
                            case "any" -> R.string.covers_any;
                            case "off" -> R.string.covers_downloaded;
                            default -> R.string.covers_wifi;
                        }),
                this::coverSettings);
        settingsRow(
                group,
                dialog,
                R.drawable.icon_list,
                R.string.ui_sort_by_title,
                getString(R.string.ui_title_a_z),
                () -> showLibrary(5));
        settingsRow(
                group,
                dialog,
                R.drawable.icon_sync,
                R.string.ui_refresh_library,
                "",
                () -> {
                    if (remoteMode && !downloadsOnly) refreshMega();
                    else refresh();
                });

        group =
                settingsSection(
                        body, R.string.settings_cloud_progress, R.drawable.icon_cloud_filled);
        settingsToggle(
                group,
                R.drawable.icon_sync,
                R.string.settings_sync_enabled,
                cloud.getBoolean("auto", false),
                checked -> {
                    cloud.edit().putBoolean("auto", checked).apply();
                    getSystemService(JobScheduler.class).cancel(1);
                    Cloud.schedule(this);
                });
        settingsRow(
                group,
                dialog,
                R.drawable.icon_cloud,
                R.string.ui_cloud_accounts,
                getString(R.string.ui_direct_access),
                this::cloudMenu);
        settingsRow(
                group,
                dialog,
                R.drawable.icon_settings,
                R.string.ui_sync_settings,
                getString(
                        !cloud.getBoolean("auto", false)
                                ? R.string.settings_manual_sync
                                : cloud.getBoolean("wifi", true)
                                        ? R.string.settings_auto_wifi
                                        : R.string.settings_auto_any),
                this::syncSettings);
        settingsRow(
                group,
                dialog,
                R.drawable.icon_sync,
                R.string.ui_sync_progress,
                switch (cloud.getString("provider", "")) {
                    case "mega" -> "MEGA";
                    case "drive" -> "Google Drive";
                    case "webdav" -> "WebDAV";
                    default -> getString(R.string.ui_no_provider);
                },
                this::sync);
        settingsRow(
                group,
                dialog,
                R.drawable.icon_upload,
                R.string.ui_export_progress,
                "",
                () ->
                        startActivityForResult(
                                new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                        .setType("application/octet-stream")
                                        .addCategory(Intent.CATEGORY_OPENABLE)
                                        .putExtra(Intent.EXTRA_TITLE, "comicreader-progress.crp"),
                                EXPORT));
        settingsRow(
                group,
                dialog,
                R.drawable.icon_download,
                R.string.ui_import_progress,
                "",
                () ->
                        startActivityForResult(
                                new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                        .setType("*/*")
                                        .addCategory(Intent.CATEGORY_OPENABLE),
                                RESTORE));

        group =
                settingsSection(
                        body, R.string.settings_application, R.drawable.icon_settings_filled);
        settingsToggle(
                group,
                R.drawable.icon_sync,
                R.string.settings_auto_updates,
                getSharedPreferences("updates", 0).getBoolean("auto", true),
                checked ->
                        getSharedPreferences("updates", 0)
                                .edit()
                                .putBoolean("auto", checked)
                                .apply());
        settingsRow(
                group,
                dialog,
                R.drawable.icon_download,
                R.string.ui_app_updates,
                "GitHub Releases",
                updater::showSettings);
        settingsRow(
                group,
                dialog,
                R.drawable.icon_info,
                R.string.ui_about,
                "ComicReader",
                () ->
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.ui_comicreader_mit)
                                .setMessage(
                                        getString(
                                                R.string.about_message,
                                                MegaClient.instance(this).status()))
                                .setPositiveButton(R.string.action_ok, null)
                                .show());
        TextView noMatches = Ui.text(this, getString(R.string.settings_no_matches), 16);
        noMatches.setGravity(Gravity.CENTER);
        noMatches.setVisibility(View.GONE);
        body.addView(noMatches);
        query.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(
                            CharSequence value, int start, int count, int after) {}

                    public void onTextChanged(
                            CharSequence value, int start, int before, int count) {
                        String term = value.toString().toLowerCase(Locale.ROOT).trim();
                        int matches = 0;
                        for (int i = 0; i < body.getChildCount() - 1; i++) {
                            LinearLayout section = (LinearLayout) body.getChildAt(i);
                            boolean headingMatches =
                                    section.getTag()
                                            .toString()
                                            .toLowerCase(Locale.ROOT)
                                            .contains(term);
                            int rows = 0;
                            for (int j = 1; j < section.getChildCount(); j++) {
                                View row = section.getChildAt(j);
                                if (row.getTag() == null) {
                                    row.setVisibility(View.GONE);
                                    continue;
                                }
                                boolean show =
                                        headingMatches
                                                || row.getTag()
                                                        .toString()
                                                        .toLowerCase(Locale.ROOT)
                                                        .contains(term);
                                row.setVisibility(show ? View.VISIBLE : View.GONE);
                                if (show) {
                                    if (rows++ > 0 && j > 1)
                                        section.getChildAt(j - 1).setVisibility(View.VISIBLE);
                                }
                            }
                            section.setVisibility(rows == 0 ? View.GONE : View.VISIBLE);
                            matches += rows;
                        }
                        noMatches.setVisibility(matches == 0 ? View.VISIBLE : View.GONE);
                    }

                    public void afterTextChanged(Editable value) {}
                });
        dialog.setContentView(root);
        dialog.setOnDismissListener(
                ignored -> {
                    if (settingsDialog == dialog) {
                        settingsDialog = null;
                        settingsBody = null;
                    }
                });
        dialog.getWindow()
                .setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(Ui.background(this)));
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
        sizeSettingsDialog();
    }

    private LinearLayout settingsSection(LinearLayout parent, int title, int icon) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        card.setBackground(Ui.background(Ui.surface(this), Ui.dp(this, 16)));
        card.setTag(getString(title));
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        ImageView symbol = new ImageView(this);
        symbol.setImageResource(icon);
        symbol.setColorFilter(Ui.accentText(this));
        symbol.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heading.addView(symbol, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
        TextView label = Ui.text(this, getString(title), 16);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setAccessibilityHeading(true);
        heading.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        heading.setMinimumHeight(Ui.dp(this, 40));
        card.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = Ui.dp(this, 12);
        parent.addView(card, params);
        return card;
    }

    private void settingsDivider(LinearLayout group) {
        if (group.getChildCount() <= 1) return;
        View line = new View(this);
        line.setBackgroundColor(Ui.dark(this) ? 0xff1b2024 : 0xffdfe5ea);
        group.addView(line, new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
    }

    private void settingsRow(
            LinearLayout group,
            Dialog dialog,
            int icon,
            int title,
            String summary,
            Runnable action) {
        settingsDivider(group);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 4), 0);
        row.setMinimumHeight(Ui.dp(this, 48));
        ImageView symbol = new ImageView(this);
        symbol.setImageResource(icon);
        symbol.setColorFilter(
                title == R.string.accent_color ? Ui.accentText(this) : Ui.secondary(this));
        row.addView(symbol, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));
        TextView label = Ui.text(this, getString(title), 14);
        label.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        if (!summary.isEmpty()) {
            TextView value = Ui.text(this, summary, 13);
            value.setPadding(0, Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
            value.setTextColor(Ui.secondary(this));
            value.setMaxWidth(Ui.dp(this, 140));
            value.setMaxLines(2);
            value.setEllipsize(TextUtils.TruncateAt.END);
            value.setGravity(Gravity.END);
            row.addView(value);
        }
        ImageView arrow = new ImageView(this);
        arrow.setImageResource(
                title == R.string.ui_theme ? R.drawable.icon_expand : R.drawable.icon_next);
        arrow.setColorFilter(Ui.secondary(this));
        row.addView(arrow, new LinearLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, 16)));
        for (int i = 0; i < row.getChildCount(); i++)
            row.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.setContentDescription(getString(title) + (summary.isEmpty() ? "" : ". " + summary));
        row.setTag(row.getContentDescription());
        row.setFocusable(true);
        row.setBackground(Ui.selectable(this, android.graphics.Color.TRANSPARENT, 8));
        row.setOnClickListener(
                view -> {
                    dialog.dismiss();
                    action.run();
                });
        group.addView(row);
    }

    private void settingsToggle(
            LinearLayout group,
            int icon,
            int title,
            boolean checked,
            java.util.function.Consumer<Boolean> changed) {
        settingsDivider(group);
        Switch toggle = new Switch(this);
        toggle.setText(title);
        toggle.setTextSize(14);
        toggle.setGravity(Gravity.CENTER_VERTICAL);
        toggle.setIncludeFontPadding(false);
        toggle.setTextColor(Ui.foreground(this));
        toggle.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 12), 0);
        toggle.setMinimumHeight(Ui.dp(this, 48));
        toggle.setSwitchPadding(Ui.dp(this, 8));
        android.graphics.drawable.GradientDrawable thumb =
                Ui.background(android.graphics.Color.WHITE, Ui.dp(this, 10));
        thumb.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        thumb.setSize(Ui.dp(this, 20), Ui.dp(this, 20));
        android.graphics.drawable.GradientDrawable track =
                Ui.background(android.graphics.Color.WHITE, Ui.dp(this, 10));
        track.setSize(Ui.dp(this, 40), Ui.dp(this, 20));
        toggle.setThumbDrawable(thumb);
        toggle.setTrackDrawable(track);
        toggle.setSwitchMinWidth(Ui.dp(this, 40));
        toggle.setShowText(false);
        toggle.setSplitTrack(false);
        toggle.setThumbTintList(
                android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE));
        toggle.setTrackTintList(
                new android.content.res.ColorStateList(
                        new int[][] {new int[] {android.R.attr.state_checked}, new int[0]},
                        new int[] {Ui.accent(this), Ui.dark(this) ? 0xff454b50 : 0xffadb6bf}));
        android.graphics.drawable.Drawable symbol = getDrawable(icon).mutate();
        symbol.setTint(Ui.secondary(this));
        symbol.setBounds(0, 0, Ui.dp(this, 20), Ui.dp(this, 20));
        toggle.setCompoundDrawables(symbol, null, null, null);
        toggle.setCompoundDrawablePadding(Ui.dp(this, 12));
        toggle.setChecked(checked);
        toggle.setTag(getString(title));
        toggle.setOnCheckedChangeListener((view, value) -> changed.accept(value));
        group.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
    }

    private void sizeSettingsDialog() {
        if (settingsDialog == null || !settingsDialog.isShowing()) return;
        settingsDialog.getWindow().setLayout(-1, -1);
        int width = getResources().getConfiguration().screenWidthDp;
        int padding = Ui.dp(this, Math.max(12, (width - 760) / 2));
        settingsBody.setPadding(padding, Ui.dp(this, 8), padding, Ui.dp(this, 12));
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        sizeSettingsDialog();
        updateDisplayMode();
    }

    private void accentSettings() {
        String[] values = {"blue", "orange", "teal"};
        new AlertDialog.Builder(this)
                .setTitle(R.string.accent_color)
                .setSingleChoiceItems(
                        new String[] {
                            getString(R.string.accent_blue),
                            getString(R.string.accent_orange),
                            getString(R.string.accent_teal)
                        },
                        Arrays.asList(values)
                                .indexOf(
                                        getSharedPreferences("appearance", 0)
                                                .getString("accent", "blue")),
                        (dialog, which) -> {
                            getSharedPreferences("appearance", 0)
                                    .edit()
                                    .putString("accent", values[which])
                                    .apply();
                            dialog.dismiss();
                            recreate();
                        })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void libraryViewSettings() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.library_view)
                .setSingleChoiceItems(
                        new String[] {
                            getString(R.string.ui_cover_view), getString(R.string.ui_list_view)
                        },
                        grid ? 0 : 1,
                        (dialog, which) -> {
                            grid = which == 0;
                            getSharedPreferences("library", 0)
                                    .edit()
                                    .putBoolean("grid", grid)
                                    .apply();
                            updateDisplayMode();
                            dialog.dismiss();
                        })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void readingChoice(int title, String key, int off, int on) {
        SharedPreferences preferences = getSharedPreferences("reader", 0);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(
                        new String[] {getString(off), getString(on)},
                        preferences.getBoolean(key, false) ? 1 : 0,
                        (dialog, which) -> {
                            preferences.edit().putBoolean(key, which == 1).apply();
                            dialog.dismiss();
                        })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void themeSettings() {
        String[] values = {"light", "dark", "oled"};
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.ui_theme))
                .setSingleChoiceItems(
                        new String[] {
                            getString(R.string.ui_light),
                            getString(R.string.ui_dark),
                            getString(R.string.ui_oled_black)
                        },
                        Arrays.asList(values).indexOf(Ui.theme(this)),
                        (dialog, which) -> {
                            getSharedPreferences("appearance", 0)
                                    .edit()
                                    .putString("theme", values[which])
                                    .apply();
                            dialog.dismiss();
                            recreate();
                        })
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show();
    }

    private void startupSettings() {
        String[] values = {"library", "home", "reader"};
        SharedPreferences preferences = getSharedPreferences("appearance", 0);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.ui_when_the_app_opens))
                .setSingleChoiceItems(
                        new String[] {
                            getString(R.string.library_title),
                            getString(R.string.ui_connections_and_setup),
                            getString(R.string.ui_last_unfinished_comic)
                        },
                        Arrays.asList(values).indexOf(preferences.getString("startup", "library")),
                        (dialog, which) -> {
                            preferences.edit().putString("startup", values[which]).apply();
                            dialog.dismiss();
                        })
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show();
    }

    private void languageSettings() {
        String[] tags = {"", "en", "es"};
        int selected = java.util.Arrays.asList(tags).indexOf(AppLanguage.selected(this));
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_language)
                .setSingleChoiceItems(
                        new String[] {getString(R.string.system_language), "English", "Español"},
                        Math.max(0, selected),
                        (dialog, which) -> {
                            dialog.dismiss();
                            AppLanguage.select(this, tags[which]);
                        })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void downloadSettings() {
        SharedPreferences preferences = getSharedPreferences("downloads", 0);
        new AlertDialog.Builder(this)
                .setTitle(R.string.download_settings)
                .setMultiChoiceItems(
                        new String[] {getString(R.string.allow_mobile_downloads)},
                        new boolean[] {preferences.getBoolean("metered", false)},
                        (dialog, index, checked) ->
                                preferences.edit().putBoolean("metered", checked).apply())
                .setPositiveButton(R.string.action_done, null)
                .show();
    }

    private void coverSettings() {
        String[] modes = {"wifi", "any", "off"};
        int selected =
                java.util.Arrays.asList(modes)
                        .indexOf(getSharedPreferences("appearance", 0).getString("covers", "wifi"));
        new AlertDialog.Builder(this)
                .setTitle(R.string.remote_covers)
                .setSingleChoiceItems(
                        new String[] {
                            getString(R.string.covers_wifi),
                            getString(R.string.covers_any),
                            getString(R.string.covers_downloaded)
                        },
                        Math.max(0, selected),
                        (dialog, which) -> {
                            dialog.dismiss();
                            covers.setPaused(true);
                            getSharedPreferences("appearance", 0)
                                    .edit()
                                    .putString("covers", modes[which])
                                    .apply();
                            covers.setPaused(!resumed || busy);
                            CoverJob.schedule(this, true);
                            refresh();
                        })
                .setNegativeButton(R.string.action_cancel, null)
                .setNeutralButton(
                        R.string.covers_info,
                        (dialog, which) ->
                                new AlertDialog.Builder(this)
                                        .setTitle(R.string.remote_covers)
                                        .setMessage(R.string.covers_explanation)
                                        .setPositiveButton(R.string.action_ok, null)
                                        .show())
                .show();
    }

    private void cloudMenu() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.ui_cloud_accounts))
                .setItems(
                        new String[] {
                            getString(R.string.ui_google_drive_direct_access),
                            getString(R.string.ui_mega_direct_access),
                            getString(R.string.ui_optional_webdav),
                            getString(R.string.ui_disconnect_active_provider)
                        },
                        (dialog, which) -> {
                            if (which == 0)
                                DriveAuth.signIn(
                                        this, this::driveConnected, error -> Ui.error(this, error));
                            else if (which == 1) megaLogin();
                            else if (which == 2) webDavMenu();
                            else disconnect();
                        })
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    private void webDavMenu() {
        new AlertDialog.Builder(this)
                .setTitle("WebDAV HTTPS")
                .setItems(
                        new String[] {
                            getString(R.string.ui_browse_download),
                            getString(R.string.ui_configure_connection)
                        },
                        (dialog, which) -> {
                            if (which == 0) {
                                if (getSharedPreferences("cloud", 0)
                                        .getString("endpoint", "")
                                        .isEmpty()) configureCloud();
                                else
                                    task(
                                            getString(R.string.ui_connecting),
                                            () -> Cloud.client(this),
                                            dav -> {
                                                useProvider("webdav");
                                                browse(dav, dav.root(), new ArrayDeque<>());
                                            });
                            } else if (which == 1) configureCloud();
                        })
                .show();
    }

    private void useProvider(String name) {
        getSystemService(JobScheduler.class).cancel(1);
        getSharedPreferences("cloud", 0)
                .edit()
                .putString("provider", name)
                .remove("last_error")
                .remove("last_sync")
                .apply();
        updateStatus();
    }

    private void driveConnected(String token) {
        useProvider("drive");
        try {
            browseDrive(new DriveClient(token), "root", new ArrayDeque<>());
        } catch (Exception e) {
            Ui.error(this, e);
        }
    }

    private void browseDrive(DriveClient drive, String folder, Deque<String> parents) {
        task(
                getString(R.string.ui_loading_google_drive),
                () -> drive.list(folder),
                entries -> {
                    List<DriveClient.Entry> listed = new ArrayList<>();
                    for (DriveClient.Entry entry : entries)
                        if (entry.directory() || ComicFiles.supported(entry.name()))
                            listed.add(entry);
                    String[] labels = new String[listed.size()];
                    for (int i = 0; i < listed.size(); i++)
                        labels[i] =
                                (listed.get(i).directory()
                                                ? getString(R.string.folder_name_prefix)
                                                : "")
                                        + listed.get(i).name();
                    new AlertDialog.Builder(this)
                            .setTitle("Google Drive")
                            .setItems(
                                    labels,
                                    (dialog, index) -> {
                                        DriveClient.Entry entry = listed.get(index);
                                        if (entry.directory()) {
                                            parents.push(folder);
                                            browseDrive(drive, entry.id(), parents);
                                        } else
                                            confirmDownload(
                                                    entry.name(),
                                                    entry.size(),
                                                    () ->
                                                            downloadTask(
                                                                    getString(
                                                                            R.string
                                                                                    .ui_downloading_from_drive),
                                                                    () -> {
                                                                        try (InputStream input =
                                                                                drive.download(
                                                                                        entry
                                                                                                .id())) {
                                                                            return Importer
                                                                                    .fromStream(
                                                                                            this,
                                                                                            entry
                                                                                                    .name(),
                                                                                            input);
                                                                        }
                                                                    },
                                                                    book -> {
                                                                        refresh();
                                                                        open(book);
                                                                    }));
                                    })
                            .setNegativeButton(getString(R.string.action_close), null)
                            .setNeutralButton(
                                    parents.isEmpty()
                                            ? getString(R.string.ui_root)
                                            : getString(R.string.ui_up),
                                    (dialog, which) -> {
                                        if (!parents.isEmpty())
                                            browseDrive(drive, parents.pop(), parents);
                                    })
                            .show();
                });
    }

    private void megaLogin() {
        MegaClient mega = MegaClient.instance(this);
        if (!mega.available()) {
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.ui_mega_is_unavailable))
                    .setMessage(mega.status())
                    .setPositiveButton(getString(R.string.action_ok), null)
                    .show();
            return;
        }
        if (mega.loggedIn()) {
            useProvider("mega");
            chooseMegaFolder(mega, MegaClient.ROOT, "MEGA", new ArrayDeque<>());
            return;
        }
        LinearLayout form = Ui.column(this);
        EditText email = Ui.input(this, getString(R.string.ui_mega_email)),
                password = Ui.input(this, getString(R.string.ui_password)),
                otp = Ui.input(this, getString(R.string.ui_2fa_code_if_enabled));
        email.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        password.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        otp.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        form.addView(email);
        form.addView(password);
        form.addView(otp);
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle(getString(R.string.ui_sign_in_to_mega))
                        .setView(form)
                        .setNegativeButton(getString(R.string.action_cancel), null)
                        .setPositiveButton(getString(R.string.ui_sign_in), null)
                        .create();
        dialog.setOnShowListener(
                ignored -> {
                    dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                            .setOnClickListener(
                                    view -> {
                                        String address = email.getText().toString().trim(),
                                                secret = password.getText().toString(),
                                                code = otp.getText().toString().trim();
                                        password.getText().clear();
                                        otp.getText().clear();
                                        task(
                                                getString(R.string.ui_signing_in_to_mega),
                                                () -> {
                                                    mega.login(address, secret, code);
                                                    return null;
                                                },
                                                result -> {
                                                    dialog.dismiss();
                                                    useProvider("mega");
                                                    chooseMegaFolder(
                                                            mega,
                                                            MegaClient.ROOT,
                                                            "MEGA",
                                                            new ArrayDeque<>());
                                                });
                                    });
                });
        dialog.show();
    }

    private void chooseMegaFolder(
            MegaClient mega, long folder, String name, Deque<MegaClient.Node> parents) {
        task(
                getString(R.string.ui_loading_mega_folders),
                () -> new FolderListing(mega.account(), mega.list(folder)),
                listing -> showMegaFolderPicker(mega, folder, name, parents, listing));
    }

    void showMegaFolderPicker(
            MegaClient mega,
            long folder,
            String name,
            Deque<MegaClient.Node> parents,
            FolderListing listing) {
        List<MegaClient.Node> directories = new ArrayList<>();
        for (MegaClient.Node entry : listing.nodes())
            if (entry.directory() && !entry.name().equals(".comicreader-state-v1"))
                directories.add(entry);
        String[] labels = directories.stream().map(MegaClient.Node::name).toArray(String[]::new);
        AlertDialog.Builder dialog =
                new AlertDialog.Builder(this).setTitle(getString(R.string.ui_choose_folder) + name);
        if (directories.isEmpty()) dialog.setMessage(getString(R.string.choose_folder_empty));
        else
            dialog.setItems(
                    labels,
                    (picker, index) -> {
                        MegaClient.Node entry = directories.get(index);
                        parents.push(new MegaClient.Node(folder, name, true, 0));
                        chooseMegaFolder(mega, entry.handle(), entry.name(), parents);
                    });
        dialog.setPositiveButton(
                        getString(R.string.action_ok),
                        (picker, which) ->
                                task(
                                        getString(R.string.ui_saving_library),
                                        () -> {
                                            MegaLibrary.get(this)
                                                    .replace(
                                                            listing.account(),
                                                            folder,
                                                            name,
                                                            listing.nodes());
                                            if (!getSharedPreferences("library", 0)
                                                    .edit()
                                                    .putString("mega_account", listing.account())
                                                    .putLong("mega_root", folder)
                                                    .putString("mega_name", name)
                                                    .putString("source", "mega")
                                                    .commit())
                                                throw new IOException(
                                                        "Could not save the selected folder");
                                            return null;
                                        },
                                        ignored -> {
                                            megaAccount = listing.account();
                                            megaRoot = megaFolder = folder;
                                            megaName = name;
                                            megaParents.clear();
                                            remoteMode = true;
                                            catalogStale = false;
                                            catalogError = "";
                                            autoRefreshAttempted = true;
                                            showLibrary(0);
                                            refresh();
                                        }))
                .setNegativeButton(getString(R.string.action_cancel), null);
        if (!parents.isEmpty())
            dialog.setNeutralButton(
                    getString(R.string.ui_up),
                    (picker, which) -> {
                        MegaClient.Node parent = parents.pop();
                        chooseMegaFolder(mega, parent.handle(), parent.name(), parents);
                    });
        dialog.show();
    }

    record FolderListing(String account, List<MegaClient.Node> nodes) {}

    private void confirmDownload(String name, long size, Runnable download) {
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setMessage(
                        getString(R.string.ui_download_for_offline_reading)
                                + (size >= 0
                                        ? " · "
                                                + String.format(
                                                        Locale.getDefault(),
                                                        getString(R.string.ui_1f_mib),
                                                        size / 1048576.0)
                                        : "")
                                + ".")
                .setNegativeButton(getString(R.string.action_cancel), null)
                .setPositiveButton(
                        getString(R.string.ui_download), (dialog, which) -> download.run())
                .show();
    }

    private void disconnect() {
        String provider = getSharedPreferences("cloud", 0).getString("provider", "");
        Runnable clear =
                () -> {
                    if (provider.equals("mega")) {
                        getSharedPreferences("library", 0)
                                .edit()
                                .remove("mega_account")
                                .remove("mega_root")
                                .remove("mega_name")
                                .putString("source", "local")
                                .apply();
                        megaAccount = "";
                        remoteMode = false;
                        megaParents.clear();
                        showLibrary(0);
                        refresh();
                    }
                    getSharedPreferences("cloud", 0).edit().clear().apply();
                    getSystemService(JobScheduler.class).cancel(1);
                    updateStatus();
                };
        new AlertDialog.Builder(this)
                .setMessage(
                        getString(R.string.ui_disconnect)
                                + (provider.isEmpty() ? "cloud" : provider)
                                + getString(
                                        R.string.ui_your_local_library_and_progress_will_be_kept))
                .setNegativeButton(getString(R.string.action_cancel), null)
                .setPositiveButton(
                        getString(R.string.action_disconnect),
                        (dialog, which) -> {
                            if (provider.equals("drive"))
                                DriveAuth.disconnect(
                                        this,
                                        ignored -> clear.run(),
                                        error -> Ui.error(this, error));
                            else if (provider.equals("mega"))
                                this.<Exception>task(
                                        getString(R.string.ui_signing_out),
                                        () -> {
                                            MegaClient mega = MegaClient.instance(this);
                                            try {
                                                mega.logout();
                                                return null;
                                            } catch (Exception error) {
                                                if (!mega.loggedIn()) return error;
                                                throw error;
                                            }
                                        },
                                        error -> {
                                            clear.run();
                                            if (error != null) Ui.error(this, error);
                                        });
                            else clear.run();
                        })
                .show();
    }

    private void configureCloud() {
        LinearLayout form = Ui.column(this);
        form.addView(Ui.text(this, getString(R.string.webdav_setup_hint), 14));
        EditText endpoint = Ui.input(this, "https://tu-servidor/comics/");
        endpoint.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        endpoint.setText(getSharedPreferences("cloud", 0).getString("endpoint", ""));
        EditText user = Ui.input(this, getString(R.string.ui_webdav_username)),
                password = Ui.input(this, getString(R.string.ui_webdav_password));
        password.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(endpoint);
        form.addView(user);
        form.addView(password);
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle(getString(R.string.ui_https_connection))
                        .setView(form)
                        .setNegativeButton(getString(R.string.action_cancel), null)
                        .setPositiveButton(getString(R.string.ui_test_and_save), null)
                        .create();
        dialog.setOnShowListener(
                ignored ->
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                                .setOnClickListener(
                                        view -> {
                                            String address = endpoint.getText().toString().trim(),
                                                    username = user.getText().toString(),
                                                    secret = password.getText().toString();
                                            task(
                                                    getString(R.string.ui_checking_connection),
                                                    () -> {
                                                        String auth =
                                                                username.isEmpty()
                                                                                && secret.isEmpty()
                                                                        ? ""
                                                                        : "Basic "
                                                                                + android.util
                                                                                        .Base64
                                                                                        .encodeToString(
                                                                                                (username
                                                                                                                + ":"
                                                                                                                + secret)
                                                                                                        .getBytes(
                                                                                                                java
                                                                                                                        .nio
                                                                                                                        .charset
                                                                                                                        .StandardCharsets
                                                                                                                        .UTF_8),
                                                                                                android
                                                                                                        .util
                                                                                                        .Base64
                                                                                                        .NO_WRAP);
                                                        WebDav dav = new WebDav(address, auth);
                                                        dav.list(dav.root());
                                                        Secrets.configure(
                                                                this,
                                                                dav.root().toString(),
                                                                username,
                                                                secret);
                                                        return null;
                                                    },
                                                    result -> {
                                                        dialog.dismiss();
                                                        Ui.toast(
                                                                this,
                                                                getString(
                                                                        R.string
                                                                                .ui_connection_saved));
                                                        updateStatus();
                                                    });
                                        }));
        dialog.show();
    }

    private void syncSettings() {
        SharedPreferences preferences = getSharedPreferences("cloud", 0);
        String[] keys = {"auto", "wifi"};
        boolean[] values = {
            preferences.getBoolean("auto", false), preferences.getBoolean("wifi", true)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.ui_sync_settings)
                .setMultiChoiceItems(
                        new String[] {
                            getString(
                                    R.string
                                            .ui_automatically_sync_when_using_or_closing_the_reader),
                            getString(R.string.ui_automatic_sync_on_unmetered_networks_only)
                        },
                        values,
                        (dialog, which, checked) -> {
                            preferences.edit().putBoolean(keys[which], checked).apply();
                            getSystemService(JobScheduler.class).cancel(1);
                            Cloud.schedule(this);
                        })
                .setPositiveButton(getString(R.string.action_done), null)
                .show();
    }

    private void sync() {
        if (!Cloud.configured(this)) {
            cloudMenu();
            return;
        }
        task(
                getString(R.string.ui_syncing_progress),
                () -> Cloud.sync(this),
                count -> {
                    Ui.toast(this, getString(R.string.ui_progress_synced));
                    refresh();
                });
    }

    private void browse(WebDav dav, URI folder, Deque<URI> parents) {
        task(
                getString(R.string.ui_loading_folder),
                () -> dav.list(folder),
                entries -> {
                    List<WebDav.Entry> listed = new ArrayList<>();
                    for (WebDav.Entry entry : entries)
                        if (!entry.name().equals(".comicreader-state-v1")
                                && (entry.directory() || ComicFiles.supported(entry.name())))
                            listed.add(entry);
                    listed.sort(
                            (a, b) ->
                                    a.directory() == b.directory()
                                            ? ComicFiles.naturalCompare(a.name(), b.name())
                                            : a.directory() ? -1 : 1);
                    String[] labels = new String[listed.size()];
                    for (int i = 0; i < listed.size(); i++)
                        labels[i] =
                                (listed.get(i).directory()
                                                ? getString(R.string.folder_name_prefix)
                                                : "")
                                        + listed.get(i).name();
                    new AlertDialog.Builder(this)
                            .setTitle(folder.getPath())
                            .setItems(
                                    labels,
                                    (dialog, index) -> {
                                        WebDav.Entry entry = listed.get(index);
                                        if (entry.directory()) {
                                            parents.push(folder);
                                            browse(dav, entry.uri(), parents);
                                        } else
                                            new AlertDialog.Builder(this)
                                                    .setTitle(entry.name())
                                                    .setMessage(
                                                            getString(
                                                                            R.string
                                                                                    .ui_download_a_private_copy_for_offline_reading)
                                                                    + (entry.size() >= 0
                                                                            ? " ("
                                                                                    + String.format(
                                                                                            Locale
                                                                                                    .getDefault(),
                                                                                            getString(
                                                                                                    R
                                                                                                            .string
                                                                                                            .ui_1f_mib),
                                                                                            entry
                                                                                                            .size()
                                                                                                    / 1048576.0)
                                                                                    + ")"
                                                                            : "")
                                                                    + ".")
                                                    .setNegativeButton(
                                                            getString(R.string.action_cancel), null)
                                                    .setPositiveButton(
                                                            getString(R.string.ui_download),
                                                            (d, w) ->
                                                                    downloadTask(
                                                                            getString(
                                                                                    R.string
                                                                                            .ui_downloading),
                                                                            () -> {
                                                                                try (InputStream
                                                                                        in =
                                                                                                dav
                                                                                                        .download(
                                                                                                                entry
                                                                                                                        .uri())) {
                                                                                    return Importer
                                                                                            .fromStream(
                                                                                                    this,
                                                                                                    entry
                                                                                                            .name(),
                                                                                                    in);
                                                                                }
                                                                            },
                                                                            book -> {
                                                                                refresh();
                                                                                open(book);
                                                                            }))
                                                    .show();
                                    })
                            .setNegativeButton(getString(R.string.action_close), null)
                            .setNeutralButton(
                                    parents.isEmpty()
                                            ? getString(R.string.ui_root)
                                            : getString(R.string.ui_up),
                                    (dialog, which) -> {
                                        if (!parents.isEmpty()) browse(dav, parents.pop(), parents);
                                    })
                            .show();
                });
    }

    private void importUris(List<Uri> uris) {
        task(
                getString(R.string.ui_importing),
                () -> {
                    Library.Book last = null;
                    for (Uri uri : uris) last = Importer.fromUri(this, uri);
                    return last;
                },
                last -> {
                    refresh();
                    if (last != null && uris.size() == 1) open(last);
                });
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (DriveAuth.onActivityResult(
                this, request, result, data, this::driveConnected, error -> Ui.error(this, error)))
            return;
        if (result != RESULT_OK || data == null) return;
        if (request == OPEN) {
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                if (data.getClipData().getItemCount() > 100) {
                    Ui.error(
                            this,
                            new IOException(
                                    getString(R.string.ui_import_at_most_100_comics_at_a_time)));
                    return;
                }
                for (int i = 0; i < data.getClipData().getItemCount(); i++)
                    uris.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) uris.add(data.getData());
            if (!uris.isEmpty()) importUris(uris);
        } else if (data.getData() != null && request == EXPORT)
            task(
                    getString(R.string.ui_exporting),
                    () -> {
                        byte[] bytes = Progress.encode(Library.get(this).states());
                        try (OutputStream out =
                                getContentResolver().openOutputStream(data.getData(), "wt")) {
                            if (out == null) throw new IOException("Cannot create the document");
                            out.write(bytes);
                        }
                        return null;
                    },
                    ignored -> Ui.toast(this, getString(R.string.ui_progress_exported)));
        else if (data.getData() != null && request == RESTORE)
            task(
                    getString(R.string.ui_importing_progress),
                    () -> {
                        try (InputStream in =
                                getContentResolver().openInputStream(data.getData())) {
                            if (in == null) throw new IOException("Cannot open the document");
                            Library.get(this)
                                    .merge(
                                            Progress.decode(
                                                    ComicFiles.read(in, Progress.MAX_BYTES)));
                        }
                        return null;
                    },
                    ignored -> {
                        refresh();
                        Cloud.schedule(this);
                    });
    }

    private interface Work<T> {
        T run() throws Exception;
    }

    private <T> void downloadTask(String label, Work<T> work, java.util.function.Consumer<T> done) {
        if (!Cloud.downloadAllowed(this)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.download_settings)
                    .setMessage(R.string.download_network_required)
                    .setPositiveButton(
                            R.string.settings_title, (dialog, which) -> downloadSettings())
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
            return;
        }
        task(
                label,
                () -> {
                    Thread owner = Thread.currentThread();
                    java.util.concurrent.atomic.AtomicBoolean blocked =
                            new java.util.concurrent.atomic.AtomicBoolean();
                    android.net.ConnectivityManager network =
                            getSystemService(android.net.ConnectivityManager.class);
                    class DownloadNetwork extends android.net.ConnectivityManager.NetworkCallback {
                        private boolean active = true;

                        private synchronized void check() {
                            if (active && !Cloud.downloadAllowed(MainActivity.this)) {
                                blocked.set(true);
                                owner.interrupt();
                            }
                        }

                        synchronized void stop() {
                            active = false;
                        }

                        @Override
                        public void onCapabilitiesChanged(
                                android.net.Network value,
                                android.net.NetworkCapabilities capabilities) {
                            check();
                        }

                        @Override
                        public void onLost(android.net.Network value) {
                            check();
                        }
                    }
                    DownloadNetwork watch = new DownloadNetwork();
                    network.registerDefaultNetworkCallback(watch);
                    try {
                        if (!Cloud.downloadAllowed(this))
                            throw new IOException(getString(R.string.download_network_required));
                        return work.run();
                    } catch (Exception error) {
                        if (blocked.get())
                            throw new IOException(
                                    getString(R.string.download_network_changed), error);
                        throw error;
                    } finally {
                        watch.stop();
                        network.unregisterNetworkCallback(watch);
                        Thread.interrupted();
                    }
                },
                done);
    }

    private <T> void task(String label, Work<T> work, java.util.function.Consumer<T> done) {
        task(
                label,
                work,
                done,
                error -> {
                    Ui.error(this, error);
                    refresh();
                });
    }

    private <T> void task(
            String label,
            Work<T> work,
            java.util.function.Consumer<T> done,
            java.util.function.Consumer<Exception> failed) {
        if (busy) {
            Ui.toast(this, getString(R.string.ui_wait_for_the_current_operation_to_finish));
            return;
        }
        busy = true;
        covers.setPaused(true);
        status.setVisibility(View.VISIBLE);
        status.setText(label);
        for (View action : actions) action.setEnabled(false);
        worker.execute(
                () -> {
                    CoverJob.pause(this);
                    try {
                        T result = work.run();
                        runOnUiThread(
                                () -> {
                                    if (isDestroyed()) return;
                                    busy = false;
                                    covers.setPaused(!resumed);
                                    for (View action : actions) action.setEnabled(true);
                                    updateStatus();
                                    done.accept(result);
                                });
                    } catch (Exception e) {
                        runOnUiThread(
                                () -> {
                                    if (isDestroyed()) return;
                                    busy = false;
                                    covers.setPaused(!resumed);
                                    for (View action : actions) action.setEnabled(true);
                                    updateStatus();
                                    failed.accept(e);
                                });
                    } finally {
                        CoverJob.resume(this);
                    }
                });
    }

    @android.annotation.SuppressLint(
            "GestureBackNavigation") // API 26–32; newer Android uses the dispatcher above.
    @Override
    public void onBackPressed() {
        navigateBack();
    }

    private void navigateBack() {
        if (search.getVisibility() == View.VISIBLE) {
            search.getText().clear();
            search.setVisibility(View.GONE);
        } else if (recent || downloadsOnly) {
            recent = downloadsOnly = false;
            showLibrary(0);
        } else if (!home && !megaParents.isEmpty()) upMega();
        else if (!home) showHome();
        else finish();
    }

    private void upMega() {
        if (busy || megaParents.isEmpty()) return;
        covers.cancelPending();
        MegaClient.Node parent = megaParents.pop();
        megaFolder = parent.handle();
        megaName = parent.name();
        catalogStale = true;
        catalogError = "";
        autoRefreshAttempted = false;
        remoteBooks.clear();
        showLibrary(selection);
        refresh();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("home", home);
        out.putBoolean("recent", recent);
        out.putBoolean("downloads", downloadsOnly);
        out.putInt("selection", selection);
        out.putLong("mega_folder", megaFolder);
        out.putString("mega_name", megaName);
        out.putBoolean("mega_attempted", autoRefreshAttempted);
        out.putBoolean("mega_stale", catalogStale);
        out.putString("mega_error", catalogError);
        out.putLongArray(
                "mega_parents", megaParents.stream().mapToLong(MegaClient.Node::handle).toArray());
        out.putStringArrayList(
                "mega_parent_names",
                new ArrayList<>(megaParents.stream().map(MegaClient.Node::name).toList()));
        super.onSaveInstanceState(out);
    }

    @Override
    protected void onDestroy() {
        if (settingsDialog != null) settingsDialog.dismiss();
        updater.close();
        covers.close();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        if (resumed) MegaClient.instance(this).browsing(false);
        resumed = false;
        updater.onPause();
        covers.setPaused(true);
        if (!busy && !CoverJob.running() && MegaClient.instance(this).loggedIn())
            worker.execute(
                    () -> {
                        try {
                            MegaClient.instance(this).suspend();
                        } catch (Exception e) {
                            android.util.Log.w(
                                    "ComicReader", "MEGA could not disconnect on pause", e);
                        }
                    });
        super.onPause();
    }
}
