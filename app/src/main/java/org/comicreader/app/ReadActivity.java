package org.comicreader.app;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

import java.io.IOException;
import java.util.concurrent.*;

/** Private entry point for widget and next-issue actions. */
public final class ReadActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Future<?> task;
    private TextView status;
    private ProgressBar progress;
    private Button library;
    private boolean active, done, failed;
    private volatile boolean stopped;
    private ReadingShelf.Item result;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.attach(base));
    }

    @Override
    public void onCreate(Bundle saved) {
        Ui.applyTheme(this);
        super.onCreate(saved);
        Ui.bars(this);
        LinearLayout root = Ui.column(this);
        root.setGravity(Gravity.CENTER);
        Ui.insets(root);
        setContentView(root);
        status = Ui.text(this, getString(R.string.reading_opening), 16);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));
        progress = new ProgressBar(this);
        progress.setIndeterminateTintList(ColorStateList.valueOf(Ui.accent(this)));
        LinearLayout.LayoutParams spinning =
                new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48));
        spinning.setMargins(0, Ui.dp(this, 24), 0, Ui.dp(this, 24));
        root.addView(progress, spinning);
        library =
                Ui.button(
                        this,
                        getString(R.string.library_title),
                        () -> {
                            startActivity(
                                    new Intent(this, MainActivity.class)
                                            .setAction("org.comicreader.app.LIBRARY")
                                            .addFlags(
                                                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                                                            | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                            finish();
                        });
        library.setVisibility(View.GONE);
        root.addView(library);
        root.addView(Ui.button(this, getString(R.string.action_cancel), this::finish));
        task =
                worker.submit(
                        () -> {
                            CoverJob.pause(this);
                            try {
                                ReadingShelf shelf = ReadingShelf.get(this);
                                ReadingShelf.Item item;
                                if ("org.comicreader.app.NEXT".equals(getIntent().getAction())) {
                                    String id = getIntent().getStringExtra("finished_book");
                                    if (id == null || !id.matches("[0-9a-f]{64}"))
                                        throw new IOException("Invalid completed comic");
                                    ReadingShelf.Item current =
                                            shelf.current(
                                                    id,
                                                    getIntent().getStringExtra("reading_location"));
                                    if (current == null || !current.completed())
                                        throw new IOException("The completed comic is unavailable");
                                    runOnUiThread(
                                            () -> status.setText(R.string.series_finding_next));
                                    item = ReadingShelf.next(current, shelf.items());
                                    if (item == null || item.book() == null)
                                        item =
                                                Cloud.download(
                                                        this,
                                                        () -> {
                                                            shelf.refresh(current.location());
                                                            return prepare(
                                                                    shelf,
                                                                    ReadingShelf.next(
                                                                            current,
                                                                            shelf.items()));
                                                        });
                                } else {
                                    String key = getIntent().getDataString();
                                    item =
                                            key == null
                                                            || key.length()
                                                                    > ReadingShelf.MAX_KEY_LENGTH
                                                    ? null
                                                    : shelf.find(key);
                                    if (item == null)
                                        throw new IOException(
                                                "The comic is no longer in the library");
                                    ReadingShelf.Item selected = item;
                                    if (item.book() == null)
                                        item = Cloud.download(this, () -> prepare(shelf, selected));
                                }
                                ReadingShelf.Item opened = item;
                                runOnUiThread(
                                        () -> {
                                            if (isDestroyed()) return;
                                            result = opened;
                                            done = true;
                                            complete();
                                        });
                            } catch (Exception error) {
                                runOnUiThread(
                                        () -> {
                                            if (isDestroyed()) return;
                                            failed = true;
                                            done = true;
                                            progress.setVisibility(View.GONE);
                                            status.setText(Ui.message(this, error));
                                            library.setVisibility(View.VISIBLE);
                                        });
                            } finally {
                                if (!stopped && MegaClient.instance(this).loggedIn())
                                    try {
                                        MegaClient.instance(this).suspend();
                                    } catch (Exception error) {
                                        android.util.Log.w(
                                                "ComicReader",
                                                "MEGA could not disconnect after opening a comic",
                                                error);
                                    }
                                CoverJob.resume(this);
                            }
                        });
    }

    private ReadingShelf.Item prepare(ReadingShelf shelf, ReadingShelf.Item item) throws Exception {
        if (item == null || item.book() != null) return item;
        runOnUiThread(() -> status.setText(R.string.ui_downloading_for_reading));
        Library.Book book = shelf.download(item.location());
        return new ReadingShelf.Item(
                item.location(), book, Library.get(this).state(book.id()), false);
    }

    private void complete() {
        if (!active || !done || failed || isFinishing()) return;
        if (result != null) startActivity(ReadingShelf.open(this, result));
        else Ui.toast(this, getString(R.string.series_finished));
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!active) ComicWidget.foreground(this, true);
        active = true;
        complete();
    }

    @Override
    protected void onPause() {
        if (active) ComicWidget.foreground(this, false);
        active = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopped = true;
        if (task != null) task.cancel(true);
        worker.shutdownNow();
        super.onDestroy();
    }
}
