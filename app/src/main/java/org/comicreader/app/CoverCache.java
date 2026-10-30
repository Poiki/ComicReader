package org.comicreader.app;

import android.app.Activity;
import android.content.*;
import android.graphics.Bitmap;
import android.net.*;
import android.os.*;
import android.widget.ImageView;

import java.io.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;

/** Visible-cell requests yield the background batch and share its application-owned cache. */
final class CoverCache implements AutoCloseable {
    static final class CoverView extends ImageView {
        volatile String bookId;

        CoverView(Context context) {
            super(context);
        }
    }

    private final Activity activity;
    private final CoverStore store;
    private final boolean production;
    private volatile boolean paused;
    private volatile Thread remoteThread;
    private FileObserver observer;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.atomic.AtomicBoolean refreshQueued =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final Set<String> failed = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(32),
                    new ThreadPoolExecutor.DiscardOldestPolicy());
    private final ConnectivityManager.NetworkCallback network =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(Network value, NetworkCapabilities capabilities) {
                    if (!CoverStore.remoteAllowed(activity)) cancelPending();
                }

                @Override
                public void onLost(Network value) {
                    cancelPending();
                }
            };
    private final BroadcastReceiver battery =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (!CoverStore.remoteAllowed(context)) cancelPending();
                }
            };

    CoverCache(Activity activity) {
        this(activity, null);
    }

    CoverCache(Activity activity, CoverStore.RemoteLoader loader) {
        this.activity = activity;
        production = loader == null;
        store = production ? CoverStore.get(activity) : new CoverStore(activity, loader);
        if (production) {
            activity.getSystemService(ConnectivityManager.class)
                    .registerDefaultNetworkCallback(network);
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_LOW);
            filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33)
                activity.registerReceiver(battery, filter, Context.RECEIVER_NOT_EXPORTED);
            else activity.registerReceiver(battery, filter);
        }
    }

    void observe(Runnable changed) {
        if (observer != null) observer.stopWatching();
        try {
            store.ensureDirectory();
        } catch (IOException error) {
            android.util.Log.w("ComicReader", "Cannot watch cover cache", error);
            return;
        }
        observer =
                new FileObserver(store.directory.getAbsolutePath(), FileObserver.MOVED_TO) {
                    @Override
                    public void onEvent(int event, String path) {
                        if (path == null || !path.endsWith(".jpg")) return;
                        failed.remove(path.substring(0, path.length() - 4));
                        if (refreshQueued.compareAndSet(false, true))
                            ui.postDelayed(
                                    () -> {
                                        refreshQueued.set(false);
                                        if (!paused && !activity.isDestroyed()) changed.run();
                                    },
                                    150);
                    }
                };
        observer.startWatching();
    }

    void bind(CoverView view, Library.Book book) {
        bind(view, book.id(), book, null, null);
    }

    void bind(CoverView view, String account, MegaClient.Node node) {
        bind(view, CoverStore.key(account, node), null, account, node);
    }

    private void bind(
            CoverView view, String key, Library.Book book, String account, MegaClient.Node node) {
        view.bookId = key;
        Bitmap bitmap = store.cached(key);
        if (bitmap != null) {
            view.setImageBitmap(bitmap);
            return;
        }
        android.graphics.drawable.Drawable icon =
                activity.getDrawable(node == null ? R.drawable.icon_book : R.drawable.icon_cloud)
                        .mutate();
        icon.setTint(Ui.foreground(activity));
        view.setImageDrawable(icon);
        if (worker.isShutdown() || failed.contains(key)) return;
        worker.getQueue().removeIf(work -> work instanceof Load load && !load.wanted());
        worker.execute(new Load(view, key, book, account, node));
    }

    private final class Load implements Runnable {
        private final WeakReference<CoverView> target;
        private final String key, account;
        private final Library.Book book;
        private final MegaClient.Node node;

        Load(CoverView view, String key, Library.Book book, String account, MegaClient.Node node) {
            target = new WeakReference<>(view);
            this.key = key;
            this.book = book;
            this.account = account;
            this.node = node;
        }

        boolean wanted() {
            CoverView view = target.get();
            return view != null && key.equals(view.bookId);
        }

        public void run() {
            if (!wanted() || paused || worker.isShutdown()) return;
            boolean priority = production && !store.contains(key);
            if (priority) CoverJob.pause(activity);
            try {
                if (node != null) {
                    remoteThread = Thread.currentThread();
                    if (paused) return;
                }
                Bitmap loaded = store.load(key, book, account, node);
                if (loaded == null || worker.isShutdown()) return;
                activity.runOnUiThread(
                        () -> {
                            CoverView view = target.get();
                            if (view != null
                                    && key.equals(view.bookId)
                                    && !paused
                                    && !activity.isDestroyed()) view.setImageBitmap(loaded);
                        });
            } catch (InterruptedException expected) {
                // Paused cells are rebound on resume; the application batch keeps its own lifetime.
            } catch (Exception | OutOfMemoryError error) {
                if (!Thread.currentThread().isInterrupted() || error instanceof OutOfMemoryError) {
                    failed.add(key);
                    android.util.Log.w("ComicReader", "Could not load the cover: " + key, error);
                }
            } finally {
                remoteThread = null;
                Thread.interrupted();
                if (priority) CoverJob.resume(activity);
            }
        }
    }

    void setPaused(boolean value) {
        paused = value;
        if (value) {
            cancelPending();
            if (observer != null) observer.stopWatching();
        } else {
            failed.clear();
            if (observer != null) observer.startWatching();
        }
    }

    void cancelPending() {
        worker.getQueue().clear();
        Thread active = remoteThread;
        if (active != null) active.interrupt();
    }

    @Override
    public void close() {
        if (worker.isShutdown()) return;
        setPaused(true);
        ui.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        if (production) {
            activity.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(network);
            activity.unregisterReceiver(battery);
        }
    }
}
