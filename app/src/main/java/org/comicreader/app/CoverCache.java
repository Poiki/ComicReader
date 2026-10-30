package org.comicreader.app;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.net.*;
import android.os.*;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;

/** Visible covers share a bounded worker, memory cache and disposable disk cache. */
final class CoverCache implements AutoCloseable {
    interface RemoteLoader {
        Bitmap load(String account, MegaClient.Node node) throws Exception;
    }

    static final class CoverView extends ImageView {
        volatile String bookId;

        CoverView(Context context) {
            super(context);
        }
    }

    private final Activity activity;
    private final RemoteLoader remoteLoader;
    private final File directory;
    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(
                        Network network, NetworkCapabilities capabilities) {
                    if (!remoteAllowed(activity)) cancelPending();
                }

                @Override
                public void onLost(Network network) {
                    cancelPending();
                }
            };
    private final BroadcastReceiver batteryReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (!remoteAllowed(context)) cancelPending();
                }
            };
    private volatile boolean paused;
    private volatile Thread remoteThread;
    private final Set<String> failed = ConcurrentHashMap.newKeySet();
    private final LruCache<String, Bitmap> memory =
            new LruCache<>(8 * 1024 * 1024) {
                @Override
                protected int sizeOf(String id, Bitmap bitmap) {
                    return bitmap.getAllocationByteCount();
                }
            };
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(32),
                    new ThreadPoolExecutor.DiscardOldestPolicy());

    CoverCache(Activity activity) {
        this(activity, null);
    }

    CoverCache(Activity activity, RemoteLoader remoteLoader) {
        this.activity = activity;
        this.remoteLoader = remoteLoader;
        directory = new File(activity.getCacheDir(), "covers-v1");
        if (remoteLoader == null) {
            activity.getSystemService(ConnectivityManager.class)
                    .registerDefaultNetworkCallback(networkCallback);
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_LOW);
            filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33)
                activity.registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else activity.registerReceiver(batteryReceiver, filter);
        }
    }

    void bind(CoverView view, Library.Book book) {
        bind(view, book.id(), book, null, null);
    }

    void bind(CoverView view, String account, MegaClient.Node node) {
        if (!account.matches("[A-Za-z0-9_-]{1,64}")
                || node.directory()
                || node.size() < 0
                || node.size() > ComicFiles.MAX_COMIC)
            throw new IllegalArgumentException("Invalid remote cover identity");
        bind(
                view,
                "mega_" + account + "_" + node.handle() + "_" + node.size(),
                null,
                account,
                node);
    }

    private void bind(
            CoverView view, String key, Library.Book book, String account, MegaClient.Node node) {
        view.bookId = key;
        Bitmap cached = memory.get(key);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        android.graphics.drawable.Drawable placeholder =
                activity.getDrawable(node == null ? R.drawable.icon_book : R.drawable.icon_cloud)
                        .mutate();
        placeholder.setTint(Ui.foreground(activity));
        view.setImageDrawable(placeholder);
        if (worker.isShutdown() || failed.contains(key)) return;
        worker.getQueue().removeIf(work -> work instanceof Load load && !load.wanted());
        worker.execute(new Load(view, key, book, account, node));
    }

    static boolean remoteAllowed(Context context) {
        String mode = context.getSharedPreferences("appearance", 0).getString("covers", "wifi");
        if (mode.equals("off") || context.getSystemService(PowerManager.class).isPowerSaveMode())
            return false;
        Intent battery =
                context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null && battery.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, false))
            return false;
        ConnectivityManager network = context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities capabilities =
                network.getNetworkCapabilities(network.getActiveNetwork());
        return Cloud.networkAllowed(capabilities, mode.equals("any"));
    }

    private Bitmap fetch(String account, MegaClient.Node node) throws Exception {
        if (remoteLoader != null) return remoteLoader.load(account, node);
        MegaClient mega = MegaClient.instance(activity);
        if (!mega.loggedIn() || !remoteAllowed(activity) || !account.equals(mega.account()))
            return null;
        File temporary = mega.thumbnail(node);
        boolean image = temporary != null;
        if (!image) temporary = mega.download(node);
        try {
            if (image) return readImage(temporary);
            String format =
                    switch (ComicFiles.extension(node.name())) {
                        case "pdf" -> "pdf";
                        case "cbr", "rar" -> "cbr";
                        default -> "cbz";
                    };
            try (PageSource source = new PageSource(temporary, format)) {
                return source.render(0, 192, 288);
            }
        } finally {
            if (temporary.exists() && !temporary.delete())
                android.util.Log.w("ComicReader", "Could not remove a temporary cover download");
        }
    }

    private static Bitmap readImage(File file) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (file.length() > 5 * 1024 * 1024 || options.outWidth <= 0 || options.outHeight <= 0)
            throw new IOException("Invalid cover image");
        options.inJustDecodeBounds = false;
        options.inSampleSize = ComicFiles.sampleSize(options.outWidth, options.outHeight, 192, 288);
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) throw new IOException("Cannot decode the cover image");
        return bitmap;
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
            if (!wanted() || worker.isShutdown()) return;
            try {
                Bitmap bitmap = memory.get(key);
                File cached = new File(directory, key + ".jpg");
                if (bitmap == null && cached.isFile()) {
                    try {
                        bitmap = readImage(cached);
                        if (!cached.setLastModified(System.currentTimeMillis()))
                            android.util.Log.w("ComicReader", "Could not update cover cache age");
                    } catch (IOException error) {
                        android.util.Log.w(
                                "ComicReader", "Regenerating a damaged cached cover", error);
                        if (!cached.delete())
                            throw new IOException("Cannot remove damaged cover", error);
                    }
                }
                if (bitmap == null) {
                    if (book != null) {
                        try (PageSource source =
                                new PageSource(Library.get(activity).file(book), book.format())) {
                            bitmap = source.render(0, 192, 288);
                        }
                    } else {
                        remoteThread = Thread.currentThread();
                        if (paused || worker.isShutdown()) return;
                        bitmap = fetch(account, node);
                        if (bitmap == null) return;
                    }
                    float scale =
                            Math.min(
                                    1f,
                                    Math.min(192f / bitmap.getWidth(), 288f / bitmap.getHeight()));
                    if (scale < 1f) {
                        Bitmap thumbnail =
                                Bitmap.createScaledBitmap(
                                        bitmap,
                                        Math.max(1, (int) (bitmap.getWidth() * scale)),
                                        Math.max(1, (int) (bitmap.getHeight() * scale)),
                                        true);
                        bitmap.recycle();
                        bitmap = thumbnail;
                    }
                    if (worker.isShutdown() || Thread.currentThread().isInterrupted()) return;
                    save(cached, bitmap);
                }
                if (worker.isShutdown()) return;
                memory.put(key, bitmap);
                Bitmap loaded = bitmap;
                activity.runOnUiThread(
                        () -> {
                            CoverView view = target.get();
                            if (view != null && key.equals(view.bookId) && !activity.isDestroyed())
                                view.setImageBitmap(loaded);
                        });
            } catch (InterruptedException error) {
                // Cancellation yields the SDK to foreground work and is retried on the next bind.
            } catch (Exception | OutOfMemoryError error) {
                if (!Thread.currentThread().isInterrupted() || error instanceof OutOfMemoryError) {
                    failed.add(key);
                    android.util.Log.w("ComicReader", "Could not load the cover: " + key, error);
                }
            } finally {
                remoteThread = null;
                Thread.interrupted();
            }
        }
    }

    private void save(File destination, Bitmap bitmap) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot create cover cache");
        File temporary = File.createTempFile("cover-", ".tmp", directory);
        try {
            try (OutputStream out = new FileOutputStream(temporary)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out))
                    throw new IOException("Cannot encode cover");
            }
            if (!temporary.renameTo(destination)) throw new IOException("Cannot save cover cache");
            trim(directory);
        } finally {
            if (temporary.exists() && !temporary.delete())
                android.util.Log.w("ComicReader", "Could not remove a temporary cover image");
        }
    }

    static void trim(File directory) throws IOException {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".jpg"));
        if (files == null) throw new IOException("Cannot list cover cache");
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        long bytes = 0;
        int kept = 0;
        for (File file : files) {
            if (bytes + file.length() <= 32L * 1024 * 1024 && kept < 2000) {
                bytes += file.length();
                kept++;
            } else if (!file.delete()) throw new IOException("Cannot evict an old cover");
        }
    }

    void setPaused(boolean paused) {
        this.paused = paused;
        if (paused) cancelPending();
        else failed.clear();
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
        worker.shutdownNow();
        if (remoteLoader == null) {
            activity.getSystemService(ConnectivityManager.class)
                    .unregisterNetworkCallback(networkCallback);
            activity.unregisterReceiver(batteryReceiver);
        }
        memory.evictAll();
    }
}
