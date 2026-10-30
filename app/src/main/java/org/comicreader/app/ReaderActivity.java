package org.comicreader.app;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.os.*;
import android.util.LruCache;
import android.view.*;
import android.widget.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public final class ReaderActivity extends Activity implements PageView.Actions {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.attach(base));
    }

    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LruCache<Integer, Bitmap> cache =
            new LruCache<>(24 * 1024 * 1024) {
                @Override
                protected int sizeOf(Integer page, Bitmap bitmap) {
                    return bitmap.getAllocationByteCount();
                }
            };
    private PageView image;
    private LinearLayout top, bottom, positionBar, readerTools;
    private TextView label, title, headerPosition;
    private SeekBar seek;
    private Button bookmark;
    private Library.Book book;
    private Progress state;
    private PageSource source;
    private int page, requested;
    private volatile int generation;
    private volatile boolean active;
    private boolean dirty, controls = true, ready, sourceReady, pageDirty, completionDirty;
    private boolean trackingSeek;
    private int renderedWidth, renderedHeight;
    private final TreeSet<Integer> bookmarks = new TreeSet<>();
    private final TreeSet<Integer> addedMarks = new TreeSet<>(), removedMarks = new TreeSet<>();
    private final Runnable saveLater = this::save;

    @Override
    public void onCreate(Bundle saved) {
        Ui.applyTheme(this);
        super.onCreate(saved);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(android.graphics.Color.BLACK);
        Ui.insets(root);
        setContentView(root);
        getWindow().setStatusBarColor(android.graphics.Color.BLACK);
        getWindow().setNavigationBarColor(android.graphics.Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(0);
        image = new PageView(this, this);
        root.addView(image, new FrameLayout.LayoutParams(-1, -1));
        image.addOnLayoutChangeListener(
                (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    if (right > left
                            && bottom > top
                            && (right - left != oldRight - oldLeft
                                    || bottom - top != oldBottom - oldTop)) request(requested);
                });
        top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setMinimumHeight(Ui.dp(this, 56));
        top.setBackgroundColor(android.graphics.Color.BLACK);
        top.addView(
                readerIcon(R.drawable.icon_back, R.string.action_back, this::finish),
                new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 56)));
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        title = Ui.text(this, getString(R.string.ui_opening), 16);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTextColor(android.graphics.Color.WHITE);
        title.setPadding(0, 0, 0, Ui.dp(this, 2));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.addView(title);
        headerPosition = Ui.text(this, "", 12);
        headerPosition.setTextColor(0xffbbbbbb);
        headerPosition.setPadding(0, 0, 0, 0);
        heading.addView(headerPosition);
        top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        bookmark = readerIcon(R.drawable.icon_bookmark, R.string.ui_bookmark, this::toggleBookmark);
        top.addView(bookmark, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 56)));
        top.addView(
                readerIcon(
                        R.drawable.icon_brightness,
                        R.string.ui_window_brightness,
                        this::brightness),
                new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 56)));
        top.addView(
                readerIcon(R.drawable.icon_settings, R.string.settings_title, this::menu),
                new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 56)));
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        bottom = Ui.column(this);
        bottom.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), Ui.dp(this, 4));
        bottom.setBackgroundColor(android.graphics.Color.BLACK);
        positionBar = new LinearLayout(this);
        positionBar.setGravity(Gravity.CENTER_VERTICAL);
        seek = new SeekBar(this);
        seek.setContentDescription(getString(R.string.ui_go_to_page));
        positionBar.addView(seek, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1));
        label = Ui.text(this, "", 12);
        label.setTextColor(android.graphics.Color.WHITE);
        label.setGravity(Gravity.CENTER);
        positionBar.addView(label);
        bottom.addView(positionBar);
        readerTools = new LinearLayout(this);
        readerTools.setGravity(Gravity.CENTER_VERTICAL);
        bottom.addView(readerTools, new LinearLayout.LayoutParams(-1, Ui.dp(this, 68)));
        buildReaderTools();
        seek.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                        if (user && state != null) {
                            label.setText(
                                    getString(
                                            R.string.reader_go_to_position,
                                            progress + 1,
                                            state.pages));
                            if (!trackingSeek) request(progress);
                        }
                    }

                    public void onStartTrackingTouch(SeekBar bar) {
                        trackingSeek = true;
                    }

                    public void onStopTrackingTouch(SeekBar bar) {
                        trackingSeek = false;
                        request(bar.getProgress());
                    }
                });
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        top.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> layoutPage());
        bottom.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> layoutPage());
        Library.IO.execute(
                () -> {
                    try {
                        String id = getIntent().getStringExtra("id");
                        Library library = Library.get(this);
                        Library.Book loadedBook = id == null ? null : library.book(id);
                        if (loadedBook == null)
                            throw new IOException("The comic is no longer in the library");
                        Progress loaded = library.state(loadedBook.id());
                        if (loaded == null)
                            throw new IOException("No progress record was found for this file");
                        runOnUiThread(
                                () -> {
                                    if (isDestroyed()) return;
                                    book = loadedBook;
                                    state = loaded;
                                    settings().edit().putString("last_book", book.id()).apply();
                                    for (int value : loaded.bookmarks()) bookmarks.add(value);
                                    page = requested = loaded.page;
                                    if (saved != null && saved.containsKey("page")) {
                                        page =
                                                requested =
                                                        Math.max(
                                                                0,
                                                                Math.min(
                                                                        loaded.pages - 1,
                                                                        saved.getInt("page")));
                                        int[] marks = saved.getIntArray("marks");
                                        bookmarks.clear();
                                        if (marks != null)
                                            for (int mark : marks)
                                                if (mark >= 0 && mark < loaded.pages)
                                                    bookmarks.add(mark);
                                        boolean completed =
                                                saved.getBoolean("completed", loaded.completed);
                                        state =
                                                new Progress(
                                                        loaded.id,
                                                        loaded.page,
                                                        loaded.pages,
                                                        completed,
                                                        loaded.favorite,
                                                        loaded.bookmarks(),
                                                        loaded.revision,
                                                        loaded.device);
                                        dirty =
                                                page != loaded.page
                                                        || completed != loaded.completed
                                                        || !Arrays.equals(
                                                                marks, loaded.bookmarks());
                                        pageDirty = page != loaded.page;
                                        completionDirty = completed != loaded.completed;
                                        for (int mark : bookmarks)
                                            if (!loaded.bookmarked(mark)) addedMarks.add(mark);
                                        for (int mark : loaded.bookmarks())
                                            if (!bookmarks.contains(mark)) removedMarks.add(mark);
                                    }
                                    seek.setMax(loaded.pages - 1);
                                    title.setText(book.title());
                                    worker.execute(
                                            () -> {
                                                try {
                                                    source =
                                                            new PageSource(
                                                                    library.file(loadedBook),
                                                                    loadedBook.format());
                                                    if (loaded.pages != source.count())
                                                        throw new IOException(
                                                                "Progress does not match the file's page count");
                                                    runOnUiThread(
                                                            () -> {
                                                                if (!isDestroyed()) {
                                                                    ready = sourceReady = true;
                                                                    if (active) request(page);
                                                                }
                                                            });
                                                } catch (Exception e) {
                                                    runOnUiThread(() -> Ui.error(this, e));
                                                }
                                            });
                                });
                    } catch (Exception e) {
                        runOnUiThread(
                                () -> {
                                    Ui.error(this, e);
                                    label.setText(getString(R.string.ui_could_not_open_the_comic));
                                });
                    }
                });
    }

    private Button readerIcon(int icon, int label, Runnable action) {
        Button button = Ui.icon(this, icon, getString(label), action);
        button.getCompoundDrawables()[0].setTint(android.graphics.Color.WHITE);
        return button;
    }

    private void buildReaderTools() {
        readerTools.removeAllViews();
        readerTool(
                R.drawable.icon_direction,
                R.string.reader_direction,
                settings().getBoolean("manga", false),
                () -> toggleSetting("manga"));
        readerTool(
                R.drawable.icon_search,
                R.string.reader_zoom,
                settings().getBoolean("width", false),
                () -> toggleSetting("width"));
        readerTool(R.drawable.icon_previous, R.string.action_previous, false, () -> advance(-1));
        FrameLayout nextSlot = new FrameLayout(this);
        Button next = readerIcon(R.drawable.icon_next, R.string.action_next, () -> advance(1));
        android.graphics.drawable.GradientDrawable outline =
                Ui.background(0xff0a0d10, Ui.dp(this, 28));
        outline.setStroke(Ui.dp(this, 1), Ui.accent(this));
        next.setBackground(
                new android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(0x3300b0f0), outline, null));
        nextSlot.addView(
                next,
                new FrameLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56), Gravity.CENTER));
        readerTools.addView(nextSlot, new LinearLayout.LayoutParams(0, -1, 1));
        readerTool(
                R.drawable.icon_volume,
                R.string.reader_volume,
                settings().getBoolean("volume", false),
                () -> toggleSetting("volume"));
        readerTool(R.drawable.icon_more, R.string.reader_more, false, this::menu);
    }

    private void readerTool(int icon, int title, boolean selected, Runnable action) {
        readerTools.addView(
                Ui.destination(this, icon, getString(title), selected, true, action),
                new LinearLayout.LayoutParams(0, -1, 1));
    }

    private void toggleSetting(String key) {
        settings().edit().putBoolean(key, !settings().getBoolean(key, false)).apply();
        buildReaderTools();
        applySettings();
        if (state != null) {
            updateLabel();
            if (key.equals("width")) request(page);
        }
    }

    private void layoutPage() {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) image.getLayoutParams();
        int topHeight = controls ? top.getHeight() : 0,
                bottomHeight = controls ? bottom.getHeight() : 0;
        if (params.topMargin == topHeight && params.bottomMargin == bottomHeight) return;
        params.topMargin = topHeight;
        params.bottomMargin = bottomHeight;
        image.setLayoutParams(params);
    }

    private SharedPreferences settings() {
        return getSharedPreferences("reader", 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = true;
        applySettings();
        if (state != null && sourceReady) {
            if (dirty) {
                ready = true;
                request(page);
            } else {
                ready = false;
                int ticket = ++generation;
                Library.IO.execute(
                        () -> {
                            try {
                                Progress loaded = Library.get(this).state(book.id());
                                runOnUiThread(
                                        () -> {
                                            if (!active || isDestroyed() || ticket != generation)
                                                return;
                                            ready = true;
                                            state = loaded;
                                            bookmarks.clear();
                                            for (int mark : loaded.bookmarks()) bookmarks.add(mark);
                                            page = requested = loaded.page;
                                            request(page);
                                        });
                            } catch (Exception e) {
                                runOnUiThread(
                                        () -> {
                                            ready = true;
                                            Ui.error(this, e);
                                        });
                            }
                        });
            }
        }
    }

    private void applySettings() {
        positionBar.setVisibility(
                settings().getBoolean("progress", true) ? View.VISIBLE : View.GONE);
        if (settings().getBoolean("keep_screen", false))
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void turn(int direction) {
        advance(settings().getBoolean("manga", false) ? -direction : direction);
    }

    @Override
    public void advance(int direction) {
        if (state == null) return;
        int next = requested + direction;
        if (next < 0 || next >= state.pages) {
            Ui.toast(
                    this,
                    next < 0
                            ? getString(R.string.ui_first_page)
                            : getString(R.string.ui_last_page));
            return;
        }
        request(next);
    }

    private void request(int number) {
        if (state == null || !ready || !active || number < 0 || number >= state.pages) return;
        int direction = number < requested ? -1 : 1;
        requested = number;
        int ticket = ++generation;
        int width = Math.max(1, image.getWidth()), height = Math.max(1, image.getHeight());
        if (width == 1 || height == 1) return;
        label.setText(getString(R.string.reader_loading_position, number + 1, state.pages));
        // Only one pending render: rapid swipes must not build an unbounded queue.
        worker.getQueue().removeIf(work -> work instanceof Render);
        worker.execute(new Render(number, ticket, width, height, direction));
    }

    private final class Render implements Runnable {
        final int number, ticket, width, height, direction;

        Render(int number, int ticket, int width, int height, int direction) {
            this.number = number;
            this.ticket = ticket;
            this.width = width;
            this.height = height;
            this.direction = direction;
        }

        public void run() {
            try {
                if (renderedWidth != width || renderedHeight != height) {
                    cache.evictAll();
                    renderedWidth = width;
                    renderedHeight = height;
                }
                Bitmap bitmap = cache.get(number);
                if (bitmap == null) {
                    bitmap = source.render(number, width, height);
                    cache.put(number, bitmap);
                }
                Bitmap rendered = bitmap;
                runOnUiThread(
                        () -> {
                            if (!active || isDestroyed() || ticket != generation) return;
                            if (page != number) {
                                dirty = true;
                                pageDirty = true;
                            }
                            page = number;
                            image.show(
                                    rendered,
                                    settings().getBoolean("width", false),
                                    getString(
                                            R.string.reader_page_description,
                                            page + 1,
                                            state.pages,
                                            book.title()));
                            seek.setProgress(page);
                            updateLabel();
                            handler.removeCallbacks(saveLater);
                            handler.postDelayed(saveLater, 600);
                        });
                PowerManager power = getSystemService(PowerManager.class);
                if (ticket == generation
                        && active
                        && !power.isPowerSaveMode()
                        && !getSystemService(ActivityManager.class).isLowRamDevice()) {
                    int next = number + direction;
                    if (next >= 0 && next < source.count() && cache.get(next) == null) {
                        try {
                            cache.put(next, source.render(next, width, height));
                        } catch (Exception | OutOfMemoryError error) {
                            // Speculative work cannot fail the page already displayed; the
                            // requested page reports its own error.
                            android.util.Log.w("ComicReader", "Could not preload a page", error);
                        }
                    }
                }
            } catch (Exception | OutOfMemoryError error) {
                cache.evictAll();
                runOnUiThread(
                        () -> {
                            if (active && ticket == generation) {
                                requested = page;
                                updateLabel();
                                Ui.error(ReaderActivity.this, error);
                            }
                        });
            }
        }
    }

    private void updateLabel() {
        label.setText(
                getString(R.string.reader_position, page + 1, state.pages)
                        + (settings().getBoolean("manga", false)
                                ? getString(R.string.reader_manga_indicator)
                                : ""));
        headerPosition.setText(getString(R.string.reader_position, page + 1, state.pages));
        bookmark.setContentDescription(
                getString(
                        bookmarks.contains(page)
                                ? R.string.ui_remove_bookmark
                                : R.string.ui_bookmark));
        bookmark.setSelected(bookmarks.contains(page));
        bookmark.getCompoundDrawables()[0].setTint(
                bookmarks.contains(page) ? Ui.accent(this) : android.graphics.Color.WHITE);
    }

    private void toggleBookmark() {
        if (state == null) return;
        if (bookmarks.remove(page)) {
            removedMarks.add(page);
            addedMarks.remove(page);
        } else {
            bookmarks.add(page);
            addedMarks.add(page);
            removedMarks.remove(page);
        }
        dirty = true;
        updateLabel();
        save();
    }

    private void save() {
        handler.removeCallbacks(saveLater);
        if (!dirty || state == null) return;
        Integer savedPage = pageDirty ? page : null;
        Boolean completed = completionDirty ? state.completed : null;
        int[] added = addedMarks.stream().mapToInt(Integer::intValue).toArray(),
                removed = removedMarks.stream().mapToInt(Integer::intValue).toArray();
        dirty = pageDirty = completionDirty = false;
        addedMarks.clear();
        removedMarks.clear();
        Library.IO.execute(
                () -> {
                    try {
                        Library.get(this)
                                .updateReading(book.id(), savedPage, completed, added, removed);
                    } catch (Exception e) {
                        runOnUiThread(
                                () -> {
                                    dirty = true;
                                    pageDirty |= savedPage != null;
                                    completionDirty |= completed != null;
                                    for (int mark : added)
                                        if (!removedMarks.contains(mark)) addedMarks.add(mark);
                                    for (int mark : removed)
                                        if (!addedMarks.contains(mark)) removedMarks.add(mark);
                                    Ui.error(this, e);
                                });
                    }
                });
    }

    @Override
    public void toggleControls() {
        controls = !controls;
        top.setVisibility(controls ? View.VISIBLE : View.GONE);
        bottom.setVisibility(controls ? View.VISIBLE : View.GONE);
        layoutPage();
        getWindow()
                .getDecorView()
                .setSystemUiVisibility(
                        controls
                                ? View.SYSTEM_UI_FLAG_VISIBLE
                                : View.SYSTEM_UI_FLAG_FULLSCREEN
                                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void menu() {
        if (state == null) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.ui_reader_settings)
                .setItems(
                        new String[] {
                            getString(R.string.ui_go_to_page),
                            getString(R.string.ui_bookmarks),
                            getString(
                                    settings().getBoolean("manga", false)
                                            ? R.string.reader_manga_on
                                            : R.string.reader_manga_off),
                            getString(
                                    settings().getBoolean("width", false)
                                            ? R.string.reader_fit_width
                                            : R.string.reader_fit_page),
                            getString(R.string.ui_lock_unlock_rotation),
                            getString(R.string.ui_window_brightness),
                            state.completed
                                    ? getString(R.string.ui_mark_as_unfinished)
                                    : getString(R.string.ui_mark_as_finished),
                            getString(
                                    settings().getBoolean("keep_screen", false)
                                            ? R.string.reader_keep_screen_on
                                            : R.string.reader_keep_screen_off)
                        },
                        (dialog, which) -> {
                            switch (which) {
                                case 0 -> {
                                    EditText input = Ui.input(this, "1 – " + state.pages);
                                    input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                                    new AlertDialog.Builder(this)
                                            .setTitle(getString(R.string.ui_go_to_page))
                                            .setView(input)
                                            .setNegativeButton(
                                                    getString(R.string.action_cancel), null)
                                            .setPositiveButton(
                                                    getString(R.string.ui_go),
                                                    (d, w) -> {
                                                        try {
                                                            int target =
                                                                    Integer.parseInt(
                                                                                    input.getText()
                                                                                            .toString())
                                                                            - 1;
                                                            if (target < 0 || target >= state.pages)
                                                                throw new NumberFormatException();
                                                            request(target);
                                                        } catch (NumberFormatException e) {
                                                            Ui.toast(
                                                                    this,
                                                                    getString(
                                                                            R.string
                                                                                    .reader_page_range,
                                                                            state.pages));
                                                        }
                                                    })
                                            .show();
                                }
                                case 1 -> {
                                    Integer[] pages = bookmarks.toArray(new Integer[0]);
                                    String[] labels = new String[pages.length];
                                    for (int i = 0; i < pages.length; i++)
                                        labels[i] =
                                                getString(
                                                        R.string.reader_page_number, pages[i] + 1);
                                    new AlertDialog.Builder(this)
                                            .setTitle(
                                                    pages.length == 0
                                                            ? getString(R.string.ui_no_bookmarks)
                                                            : getString(R.string.ui_bookmarks))
                                            .setItems(labels, (d, index) -> request(pages[index]))
                                            .setNegativeButton(
                                                    getString(R.string.action_close), null)
                                            .show();
                                }
                                case 2, 3, 7 -> {
                                    String key =
                                            which == 2
                                                    ? "manga"
                                                    : which == 3 ? "width" : "keep_screen";
                                    settings()
                                            .edit()
                                            .putBoolean(key, !settings().getBoolean(key, false))
                                            .apply();
                                    applySettings();
                                    buildReaderTools();
                                    request(page);
                                }
                                case 4 ->
                                        setRequestedOrientation(
                                                getRequestedOrientation()
                                                                == ActivityInfo
                                                                        .SCREEN_ORIENTATION_LOCKED
                                                        ? ActivityInfo
                                                                .SCREEN_ORIENTATION_UNSPECIFIED
                                                        : ActivityInfo.SCREEN_ORIENTATION_LOCKED);
                                case 5 -> brightness();
                                case 6 -> {
                                    state =
                                            new Progress(
                                                    state.id,
                                                    page,
                                                    state.pages,
                                                    !state.completed,
                                                    state.favorite,
                                                    state.bookmarks(),
                                                    state.revision,
                                                    state.device);
                                    dirty = completionDirty = true;
                                    save();
                                    Ui.toast(
                                            this,
                                            state.completed
                                                    ? getString(R.string.ui_marked_as_finished)
                                                    : getString(R.string.ui_marked_as_unfinished));
                                }
                            }
                        })
                .show();
    }

    private void brightness() {
        SeekBar brightness = new SeekBar(this);
        brightness.setMax(100);
        brightness.setContentDescription(
                getString(R.string.ui_brightness_zero_uses_the_system_setting));
        brightness.setProgress(
                getWindow().getAttributes().screenBrightness < 0
                        ? 0
                        : (int) (getWindow().getAttributes().screenBrightness * 100));
        brightness.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar bar, int value, boolean user) {
                        if (user) {
                            WindowManager.LayoutParams attributes = getWindow().getAttributes();
                            attributes.screenBrightness =
                                    value == 0 ? -1 : Math.max(0.02f, value / 100f);
                            getWindow().setAttributes(attributes);
                        }
                    }

                    public void onStartTrackingTouch(SeekBar bar) {}

                    public void onStopTrackingTouch(SeekBar bar) {}
                });
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.ui_brightness_0_system))
                .setView(brightness)
                .setPositiveButton(getString(R.string.action_done), null)
                .show();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent event) {
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            turn(1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
            turn(-1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_PAGE_DOWN
                || code == KeyEvent.KEYCODE_SPACE
                || code == KeyEvent.KEYCODE_VOLUME_DOWN && settings().getBoolean("volume", false)) {
            advance(1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_PAGE_UP
                || code == KeyEvent.KEYCODE_VOLUME_UP && settings().getBoolean("volume", false)) {
            advance(-1);
            return true;
        }
        return super.onKeyDown(code, event);
    }

    @Override
    protected void onPause() {
        active = false;
        generation++;
        save();
        worker.getQueue().removeIf(work -> work instanceof Render);
        Library.IO.execute(() -> Cloud.schedule(this));
        super.onPause();
    }

    @Override
    protected void onStop() {
        image.clear();
        worker.execute(cache::evictAll);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        worker.execute(
                () -> {
                    cache.evictAll();
                    if (source != null)
                        try {
                            source.close();
                        } catch (IOException e) {
                            android.util.Log.e("ComicReader", "Could not close the comic", e);
                        }
                });
        worker.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        if (state != null) {
            out.putInt("page", page);
            out.putIntArray("marks", bookmarks.stream().mapToInt(Integer::intValue).toArray());
            out.putBoolean("completed", state.completed);
        }
        super.onSaveInstanceState(out);
    }
}
